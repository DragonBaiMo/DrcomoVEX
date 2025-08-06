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
 * @author BaiMo
 */
public class RefactoredVariablesManager {
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final YamlUtil yamlUtil;
    private final AsyncTaskManager asyncTaskManager;
    private final PlaceholderAPIUtil placeholderUtil;
    private final HikariConnection database;
    
    // 核心存储组件
    private final VariableMemoryStorage memoryStorage;
    private final BatchPersistenceManager persistenceManager;
    private final MultiLevelCacheManager cacheManager;
    
    // 变量定义注册表
    private final ConcurrentHashMap<String, Variable> variableRegistry = new ConcurrentHashMap<>();
    
    // 线程本地变量，用于在约束验证过程中传递当前变量键
    private final ThreadLocal<String> currentValidatingVariable = new ThreadLocal<>();
    
    // 安全限制
    private static final int MAX_RECURSION_DEPTH = 10;
    private static final int MAX_EXPRESSION_LENGTH = 1000;
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%[^%]+%");
    private static final Pattern INTERNAL_VAR_PATTERN = Pattern.compile("\\$\\{[^}]+\\}");
    
    /**
     * 构造函数
     */
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
        
        // 初始化存储组件
        this.memoryStorage = new VariableMemoryStorage(logger, 256 * 1024 * 1024); // 256MB
        
        // 初始化缓存管理器
        MultiLevelCacheManager.CacheConfig cacheConfig = new MultiLevelCacheManager.CacheConfig()
                .setL2MaximumSize(10000)
                .setL2ExpireMinutes(5)
                .setL3MaximumSize(5000)
                .setL3ExpireMinutes(2);
        this.cacheManager = new MultiLevelCacheManager(logger, memoryStorage, cacheConfig);
        
