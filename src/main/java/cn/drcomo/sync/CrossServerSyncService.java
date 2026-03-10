package cn.drcomo.sync;

import cn.drcomo.corelib.async.AsyncTaskManager;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.database.HikariConnection;
import cn.drcomo.managers.RefactoredVariablesManager;
import cn.drcomo.storage.VariableMemoryStorage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 跨服变量同步服务（MySQL 事件表轮询）
 */
public class CrossServerSyncService {

    private final DebugUtil logger;
    private final AsyncTaskManager asyncTaskManager;
    private final HikariConnection database;
    private final RefactoredVariablesManager variablesManager;
    private final VariableMemoryStorage memoryStorage;
    private final CrossServerSyncConfig config;
    private final CrossServerSyncStore store;
    private final OnlinePlayerTracker onlineTracker;

    private volatile long lastEventId;
    private ScheduledFuture<?> pollTask;
    private ScheduledFuture<?> cleanupTask;
    private ScheduledFuture<?> flushTask;
    private volatile boolean warnedNotInitialized;

    public CrossServerSyncService(
            DebugUtil logger,
            AsyncTaskManager asyncTaskManager,
            HikariConnection database,
            RefactoredVariablesManager variablesManager,
            VariableMemoryStorage memoryStorage,
            CrossServerSyncConfig config,
            CrossServerSyncStore store,
            OnlinePlayerTracker onlineTracker
    ) {
        this.logger = logger;
        this.asyncTaskManager = asyncTaskManager;
        this.database = database;
        this.variablesManager = variablesManager;
        this.memoryStorage = memoryStorage;
        this.config = config;
        this.store = store;
        this.onlineTracker = onlineTracker;
        this.lastEventId = store.getLastEventId(config.getServerId());
    }


    public void start() {
        if (!config.isEnabled()) {
            logger.info("跨服同步已禁用");
            return;
        }
        if (!database.isMySQL()) {
            logger.warn("跨服同步仅支持 MySQL，当前数据库类型: " + database.getDatabaseType());
            return;
        }
        if (config.getServerId() == null || config.getServerId().isEmpty()) {
            logger.warn("跨服同步未配置 server-id，已跳过启动");
            return;
        }
        if (config.getServerId().length() > 64) {
            logger.warn("跨服同步 server-id 过长(>64)，已跳过启动");
            return;
        }
        if (!database.isConnectionValid()) {
            logger.warn("跨服同步启动失败：数据库连接不可用");
            return;
        }

        pollTask = asyncTaskManager.scheduleAtFixedRate(
                this::pollEvents,
                0,
                config.getPollIntervalMs(),
                TimeUnit.MILLISECONDS
        );

        cleanupTask = asyncTaskManager.scheduleAtFixedRate(
                this::cleanupEvents,
                config.getCleanupIntervalSeconds(),
                config.getCleanupIntervalSeconds(),
                TimeUnit.SECONDS
        );

        flushTask = asyncTaskManager.scheduleAtFixedRate(
                store::flush,
                30,
                30,
                TimeUnit.SECONDS
        );

        logger.info("跨服同步监听已启动: poll=" + config.getPollIntervalMs() + "ms, batch=" + config.getBatchSize());
    }

    public void stop() {
        if (pollTask != null) {
            pollTask.cancel(false);
        }
        if (cleanupTask != null) {
            cleanupTask.cancel(false);
        }
        if (flushTask != null) {
            flushTask.cancel(false);
        }
        store.flush();
    }

