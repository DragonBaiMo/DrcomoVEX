package cn.drcomo.managers;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.model.VariableResult;
import cn.drcomo.model.structure.ScopeType;
import cn.drcomo.model.structure.Variable;
import cn.drcomo.database.HikariConnection;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.redis.RedisCrossServerSync;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 玩家变量管理器
 * 
 * 专门管理玩家作用域的变量。
 * 
 * @author BaiMo
 */
public class PlayerVariablesManager {

    /** 异步操作超时时间（秒） */
    private static final long TIMEOUT_SECONDS = 5L;
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final RefactoredVariablesManager variablesManager;
    private volatile RedisCrossServerSync redisSyncService;
    
    public PlayerVariablesManager(
            DrcomoVEX plugin,
            DebugUtil logger,
            RefactoredVariablesManager variablesManager,
            HikariConnection database
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.variablesManager = variablesManager;
        // database 依赖保留在构造参数中以兼容现有注入链路，当前类不直接使用。
    }
    
    /**
     * 初始化玩家变量管理器
     */
    public void initialize() {
        logger.info("正在初始化玩家变量管理器...");
        logger.info("玩家变量管理器初始化完成！");
    }

    /** 注入 Redis 跨服同步服务（可选） */
    public void setRedisSyncService(RedisCrossServerSync redisSyncService) {
        this.redisSyncService = redisSyncService;
    }

    public RedisCrossServerSync getRedisSyncService() {
        return redisSyncService;
    }
    
    /**
     * 获取玩家变量值
     */
    public CompletableFuture<VariableResult> getPlayerVariable(OfflinePlayer player, String key) {
        CompletableFuture<VariableResult> routedOrLocal = routeOrLocal(
                player,
                key,
                null,
                "GET",
                () -> variablesManager.getVariable(player, key)
        );
        return withTimeout(routedOrLocal)
                .thenApply(result -> {
                    if (result.isSuccess()) {
                        // 检查是否为玩家变量
                        Variable variable = variablesManager.getVariableDefinition(key);
                        if (variable != null && !variable.isPlayerScoped()) {
                            return VariableResult.failure("变量不是玩家作用域: " + key);
                        }
                    }
                    return result;
                });
    }
    
    /**
     * 设置玩家变量值
     */
    public CompletableFuture<VariableResult> setPlayerVariable(OfflinePlayer player, String key, String value) {
        return setPlayerVariable(player, key, value, false);
    }

    public CompletableFuture<VariableResult> setPlayerVariable(OfflinePlayer player, String key, String value, boolean forceOffline) {
        Optional<VariableResult> validation = variablesManager.validateScope(key, ScopeType.PLAYER);
        if (validation.isPresent()) {
            return CompletableFuture.completedFuture(validation.get());
        }
        if (!forceOffline && shouldBlockOfflineWriteWithoutRedis(player)) {
            logger.warn("[PolicyBlockWrite] op=SET, player=" + safePlayerName(player)
                    + ", online=" + (player != null && player.isOnline())
                    + ", redisAvailable=" + isRedisAvailable());
            return CompletableFuture.completedFuture(VariableResult.failure(
                    "Redis 未启用：禁止修改本服离线玩家变量（仅允许 get）",
                    "SET",
                    key,
                    safePlayerName(player)
            ));
        }
        return withTimeout(routeOrLocal(
                player,
                key,
                value,
                "SET",
                () -> variablesManager.setVariable(player, key, value)
        ));
    }
    
    /**
     * 增加玩家变量值
     */
    public CompletableFuture<VariableResult> addPlayerVariable(OfflinePlayer player, String key, String addValue) {
        return addPlayerVariable(player, key, addValue, false);
    }

