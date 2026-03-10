package cn.drcomo.redis;

import cn.drcomo.corelib.async.AsyncTaskManager;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.managers.RefactoredVariablesManager;
import cn.drcomo.model.VariableResult;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis 跨服同步服务
 *
 * 目标：
 * 1) 变量变更实时广播（Pub/Sub）
 * 2) 离线命令在“目标玩家在线于其他子服”时，路由到目标子服执行
 * 3) 在线玩家位置追踪（Redis Key）
 */
public class RedisCrossServerSync {

    public static final String CHANNEL_SYNC = "vex:sync";

    private final DebugUtil logger;
    private final AsyncTaskManager asyncTaskManager;
    private final RefactoredVariablesManager variablesManager;
    private final RedisConnection redis;
    private final RedisOnlinePlayerTracker onlineTracker;

    private final String serverId;
    private final String serverOwner;
    private final int heartbeatIntervalSeconds;
    private final long requestTimeoutMillis;
    private final int serverIdClaimTtlSeconds;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, CompletableFuture<RedisSyncMessage>> pendingRequests = new ConcurrentHashMap<>();
    private final Set<UUID> localOnlinePlayers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean claimLostWarned = new AtomicBoolean(false);

    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> claimRefreshTask;

    public RedisCrossServerSync(
            DebugUtil logger,
            AsyncTaskManager asyncTaskManager,
            RefactoredVariablesManager variablesManager,
            RedisConnection redis,
            RedisOnlinePlayerTracker onlineTracker,
            String serverId,
            String serverOwner,
            int serverIdClaimTtlSeconds,
            FileConfiguration config
    ) {
        this.logger = logger;
        this.asyncTaskManager = asyncTaskManager;
        this.variablesManager = variablesManager;
        this.redis = redis;
        this.onlineTracker = onlineTracker;

        this.serverId = serverId == null ? "" : serverId.trim();
        this.serverOwner = serverOwner == null ? "" : serverOwner.trim();
        this.heartbeatIntervalSeconds = Math.max(10,
                config.getInt("settings.redis-sync.heartbeat-interval-seconds", 60));
        this.requestTimeoutMillis = Math.max(500L,
                config.getLong("settings.redis-sync.request-timeout-millis", 3000L));
        this.serverIdClaimTtlSeconds = Math.max(30, serverIdClaimTtlSeconds);
    }

    public boolean isEnabled() {
        return running.get() && redis != null && redis.isReady();
    }

    public boolean isLocalServer(String otherServerId) {
        return otherServerId != null && otherServerId.equals(serverId);
    }

    public String getServerId() {
        return serverId;
    }

    public String getServerOwner() {
        return serverOwner;
    }

    public int getServerIdClaimTtlSeconds() {
        return serverIdClaimTtlSeconds;
    }

    public String getOnlineServer(UUID playerId) {
        if (onlineTracker == null) {
            return null;
        }
        return onlineTracker.getPlayerServer(playerId);
    }

