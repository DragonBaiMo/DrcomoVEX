package cn.drcomo.managers;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.async.AsyncTaskManager;
import cn.drcomo.corelib.config.YamlUtil;
import cn.drcomo.corelib.hook.placeholder.PlaceholderAPIUtil;
import cn.drcomo.corelib.hook.placeholder.parse.PlaceholderConditionEvaluator;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.database.HikariConnection;
import cn.drcomo.model.VariableResult;
import cn.drcomo.model.structure.DependencySnapshot;
import cn.drcomo.model.structure.EffectiveParams;
import cn.drcomo.model.structure.Limitations;
import cn.drcomo.model.structure.ScopeType;
import cn.drcomo.model.structure.ValueType;
import cn.drcomo.model.structure.Variable;
import cn.drcomo.model.structure.RegenRule;
import cn.drcomo.managers.components.ActionExecutor;
import cn.drcomo.managers.components.ParameterGroupResolver;
import cn.drcomo.managers.components.VariableDefinitionLoader;
import cn.drcomo.storage.BatchPersistenceManager;
import cn.drcomo.storage.BatchPersistenceManager.PersistenceConfig;
import cn.drcomo.storage.VariableMemoryStorage;
import cn.drcomo.storage.VariableValue;
import cn.drcomo.config.ConfigsManager;
import cn.drcomo.util.DependencyResolver;
import cn.drcomo.util.ValueLimiter;
import cn.drcomo.corelib.math.FormulaCalculator;
import cn.drcomo.events.PlayerVariableChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
 



import java.util.ArrayList;
import java.util.Arrays;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 重构后的变量管理器 - 高性能版本
 *
 * 重构要点：
 * 1. 内存优先存储，减少数据库访问
 * 2. 批量持久化，消除I/O压力
 * 3. 多级缓存，提升响应速度
 * 4. 完全异步，消除阻塞操作
 *
 * 注意：
 * - 所有 public 方法与字段保留原有访问权限与方法名，保证兼容性
 * - 提取复用的私有方法，减少重复代码，提高可维护性
 * - 保留所有原有注释，并在关键位置补充说明
 */
public class RefactoredVariablesManager {

    // ======================== 常量配置 ========================
    private static final int MAX_RECURSION_DEPTH = 10;
    private static final int MAX_EXPRESSION_LENGTH = 1000;
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%[^%]+%");
    private static final Pattern INTERNAL_VAR_PATTERN = Pattern.compile("\\$\\{[^}]+}");

    // ======================== 插件核心组件（保持 public） ========================
    public final DrcomoVEX plugin;
    public final DebugUtil logger;
    public final YamlUtil yamlUtil;
    public final AsyncTaskManager asyncTaskManager;
    public final PlaceholderAPIUtil placeholderUtil;
    public final HikariConnection database;

    // ======================== 核心存储和缓存组件 ========================
    private final VariableMemoryStorage memoryStorage;
    private final BatchPersistenceManager persistenceManager;
    private final DependencyResolver dependencyResolver;
    private final ConfigsManager configsManager;
    private final ActionExecutor actionExecutor;
    private final VariableDefinitionLoader definitionLoader;
    private final ParameterGroupResolver groupResolver;
    private final PlaceholderConditionEvaluator conditionEvaluator;
    private final Set<String> regenVariableKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Long> recentAccessPlayers = new ConcurrentHashMap<>();

    /**
     * 穿透查询的“负缓存”（DB 明确无记录时）：避免频繁 miss 导致 DB 压力。
     *
     * 注意：
     * - 负缓存只用于“DB 无记录”的场景，不用于 DB 异常/超时。
     * - 使用 Caffeine TTL + max size，避免内存泄漏与跨服长期不一致。
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, Boolean> playerNegativeCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(200_000)
                    .expireAfterWrite(java.time.Duration.ofSeconds(10))
                    .build();

    /** 主线程穿透查询超时预算（ms）- 避免拖垮 tick（50ms） */
    private static final long MAIN_THREAD_DB_READ_TIMEOUT_MS = 10L;

    /** 异步线程穿透查询超时预算（ms） */
    private static final long ASYNC_THREAD_DB_READ_TIMEOUT_MS = 50L;

    // 变量定义注册表
    private final ConcurrentHashMap<String, Variable> variableRegistry = new ConcurrentHashMap<>();

    // 初始化完成状态
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    // 验证过程中传递当前变量键的线程本地变量
    private final ThreadLocal<String> currentValidatingVariable = new ThreadLocal<>();
    // 门控评估中的变量栈，防止条件中自引用导致的递归
    private final ThreadLocal<Set<String>> gatingEvaluatingStack = ThreadLocal.withInitial(HashSet::new);
    // 变量值解析栈，防止跨变量循环引用
    private final ThreadLocal<Set<String>> valueResolvingStack = ThreadLocal.withInitial(HashSet::new);

    // ======================== 构造函数 ========================
    public RefactoredVariablesManager(
            DrcomoVEX plugin,
            DebugUtil logger,
            YamlUtil yamlUtil,
            AsyncTaskManager asyncTaskManager,
            PlaceholderAPIUtil placeholderUtil,
            HikariConnection database
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.yamlUtil = yamlUtil;
        this.asyncTaskManager = asyncTaskManager;
        this.placeholderUtil = placeholderUtil;
        this.database = database;
        this.configsManager = plugin.getConfigsManager();

        // 初始化依赖解析器
        this.dependencyResolver = new DependencyResolver(logger);

        // 初始化内存存储（256MB）
        this.memoryStorage = new VariableMemoryStorage(logger, database, 256 * 1024 * 1024);

        // 初始化批量持久化管理器
        PersistenceConfig persistenceConfig = new PersistenceConfig()
                .setBatchIntervalSeconds(30)
                .setMaxBatchSize(1000)
                .setMemoryPressureThreshold(80.0)
                .setMaxRetries(3);
        this.persistenceManager = new BatchPersistenceManager(
                logger, database, memoryStorage, asyncTaskManager, persistenceConfig);

        // 初始化周期动作执行组件（仅解析内部变量，PAPI 解析在组件内处理）
        this.actionExecutor = new ActionExecutor(this.plugin, this.logger, this.placeholderUtil, this::resolveInternalVariables);

        // 初始化变量定义加载组件
        this.definitionLoader = new VariableDefinitionLoader(this.plugin, this.logger);

        // 初始化参数组解析器
        this.groupResolver = new ParameterGroupResolver(
                this.logger,
                this::evaluateConditionsList
        );

        // 初始化条件表达式评估器（支持比较/逻辑/数学/占位符）
        this.conditionEvaluator = new PlaceholderConditionEvaluator(
                this.plugin,
                this.logger,
                this.placeholderUtil,
                this.asyncTaskManager
        );
    }

    // ======================== 生命周期管理 ========================

    /**
     * 初始化变量管理器
     */
    public CompletableFuture<Void> initialize() {
        logger.info("正在初始化重构后的变量管理系统...");
        initialized.set(false);
        return CompletableFuture.runAsync(() -> {
            try {
                loadAllVariableDefinitions();
                rebuildRegenVariableKeys();
                validateVariableDefinitions();
                persistenceManager.start();
                logger.info("变量管理系统初始化完成！已加载 " + variableRegistry.size() + " 个变量定义");
            } catch (Exception e) {
                logger.error("变量管理系统初始化失败！", e);
                throw new RuntimeException("变量管理系统初始化失败", e);
            }
        }, asyncTaskManager.getExecutor());
    }

    // ======================== 周期动作执行 ========================

    /**
     * 在变量重置后执行 cycle-actions
     * - 当变量作用域为 player 时：对指定玩家执行；若未传入玩家（如周期任务批量重置），对所有在线玩家各执行一次
     * - 当变量作用域为 global 时：仅以控制台身份执行一次；占位符解析上下文使用传入玩家，若为空则任选一个在线玩家
     */
    public void executeCycleActionsOnReset(Variable variable, OfflinePlayer contextPlayer) {
        actionExecutor.executeCycleActionsOnReset(variable, contextPlayer);
    }

    

    /**
     * 关闭变量管理器
     */
    public CompletableFuture<Void> shutdown() {
        logger.info("正在关闭变量管理系统...");
        initialized.set(false);
        return CompletableFuture.runAsync(() -> {
            try {
                persistenceManager.shutdown();
                logger.info("持久化管理器关闭完成");
                if (database != null) {
                    database.close();
                    logger.info("数据库连接关闭完成");
                }
                logger.info("变量管理系统关闭完成");
            } catch (Exception e) {
                logger.error("关闭变量管理系统失败", e);
                if (database != null) {
                    try {
                        database.close();
                    } catch (Exception dbEx) {
                        logger.error("强制关闭数据库时发生异常", dbEx);
                    }
                }
                throw new RuntimeException("关闭变量管理系统失败", e);
            }
            // 关闭后清理内存存储
            try {
                memoryStorage.clearAllCaches();
            } catch (Exception e) {
                logger.debug("内存存储清理跳过: " + e.getMessage());
            }
        }, asyncTaskManager.getExecutor());
    }

    /**
     * 保存所有数据
     */
    public CompletableFuture<Void> saveAllData() {
        return saveAllData(false);
    }

    /**
     * 保存所有数据
     * @param forceFlush 是否强制刷新数据库到磁盘
     */
    public CompletableFuture<Void> saveAllData(boolean forceFlush) {
        logger.info("正在保存所有变量数据...");
        CompletableFuture<Void> saveTask = persistenceManager.flushAllDirtyData();
        if (forceFlush) {
            saveTask = saveTask.thenCompose(ignored -> {
                logger.debug("执行数据库强制刷新...");
                return database.flushDatabase();
            });
        }
        return saveTask.thenRun(() -> logger.info("所有变量数据保存完成！"))
                .exceptionally(throwable -> {
                    if (throwable.getCause() instanceof TimeoutException) {
                        logger.warn("持久化数据超时，部分数据可能未保存");
                    } else {
                        logger.error("保存变量数据失败！", throwable);
                    }
                    throw new RuntimeException("保存变量数据失败", throwable);
                });
    }

    /**
     * 获取变量的首次修改时间
     *
     * 玩家变量的查询由内存存储负责索引和数据库回退。
     *
     * @param isGlobal 是否为全局变量
     * @param key      变量键
     * @return 首次修改时间，若不存在返回 null
     */
    public Long getFirstModifiedAt(boolean isGlobal, String key) {
        if (isGlobal) {
            VariableValue vv = memoryStorage.getServerVariable(key);
            return vv != null ? vv.getFirstModifiedAt() : null;
        }
        return memoryStorage.getPlayerFirstModifiedAt(key);
    }

    /**
     * 异步获取变量的首次修改时间
     *
     * 全局变量直接读取内存索引，玩家变量通过内存索引缺失时回退到数据库的异步查询。
     * @param isGlobal 是否为全局变量
     * @param key      变量键
     * @return CompletableFuture<Long> 首次修改时间，若不存在返回 null
     */
    public CompletableFuture<Long> getFirstModifiedAtAsync(boolean isGlobal, String key) {
        if (isGlobal) {
            VariableValue vv = memoryStorage.getServerVariable(key);
            Long ts = vv != null ? vv.getFirstModifiedAt() : null;
            return CompletableFuture.completedFuture(ts);
        }
        return memoryStorage.getPlayerFirstModifiedAtAsync(key);
    }

    // ======================== 公共 API：变量操作 ========================

    /**
     * 获取变量值（完全异步，无阻塞）
     */
    public CompletableFuture<VariableResult> getVariable(OfflinePlayer player, String key) {
        if (Bukkit.isPrimaryThread()) {
            return CompletableFuture.completedFuture(getVariableSync(player, key, true));
        }
        return CompletableFuture.supplyAsync(() -> getVariableSync(player, key, false), asyncTaskManager.getExecutor());
    }

    public VariableResult getVariableSync(OfflinePlayer player, String key) {
        return getVariableSync(player, key, Bukkit.isPrimaryThread());
    }

    private VariableResult getVariableSync(OfflinePlayer player, String key, boolean allowPapi) {
        final String playerName = getPlayerName(player);
        try {
            Variable variable = getVariableDefinition(key);
            Optional<VariableResult> pre = checkOpPreconditions(player, variable, key, "GET", playerName, false, allowPapi);
            if (pre.isPresent()) return pre.get();

            // 直接从内存读取，不再使用多级缓存
            String finalValue = getVariableFromMemoryOrDefault(player, variable, allowPapi);
            if (!isFormulaVariable(variable)) {
                if (allowPapi) {
                    finalValue = resolveExpression(finalValue, player, variable, allowPapi);
                } else {
                    finalValue = resolveExpressionWithoutPapi(finalValue, player, variable);
                }
            }
            // 确保最终返回值经过类型规范化
            finalValue = normalizeValueByType(finalValue, variable.getValueType());
            touchRecentPlayer(player);
            return VariableResult.success(finalValue, "GET", key, playerName);
        } catch (Exception e) {
            logger.error("获取变量失败: " + key, e);
            return VariableResult.fromException(e, "GET", key, playerName);
        }
    }

    private String resolveExpressionWithoutPapi(String expression, OfflinePlayer player, Variable variable) {
        return resolveExpression(expression, player, variable, false);
    }

    private String resolveInternalVariables(String text, OfflinePlayer player, boolean allowPapi) {
        return resolveInternalVariablesInternal(text, player, allowPapi);
    }

