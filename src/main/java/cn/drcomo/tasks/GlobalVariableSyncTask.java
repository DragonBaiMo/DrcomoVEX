package cn.drcomo.tasks;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.database.HikariConnection;
import cn.drcomo.managers.RefactoredVariablesManager;
import cn.drcomo.storage.VariableValue;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全局变量数据库拉取同步任务。
 *
 * 设计目标：
 * 1. 仅处理 scope=global 的变量
 * 2. 通过共享 MySQL 的 server_variables 表实现跨服同步
 * 3. 不依赖 settings.cross-server-sync.server-id
 * 4. 维持“玩家变量跨服逻辑不动”
 */
public class GlobalVariableSyncTask {

    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final RefactoredVariablesManager variablesManager;
    private final HikariConnection database;
    private final FileConfiguration config;

    /**
     * 记录最近一次从数据库确认过的更新时间。
     * 仅用于安全判断“该变量是否确实已在数据库中被删除”。
     */
    private final ConcurrentHashMap<String, Long> lastSeenDbUpdatedAt = new ConcurrentHashMap<>();
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private volatile boolean waitForInitializationLogged;
    private ScheduledFuture<?> task;

    public GlobalVariableSyncTask(
            DrcomoVEX plugin,
            DebugUtil logger,
            RefactoredVariablesManager variablesManager,
            HikariConnection database,
            FileConfiguration config
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.variablesManager = variablesManager;
        this.database = database;
        this.config = config;
    }

    public void start() {
        if (!isEnabled()) {
            logger.info("全局变量数据库拉取同步已禁用");
            return;
        }
        if (database == null || !database.isMySQL()) {
            logger.info("全局变量数据库拉取同步仅支持共享 MySQL，当前已跳过");
            return;
        }
        if (plugin.getRedisCrossServerSync() != null && plugin.getRedisCrossServerSync().isEnabled()) {
            logger.info("Redis 同步已启用，跳过全局变量数据库拉取同步");
            return;
        }

        int intervalMillis = Math.max(250, config.getInt("settings.global-db-sync.poll-interval-millis", 1000));
        task = plugin.getAsyncTaskManager().scheduleAtFixedRate(
                this::poll,
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
        logger.info("已启动全局变量数据库拉取同步，间隔: " + intervalMillis + " 毫秒");
    }

    public void stop() {
        if (task != null && !task.isCancelled()) {
            task.cancel(false);
            logger.info("全局变量数据库拉取同步已停止");
        }
    }

    private void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }

        try {
            if (!variablesManager.isInitialized()) {
                if (!waitForInitializationLogged) {
                    waitForInitializationLogged = true;
                    logger.info("变量管理器尚未初始化完成，暂缓全局变量数据库拉取同步");
                }
                return;
            }

            if (waitForInitializationLogged) {
                waitForInitializationLogged = false;
                logger.info("变量管理器初始化完成，开始执行全局变量数据库拉取同步");
            }

            Map<String, DbGlobalRecord> dbMap = fetchDatabaseGlobals();
            if (dbMap == null) {
                return;
            }

            applyDatabaseRows(dbMap);
            applyDeletions(dbMap.keySet());
        } finally {
            polling.set(false);
        }
    }

    private Map<String, DbGlobalRecord> fetchDatabaseGlobals() {
        try {
            long timeoutMillis = Math.max(1000L, config.getLong("settings.global-db-sync.query-timeout-millis", 5000L));
            List<String[]> rows = database.queryAllServerVariablesAsync()
                    .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);

            Map<String, DbGlobalRecord> dbMap = new HashMap<>();
            for (String[] row : rows) {
                if (row == null || row.length < 4) {
                    continue;
                }
                String key = row[0];
                if (key == null || key.isEmpty()) {
                    continue;
                }
                dbMap.put(key, new DbGlobalRecord(
                        key,
                        row[1],
                        parseLongOrZero(row[2]),
                        parseLongOrZero(row[3])
                ));
            }
            return dbMap;
        } catch (TimeoutException e) {
            logger.warn("全局变量数据库拉取同步超时");
        } catch (Exception e) {
            logger.warn("全局变量数据库拉取同步失败: " + e.getMessage());
        }
        return null;
    }

    private void applyDatabaseRows(Map<String, DbGlobalRecord> dbMap) {
        Set<String> globalKeys = variablesManager.getGlobalVariableKeys();
        for (Map.Entry<String, DbGlobalRecord> entry : dbMap.entrySet()) {
            String key = entry.getKey();
            if (!globalKeys.contains(key)) {
                continue;
            }

            DbGlobalRecord record = entry.getValue();
            lastSeenDbUpdatedAt.put(key, record.updatedAt);
            variablesManager.applyRemoteGlobalChange(
                    key,
                    record.value,
                    record.updatedAt,
                    record.firstModifiedAt
            );
        }
    }

    private void applyDeletions(Set<String> existingDbKeys) {
        for (String key : variablesManager.getGlobalVariableKeys()) {
            if (existingDbKeys.contains(key)) {
                continue;
            }

            Long lastSeen = lastSeenDbUpdatedAt.get(key);
            if (lastSeen == null) {
                continue;
            }

            VariableValue current = variablesManager.getMemoryStorage().getServerVariable(key);
            if (current == null) {
                lastSeenDbUpdatedAt.remove(key);
                continue;
            }
            if (shouldApplyDelete(current, lastSeen)) {
                variablesManager.applyRemoteGlobalDelete(key, lastSeen);
                lastSeenDbUpdatedAt.remove(key);
            }
        }
    }

    /**
     * 仅当本服仍停留在“上次已确认的数据库版本”或更旧版本时，才允许按数据库缺失判定为删除。
     * 这样可以避免覆盖本服尚未完成写库的新值。
     */
    static boolean shouldApplyDelete(VariableValue current, Long lastSeenDbUpdatedAt) {
        if (current == null || lastSeenDbUpdatedAt == null) {
            return false;
        }
        if (current.isDirty()) {
            return false;
        }
        return current.getLastModified() <= lastSeenDbUpdatedAt;
    }

    private boolean isEnabled() {
        return config.getBoolean("settings.global-db-sync.enabled", true);
    }

    private long parseLongOrZero(String input) {
        try {
            return input == null ? 0L : Long.parseLong(input.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    private static final class DbGlobalRecord {
        private final String key;
        private final String value;
        private final long updatedAt;
        private final long firstModifiedAt;

        private DbGlobalRecord(String key, String value, long updatedAt, long firstModifiedAt) {
            this.key = key;
            this.value = value;
            this.updatedAt = updatedAt;
            this.firstModifiedAt = firstModifiedAt;
        }
    }
}
