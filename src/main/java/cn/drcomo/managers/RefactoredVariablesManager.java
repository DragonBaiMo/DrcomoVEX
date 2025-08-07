package cn.drcomo.managers;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.model.VariableResult;
import cn.drcomo.model.structure.*;
import cn.drcomo.database.HikariConnection;
import cn.drcomo.storage.*;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.corelib.config.YamlUtil;
import cn.drcomo.corelib.async.AsyncTaskManager;
import cn.drcomo.corelib.hook.placeholder.PlaceholderAPIUtil;
import cn.drcomo.corelib.math.FormulaCalculator;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
 * 所有公共方法和字段保持访问权限和方法名不变，提取公共私有方法以提高代码复用性。
 */
public class RefactoredVariablesManager {

    // ======================== 常量配置 ========================
    private static final int MAX_RECURSION_DEPTH = 10;
    private static final int MAX_EXPRESSION_LENGTH = 1000;
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%[^%]+%");
    private static final Pattern INTERNAL_VAR_PATTERN = Pattern.compile("\\$\\{[^}]+\\}");

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

    // 线程本地变量，用于在验证过程中传递当前变量键
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

        // 初始化存储组件（256MB 内存）
        this.memoryStorage = new VariableMemoryStorage(logger, 256 * 1024 * 1024);

        // 初始化多级缓存
        MultiLevelCacheManager.CacheConfig cacheConfig = new MultiLevelCacheManager.CacheConfig()
                .setL2MaximumSize(10000)
                .setL2ExpireMinutes(5)
                .setL3MaximumSize(5000)
                .setL3ExpireMinutes(2);
        this.cacheManager = new MultiLevelCacheManager(logger, memoryStorage, cacheConfig);