    /**
     * 设置变量值（完全异步，立即返回）
     */
    public CompletableFuture<VariableResult> setVariable(OfflinePlayer player, String key, String value) {
        final String playerName = getPlayerName(player);
        if (Bukkit.isPrimaryThread()) {
            return CompletableFuture.completedFuture(setVariableSync(player, key, value, true));
        }
        return CompletableFuture.supplyAsync(
                () -> setVariableSync(player, key, value, false),
                asyncTaskManager.getExecutor()
        );
    }

    private VariableResult setVariableSync(OfflinePlayer player, String key, String value, boolean allowPapi) {
        final String playerName = getPlayerName(player);
        try {
            Variable variable = getVariableDefinition(key);
            Optional<VariableResult> pre = checkOpPreconditions(player, variable, key, "SET", playerName, true, allowPapi);
            if (pre.isPresent()) return pre.get();

            String processed = processAndValidateValue(variable, value, player, allowPapi);
            if (processed == null) {
                return VariableResult.failure("值格式错误或超出约束: " + value, "SET", key, playerName);
            }

            if (isFormulaVariable(variable)) {
                String increment = calculateIncrementForSet(variable, processed, player, allowPapi);
                updateMemoryAndInvalidate(player, variable, increment, PlayerVariableChangeEvent.ChangeReason.SET);
                logger.debug("设置公式变量: " + key + " 增量= " + increment + " 最终值= " + processed + " (异步持久化中)");
            } else {
                updateMemoryAndInvalidate(player, variable, processed, PlayerVariableChangeEvent.ChangeReason.SET);
                logger.debug("设置变量: " + key + " = " + processed + " (异步持久化中)");
            }
            return VariableResult.success(processed, "SET", key, playerName);
        } catch (Exception e) {
            logger.error("设置变量失败: " + key, e);
            return VariableResult.fromException(e, "SET", key, playerName);
        }
    }

    /**
     * 增加变量值（智能操作，完全异步）
     */
    public CompletableFuture<VariableResult> addVariable(OfflinePlayer player, String key, String addValue) {
        final String playerName = getPlayerName(player);
        if (Bukkit.isPrimaryThread()) {
            return CompletableFuture.completedFuture(addVariableSync(player, key, addValue, true));
        }
        return CompletableFuture.supplyAsync(
                () -> addVariableSync(player, key, addValue, false),
                asyncTaskManager.getExecutor()
        );
    }

    private VariableResult addVariableSync(OfflinePlayer player, String key, String addValue, boolean allowPapi) {
        final String playerName = getPlayerName(player);
        try {
            Variable variable = getVariableDefinition(key);
            Optional<VariableResult> pre = checkOpPreconditions(player, variable, key, "ADD", playerName, true, allowPapi);
            if (pre.isPresent()) return pre.get();

            // rank 保护：只要通过 ADD 造成值减少（例如 add -1），就阻止并记录
            if (player != null && variable != null && "rank".equalsIgnoreCase(variable.getKey())) {
                String currentRaw = getVariableFromMemoryOrDefault(player, variable, allowPapi);
                int current = parseIntOrDefault(resolveExpression(currentRaw, player, variable, allowPapi));
                String resolvedAdd = resolveExpression(addValue, player, variable, allowPapi);
                int delta = parseIntOrDefault(resolvedAdd);
                int next = current + delta;
                if (next < current) {
                    cn.drcomo.util.RankProtectionLogger rpl = plugin.getRankProtectionLogger();
                    if (rpl != null) {
                        rpl.logBlockedOperation(
                                "ADD",
                                player,
                                String.valueOf(current),
                                String.valueOf(delta),
                                "blocked decrease via ADD; only SET is allowed",
                                cn.drcomo.util.RankProtectionLogger.getCallerSummary()
                        );
                    }
                    return VariableResult.failure("rank 不允许通过 add/remove 减少，请使用 set", "ADD", key, playerName);
                }
            }

            String currentValue = getVariableFromMemoryOrDefault(player, variable, allowPapi);
            String newIncrement;
            String displayValue;

            if (isFormulaVariable(variable)) {
                FormulaOpResult r = computeFormulaAdd(variable, player, addValue, allowPapi);
                newIncrement = r.newIncrement;
                displayValue = r.displayValue;
            } else {
                String resolvedAdd = resolveExpression(addValue, player, variable, allowPapi);
                newIncrement = calculateAddition(variable.getValueType(), currentValue, resolvedAdd);
                displayValue = newIncrement;
            }

            String rawCheckValue = isFormulaVariable(variable) ? displayValue : newIncrement;
            String fitted = fitValueWithinLimitOrNull(variable, rawCheckValue, player, allowPapi);
            if (fitted == null) {
                return VariableResult.failure("加法结果超出限制", "ADD", key, playerName);
            }
            if (isFormulaVariable(variable)) {
                if (!fitted.equals(rawCheckValue)) {
                    newIncrement = calculateIncrementForSet(variable, fitted, player, allowPapi);
                }
                displayValue = fitted;
            } else {
                newIncrement = fitted;
                displayValue = fitted;
            }
            updateMemoryAndInvalidate(player, variable, newIncrement, PlayerVariableChangeEvent.ChangeReason.ADD);
            return VariableResult.success(displayValue, "ADD", key, playerName);
        } catch (Exception e) {
            logger.error("增加变量失败: " + key, e);
            return VariableResult.fromException(e, "ADD", key, playerName);
        }
    }

    /**
     * 移除变量值（智能操作，完全异步）
     */
    public CompletableFuture<VariableResult> removeVariable(OfflinePlayer player, String key, String removeValue) {
        final String playerName = getPlayerName(player);
        if (Bukkit.isPrimaryThread()) {
            return CompletableFuture.completedFuture(removeVariableSync(player, key, removeValue, true));
        }
        return CompletableFuture.supplyAsync(
                () -> removeVariableSync(player, key, removeValue, false),
                asyncTaskManager.getExecutor()
        );
    }

    private VariableResult removeVariableSync(OfflinePlayer player, String key, String removeValue, boolean allowPapi) {
        final String playerName = getPlayerName(player);
        try {
            Variable variable = getVariableDefinition(key);
            Optional<VariableResult> pre = checkOpPreconditions(player, variable, key, "REMOVE", playerName, true, allowPapi);
            if (pre.isPresent()) return pre.get();

            // rank 保护：禁止 REMOVE（只允许 set）
            if (player != null && variable != null && "rank".equalsIgnoreCase(variable.getKey())) {
                cn.drcomo.util.RankProtectionLogger rpl = plugin.getRankProtectionLogger();
                if (rpl != null) {
                    String resolvedRemove = resolveExpression(removeValue, player, variable, allowPapi);
                    rpl.logBlockedOperation(
                            "REMOVE",
                            player,
                            getVariableFromMemoryOrDefault(player, variable, allowPapi),
                            resolvedRemove,
                            "blocked remove for rank; use SET instead",
                            cn.drcomo.util.RankProtectionLogger.getCallerSummary()
                    );
                }
                return VariableResult.failure("rank 不允许通过 add/remove 减少，请使用 set", "REMOVE", key, playerName);
            }

            String currentValue = getVariableFromMemoryOrDefault(player, variable, allowPapi);
            String newIncrement;
            String displayValue;

            if (isFormulaVariable(variable)) {
                FormulaOpResult r = computeFormulaRemove(variable, player, removeValue, allowPapi);
                newIncrement = r.newIncrement;
                displayValue = r.displayValue;
            } else {
                String resolvedRemove = resolveExpression(removeValue, player, variable, allowPapi);
                newIncrement = calculateAddition(variable.getValueType(), currentValue, "-" + resolvedRemove);
                displayValue = newIncrement;
            }

            String rawCheckValue = isFormulaVariable(variable) ? displayValue : newIncrement;
            String fitted = fitValueWithinLimitOrNull(variable, rawCheckValue, player, allowPapi);
            if (fitted == null) {
                return VariableResult.failure("删除结果超出限制", "REMOVE", key, playerName);
            }
            if (isFormulaVariable(variable)) {
                if (!fitted.equals(rawCheckValue)) {
                    newIncrement = calculateIncrementForSet(variable, fitted, player, allowPapi);
                }
                displayValue = fitted;
            } else {
                newIncrement = fitted;
                displayValue = fitted;
            }
            updateMemoryAndInvalidate(player, variable, newIncrement, PlayerVariableChangeEvent.ChangeReason.REMOVE);
            return VariableResult.success(displayValue, "REMOVE", key, playerName);
        } catch (Exception e) {
            logger.error("移除变量失败: " + key, e);
            return VariableResult.fromException(e, "REMOVE", key, playerName);
        }
    }

    /**
     * 重置变量为初始值（完全异步）
     */
    public CompletableFuture<VariableResult> resetVariable(OfflinePlayer player, String key) {

        final String playerName = getPlayerName(player);
        if (Bukkit.isPrimaryThread()) {
            return CompletableFuture.completedFuture(resetVariableSync(player, key, true));
        }
        return CompletableFuture.supplyAsync(
                () -> resetVariableSync(player, key, false),
                asyncTaskManager.getExecutor()
        );
    }

    private VariableResult resetVariableSync(OfflinePlayer player, String key, boolean allowPapi) {
        final String playerName = getPlayerName(player);
        try {
            Variable variable = getVariableDefinition(key);
            Optional<VariableResult> pre = checkOpPreconditions(player, variable, key, "RESET", playerName, true, allowPapi);
            if (pre.isPresent()) return pre.get();

            removeFromMemoryAndInvalidate(player, variable);

            if (variable.isStrictInitialMode()) {
                dependencyResolver.clearSnapshot(player, key);
                logger.debug("清理变量依赖快照: " + key);
            }

            VariableValue resetVal = getMemoryValue(player, variable);
            if (resetVal != null && variable.isStrictInitialMode()) {
                resetVal.resetStrictMode();
                logger.debug("重置变量严格模式状态: " + key);
            }

            String resetValue = getVariableFromMemoryOrDefault(player, variable, allowPapi);
            if (isFormulaVariable(variable)) {
                logger.debug("重置公式变量: " + key + " = " + resetValue + " (清空增量)");
            } else {
                logger.debug("重置变量: " + key + " = " + resetValue);
            }
            try {
                executeCycleActionsOnReset(variable, player);
            } catch (Exception exAct) {
                logger.error("执行重置动作失败: " + key, exAct);
            }
            return VariableResult.success(resetValue, "RESET", key, playerName);
        } catch (Exception e) {
            logger.error("重置变量失败: " + key, e);
            return VariableResult.fromException(e, "RESET", key, playerName);
        }
    }

    /**
     * 玩家退出时的数据处理（立即持久化该玩家数据）
     */
    public CompletableFuture<Void> handlePlayerQuit(OfflinePlayer player) {
        return persistenceManager.flushPlayerData(player.getUniqueId())
                .whenComplete((res, th) -> {
                    if (th != null) {
                        logger.error("玩家退出数据持久化失败: " + player.getName(), th);
                    } else {
                        logger.debug("玩家退出数据持久化完成: " + player.getName());
                        // 清理玩家的依赖快照
                        dependencyResolver.clearPlayerSnapshots(player);
                        logger.debug("清理玩家依赖快照: " + player.getName());
                        // 清理玩家的条件评估缓存
                        clearConditionCache(player);
                    }
                });
    }

    /**
     * 玩家上线时的数据预加载
     */
    public CompletableFuture<Void> handlePlayerJoin(OfflinePlayer player) {
        // 加载该玩家的持久化数据到内存
        return loadPlayerVariables(player)
                .thenRunAsync(() -> {
                    logger.debug("玩家上线数据加载完成: " + player.getName());
                }, asyncTaskManager.getExecutor());
    }

    // ======================== 公共查询方法 ========================

    public Variable getVariableDefinition(String key) {
        return variableRegistry.get(key);
    }

    public void rebuildRegenVariableKeys() {
        regenVariableKeys.clear();
        for (Map.Entry<String, Variable> entry : variableRegistry.entrySet()) {
            Variable variable = entry.getValue();
            if (variable != null && variable.hasAnyRegenRule()) {
                regenVariableKeys.add(entry.getKey());
            }
        }
    }

    public void reloadVariableDefinitions() {
        Map<String, Variable> nextRegistry = new HashMap<>();
        definitionLoader.loadAll(variable -> nextRegistry.put(variable.getKey(), variable));
        if (nextRegistry.isEmpty()) {
            logger.warn("变量定义重载失败：新配置为空，保留旧定义。");
            return;
        }
        variableRegistry.putAll(nextRegistry);
        variableRegistry.keySet().removeIf(key -> !nextRegistry.containsKey(key));
        rebuildRegenVariableKeys();
        validateVariableDefinitions();
    }