    public void start() {
        if (running.get()) {
            return;
        }
        if (redis == null || !redis.isReady()) {
            logger.info("Redis 跨服同步未启用：Redis 不可用");
            return;
        }
        if (serverId.isEmpty()) {
            logger.warn("Redis 跨服同步未启用：无法解析 server-id");
            return;
        }

        // 启动前校验并占用 server-id，避免与其他实例冲突
        boolean claimed = redis.refreshServerIdClaim(serverId, serverOwner, serverIdClaimTtlSeconds)
                || redis.tryClaimServerId(serverId, serverOwner, serverIdClaimTtlSeconds);
        if (!claimed) {
            String owner = redis.getServerIdOwner(serverId);
            logger.error("Redis 跨服同步未启用：server-id 已被占用。id=" + serverId
                    + ", owner=" + (owner == null ? "unknown" : owner));
            return;
        }

        running.set(true);
        claimLostWarned.set(false);

        redis.subscribe(new String[]{CHANNEL_SYNC}, (channel, payload) -> {
            if (!CHANNEL_SYNC.equals(channel) || payload == null || payload.isEmpty()) {
                return;
            }
            handleIncomingMessage(payload);
        });

        // 启动心跳刷新在线状态
        heartbeatTask = asyncTaskManager.scheduleAtFixedRate(
                this::heartbeat,
                5,
                heartbeatIntervalSeconds,
                TimeUnit.SECONDS
        );

        int claimRefreshSeconds = Math.max(10,
                Math.min(heartbeatIntervalSeconds, Math.max(10, serverIdClaimTtlSeconds / 3)));
        claimRefreshTask = asyncTaskManager.scheduleAtFixedRate(
                this::refreshServerIdClaim,
                claimRefreshSeconds,
                claimRefreshSeconds,
                TimeUnit.SECONDS
        );

        // 启动时立即同步当前在线玩家（先在主线程抓快照，再异步写 Redis）
        java.util.List<Player> snapshot = new java.util.ArrayList<>(Bukkit.getOnlinePlayers());
        asyncTaskManager.getExecutor().execute(() -> {
            try {
                for (Player player : snapshot) {
                    handlePlayerJoin(player);
                }
            } catch (Exception e) {
                logger.debug("启动时同步在线玩家到 Redis 失败: " + e.getMessage());
            }
        });

        logger.info("Redis 跨服同步已启动: server-id=" + serverId + ", heartbeat=" + heartbeatIntervalSeconds + "s");
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        ScheduledFuture<?> task = heartbeatTask;
        heartbeatTask = null;
        if (task != null) {
            task.cancel(false);
        }

        ScheduledFuture<?> claimTask = claimRefreshTask;
        claimRefreshTask = null;
        if (claimTask != null) {
            claimTask.cancel(false);
        }

        // 关闭前清理本服在线标记
        for (Player player : Bukkit.getOnlinePlayers()) {
            handlePlayerQuit(player);
        }
        localOnlinePlayers.clear();

        // 结束所有挂起请求
        for (Map.Entry<String, CompletableFuture<RedisSyncMessage>> e : pendingRequests.entrySet()) {
            e.getValue().completeExceptionally(new IllegalStateException("Redis 同步服务已停止"));
        }
        pendingRequests.clear();

        redis.stopSubscriber();
        redis.releaseServerIdClaim(serverId, serverOwner);
    }

    public void handlePlayerJoin(Player player) {
        if (player == null || onlineTracker == null || !isEnabled()) {
            return;
        }
        UUID id = player.getUniqueId();
        if (id != null) {
            localOnlinePlayers.add(id);
            asyncTaskManager.getExecutor().execute(() -> {
                try {
                    onlineTracker.markOnline(id);
                } catch (Exception e) {
                    logger.debug("玩家上线 Redis 标记失败(已忽略): " + e.getMessage());
                }
            });
        }
    }

    public void handlePlayerQuit(Player player) {
        if (player == null || onlineTracker == null || redis == null || !redis.isReady()) {
            return;
        }
        UUID id = player.getUniqueId();
        if (id != null) {
            localOnlinePlayers.remove(id);
            asyncTaskManager.getExecutor().execute(() -> {
                try {
                    onlineTracker.markOffline(id);
                } catch (Exception e) {
                    logger.debug("玩家下线 Redis 标记失败(已忽略): " + e.getMessage());
                }
            });
        }
    }

    private void heartbeat() {
        if (!isEnabled()) {
            return;
        }
        try {
            for (UUID playerId : localOnlinePlayers) {
                onlineTracker.refreshOnline(playerId);
            }
        } catch (Exception e) {
            logger.debug("Redis 心跳刷新失败: " + e.getMessage());
        }
    }

    private void refreshServerIdClaim() {
        if (!isEnabled()) {
            return;
        }
        try {
            boolean ok = redis.refreshServerIdClaim(serverId, serverOwner, serverIdClaimTtlSeconds);
            if (ok) {
                claimLostWarned.set(false);
                return;
            }

            if (claimLostWarned.compareAndSet(false, true)) {
                String owner = redis.getServerIdOwner(serverId);
                logger.warn("Redis server-id 占用续租失败，可能存在冲突。id=" + serverId
                        + ", owner=" + (owner == null ? "unknown" : owner));
            }
        } catch (Exception e) {
            logger.debug("刷新 server-id 占用失败: " + e.getMessage());
        }
    }

