package cn.drcomo.managers;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.async.AsyncTaskManager;
import cn.drcomo.corelib.config.YamlUtil;
import cn.drcomo.corelib.hook.placeholder.PlaceholderAPIUtil;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.database.HikariConnection;
import cn.drcomo.model.VariableResult;
import cn.drcomo.model.structure.DependencySnapshot;
import cn.drcomo.model.structure.Limitations;
import cn.drcomo.model.structure.ScopeType;
import cn.drcomo.model.structure.ValueType;
import cn.drcomo.model.structure.Variable;
import cn.drcomo.storage.BatchPersistenceManager;
import cn.drcomo.storage.BatchPersistenceManager.PersistenceConfig;
import cn.drcomo.storage.MultiLevelCacheManager;
import cn.drcomo.storage.MultiLevelCacheManager.CacheConfig;
import cn.drcomo.storage.MultiLevelCacheManager.CacheResult;
import cn.drcomo.storage.VariableMemoryStorage;
import cn.drcomo.storage.VariableValue;
import cn.drcomo.util.DependencyResolver;
import cn.drcomo.util.ValueLimiter;
import cn.drcomo.corelib.math.FormulaCalculator;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
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
    private final MultiLevelCacheManager cacheManager;
    private final DependencyResolver dependencyResolver;

    // 变量定义注册表
    private final ConcurrentHashMap<String, Variable> variableRegistry = new ConcurrentHashMap<>();

    // 验证过程中传递当前变量键的线程本地变量
    private final ThreadLocal<String> currentValidatingVariable = new ThreadLocal<>();

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

        // 初始化依赖解析器
        this.dependencyResolver = new DependencyResolver(logger);

        // 初始化内存存储（256MB）
        this.memoryStorage = new VariableMemoryStorage(logger, database, 256 * 1024 * 1024);

        // 初始化多级缓存
        CacheConfig cacheConfig = new CacheConfig()
                .setL2MaximumSize(10000)
                .setL2ExpireMinutes(5)
                .setL3MaximumSize(5000)
                .setL3ExpireMinutes(2);
        this.cacheManager = new MultiLevelCacheManager(logger, memoryStorage, cacheConfig);

        // 初始化批量持久化管理器
        PersistenceConfig persistenceConfig = new PersistenceConfig()
                .setBatchIntervalSeconds(30)
                .setMaxBatchSize(1000)
                .setMemoryPressureThreshold(80.0)
                .setMaxRetries(3);
        this.persistenceManager = new BatchPersistenceManager(
                logger, database, memoryStorage, asyncTaskManager, persistenceConfig);
    }

    // ======================== 生命周期管理 ========================

    /**
     * 初始化变量管理器
     */
    public CompletableFuture<Void> initialize() {
        logger.info("正在初始化重构后的变量管理系统...");
        return CompletableFuture.runAsync(() -> {
            try {
                loadAllVariableDefinitions();
                validateVariableDefinitions();
                persistenceManager.start();
                logger.info("变量管理系统初始化完成！已加载 " + variableRegistry.size() + " 个变量定义");
            } catch (Exception e) {
                logger.error("变量管理系统初始化失败！", e);
                throw new RuntimeException("变量管理系统初始化失败", e);
            }
        }, asyncTaskManager.getExecutor());
    }

    /**
     * 关闭变量管理器
     */
    public CompletableFuture<Void> shutdown() {
        logger.info("正在关闭变量管理系统...");
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
            // 关闭后尝试清理缓存（异常不影响流程）
            try {
                cacheManager.clearAllCaches();
            } catch (Exception e) {
                logger.debug("缓存清理跳过: " + e.getMessage());
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
        final String playerName = getPlayerName(player);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Variable variable = getVariableDefinition(key);
                Optional<VariableResult> pre = checkOpPreconditions(variable, key, "GET", playerName, false);
                if (pre.isPresent()) return pre.get();

                CacheResult cacheResult = cacheManager.getFromCache(player, key, variable);
                if (cacheResult.isHit()) {
                    logger.debug("缓存命中(" + cacheResult.getLevel() + "): " + key);
                    return VariableResult.success(cacheResult.getValue(), "GET", key, playerName);
                }

                String finalValue = getVariableFromMemoryOrDefault(player, variable);
                if (!isFormulaVariable(variable)) {
                    finalValue = resolveExpression(finalValue, player, variable);
                }
                cacheManager.cacheResult(player, key, finalValue, finalValue);
                return VariableResult.success(finalValue, "GET", key, playerName);
            } catch (Exception e) {
                logger.error("获取变量失败: " + key, e);
                return VariableResult.fromException(e, "GET", key, playerName);
            }
        }, asyncTaskManager.getExecutor());
    }

    /**
     * 设置变量值（完全异步，立即返回）
     */
    public CompletableFuture<VariableResult> setVariable(OfflinePlayer player, String key, String value) {
        final String playerName = getPlayerName(player);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Variable variable = getVariableDefinition(key);
                Optional<VariableResult> pre = checkOpPreconditions(variable, key, "SET", playerName, true);
                if (pre.isPresent()) return pre.get();

                String processed = processAndValidateValue(variable, value, player);
                if (processed == null) {
                    return VariableResult.failure("值格式错误或超出约束: " + value, "SET", key, playerName);
                }

                if (isFormulaVariable(variable)) {
                    String increment = calculateIncrementForSet(variable, processed, player);
                    updateMemoryAndInvalidate(player, variable, increment);
                    logger.debug("设置公式变量: " + key + " 增量= " + increment + " 最终值= " + processed + " (异步持久化中)");
                } else {
                    updateMemoryAndInvalidate(player, variable, processed);
                    logger.debug("设置变量: " + key + " = " + processed + " (异步持久化中)");
                }
                return VariableResult.success(processed, "SET", key, playerName);
            } catch (Exception e) {
                logger.error("设置变量失败: " + key, e);
                return VariableResult.fromException(e, "SET", key, playerName);
            }
        }, asyncTaskManager.getExecutor());
    }

    /**
     * 增加变量值（智能操作，完全异步）
     */
    public CompletableFuture<VariableResult> addVariable(OfflinePlayer player, String key, String addValue) {
        final String playerName = getPlayerName(player);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Variable variable = getVariableDefinition(key);
                Optional<VariableResult> pre = checkOpPreconditions(variable, key, "ADD", playerName, true);
                if (pre.isPresent()) return pre.get();

                String currentValue = getVariableFromMemoryOrDefault(player, variable);
                String newIncrement;
                String displayValue;

                if (isFormulaVariable(variable)) {
                    String resolvedAdd = resolveExpression(addValue, player, variable);
                    String currInc = Optional.ofNullable(getMemoryValue(player, variable))
                            .map(VariableValue::getActualValue).orElse("0");
                    newIncrement = calculateFormulaIncrement(variable.getValueType(), currInc, resolvedAdd, true);
                    String base = resolveExpression(variable.getInitial(), player, variable);
                    displayValue = addFormulaIncrement(base, newIncrement, variable.getValueType());
                } else {
                    String resolvedAdd = resolveExpression(addValue, player, variable);
                    newIncrement = calculateAddition(variable.getValueType(), currentValue, resolvedAdd);
                    displayValue = newIncrement;
                }

                if (newIncrement == null) {
                    return VariableResult.failure("加法操作失败或超出约束", "ADD", key, playerName);
                }
                if (!validateValue(variable, newIncrement)) {
                    String adjusted = ValueLimiter.apply(variable, newIncrement);
                    if (adjusted == null || !validateValue(variable, adjusted)) {
                        return VariableResult.failure("加法结果超出限制", "ADD", key, playerName);
                    }
                    newIncrement = adjusted;
                    displayValue = newIncrement;
                }

                updateMemoryAndInvalidate(player, variable, newIncrement);
                logger.debug("加法操作: " + key + " = " + displayValue + " (当前: " + currentValue + " + 增加: " + addValue + ")");
                return VariableResult.success(displayValue, "ADD", key, playerName);
            } catch (Exception e) {
                logger.error("增加变量失败: " + key, e);
                return VariableResult.fromException(e, "ADD", key, playerName);
            }
        }, asyncTaskManager.getExecutor());
    }

    /**
     * 移除变量值（智能操作，完全异步）
     */
    public CompletableFuture<VariableResult> removeVariable(OfflinePlayer player, String key, String removeValue) {
        final String playerName = getPlayerName(player);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Variable variable = getVariableDefinition(key);
                Optional<VariableResult> pre = checkOpPreconditions(variable, key, "REMOVE", playerName, true);
                if (pre.isPresent()) return pre.get();

                String currentValue = getVariableFromMemoryOrDefault(player, variable);
                String newIncrement;
                String displayValue;

                if (isFormulaVariable(variable)) {
                    newIncrement = calculateFormulaIncrement(
                            variable.getValueType(),
                            Optional.ofNullable(getMemoryValue(player, variable)).map(VariableValue::getActualValue).orElse("0"),
                            removeValue, false);
                    String base = resolveExpression(variable.getInitial(), player, variable);
                    displayValue = addFormulaIncrement(base, newIncrement, variable.getValueType());
                } else {
                    newIncrement = calculateRemoval(variable.getValueType(), currentValue, removeValue);
                    displayValue = newIncrement;
                }

                if (newIncrement == null) {
                    return VariableResult.failure("删除操作失败", "REMOVE", key, playerName);
                }
                if (!validateValue(variable, newIncrement)) {
                    String adjusted = ValueLimiter.apply(variable, newIncrement);
                    if (adjusted == null || !validateValue(variable, adjusted)) {
                        return VariableResult.failure("删除结果超出限制", "REMOVE", key, playerName);
                    }
                    newIncrement = adjusted;
                    displayValue = newIncrement;
                }

                updateMemoryAndInvalidate(player, variable, newIncrement);
                logger.debug("删除操作: " + key + " = " + displayValue + " (删除: " + removeValue + ")");
                return VariableResult.success(displayValue, "REMOVE", key, playerName);
            } catch (Exception e) {
                logger.error("移除变量失败: " + key, e);
                return VariableResult.fromException(e, "REMOVE", key, playerName);
            }
        }, asyncTaskManager.getExecutor());
    }

    /**
     * 重置变量为初始值（完全异步）
     */
    public CompletableFuture<VariableResult> resetVariable(OfflinePlayer player, String key) {
        final String playerName = getPlayerName(player);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Variable variable = getVariableDefinition(key);
                Optional<VariableResult> pre = checkOpPreconditions(variable, key, "RESET", playerName, true);
                if (pre.isPresent()) return pre.get();

                removeFromMemoryAndInvalidate(player, variable);

                // 清理依赖快照（如果是严格模式变量）
                if (variable.isStrictInitialMode()) {
                    dependencyResolver.clearSnapshot(player, key);
                    logger.debug("清理变量依赖快照: " + key);
                }

                // 对于严格模式变量，还需要清理内存中的 strict 状态
                VariableValue resetVal = getMemoryValue(player, variable);
                if (resetVal != null && variable.isStrictInitialMode()) {
                    resetVal.resetStrictMode();
                    logger.debug("重置变量严格模式状态: " + key);
                }

                String resetValue = getVariableFromMemoryOrDefault(player, variable);
                if (isFormulaVariable(variable)) {
                    logger.debug("重置公式变量: " + key + " = " + resetValue + " (清空增量)");
                } else {
                    logger.debug("重置变量: " + key + " = " + resetValue);
                }
                return VariableResult.success(resetValue, "RESET", key, playerName);
            } catch (Exception e) {
                logger.error("重置变量失败: " + key, e);
                return VariableResult.fromException(e, "RESET", key, playerName);
            }
        }, asyncTaskManager.getExecutor());
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
                    }
                });
    }

    /**
     * 玩家上线时的数据预加载
     */
    public CompletableFuture<Void> handlePlayerJoin(OfflinePlayer player) {
        // 先加载该玩家的持久化数据，再进行缓存预热，避免玩家加入后读到默认值
        return loadPlayerVariables(player)
                .thenRunAsync(() -> {
                    try {
                        Set<String> keys = getPlayerVariableKeys();
                        for (String key : keys) {
                            Variable var = getVariableDefinition(key);
                            if (var != null) {
                                cacheManager.preloadCache(player, key, var);
                            }
                        }
                        logger.debug("玩家上线预加载完成: " + player.getName() + "，变量数: " + keys.size());
                    } catch (Exception e) {
                        logger.error("玩家上线数据预加载失败: " + player.getName(), e);
                    }
                }, asyncTaskManager.getExecutor());
    }

    // ======================== 公共查询方法 ========================

    public Variable getVariableDefinition(String key) {
        return variableRegistry.get(key);
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
     * 清理指定变量的所有缓存
     */
    public void invalidateAllCaches(String key) {
        try {
            cacheManager.invalidateCache(null, key);
            logger.debug("已清理变量缓存: " + key);
        } catch (Exception e) {
            logger.error("清理变量缓存失败: " + key, e);
        }
    }

    /**
     * 清理指定玩家上下文下的变量缓存（不触碰内存存储，不产生删除标记）
     */
    public void invalidateCachesForPlayer(OfflinePlayer player, String key) {
        try {
            cacheManager.invalidateCache(player, key);
            logger.debug("已清理玩家上下文变量缓存: player=" + (player != null ? player.getName() : "null") + ", key=" + key);
        } catch (Exception e) {
            logger.error("清理玩家上下文变量缓存失败: " + key, e);
        }
    }

    /**
     * 从内存与多级缓存中删除变量
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
                cacheManager.invalidateCache(null, key);
            } else if (variable.isPlayerScoped()) {
                for (OfflinePlayer p : Bukkit.getOnlinePlayers()) {
                    memoryStorage.removePlayerVariable(p.getUniqueId(), key);
                    cacheManager.invalidateCache(p, key);
                }
            }
            logger.debug("已从内存与缓存删除变量: " + key);
        } catch (Exception e) {
            logger.error("删除变量缓存失败: " + key, e);
        }
    }

    // ======================== 私有辅助方法（抽取复用逻辑） ========================

    /**
     * 统一的操作前置校验：
     * 1) 变量是否存在 2)（可选）是否可写
     */
    private Optional<VariableResult> checkOpPreconditions(
            Variable variable, String key, String op, String playerName, boolean requireWritable) {
        if (variable == null) {
            return Optional.of(VariableResult.failure("变量不存在: " + key, op, key, playerName));
        }
        if (requireWritable && variable.getLimitations() != null && variable.getLimitations().isReadOnly()) {
            return Optional.of(VariableResult.failure("变量为只读模式: " + key, op, key, playerName));
        }
        return Optional.empty();
    }

    /** 获取玩家名，若为空则返回 "SERVER" */
    private String getPlayerName(OfflinePlayer player) {
        return player != null ? player.getName() : "SERVER";
    }

    /** 是否在线（避免 NPE 与 PAPI 无上下文问题） */
    private boolean isOnline(OfflinePlayer player) {
        return player != null && player.isOnline() && player.getPlayer() != null;
    }

    /** 字符串判空（包含 trim） */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 获取内存存储中的 VariableValue 引用 */
    private VariableValue getMemoryValue(OfflinePlayer player, Variable variable) {
        if (variable.isPlayerScoped() && player != null) {
            return memoryStorage.getPlayerVariable(player.getUniqueId(), variable.getKey());
        } else if (variable.isGlobal()) {
            return memoryStorage.getServerVariable(variable.getKey());
        }
        return null;
    }

    /** 从内存获取变量值或返回默认/初始值（含严格模式与公式处理） */
    private String getVariableFromMemoryOrDefault(OfflinePlayer player, Variable variable) {
        VariableValue val = getMemoryValue(player, variable);
        String init = variable.getInitial();
        boolean isFormula = isFormulaVariable(variable);

        // 严格初始值模式
        if (variable.isStrictInitialMode() && !isBlank(init)) {
            boolean hasCycle = !isBlank(variable.getCycle());

            if (val != null) {
                // 已存在
                if (val.isStrictComputed()) {
                    String currentValue = val.getActualValue();
                    logger.debug("使用已计算的严格模式值" + (hasCycle ? "(有cycle)" : "(无cycle)") + ": " + variable.getKey() + " = " + currentValue);
                    return currentValue;
                } else {
                    // 首次严格计算
                    String calculatedValue = calculateStrictInitialValue(variable, player, init);
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
                                finalValue = String.valueOf((int) (calculated + increment));
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
                    return finalValue;
                }
            } else {
                // 首次创建
                String calculatedValue = calculateStrictInitialValue(variable, player, init);
                updateMemoryAndInvalidate(player, variable, "STRICT:" + calculatedValue + ":" + System.currentTimeMillis());
                logger.info("首次创建严格模式变量" + (hasCycle ? "(有cycle)" : "(无cycle)") + ": " + variable.getKey() + " = " + calculatedValue);
                return calculatedValue;
            }
        }

        // 默认（非严格）行为
        if (isFormula && !isBlank(init)) {
            String base = resolveExpression(init, player, variable);
            return (val != null) ? addFormulaIncrement(base, val.getActualValue(), variable.getValueType()) : base;
        }
        if (val != null) {
            return val.getActualValue();
        }
        if (!isBlank(init)) {
            return resolveExpression(init, player, variable);
        }
        return getDefaultValueByType(variable.getValueType());
    }

    /** 设置变量到内存并按 persistable 配置处理脏标记与缓存失效 */
    private void updateMemoryAndInvalidate(OfflinePlayer player, Variable variable, String value) {
        boolean persist = variable.getLimitations() == null || variable.getLimitations().isPersistable();

        if (variable.isPlayerScoped() && player != null) {
            memoryStorage.setPlayerVariable(player.getUniqueId(), variable.getKey(), value);
            if (!persist) {
                memoryStorage.clearDirtyFlag("player:" + player.getUniqueId() + ":" + variable.getKey());
                logger.debug("变量设置为不可持久化，跳过数据库保存: " + variable.getKey());
            }
        } else if (variable.isGlobal()) {
            memoryStorage.setServerVariable(variable.getKey(), value);
            if (!persist) {
                memoryStorage.clearDirtyFlag("server:" + variable.getKey());
                logger.debug("服务器变量设置为不可持久化，跳过数据库保存: " + variable.getKey());
            }
            // 全局上下文缓存一并清理
            invalidateCachesSafely(null, variable.getKey());
        }
        // 当前上下文缓存失效
        invalidateCachesSafely(player, variable.getKey());
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

    /** 缓存失效的安全封装（异常不向外抛） */
    private void invalidateCachesSafely(OfflinePlayer player, String key) {
        try {
            cacheManager.invalidateCache(player, key);
        } catch (Exception e) {
            logger.debug("清理缓存失败: " + key + " - " + e.getMessage());
        }
    }

    /**
     * 解析表达式、占位符及内部变量（支持 Variable 的安全限制配置）
     */
    private String resolveExpression(String expression, OfflinePlayer player, Variable variable) {
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
        Set<String> seen = allowCircular ? null : new HashSet<>();

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
            result = resolveInternalVariables(result, player);

            // 再解析 PAPI（仅在线玩家上下文）
            if (isOnline(player)) {
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

        cacheManager.cacheExpression(expression, player, result);
        return result;
    }

    /** 解析内部变量占位符 ${var} */
    private String resolveInternalVariables(String text, OfflinePlayer player) {
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
            Variable def = getVariableDefinition(varKey);
            try {
                String val = def != null ? getVariableFromMemoryOrDefault(player, def) : null;
                sb.append(val != null ? val : match);
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
    private String processAndValidateValue(Variable variable, String value, OfflinePlayer player) {
        if (value == null) return null;
        String resolved = resolveExpression(value, player, variable);
        return validateValue(variable, resolved) ? resolved : null;
    }

    /**
     * 验证变量值
     * @param variable 变量定义
     * @param value    已解析的值
     * @return true 若验证通过
     */
    private boolean validateValue(Variable variable, String value) {
        ValueType type = variable.getValueType();
        if (type == null) type = inferTypeFromValue(value);

        if (value != null) {
            try {
                if (type == ValueType.INT) {
                    Integer.parseInt(value);
                } else if (type == ValueType.DOUBLE) {
                    Double.parseDouble(value);
                }
            } catch (NumberFormatException e) {
                logger.warn("变量 " + variable.getKey() + " 的值 '" + value + "' 与类型 " + type + " 不匹配。");
                return false;
            }
        }

        Limitations lim = variable.getLimitations();
        if (lim != null) {
            if (lim.hasValueConstraints()) {
                switch (type) {
                    case INT, DOUBLE -> {
                        try {
                            double num = (value != null) ? Double.parseDouble(value) : 0D;
                            if (!lim.isValueInRange(num)) {
                                logger.warn("变量 " + variable.getKey() + " 的值 " + num
                                        + " 超出范围 [" + lim.getMinValue() + ", " + lim.getMaxValue() + "]");
                                return false;
                            }
                        } catch (NumberFormatException ignored) { }
                    }
                    case STRING, LIST -> {
                        if (lim.getMinValue() != null || lim.getMaxValue() != null) {
                            int min = 0, max = Integer.MAX_VALUE;
                            try {
                                if (lim.getMinValue() != null) min = Integer.parseInt(lim.getMinValue());
                                if (lim.getMaxValue() != null) max = Integer.parseInt(lim.getMaxValue());
                                if (value != null && (value.length() < min || value.length() > max)) {
                                    logger.warn("变量 " + variable.getKey() + " 的长度 "
                                            + value.length() + " 超出范围 [" + min + ", " + max + "]");
                                    return false;
                                }
                            } catch (NumberFormatException ignored) {
                                logger.debug("字符串类型长度限制解析异常: " + variable.getKey());
                            }
                        }
                    }
                }
            }
            if (lim.hasSecurityLimitations() && !lim.isExpressionLengthValid(value)) {
                if (value != null) {
                    logger.warn("变量 " + variable.getKey() + " 的表达式长度超出安全限制: " + value.length());
                }
                return false;
            }
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
            case INT:
                return String.valueOf(parseIntOrDefault(currentValue) - parseIntOrDefault(removeValue));
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
            case INT:
                return String.valueOf(parseIntOrDefault(currentIncrement) + sign * parseIntOrDefault(value));
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

    /** 统一的“按类型合并值”实现，减少重复（用于加法与公式叠加） */
    private String mergeByType(String baseValue, String increment, ValueType type) {
        if (type == null) type = inferTypeFromValue(baseValue);
        return switch (type) {
            case INT -> String.valueOf(parseIntOrDefault(baseValue) + parseIntOrDefault(increment));
            case DOUBLE -> String.valueOf(parseDoubleOrDefault(baseValue) + parseDoubleOrDefault(increment));
            case LIST -> {
                if (isBlank(baseValue)) yield increment;
                yield baseValue + "," + increment;
            }
            default -> (baseValue == null ? "" : baseValue) + increment;
        };
    }

    /** 为公式变量计算设置值相对于基础公式的增量 */
    private String calculateIncrementForSet(Variable variable, String setValue, OfflinePlayer player) {
        String base = resolveExpression(variable.getInitial(), player, variable);
        ValueType type = variable.getValueType();
        if (type == null) type = inferTypeFromValue(base);

        switch (type) {
            case INT:
                return String.valueOf(parseIntOrDefault(setValue) - parseIntOrDefault(base));
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
        try {
            return Integer.parseInt(input);
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** 安全解析浮点，失败返回 0.0 */
    private double parseDoubleOrDefault(String input) {
        try {
            return Double.parseDouble(input);
        } catch (Exception ignored) {
            return 0D;
        }
    }

    // ======================== 变量定义加载与验证 ========================

    /** 加载所有变量定义（扫描 variables 目录） */
    private void loadAllVariableDefinitions() {
        try {
            Map<String, YamlConfiguration> configs = yamlUtil.loadAllConfigsInFolder("variables");
            for (String name : configs.keySet()) {
                try {
                    loadVariableDefinitionsFromFile(name);
                    logger.debug("已加载变量文件: " + name);
                } catch (Exception e) {
                    logger.error("加载变量文件失败: " + name, e);
                }
            }
            logger.info("变量目录扫描完成，共加载 " + configs.size() + " 个配置文件");
        } catch (Exception e) {
            logger.error("扫描变量目录失败", e);
        }
    }

    /** 从单个配置文件加载变量定义 */
    private void loadVariableDefinitionsFromFile(String configName) {
        FileConfiguration config = yamlUtil.getConfig(configName);
        if (config == null) {
            logger.warn("配置文件不存在: " + configName);
            return;
        }
        ConfigurationSection section = config.getConfigurationSection("variables");
        if (section == null) {
            logger.warn("未找到 variables 节: " + configName);
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                ConfigurationSection varSec = section.getConfigurationSection(key);
                if (varSec != null) {
                    Variable var = parseVariableDefinition(key, varSec);
                    registerVariable(var);
                }
            } catch (Exception e) {
                logger.error("解析变量定义失败: " + key, e);
            }
        }
    }

    /** 解析单个变量定义（补充了空值安全与字段校验） */
    private Variable parseVariableDefinition(String key, ConfigurationSection section) {
        Variable.Builder builder = new Variable.Builder(key);
        Limitations.Builder lb = new Limitations.Builder();

        builder.name(section.getString("name"))
                .scope(section.getString("scope", "player"))
                .initial(section.getString("initial"))
                .cycle(section.getString("cycle"));

        String typeStr = section.getString("type");
        if (!isBlank(typeStr)) {
            ValueType vt = ValueType.fromString(typeStr);
            if (vt != null) {
                builder.valueType(vt);
            } else {
                logger.warn("变量 " + key + " 定义了无效的类型: " + typeStr);
            }
        }

        if (section.contains("min")) lb.minValue(section.getString("min"));
        if (section.contains("max")) lb.maxValue(section.getString("max"));

        if (section.isConfigurationSection("limitations")) {
            ConfigurationSection limSec = section.getConfigurationSection("limitations");
            applyLimitationsFromSection(limSec, lb);
        }

        builder.limitations(lb.build());
        return builder.build();
    }

    /** 从配置节填充 Limitations.Builder（减少重复 null 判断） */
    private void applyLimitationsFromSection(ConfigurationSection limSec, Limitations.Builder lb) {
        if (limSec == null) return;
        if (limSec.contains("read-only")) lb.readOnly(limSec.getBoolean("read-only"));
        if (limSec.contains("persistable")) lb.persistable(limSec.getBoolean("persistable"));
        if (limSec.contains("strict-initial-mode")) lb.strictInitialMode(limSec.getBoolean("strict-initial-mode"));
        if (limSec.contains("max-recursion-depth")) lb.maxRecursionDepth(limSec.getInt("max-recursion-depth"));
        if (limSec.contains("max-expression-length")) lb.maxExpressionLength(limSec.getInt("max-expression-length"));
        if (limSec.contains("allow-circular-references")) lb.allowCircularReferences(limSec.getBoolean("allow-circular-references"));
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
                if (err.startsWith("警告:")) {
                    logger.warn("变量 " + v.getKey() + ": " + err);
                } else {
                    logger.error("变量 " + v.getKey() + ": " + err);
                }
            }
        }
        currentValidatingVariable.remove();
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
        }
    }

    /** 加载所有全局变量（抽取查询三元组的复用逻辑） */
    private CompletableFuture<Void> loadServerVariables() {
        return CompletableFuture.runAsync(() -> {
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (String key : getGlobalVariableKeys()) {
                    futures.add(fetchServerTriple(key).thenAccept(tri -> {
                        if (tri.value != null) {
                            memoryStorage.loadServerVariable(
                                    key, tri.value, tri.updatedAt, tri.firstModifiedAt);
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
                for (String key : getPlayerVariableKeys()) {
                    futures.add(fetchPlayerTriple(pid, key).thenAccept(tri -> {
                        if (tri.value != null) {
                            memoryStorage.loadPlayerVariable(
                                    player.getUniqueId(), key, tri.value, tri.updatedAt, tri.firstModifiedAt);
                        }
                    }).exceptionally(ex -> {
                        logger.error("加载玩家变量失败: " + pid + " - " + key, ex);
                        return null;
                    }));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                // 加载完成后，主动进行一次缓存预热，确保 join 后首次读取命中缓存
                try {
                    for (String key : getPlayerVariableKeys()) {
                        Variable var = getVariableDefinition(key);
                        if (var != null) {
                            cacheManager.preloadCache(player, key, var);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("玩家变量缓存预热失败: " + player.getName());
                }
            } catch (Exception e) {
                logger.error("加载玩家变量失败: " + player.getUniqueId(), e);
            }
        }, asyncTaskManager.getExecutor());
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
    private String calculateStrictInitialValue(Variable variable, OfflinePlayer player, String initialExpression) {
        try {
            // 如果变量需要依赖快照，使用快照机制
            if (variable.needsDependencySnapshot()) {
                DependencySnapshot snapshot = dependencyResolver.resolveWithSnapshot(
                        variable, player, (p, key) -> {
                            Variable depVar = getVariableDefinition(key);
                            return depVar != null ? getVariableFromMemoryOrDefault(p, depVar) : null;
                        }
                );

                if (snapshot.hasCircularDependency()) {
                    logger.warn("变量存在循环依赖，使用原始表达式: " + variable.getKey());
                    return initialExpression;
                }

                return snapshot.getCalculatedValue();
            } else {
                // 不需要快照，直接解析表达式
                return resolveExpression(initialExpression, player, variable);
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

    // ======================== 末尾隐藏：未被调用的注释内容（保持位置最末） ========================
    // （当前无仅注释且未被调用的片段）
}