    /**
     * 从数据库重载指定玩家的变量到内存（用于跨服同步）
     *
     * @param player 玩家
     * @param key 变量键
     * @return CompletableFuture
     */
    public CompletableFuture<Void> reloadVariableFromDB(OfflinePlayer player, String key) {
        // 使用 thenCompose 链式组合，避免在异步任务中阻塞线程导致线程池死锁
        return CompletableFuture.supplyAsync(() -> getVariableDefinition(key), asyncTaskManager.getExecutor())
            .thenCompose(var -> {
                if (var == null) {
                    logger.debug("重载变量失败: 变量定义不存在 key=" + key);
                    return CompletableFuture.completedFuture(null);
                }

                if (var.isPlayerScoped() && player != null) {
                    // 从数据库查询并更新内存
                    String pid = player.getUniqueId().toString();
                    return fetchPlayerTriple(pid, key).thenAccept(tri -> {
                        if (tri.value != null) {
                            // 若该玩家该变量在内存中存在未落库的脏写入，则不允许 DB 覆盖，避免“离线改值后入服变回去”
                            if (shouldSkipDbOverwrite(player.getUniqueId(), key, tri.updatedAt)) {
                                return;
                            }
                            String normalizedValue = normalizeValueByType(tri.value, var.getValueType());

                            // 关键变量审计日志：显式从 DB 重载（INFO 级别）
                            if (isAuditKey(key)) {
                                VariableValue before = memoryStorage.getPlayerVariable(player.getUniqueId(), key);
                                String beforeVal = before != null ? before.getActualValue() : null;
                                logger.info(buildAuditLine(
                                        "DB_RELOAD", player, key, beforeVal, normalizedValue,
                                        "db.updated_at=" + tri.updatedAt));
                            }
                            memoryStorage.loadPlayerVariable(
                                    player.getUniqueId(), key, normalizedValue, tri.updatedAt, tri.firstModifiedAt);
                            logger.debug("重载玩家变量成功: player=" + player.getName() + ", key=" + key);
                        }
                    });
                } else if (var.isGlobal()) {
                    // 全局变量从数据库重载
                    return fetchServerTriple(key).thenAccept(tri -> {
                        if (tri.value != null) {
                            String normalizedValue = normalizeValueByType(tri.value, var.getValueType());
                            memoryStorage.loadServerVariable(key, normalizedValue, tri.updatedAt, tri.firstModifiedAt);
                            logger.debug("重载全局变量成功: key=" + key);
                        }
                    });
                }
                return CompletableFuture.completedFuture(null);
            })
            .exceptionally(ex -> {
                logger.debug("重载变量失败: " + key + ", 错误: " + ex.getMessage());
                return null;
            });
    }

    public Set<String> getAllVariableKeys() {
        return new HashSet<>(variableRegistry.keySet());
    }