    /**
     * 广播玩家变量变更（实时跨服同步）。
     */
    public void broadcastPlayerVariableChange(UUID playerId, String playerName, String key, String storedValue,
                                              long updatedAt, long firstModifiedAt, String op) {
        if (!isEnabled() || playerId == null || key == null) {
            return;
        }
        RedisSyncMessage msg = new RedisSyncMessage();
        msg.type = "VARIABLE_CHANGE";
        msg.sourceServerId = serverId;
        msg.playerUuid = playerId.toString();
        msg.playerName = playerName;
        msg.variableKey = key;
        msg.value = storedValue;
        msg.updatedAt = updatedAt;
        msg.firstModifiedAt = firstModifiedAt;
        msg.op = op;
        redis.publish(CHANNEL_SYNC, msg.toJson());
    }

    /**
     * 广播玩家变量删除（reset/delete）。
     */
    public void broadcastPlayerVariableDelete(UUID playerId, String playerName, String key,
                                              long updatedAt, long firstModifiedAt, String op) {
        if (!isEnabled() || playerId == null || key == null) {
            return;
        }
        RedisSyncMessage msg = new RedisSyncMessage();
        msg.type = "VARIABLE_CHANGE";
        msg.sourceServerId = serverId;
        msg.playerUuid = playerId.toString();
        msg.playerName = playerName;
        msg.variableKey = key;
        msg.value = null;
        msg.updatedAt = updatedAt;
        msg.firstModifiedAt = firstModifiedAt;
        msg.op = (op == null || op.isEmpty()) ? "DELETE" : op;
        redis.publish(CHANNEL_SYNC, msg.toJson());
    }

    /**
     * 广播全局变量变更。
     */
    public void broadcastGlobalVariableChange(String key, String storedValue,
                                              long updatedAt, long firstModifiedAt, String op) {
        if (!isEnabled() || key == null) {
            return;
        }
        RedisSyncMessage msg = new RedisSyncMessage();
        msg.type = "VARIABLE_CHANGE";
        msg.sourceServerId = serverId;
        msg.playerUuid = null; // null 表示 GLOBAL
        msg.variableKey = key;
        msg.value = storedValue;
        msg.updatedAt = updatedAt;
        msg.firstModifiedAt = firstModifiedAt;
        msg.op = op;
        redis.publish(CHANNEL_SYNC, msg.toJson());
    }

    public void broadcastGlobalVariableDelete(String key, long updatedAt, long firstModifiedAt, String op) {
        if (!isEnabled() || key == null) {
            return;
        }
        RedisSyncMessage msg = new RedisSyncMessage();
        msg.type = "VARIABLE_CHANGE";
        msg.sourceServerId = serverId;
        msg.playerUuid = null;
        msg.variableKey = key;
        msg.value = null;
        msg.updatedAt = updatedAt;
        msg.firstModifiedAt = firstModifiedAt;
        msg.op = (op == null || op.isEmpty()) ? "DELETE" : op;
        redis.publish(CHANNEL_SYNC, msg.toJson());
    }