    private void pollEvents() {
        if (!variablesManager.isInitialized()) {
            if (!warnedNotInitialized) {
                logger.info("跨服同步等待变量管理器初始化完成...");
                warnedNotInitialized = true;
            }
            return;
        }
        if (warnedNotInitialized) {
            logger.info("变量管理器初始化完成，跨服同步开始轮询事件");
            warnedNotInitialized = false;
        }
        try (Connection conn = database.getConnection()) {
            String sql = "SELECT id, player_uuid, variable_key, value, updated_at, first_modified_at, scope, op " +
                    "FROM player_variable_change_events " +
                    "WHERE id > ? AND server_id <> ? AND created_at < ? " +
                    "ORDER BY id ASC LIMIT ?";
            List<EventRow> rows = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                try { stmt.setQueryTimeout(15); } catch (Throwable ignore) { }
                stmt.setLong(1, lastEventId);
                stmt.setString(2, config.getServerId());
                stmt.setLong(3, System.currentTimeMillis() - 3000L);
                stmt.setInt(4, config.getBatchSize());
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new EventRow(
                                rs.getLong("id"),
                                rs.getString("player_uuid"),
                                rs.getString("variable_key"),
                                rs.getString("value"),
                                rs.getLong("updated_at"),
                                rs.getLong("first_modified_at"),
                                rs.getString("scope"),
                                rs.getString("op")
                        ));
                    }
                }
            }

            if (rows.isEmpty()) {
                return;
            }

            for (EventRow row : rows) {
                applyEvent(row);
                lastEventId = Math.max(lastEventId, row.id);
            }

            store.setLastEventId(config.getServerId(), lastEventId);
            upsertConsumer(conn, lastEventId);
        } catch (Exception e) {
            logger.warn("跨服同步轮询失败: " + e.getMessage());
        }
    }

    private void applyEvent(EventRow row) {
        if (row.variableKey == null) {
            return;
        }
        try {
            String scope = row.scope == null ? "PLAYER" : row.scope;
            String op = row.op == null ? "OTHER" : row.op;
            boolean isDelete = "DELETE".equalsIgnoreCase(op);
            if ("GLOBAL".equalsIgnoreCase(scope)) {
                if (isDelete) {
                    // 统一复用变量管理器的远端同步入口：包含版本保护与缓存一致性处理
                    variablesManager.applyRemoteGlobalDelete(row.variableKey, row.updatedAt);
                    return;
                }
                // 统一复用变量管理器的远端同步入口：包含版本保护与缓存一致性处理
                variablesManager.applyRemoteGlobalChange(
                        row.variableKey,
                        row.value,
                        row.updatedAt,
                        row.firstModifiedAt
                );
                return;
            }

            UUID playerId;
            try {
                if (row.playerUuid == null) {
                    return;
                }
                playerId = UUID.fromString(row.playerUuid);
            } catch (Exception ex) {
                logger.warn("无效的 player_uuid，已跳过事件: " + row.playerUuid + ", key=" + row.variableKey);
                return;
            }
            if (!onlineTracker.isOnline(playerId)) {
                return;
            }

            if (isDelete) {
                // 统一复用变量管理器的远端同步入口：包含版本保护与事件触发
                variablesManager.applyRemotePlayerDelete(playerId, row.variableKey, row.updatedAt);
                return;
            }

            // 统一复用变量管理器的远端同步入口：包含版本保护与事件触发
            variablesManager.applyRemotePlayerChange(
                    playerId,
                    row.variableKey,
                    row.value,
                    row.updatedAt,
                    row.firstModifiedAt
            );
        } catch (Exception e) {
            logger.warn("应用跨服同步事件失败: scope=" + row.scope + ", player=" + row.playerUuid + ", key=" + row.variableKey
                    + ", err=" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    private void upsertConsumer(Connection conn, long lastId) throws SQLException {
        String sql = "INSERT INTO player_variable_change_consumers (server_id, last_event_id, updated_at) " +
                "VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE last_event_id = VALUES(last_event_id), updated_at = VALUES(updated_at)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, config.getServerId());
            stmt.setLong(2, lastId);
            stmt.setLong(3, System.currentTimeMillis());
            stmt.executeUpdate();
        }
    }

    private void cleanupEvents() {
        try (Connection conn = database.getConnection()) {
            long minId = 0L;
            if (config.isIgnoreStaleConsumers()) {
                long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(config.getConsumerStaleDays());
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT MIN(last_event_id) FROM player_variable_change_consumers WHERE updated_at >= ?")) {
                    try { stmt.setQueryTimeout(15); } catch (Throwable ignore) { }
                    stmt.setLong(1, cutoff);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            minId = rs.getLong(1);
                        }
                    }
                }
            } else {
                try (PreparedStatement stmt = conn.prepareStatement("SELECT MIN(last_event_id) FROM player_variable_change_consumers")) {
                    try { stmt.setQueryTimeout(15); } catch (Throwable ignore) { }
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            minId = rs.getLong(1);
                        }
                    }
                }
            }
            if (minId <= 0) {
                long createdCutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(config.getRetentionDays());
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM player_variable_change_events WHERE created_at < ? LIMIT 1000")) {
                    try { stmt.setQueryTimeout(15); } catch (Throwable ignore) { }
                    stmt.setLong(1, createdCutoff);
                    stmt.executeUpdate();
                }
                return;
            }
            long safeId = Math.max(0, minId - config.getSafetyMargin());
            long createdCutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(config.getRetentionDays());
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM player_variable_change_events WHERE id <= ? AND created_at < ? LIMIT 1000")) {
                try { stmt.setQueryTimeout(15); } catch (Throwable ignore) { }
                stmt.setLong(1, safeId);
                stmt.setLong(2, createdCutoff);
                stmt.executeUpdate();
            }
        } catch (Exception e) {
            logger.debug("跨服同步事件清理失败: " + e.getMessage());
        }
    }

    private static final class EventRow {
        final long id;
        final String playerUuid;
        final String variableKey;
        final String value;
        final long updatedAt;
        final long firstModifiedAt;
        final String scope;
        final String op;

        EventRow(long id, String playerUuid, String variableKey, String value, long updatedAt, long firstModifiedAt, String scope, String op) {
            this.id = id;
            this.playerUuid = playerUuid;
            this.variableKey = variableKey;
            this.value = value;
            this.updatedAt = updatedAt;
            this.firstModifiedAt = firstModifiedAt;
            this.scope = scope;
            this.op = op;
        }
    }
}
