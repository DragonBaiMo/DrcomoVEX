package cn.drcomo.managers;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.model.VariableResult;
import cn.drcomo.model.structure.Variable;
import cn.drcomo.model.structure.Limitations;
import cn.drcomo.model.structure.ValueType;
import cn.drcomo.storage.VariableMemoryStorage;
import cn.drcomo.storage.BatchPersistenceManager;
import cn.drcomo.storage.MultiLevelCacheManager;
import cn.drcomo.storage.VariableValue;
import cn.drcomo.storage.BatchPersistenceManager.PersistenceConfig;
import cn.drcomo.storage.MultiLevelCacheManager.CacheConfig;
import cn.drcomo.storage.MultiLevelCacheManager.CacheResult;
import cn.drcomo.database.HikariConnection;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.corelib.config.YamlUtil;
import cn.drcomo.corelib.async.AsyncTaskManager;
import cn.drcomo.corelib.hook.placeholder.PlaceholderAPIUtil;
import cn.drcomo.corelib.math.FormulaCalculator;
import cn.drcomo.util.ValueLimiter;

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
import java.util.regex.Pattern;
import java.util.regex.Matcher;
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
 * 所有公共方法和字段保持访问权限和方法名不变，提取公共私有方法以提高代码复用性。
 */
public class RefactoredVariablesManager {

    // ======================== 常量配置 ========================
    private static final int MAX_RECURSION_DEPTH = 10;
    private static final int MAX_EXPRESSION_LENGTH = 1000;
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%[^%]+%");
    private static final Pattern INTERNAL_VAR_PATTERN = Pattern.compile("\\$\\{[^}]+}");

    // ======================== 插件核心组件 ========================
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

        // 初始化内存存储（256MB）
        this.memoryStorage = new VariableMemoryStorage(logger, 256 * 1024 * 1024);

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
     * @param isGlobal 是否为全局变量
     * @param key      变量键
     * @return 首次修改时间，若不存在返回 null
     */
    public Long getFirstModifiedAt(boolean isGlobal, String key) {
        if (isGlobal) {
            VariableValue vv = memoryStorage.getServerVariable(key);
            return vv != null ? vv.getFirstModifiedAt() : null;
        }
        Long first = memoryStorage.getPlayerFirstModifiedAt(key);
        if (first != null) {
            return first;
        }
        try {
            String val = database
                    .queryValueAsync("SELECT MIN(first_modified_at) FROM player_variables WHERE variable_key = ?", key)
                    .join();
            return val != null ? Long.parseLong(val) : null;
        } catch (Exception e) {
            logger.error("查询玩家变量首次修改时间失败: " + key, e);
            return null;
        }
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
                if (pre.isPresent()) {
                    return pre.get();
                }

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
                if (pre.isPresent()) {
                    return pre.get();
                }
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
                if (pre.isPresent()) {
                    return pre.get();
                }

                String currentValue = getVariableFromMemoryOrDefault(player, variable);
                String newIncrement;
                String displayValue;
                if (isFormulaVariable(variable)) {
                    String resolvedAdd = resolveExpression(addValue, player, variable);
                    String currInc = Optional.ofNullable(getMemoryValue(player, variable))
                            .map(VariableValue::getValue).orElse("0");
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
                // 二次校验与限幅
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
                if (pre.isPresent()) {
                    return pre.get();
                }

                String currentValue = getVariableFromMemoryOrDefault(player, variable);
                String newIncrement;
                String displayValue;
                if (isFormulaVariable(variable)) {
                    newIncrement = calculateFormulaIncrement(
                            variable.getValueType(),
                            Optional.ofNullable(getMemoryValue(player, variable)).map(VariableValue::getValue).orElse("0"),
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
                if (pre.isPresent()) {
                    return pre.get();
                }

                removeFromMemoryAndInvalidate(player, variable);
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
                    }
                });
    }