    /**
     * 请求“目标玩家所在子服”执行操作。
     *
     * 使用场景：
     * - 本服命令操作离线玩家时，若该玩家实际在线于其他子服，避免本服写 DB 与对方内存冲突。
     */
    public CompletableFuture<VariableResult> requestRemotePlayerOperation(
            OfflinePlayer player,
            String key,
            String value,
            String op
    ) {
        String opName = op == null ? "OTHER" : op.toUpperCase(Locale.ROOT);
        if (!isEnabled() || player == null || key == null) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("Redis 跨服同步不可用", opName, key, getPlayerName(player))
            );
        }

        UUID playerId = player.getUniqueId();
        if (playerId == null) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("无效的玩家 UUID", opName, key, getPlayerName(player))
            );
        }

        String targetServer = getOnlineServer(playerId);
        if (targetServer == null || targetServer.isEmpty()) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("玩家当前不在线（任何子服）", opName, key, getPlayerName(player))
            );
        }
        if (isLocalServer(targetServer)) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("玩家在本服在线，无需跨服路由", opName, key, getPlayerName(player))
            );
        }

        String requestId = UUID.randomUUID().toString();
        RedisSyncMessage req = new RedisSyncMessage();
        req.type = "OFFLINE_MOD_REQUEST";
        req.requestId = requestId;
        req.sourceServerId = serverId;
        req.targetServerId = targetServer;
        req.op = opName;
        req.playerUuid = playerId.toString();
        req.playerName = getPlayerName(player);
        req.variableKey = key;
        req.value = value;

        CompletableFuture<RedisSyncMessage> waiting = new CompletableFuture<>();
        pendingRequests.put(requestId, waiting);
        redis.publish(CHANNEL_SYNC, req.toJson());

        return waiting
                .orTimeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
                .thenApply(resp -> {
                    if (resp == null) {
                        return VariableResult.failure("跨服响应为空", opName, key, getPlayerName(player));
                    }
                    if (resp.error != null && !resp.error.isEmpty()) {
                        return VariableResult.failure(resp.error, opName, key, getPlayerName(player));
                    }
                    String val = resp.value != null ? resp.value : "";
                    return VariableResult.success(val, opName, key, getPlayerName(player));
                })
                .exceptionally(ex -> VariableResult.failure(
                        "跨服操作超时或失败: " + friendlyError(ex),
                        opName,
                        key,
                        getPlayerName(player)
                ))
                .whenComplete((r, t) -> pendingRequests.remove(requestId));
    }

    private void handleIncomingMessage(String payload) {
        RedisSyncMessage msg = RedisSyncMessage.fromJson(payload);
        if (msg == null || msg.type == null || msg.type.isEmpty()) {
            return;
        }

        switch (msg.type) {
            case "VARIABLE_CHANGE":
                handleVariableChange(msg);
                break;
            case "OFFLINE_MOD_REQUEST":
                handleOfflineModRequest(msg);
                break;
            case "OFFLINE_MOD_RESULT":
                handleOfflineModResult(msg);
                break;
            default:
                // ignore unknown types
                break;
        }
    }

    private void handleVariableChange(RedisSyncMessage msg) {
        // 本服自己发出的广播忽略
        if (msg.sourceServerId != null && msg.sourceServerId.equals(serverId)) {
            return;
        }
        if (msg.variableKey == null || msg.variableKey.isEmpty()) {
            return;
        }

        long updatedAt = msg.updatedAt != null ? msg.updatedAt : System.currentTimeMillis();
        long firstModifiedAt = msg.firstModifiedAt != null ? msg.firstModifiedAt : updatedAt;
        boolean isDelete = "DELETE".equalsIgnoreCase(msg.op) || "RESET".equalsIgnoreCase(msg.op);

        if (msg.playerUuid != null && !msg.playerUuid.isEmpty()) {
            UUID playerId;
            try {
                playerId = UUID.fromString(msg.playerUuid);
            } catch (Exception e) {
                logger.debug("忽略非法 playerUuid 的同步消息: " + msg.playerUuid);
                return;
            }

            if (isDelete) {
                variablesManager.applyRemotePlayerDelete(playerId, msg.variableKey, updatedAt);
            } else {
                variablesManager.applyRemotePlayerChange(playerId, msg.variableKey,
                        msg.value == null ? "" : msg.value, updatedAt, firstModifiedAt);
            }
            return;
        }

        // GLOBAL
        if (isDelete) {
            variablesManager.applyRemoteGlobalDelete(msg.variableKey, updatedAt);
        } else {
            variablesManager.applyRemoteGlobalChange(msg.variableKey,
                    msg.value == null ? "" : msg.value, updatedAt, firstModifiedAt);
        }
    }

    private void handleOfflineModRequest(RedisSyncMessage msg) {
        if (!isEnabled()) {
            return;
        }
        if (msg.targetServerId == null || !msg.targetServerId.equals(serverId)) {
            return;
        }
        if (msg.requestId == null || msg.requestId.isEmpty()) {
            return;
        }

        // 异步处理请求
        asyncTaskManager.getExecutor().execute(() -> processOfflineModRequest(msg));
    }

    private void processOfflineModRequest(RedisSyncMessage req) {
        RedisSyncMessage resp = new RedisSyncMessage();
        resp.type = "OFFLINE_MOD_RESULT";
        resp.requestId = req.requestId;
        resp.sourceServerId = serverId;
        resp.targetServerId = req.sourceServerId;
        resp.op = req.op;
        resp.playerUuid = req.playerUuid;
        resp.playerName = req.playerName;
        resp.variableKey = req.variableKey;

        Bukkit.getScheduler().runTask(variablesManager.plugin, () -> {
            try {
            if (req.playerUuid == null || req.playerUuid.isEmpty()) {
                resp.error = "缺少 playerUuid";
                publishResponse(resp);
                return;
            }

            UUID uuid = UUID.fromString(req.playerUuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                resp.error = "目标玩家已不在线";
                publishResponse(resp);
                return;
            }

            String op = req.op == null ? "SET" : req.op.toUpperCase(Locale.ROOT);
            CompletableFuture<VariableResult> future;
            switch (op) {
                case "GET":
                    future = variablesManager.getVariable(player, req.variableKey);
                    break;
                case "SET":
                    future = variablesManager.setVariable(player, req.variableKey, req.value == null ? "" : req.value);
                    break;
                case "ADD":
                    future = variablesManager.addVariable(player, req.variableKey, req.value == null ? "0" : req.value);
                    break;
                case "REMOVE":
                    future = variablesManager.removeVariable(player, req.variableKey, req.value == null ? "0" : req.value);
                    break;
                case "RESET":
                    future = variablesManager.resetVariable(player, req.variableKey);
                    break;
                default:
                    resp.error = "不支持的操作: " + op;
                    publishResponse(resp);
                    return;
            }

            future.orTimeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            resp.error = "跨服请求执行超时或异常: " + friendlyError(ex);
                            publishResponse(resp);
                            return;
                        }

                        if (result == null) {
                            resp.error = "操作结果为空";
                        } else if (!result.isSuccess()) {
                            resp.error = result.getErrorMessage() != null ? result.getErrorMessage() : "操作失败";
                        } else {
                            resp.value = result.getValue();
                            resp.updatedAt = System.currentTimeMillis();
                        }
                        publishResponse(resp);
                    });
            } catch (Exception e) {
                resp.error = "处理跨服请求失败: " + friendlyError(e);
                publishResponse(resp);
            }
        });
    }

    private void handleOfflineModResult(RedisSyncMessage msg) {
        if (msg.targetServerId == null || !msg.targetServerId.equals(serverId)) {
            return;
        }
        if (msg.requestId == null || msg.requestId.isEmpty()) {
            return;
        }
        CompletableFuture<RedisSyncMessage> waiting = pendingRequests.remove(msg.requestId);
        if (waiting != null) {
            waiting.complete(msg);
        }
    }

    private void publishResponse(RedisSyncMessage resp) {
        if (resp == null || redis == null || !redis.isReady()) {
            return;
        }
        asyncTaskManager.getExecutor().execute(() -> {
            try {
                redis.publish(CHANNEL_SYNC, resp.toJson());
            } catch (Exception e) {
                logger.debug("发布跨服响应失败: " + e.getMessage());
            }
        });
    }

    private String getPlayerName(OfflinePlayer player) {
        if (player == null) {
            return "";
        }
        String name = player.getName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        UUID id = player.getUniqueId();
        return id != null ? id.toString() : "";
    }

    private String friendlyError(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        Throwable t = throwable;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        if (msg != null && !msg.isEmpty()) {
            return msg;
        }
        return t.getClass().getSimpleName();
    }
}