    public Set<String> getPlayerVariableKeys() {
        return variableRegistry.entrySet().stream()
                .filter(e -> e.getValue().isPlayerScoped())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    public Set<String> getGlobalVariableKeys() {
        return variableRegistry.entrySet().stream()
                .filter(e -> e.getValue().isGlobal())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
     * 按在线玩家上下文刷新 regen（用于定时任务）
     */
    public void tickRegenForOnlinePlayers(List<? extends OfflinePlayer> players, long activeWindowMillis, int maxPlayers) {
        if (activeWindowMillis <= 0) {
            activeWindowMillis = 15000L;
        }
        if (maxPlayers <= 0) {
            maxPlayers = 64;
        }
        if (players == null || players.isEmpty()) {
            tickRegenForGlobal(null);
            return;
        }

        // 关键：Regen 不应依赖“玩家是否执行过 /get 指令”。
        // 在线玩家应被视为活跃玩家，否则会出现“完全不恢复”的体验。
        // 仍保留 activeWindow + maxPlayers 作为限流手段。
        for (OfflinePlayer p : players) {
            touchRecentPlayer(p);
        }

        OfflinePlayer context = players.get(0);
        tickRegenForGlobal(context);
        List<OfflinePlayer> activePlayers = selectActivePlayers(players, activeWindowMillis, maxPlayers);
        for (OfflinePlayer player : activePlayers) {
            if (player == null) {
                continue;
            }

            // 每次 tick 对每个玩家建立一次 params 缓存：同一变量的 group/conditions 仅评估一次
            final java.util.HashMap<String, EffectiveParams> paramsCache = new java.util.HashMap<>();

            // 重要：不要仅遍历“已存在于内存 Map 的变量”，否则首次从未写入/未持久化的变量不会被 regen 处理。
            // 对 activePlayers，仅遍历配置中声明了 regen 的变量键；缺失时用 initial 写入内存后再执行 regen。
            for (String key : regenVariableKeys) {
                if (key == null) {
                    continue;
                }
                Variable variable = getVariableDefinition(key);
                if (variable == null || !variable.isPlayerScoped()) {
                    continue;
                }

                boolean allowPapi = Bukkit.isPrimaryThread();
                EffectiveParams params = getEffectiveParamsCached(variable, player, allowPapi, paramsCache);
                if (params == null || !params.hasRegenRule()) {
                    continue;
                }

                VariableValue storage = memoryStorage.getPlayerVariable(player.getUniqueId(), key);
                if (storage == null) {
                    // 首次初始化：将 initial 写入内存，确保后续 regen 有 lastModified 基准
                    try {
                        String seed = getVariableFromMemoryOrDefault(player, variable, true);
                        boolean persist = variable.getLimitations() == null || variable.getLimitations().isPersistable();
                        if (persist) {
                            memoryStorage.setPlayerVariable(player.getUniqueId(), key, seed);
                        } else {
                            long now = System.currentTimeMillis();
                            memoryStorage.loadPlayerVariable(player.getUniqueId(), key, seed, now, now);
                        }
                        storage = memoryStorage.getPlayerVariable(player.getUniqueId(), key);
                    } catch (Exception e) {
                        // 防止坏配置导致每 tick 重复初始化失败
                        logger.warn("Regen 初始化变量失败，已跳过: player=" + getPlayerName(player)
                                + ", key=" + key
                                + ", err=" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                        storage = null;
                    }
                }
                if (storage == null) {
                    continue;
                }

                // 在同一 tick 内复用 params，并一次性解析 max/min（避免重复条件判断）
                Double maxVal = parseDoubleWithPlaceholders(params.getMax(), player, variable, allowPapi);
                Double minVal = parseDoubleWithPlaceholders(params.getMin(), player, variable, allowPapi);
                Limitations lim = params.getLimitations();
                if (maxVal == null && lim != null) {
                    maxVal = parseDoubleWithPlaceholders(lim.getMaxValue(), player, variable, allowPapi);
                }
                if (minVal == null && lim != null) {
                    minVal = parseDoubleWithPlaceholders(lim.getMinValue(), player, variable, allowPapi);
                }
                applyRegenIfNeededCached(player, variable, storage, storage.getActualValue(), allowPapi, params, maxVal, minVal);
            }
        }
    }

    /**
     * Regen 专用：缓存 EffectiveParams（同一 tick 内同一变量只评估一次 group/conditions）
     */
    private EffectiveParams getEffectiveParamsCached(Variable variable,
                                                     OfflinePlayer player,
                                                     boolean allowPapi,
                                                     java.util.Map<String, EffectiveParams> cache) {
        if (variable == null) {
            return null;
        }
        if (cache == null) {
            return getEffectiveParams(variable, player, allowPapi);
        }
        String k = variable.getKey();
        EffectiveParams cached = cache.get(k);
        if (cached != null) {
            return cached;
        }
        EffectiveParams params = getEffectiveParams(variable, player, allowPapi);
        if (params != null) {
            cache.put(k, params);
        }
        return params;
    }

    private void tickRegenForGlobal(OfflinePlayer contextPlayer) {
        Map<String, VariableValue> vars = memoryStorage.getServerVariables();
        if (vars.isEmpty()) {
            return;
        }
        for (Map.Entry<String, VariableValue> entry : vars.entrySet()) {
            if (!regenVariableKeys.contains(entry.getKey())) {
                continue;
            }
            Variable variable = getVariableDefinition(entry.getKey());
            if (variable == null || !variable.isGlobal()) {
                continue;
            }
            VariableValue storage = entry.getValue();
            if (storage == null) {
                continue;
            }
            applyRegenIfNeeded(contextPlayer, variable, storage, storage.getActualValue(), Bukkit.isPrimaryThread());
        }
    }

    private void touchRecentPlayer(OfflinePlayer player) {
        if (player == null) {
            return;
        }
        recentAccessPlayers.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private List<OfflinePlayer> selectActivePlayers(List<? extends OfflinePlayer> players, long activeWindowMillis, int maxPlayers) {
        long now = System.currentTimeMillis();
        recentAccessPlayers.entrySet().removeIf(entry -> now - entry.getValue() > activeWindowMillis);
        List<OfflinePlayer> active = new ArrayList<>();
        List<? extends OfflinePlayer> candidates = new ArrayList<>(players);
        Collections.shuffle(candidates);
        for (OfflinePlayer player : candidates) {
            if (player == null) {
                continue;
            }
            Long last = recentAccessPlayers.get(player.getUniqueId());
            if (last == null || now - last > activeWindowMillis) {
                continue;
            }
            active.add(player);
            if (active.size() >= maxPlayers) {
                break;
            }
        }
        return active;
    }

    /**
     * 验证变量作用域是否符合预期
     *
     * @param key       变量键
     * @param scopeType 期望的作用域类型
     * @return 若验证失败返回错误结果，否则返回 {@code Optional.empty()}
     */
    public Optional<VariableResult> validateScope(String key, ScopeType scopeType) {
        Variable variable = getVariableDefinition(key);
        if (variable == null) {
            return Optional.of(VariableResult.failure("变量不存在: " + key));
        }
        if (scopeType == ScopeType.PLAYER && !variable.isPlayerScoped()) {
            return Optional.of(VariableResult.failure("变量不是玩家作用域: " + key));
        }
        if (scopeType == ScopeType.GLOBAL && !variable.isGlobal()) {
            return Optional.of(VariableResult.failure("变量不是全局作用域: " + key));
        }
        return Optional.empty();
    }

    /**
     * 清理指定变量的全局上下文缓存（仅 server 上下文）
     * @deprecated 缓存层已移除，此方法仅为 API 兼容保留
     */
    @Deprecated
    public void invalidateGlobalCaches(String key) {
        // 缓存已移除，此方法保留仅为 API 兼容
        // 注意：不再删除 L1 内存数据，避免数据丢失风险
        logger.debug("invalidateGlobalCaches 已弃用，缓存层已移除: " + key);
    }

    /**
     * 获取当前公式增量值：
     * - 若不存在或处于严格模式计算结果，返回 "0"；
     * - 否则返回当前内存中的实际值（作为增量）。
     */
    private String getCurrentIncrementForFormula(VariableValue memVal) {
        return (memVal == null || memVal.isStrictComputed()) ? "0" : memVal.getActualValue();
    }

    /**
     * 统一封装：公式变量的加法计算
     * 返回新增量与用于展示的最终值
     */
    private FormulaOpResult computeFormulaAdd(Variable variable, OfflinePlayer player, String addValue, boolean allowPapi) {
        String resolvedAdd = resolveExpression(addValue, player, variable, allowPapi);
        VariableValue memVal = getMemoryValue(player, variable);
        String currInc = getCurrentIncrementForFormula(memVal);
        String newIncrement = calculateFormulaIncrement(variable.getValueType(), currInc, resolvedAdd, true);
        EffectiveParams params = getEffectiveParams(variable, player, allowPapi);
        String base = resolveExpression(params.getInitial(), player, variable, allowPapi);
        String displayValue = addFormulaIncrement(base, newIncrement, variable.getValueType());
        logger.debug("公式加法: key=" + variable.getKey()
                + ", strict=" + (memVal != null && memVal.isStrictComputed())
                + ", currInc=" + currInc
                + ", resolvedAdd=" + resolvedAdd
                + ", newInc=" + newIncrement
                + ", base=" + base
                + ", display=" + displayValue);
        return new FormulaOpResult(newIncrement, displayValue);
    }

    /**
     * 统一封装：公式变量的删除/减法计算
     * 返回新增量与用于展示的最终值
     */
    private FormulaOpResult computeFormulaRemove(Variable variable, OfflinePlayer player, String removeValue, boolean allowPapi) {
        VariableValue memVal = getMemoryValue(player, variable);
        String currInc = getCurrentIncrementForFormula(memVal);
        String resolvedRemove = resolveExpression(removeValue, player, variable, allowPapi);
        String newIncrement = calculateFormulaIncrement(
                variable.getValueType(), currInc, resolvedRemove, false);
        EffectiveParams params = getEffectiveParams(variable, player, allowPapi);
        String base = resolveExpression(params.getInitial(), player, variable, allowPapi);
        String displayValue = addFormulaIncrement(base, newIncrement, variable.getValueType());
        logger.debug("公式删除: key=" + variable.getKey()
                + ", strict=" + (memVal != null && memVal.isStrictComputed())
                + ", currInc=" + currInc
                + ", resolvedRemove=" + resolvedRemove
                + ", newInc=" + newIncrement
                + ", base=" + base
                + ", display=" + displayValue);
        return new FormulaOpResult(newIncrement, displayValue);
    }

    /**
     * 清理指定变量的所有缓存
     * @deprecated 缓存层已移除，此方法仅为 API 兼容保留
     */
    @Deprecated
    public void invalidateAllCaches(String key) {
        invalidateGlobalCaches(key);
    }

    /**
     * 清理指定玩家上下文下的变量缓存（不触碰内存存储，不产生删除标记）
     * @deprecated 缓存层已移除，此方法仅为 API 兼容保留
     */
    @Deprecated
    public void invalidateCachesForPlayer(OfflinePlayer player, String key) {
        // 缓存已移除，此方法保留仅为 API 兼容
        logger.debug("invalidateCachesForPlayer 已弃用，缓存层已移除");
    }

    /**
     * 一次性清理指定玩家上下文下的所有 L2 表达式缓存（不影响 L3）
     * @return 实际清理的 L2 条目数
     * @deprecated 缓存层已移除，此方法仅为 API 兼容保留，始终返回 0
     */
    @Deprecated
    public int invalidateAllL2ForPlayer(OfflinePlayer player) {
        // 缓存已移除，返回 0 表示未清理任何条目
        return 0;
    }

    /**
     * 获取累计的 L2 清理条目数（自插件启动以来）
     * @deprecated 缓存层已移除，此方法仅为 API 兼容保留，始终返回 0
     */
    @Deprecated
    public long getL2InvalidationsTotal() { return 0L; }

    /**
     * 获取累计的 L3 清理条目数（自插件启动以来）
     * @deprecated 缓存层已移除，此方法仅为 API 兼容保留，始终返回 0
     */
    @Deprecated
    public long getL3InvalidationsTotal() { return 0L; }

    /**
     * 仅清理 L3（最终结果）缓存，不触碰 L2
     * @deprecated 缓存层已移除，此方法仅为 API 兼容保留
     */
    @Deprecated
    public void invalidateL3Only(OfflinePlayer player, String key) {
        // 缓存已移除，此方法保留仅为 API 兼容
    }

    /**
     * 从内存中删除变量
     * @param key 变量键
     */
    public void removeVariableFromMemoryAndCache(String key) {
        try {
            Variable variable = getVariableDefinition(key);
            if (variable == null) {
                logger.warn("变量不存在: " + key);
                return;
            }
            if (variable.isGlobal()) {
                memoryStorage.removeServerVariable(key);
            } else if (variable.isPlayerScoped()) {
                memoryStorage.removeVariableForAllPlayers(key, true);
            }
            logger.debug("已从内存删除变量: " + key);
        } catch (Exception e) {
            logger.error("删除变量失败: " + key, e);
        }
    }

    // ======================== 私有辅助方法（抽取复用逻辑） ========================

    /**
     * 统一的操作前置校验：
     * 1) 变量是否存在 2)（可选）是否可写
     */
    private Optional<VariableResult> checkOpPreconditions(
            OfflinePlayer player, Variable variable, String key, String op, String playerName, boolean requireWritable, boolean allowPapi) {
        if (variable == null) {
            return Optional.of(VariableResult.failure("变量不存在: " + key, op, key, playerName));
        }
        if (requireWritable && variable.getLimitations() != null && variable.getLimitations().isReadOnly()) {
            return Optional.of(VariableResult.failure("变量为只读模式: " + key, op, key, playerName));
        }
        // 条件门控：所有操作在继续前判定
        try {
            if (variable.hasConditions() && !evaluateConditions(player, variable, allowPapi)) {
                logger.debug("变量门控未通过: op=" + op + ", key=" + key + ", player=" + playerName);
                return Optional.of(VariableResult.failure("变量不满足访问条件: " + key, op, key, playerName));
            }
        } catch (Exception e) {
            logger.warn("变量门控评估异常，拒绝访问: key=" + key + ", player=" + playerName);
            return Optional.of(VariableResult.failure("变量条件评估异常: " + key, op, key, playerName));
        }
        return Optional.empty();
    }

    /** 评估变量的门控条件：全部为 true 才通过。解析错误按 false 处理 */
    private boolean evaluateConditions(OfflinePlayer player, Variable variable) {
        return evaluateConditions(player, variable, true);
    }

    private boolean evaluateConditions(OfflinePlayer player, Variable variable, boolean allowPapi) {
        List<String> conds = variable.getConditions();
        if (conds == null || conds.isEmpty()) return true;
        Set<String> stack = gatingEvaluatingStack.get();
        String k = variable.getKey();
        if (stack.contains(k)) {
            logger.debug("检测到变量门控自引用，拒绝通过: " + k);
            return false;
        }
        stack.add(k);
        try {
        int idx = 0;
        for (String raw : conds) {
            idx++;
            if (isBlank(raw)) {
                logger.debug("变量条件为空，忽略: " + variable.getKey());
                continue;
            }
            String resolved = resolveInternalVariablesInternal(raw, player, allowPapi);
            if (!isConditionLengthValid(resolved, variable)) {
                logger.debug("变量条件长度超限，视为不通过: key=" + variable.getKey());
                return false;
            }

            boolean pass;
            try {
                if (!allowPapi && resolved.contains("%")) {
                    logger.debug("异步操作跳过含 PAPI 的条件评估: " + variable.getKey());
                    pass = false;
                } else {
                    pass = conditionEvaluator.parse(allowPapi ? toOnlinePlayer(player) : null, resolved);
                }
            } catch (Exception e) {
                logger.debug("变量条件解析异常: key=" + variable.getKey() + ", 条件#" + idx + " => 异常: " + e.getMessage());
                return false;
            }

            if (!pass) {
                String condPreview = raw.length() > 64 ? raw.substring(0, 64) + "..." : raw;
                logger.debug("变量门控失败: key=" + variable.getKey() + ", 条件#" + idx + " '" + condPreview + "'");
                return false;
            }
        }
        return true;
        } finally {
            stack.remove(k);
        }
    }

    /**
     * 评估条件列表（供 ParameterGroupResolver 复用）
     *
     * @param player 玩家上下文
     * @param conditions 条件表达式列表
     * @return 是否全部通过
     */
    private boolean evaluateConditionsList(OfflinePlayer player, List<String> conditions) {
        return evaluateConditionsList(player, conditions, Bukkit.isPrimaryThread());
    }

    private boolean evaluateConditionsList(OfflinePlayer player, List<String> conditions, boolean allowPapi) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        Player onlinePlayer = toOnlinePlayer(player);
        for (String cond : conditions) {
            if (cond == null || cond.trim().isEmpty()) {
                continue;
            }
            String resolved = resolveInternalVariablesInternal(cond, player, allowPapi);
            if (!isConditionLengthValid(resolved, null)) {
                return false;
            }
            try {
                if (!allowPapi && resolved.contains("%")) {
                    // 异步路径下无法评估 PAPI 条件时，尝试从缓存获取结果
                    // 如果缓存中有该玩家+条件的匹配结果，使用缓存；否则保守返回 true（不做破坏性回退）
                    if (player != null) {
                        Boolean cached = getConditionCacheResult(player, cond);
                        if (cached != null) {
                            if (!cached) {
                                return false;
                            }
                            continue; // 缓存命中且为 true，继续下一个条件
                        }
                    }
                    // 无缓存时：保守策略 - 返回 true 以避免错误截断
                    // 这样异步路径下 group 会"乐观匹配"，但不会导致值被错误截断到基础配置
                    logger.debug("异步路径跳过 PAPI 条件评估（保守通过）: " + cond);
                    continue;
                }
                boolean result = conditionEvaluator.parse(allowPapi ? onlinePlayer : null, resolved);
                // 缓存主线程评估结果，供异步路径使用
                if (allowPapi && player != null) {
                    cacheConditionResult(player, cond, result);
                }
                if (!result) {
                    return false;
                }
            } catch (Exception e) {
                logger.debug("参数组条件解析异常: " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    // ======================== 条件评估结果缓存（用于异步路径） ========================

    /**
     * 条件评估结果缓存：玩家 UUID -> (条件表达式 -> 评估结果)
     * 使用 Caffeine 缓存，自动过期以避免权限变更后的不一致
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, Boolean> conditionResultCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(10000)
                    .expireAfterWrite(java.time.Duration.ofSeconds(30)) // 30 秒过期，平衡一致性与性能
                    .build();

    private String buildConditionCacheKey(OfflinePlayer player, String condition) {
        // 直接使用 condition 字符串作为键，消除 hashCode 碰撞风险
        // condition 是配置文件的条件表达式，长度通常可控
        return player.getUniqueId().toString() + "@" + condition;
    }

    private Boolean getConditionCacheResult(OfflinePlayer player, String condition) {
        return conditionResultCache.getIfPresent(buildConditionCacheKey(player, condition));
    }

    private void cacheConditionResult(OfflinePlayer player, String condition, boolean result) {
        conditionResultCache.put(buildConditionCacheKey(player, condition), result);
    }

    /**
     * 清理玩家的条件缓存（玩家退出或权限变更时调用）
     */
    public void clearConditionCache(OfflinePlayer player) {
        if (player == null) return;
        String prefix = player.getUniqueId().toString() + "@";
        conditionResultCache.asMap().keySet().removeIf(key -> key.startsWith(prefix));
    }

    private Player toOnlinePlayer(OfflinePlayer player) {
        return isOnline(player) ? player.getPlayer() : null;
    }

    private boolean isConditionLengthValid(String expression, Variable variable) {
        if (expression == null) {
            return false;
        }
        int maxLen = MAX_EXPRESSION_LENGTH;
        if (variable != null && variable.getLimitations() != null) {
            Limitations lim = variable.getLimitations();
            if (lim.getMaxExpressionLength() != null) {
                maxLen = lim.getMaxExpressionLength();
            }
        }
        if (expression.length() > maxLen) {
            logger.warn("条件表达式长度超出限制，已拒绝: "
                    + expression.substring(0, Math.min(100, expression.length())) + "...");
            return false;
        }
        return true;
    }

    /** 获取玩家名，若为空则返回 "SERVER" */
    private String getPlayerName(OfflinePlayer player) {
        return player != null ? player.getName() : "SERVER";
    }

    /** 是否在线（避免 NPE 与 PAPI 无上下文问题） */
    private boolean isOnline(OfflinePlayer player) {
        return player != null && player.isOnline() && player.getPlayer() != null;
    }

    private boolean isNumberInRange(double value, String minStr, String maxStr) {
        if (minStr != null) {
            try {
                double min = Double.parseDouble(minStr);
                if (value < min) return false;
            } catch (NumberFormatException ignored) { }
        }
        if (maxStr != null) {
            try {
                double max = Double.parseDouble(maxStr);
                if (value > max) return false;
            } catch (NumberFormatException ignored) { }
        }
        return true;
    }

    /** 字符串判空（包含 trim） */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 
     * 获取内存存储中的 VariableValue 引用
     * 
     * 穿透查询策略：当内存未命中时，同步从数据库加载到内存，解决跨服离线玩家数据访问问题
     */
    private VariableValue getMemoryValue(OfflinePlayer player, Variable variable) {
        if (variable.isPlayerScoped() && player != null) {
            VariableValue val = memoryStorage.getPlayerVariable(player.getUniqueId(), variable.getKey());
            if (val == null) {
                // 穿透查询：内存未命中时从数据库加载
                val = loadPlayerVariableFromDBSync(player, variable.getKey());
            }
            return val;
        } else if (variable.isGlobal()) {
            return memoryStorage.getServerVariable(variable.getKey());
        }
        return null;
    }

    /**
     * 同步从数据库加载玩家变量到内存（穿透查询）
     * 
     * 用于跨服场景下离线玩家数据的即时加载
     * 
     * 注意事项：
     * - 此方法会阻塞调用线程（建议预算 <= 50ms），避免在主线程高频调用
     * - 包含竞态保护：DB 查询期间若内存已被其他线程写入，优先使用内存值
     * - 包含缓存穿透保护：DB 不存在时写入负缓存（TTL），避免重复查询
     * 
     * @param player 玩家
     * @param key 变量键
     * @return 加载后的 VariableValue；若数据库无记录或 DB 读取失败，则返回 null（上层将回退默认值）
     */
    private VariableValue loadPlayerVariableFromDBSync(OfflinePlayer player, String key) {
        if (player == null || key == null) {
            return null;
        }
        try {
            String pid = player.getUniqueId().toString();
            UUID playerId = player.getUniqueId();

            // 负缓存：DB 确认无记录时，短时间内不再穿透，避免 DB miss 风暴
            String negKey = playerId + ":" + key;
            if (playerNegativeCache.getIfPresent(negKey) != null) {
                return null;
            }

            // 同步查询数据库（穿透查询场景，需要立即获取结果）
            // 重要：DB 异常/超时不能被当作“无记录”，否则会导致默认值参与后续写入，造成数据丢失。
            ValueTriple tri;
            try {
                long timeoutMs = Bukkit.isPrimaryThread() ? MAIN_THREAD_DB_READ_TIMEOUT_MS : ASYNC_THREAD_DB_READ_TIMEOUT_MS;
                tri = fetchPlayerTriple(pid, key).get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception ex) {
                // Fail-closed：DB 读取失败时不允许回退到默认值继续参与后续 ADD/REMOVE 计算，否则可能造成数据丢失。
                throw new RuntimeException("DB穿透查询失败: player=" + getPlayerName(player) + ", key=" + key
                        + ", err=" + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()), ex);
            }

            // [竞态保护] DB 查询期间内存可能已被其他线程写入，需二次检查
            VariableValue concurrentVal = memoryStorage.getPlayerVariable(playerId, key);
            if (concurrentVal != null) {
                logger.debug("穿透查询跳过: 内存已存在值 player=" + player.getName() + ", key=" + key);
                return concurrentVal;
            }

            if (tri.value != null) {
                // 命中：清理负缓存
                playerNegativeCache.invalidate(negKey);
                // 类型规范化
                Variable var = getVariableDefinition(key);
                String normalizedValue = (var != null)
                        ? normalizeValueByType(tri.value, var.getValueType())
                        : tri.value;

                // 加载到内存
                memoryStorage.loadPlayerVariable(playerId, key, normalizedValue, tri.updatedAt, tri.firstModifiedAt);

                logger.debug("穿透查询成功: player=" + player.getName() + ", key=" + key + ", value=" + normalizedValue);
                return memoryStorage.getPlayerVariable(playerId, key);
            } else {
                // [缓存穿透保护] 数据库无记录：写入负缓存（TTL），不污染内存存储
                playerNegativeCache.put(negKey, Boolean.TRUE);
            }
            return null;
        } catch (Exception e) {
            // 若这里吞掉异常，会被上层当成“无记录”回退默认值，进而导致数据丢失。
            // 因此这里同样 fail-closed。
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("穿透查询异常: player=" + getPlayerName(player) + ", key=" + key
                    + ", err=" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
        }
    }

    /** 从内存获取变量值或返回默认/初始值（含严格模式与公式处理） */
    private String getVariableFromMemoryOrDefault(OfflinePlayer player, Variable variable) {
        return getVariableFromMemoryOrDefault(player, variable, true);
    }

        private String getVariableFromMemoryOrDefault(OfflinePlayer player, Variable variable, boolean allowPapi) {
        String varKey = variable.getKey();
        Set<String> stack = valueResolvingStack.get();
        if (stack.contains(varKey)) {
            logger.warn("检测到变量值循环引用: " + varKey);
            return getDefaultValueByType(variable.getValueType());
        }
        stack.add(varKey);

        try {
            // 内部访问也进行门控，但失败时返回空串避免连锁失败
            try {
                if (variable.hasConditions()) {
                    if (!evaluateConditions(player, variable, allowPapi)) {
                        logger.debug("内部访问门控未通过: " + variable.getKey());
                        return "";
                    }
                }
            } catch (Exception e) {
                logger.debug("内部访问门控评估异常，返回空串: " + variable.getKey());
                return "";
            }

            EffectiveParams params = getEffectiveParams(variable, player, allowPapi);
            VariableValue val = getMemoryValue(player, variable);
            String init = params.getInitial();
            boolean isFormula = isFormulaVariable(variable);

            // 严格初始值模式
            if (variable.isStrictInitialMode() && !isBlank(init)) {
                boolean hasCycle = !isBlank(variable.getCycle());

                if (val != null) {
                    // 已存在
                    if (val.isStrictComputed()) {
                        String currentValue = val.getActualValue();
                        logger.debug("使用已计算的严格模式值" + (hasCycle ? "(有cycle)" : "(无cycle)") + ": " + variable.getKey() + " = " + currentValue);
                        return finalizeValueWithRegen(player, variable, val, currentValue, allowPapi);
                    } else {
                        // 首次严格计算
                        String calculatedValue = calculateStrictInitialValue(variable, player, init, allowPapi);
                        String finalValue = calculatedValue;

                        // 公式变量叠加当前增量
                        if (isFormula) {
                            String currentVal = val.getActualValue();
                            String defaultVal = getDefaultValueByType(variable.getValueType());
                            try {
                                double current = Double.parseDouble(currentVal);
                                double defaultV = Double.parseDouble(defaultVal);
                                double calculated = Double.parseDouble(calculatedValue);
                                double increment = current - defaultV;
                                if (variable.getValueType() == ValueType.INT) {
                                    int result = (int) Math.round(calculated + increment);
                                    finalValue = String.valueOf(result);
                                } else {
                                    finalValue = String.valueOf(calculated + increment);
                                }
                                logger.debug("严格模式公式变量增量计算: " + calculatedValue + " + " + increment + " = " + finalValue);
                            } catch (NumberFormatException ignored) {
                                finalValue = calculatedValue;
                            }
                        }

                        // 标记完成严格计算
                        val.setStrictValue(finalValue);
                        logger.info("完成严格模式初始值计算" + (hasCycle ? "(有cycle)" : "(无cycle)") + ": " + variable.getKey() + " = " + finalValue);
                        return finalizeValueWithRegen(player, variable, val, finalValue, allowPapi);
                    }
                } else {
                    // 首次创建
                    String calculatedValue = calculateStrictInitialValue(variable, player, init, allowPapi);
                    updateMemoryAndInvalidate(player, variable, "STRICT:" + calculatedValue + ":" + System.currentTimeMillis());
                    logger.info("首次创建严格模式变量" + (hasCycle ? "(有cycle)" : "(无cycle)") + ": " + variable.getKey() + " = " + calculatedValue);
                    return finalizeValueWithRegen(player, variable, val, calculatedValue, allowPapi);
                }
            }

            // 默认（非严格）行为
            if (isFormula && !isBlank(init)) {
                String base = resolveExpression(init, player, variable, allowPapi);
                String res = (val != null) ? addFormulaIncrement(base, val.getActualValue(), variable.getValueType()) : base;
                return finalizeValueWithRegen(player, variable, val, res, allowPapi);
            }
            if (val != null) {
                return finalizeValueWithRegen(player, variable, val, val.getActualValue(), allowPapi);
            }
            if (!isBlank(init)) {
                String resolvedInit = resolveExpression(init, player, variable, allowPapi);
                return finalizeValueWithRegen(player, variable, val, resolvedInit, allowPapi);
            }
            return finalizeValueWithRegen(player, variable, val, getDefaultValueByType(variable.getValueType()), allowPapi);
        } finally {
            stack.remove(varKey);
        }
    }

    /** 设置变量到内存并按 persistable 配置处理脏标记与缓存失效（默认 OTHER 原因） */
    private void updateMemoryAndInvalidate(OfflinePlayer player, Variable variable, String value) {
        updateMemoryAndInvalidate(player, variable, value, PlayerVariableChangeEvent.ChangeReason.OTHER);
    }

    /** 设置变量到内存并按 persistable 配置处理脏标记与缓存失效，并触发变更事件 */
    private void updateMemoryAndInvalidate(OfflinePlayer player, Variable variable, String value,
                                           PlayerVariableChangeEvent.ChangeReason reason) {
        boolean persist = variable.getLimitations() == null || variable.getLimitations().isPersistable();
        String oldValue = null;

        if (variable.isPlayerScoped() && player != null) {
            // 获取旧值
            VariableValue oldVarValue = memoryStorage.getPlayerVariable(player.getUniqueId(), variable.getKey());
            if (oldVarValue != null) {
                oldValue = oldVarValue.getValue();
            }

            memoryStorage.setPlayerVariable(player.getUniqueId(), variable.getKey(), value);

            // 关键变量审计日志（INFO 级别）
            if (isAuditKey(variable.getKey())) {
                logger.info(buildAuditLine(
                        "WRITE", player, variable.getKey(), oldValue, value,
                        reason != null ? reason.name() : "OTHER"));
            }
            if (!persist) {
                memoryStorage.clearDirtyFlag("player:" + player.getUniqueId() + ":" + variable.getKey());
                logger.debug("变量设置为不可持久化，跳过数据库保存: " + variable.getKey());
            }

            // 离线写入兜底：立刻触发该玩家的数据刷盘，避免玩家入服时 DB 覆盖内存导致“变回去”
            if (persist && !player.isOnline()) {
                try {
                    persistenceManager.flushPlayerData(player.getUniqueId())
                            .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                            .exceptionally(ex -> {
                                logger.warn("离线玩家变量写入触发持久化失败: uuid=" + player.getUniqueId()
                                        + ", key=" + variable.getKey()
                                        + ", err=" + (ex != null ? ex.getMessage() : "unknown"));
                                return null;
                            });
                } catch (Exception ex) {
                    logger.warn("离线玩家变量写入触发持久化异常: uuid=" + player.getUniqueId()
                            + ", key=" + variable.getKey()
                            + ", err=" + ex.getMessage());
                }
            }

            // 当前上下文缓存失效
            invalidateCachesSafely(player, variable.getKey());

            // 触发玩家变量变更事件（异步）
            firePlayerVariableChangeEvent(player, variable.getKey(), oldValue, value, reason);

        } else if (variable.isGlobal()) {
            memoryStorage.setServerVariable(variable.getKey(), value);
            if (!persist) {
                memoryStorage.clearDirtyFlag("server:" + variable.getKey());
                logger.debug("服务器变量设置为不可持久化，跳过数据库保存: " + variable.getKey());
            }
            // 全局上下文缓存一并清理
            invalidateCachesSafely(null, variable.getKey());
            // 注意：全局变量暂不触发事件，因为 PlayerVariableChangeEvent 需要 player
        }
    }

    // ======================== 审计日志相关 ========================

    /**
     * 需要审计的变量 key 列表（可配置化扩展）
     */
    private static final java.util.Set<String> AUDIT_KEYS = java.util.Set.of(
            "rank", "player_energy", "hp", "ep"
    );

    /**
     * 判断变量是否需要审计日志
     */
    private boolean isAuditKey(String key) {
        if (key == null) return false;
        return AUDIT_KEYS.contains(key.toLowerCase());
    }

    /**
     * 审计日志构造（尽量短且可检索）
     */
    private String buildAuditLine(String action, OfflinePlayer player, String key,
                                      String oldValue, String newValue, String reason) {
        String playerName = (player != null ? player.getName() : "SERVER");
        String playerId = (player != null ? String.valueOf(player.getUniqueId()) : "-");
        String thread = Thread.currentThread().getName();
        // 性能优化：仅对低频关键变量(rank)记录堆栈，hp/ep/player_energy 等高频变量跳过
        String caller = "rank".equalsIgnoreCase(key) ? getCallerSummary(6) : "(-)";
        return "[VarAudit] action=" + action
                + ", key=" + key
                + ", player=" + playerName
                + ", uuid=" + playerId
                + ", old=" + String.valueOf(oldValue)
                + ", new=" + String.valueOf(newValue)
                + ", reason=" + String.valueOf(reason)
                + ", thread=" + thread
                + ", caller=" + caller;
    }

    /**
     * 获取调用方摘要（截取若干栈帧，避免日志过长）
     */
    private String getCallerSummary(int maxFrames) {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            // st[0]=getStackTrace, st[1]=getCallerSummary, st[2]=buildRankAuditLine, st[3]=updateMemoryAndInvalidate...
            int start = 4;
            StringBuilder sb = new StringBuilder();
            int appended = 0;
            for (int i = start; i < st.length && appended < Math.max(1, maxFrames); i++) {
                StackTraceElement e = st[i];
                if (e == null) continue;
                String cls = e.getClassName();
                // 跳过 JDK/反射框架栈
                if (cls != null && (cls.startsWith("java.") || cls.startsWith("jdk."))) {
                    continue;
                }
                if (sb.length() > 0) sb.append(" <- ");
                sb.append(e.getClassName()).append("#").append(e.getMethodName()).append(":").append(e.getLineNumber());
                appended++;
            }
            return sb.length() == 0 ? "(unknown)" : sb.toString();
        } catch (Exception ignored) {
            return "(unknown)";
        }
    }

    /**
     * 异步触发玩家变量变更事件
     */
    private void firePlayerVariableChangeEvent(OfflinePlayer player, String variableKey,
                                                String oldValue, String newValue,
                                                PlayerVariableChangeEvent.ChangeReason reason) {
        try {
            // 判断当前是否在主线程
            boolean isAsync = !Bukkit.isPrimaryThread();
            PlayerVariableChangeEvent event = new PlayerVariableChangeEvent(
                    player, variableKey, oldValue, newValue, reason, isAsync
            );

            if (isAsync) {
                // 已经在异步线程，直接触发（监听方需自行切回主线程调用 Bukkit API）
                Bukkit.getPluginManager().callEvent(event);
            } else {
                // 在主线程，异步触发以避免阻塞
                asyncTaskManager.getExecutor().execute(() -> {
                    PlayerVariableChangeEvent asyncEvent = new PlayerVariableChangeEvent(
                            player, variableKey, oldValue, newValue, reason, true
                    );
                    Bukkit.getPluginManager().callEvent(asyncEvent);
                });
            }
        } catch (Exception e) {
            logger.warn("触发变量变更事件失败: " + variableKey + " - " + e.getMessage());
        }
    }

    /** 从内存删除变量并清除缓存 */
    private void removeFromMemoryAndInvalidate(OfflinePlayer player, Variable variable) {
        if (variable.isPlayerScoped() && player != null) {
            memoryStorage.removePlayerVariable(player.getUniqueId(), variable.getKey());
        } else if (variable.isGlobal()) {
            memoryStorage.removeServerVariable(variable.getKey());
        }
        invalidateCachesSafely(player, variable.getKey());
    }

    /** 缓存失效的安全封装（缓存层已移除，此方法为空操作） */
    private void invalidateCachesSafely(OfflinePlayer player, String key) {
        // 缓存层已移除，此方法保留仅为代码兼容
    }

    /** 统一出口：在返回前应用渐进恢复 */
    private String finalizeValueWithRegen(OfflinePlayer player, Variable variable, VariableValue storage, String value) {
        return finalizeValueWithRegen(player, variable, storage, value, true);
    }

    private String finalizeValueWithRegen(OfflinePlayer player, Variable variable, VariableValue storage, String value, boolean allowPapi) {
        return applyRegenIfNeeded(player, variable, storage, value, allowPapi);
    }

    /**
     * 数值渐进恢复：仅对 INT/DOUBLE、生效 regen 规则的变量执行
     */
    private String applyRegenIfNeeded(OfflinePlayer player, Variable variable, VariableValue storage, String currentValue, boolean allowPapi) {
        if (variable == null || storage == null) {
            return currentValue;
        }
        EffectiveParams params = getEffectiveParams(variable, player, allowPapi);
        if (!params.hasRegenRule()) {
            return currentValue;
        }
        ValueType vt = variable.getValueType();
        if (vt != ValueType.INT && vt != ValueType.DOUBLE) {
            return currentValue;
        }
        // 重要修复：max/min 必须使用 group 生效后的 EffectiveParams，而不是 variable 基础配置。
        Double maxVal = parseDoubleWithPlaceholders(params.getMax(), player, variable, allowPapi);
        Double minVal = parseDoubleWithPlaceholders(params.getMin(), player, variable, allowPapi);

        Limitations lim = params.getLimitations();
        String maxRaw = params.getMax();
        String minRaw = params.getMin();
        if (maxRaw == null && lim != null) {
            maxRaw = lim.getMaxValue();
            if (maxVal == null) {
                maxVal = parseDoubleWithPlaceholders(maxRaw, player, variable, allowPapi);
            }
        }
        if (minRaw == null && lim != null) {
            minRaw = lim.getMinValue();
            if (minVal == null) {
                minVal = parseDoubleWithPlaceholders(minRaw, player, variable, allowPapi);
            }
        }
        if ((maxRaw != null && maxVal == null) || (minRaw != null && minVal == null)) {
            return currentValue;
        }

        // Lambda 中需要捕获 final/effectively final
        final Double fMaxVal = maxVal;
        final Double fMinVal = minVal;
        long now = System.currentTimeMillis();
        RegenRule rule = params.getRegenRule();

        if (variable.isPlayerScoped() && player != null) {
            String[] resultHolder = new String[1];
            memoryStorage.updatePlayerVariableAtomic(player.getUniqueId(), variable.getKey(), old -> {
                if (old == null) {
                    resultHolder[0] = currentValue;
                    return old;
                }
                String baseValue = old.getActualValue();
                Double current = parseDoubleSafe(baseValue);
                if (current == null) {
                    resultHolder[0] = baseValue;
                    return old;
                }
                if (fMaxVal != null && current >= fMaxVal - 1e-9) {
                    resultHolder[0] = baseValue;
                    return old;
                }
                double gain = rule.calculateGain(old.getLastModified(), now, resolveRegenZone());
                if (gain <= 0) {
                    resultHolder[0] = baseValue;
                    return old;
                }
                double next = current + gain;
                if (fMaxVal != null) {
                    next = Math.min(fMaxVal, next);
                }
                if (fMinVal != null) {
                    next = Math.max(fMinVal, next);
                }
                String formatted = formatNumber(vt, next);
                old.setValueAndMarkDirty(formatted);
                resultHolder[0] = formatted;
                return old;
            });
            return resultHolder[0] != null ? resultHolder[0] : currentValue;
        }

        if (variable.isGlobal()) {
            String[] resultHolder = new String[1];
            memoryStorage.updateServerVariableAtomic(variable.getKey(), old -> {
                if (old == null) {
                    resultHolder[0] = currentValue;
                    return old;
                }
                String baseValue = old.getActualValue();
                Double current = parseDoubleSafe(baseValue);
                if (current == null) {
                    resultHolder[0] = baseValue;
                    return old;
                }
                if (fMaxVal != null && current >= fMaxVal - 1e-9) {
                    resultHolder[0] = baseValue;
                    return old;
                }
                double gain = rule.calculateGain(old.getLastModified(), now, resolveRegenZone());
                if (gain <= 0) {
                    resultHolder[0] = baseValue;
                    return old;
                }
                double next = current + gain;
                if (fMaxVal != null) {
                    next = Math.min(fMaxVal, next);
                }
                if (fMinVal != null) {
                    next = Math.max(fMinVal, next);
                }
                String formatted = formatNumber(vt, next);
                old.setValueAndMarkDirty(formatted);
                resultHolder[0] = formatted;
                return old;
            });
            return resultHolder[0] != null ? resultHolder[0] : currentValue;
        }

        return currentValue;
    }

    /**
     * Regen 专用：调用方已计算好 params/max/min，避免重复 group/conditions 评估。
     */
    private String applyRegenIfNeededCached(OfflinePlayer player,
                                            Variable variable,
                                            VariableValue storage,
                                            String currentValue,
                                            boolean allowPapi,
                                            EffectiveParams params,
                                            Double maxVal,
                                            Double minVal) {
        if (variable == null || storage == null || params == null) {
            return currentValue;
        }
        if (!params.hasRegenRule()) {
            return currentValue;
        }
        ValueType vt = variable.getValueType();
        if (vt != ValueType.INT && vt != ValueType.DOUBLE) {
            return currentValue;
        }
        RegenRule rule = params.getRegenRule();
        if (rule == null) {
            return currentValue;
        }
        final Double fMaxVal = maxVal;
        final Double fMinVal = minVal;
        final long now = System.currentTimeMillis();

        String[] resultHolder = new String[1];
        memoryStorage.updatePlayerVariableAtomic(player.getUniqueId(), variable.getKey(), old -> {
            if (old == null) {
                resultHolder[0] = currentValue;
                return old;
            }
            String baseValue = old.getActualValue();
            Double current = parseDoubleSafe(baseValue);
            if (current == null) {
                resultHolder[0] = baseValue;
                return old;
            }
            if (fMaxVal != null && current >= fMaxVal - 1e-9) {
                resultHolder[0] = baseValue;
                return old;
            }
            double gain = rule.calculateGain(old.getLastModified(), now, resolveRegenZone());
            if (gain <= 0) {
                resultHolder[0] = baseValue;
                return old;
            }
            double next = current + gain;
            if (fMaxVal != null) {
                next = Math.min(fMaxVal, next);
            }
            if (fMinVal != null) {
                next = Math.max(fMinVal, next);
            }
            String formatted = formatNumber(vt, next);
            old.setValueAndMarkDirty(formatted);
            resultHolder[0] = formatted;
            return old;
        });
        return resultHolder[0] != null ? resultHolder[0] : currentValue;
    }

    private Double resolveMax(OfflinePlayer player, Variable variable, boolean allowPapi) {
        EffectiveParams params = getEffectiveParams(variable, player, allowPapi);
        Double v = parseDoubleWithPlaceholders(params.getMax(), player, variable, allowPapi);
        if (v != null) return v;
        if (variable.getLimitations() != null) {
            v = parseDoubleWithPlaceholders(params.getLimitations().getMaxValue(), player, variable, allowPapi);
        }
        return v;
    }

    private Double resolveMin(OfflinePlayer player, Variable variable, boolean allowPapi) {
        EffectiveParams params = getEffectiveParams(variable, player, allowPapi);
        Double v = parseDoubleWithPlaceholders(params.getMin(), player, variable, allowPapi);
        if (v != null) return v;
        if (variable.getLimitations() != null) {
            v = parseDoubleWithPlaceholders(params.getLimitations().getMinValue(), player, variable, allowPapi);
        }
        return v;
    }

    private Double parseDoubleSafe(String raw) {
        if (isBlank(raw)) return null;
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 支持占位符/表达式的数值解析（用于 min/max 动态化） */
    private Double parseDoubleWithPlaceholders(String raw, OfflinePlayer player, Variable variable, boolean allowPapi) {
        if (isBlank(raw)) return null;
        String candidate = raw;
        // 仅在存在占位符或运算符时才解析，避免无谓的 resolveExpression
        if (raw.contains("%") || raw.contains("${") || raw.matches(".*[+\\-*/()^].*")) {
            candidate = resolveExpression(raw, player, variable, allowPapi);
        }
        return parseDoubleSafe(candidate);
    }

    private String formatNumber(ValueType vt, double value) {
        if (vt == ValueType.INT) {
            return String.valueOf((int) Math.floor(value));
        }
        java.math.BigDecimal bd = java.math.BigDecimal.valueOf(value).stripTrailingZeros();
        return bd.toPlainString();
    }

    /**
     * 计算指定变量的下次恢复剩余秒数（仅对数值型、带 regen 的变量有效）
     */
    private String resolveRegenCountdown(String targetKey, OfflinePlayer player) {
        if (isBlank(targetKey)) {
            return null;
        }
        Variable target = getVariableDefinition(targetKey);
        if (target == null) {
            return "0";
        }
        EffectiveParams params = getEffectiveParams(target, player, Bukkit.isPrimaryThread());
        if (!params.hasRegenRule()) {
            return "0";
        }
        ValueType vt = target.getValueType();
        if (vt != ValueType.INT && vt != ValueType.DOUBLE) {
            return "0";
        }
        VariableValue storage = getMemoryValue(player, target);
        if (storage == null) {
            return "0";
        }
        Double current = parseDoubleSafe(storage.getActualValue());
        Double maxVal = resolveMax(player, target, Bukkit.isPrimaryThread());
        if (current != null && maxVal != null && current >= maxVal - 1e-9) {
            return "0";
        }
        long delta = params.getRegenRule().calculateNextMillis(
                storage.getLastModified(),
                System.currentTimeMillis(),
                resolveRegenZone()
        );
        if (delta <= 0) {
            return "0";
        }
        long seconds = Math.max(0, delta / 1000);
        return String.valueOf(seconds);
    }

    /**
     * 恢复计算使用的时区，优先读取配置，异常时回退系统默认
     */
    private ZoneId resolveRegenZone() {
        String zid = null;
        try {
            if (configsManager != null && configsManager.getMainConfig() != null) {
                zid = configsManager.getMainConfig().getString("cycle.timezone", "Asia/Shanghai");
            }
            if (isBlank(zid)) {
                zid = "Asia/Shanghai";
            }
            return ZoneId.of(zid);
        } catch (ZoneRulesException ex) {
            logger.warn("恢复时区配置无效: " + zid + "，回退系统默认");
            return ZoneId.systemDefault();
        } catch (Exception ex) {
            logger.warn("解析恢复时区失败，回退系统默认: " + ex.getMessage());
            return ZoneId.systemDefault();
        }
    }

    /**
     * 获取变量的有效参数（考虑参数组覆盖）
     *
     * @param variable 变量定义
     * @param player 玩家上下文
     * @return 合并后的有效参数
     */
    public EffectiveParams getEffectiveParams(Variable variable, OfflinePlayer player) {
        return groupResolver.resolve(variable, player, true);
    }

    public EffectiveParams getEffectiveParams(Variable variable, OfflinePlayer player, boolean allowPapi) {
        return groupResolver.resolve(variable, player, allowPapi);
    }

    /**
     * 解析表达式、占位符及内部变量（支持 Variable 的安全限制配置）
     */
    private String resolveExpression(String expression, OfflinePlayer player, Variable variable) {
        return resolveExpression(expression, player, variable, true);
    }

    private String resolveExpression(String expression, OfflinePlayer player, Variable variable, boolean allowPapi) {
        if (isBlank(expression)
                || (!PLACEHOLDER_PATTERN.matcher(expression).find()
                && !INTERNAL_VAR_PATTERN.matcher(expression).find()
                && !expression.matches(".*[+\\-*/()^].*"))) {
            return expression;
        }

        int maxDepth = MAX_RECURSION_DEPTH;
        int maxLen = MAX_EXPRESSION_LENGTH;
        boolean allowCircular = false;
        if (variable != null && variable.getLimitations() != null) {
            Limitations lim = variable.getLimitations();
            maxDepth = lim.getEffectiveRecursionDepth(MAX_RECURSION_DEPTH);
            if (lim.getMaxExpressionLength() != null) {
                maxLen = lim.getMaxExpressionLength();
            }
            allowCircular = lim.allowsCircularReferences();
        }

        if (expression.length() > maxLen) {
            logger.warn("表达式长度超出安全限制，已截断: "
                    + expression.substring(0, Math.min(100, expression.length())) + "...");
            return expression;
        }

        String result = expression;
        int depth = 0;
        Set<String> seen = new HashSet<>();

        while (depth < maxDepth
                && (PLACEHOLDER_PATTERN.matcher(result).find()
                || INTERNAL_VAR_PATTERN.matcher(result).find()
                || (result.matches(".*[+\\-*/()^].*") && result.matches(".*\\d.*")))) {

            if (!allowCircular && seen.contains(result)) {
                logger.warn("检测到循环引用，停止表达式解析: " + result);
                break;
            }
            if (!allowCircular) seen.add(result);

            String prev = result;

            // 先解析内部变量 ${var}
            result = resolveInternalVariablesInternal(result, player, allowPapi);

            // 再解析 PAPI（仅在线玩家上下文）
            if (allowPapi && isOnline(player)) {
                result = placeholderUtil.parse(player.getPlayer(), result);
            }

            // 最后尝试数学运算
            if (result.matches(".*[+\\-*/()^].*") && result.length() <= maxLen) {
                try {
                    result = String.valueOf(FormulaCalculator.calculate(result));
                } catch (Exception e) {
                    logger.debug("数学表达式计算失败: " + result);
                }
            }

            if (result.equals(prev)) break;
            depth++;
        }

        if (depth >= maxDepth) {
            logger.warn("表达式解析达到最大递归深度限制: " + maxDepth);
        }

        // 根据变量类型规范化结果格式
        if (variable != null) {
            result = normalizeValueByType(result, variable.getValueType());
        }

        // 缓存层已移除，不再缓存表达式结果
        return result;
    }

    /** 判断表达式是否涉及 regen 变量，涉及则跳过缓存以防时间驱动的数值被延迟 */
    private boolean shouldSkipExpressionCacheForRegen(String expression, Variable variable) {
        if (variable != null && variable.hasRegenRule()) {
            return true;
        }
        if (isBlank(expression)) {
            return false;
        }
        Matcher matcher = INTERNAL_VAR_PATTERN.matcher(expression);
        while (matcher.find()) {
            String match = matcher.group();
            String varKey = match.substring(2, match.length() - 1);
            if (varKey.startsWith("regen_next:")) {
                String targetKey = varKey.substring("regen_next:".length()).trim();
                Variable target = getVariableDefinition(targetKey);
                if (target != null && target.hasRegenRule()) {
                    return true;
                }
            } else {
                Variable dep = getVariableDefinition(varKey);
                if (dep != null && dep.hasRegenRule()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 根据变量类型规范化值的格式
     * 主要用于解决占位符解析后返回带小数点的 INT 类型问题
     */
    public String normalizeValueByType(String value, ValueType type) {
        if (value == null || type == null) {
            return value;
        }

        try {
            switch (type) {
                case INT:
                    // INT 类型：去除小数点，确保返回纯整数格式
                    if (value.matches("-?\\d+\\.0*")) {
                        // 处理 "90.0", "90.00" 等格式
                        int intValue = parseIntOrDefault(value);
                        return String.valueOf(intValue);
                    }
                    // 如果已经是整数格式或无法解析，保持原样
                    return value;

                case DOUBLE:
                    // DOUBLE 类型：保持原样，允许小数
                    return value;

                case STRING:
                case LIST:
                default:
                    // 其他类型：保持原样
                    return value;
            }
        } catch (Exception e) {
            // 异常情况下返回原值
            logger.debug("值类型规范化失败: " + value + " -> " + type);
            return value;
        }
    }

    /**
     * 将候选值按变量限制进行适配：
     * - 先调用 {@link #validateValue(Variable, String, OfflinePlayer)} 校验；
     * - 若失败，尝试通过 {@link ValueLimiter#apply(EffectiveParams, String)} 进行修正；
     * - 若修正后仍不通过，返回 null。
     */
    private String fitValueWithinLimitOrNull(Variable variable, String candidate, OfflinePlayer player) {
        return fitValueWithinLimitOrNull(variable, candidate, player, true);
    }

    private String fitValueWithinLimitOrNull(Variable variable, String candidate, OfflinePlayer player, boolean allowPapi) {
        try {
            Double minVal = resolveMin(player, variable, allowPapi);
            Double maxVal = resolveMax(player, variable, allowPapi);
            ValueType type = variable.getValueType();
            if (type == null) type = inferTypeFromValue(candidate);

            String adjusted = null;
            if ((type == ValueType.INT || type == ValueType.DOUBLE) && (minVal != null || maxVal != null)) {
                double val = parseDoubleOrDefault(candidate);
                if (minVal != null) val = Math.max(minVal, val);
                if (maxVal != null) val = Math.min(maxVal, val);
                adjusted = (type == ValueType.INT) ? String.valueOf((int) Math.round(val)) : String.valueOf(val);
            } else if (type == ValueType.STRING && maxVal != null) {
                int maxLen = Math.max(0, (int) Math.round(maxVal));
                adjusted = candidate != null && candidate.length() > maxLen
                        ? candidate.substring(0, maxLen)
                        : candidate;
            }

            if (adjusted != null && validateValue(variable, adjusted, player, allowPapi)) {
                return adjusted;
            }

            if (validateValue(variable, candidate, player, allowPapi)) return candidate;

            EffectiveParams params = getEffectiveParams(variable, player, allowPapi);
            adjusted = ValueLimiter.apply(params, candidate);
            if (adjusted != null && validateValue(variable, adjusted, player, allowPapi)) {
                return adjusted;
            }
        } catch (Exception e) {
            logger.debug("动态边界截断异常: " + e.getMessage());
        }
        return null;
    }

    /** 解析内部变量占位符 ${var}，支持 regen_next:<key> 返回下次恢复剩余秒数 */
    private String resolveInternalVariables(String text, OfflinePlayer player) {
        return resolveInternalVariablesInternal(text, player, true);
    }

    private String resolveInternalVariablesInternal(String text, OfflinePlayer player, boolean allowPapi) {
        if (text == null || !INTERNAL_VAR_PATTERN.matcher(text).find()) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        Matcher m = INTERNAL_VAR_PATTERN.matcher(text);
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            String match = m.group();
            String varKey = match.substring(2, match.length() - 1);
            try {
                if (varKey.startsWith("regen_next:")) {
                    String targetKey = varKey.substring("regen_next:".length()).trim();
                    String val = resolveRegenCountdown(targetKey, player);
                    sb.append(val != null ? val : match);
                } else {
                    Variable def = getVariableDefinition(varKey);
                    String val = def != null ? getVariableFromMemoryOrDefault(player, def, allowPapi) : null;
                    sb.append(val != null ? val : match);
                }
            } catch (Exception e) {
                logger.debug("解析内部变量异常: " + match + " - " + e.getMessage());
                sb.append(match);
            }
            last = m.end();
        }
        sb.append(text.substring(last));
        return sb.toString();
    }

    /** 处理并验证值，返回合法值或 null */
    private String processAndValidateValue(Variable variable, String value, OfflinePlayer player, boolean allowPapi) {
        if (value == null) return null;
        String resolved = resolveExpression(value, player, variable, allowPapi);
        return validateValue(variable, resolved, player, allowPapi) ? resolved : null;
    }

    /**
     * 验证变量值
     * @param variable 变量定义
     * @param value    已解析的值
     * @return true 若验证通过
     */
    private boolean validateValue(Variable variable, String value, OfflinePlayer player, boolean allowPapi) {
        ValueType type = variable.getValueType();
        if (type == null) type = inferTypeFromValue(value);

        if (value != null) {
            try {
                if (type == ValueType.INT) {
                    // 使用与 parseIntOrDefault 相同的逻辑，支持小数四舍五入
                    try {
                        Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        // 兼容小数，如 "90.00" -> 90
                        Double.parseDouble(value);
                    }
                } else if (type == ValueType.DOUBLE) {
                    Double.parseDouble(value);
                }
            } catch (NumberFormatException e) {
                logger.warn("变量 " + variable.getKey() + " 的值 '" + value + "' 与类型 " + type + " 不匹配。");
                return false;
            }
        }

        EffectiveParams params = getEffectiveParams(variable, player, allowPapi);
        Limitations lim = params.getLimitations();
        Double minVal = resolveMin(player, variable, allowPapi);
        Double maxVal = resolveMax(player, variable, allowPapi);
        String minRaw = params.getMin();
        String maxRaw = params.getMax();
        if (minRaw == null && lim != null) {
            minRaw = lim.getMinValue();
        }
        if (maxRaw == null && lim != null) {
            maxRaw = lim.getMaxValue();
        }
        if ((minRaw != null && minVal == null) || (maxRaw != null && maxVal == null)) {
            logger.warn("变量 " + variable.getKey() + " 的动态约束无法解析，拒绝操作");
            return false;
        }

        if (minVal != null || maxVal != null) {
            switch (type) {
                case INT, DOUBLE -> {
                    double num = parseDoubleOrDefault(value);
                    if ((minVal != null && num < minVal) || (maxVal != null && num > maxVal)) {
                        logger.warn("变量 " + variable.getKey() + " 的值 " + num
                                + " 超出范围 [" + minVal + ", " + maxVal + "]");
                        return false;
                    }
                }
                case STRING, LIST -> {
                    int min = minVal != null ? (int) Math.round(minVal) : 0;
                    int max = maxVal != null ? (int) Math.round(maxVal) : Integer.MAX_VALUE;
                    if (value != null && (value.length() < min || value.length() > max)) {
                        logger.warn("变量 " + variable.getKey() + " 的长度 "
                                + value.length() + " 超出范围 [" + min + ", " + max + "]");
                        return false;
                    }
                }
            }
        }

        if (lim != null && lim.hasSecurityLimitations() && !lim.isExpressionLengthValid(value)) {
            if (value != null) {
                logger.warn("变量 " + variable.getKey() + " 的表达式长度超出安全限制: " + value.length());
            }
            return false;
        }
        return true;
    }

    /** 判断是否为公式变量：初始值存在且包含占位符或数学符号 */
    private boolean isFormulaVariable(Variable variable) {
        String init = variable.getInitial();
        return init != null && !init.trim().isEmpty()
                && (PLACEHOLDER_PATTERN.matcher(init).find()
                || INTERNAL_VAR_PATTERN.matcher(init).find()
                || init.matches(".*[+\\-*/()^].*"));
    }

    /** 根据类型执行普通加法（与 addFormulaIncrement 的实现保持一致的类型规则） */
    private String calculateAddition(ValueType type, String currentValue, String addValue) {
        return mergeByType(currentValue, addValue, type);
    }

    /** 根据类型执行普通删除操作 */
    private String calculateRemoval(ValueType type, String currentValue, String removeValue) {
        if (isBlank(currentValue)) return currentValue;
        if (type == null) type = inferTypeFromValue(currentValue);

        switch (type) {
            case INT: {
                int result = parseIntOrDefault(currentValue) - parseIntOrDefault(removeValue);
                return String.valueOf(result);
            }
            case DOUBLE:
                return String.valueOf(parseDoubleOrDefault(currentValue) - parseDoubleOrDefault(removeValue));
            case LIST: {
                List<String> items = new ArrayList<>(Arrays.asList(currentValue.split(",")));
                items.removeIf(item -> item.trim().equals(removeValue.trim()));
                return String.join(",", items);
            }
            default:
                return currentValue.replace(removeValue, "");
        }
    }

    /**
     * 公式变量增量计算
     * @param isAdd true 表示加法，false 表示删除/减法
     */
    private String calculateFormulaIncrement(ValueType type, String currentIncrement, String value, boolean isAdd) {
        if (type == null) type = inferTypeFromValue(currentIncrement);
        int sign = isAdd ? 1 : -1;

        switch (type) {
            case INT: {
                int result = parseIntOrDefault(currentIncrement) + sign * parseIntOrDefault(value);
                return String.valueOf(result);
            }
            case DOUBLE:
                return String.valueOf(parseDoubleOrDefault(currentIncrement) + sign * parseDoubleOrDefault(value));
            case LIST: {
                boolean emptyOrZero = isBlank(currentIncrement) || "0".equals(currentIncrement.trim());
                if (isAdd) {
                    return emptyOrZero ? value : currentIncrement + "," + value;
                } else {
                    if (emptyOrZero) return "0";
                    List<String> list = new ArrayList<>(Arrays.asList(currentIncrement.split(",")));
                    list.removeIf(item -> item.trim().equals(value.trim()));
                    String res = String.join(",", list);
                    return res.isEmpty() ? "0" : res;
                }
            }
            default:
                return isAdd
                        ? ((currentIncrement.equals("0") ? "" : currentIncrement) + value)
                        : currentIncrement.replace(value, "");
        }
    }

    /** 为公式变量添加增量 - 基础值 + 增量 */
    private String addFormulaIncrement(String baseValue, String increment, ValueType type) {
        if (isBlank(increment) || "0".equals(increment.trim())) return baseValue;
        return mergeByType(baseValue, increment, type);
    }

    /** 统一的"按类型合并值"实现，减少重复（用于加法与公式叠加） */
    private String mergeByType(String baseValue, String increment, ValueType type) {
        if (type == null) type = inferTypeFromValue(baseValue);
        return switch (type) {
            case INT -> {
                int result = parseIntOrDefault(baseValue) + parseIntOrDefault(increment);
                yield String.valueOf(result);
            }
            case DOUBLE -> String.valueOf(parseDoubleOrDefault(baseValue) + parseDoubleOrDefault(increment));
            case LIST -> {
                if (isBlank(baseValue)) yield increment;
                yield baseValue + "," + increment;
            }
            default -> (baseValue == null ? "" : baseValue) + increment;
        };
    }

    /** 为公式变量计算设置值相对于基础公式的增量 */
    private String calculateIncrementForSet(Variable variable, String setValue, OfflinePlayer player, boolean allowPapi) {
        EffectiveParams params = getEffectiveParams(variable, player, allowPapi);
        String base = resolveExpression(params.getInitial(), player, variable, allowPapi);
        ValueType type = variable.getValueType();
        if (type == null) type = inferTypeFromValue(base);

        switch (type) {
            case INT: {
                int result = parseIntOrDefault(setValue) - parseIntOrDefault(base);
                return String.valueOf(result);
            }
            case DOUBLE:
                return String.valueOf(parseDoubleOrDefault(setValue) - parseDoubleOrDefault(base));
            case LIST:
                if (isBlank(setValue)) return "";
                if (isBlank(base)) return setValue;
                if (setValue.startsWith(base)) {
                    String diff = setValue.substring(base.length());
                    return diff.startsWith(",") ? diff.substring(1) : diff;
                }
                return setValue;
            default:
                if (isBlank(setValue)) return "";
                if (isBlank(base)) return setValue;
                if (setValue.startsWith(base)) return setValue.substring(base.length());
                return setValue;
        }
    }

    /** 推断值类型 */
    private ValueType inferTypeFromValue(String value) {
        if (isBlank(value)) return ValueType.STRING;
        if (value.matches("-?\\d+")) return ValueType.INT;
        if (value.matches("-?\\d*\\.\\d+")) return ValueType.DOUBLE;
        if (value.contains(",")) return ValueType.LIST;
        return ValueType.STRING;
    }

    /** 获取类型默认值 */
    private String getDefaultValueByType(ValueType type) {
        if (type == null) return "0";
        return switch (type) {
            case INT, DOUBLE -> "0";
            default -> "";
        };
    }

    /** 安全解析整型，失败返回 0 */
    private int parseIntOrDefault(String input) {
        String s = (input == null ? "" : input.trim());
        if (s.isEmpty()) return 0;
        try {
            // 优先按整数解析
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            // 兼容像 "180.0" 这样的浮点字符串；按照业务直觉进行四舍五入
            try {
                double d = Double.parseDouble(s);
                return (int) Math.round(d);
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    /** 安全解析浮点，失败返回 0.0 */
    private double parseDoubleOrDefault(String input) {
        try {
            if (input == null) return 0D;
            return Double.parseDouble(input.trim());
        } catch (Exception ignored) {
            return 0D;
        }
    }

    // ======================== 变量定义加载与验证 ========================

    /** 加载所有变量定义（委托到组件） */
    private void loadAllVariableDefinitions() {
        definitionLoader.loadAll(this::registerVariable);
    }

    

    /** 注册变量到内存定义表 */
    private void registerVariable(Variable variable) {
        variableRegistry.put(variable.getKey(), variable);
        logger.debug("已注册变量: " + variable.getKey());
    }

    /** 验证所有变量定义的合法性 */
    private void validateVariableDefinitions() {
        for (Variable v : variableRegistry.values()) {
            currentValidatingVariable.set(v.getKey());
            List<String> errs = v.validate();
            for (String err : errs) {
                // 对“不持久化”的变量，放行“初始值为空”类提示（不做错误输出）
                if (shouldSuppressInitialEmptyError(v, err)) {
                    continue;
                }
                if (err.startsWith("警告:")) {
                    logger.warn("变量 " + v.getKey() + ": " + err);
                } else {
                    logger.error("变量 " + v.getKey() + ": " + err);
                }
            }
        }
        currentValidatingVariable.remove();
    }

    /**
     * 当变量为不持久化（persistable=false）时，忽略“初始值为空”类校验报错。
     */
    private boolean shouldSuppressInitialEmptyError(Variable v, String err) {
        try {
            if (v == null || err == null) return false;
            Limitations lim = v.getLimitations();
            if (lim != null && Boolean.FALSE.equals(lim.getPersistable())) {
                String s = err;
                // 兼容不同文案/编码情况：只要包含“初始值”和“空”字样就认为是该类提示
                return s.contains("初始值") && s.contains("空");
            }
        } catch (Exception ignored) { }
        return false;
    }

    // ======================== 数据加载 ========================

    /** 加载数据库中的持久化数据 */
    public void loadPersistedData() {
        logger.info("开始加载数据库持久化数据到内存...");
        try {
            CompletableFuture<Void> sv = loadServerVariables();
            CompletableFuture<Void> pv = loadOnlinePlayerVariables();
            CompletableFuture.allOf(sv, pv).join();
            VariableMemoryStorage.MemoryStats stats = memoryStorage.getMemoryStats();
            logger.info("加载完成: " + stats);
        } catch (Exception e) {
            logger.error("加载数据库数据失败", e);
        } finally {
            initialized.set(true);
        }
    }

    /**
     * 判断变量管理器是否已初始化完成
     */
    public boolean isInitialized() {
        return initialized.get();
    }

    /** 加载所有全局变量（抽取查询三元组的复用逻辑） */
    private CompletableFuture<Void> loadServerVariables() {
        return CompletableFuture.runAsync(() -> {
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (String key : getGlobalVariableKeys()) {
                    futures.add(fetchServerTriple(key).thenAccept(tri -> {
                        if (tri.value != null) {
                            // 数据加载时进行类型规范化，确保历史数据格式正确
                            Variable var = getVariableDefinition(key);
                            String normalizedValue = (var != null)
                                ? normalizeValueByType(tri.value, var.getValueType())
                                : tri.value;
                            memoryStorage.loadServerVariable(
                                    key, normalizedValue, tri.updatedAt, tri.firstModifiedAt);
                        }
                    }).exceptionally(ex -> {
                        logger.error("加载服务器变量失败: " + key, ex);
                        return null;
                    }));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                logger.debug("服务器变量加载完成，数量: " + getGlobalVariableKeys().size());
            } catch (Exception e) {
                logger.error("加载服务器变量失败", e);
            }
        }, asyncTaskManager.getExecutor());
    }

    /** 加载在线玩家的所有变量 */
    private CompletableFuture<Void> loadOnlinePlayerVariables() {
        return CompletableFuture.runAsync(() -> {
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (OfflinePlayer p : Bukkit.getOnlinePlayers()) {
                    futures.add(loadPlayerVariables(p));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                logger.debug("在线玩家变量加载完成，玩家数: " + Bukkit.getOnlinePlayers().size());
            } catch (Exception e) {
                logger.error("加载在线玩家变量失败", e);
            }
        }, asyncTaskManager.getExecutor());
    }

    /** 加载单个玩家的变量（使用统一三元组查询） */
    private CompletableFuture<Void> loadPlayerVariables(OfflinePlayer player) {
        return CompletableFuture.runAsync(() -> {
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                String pid = player.getUniqueId().toString();
                java.util.UUID puid = player.getUniqueId();
                for (String key : getPlayerVariableKeys()) {
                    futures.add(fetchPlayerTriple(pid, key).thenAccept(tri -> {
                        // 若该玩家该变量在内存中存在未落库的脏写入，则不允许 DB 覆盖，避免“离线改值后入服变回去”
                        if (shouldSkipDbOverwrite(puid, key, tri.updatedAt)) {
                            return;
                        }
                        if (tri.value != null) {
                            // 数据加载时进行类型规范化，确保历史数据格式正确
                            Variable var = getVariableDefinition(key);
                            String normalizedValue = (var != null)
                                ? normalizeValueByType(tri.value, var.getValueType())
                                : tri.value;

                            // 关键变量审计日志：从 DB 加载覆盖内存（INFO 级别）
                            if (isAuditKey(key)) {
                                VariableValue before = memoryStorage.getPlayerVariable(player.getUniqueId(), key);
                                String beforeVal = before != null ? before.getActualValue() : null;
                                logger.info(buildAuditLine(
                                        "DB_LOAD", player, key, beforeVal, normalizedValue,
                                        "db.updated_at=" + tri.updatedAt));
                            }
                            memoryStorage.loadPlayerVariable(
                                    player.getUniqueId(), key, normalizedValue, tri.updatedAt, tri.firstModifiedAt);
                        } else {
                            memoryStorage.removePlayerVariable(player.getUniqueId(), key, false);
                        }
                    }).exceptionally(ex -> {
                        logger.error("加载玩家变量失败: " + pid + " - " + key, ex);
                        return null;
                    }));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                logger.debug("玩家变量加载完成: " + player.getName());
            } catch (Exception e) {
                logger.error("加载玩家变量失败: " + player.getUniqueId(), e);
            }
        }, asyncTaskManager.getExecutor());
    }

    /**
     * 是否应跳过数据库覆盖（保护内存中的脏写入，避免被入服加载/DB 重载覆盖）
     */
    private boolean shouldSkipDbOverwrite(java.util.UUID playerId, String key, long dbUpdatedAt) {
        try {
            if (playerId == null || key == null || key.isEmpty()) {
                return false;
            }
            VariableValue mem = memoryStorage.getPlayerVariable(playerId, key);
            if (mem == null) {
                return false;
            }
            // 只要存在脏写入，一律保护（DB 可能滞后，或刷盘尚未完成）
            if (mem.isDirty()) {
                return true;
            }
            // 额外保护：若内存修改时间比 DB 新，则不覆盖
            return mem.getLastModified() > dbUpdatedAt;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 服务器变量统一三元组查询 */
    private CompletableFuture<ValueTriple> fetchServerTriple(String key) {
        CompletableFuture<String> vF = database.queryValueAsync(
                "SELECT value FROM server_variables WHERE variable_key = ?", key);
        CompletableFuture<String> uF = database.queryValueAsync(
                "SELECT updated_at FROM server_variables WHERE variable_key = ?", key);
        CompletableFuture<String> fF = database.queryValueAsync(
                "SELECT first_modified_at FROM server_variables WHERE variable_key = ?", key);

        return CompletableFuture.allOf(vF, uF, fF)
                .thenApply(ignored -> new ValueTriple(
                        vF.join(),
                        parseLongOrNow(uF.join()),
                        parseLongOrNow(fF.join())
                ));
    }

    /** 玩家变量统一三元组查询 */
    private CompletableFuture<ValueTriple> fetchPlayerTriple(String playerUuid, String key) {
        CompletableFuture<String> vF = database.queryValueAsync(
                "SELECT value FROM player_variables WHERE player_uuid = ? AND variable_key = ?", playerUuid, key);
        CompletableFuture<String> uF = database.queryValueAsync(
                "SELECT updated_at FROM player_variables WHERE player_uuid = ? AND variable_key = ?", playerUuid, key);
        CompletableFuture<String> fF = database.queryValueAsync(
                "SELECT first_modified_at FROM player_variables WHERE player_uuid = ? AND variable_key = ?", playerUuid, key);

        return CompletableFuture.allOf(vF, uF, fF)
                .thenApply(ignored -> new ValueTriple(
                        vF.join(),
                        parseLongOrNow(uF.join()),
                        parseLongOrNow(fF.join())
                ));
    }

    /** 安全解析为 long，失败返回当前时间戳 */
    private long parseLongOrNow(String value) {
        try {
            return value == null ? System.currentTimeMillis() : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return System.currentTimeMillis();
        }
    }

    // ======================== 依赖快照管理方法 ========================

    /**
     * 清理特定变量的依赖快照（用于周期重置等场景）
     */
    public void clearVariableDependencySnapshots(String key) {
        try {
            Variable variable = getVariableDefinition(key);
            if (variable != null && variable.isStrictInitialMode()) {
                if (variable.isGlobal()) {
                    dependencyResolver.clearSnapshot(null, key);
                } else {
                    // 对于玩家变量，清理所有玩家的该变量快照（必要时可按需优化范围）
                    dependencyResolver.clearAllSnapshots();
                }
                logger.debug("清理变量依赖快照: " + key);
            }
        } catch (Exception e) {
            logger.error("清理变量依赖快照失败: " + key, e);
        }
    }

    /**
     * 获取依赖解析器统计信息
     */
    public Map<String, Object> getDependencyStatistics() {
        return dependencyResolver.getStatistics();
    }

    /**
     * 计算严格模式的初始值
     *
     * @param variable           变量定义
     * @param player             玩家
     * @param initialExpression  初始值表达式
     * @return 计算结果
     */
    private String calculateStrictInitialValue(Variable variable, OfflinePlayer player, String initialExpression, boolean allowPapi) {
        try {
            // 如果变量需要依赖快照，使用快照机制
            if (variable.needsDependencySnapshot()) {
                DependencySnapshot snapshot = dependencyResolver.resolveWithSnapshot(
                        variable, player, (p, key) -> {
                            Variable depVar = getVariableDefinition(key);
                            return depVar != null ? getVariableFromMemoryOrDefault(p, depVar, allowPapi) : null;
                        }
                );

                if (snapshot.hasCircularDependency()) {
                    logger.warn("变量存在循环依赖，使用原始表达式: " + variable.getKey());
                    return initialExpression;
                }

                // 对快照结果再进行一次通用解析，确保 PAPI 与数学表达式被最终计算
                String snapped = snapshot.getCalculatedValue();
                return resolveExpression(snapped, player, variable, allowPapi);
            } else {
                // 不需要快照，直接解析表达式
                return resolveExpression(initialExpression, player, variable, allowPapi);
            }
        } catch (Exception e) {
            logger.error("严格模式初始值计算失败，使用原始表达式: " + variable.getKey(), e);
            return initialExpression;
        }
    }

    // ======================== 辅助内部类型 ========================

    /**
     * 内部使用的三元组载体：value / updatedAt / firstModifiedAt
     * 仅用于减少加载逻辑中的临时对象分散，提升可读性与复用性。
     */
    private static final class ValueTriple {
        final String value;
        final long updatedAt;
        final long firstModifiedAt;

        ValueTriple(String value, long updatedAt, long firstModifiedAt) {
            this.value = value;
            this.updatedAt = updatedAt;
            this.firstModifiedAt = firstModifiedAt;
        }
    }

    /**
     * 内部封装：公式操作计算结果
     * newIncrement 为需要写入内存的增量值；displayValue 为用户可见的最终值
     */
    private static final class FormulaOpResult {
        final String newIncrement;
        final String displayValue;

        FormulaOpResult(String newIncrement, String displayValue) {
            this.newIncrement = newIncrement;
            this.displayValue = displayValue;
        }
    }

    // ======================== 末尾隐藏：未被调用的注释内容（保持位置最末） ========================
    // （当前无仅注释且未被调用的片段）
}