        // 初始化批量持久化管理器
        BatchPersistenceManager.PersistenceConfig persistenceConfig = new BatchPersistenceManager.PersistenceConfig()
                .setBatchIntervalSeconds(30)
                .setMaxBatchSize(1000)
                .setMemoryPressureThreshold(80.0)
                .setMaxRetries(3);
        this.persistenceManager = new BatchPersistenceManager(
                logger, database, memoryStorage, asyncTaskManager, persistenceConfig);
    }

    // ======================== 生命周期管理方法 ========================

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
                preloadDatabaseData();
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
                // 1. 关闭持久化管理器，确保数据写入
                persistenceManager.shutdown();
                logger.info("持久化管理器关闭完成");

                // 2. 关闭数据库连接
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

            // 缓存清理放在单独try块，避免影响主流程
            try {
                cacheManager.clearAllCaches();
            } catch (Exception e) {
                logger.debug("缓存清理跳过: " + e.getMessage());
            }
        }, asyncTaskManager.getExecutor());
    }

    /**
     * 重载所有变量定义
     */
    public CompletableFuture<Void> reload() {
        logger.info("正在重载变量定义...");
        return CompletableFuture.runAsync(() -> {
            try {
                cacheManager.clearAllCaches();
            } catch (Exception e) {
                logger.debug("缓存清理跳过: " + e.getMessage());
            }

            try {
                variableRegistry.clear();
                loadAllVariableDefinitions();
                validateVariableDefinitions();
                logger.info("变量定义重载完成！已加载 " + variableRegistry.size() + " 个变量定义");
            } catch (Exception e) {
                logger.error("变量定义重载失败", e);
                throw new RuntimeException("变量定义重载失败", e);
            }
        }, asyncTaskManager.getExecutor());
    }

    /**
     * 保存所有数据
     */
    public CompletableFuture<Void> saveAllData() {
        logger.info("正在保存所有变量数据...");
        return CompletableFuture.runAsync(() -> {
            try {
                persistenceManager.flushAllDirtyData().join();
                logger.info("所有变量数据保存完成！");
            } catch (Exception e) {
                logger.error("保存变量数据失败！", e);
                throw new RuntimeException("保存变量数据失败", e);
            }

            try {
                cacheManager.clearAllCaches();
            } catch (Exception e) {
                logger.debug("缓存清理跳过: " + e.getMessage());
            }
        }, asyncTaskManager.getExecutor());
    }

    // ======================== 公共 API 方法 ========================

    /**
     * 获取变量值（完全异步，无阻塞）
     */
    public CompletableFuture<VariableResult> getVariable(OfflinePlayer player, String key) {
        final String playerName = getPlayerName(player);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Variable variable = getVariableDefinition(key);
                if (variable == null) {
                    return VariableResult.failure("变量不存在: " + key, "GET", key, playerName);
                }
                // 多级缓存尝试
                MultiLevelCacheManager.CacheResult cacheResult =
                        cacheManager.getFromCache(player, key, variable);
                if (cacheResult.isHit()) {
                    logger.debug("缓存命中(" + cacheResult.getLevel() + "): " + key);
                    return VariableResult.success(cacheResult.getValue(), "GET", key, playerName);
                }
                
                // 对于公式变量，必须通过 getVariableFromMemoryOrDefault 获取完整值
                // 对于普通变量，也使用统一的逻辑以保持一致性
                String finalValue = getVariableFromMemoryOrDefault(player, variable);
                
                // 对于非公式变量，还需要进行表达式解析
                if (!isFormulaVariable(variable)) {
                    finalValue = resolveExpression(finalValue, player, variable);
                }
                
                // 缓存最终结果
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
                if (variable == null) {
                    return VariableResult.failure("变量不存在: " + key, "SET", key, playerName);
                }
                if (variable.getLimitations() != null && variable.getLimitations().isReadOnly()) {
                    return VariableResult.failure("变量为只读模式: " + key, "SET", key, playerName);
                }
                String processedValue = processAndValidateValue(variable, value, player);
                if (processedValue == null) {
                    return VariableResult.failure("值格式错误或超出约束: " + value, "SET", key, playerName);
                }

                if (isFormulaVariable(variable)) {
                    // 计算相对于基础公式的增量
                    String increment = calculateIncrementForSet(variable, processedValue, player);
                    updateMemoryAndInvalidate(player, variable, increment);
                    logger.debug("设置公式变量: " + key + " 增量= " + increment + " 最终值= " + processedValue + " (异步持久化中)");
                } else {
                    updateMemoryAndInvalidate(player, variable, processedValue);
                    logger.debug("设置变量: " + key + " = " + processedValue + " (异步持久化中)");
                }
                return VariableResult.success(processedValue, "SET", key, playerName);
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
                if (variable == null) {
                    return VariableResult.failure("变量不存在: " + key, "ADD", key, playerName);
                }
                if (variable.getLimitations() != null && variable.getLimitations().isReadOnly()) {
                    return VariableResult.failure("变量为只读模式: " + key, "ADD", key, playerName);
                }
                String currentValue = getVariableFromMemoryOrDefault(player, variable);
                String newValue;
                String finalDisplayValue;
                
                if (isFormulaVariable(variable)) {
                    // 公式变量：维护增量
                    String resolvedAdd = resolveExpression(addValue, player, variable);
                    String currentIncrement = Optional.ofNullable(getMemoryValue(player, variable))
                            .map(VariableValue::getValue).orElse("0");
                    newValue = calculateFormulaIncrement(variable.getValueType(), currentIncrement, resolvedAdd, true);
                    
                    // 计算最终显示值：基础公式值 + 新增量
                    String baseValue = resolveExpression(variable.getInitial(), player, variable);
                    finalDisplayValue = addFormulaIncrement(baseValue, newValue, variable.getValueType());
                } else {
                    // 普通变量加法
                    String resolvedAdd = resolveExpression(addValue, player, variable);
                    newValue = calculateAddition(variable.getValueType(), currentValue, resolvedAdd);
                    finalDisplayValue = newValue;
                }
                
                if (newValue == null) {
                    return VariableResult.failure("加法操作失败或超出约束", "ADD", key, playerName);
                }
                updateMemoryAndInvalidate(player, variable, newValue);
                logger.debug("加法操作: " + key + " = " + finalDisplayValue + " (当前: " + currentValue + " + 增加: " + addValue + ")");
                return VariableResult.success(finalDisplayValue, "ADD", key, playerName);
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
                if (variable == null) {
                    return VariableResult.failure("变量不存在: " + key, "REMOVE", key, playerName);
                }
                if (variable.getLimitations() != null && variable.getLimitations().isReadOnly()) {
                    return VariableResult.failure("变量为只读模式: " + key, "REMOVE", key, playerName);
                }
                String currentValue = getVariableFromMemoryOrDefault(player, variable);
                String newValue;
                String finalDisplayValue;
                
                if (isFormulaVariable(variable)) {
                    // 公式变量删除同样维护增量
                    newValue = calculateFormulaIncrement(
                            variable.getValueType(),
                            Optional.ofNullable(getMemoryValue(player, variable)).map(VariableValue::getValue).orElse("0"),
                            removeValue, false);
                    
                    // 计算最终显示值：基础公式值 + 新增量
                    String baseValue = resolveExpression(variable.getInitial(), player, variable);
                    finalDisplayValue = addFormulaIncrement(baseValue, newValue, variable.getValueType());
                } else {
                    newValue = calculateRemoval(variable.getValueType(), currentValue, removeValue);
                    finalDisplayValue = newValue;
                }
                
                if (newValue == null) {
                    return VariableResult.failure("删除操作失败", "REMOVE", key, playerName);
                }
                updateMemoryAndInvalidate(player, variable, newValue);
                logger.debug("删除操作: " + key + " = " + finalDisplayValue + " (删除: " + removeValue + ")");
                return VariableResult.success(finalDisplayValue, "REMOVE", key, playerName);
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
                if (variable == null) {
                    return VariableResult.failure("变量不存在: " + key, "RESET", key, playerName);
                }
                if (variable.getLimitations() != null && variable.getLimitations().isReadOnly()) {
                    return VariableResult.failure("变量为只读模式: " + key, "RESET", key, playerName);
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

    // ======================== 私有辅助方法 ========================

    /** 获取玩家名，若为空则返回 "SERVER" */
    private String getPlayerName(OfflinePlayer player) {
        return player != null ? player.getName() : "SERVER";
    }

    /** 根据值类型返回默认值 */
    private String getDefaultValueByType(ValueType type) {
        if (type == null) {
            return "0";
        }
        switch (type) {
            case INT:
            case DOUBLE:
                return "0";
            case LIST:
            case STRING:
            default:
                return "";
        }
    }

    /**
     * 私有方法：获取内存存储的变量值
     */
    private VariableValue getMemoryValue(OfflinePlayer player, Variable variable) {
        if (variable.isPlayerScoped() && player != null) {
            return memoryStorage.getPlayerVariable(player.getUniqueId(), variable.getKey());
        } else if (variable.isGlobal()) {
            return memoryStorage.getServerVariable(variable.getKey());
        } else {
            return null;
        }
    }

    /** 从内存获取变量值或返回默认/初始值 */
    private String getVariableFromMemoryOrDefault(OfflinePlayer player, Variable variable) {
        VariableValue val = getMemoryValue(player, variable);
        String init = variable.getInitial();
        boolean isFormula = isFormulaVariable(variable);

        if (isFormula && init != null && !init.trim().isEmpty()) {
            // 公式变量：基础值 + 内存增量
            String baseValue = resolveExpression(init, player, variable);
            if (val != null) {
                return addFormulaIncrement(baseValue, val.getValue(), variable.getValueType());
            } else {
                return baseValue;
            }
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
    private void setVariableInMemory(OfflinePlayer player, Variable variable, String value) {
        boolean shouldPersist = variable.getLimitations() == null || variable.getLimitations().isPersistable();
        
        if (variable.isPlayerScoped() && player != null) {
            memoryStorage.setPlayerVariable(player.getUniqueId(), variable.getKey(), value);
            // 如果变量不可持久化，清除脏数据标记
            if (!shouldPersist) {
                memoryStorage.clearDirtyFlag("player:" + player.getUniqueId() + ":" + variable.getKey());
                logger.debug("变量设置为不可持久化，跳过数据库保存: " + variable.getKey());
            }
        } else if (variable.isGlobal()) {
            memoryStorage.setServerVariable(variable.getKey(), value);
            // 如果变量不可持久化，清除脏数据标记
            if (!shouldPersist) {
                memoryStorage.clearDirtyFlag("server:" + variable.getKey());
                logger.debug("服务器变量设置为不可持久化，跳过数据库保存: " + variable.getKey());
            }
        }
    }

    /** 从内存删除变量 */
    private void removeVariableFromMemory(OfflinePlayer player, Variable variable) {
        if (variable.isPlayerScoped() && player != null) {
            memoryStorage.removePlayerVariable(player.getUniqueId(), variable.getKey());
        } else if (variable.isGlobal()) {
            memoryStorage.removeServerVariable(variable.getKey());
        }
    }

    /** 代码复用：更新内存并清除缓存 */
    private void updateMemoryAndInvalidate(OfflinePlayer player, Variable variable, String value) {
        setVariableInMemory(player, variable, value);
        cacheManager.invalidateCache(player, variable.getKey());
    }

    /** 代码复用：删除内存变量并清除缓存 */
    private void removeFromMemoryAndInvalidate(OfflinePlayer player, Variable variable) {
        removeVariableFromMemory(player, variable);
        cacheManager.invalidateCache(player, variable.getKey());
    }

    /**
     * 解析表达式、占位符及内部变量（使用默认安全限制）
     */
    private String resolveExpression(String expression, OfflinePlayer player) {
        return resolveExpression(expression, player, null);
    }

    /**
     * 解析表达式、占位符及内部变量（支持 Variable 的安全限制配置）
     */
    private String resolveExpression(String expression, OfflinePlayer player, Variable variable) {
        if (expression == null || expression.trim().isEmpty()) {
            return expression;
        }
        if (!containsPlaceholders(expression)
                && !containsInternalVariables(expression)
                && !containsMathExpression(expression)) {
            return expression;
        }

        // 获取安全限制配置
        int maxDepth = MAX_RECURSION_DEPTH;
        int maxLength = MAX_EXPRESSION_LENGTH;
        boolean allowCircular = false;

        if (variable != null && variable.getLimitations() != null) {
            Limitations limitations = variable.getLimitations();
            maxDepth = limitations.getEffectiveRecursionDepth(MAX_RECURSION_DEPTH);
            if (limitations.getMaxExpressionLength() != null) {
                maxLength = limitations.getMaxExpressionLength();
            }
            allowCircular = limitations.allowsCircularReferences();
        }

        // 表达式长度预检查
        if (expression.length() > maxLength) {
            logger.warn("表达式长度超出安全限制，已截断: " + expression.substring(0, Math.min(100, expression.length())) + "...");
            return expression;
        }

        String result = expression;
        int depth = 0;
        Set<String> processedExpressions = allowCircular ? null : new HashSet<>();
        
        while (depth < maxDepth
                && (containsPlaceholders(result) || containsInternalVariables(result)
                || containsMathExpression(result))) {
            
            // 循环引用检查
            if (!allowCircular && processedExpressions != null && processedExpressions.contains(result)) {
                logger.warn("检测到循环引用，停止表达式解析: " + result);
                break;
            }
            if (!allowCircular && processedExpressions != null) {
                processedExpressions.add(result);
            }

            String prev = result;
            result = resolveInternalVariables(result, player);
            if (player != null && player.isOnline()) {
                result = placeholderUtil.parse(player.getPlayer(), result);
            }
            if (containsMathExpression(result) && result.length() <= maxLength) {
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

    private boolean containsPlaceholders(String text) {
        return text != null && PLACEHOLDER_PATTERN.matcher(text).find();
    }

    private boolean containsInternalVariables(String text) {
        return text != null && INTERNAL_VAR_PATTERN.matcher(text).find();
    }

    private boolean containsMathExpression(String text) {
        return text != null && text.matches(".*[+\\-*/()^].*") && text.matches(".*\\d.*");
    }

    private String resolveInternalVariables(String text, OfflinePlayer player) {
        if (text == null || !containsInternalVariables(text)) {
            return text;
        }

        StringBuilder result = new StringBuilder();
        java.util.regex.Matcher matcher = INTERNAL_VAR_PATTERN.matcher(text);
        int lastEnd = 0;

        while (matcher.find()) {
            result.append(text.substring(lastEnd, matcher.start()));
            String matched = matcher.group();
            String varName = matched.substring(2, matched.length() - 1);
            Variable def = getVariableDefinition(varName);
            try {
                String val = def != null ? getVariableFromMemoryOrDefault(player, def) : null;
                result.append(val != null ? val : matched);
            } catch (Exception e) {
                logger.debug("解析内部变量异常: " + matched + " - " + e.getMessage());
                result.append(matched);
            }
            lastEnd = matcher.end();
        }
        result.append(text.substring(lastEnd));
        return result.toString();
    }

    /** 处理并验证值 */
    private String processAndValidateValue(Variable variable, String value, OfflinePlayer player) {
        if (value == null) {
            return null;
        }
        String resolved = resolveExpression(value, player, variable);
        return validateValue(variable, resolved) ? resolved : null;
    }

    /**
     * 核心验证逻辑 - 使用 Limitations 的完整功能
     * @param variable 变量定义
     * @param value 待验证的值（已被解析）
     * @return true 如果验证通过
     */
    private boolean validateValue(Variable variable, String value) {
        ValueType type = variable.getValueType();
        if (type == null) {
            type = inferTypeFromValue(value);
        }

        // 1. 类型校验
        if (!isValidType(value, type)) {
            logger.warn("变量 " + variable.getKey() + " 的值 '" + value + "' 与其定义的类型 " + type + " 不匹配。");
            return false;
        }

        // 2. Limitations 完整校验
        Limitations limitations = variable.getLimitations();
        if (limitations != null) {
            // 2.1 数值范围校验
            if (limitations.hasValueConstraints()) {
                switch (type) {
                    case INT:
                    case DOUBLE:
                        try {
                            double numericValue = Double.parseDouble(value);
                            if (!limitations.isValueInRange(numericValue)) {
                                logger.warn("变量 " + variable.getKey() + " 的值 " + numericValue + " 超出范围 [" + limitations.getMinValue() + ", " + limitations.getMaxValue() + "]");
                                return false;
                            }
                        } catch (NumberFormatException e) { /* 已经在 isValidType 中处理 */ }
                        break;
                    case STRING:
                    case LIST:
                        // 对于字符串类型，尝试将 min/max 作为长度限制
                        if (limitations.getMinValue() != null || limitations.getMaxValue() != null) {
                            int minLen = 0;
                            int maxLen = Integer.MAX_VALUE;
                            try {
                                if (limitations.getMinValue() != null) {
                                    minLen = Integer.parseInt(limitations.getMinValue());
                                }
                                if (limitations.getMaxValue() != null) {
                                    maxLen = Integer.parseInt(limitations.getMaxValue());
                                }
                                if (value.length() < minLen || value.length() > maxLen) {
                                    logger.warn("变量 " + variable.getKey() + " 的值长度 " + value.length() + " 超出范围 [" + minLen + ", " + maxLen + "]");
                                    return false;
                                }
                            } catch (NumberFormatException e) {
                                logger.debug("字符串类型变量的 min/max 值无法解析为长度限制: " + variable.getKey());
                            }
                        }
                        break;
                }
            }

            // 2.2 表达式长度安全校验
            if (limitations.hasSecurityLimitations()) {
                if (!limitations.isExpressionLengthValid(value)) {
                    logger.warn("变量 " + variable.getKey() + " 的表达式长度超出安全限制: " + value.length());
                    return false;
                }
            }
        }
        return true;
    }

    /** 判断变量是否为公式变量 */
    private boolean isFormulaVariable(Variable variable) {
        String initial = variable.getInitial();
        if (initial == null || initial.trim().isEmpty()) {
            return false;
        }
        return containsPlaceholders(initial)
                || containsInternalVariables(initial)
                || containsMathExpression(initial);
    }

    /**
     * 私有方法：根据类型执行普通变量的加法操作
     */
    private String calculateAddition(ValueType type, String currentValue, String addValue) {
        if (type == null) {
            type = inferTypeFromValue(currentValue);
        }
        switch (type) {
            case INT:
                int ci = parseIntOrDefault(currentValue, 0);
                int ai = parseIntOrDefault(addValue, 0);
                return String.valueOf(ci + ai);
            case DOUBLE:
                double cd = parseDoubleOrDefault(currentValue, 0);
                double ad = parseDoubleOrDefault(addValue, 0);
                return String.valueOf(cd + ad);
            case LIST:
                if (currentValue == null || currentValue.trim().isEmpty()) {
                    return addValue;
                }
                return currentValue + "," + addValue;
            default:
                return (currentValue == null ? "" : currentValue) + addValue;
        }
    }

    /**
     * 私有方法：根据类型执行普通变量的删除操作
     */
    private String calculateRemoval(ValueType type, String currentValue, String removeValue) {
        if (currentValue == null || currentValue.trim().isEmpty()) {
            return currentValue;
        }
        if (type == null) {
            type = inferTypeFromValue(currentValue);
        }
        switch (type) {
            case INT:
                int cv = parseIntOrDefault(currentValue, 0);
                int rv = parseIntOrDefault(removeValue, 0);
                return String.valueOf(cv - rv);
            case DOUBLE:
                double cdd = parseDoubleOrDefault(currentValue, 0);
                double rdd = parseDoubleOrDefault(removeValue, 0);
                return String.valueOf(cdd - rdd);
            case LIST:
                List<String> items = new ArrayList<>(Arrays.asList(currentValue.split(",")));
                items.removeIf(item -> item.trim().equals(removeValue.trim()));
                return String.join(",", items);
            default:
                return currentValue.replace(removeValue, "");
        }
    }

    /**
     * 私有方法：为公式变量执行增量计算
     * @param isAdd true=加法，false=删除/减法
     */
    private String calculateFormulaIncrement(ValueType type, String currentIncrement, String value, boolean isAdd) {
        if (type == null) {
            type = inferTypeFromValue(currentIncrement);
        }
        int sign = isAdd ? 1 : -1;
        switch (type) {
            case INT:
                int currI = parseIntOrDefault(currentIncrement, 0);
                int valI = parseIntOrDefault(value, 0);
                return String.valueOf(currI + sign * valI);
            case DOUBLE:
                double currD = parseDoubleOrDefault(currentIncrement, 0);
                double valD = parseDoubleOrDefault(value, 0);
                return String.valueOf(currD + sign * valD);
            case LIST:
                if (isAdd) {
                    if (currentIncrement == null || currentIncrement.trim().isEmpty() || "0".equals(currentIncrement.trim())) {
                        return value;
                    }
                    return currentIncrement + "," + value;
                } else {
                    if (currentIncrement == null || currentIncrement.trim().isEmpty() || "0".equals(currentIncrement.trim())) {
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

    /** 为公式变量添加增量 - 将基础公式值与增量相加 */
    private String addFormulaIncrement(String baseValue, String increment, ValueType type) {
        if (increment == null || increment.trim().isEmpty() || "0".equals(increment.trim())) {
            return baseValue;
        }
        
        if (type == null) {
            type = inferTypeFromValue(baseValue);
        }
        
        switch (type) {
            case INT:
                int base = parseIntOrDefault(baseValue, 0);
                int inc = parseIntOrDefault(increment, 0);
                return String.valueOf(base + inc);
            case DOUBLE:
                double baseD = parseDoubleOrDefault(baseValue, 0);
                double incD = parseDoubleOrDefault(increment, 0);
                return String.valueOf(baseD + incD);
            case LIST:
                if (baseValue == null || baseValue.trim().isEmpty()) {
                    return increment;
                }
                if (increment.trim().isEmpty()) {
                    return baseValue;
                }
                return baseValue + "," + increment;
            default:
                return (baseValue == null ? "" : baseValue) + increment;
        }
    }

    /** 为公式变量计算设置值相对于基础公式的增量（保持原逻辑） */
    private String calculateIncrementForSet(Variable variable, String setValue, OfflinePlayer player) {
        String baseValue = resolveExpression(variable.getInitial(), player, variable);
        ValueType type = variable.getValueType();
        if (type == null) {
            type = inferTypeFromValue(baseValue);
        }
        switch (type) {
            case INT:
                int base = parseIntOrDefault(baseValue, 0);
                int set = parseIntOrDefault(setValue, 0);
                return String.valueOf(set - base);
            case DOUBLE:
                double baseD = parseDoubleOrDefault(baseValue, 0);
                double setD = parseDoubleOrDefault(setValue, 0);
                return String.valueOf(setD - baseD);
            case LIST:
                if (setValue == null || setValue.trim().isEmpty()) {
                    return "";
                }
                if (baseValue == null || baseValue.trim().isEmpty()) {
                    return setValue;
                }
                if (setValue.startsWith(baseValue)) {
                    String diff = setValue.substring(baseValue.length());
                    return diff.startsWith(",") ? diff.substring(1) : diff;
                }
                return setValue;
            default:
                if (setValue == null || setValue.trim().isEmpty()) {
                    return "";
                }
                if (baseValue == null || baseValue.trim().isEmpty()) {
                    return setValue;
                }
                if (setValue.startsWith(baseValue)) {
                    return setValue.substring(baseValue.length());
                }
                return setValue;
        }
    }

    /** 判断字符串是否符合预期类型 */
    private boolean isValidType(String value, ValueType expected) {
        if (value == null) {
            return true;
        }
        try {
            if (expected == ValueType.INT) {
                Integer.parseInt(value);
            } else if (expected == ValueType.DOUBLE) {
                Double.parseDouble(value);
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private int parseIntOrDefault(String input, int def) {
        try {
            return Integer.parseInt(input);
        } catch (Exception e) {
            return def;
        }
    }

    private double parseDoubleOrDefault(String input, double def) {
        try {
            return Double.parseDouble(input);
        } catch (Exception e) {
            return def;
        }
    }

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

    /** 解析变量定义 */
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

        // 顶层 min/max
        if (section.contains("min")) {
            lb.minValue(section.getString("min"));
        }
        if (section.contains("max")) {
            lb.maxValue(section.getString("max"));
        }

        // limitations 配置块（仅处理安全和行为限制）
        if (section.isConfigurationSection("limitations")) {
            ConfigurationSection limitSection = section.getConfigurationSection("limitations");
            if (limitSection.contains("read-only")) lb.readOnly(limitSection.getBoolean("read-only"));
            if (limitSection.contains("persistable")) lb.persistable(limitSection.getBoolean("persistable"));
            if (limitSection.contains("max-recursion-depth")) lb.maxRecursionDepth(limitSection.getInt("max-recursion-depth"));
            if (limitSection.contains("max-expression-length")) lb.maxExpressionLength(limitSection.getInt("max-expression-length"));
            if (limitSection.contains("allow-circular-references")) lb.allowCircularReferences(limitSection.getBoolean("allow-circular-references"));
        }

        builder.limitations(lb.build());
        return builder.build();
    }

    /** 注册变量 */
    private void registerVariable(Variable variable) {
        variableRegistry.put(variable.getKey(), variable);
        logger.debug("已注册变量: " + variable.getKey());
    }

    /** 验证变量定义 */
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

    // ======================== 数据预加载 ========================

    /** 预加载数据库中的变量数据 */
    private void preloadDatabaseData() {
        logger.info("开始预加载数据库数据到内存...");
        try {
            CompletableFuture<Void> sv = preloadServerVariables();
            CompletableFuture<Void> pv = preloadOnlinePlayerVariables();
            CompletableFuture.allOf(sv, pv).join();
            VariableMemoryStorage.MemoryStats stats = memoryStorage.getMemoryStats();
            logger.info("预加载完成: " + stats);
        } catch (Exception e) {
            logger.error("预加载数据库数据失败", e);
        }
    }

    /** 预加载服务器变量 */
    private CompletableFuture<Void> preloadServerVariables() {
        return CompletableFuture.runAsync(() -> {
            try {
                for (String key : getGlobalVariableKeys()) {
                    database.queryValueAsync(
                                    "SELECT value FROM server_variables WHERE variable_key = ?", key)
                            .thenAccept(val -> {
                                if (val != null) {
                                    memoryStorage.setServerVariable(key, val);
                                    memoryStorage.clearDirtyFlag("server:" + key);
                                }
                            });
                }
                logger.debug("服务器变量预加载完成，数量: " + getGlobalVariableKeys().size());
            } catch (Exception e) {
                logger.error("预加载服务器变量失败", e);
            }
        }, asyncTaskManager.getExecutor());
    }

    /** 预加载在线玩家变量 */
    private CompletableFuture<Void> preloadOnlinePlayerVariables() {
        return CompletableFuture.runAsync(() -> {
            try {
                Collection<? extends OfflinePlayer> players = Bukkit.getOnlinePlayers();
                for (OfflinePlayer p : players) {
                    preloadPlayerVariables(p);
                }
                logger.debug("在线玩家变量预加载完成，玩家数: " + players.size());
            } catch (Exception e) {
                logger.error("预加载在线玩家变量失败", e);
            }
        }, asyncTaskManager.getExecutor());
    }

    /** 预加载单个玩家变量 */
    private void preloadPlayerVariables(OfflinePlayer player) {
        for (String key : getPlayerVariableKeys()) {
            database.queryValueAsync(
                            "SELECT value FROM player_variables WHERE player_uuid = ? AND variable_key = ?",
                            player.getUniqueId().toString(), key)
                    .thenAccept(val -> {
                        if (val != null) {
                            memoryStorage.setPlayerVariable(player.getUniqueId(), key, val);
                            memoryStorage.clearDirtyFlag("player:" + player.getUniqueId() + ":" + key);
                        }
                    });
        }
    }

    // ======================== 未调用的内容（隐藏） ========================
    /*
    // 注释掉的旧版方法或示例：
    // private void oldExampleMethod() { ... }
    */
}
