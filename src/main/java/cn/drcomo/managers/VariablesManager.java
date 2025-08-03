package cn.drcomo.managers;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.model.VariableResult;
import cn.drcomo.model.structure.*;
import cn.drcomo.database.HikariConnection;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.corelib.config.YamlUtil;
import cn.drcomo.corelib.async.AsyncTaskManager;
import cn.drcomo.corelib.hook.placeholder.PlaceholderAPIUtil;
import cn.drcomo.corelib.math.FormulaCalculator;
import cn.drcomo.corelib.math.NumberUtil;
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
 * 变量管理器 - 系统核心
 * 
 * 负责所有变量的定义、加载、解析和管理。
 * 实现智能类型推断、动态表达式计算和约束验证。
 * 
 * @author BaiMo
 */
public class VariablesManager {
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final YamlUtil yamlUtil;
    private final AsyncTaskManager asyncTaskManager;
    private final PlaceholderAPIUtil placeholderUtil;
    private final HikariConnection database;
    
    // 变量定义注册表
    private final ConcurrentHashMap<String, Variable> variableRegistry = new ConcurrentHashMap<>();
    
    // 缓存管理
    private final ConcurrentHashMap<String, CachedValue> valueCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedExpression> expressionCache = new ConcurrentHashMap<>();
    
    // 安全限制
    private static final int MAX_RECURSION_DEPTH = 10;
    private static final int MAX_EXPRESSION_LENGTH = 1000;
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("%[^%]+%");
    private static final Pattern INTERNAL_VAR_PATTERN = Pattern.compile("\\$\\{[^}]+\\}");
    
    public VariablesManager(
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
    }
    
    /**
     * 初始化变量管理器
     */
    public void initialize() {
        logger.info("正在初始化变量管理系统...");
        
        try {
            // 加载所有变量定义
            loadAllVariableDefinitions();
            
            // 验证变量配置
            validateVariableDefinitions();
            
            // 初始化缓存清理任务
            startCacheCleanupTask();
            
            logger.info("变量管理系统初始化完成！已加载 " + variableRegistry.size() + " 个变量定义");
        } catch (Exception e) {
            logger.error("变量管理系统初始化失败！", e);
            throw new RuntimeException("变量管理系统初始化失败", e);
        }
    }
    
    /**
     * 重载所有变量定义
     */
    public void reload() {
        logger.info("正在重载变量定义...");
        
        // 清空缓存
        valueCache.clear();
        expressionCache.clear();
        
        // 清空注册表
        variableRegistry.clear();
        
        // 重新加载
        loadAllVariableDefinitions();
        validateVariableDefinitions();
        
        logger.info("变量定义重载完成！已加载 " + variableRegistry.size() + " 个变量定义");
    }
    