    /**
     * 玩家上线时的数据预加载
     */
    public CompletableFuture<Void> handlePlayerJoin(OfflinePlayer player) {
        return CompletableFuture.runAsync(() -> {
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

    // ======================== 私有辅助方法 ========================

    /**
     * 检查变量是否存在及是否可写，返回操作前置校验结果
     * @param variable        变量定义对象
     * @param key             变量键
     * @param op              操作类型（GET/SET/ADD/REMOVE/RESET）
     * @param playerName      玩家名称或 SERVER
     * @param requireWritable 是否要求变量可写
     * @return 若不通过则返回对应失败结果，否则返回 Optional.empty()
     */
    private Optional<VariableResult> checkOpPreconditions(
            Variable variable, String key, String op, String playerName, boolean requireWritable) {
        if (variable == null) {
            return Optional.of(VariableResult.failure("变量不存在: " + key, op, key, playerName));
        }
        if (requireWritable
                && variable.getLimitations() != null
                && variable.getLimitations().isReadOnly()) {
            return Optional.of(VariableResult.failure("变量为只读模式: " + key, op, key, playerName));
        }
        return Optional.empty();
    }

    /** 获取玩家名，若为空则返回 "SERVER" */
    private String getPlayerName(OfflinePlayer player) {
        return player != null ? player.getName() : "SERVER";
    }

    /** 私有方法：获取内存存储的 VariableValue 对象 */
    private VariableValue getMemoryValue(OfflinePlayer player, Variable variable) {
        if (variable.isPlayerScoped() && player != null) {
            return memoryStorage.getPlayerVariable(player.getUniqueId(), variable.getKey());
        } else if (variable.isGlobal()) {
            return memoryStorage.getServerVariable(variable.getKey());
        }
        return null;
    }

    /** 从内存获取变量值或返回默认/初始值 */
    private String getVariableFromMemoryOrDefault(OfflinePlayer player, Variable variable) {
        VariableValue val = getMemoryValue(player, variable);
        String init = variable.getInitial();
        boolean isFormula = isFormulaVariable(variable);

        if (isFormula && init != null && !init.trim().isEmpty()) {
            String base = resolveExpression(init, player, variable);
            return (val != null)
                    ? addFormulaIncrement(base, val.getValue(), variable.getValueType())
                    : base;
        }
        if (val != null) {
            return val.getValue();
        }
        if (init != null && !init.trim().isEmpty()) {
            return init;
        }
        return getDefaultValueByType(variable.getValueType());
    }

    /** 设置变量到内存并根据 persistable 配置标记脏数据 */
    private void updateMemoryAndInvalidate(OfflinePlayer player, Variable variable, String value) {
        boolean persist = variable.getLimitations() == null
                || variable.getLimitations().isPersistable();
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
        }
        cacheManager.invalidateCache(player, variable.getKey());
    }

    /** 从内存删除变量并清除缓存 */
    private void removeFromMemoryAndInvalidate(OfflinePlayer player, Variable variable) {
        if (variable.isPlayerScoped() && player != null) {
            memoryStorage.removePlayerVariable(player.getUniqueId(), variable.getKey());
        } else if (variable.isGlobal()) {
            memoryStorage.removeServerVariable(variable.getKey());
        }
        cacheManager.invalidateCache(player, variable.getKey());
    }

    /**
     * 解析表达式、占位符及内部变量（支持 Variable 的安全限制配置）
     */
    private String resolveExpression(String expression, OfflinePlayer player, Variable variable) {
        if (expression == null || expression.trim().isEmpty()
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
            if (!allowCircular) {
                seen.add(result);
            }

            String prev = result;
            result = resolveInternalVariables(result, player);
            if (player != null && player.isOnline()) {
                result = placeholderUtil.parse(player.getPlayer(), result);
            }
            if (result.matches(".*[+\\-*/()^].*") && result.length() <= maxLen) {
                try {
                    result = String.valueOf(FormulaCalculator.calculate(result));
                } catch (Exception e) {
                    logger.debug("数学表达式计算失败: " + result);
                }
            }
            if (result.equals(prev)) {
                break;
            }
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
        if (value == null) {
            return null;
        }
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
        if (type == null) {
            type = inferTypeFromValue(value);
        }
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
                    case INT:
                    case DOUBLE:
                        try {
                            double num = 0;
                            if (value != null) {
                                num = Double.parseDouble(value);
                            }
                            if (!lim.isValueInRange(num)) {
                                logger.warn("变量 " + variable.getKey() + " 的值 " + num
                                        + " 超出范围 [" + lim.getMinValue() + ", " + lim.getMaxValue() + "]");
                                return false;
                            }
                        } catch (NumberFormatException ignored) { }
                        break;
                    case STRING:
                    case LIST:
                        if (lim.getMinValue() != null || lim.getMaxValue() != null) {
                            int min = 0, max = Integer.MAX_VALUE;
                            try {
                                if (lim.getMinValue() != null) {
                                    min = Integer.parseInt(lim.getMinValue());
                                }
                                if (lim.getMaxValue() != null) {
                                    max = Integer.parseInt(lim.getMaxValue());
                                }
                                if (value != null && (value.length() < min || value.length() > max)) {
                                    logger.warn("变量 " + variable.getKey() + " 的长度 "
                                            + value.length() + " 超出范围 [" + min + ", " + max + "]");
                                    return false;
                                }
                            } catch (NumberFormatException ignored) {
                                logger.debug("字符串类型长度限制解析异常: " + variable.getKey());
                            }
                        }
                        break;
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

    /** 判断是否为公式变量 */
    private boolean isFormulaVariable(Variable variable) {
        String init = variable.getInitial();
        return init != null && !init.trim().isEmpty()
                && (PLACEHOLDER_PATTERN.matcher(init).find()
                || INTERNAL_VAR_PATTERN.matcher(init).find()
                || init.matches(".*[+\\-*/()^].*"));
    }

    /** 根据类型执行普通加法 */
    private String calculateAddition(ValueType type, String currentValue, String addValue) {
        return getValueType(currentValue, addValue, type);
    }

    /** 根据类型执行普通删除操作 */
    private String calculateRemoval(ValueType type, String currentValue, String removeValue) {
        if (currentValue == null || currentValue.trim().isEmpty()) {
            return currentValue;
        }
        if (type == null) {
            type = inferTypeFromValue(currentValue);
        }
        switch (type) {
            case INT:
                return String.valueOf(parseIntOrDefault(currentValue)
                        - parseIntOrDefault(removeValue));
            case DOUBLE:
                return String.valueOf(parseDoubleOrDefault(currentValue)
                        - parseDoubleOrDefault(removeValue));
            case LIST:
                List<String> items = new ArrayList<>(Arrays.asList(currentValue.split(",")));
                items.removeIf(item -> item.trim().equals(removeValue.trim()));
                return String.join(",", items);
            default:
                return currentValue.replace(removeValue, "");
        }
    }

    /**
     * 公式变量增量计算
     * @param isAdd true 表示加法，false 表示删除/减法
     */
    private String calculateFormulaIncrement(ValueType type, String currentIncrement, String value, boolean isAdd) {
        if (type == null) {
            type = inferTypeFromValue(currentIncrement);
        }
        int sign = isAdd ? 1 : -1;
        switch (type) {
            case INT:
                return String.valueOf(parseIntOrDefault(currentIncrement)
                        + sign * parseIntOrDefault(value));
            case DOUBLE:
                return String.valueOf(parseDoubleOrDefault(currentIncrement)
                        + sign * parseDoubleOrDefault(value));
            case LIST:
                boolean isboolean = currentIncrement == null || currentIncrement.trim().isEmpty()
                        || "0".equals(currentIncrement.trim());
                if (isAdd) {
                    if (isboolean) {
                        return value;
                    }
                    return currentIncrement + "," + value;
                } else {
                    if (isboolean) {
                        return "0";
                    }
                    List<String> list = new ArrayList<>(Arrays.asList(currentIncrement.split(",")));
                    list.removeIf(item -> item.trim().equals(value.trim()));
                    String res = String.join(",", list);
                    return res.isEmpty() ? "0" : res;
                }
            default:
                if (isAdd) {
                    return (currentIncrement.equals("0") ? "" : currentIncrement) + value;
                } else {
                    return currentIncrement.replace(value, "");
                }
        }
    }

    /** 为公式变量添加增量 - 基础值 + 增量 */
    private String addFormulaIncrement(String baseValue, String increment, ValueType type) {
        if (increment == null || increment.trim().isEmpty() || "0".equals(increment.trim())) {
            return baseValue;
        }
        return getValueType(baseValue, increment, type);
    }

    private String getValueType(String baseValue, String increment, ValueType type) {
        if (type == null) {
            type = inferTypeFromValue(baseValue);
        }
        return switch (type) {
            case INT -> String.valueOf(parseIntOrDefault(baseValue)
                    + parseIntOrDefault(increment));
            case DOUBLE -> String.valueOf(parseDoubleOrDefault(baseValue)
                    + parseDoubleOrDefault(increment));
            case LIST -> {
                if (baseValue == null || baseValue.trim().isEmpty()) {
                    yield increment;
                }
                yield baseValue + "," + increment;
            }
            default -> (baseValue == null ? "" : baseValue) + increment;
        };
    }

    /** 为公式变量计算设置值相对于基础公式的增量 */
    private String calculateIncrementForSet(Variable variable, String setValue, OfflinePlayer player) {
        String base = resolveExpression(variable.getInitial(), player, variable);
        ValueType type = variable.getValueType();
        if (type == null) {
            type = inferTypeFromValue(base);
        }
        switch (type) {
            case INT:
                return String.valueOf(parseIntOrDefault(setValue)
                        - parseIntOrDefault(base));
            case DOUBLE:
                return String.valueOf(parseDoubleOrDefault(setValue)
                        - parseDoubleOrDefault(base));
            case LIST:
                if (setValue == null || setValue.trim().isEmpty()) {
                    return "";
                }
                if (base == null || base.trim().isEmpty()) {
                    return setValue;
                }
                if (setValue.startsWith(base)) {
                    String diff = setValue.substring(base.length());
                    return diff.startsWith(",") ? diff.substring(1) : diff;
                }
                return setValue;
            default:
                if (setValue == null || setValue.trim().isEmpty()) {
                    return "";
                }
                if (base == null || base.trim().isEmpty()) {
                    return setValue;
                }
                if (setValue.startsWith(base)) {
                    return setValue.substring(base.length());
                }
                return setValue;
        }
    }

    /** 推断值类型 */
    private ValueType inferTypeFromValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return ValueType.STRING;
        }
        if (value.matches("-?\\d+")) {
            return ValueType.INT;
        }
        if (value.matches("-?\\d*\\.\\d+")) {
            return ValueType.DOUBLE;
        }
        if (value.contains(",")) {
            return ValueType.LIST;
        }
        return ValueType.STRING;
    }

    /** 获取类型默认值 */
    private String getDefaultValueByType(ValueType type) {
        if (type == null) {
            return "0";
        }
        return switch (type) {
            case INT, DOUBLE -> "0";
            default -> "";
        };
    }

    /** 安全解析整型，失败返回默认值 */
    private int parseIntOrDefault(String input) {
        try {
            return Integer.parseInt(input);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 安全解析浮点，失败返回默认值 */
    private double parseDoubleOrDefault(String input) {
        try {
            return Double.parseDouble(input);
        } catch (Exception e) {
            return 0;
        }
    }

    // ======================== 变量定义加载与验证 ========================

    /** 加载所有变量定义 */
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

    /** 解析单个变量定义 */
    private Variable parseVariableDefinition(String key, ConfigurationSection section) {
        Variable.Builder builder = new Variable.Builder(key);
        Limitations.Builder lb = new Limitations.Builder();

        builder.name(section.getString("name"))
                .scope(section.getString("scope", "player"))
                .initial(section.getString("initial"))
                .cycle(section.getString("cycle"));

        String typeStr = section.getString("type");
        if (typeStr != null) {
            ValueType vt = ValueType.fromString(typeStr);
            if (vt != null) {
                builder.valueType(vt);
            } else {
                logger.warn("变量 " + key + " 定义了无效的类型: " + typeStr);
            }
        }

        if (section.contains("min")) {
            lb.minValue(section.getString("min"));
        }
        if (section.contains("max")) {
            lb.maxValue(section.getString("max"));
        }

        if (section.isConfigurationSection("limitations")) {
            ConfigurationSection limSec = section.getConfigurationSection("limitations");
            if (limSec != null && limSec.contains("read-only")) lb.readOnly(limSec.getBoolean("read-only"));
            if (limSec != null && limSec.contains("persistable")) lb.persistable(limSec.getBoolean("persistable"));
            if (limSec != null && limSec.contains("max-recursion-depth"))
                lb.maxRecursionDepth(limSec.getInt("max-recursion-depth"));
            if (limSec != null && limSec.contains("max-expression-length"))
                lb.maxExpressionLength(limSec.getInt("max-expression-length"));
            if (limSec != null && limSec.contains("allow-circular-references"))
                lb.allowCircularReferences(limSec.getBoolean("allow-circular-references"));
        }

        builder.limitations(lb.build());
        return builder.build();
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

    /** 加载所有全局变量 */
    private CompletableFuture<Void> loadServerVariables() {
        return CompletableFuture.runAsync(() -> {
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (String key : getGlobalVariableKeys()) {
                    CompletableFuture<String> vF = database
                            .queryValueAsync("SELECT value FROM server_variables WHERE variable_key = ?", key);
                    CompletableFuture<String> uF = database
                            .queryValueAsync("SELECT updated_at FROM server_variables WHERE variable_key = ?", key);
                    CompletableFuture<String> fF = database
                            .queryValueAsync("SELECT first_modified_at FROM server_variables WHERE variable_key = ?", key);
                    futures.add(CompletableFuture.allOf(vF, uF, fF).thenRun(() -> {
                        String val = vF.join();
                        if (val != null) {
                            memoryStorage.loadServerVariable(key, val,
                                    parseLong(uF.join()), parseLong(fF.join()));
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

    /** 加载单个玩家的变量 */
    private CompletableFuture<Void> loadPlayerVariables(OfflinePlayer player) {
        return CompletableFuture.runAsync(() -> {
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                String pid = player.getUniqueId().toString();
                for (String key : getPlayerVariableKeys()) {
                    CompletableFuture<String> vF = database.queryValueAsync(
                            "SELECT value FROM player_variables WHERE player_uuid = ? AND variable_key = ?", pid, key);
                    CompletableFuture<String> uF = database.queryValueAsync(
                            "SELECT updated_at FROM player_variables WHERE player_uuid = ? AND variable_key = ?", pid, key);
                    CompletableFuture<String> fF = database.queryValueAsync(
                            "SELECT first_modified_at FROM player_variables WHERE player_uuid = ? AND variable_key = ?", pid, key);
                    futures.add(CompletableFuture.allOf(vF, uF, fF).thenRun(() -> {
                        String val = vF.join();
                        if (val != null) {
                            memoryStorage.loadPlayerVariable(
                                    player.getUniqueId(), key, val,
                                    parseLong(uF.join()), parseLong(fF.join()));
                        }
                    }).exceptionally(ex -> {
                        logger.error("加载玩家变量失败: " + pid + " - " + key, ex);
                        return null;
                    }));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (Exception e) {
                logger.error("加载玩家变量失败: " + player.getUniqueId(), e);
            }
        }, asyncTaskManager.getExecutor());
    }

    /** 安全解析为 long，失败返回当前时间戳 */
    private long parseLong(String value) {
        try {
            return value == null ? System.currentTimeMillis() : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return System.currentTimeMillis();
        }
    }
}