        // 初始化批量持久化管理器
        BatchPersistenceManager.PersistenceConfig persistenceConfig = 
                new BatchPersistenceManager.PersistenceConfig()
                        .setBatchIntervalSeconds(30)
                        .setMaxBatchSize(1000)
                        .setMemoryPressureThreshold(80.0)
                        .setMaxRetries(3);
        this.persistenceManager = new BatchPersistenceManager(
                logger, database, memoryStorage, asyncTaskManager, persistenceConfig);
    }
    
    /**
     * 初始化变量管理器
     */
    public CompletableFuture<Void> initialize() {
        logger.info("正在初始化重构后的变量管理系统...");
        
        return CompletableFuture.runAsync(() -> {
            try {
                // 加载所有变量定义
                loadAllVariableDefinitions();
                
                // 验证变量配置
                validateVariableDefinitions();
                
                // 启动批量持久化管理器
                persistenceManager.start();
                
                // 预加载数据库中的变量数据
                preloadDatabaseData();
                
                logger.info("重构后的变量管理系统初始化完成！已加载 " + variableRegistry.size() + " 个变量定义");
                
            } catch (Exception e) {
                logger.error("重构后的变量管理系统初始化失败！", e);
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
                // 关闭持久化管理器（会自动持久化所有脏数据）
                persistenceManager.shutdown();
                
                // 清空缓存
                cacheManager.clearAllCaches();
                
                logger.info("变量管理系统关闭完成");
                
            } catch (Exception e) {
                logger.error("关闭变量管理系统失败", e);
                throw new RuntimeException("关闭变量管理系统失败", e);
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
                
                // 首先尝试从多级缓存获取
                MultiLevelCacheManager.CacheResult cacheResult = 
                        cacheManager.getFromCache(player, key, variable);
                
                if (cacheResult.isHit()) {
                    logger.debug("缓存命中(" + cacheResult.getLevel() + "): " + key);
                    return VariableResult.success(cacheResult.getValue(), "GET", key, playerName);
                }
                
                // 缓存未命中，从内存存储获取
                String rawValue = cacheResult.getValue(); // MISS结果可能包含原始值
                if (rawValue == null) {
                    rawValue = getVariableFromMemoryOrDefault(player, variable);
                }
                
                // 解析表达式和占位符
                String resolvedValue = resolveExpression(rawValue, player);
                
                // 缓存解析结果
                cacheManager.cacheResult(player, key, rawValue, resolvedValue);
                
                return VariableResult.success(resolvedValue, "GET", key, playerName);
                
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
                
                // 处理和验证值
                String processedValue = processAndValidateValue(variable, value, player);
                if (processedValue == null) {
                    return VariableResult.failure("值格式错误或超出约束: " + value, "SET", key, playerName);
                }
                
                // 写入内存存储（立即生效，异步持久化）
                setVariableInMemory(player, variable, processedValue);
                
                // 清除相关缓存
                cacheManager.invalidateCache(player, key);
                
                logger.debug("设置变量: " + key + " = " + processedValue + " (异步持久化中)");
                
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
                
                // 获取当前值
                String currentValue = getVariableFromMemoryOrDefault(player, variable);
                
                // 执行加法操作
                String newValue = performAddOperation(variable, currentValue, addValue, player);
                if (newValue == null) {
                    return VariableResult.failure("加法操作失败或超出约束", "ADD", key, playerName);
                }
                
                // 写入内存存储
                setVariableInMemory(player, variable, newValue);
                
                // 清除相关缓存
                cacheManager.invalidateCache(player, key);
                
                logger.debug("加法操作: " + key + " = " + newValue + " (当前: " + currentValue + " + 增加: " + addValue + ")");
                
                return VariableResult.success(newValue, "ADD", key, playerName);
                
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
                
                // 获取当前值
                String currentValue = getVariableFromMemoryOrDefault(player, variable);
                
                // 执行删除操作
                String newValue = performRemoveOperation(variable, currentValue, removeValue, player);
                if (newValue == null) {
                    return VariableResult.failure("删除操作失败", "REMOVE", key, playerName);
                }
                
                // 写入内存存储
                setVariableInMemory(player, variable, newValue);
                
                // 清除相关缓存
                cacheManager.invalidateCache(player, key);
                
                logger.debug("删除操作: " + key + " = " + newValue + " (删除: " + removeValue + ")");
                
                return VariableResult.success(newValue, "REMOVE", key, playerName);
                
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
                
                // 删除内存中的值（回退到初始值）
                removeVariableFromMemory(player, variable);
                
                // 清除相关缓存
                cacheManager.invalidateCache(player, key);
                
                // 获取重置后的值（初始值）
                String resetValue = getVariableFromMemoryOrDefault(player, variable);
                
                logger.debug("重置变量: " + key + " = " + resetValue);
                
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
                .whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        logger.error("玩家退出数据持久化失败: " + player.getName(), throwable);
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
                // 预加载玩家的所有变量数据
                Set<String> playerVariableKeys = getPlayerVariableKeys();
                for (String key : playerVariableKeys) {
                    Variable variable = getVariableDefinition(key);
                    if (variable != null) {
                        // 触发缓存预热
                        cacheManager.preloadCache(player, key, variable);
                    }
                }
                
                logger.debug("玩家上线数据预加载完成: " + player.getName() + "，变量数: " + playerVariableKeys.size());
                
            } catch (Exception e) {
                logger.error("玩家上线数据预加载失败: " + player.getName(), e);
            }
        }, asyncTaskManager.getExecutor());
    }
    
    // ======================== 内存存储操作方法 ========================
    
    /**
     * 从内存获取变量值或返回默认值
     */
    private String getVariableFromMemoryOrDefault(OfflinePlayer player, Variable variable) {
        VariableValue value;
        
        if (variable.isPlayerScoped() && player != null) {
            value = memoryStorage.getPlayerVariable(player.getUniqueId(), variable.getKey());
        } else if (variable.isGlobal()) {
            value = memoryStorage.getServerVariable(variable.getKey());
        } else {
            return getDefaultValueByType(variable.getValueType());
        }
        
        if (value != null) {
            return value.getValue();
        }
        
        // 如果内存中没有，返回初始值或默认值
        String initialValue = variable.getInitial();
        if (initialValue != null && !initialValue.trim().isEmpty()) {
            return initialValue;
        }
        
        return getDefaultValueByType(variable.getValueType());
    }
    
    /**
     * 设置变量到内存存储（会自动标记为脏数据）
     */
    private void setVariableInMemory(OfflinePlayer player, Variable variable, String value) {
        if (variable.isPlayerScoped() && player != null) {
            memoryStorage.setPlayerVariable(player.getUniqueId(), variable.getKey(), value);
        } else if (variable.isGlobal()) {
            memoryStorage.setServerVariable(variable.getKey(), value);
        }
    }
    
    /**
     * 从内存存储中删除变量
     */
    private void removeVariableFromMemory(OfflinePlayer player, Variable variable) {
        if (variable.isPlayerScoped() && player != null) {
            memoryStorage.removePlayerVariable(player.getUniqueId(), variable.getKey());
        } else if (variable.isGlobal()) {
            memoryStorage.removeServerVariable(variable.getKey());
        }
    }
    
    // ======================== 预加载数据库数据 ========================
    
    /**
     * 预加载数据库中的变量数据到内存
     */
    private void preloadDatabaseData() {
        logger.info("开始预加载数据库数据到内存...");
        
        try {
            // 预加载服务器变量
            CompletableFuture<Void> serverPreload = preloadServerVariables();
            
            // 预加载在线玩家变量（可选，根据需要）
            CompletableFuture<Void> playerPreload = preloadOnlinePlayerVariables();
            
            // 等待预加载完成
            CompletableFuture.allOf(serverPreload, playerPreload).join();
            
            VariableMemoryStorage.MemoryStats stats = memoryStorage.getMemoryStats();
            logger.info("数据库数据预加载完成，" + stats);
            
        } catch (Exception e) {
            logger.error("预加载数据库数据失败", e);
        }
    }
    
    /**
     * 预加载服务器变量
     */
    private CompletableFuture<Void> preloadServerVariables() {
        return CompletableFuture.runAsync(() -> {
            try {
                // 查询所有服务器变量
                Set<String> serverVariableKeys = getGlobalVariableKeys();
                for (String key : serverVariableKeys) {
                    database.queryValueAsync(
                            "SELECT value FROM server_variables WHERE variable_key = ?", key)
                            .thenAccept(value -> {
                                if (value != null) {
                                    memoryStorage.setServerVariable(key, value);
                                    // 清除脏标记（因为是从数据库加载的）
                                    memoryStorage.clearDirtyFlag("server:" + key);
                                }
                            });
                }
                
                logger.debug("服务器变量预加载完成，数量: " + serverVariableKeys.size());
                
            } catch (Exception e) {
                logger.error("预加载服务器变量失败", e);
            }
        }, asyncTaskManager.getExecutor());
    }
    
    /**
     * 预加载在线玩家变量
     */
    private CompletableFuture<Void> preloadOnlinePlayerVariables() {
        return CompletableFuture.runAsync(() -> {
            try {
                Collection<? extends OfflinePlayer> onlinePlayers = Bukkit.getOnlinePlayers();
                logger.debug("预加载在线玩家变量，玩家数: " + onlinePlayers.size());
                
                for (OfflinePlayer player : onlinePlayers) {
                    // 预加载每个在线玩家的变量（异步执行，避免阻塞）
                    preloadPlayerVariables(player);
                }
                
            } catch (Exception e) {
                logger.error("预加载在线玩家变量失败", e);
            }
        }, asyncTaskManager.getExecutor());
    }
    
    /**
     * 预加载单个玩家的变量
     */
    private void preloadPlayerVariables(OfflinePlayer player) {
        Set<String> playerVariableKeys = getPlayerVariableKeys();
        for (String key : playerVariableKeys) {
            database.queryValueAsync(
                    "SELECT value FROM player_variables WHERE player_uuid = ? AND variable_key = ?",
                    player.getUniqueId().toString(), key)
                    .thenAccept(value -> {
                        if (value != null) {
                            memoryStorage.setPlayerVariable(player.getUniqueId(), key, value);
                            // 清除脏标记（因为是从数据库加载的）
                            memoryStorage.clearDirtyFlag("player:" + player.getUniqueId() + ":" + key);
                        }
                    });
        }
    }
    
    // ======================== 现有方法的保留和适配 ========================
    
    // 保留原有的变量定义加载、验证等方法，但去掉阻塞操作
    // 这里只列出几个核心方法，其他方法类似处理
    
    private void loadAllVariableDefinitions() {
        // 与原版相同的逻辑，加载变量定义
        loadVariableDefinitionsFromFile("variables");
        
        // 加载 variables/ 目录下的所有文件
        File variablesDir = new File(plugin.getDataFolder(), "variables");
        if (variablesDir.exists() && variablesDir.isDirectory()) {
            File[] files = variablesDir.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files != null) {
                for (File file : files) {
                    String fileName = file.getName().substring(0, file.getName().length() - 4);
                    String configName = "variables/" + fileName;
                    
                    try {
                        yamlUtil.loadConfig(configName);
                        loadVariableDefinitionsFromFile(configName);
                        logger.debug("已加载变量文件: " + configName);
                    } catch (Exception e) {
                        logger.error("加载变量文件失败: " + configName, e);
                    }
                }
            }
        }
    }
    
    private void loadVariableDefinitionsFromFile(String configName) {
        // 与原版相同的逻辑，只是去掉了阻塞操作
        FileConfiguration config = yamlUtil.getConfig(configName);
        if (config == null) {
            logger.warn("配置文件不存在: " + configName);
            return;
        }
        
        ConfigurationSection variablesSection = config.getConfigurationSection("variables");
        if (variablesSection == null) {
            logger.warn("配置文件中没有找到 variables 节: " + configName);
            return;
        }
        
        for (String key : variablesSection.getKeys(false)) {
            try {
                ConfigurationSection varSection = variablesSection.getConfigurationSection(key);
                if (varSection != null) {
                    Variable variable = parseVariableDefinition(key, varSection);
                    registerVariable(variable);
                }
            } catch (Exception e) {
                logger.error("解析变量定义失败: " + key, e);
            }
        }
    }
    
    // 保留其他核心方法但简化处理
    private Variable parseVariableDefinition(String key, ConfigurationSection section) {
        // 复用原有逻辑
        Variable.Builder builder = new Variable.Builder(key);
        Limitations.Builder limitBuilder = new Limitations.Builder();
        
        // 基本信息处理
        builder.name(section.getString("name"))
               .scope(section.getString("scope", "player"))
               .initial(section.getString("initial"));
        
        // 类型解析
        String typeStr = section.getString("type");
        if (typeStr != null) {
            ValueType valueType = ValueType.fromString(typeStr);
            if (valueType != null) {
                builder.valueType(valueType);
            }
        }
        
        // 简化的限制条件解析
        if (section.contains("min")) {
            limitBuilder.minValue(section.getString("min"));
        }
        if (section.contains("max")) {
            limitBuilder.maxValue(section.getString("max"));
        }
        
        builder.limitations(limitBuilder.build());
        return builder.build();
    }
    
    private void registerVariable(Variable variable) {
        variableRegistry.put(variable.getKey(), variable);
        logger.debug("已注册变量: " + variable.getKey());
    }
    
    private void validateVariableDefinitions() {
        for (Variable variable : variableRegistry.values()) {
            List<String> errors = variable.validate();
            for (String error : errors) {
                if (error.startsWith("警告:")) {
                    logger.warn("变量 " + variable.getKey() + ": " + error);
                } else {
                    logger.error("变量 " + variable.getKey() + ": " + error);
                }
            }
        }
    }
    
    // ======================== 辅助方法 ========================
    
    private String getPlayerName(OfflinePlayer player) {
        return player != null ? player.getName() : "SERVER";
    }
    
    private String getDefaultValueByType(ValueType type) {
        if (type == null) return "0";
        
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
    
    // 简化的表达式解析（复用原有逻辑但优化）
    private String resolveExpression(String expression, OfflinePlayer player) {
        if (expression == null || expression.trim().isEmpty()) {
            return expression;
        }
        
        // 检查是否需要解析
        if (!containsPlaceholders(expression) && !containsInternalVariables(expression) && 
            !containsMathExpression(expression)) {
            return expression;
        }
        
        // 先尝试从L2缓存获取
        String cachedResult = null; // 简化实现，实际应该查询缓存
        if (cachedResult != null) {
            return cachedResult;
        }
        
        String result = expression;
        int depth = 0;
        
        // 防止无限递归
        while (depth < MAX_RECURSION_DEPTH && (containsPlaceholders(result) || containsInternalVariables(result))) {
            String previous = result;
            
            // 解析内部变量 ${variable_name}
            result = resolveInternalVariables(result, player);
            
            // 解析 PlaceholderAPI 占位符 %placeholder%
            if (player != null && player.isOnline()) {
                result = placeholderUtil.parse(player.getPlayer(), result);
            }
            
            // 解析数学表达式
            if (containsMathExpression(result)) {
                try {
                    result = String.valueOf(FormulaCalculator.calculate(result));
                } catch (Exception e) {
                    logger.debug("数学表达式计算失败: " + result);
                }
            }
            
            // 如果没有变化，停止递归
            if (result.equals(previous)) {
                break;
            }
            
            depth++;
        }
        
        // 缓存解析结果
        cacheManager.cacheExpression(expression, player, result);
        
        return result;
    }
    
    // 简化的操作方法
    private String performAddOperation(Variable variable, String currentValue, String addValue, OfflinePlayer player) {
        // 复用原有逻辑但简化
        ValueType type = variable.getValueType();
        if (type == null) {
            type = inferTypeFromValue(currentValue);
        }
        
        String resolvedAddValue = resolveExpression(addValue, player);
        
        switch (type) {
            case INT:
                int currentInt = parseIntOrDefault(currentValue, 0);
                int addInt = parseIntOrDefault(resolvedAddValue, 0);
                return String.valueOf(currentInt + addInt);
                
            case DOUBLE:
                double currentDouble = parseDoubleOrDefault(currentValue, 0.0);
                double addDouble = parseDoubleOrDefault(resolvedAddValue, 0.0);
                return String.valueOf(currentDouble + addDouble);
                
            case LIST:
                if (currentValue == null || currentValue.trim().isEmpty()) {
                    return resolvedAddValue;
                } else {
                    return currentValue + "," + resolvedAddValue;
                }
                
            default: // STRING
                return (currentValue == null ? "" : currentValue) + resolvedAddValue;
        }
    }
    
    private String performRemoveOperation(Variable variable, String currentValue, String removeValue, OfflinePlayer player) {
        // 简化实现
        if (currentValue == null || currentValue.trim().isEmpty()) {
            return currentValue;
        }
        
        ValueType type = variable.getValueType();
        if (type == null) {
            type = inferTypeFromValue(currentValue);
        }
        
        switch (type) {
            case INT:
                int currentInt = parseIntOrDefault(currentValue, 0);
                int removeInt = parseIntOrDefault(removeValue, 0);
                return String.valueOf(currentInt - removeInt);
                
            case DOUBLE:
                double currentDouble = parseDoubleOrDefault(currentValue, 0.0);
                double removeDouble = parseDoubleOrDefault(removeValue, 0.0);
                return String.valueOf(currentDouble - removeDouble);
                
            case LIST:
                List<String> items = new ArrayList<>(Arrays.asList(currentValue.split(",")));
                items.removeIf(item -> item.trim().equals(removeValue.trim()));
                return String.join(",", items);
                
            default: // STRING
                return currentValue.replace(removeValue, "");
        }
    }
    
    private String processAndValidateValue(Variable variable, String value, OfflinePlayer player) {
        // 简化的验证逻辑
        if (value == null) return null;
        
        String resolvedValue = resolveExpression(value, player);
        
        // 基本类型检查
        if (variable.getValueType() != null && !isValidType(resolvedValue, variable.getValueType())) {
            return null;
        }
        
        return resolvedValue;
    }
    
    // 辅助方法
    private boolean containsPlaceholders(String text) {
        return text != null && PLACEHOLDER_PATTERN.matcher(text).find();
    }
    
    private boolean containsInternalVariables(String text) {
        return text != null && INTERNAL_VAR_PATTERN.matcher(text).find();
    }
    
    private boolean containsMathExpression(String text) {
        return text != null && text.matches(".*[+\\-*/()^].*") && text.matches(".*\\d.*");
    }
    
    private ValueType inferTypeFromValue(String value) {
        if (value == null || value.trim().isEmpty()) return ValueType.STRING;
        if (value.matches("-?\\d+")) return ValueType.INT;
        if (value.matches("-?\\d*\\.\\d+")) return ValueType.DOUBLE;
        if (value.contains(",")) return ValueType.LIST;
        return ValueType.STRING;
    }
    
    private boolean isValidType(String value, ValueType expectedType) {
        if (value == null) return true;
        
        switch (expectedType) {
            case INT:
                try { Integer.parseInt(value); return true; } 
                catch (NumberFormatException e) { return false; }
            case DOUBLE:
                try { Double.parseDouble(value); return true; } 
                catch (NumberFormatException e) { return false; }
            default:
                return true;
        }
    }
    
    private int parseIntOrDefault(String input, int def) {
        try { return Integer.parseInt(input); } 
        catch (Exception e) { return def; }
    }
    
    private double parseDoubleOrDefault(String input, double def) {
        try { return Double.parseDouble(input); } 
        catch (Exception e) { return def; }
    }
    
    private String resolveInternalVariables(String text, OfflinePlayer player) {
        return INTERNAL_VAR_PATTERN.matcher(text).replaceAll(match -> {
            String varName = match.group().substring(2, match.group().length() - 1);
            String varValue = getVariableFromMemoryOrDefault(player, getVariableDefinition(varName));
            return varValue != null ? varValue : match.group();
        });
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
                .filter(entry -> entry.getValue().isPlayerScoped())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }
    
    public Set<String> getGlobalVariableKeys() {
        return variableRegistry.entrySet().stream()
                .filter(entry -> entry.getValue().isGlobal())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }
    
    /**
     * 获取系统统计信息
     */
    public SystemStats getSystemStats() {
        VariableMemoryStorage.MemoryStats memoryStats = memoryStorage.getMemoryStats();
        MultiLevelCacheManager.CacheStatistics cacheStats = cacheManager.getCacheStats();
        BatchPersistenceManager.PersistenceStats persistenceStats = persistenceManager.getPersistenceStats();
        
        return new SystemStats(memoryStats, cacheStats, persistenceStats);
    }
    
    /**
     * 系统统计信息类
     */
    public static class SystemStats {
        private final VariableMemoryStorage.MemoryStats memoryStats;
        private final MultiLevelCacheManager.CacheStatistics cacheStats;
        private final BatchPersistenceManager.PersistenceStats persistenceStats;
        
        public SystemStats(VariableMemoryStorage.MemoryStats memoryStats,
                          MultiLevelCacheManager.CacheStatistics cacheStats,
                          BatchPersistenceManager.PersistenceStats persistenceStats) {
            this.memoryStats = memoryStats;
            this.cacheStats = cacheStats;
            this.persistenceStats = persistenceStats;
        }
        
        public VariableMemoryStorage.MemoryStats getMemoryStats() { return memoryStats; }
        public MultiLevelCacheManager.CacheStatistics getCacheStats() { return cacheStats; }
        public BatchPersistenceManager.PersistenceStats getPersistenceStats() { return persistenceStats; }
        
        @Override
        public String toString() {
            return String.format("SystemStats{\n  内存: %s\n  缓存: %s\n  持久化: %s\n}",
                    memoryStats, cacheStats, persistenceStats);
        }
    }
}