    /**
     * 加载所有变量定义
     */
    private void loadAllVariableDefinitions() {
        // 加载主变量文件
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
    
    /**
     * 从指定文件加载变量定义
     */
    private void loadVariableDefinitionsFromFile(String configName) {
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
    
    /**
     * 解析变量定义
     */
    private Variable parseVariableDefinition(String key, ConfigurationSection section) {
        Variable.Builder builder = new Variable.Builder(key);
        
        // 基本信息
        builder.name(section.getString("name"))
               .scope(section.getString("scope", "player"))
               .initial(section.getString("initial"))
               .min(section.getString("min"))
               .max(section.getString("max"))
               .cycle(section.getString("cycle"));
        
        // 解析类型
        String typeStr = section.getString("type");
        if (typeStr != null) {
            ValueType valueType = ValueType.fromString(typeStr);
            if (valueType != null) {
                builder.valueType(valueType);
            } else {
                logger.warn("未知的值类型: " + typeStr + " 在变量: " + key);
            }
        }
        
        // 解析限制条件
        if (section.contains("limitations")) {
            ConfigurationSection limitSection = section.getConfigurationSection("limitations");
            if (limitSection != null) {
                Limitations limitations = parseLimitations(limitSection);
                builder.limitations(limitations);
            }
        }
        
        return builder.build();
    }
    
    /**
     * 解析限制条件
     */
    private Limitations parseLimitations(ConfigurationSection section) {
        Limitations.Builder builder = new Limitations.Builder();
        
        // 数值约束
        if (section.contains("min-value")) {
            builder.minValue(section.getString("min-value"));
        }
        if (section.contains("max-value")) {
            builder.maxValue(section.getString("max-value"));
        }
        
        // 长度约束
        if (section.contains("min-length")) {
            builder.minLength(section.getInt("min-length"));
        }
        if (section.contains("max-length")) {
            builder.maxLength(section.getInt("max-length"));
        }
        
        // 安全限制
        if (section.contains("max-recursion-depth")) {
            builder.maxRecursionDepth(section.getInt("max-recursion-depth"));
        }
        if (section.contains("max-expression-length")) {
            builder.maxExpressionLength(section.getInt("max-expression-length"));
        }
        if (section.contains("allow-circular-references")) {
            builder.allowCircularReferences(section.getBoolean("allow-circular-references"));
        }
        
        // 行为限制
        if (section.contains("read-only")) {
            builder.readOnly(section.getBoolean("read-only"));
        }
        if (section.contains("persistable")) {
            builder.persistable(section.getBoolean("persistable"));
        }
        if (section.contains("exportable")) {
            builder.exportable(section.getBoolean("exportable"));
        }
        
        return builder.build();
    }
    
    /**
     * 注册变量定义
     */
    private void registerVariable(Variable variable) {
        if (variableRegistry.containsKey(variable.getKey())) {
            logger.warn("变量定义已存在，将被覆盖: " + variable.getKey());
        }
        
        variableRegistry.put(variable.getKey(), variable);
        logger.debug("已注册变量: " + variable.getKey() + " (" + variable.getTypeDescription() + ")");
    }
    
    /**
     * 验证所有变量定义
     */
    private void validateVariableDefinitions() {
        for (Variable variable : variableRegistry.values()) {
            List<String> errors = variable.validate();
            if (!errors.isEmpty()) {
                for (String error : errors) {
                    if (error.startsWith("警告:")) {
                        logger.warn("变量 " + variable.getKey() + ": " + error);
                    } else {
                        logger.error("变量 " + variable.getKey() + ": " + error);
                    }
                }
            }
        }
    }
    
    /**
     * 启动缓存清理任务
     */
    private void startCacheCleanupTask() {
        asyncTaskManager.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                
                // 清理过期的值缓存
                valueCache.entrySet().removeIf(entry -> {
                    CachedValue cached = entry.getValue();
                    return (now - cached.timestamp) > cached.ttl;
                });
                
                // 清理过期的表达式缓存
                expressionCache.entrySet().removeIf(entry -> {
                    CachedExpression cached = entry.getValue();
                    return (now - cached.timestamp) > cached.ttl;
                });
                
                logger.debug("缓存清理完成，当前缓存数量: 值=" + valueCache.size() + ", 表达式=" + expressionCache.size());
            } catch (Exception e) {
                logger.error("缓存清理任务异常", e);
            }
        }, 60000, 60000, java.util.concurrent.TimeUnit.MILLISECONDS); // 每分钟执行一次
    }
    
    // ======================== 公共 API 方法 ========================
    
    /**
     * 获取变量值
     */
    public CompletableFuture<VariableResult> getVariable(OfflinePlayer player, String key) {
        return asyncTaskManager.submitAsync(() -> {
            try {
                Variable variable = getVariableDefinition(key);
                if (variable == null) {
                    return VariableResult.failure("变量不存在: " + key, "GET", key, player.getName());
                }
                
                String value = getVariableValueInternal(player, variable);
                return VariableResult.success(value, "GET", key, player.getName());
                
            } catch (Exception e) {
                logger.error("获取变量失败: " + key, e);
                return VariableResult.fromException(e, "GET", key, player.getName());
            }
        });
    }
    
    /**
     * 设置变量值
     */
    public CompletableFuture<VariableResult> setVariable(OfflinePlayer player, String key, String value) {
        return asyncTaskManager.submitAsync(() -> {
            try {
                Variable variable = getVariableDefinition(key);
                if (variable == null) {
                    return VariableResult.failure("变量不存在: " + key, "SET", key, player.getName());
                }
                
                // 检查是否为只读变量
                if (variable.getLimitations() != null && variable.getLimitations().isReadOnly()) {
                    return VariableResult.failure("变量为只读模式: " + key, "SET", key, player.getName());
                }
                
                // 转换和验证值
                String processedValue = processAndValidateValue(variable, value, player);
                if (processedValue == null) {
                    return VariableResult.failure("值格式错误或超出约束: " + value, "SET", key, player.getName());
                }
                
                // 保存到数据库
                setVariableValueInternal(player, variable, processedValue);
                
                // 清理相关缓存
                invalidateCache(player, key);
                
                return VariableResult.success(processedValue, "SET", key, player.getName());
                
            } catch (Exception e) {
                logger.error("设置变量失败: " + key, e);
                return VariableResult.fromException(e, "SET", key, player.getName());
            }
        });
    }
    
    /**
     * 增加变量值（智能操作）
     */
    public CompletableFuture<VariableResult> addVariable(OfflinePlayer player, String key, String addValue) {
        return asyncTaskManager.submitAsync(() -> {
            try {
                Variable variable = getVariableDefinition(key);
                if (variable == null) {
                    return VariableResult.failure("变量不存在: " + key, "ADD", key, player.getName());
                }
                
                // 检查是否为只读变量
                if (variable.getLimitations() != null && variable.getLimitations().isReadOnly()) {
                    return VariableResult.failure("变量为只读模式: " + key, "ADD", key, player.getName());
                }
                
                // 获取当前值
                String currentValue = getVariableValueInternal(player, variable);
                
                // 根据类型执行加法操作
                String newValue = performAddOperation(variable, currentValue, addValue, player);
                if (newValue == null) {
                    return VariableResult.failure("加法操作失败或超出约束", "ADD", key, player.getName());
                }
                
                // 保存到数据库
                setVariableValueInternal(player, variable, newValue);
                
                // 清理相关缓存
                invalidateCache(player, key);
                
                return VariableResult.success(newValue, "ADD", key, player.getName());
                
            } catch (Exception e) {
                logger.error("增加变量失败: " + key, e);
                return VariableResult.fromException(e, "ADD", key, player.getName());
            }
        });
    }
    
    /**
     * 移除变量值（智能操作）
     */
    public CompletableFuture<VariableResult> removeVariable(OfflinePlayer player, String key, String removeValue) {
        return asyncTaskManager.submitAsync(() -> {
            try {
                Variable variable = getVariableDefinition(key);
                if (variable == null) {
                    return VariableResult.failure("变量不存在: " + key, "REMOVE", key, player.getName());
                }
                
                // 检查是否为只读变量
                if (variable.getLimitations() != null && variable.getLimitations().isReadOnly()) {
                    return VariableResult.failure("变量为只读模式: " + key, "REMOVE", key, player.getName());
                }
                
                // 获取当前值
                String currentValue = getVariableValueInternal(player, variable);
                
                // 根据类型执行删除操作
                String newValue = performRemoveOperation(variable, currentValue, removeValue);
                if (newValue == null) {
                    return VariableResult.failure("删除操作失败", "REMOVE", key, player.getName());
                }
                
                // 保存到数据库
                setVariableValueInternal(player, variable, newValue);
                
                // 清理相关缓存
                invalidateCache(player, key);
                
                return VariableResult.success(newValue, "REMOVE", key, player.getName());
                
            } catch (Exception e) {
                logger.error("移除变量失败: " + key, e);
                return VariableResult.fromException(e, "REMOVE", key, player.getName());
            }
        });
    }
    
    /**
     * 重置变量为初始值
     */
    public CompletableFuture<VariableResult> resetVariable(OfflinePlayer player, String key) {
        return asyncTaskManager.submitAsync(() -> {
            try {
                Variable variable = getVariableDefinition(key);
                if (variable == null) {
                    return VariableResult.failure("变量不存在: " + key, "RESET", key, player.getName());
                }
                
                // 检查是否为只读变量
                if (variable.getLimitations() != null && variable.getLimitations().isReadOnly()) {
                    return VariableResult.failure("变量为只读模式: " + key, "RESET", key, player.getName());
                }
                
                // 删除数据库中的记录（使其回退到初始值）
                deleteVariableValueInternal(player, variable);
                
                // 清理相关缓存
                invalidateCache(player, key);
                
                // 获取重置后的值
                String resetValue = getVariableValueInternal(player, variable);
                
                return VariableResult.success(resetValue, "RESET", key, player.getName());
                
            } catch (Exception e) {
                logger.error("重置变量失败: " + key, e);
                return VariableResult.fromException(e, "RESET", key, player.getName());
            }
        });
    }
    
    // ======================== 内部实现方法 ========================
    
    /**
     * 获取变量内部值
     */
    private String getVariableValueInternal(OfflinePlayer player, Variable variable) {
        try {
            String cacheKey = buildCacheKey(player, variable.getKey());
            
            // 检查缓存
            CachedValue cached = valueCache.get(cacheKey);
            if (cached != null && (System.currentTimeMillis() - cached.timestamp) < cached.ttl) {
                logger.debug("缓存命中: " + cacheKey);
                return cached.value;
            }
            
            String result;
            
            // 先从数据库获取存储的值
            String storedValue = getStoredValue(player, variable);
            
            if (storedValue != null) {
                // 如果数据库中有值，使用存储值
                result = resolveExpression(storedValue, player);
            } else {
                // 如果数据库中没有值，使用初始值
                String initialValue = variable.getInitial();
                if (initialValue != null && !initialValue.trim().isEmpty()) {
                    result = resolveExpression(initialValue, player);
                } else {
                    // 没有初始值，根据类型返回默认值
                    result = getDefaultValueByType(variable.getValueType());
                }
            }
            
            // 缓存结果
            long ttl = variable.getScope().equals("server") ? 30000 : 10000; // 服务器变量缓存30秒，玩家变量10秒
            valueCache.put(cacheKey, new CachedValue(result, ttl));
            
            return result;
            
        } catch (Exception e) {
            logger.error("获取变量内部值失败: " + variable.getKey(), e);
            return getDefaultValueByType(variable.getValueType());
        }
    }
    
    /**
     * 设置变量内部值
     */
    private void setVariableValueInternal(OfflinePlayer player, Variable variable, String value) {
        try {
            if (variable.getScope().equals("server")) {
                // 服务器变量
                database.executeUpdate(
                    "INSERT OR REPLACE INTO server_variables (variable_key, value, updated_at) VALUES (?, ?, ?)",
                    variable.getKey(), value, System.currentTimeMillis()
                );
            } else {
                // 玩家变量
                database.executeUpdate(
                    "INSERT OR REPLACE INTO player_variables (player_uuid, variable_key, value, updated_at) VALUES (?, ?, ?, ?)",
                    player.getUniqueId().toString(), variable.getKey(), value, System.currentTimeMillis()
                );
            }
            
            logger.debug("已保存变量: " + variable.getKey() + " = " + value);
            
        } catch (Exception e) {
            logger.error("保存变量失败: " + variable.getKey(), e);
            throw new RuntimeException("变量保存失败", e);
        }
    }
    
    /**
     * 删除变量内部值
     */
    private void deleteVariableValueInternal(OfflinePlayer player, Variable variable) {
        try {
            if (variable.getScope().equals("server")) {
                // 服务器变量
                database.executeUpdate(
                    "DELETE FROM server_variables WHERE variable_key = ?",
                    variable.getKey()
                );
            } else {
                // 玩家变量
                database.executeUpdate(
                    "DELETE FROM player_variables WHERE player_uuid = ? AND variable_key = ?",
                    player.getUniqueId().toString(), variable.getKey()
                );
            }
            
            logger.debug("已删除变量: " + variable.getKey());
            
        } catch (Exception e) {
            logger.error("删除变量失败: " + variable.getKey(), e);
            throw new RuntimeException("变量删除失败", e);
        }
    }
    
    /**
     * 处理和验证值
     */
    private String processAndValidateValue(Variable variable, String value, OfflinePlayer player) {
        try {
            if (value == null) {
                return null;
            }
            
            // 解析占位符
            String resolvedValue = resolveExpression(value, player);
            
            // 类型检查和转换
            if (variable.getValueType() != null) {
                if (!isValidType(resolvedValue, variable.getValueType())) {
                    logger.warn("值类型不匹配: " + resolvedValue + " 不是 " + variable.getValueType());
                    return null;
                }
            }
            
            // 约束检查
            if (variable.getLimitations() != null) {
                if (!validateConstraints(resolvedValue, variable.getLimitations(), player)) {
                    logger.warn("值超出约束: " + resolvedValue);
                    return null;
                }
            }
            
            return resolvedValue;
            
        } catch (Exception e) {
            logger.error("处理值失败: " + value, e);
            return null;
        }
    }
    
    /**
     * 执行加法操作
     */
    private String performAddOperation(Variable variable, String currentValue, String addValue, OfflinePlayer player) {
        try {
            // 解析占位符
            String resolvedAddValue = resolveExpression(addValue, player);
            
            ValueType type = variable.getValueType();
            if (type == null) {
                // 没有指定类型，智能推断
                type = inferTypeFromValue(currentValue);
            }
            
            String result;
            switch (type) {
                case INT:
                    int currentInt = NumberUtil.parseInt(currentValue, 0);
                    int addInt = NumberUtil.parseInt(resolvedAddValue, 0);
                    result = String.valueOf(currentInt + addInt);
                    break;
                    
                case DOUBLE:
                    double currentDouble = NumberUtil.parseDouble(currentValue, 0.0);
                    double addDouble = NumberUtil.parseDouble(resolvedAddValue, 0.0);
                    result = String.valueOf(currentDouble + addDouble);
                    break;
                    
                case LIST:
                    // 列表添加元素
                    if (currentValue == null || currentValue.trim().isEmpty()) {
                        result = resolvedAddValue;
                    } else {
                        result = currentValue + "," + resolvedAddValue;
                    }
                    break;
                    
                default: // STRING
                    // 字符串拼接
                    result = (currentValue == null ? "" : currentValue) + resolvedAddValue;
                    break;
            }
            
            // 验证结果
            return processAndValidateValue(variable, result, player);
            
        } catch (Exception e) {
            logger.error("加法操作失败: " + currentValue + " + " + addValue, e);
            return null;
        }
    }
    
    /**
     * 执行删除操作
     */
    private String performRemoveOperation(Variable variable, String currentValue, String removeValue) {
        try {
            if (currentValue == null || currentValue.trim().isEmpty()) {
                return currentValue;
            }
            
            ValueType type = variable.getValueType();
            if (type == null) {
                type = inferTypeFromValue(currentValue);
            }
            
            String result;
            switch (type) {
                case INT:
                    int currentInt = NumberUtil.parseInt(currentValue, 0);
                    int removeInt = NumberUtil.parseInt(removeValue, 0);
                    result = String.valueOf(currentInt - removeInt);
                    break;
                    
                case DOUBLE:
                    double currentDouble = NumberUtil.parseDouble(currentValue, 0.0);
                    double removeDouble = NumberUtil.parseDouble(removeValue, 0.0);
                    result = String.valueOf(currentDouble - removeDouble);
                    break;
                    
                case LIST:
                    // 从列表中移除元素
                    List<String> items = new ArrayList<>(Arrays.asList(currentValue.split(",")));
                    items.removeIf(item -> item.trim().equals(removeValue.trim()));
                    result = String.join(",", items);
                    break;
                    
                default: // STRING
                    // 从字符串中移除子字符串
                    result = currentValue.replace(removeValue, "");
                    break;
            }
            
            return result;
            
        } catch (Exception e) {
            logger.error("删除操作失败: " + currentValue + " - " + removeValue, e);
            return currentValue;
        }
    }
    
    /**
     * 失效缓存
     */
    private void invalidateCache(OfflinePlayer player, String key) {
        try {
            String cacheKey = buildCacheKey(player, key);
            valueCache.remove(cacheKey);
            
            // 清理相关的表达式缓存
            expressionCache.entrySet().removeIf(entry -> 
                entry.getKey().contains(cacheKey) || entry.getKey().contains(key)
            );
            
            logger.debug("已清理缓存: " + cacheKey);
            
        } catch (Exception e) {
            logger.error("清理缓存失败: " + key, e);
        }
    }
    
    // ======================== 辅助方法 ========================
    
    /**
     * 构建缓存键
     */
    private String buildCacheKey(OfflinePlayer player, String variableKey) {
        if (player == null) {
            return "server:" + variableKey;
        }
        return player.getUniqueId().toString() + ":" + variableKey;
    }
    
    /**
     * 从数据库获取存储值
     */
    private String getStoredValue(OfflinePlayer player, Variable variable) {
        try {
            if (variable.getScope().equals("server")) {
                return database.queryForString(
                    "SELECT value FROM server_variables WHERE variable_key = ?",
                    variable.getKey()
                );
            } else {
                return database.queryForString(
                    "SELECT value FROM player_variables WHERE player_uuid = ? AND variable_key = ?",
                    player.getUniqueId().toString(), variable.getKey()
                );
            }
        } catch (Exception e) {
            logger.debug("获取存储值失败: " + variable.getKey(), e);
            return null;
        }
    }
    
    /**
     * 解析表达式和占位符
     */
    private String resolveExpression(String expression, OfflinePlayer player) {
        if (expression == null || expression.trim().isEmpty()) {
            return expression;
        }
        
        try {
            String cacheKey = "expr:" + expression.hashCode() + ":" + (player != null ? player.getUniqueId() : "server");
            
            // 检查表达式缓存
            CachedExpression cached = expressionCache.get(cacheKey);
            if (cached != null && (System.currentTimeMillis() - cached.timestamp) < cached.ttl) {
                return cached.result;
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
                    result = FormulaCalculator.calculate(result);
                }
                
                // 如果没有变化，停止递归
                if (result.equals(previous)) {
                    break;
                }
                
                depth++;
            }
            
            // 缓存结果
            expressionCache.put(cacheKey, new CachedExpression(result, 5000)); // 5秒缓存
            
            return result;
            
        } catch (Exception e) {
            logger.error("解析表达式失败: " + expression, e);
            return expression; // 返回原始值
        }
    }
    
    /**
     * 解析内部变量引用
     */
    private String resolveInternalVariables(String text, OfflinePlayer player) {
        return INTERNAL_VAR_PATTERN.matcher(text).replaceAll(match -> {
            String varName = match.group().substring(2, match.group().length() - 1); // 移除 ${ 和 }
            Variable variable = getVariableDefinition(varName);
            if (variable != null) {
                return getVariableValueInternal(player, variable);
            }
            return match.group(); // 保持原样
        });
    }
    
    /**
     * 检查是否包含占位符
     */
    private boolean containsPlaceholders(String text) {
        return PLACEHOLDER_PATTERN.matcher(text).find();
    }
    
    /**
     * 检查是否包含内部变量
     */
    private boolean containsInternalVariables(String text) {
        return INTERNAL_VAR_PATTERN.matcher(text).find();
    }
    
    /**
     * 检查是否包含数学表达式
     */
    private boolean containsMathExpression(String text) {
        return text.matches(".*[+\\-*/()^].*") && text.matches(".*\\d.*");
    }
    
    /**
     * 根据类型获取默认值
     */
    private String getDefaultValueByType(ValueType type) {
        if (type == null) {
            return "0";
        }
        
        switch (type) {
            case INT:
            case DOUBLE:
                return "0";
            case LIST:
                return "";
            default: // STRING
                return "";
        }
    }
    
    /**
     * 智能推断类型
     */
    private ValueType inferTypeFromValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return ValueType.STRING;
        }
        
        // 检查是否为整数
        if (value.matches("-?\\d+")) {
            return ValueType.INT;
        }
        
        // 检查是否为浮点数
        if (value.matches("-?\\d*\\.\\d+")) {
            return ValueType.DOUBLE;
        }
        
        // 检查是否为列表（包含逗号）
        if (value.contains(",")) {
            return ValueType.LIST;
        }
        
        return ValueType.STRING;
    }
    
    /**
     * 检查类型是否有效
     */
    private boolean isValidType(String value, ValueType expectedType) {
        if (value == null) {
            return true;
        }
        
        switch (expectedType) {
            case INT:
                try {
                    Integer.parseInt(value);
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
                
            case DOUBLE:
                try {
                    Double.parseDouble(value);
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
                
            case LIST:
            case STRING:
            default:
                return true; // 字符串和列表都接受任何值
        }
    }
    
    /**
     * 验证约束条件
     */
    private boolean validateConstraints(String value, Limitations limitations, OfflinePlayer player) {
        try {
            // 数值约束
            if (limitations.getMinValue() != null || limitations.getMaxValue() != null) {
                double numValue = NumberUtil.parseDouble(value, Double.MIN_VALUE);
                if (numValue == Double.MIN_VALUE) {
                    return false; // 无法转换为数字
                }
                
                if (limitations.getMinValue() != null) {
                    String minExpr = resolveExpression(limitations.getMinValue(), player);
                    double minValue = NumberUtil.parseDouble(minExpr, Double.MIN_VALUE);
                    if (minValue != Double.MIN_VALUE && numValue < minValue) {
                        return false;
                    }
                }
                
                if (limitations.getMaxValue() != null) {
                    String maxExpr = resolveExpression(limitations.getMaxValue(), player);
                    double maxValue = NumberUtil.parseDouble(maxExpr, Double.MAX_VALUE);
                    if (maxValue != Double.MAX_VALUE && numValue > maxValue) {
                        return false;
                    }
                }
            }
            
            // 长度约束
            if (limitations.getMinLength() > 0 && value.length() < limitations.getMinLength()) {
                return false;
            }
            if (limitations.getMaxLength() > 0 && value.length() > limitations.getMaxLength()) {
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            logger.error("验证约束失败: " + value, e);
            return false;
        }
    }
    
    /**
     * 获取变量定义
     */
    public Variable getVariableDefinition(String key) {
        return variableRegistry.get(key);
    }
    
    /**
     * 获取所有变量键名
     */
    public Set<String> getAllVariableKeys() {
        return new HashSet<>(variableRegistry.keySet());
    }
    
    /**
     * 保存所有数据
     */
    public void saveAllData() {
        logger.info("正在保存所有变量数据...");
        
        try {
            // 强制清理缓存，确保数据同步
            valueCache.clear();
            expressionCache.clear();
            
            // 数据库操作会立即持久化，无需额外操作
            logger.info("所有变量数据保存完成！");
            
        } catch (Exception e) {
            logger.error("保存变量数据失败！", e);
        }
    }
    
    // 缓存相关内部类
    private static class CachedValue {
        final String value;
        final long timestamp;
        final long ttl;
        
        CachedValue(String value, long ttl) {
            this.value = value;
            this.timestamp = System.currentTimeMillis();
            this.ttl = ttl;
        }
    }
    
    private static class CachedExpression {
        final String result;
        final long timestamp;
        final long ttl;
        
        CachedExpression(String result, long ttl) {
            this.result = result;
            this.timestamp = System.currentTimeMillis();
            this.ttl = ttl;
        }
    }
}