    public CompletableFuture<VariableResult> addPlayerVariable(OfflinePlayer player, String key, String addValue, boolean forceOffline) {
        logger.debug("玩家变量管理器：开始添加变量 " + key + " 对玩家 " + player.getName() + "，值：" + addValue);
        Optional<VariableResult> validation = variablesManager.validateScope(key, ScopeType.PLAYER);
        if (validation.isPresent()) {
            logger.debug("变量校验失败: " + validation.get().getErrorMessage());
            return CompletableFuture.completedFuture(validation.get());
        }
        if (!forceOffline && shouldBlockOfflineWriteWithoutRedis(player)) {
            logger.warn("[PolicyBlockWrite] op=ADD, player=" + safePlayerName(player)
                    + ", online=" + (player != null && player.isOnline())
                    + ", redisAvailable=" + isRedisAvailable());
            return CompletableFuture.completedFuture(VariableResult.failure(
                    "Redis 未启用：禁止修改本服离线玩家变量（仅允许 get）",
                    "ADD",
                    key,
                    safePlayerName(player)
            ));
        }
        return withTimeout(routeOrLocal(
                player,
                key,
                addValue,
                "ADD",
                () -> variablesManager.addVariable(player, key, addValue)
        ));
    }
    
    /**
     * 移除玩家变量值
     */
    public CompletableFuture<VariableResult> removePlayerVariable(OfflinePlayer player, String key, String removeValue) {
        return removePlayerVariable(player, key, removeValue, false);
    }

    public CompletableFuture<VariableResult> removePlayerVariable(OfflinePlayer player, String key, String removeValue, boolean forceOffline) {
        Optional<VariableResult> validation = variablesManager.validateScope(key, ScopeType.PLAYER);
        if (validation.isPresent()) {
            return CompletableFuture.completedFuture(validation.get());
        }
        if (!forceOffline && shouldBlockOfflineWriteWithoutRedis(player)) {
            logger.warn("[PolicyBlockWrite] op=REMOVE, player=" + safePlayerName(player)
                    + ", online=" + (player != null && player.isOnline())
                    + ", redisAvailable=" + isRedisAvailable());
            return CompletableFuture.completedFuture(VariableResult.failure(
                    "Redis 未启用：禁止修改本服离线玩家变量（仅允许 get）",
                    "REMOVE",
                    key,
                    safePlayerName(player)
            ));
        }
        return withTimeout(routeOrLocal(
                player,
                key,
                removeValue,
                "REMOVE",
                () -> variablesManager.removeVariable(player, key, removeValue)
        ));
    }
    
    /**
     * 重置玩家变量
     */
    public CompletableFuture<VariableResult> resetPlayerVariable(OfflinePlayer player, String key) {
        return resetPlayerVariable(player, key, false);
    }

    public CompletableFuture<VariableResult> resetPlayerVariable(OfflinePlayer player, String key, boolean forceOffline) {
        Optional<VariableResult> validation = variablesManager.validateScope(key, ScopeType.PLAYER);
        if (validation.isPresent()) {
            return CompletableFuture.completedFuture(validation.get());
        }
        if (!forceOffline && shouldBlockOfflineWriteWithoutRedis(player)) {
            logger.warn("[PolicyBlockWrite] op=RESET, player=" + safePlayerName(player)
                    + ", online=" + (player != null && player.isOnline())
                    + ", redisAvailable=" + isRedisAvailable());
            return CompletableFuture.completedFuture(VariableResult.failure(
                    "Redis 未启用：禁止修改本服离线玩家变量（仅允许 get）",
                    "RESET",
                    key,
                    safePlayerName(player)
            ));
        }
        return withTimeout(routeOrLocal(
                player,
                key,
                null,
                "RESET",
                () -> variablesManager.resetVariable(player, key)
        ));
    }

    /**
     * 安全保护：Redis 关闭时，禁止对“本服离线玩家”执行写操作。
     */
    private boolean shouldBlockOfflineWriteWithoutRedis(OfflinePlayer player) {
        if (player == null || player.isOnline()) {
            return false;
        }
        return !isRedisAvailable();
    }
    
    /**
     * 检查是否为玩家变量
     */
    public boolean isPlayerVariable(String key) {
        Variable variable = variablesManager.getVariableDefinition(key);
        return variable != null && variable.isPlayerScoped();
    }

    /**
     * 为异步操作添加超时，避免无反馈的悬挂
     */
    private CompletableFuture<VariableResult> withTimeout(CompletableFuture<VariableResult> future) {
        return future.completeOnTimeout(VariableResult.failure("操作超时"), TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 当玩家在线于其他子服时，将操作路由到目标子服执行。
     *
     * 返回值：
     * - null: 不需要路由，按本服原逻辑执行
     * - future: 已路由（成功/失败均由 future 表达）
     */
    private CompletableFuture<VariableResult> routeOrLocal(
            OfflinePlayer player,
            String key,
            String value,
            String op,
            Supplier<CompletableFuture<VariableResult>> localSupplier
    ) {
        try {
            if (localSupplier == null) {
                return CompletableFuture.completedFuture(
                        VariableResult.failure("本地执行器为空", op, key, safePlayerName(player))
                );
            }

            if (player == null || player.getUniqueId() == null || key == null) {
                return runLocal(player, localSupplier);
            }

            RedisCrossServerSync sync = this.redisSyncService;
            if (sync == null || !sync.isEnabled()) {
                return runLocal(player, localSupplier);
            }

            // 本服在线玩家不做 Redis 查询，避免主线程高频阻塞 I/O。
            if (player.isOnline()) {
                return runLocal(player, localSupplier);
            }

            return CompletableFuture.supplyAsync(() -> {
                        try {
                            return sync.getOnlineServer(player.getUniqueId());
                        } catch (Exception e) {
                            logger.debug("查询 Redis 在线位置失败，回退本地执行: " + e.getMessage());
                            return null;
                        }
                    }, plugin.getAsyncTaskManager().getExecutor())
                    .thenCompose(targetServer -> {
                        if (targetServer == null || targetServer.isEmpty() || sync.isLocalServer(targetServer)) {
                            return runLocal(player, localSupplier);
                        }
                        logger.debug("跨服路由玩家变量操作: op=" + op
                                + ", player=" + player.getUniqueId()
                                + ", key=" + key
                                + ", target=" + targetServer);
                        return sync.requestRemotePlayerOperation(player, key, value, op)
                                .exceptionally(ex -> VariableResult.failure(
                                        "跨服路由失败: " + ex.getMessage(),
                                        op,
                                        key,
                                        safePlayerName(player)
                                ));
                    })
                    .exceptionally(ex -> VariableResult.failure(
                            "路由决策失败: " + ex.getMessage(),
                            op,
                            key,
                            safePlayerName(player)
                    ));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(VariableResult.failure(
                    "跨服路由失败: " + e.getMessage(),
                    op,
                    key,
                    safePlayerName(player)
            ));
        }
    }

    private String safePlayerName(OfflinePlayer player) {
        if (player == null) {
            return "";
        }
        String name = player.getName();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return player.getUniqueId() != null ? player.getUniqueId().toString() : "";
    }

    /** Redis 可用性（配置 + 运行态） */
    private boolean isRedisAvailable() {
        boolean enabledByConfig = false;
        try {
            if (plugin != null && plugin.getConfigsManager() != null && plugin.getConfigsManager().getMainConfig() != null) {
                FileConfiguration cfg = plugin.getConfigsManager().getMainConfig();
                enabledByConfig = cfg.getBoolean("settings.redis-sync.enabled", false);
            }
        } catch (Exception ignored) {
        }

        RedisCrossServerSync sync = this.redisSyncService;
        return enabledByConfig && sync != null && sync.isEnabled();
    }

    /**
     * 本地执行策略：
     * - 在线玩家：保留原同步快速路径
     * - 离线玩家：异步线程发起，避免主线程阻塞 DB 穿透读取
     */
    private CompletableFuture<VariableResult> runLocal(
            OfflinePlayer player,
            Supplier<CompletableFuture<VariableResult>> localSupplier
    ) {
        if (localSupplier == null) {
            return CompletableFuture.completedFuture(VariableResult.failure("本地执行器为空"));
        }

        boolean offline = player != null && !player.isOnline();
        if (!offline) {
            CompletableFuture<VariableResult> f = localSupplier.get();
            return f != null ? f : CompletableFuture.completedFuture(VariableResult.failure("本地执行结果为空"));
        }

        return CompletableFuture.supplyAsync(localSupplier, plugin.getAsyncTaskManager().getExecutor())
                .thenCompose(f -> f != null
                        ? f
                        : CompletableFuture.completedFuture(VariableResult.failure("本地执行结果为空"))
                );
    }
}
