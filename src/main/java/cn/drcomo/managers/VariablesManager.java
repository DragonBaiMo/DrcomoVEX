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
    
    // 线程本地变量，用于在约束验证过程中传递当前变量键
    private final ThreadLocal<String> currentValidatingVariable = new ThreadLocal<>();
    
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
        // 提前创建 Limitations.Builder，便于在后续逻辑中随时引用
        Limitations.Builder limitBuilder = new Limitations.Builder();
        
        // 基本信息 - 处理数字和字符串类型的约束值
        String minValue = null;
        String maxValue = null;
        
        if (section.contains("min")) {
            Object minObj = section.get("min");
            if (minObj != null) {
                minValue = minObj.toString();
            }
        }
        
        if (section.contains("max")) {
            Object maxObj = section.get("max");
            if (maxObj != null) {
                maxValue = maxObj.toString();
            }
        }
        
        builder.name(section.getString("name"))
               .scope(section.getString("scope", "player"))
               .initial(section.getString("initial"))
               .min(minValue)
               .max(maxValue)
               .cycle(section.getString("cycle"));
        
        // 解析类型
        String typeStr = section.getString("type");
        if (typeStr != null) {
            ValueType valueType = ValueType.fromString(typeStr);
            if (valueType != null) {
                builder.valueType(valueType);
                // 当值类型为 LIST 且用户在根级别使用了 min/max 时，
                // 默认将其视为对列表长度的约束（兼容历史配置）
                if (valueType == ValueType.LIST) {
                    if (minValue != null && !minValue.trim().isEmpty()) {
                        try {
                            limitBuilder.minLength(Integer.parseInt(minValue.trim()));
                        } catch (NumberFormatException ignore) {
                            logger.debug("min 参数无法转换为整数，跳过列表最小长度约束: " + minValue);
                        }
                    }
                    if (maxValue != null && !maxValue.trim().isEmpty()) {
                        try {
                            limitBuilder.maxLength(Integer.parseInt(maxValue.trim()));
                        } catch (NumberFormatException ignore) {
                            logger.debug("max 参数无法转换为整数，跳过列表最大长度约束: " + maxValue);
                        }
                    }
                }
            } else {
                logger.warn("未知的值类型: " + typeStr + " 在变量: " + key);
            }
        }
        
        // Limitations.Builder 已在方法开头初始化
        
        // 设置根级别的 min/max 约束
        if (minValue != null && !minValue.trim().isEmpty()) {
            limitBuilder.minValue(minValue);
        }
        if (maxValue != null && !maxValue.trim().isEmpty()) {
            limitBuilder.maxValue(maxValue);
        }
        
        // 解析 limitations 节中的附加限制条件
        if (section.contains("limitations")) {
            ConfigurationSection limitSection = section.getConfigurationSection("limitations");
            if (limitSection != null) {
                // 如果 limitations 节中有 min-value/max-value，优先使用这些值
                if (limitSection.contains("min-value")) {
                    limitBuilder.minValue(limitSection.getString("min-value"));
                }
                if (limitSection.contains("max-value")) {
                    limitBuilder.maxValue(limitSection.getString("max-value"));
                }
                
                // 解析其他限制条件
                parseLimitationsFromSection(limitSection, limitBuilder);
            }
        }
        
        builder.limitations(limitBuilder.build());
        
        return builder.build();
    }
    
    /**
     * 解析限制条件
     */
    /**
     * 从配置节解析限制条件（不包括 min-value/max-value，这些由主方法处理）
     */
    private void parseLimitationsFromSection(ConfigurationSection section, Limitations.Builder builder) {
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
        
        // 性能限制
        if (section.contains("max-cache-time")) {
            builder.maxCacheTime(section.getLong("max-cache-time"));
        }
        if (section.contains("max-cache-size")) {
            builder.maxCacheSize(section.getInt("max-cache-size"));
        }
    }
    
    /**
     * 注册变量定义
     */
    private void registerVariable(Variable variable) {
        if (variableRegistry.containsKey(variable.getKey())) {
            logger.warn("变量定义已存在，将被覆盖: " + variable.getKey());
        }
        
        variableRegistry.put(variable.getKey(), variable);
        logger.info("已注册变量: " + variable.getKey() + " (" + variable.getTypeDescription() + ")");
        if (variable.getLimitations() != null) {
            logger.info("  - 约束信息: " + variable.getLimitations());
            logger.info("  - 最小值: " + variable.getLimitations().getMinValue());
            logger.info("  - 最大值: " + variable.getLimitations().getMaxValue());
        } else {
            logger.info("  - 无约束");
        }
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
        CompletableFuture<VariableResult> future = new CompletableFuture<>();
        asyncTaskManager.submitAsync(() -> {
            try {
                Variable variable = getVariableDefinition(key);
                if (variable == null) {
                    future.complete(VariableResult.failure("变量不存在: " + key, "GET", key, player.getName()));
                    return;
                }

                String value = getVariableValueInternal(player, variable);
                future.complete(VariableResult.success(value, "GET", key, player.getName()));
            } catch (Exception e) {
                logger.error("获取变量失败: " + key, e);
                future.complete(VariableResult.fromException(e, "GET", key, player.getName()));
            }
        });
        return future;
    }
    
    /**
     * 设置变量值
     */
    public CompletableFuture<VariableResult> setVariable(OfflinePlayer player, String key, String value) {
        CompletableFuture<VariableResult> future = new CompletableFuture<>();
        asyncTaskManager.submitAsync(() -> {
            try {
                Variable variable = getVariableDefinition(key);
                if (variable == null) {
                    future.complete(VariableResult.failure("变量不存在: " + key, "SET", key, player.getName()));
                    return;
                }

                if (variable.getLimitations() != null && variable.getLimitations().isReadOnly()) {
                    future.complete(VariableResult.failure("变量为只读模式: " + key, "SET", key, player.getName()));
                    return;
                }

                // 调试约束信息
                if (variable.getLimitations() != null) {
                    logger.debug("SET操作约束详情: 变量=" + key + ", 约束=" + variable.getLimitations());
                    logger.debug("SET操作约束范围: min=" + variable.getLimitations().getMinValue() + 
                               ", max=" + variable.getLimitations().getMaxValue());
                } else {
                    logger.debug("SET操作: 变量=" + key + " 无约束");
                }
                
                String processedValue = processAndValidateValue(variable, value, player);
                if (processedValue == null) {
                    String errorMsg = "值格式错误或超出约束: " + value;
                    if (variable.getLimitations() != null) {
                        String minValue = variable.getLimitations().getMinValue();
                        String maxValue = variable.getLimitations().getMaxValue();
                        if (minValue != null || maxValue != null) {
                            errorMsg += " (约束范围: " + 
                                       (minValue != null ? minValue : "无下限") + " ~ " + 
                                       (maxValue != null ? maxValue : "无上限") + ")";
                        }
                    }
                    logger.warn("SET操作验证失败: " + errorMsg + ", 变量: " + key + ", 类型: " + variable.getValueType());
                    future.complete(VariableResult.failure(errorMsg, "SET", key, player.getName()));
                    return;
                }
                
                logger.debug("设置变量: " + key + " = " + processedValue);

                setVariableValueInternal(player, variable, processedValue);
                invalidateCache(player, key);
                
                // 立即更新缓存
                String cacheKey = buildCacheKey(player, key);
                long ttl = variable.getScope().equals("server") ? 30000 : 10000;
                valueCache.put(cacheKey, new CachedValue(processedValue, ttl));

                future.complete(VariableResult.success(processedValue, "SET", key, player.getName()));
            } catch (Exception e) {
                logger.error("设置变量失败: " + key, e);
                future.complete(VariableResult.fromException(e, "SET", key, player.getName()));
            }
        });
        return future;
    }
    
    /**
     * 增加变量值（智能操作）
     */
    public CompletableFuture<VariableResult> addVariable(OfflinePlayer player, String key, String addValue) {
        CompletableFuture<VariableResult> future = new CompletableFuture<>();
        asyncTaskManager.submitAsync(() -> {
            try {
                Variable variable = getVariableDefinition(key);
                if (variable == null) {
                    future.complete(VariableResult.failure("变量不存在: " + key, "ADD", key, player.getName()));
                    return;
                }

                if (variable.getLimitations() != null && variable.getLimitations().isReadOnly()) {
                    future.complete(VariableResult.failure("变量为只读模式: " + key, "ADD", key, player.getName()));
                    return;
                }

                // 先清理缓存，确保获取最新值
                invalidateCache(player, key);
                
                String currentValue = getVariableValueInternalNoCache(player, variable);
                logger.debug("获取当前值: " + currentValue + " 变量: " + key);
                
                String newValue = performAddOperation(variable, currentValue, addValue, player);
                if (newValue == null) {
                    future.complete(VariableResult.failure("加法操作失败或超出约束", "ADD", key, player.getName()));
                    return;
                }
                
                logger.debug("计算新值: " + newValue + " (当前: " + currentValue + " + 增加: " + addValue + ")");

                setVariableValueInternal(player, variable, newValue);
                invalidateCache(player, key);
                
                // 立即更新缓存为新值
                String cacheKey = buildCacheKey(player, key);
                long ttl = variable.getScope().equals("server") ? 30000 : 10000;
                valueCache.put(cacheKey, new CachedValue(newValue, ttl));
                
                logger.debug("保存完成，新值: " + newValue + " 变量: " + key);

                future.complete(VariableResult.success(newValue, "ADD", key, player.getName()));
            } catch (Exception e) {
                logger.error("增加变量失败: " + key, e);
                future.complete(VariableResult.fromException(e, "ADD", key, player.getName()));
            }
        });
        return future;
    }
    
    /**
     * 移除变量值（智能操作）
     */
    public CompletableFuture<VariableResult> removeVariable(OfflinePlayer player, String key, String removeValue) {
        CompletableFuture<VariableResult> future = new CompletableFuture<>();
        asyncTaskManager.submitAsync(() -> {
            try {
                Variable variable = getVariableDefinition(key);
                if (variable == null) {
                    future.complete(VariableResult.failure("变量不存在: " + key, "REMOVE", key, player.getName()));
                    return;
                }

                if (variable.getLimitations() != null && variable.getLimitations().isReadOnly()) {
                    future.complete(VariableResult.failure("变量为只读模式: " + key, "REMOVE", key, player.getName()));
                    return;
                }

                // 先清理缓存，确保获取最新值
                invalidateCache(player, key);
                
                String currentValue = getVariableValueInternalNoCache(player, variable);
                logger.debug("移除操作 - 当前值: " + currentValue + " 变量: " + key);
                
                String newValue = performRemoveOperation(variable, currentValue, removeValue, player);
                if (newValue == null) {
                    future.complete(VariableResult.failure("删除操作失败", "REMOVE", key, player.getName()));
                    return;
                }
                
                logger.debug("移除操作 - 新值: " + newValue + " 变量: " + key);

                setVariableValueInternal(player, variable, newValue);
                invalidateCache(player, key);
                
                // 立即更新缓存
                String cacheKey = buildCacheKey(player, key);
                long ttl = variable.getScope().equals("server") ? 30000 : 10000;
                valueCache.put(cacheKey, new CachedValue(newValue, ttl));

                future.complete(VariableResult.success(newValue, "REMOVE", key, player.getName()));
            } catch (Exception e) {
                logger.error("移除变量失败: " + key, e);
                future.complete(VariableResult.fromException(e, "REMOVE", key, player.getName()));
            }
        });
        return future;
    }
    
    /**
     * 重置变量为初始值
     */
    public CompletableFuture<VariableResult> resetVariable(OfflinePlayer player, String key) {
        CompletableFuture<VariableResult> future = new CompletableFuture<>();
        asyncTaskManager.submitAsync(() -> {
            try {
                Variable variable = getVariableDefinition(key);
                if (variable == null) {
                    future.complete(VariableResult.failure("变量不存在: " + key, "RESET", key, player.getName()));
                    return;
                }

                if (variable.getLimitations() != null && variable.getLimitations().isReadOnly()) {
                    future.complete(VariableResult.failure("变量为只读模式: " + key, "RESET", key, player.getName()));
                    return;
                }

                logger.debug("重置变量: " + key);
                
                deleteVariableValueInternal(player, variable);
                invalidateCache(player, key);

                String resetValue = getVariableValueInternalNoCache(player, variable);
                logger.debug("重置后的值: " + resetValue + " 变量: " + key);
                
                // 更新缓存
                String cacheKey = buildCacheKey(player, key);
                long ttl = variable.getScope().equals("server") ? 30000 : 10000;
                valueCache.put(cacheKey, new CachedValue(resetValue, ttl));
                future.complete(VariableResult.success(resetValue, "RESET", key, player.getName()));
            } catch (Exception e) {
                logger.error("重置变量失败: " + key, e);
                future.complete(VariableResult.fromException(e, "RESET", key, player.getName()));
            }
        });
        return future;
    }
    
    // ======================== 内部实现方法 ========================
    
    /**
     * 获取变量内部值（无缓存）
     */
    private String getVariableValueInternalNoCache(OfflinePlayer player, Variable variable) {
        try {
            String result;
            
            // 直接从数据库获取存储的值
            String storedValue = getStoredValue(player, variable);
            
            if (storedValue != null) {
                // 如果数据库中有值，使用存储值
                result = resolveExpression(storedValue, player);
                logger.debug("从数据库获取值: " + result + " 变量: " + variable.getKey());
            } else {
                // 如果数据库中没有值，使用初始值
                String initialValue = variable.getInitial();
                if (initialValue != null && !initialValue.trim().isEmpty()) {
                    result = resolveExpression(initialValue, player);
                } else {
                    // 没有初始值，根据类型返回默认值
                    result = getDefaultValueByType(variable.getValueType());
                }
                logger.debug("使用初始值: " + result + " 变量: " + variable.getKey());
            }
            
            return result;
            
        } catch (Exception e) {
            logger.error("获取变量内部值失败: " + variable.getKey(), e);
            return getDefaultValueByType(variable.getValueType());
        }
    }
    
    /**
     * 获取变量内部值（带缓存）
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
                database.executeUpdateAsync(
                    "INSERT OR REPLACE INTO server_variables (variable_key, value, created_at, updated_at) VALUES (?, ?, ?, ?)",
                    variable.getKey(), value, System.currentTimeMillis(), System.currentTimeMillis()
                ).join();
            } else {
                // 玩家变量
                database.executeUpdateAsync(
                    "INSERT OR REPLACE INTO player_variables (player_uuid, variable_key, value, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                    player.getUniqueId().toString(), variable.getKey(), value, System.currentTimeMillis(), System.currentTimeMillis()
                ).join();
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
                database.executeUpdateAsync(
                    "DELETE FROM server_variables WHERE variable_key = ?",
                    variable.getKey()
                ).join();
            } else {
                // 玩家变量
                database.executeUpdateAsync(
                    "DELETE FROM player_variables WHERE player_uuid = ? AND variable_key = ?",
                    player.getUniqueId().toString(), variable.getKey()
                ).join();
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
            String resolvedValue;
            try {
                resolvedValue = resolveExpression(value, player);
                if (resolvedValue == null || resolvedValue.trim().isEmpty()) {
                    logger.debug("表达式解析结果为空，使用原始值: " + value);
                    resolvedValue = value;
                }
            } catch (Exception e) {
                logger.debug("表达式解析失败，使用原始值: " + value + ", 错误: " + e.getMessage());
                resolvedValue = value;
            }
            
            // 类型检查和转换
            if (variable.getValueType() != null) {
                if (!isValidType(resolvedValue, variable.getValueType())) {
                    logger.warn("值类型不匹配: 值=" + resolvedValue + ", 期望类型=" + variable.getValueType() + ", 变量=" + variable.getKey());
                    return null;
                }
            }
            
            // 约束检查
            if (variable.getLimitations() != null) {
                String minValue = variable.getLimitations().getMinValue();
                String maxValue = variable.getLimitations().getMaxValue();
                logger.debug("检查约束: 值=" + resolvedValue + ", 最小值=" + minValue + ", 最大值=" + maxValue);

                if (!validateConstraints(resolvedValue, variable.getLimitations(), player)) {
                    logger.warn("值超出约束: 值=" + resolvedValue + ", 变量=" + variable.getKey() +
                               ", 最小值=" + minValue + ", 最大值=" + maxValue);

                    // 尝试自动钳制到边界
                    String clamped = clampValueToLimitations(variable, resolvedValue, player);
                    if (validateConstraints(clamped, variable.getLimitations(), player)) {
                        logger.debug("钳制后值合法: " + clamped);
                        return clamped;
                    }

                    logger.warn("钳制后仍不符合约束: 值=" + clamped + ", 变量=" + variable.getKey());
                    return null;
                } else {
                    logger.debug("约束检查通过: 值=" + resolvedValue);
                }
            } else {
                logger.debug("无约束条件，跳过验证: 值=" + resolvedValue);
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
            String resolvedAddValue;
            try {
                resolvedAddValue = resolveExpression(addValue, player);
                if (resolvedAddValue == null || resolvedAddValue.trim().isEmpty()) {
                    logger.debug("加法值表达式解析结果为空，使用原始值: " + addValue);
                    resolvedAddValue = addValue;
                }
            } catch (Exception e) {
                logger.debug("加法值表达式解析失败，使用原始值: " + addValue + ", 错误: " + e.getMessage());
                resolvedAddValue = addValue;
            }
            
            ValueType type = variable.getValueType();
            if (type == null) {
                // 没有指定类型，智能推断
                type = inferTypeFromValue(currentValue);
            }
            
            String result;
            switch (type) {
                case INT:
                    int currentInt = parseIntOrDefault(currentValue, 0);
                    int addInt = parseIntOrDefault(resolvedAddValue, 0);
                    result = String.valueOf(currentInt + addInt);
                    logger.debug("INT 加法操作: " + currentInt + " + " + addInt + " = " + result);
                    break;
                    
                case DOUBLE:
                    double currentDouble = parseDoubleOrDefault(currentValue, 0.0);
                    double addDouble = parseDoubleOrDefault(resolvedAddValue, 0.0);
                    result = String.valueOf(currentDouble + addDouble);
                    logger.debug("DOUBLE 加法操作: " + currentDouble + " + " + addDouble + " = " + result);
                    break;
                    
                case LIST:
                    // 列表添加元素
                    if (currentValue == null || currentValue.trim().isEmpty()) {
                        result = resolvedAddValue;
                    } else {
                        result = currentValue + "," + resolvedAddValue;
                    }
                    logger.debug("LIST 加法操作: " + currentValue + " + " + resolvedAddValue + " = " + result);
                    break;
                    
                default: // STRING
                    // 字符串拼接
                    result = (currentValue == null ? "" : currentValue) + resolvedAddValue;
                    logger.debug("STRING 加法操作: " + currentValue + " + " + resolvedAddValue + " = " + result);
                    break;
            }
            
            // 验证并自动钳制结果
            String validatedResult = processAndValidateValue(variable, result, player);
            if (validatedResult != null) {
                logger.debug("加法结果验证通过: " + validatedResult);
                return validatedResult;
            } else {
                logger.warn("加法结果验证失败: " + result + " 变量: " + variable.getKey());
                return null;
            }
            
        } catch (Exception e) {
            logger.error("加法操作失败: " + currentValue + " + " + addValue, e);
            return null;
        }
    }
    
    /**
     * 执行删除操作
     */
    private String performRemoveOperation(Variable variable, String currentValue, String removeValue, OfflinePlayer player) {
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
                    int currentInt = parseIntOrDefault(currentValue, 0);
                    int removeInt = parseIntOrDefault(removeValue, 0);
                    result = String.valueOf(currentInt - removeInt);
                    break;

                case DOUBLE:
                    double currentDouble = parseDoubleOrDefault(currentValue, 0.0);
                    double removeDouble = parseDoubleOrDefault(removeValue, 0.0);
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

            // 验证并自动钳制结果
            String validatedResult = processAndValidateValue(variable, result, player);
            if (validatedResult != null) {
                logger.debug("删除结果验证通过: " + validatedResult);
                return validatedResult;
            } else {
                logger.warn("删除结果验证失败: " + result + " 变量: " + variable.getKey());
                return null;
            }

        } catch (Exception e) {
            logger.error("删除操作失败: " + currentValue + " - " + removeValue, e);
            return null;
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
     * 安全解析整数
     */
    private int parseIntOrDefault(String input, int def) {
        try {
            return Integer.parseInt(input);
        } catch (Exception e) {
            return def;
        }
    }

    /**
     * 安全解析小数
     */
    private double parseDoubleOrDefault(String input, double def) {
        try {
            return Double.parseDouble(input);
        } catch (Exception e) {
            return def;
        }
    }
    
    /**
     * 从数据库获取存储值
     */
    private String getStoredValue(OfflinePlayer player, Variable variable) {
        try {
            if (variable.getScope().equals("server")) {
                return database.queryValueAsync(
                    "SELECT value FROM server_variables WHERE variable_key = ?",
                    variable.getKey()
                ).join();
            } else {
                return database.queryValueAsync(
                    "SELECT value FROM player_variables WHERE player_uuid = ? AND variable_key = ?",
                    player.getUniqueId().toString(), variable.getKey()
                ).join();
            }
        } catch (Exception e) {
            logger.error("获取存储值失败: " + variable.getKey(), e);
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
                    result = String.valueOf(FormulaCalculator.calculate(result));
                }
                
                // 如果没有变化，停止递归
                if (result.equals(previous)) {
                    break;
                }
                
                depth++;
            }
            
            // 额外调试信息
            if (result == null || result.trim().isEmpty()) {
                logger.debug("表达式解析结果为空，原始表达式: " + expression);
            } else if (containsPlaceholders(result)) {
                logger.debug("解析后仍包含未解析占位符: " + result + " (原始: " + expression + ")");
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
            logger.debug("开始验证约束: 值=" + value + ", 约束=" + limitations);
            
            // 数值约束
            if (limitations.getMinValue() != null || limitations.getMaxValue() != null) {
                logger.debug("检测到数值约束，开始验证");
                try {
                    double numValue = Double.parseDouble(value);
                    logger.debug("成功解析数值: " + numValue);
                    
                    if (limitations.getMinValue() != null) {
                        logger.debug("原始最小值约束: " + limitations.getMinValue());
                        String minExpr;
                        try {
                            minExpr = resolveExpression(limitations.getMinValue(), player);
                        } catch (Exception e) {
                            logger.warn("最小值约束表达式解析异常: " + limitations.getMinValue() + ", 错误: " + e.getMessage());
                            minExpr = limitations.getMinValue(); // 使用原始值
                        }
                        logger.debug("最小值约束表达式: " + limitations.getMinValue() + " -> " + minExpr);
                        try {
                            double minValue = Double.parseDouble(minExpr);
                            logger.debug("数值约束检查: " + numValue + " >= " + minValue + " ? " + (numValue >= minValue));
                            if (numValue < minValue) {
                                logger.debug("值小于最小约束: " + numValue + " < " + minValue);
                                return false;
                            }
                        } catch (NumberFormatException e) {
                            logger.warn("最小值约束解析失败，跳过约束检查: " + minExpr + ", 错误: " + e.getMessage());
                            // 解析失败时不阻止操作，仅记录警告
                        }
                    }
                    
                    if (limitations.getMaxValue() != null) {
                        logger.debug("原始最大值约束: " + limitations.getMaxValue());
                        String maxExpr;
                        try {
                            maxExpr = resolveExpression(limitations.getMaxValue(), player);
                        } catch (Exception e) {
                            logger.warn("最大值约束表达式解析异常: " + limitations.getMaxValue() + ", 错误: " + e.getMessage());
                            maxExpr = limitations.getMaxValue(); // 使用原始值
                        }
                        logger.debug("最大值约束表达式: " + limitations.getMaxValue() + " -> " + maxExpr);
                        try {
                            double maxValue = Double.parseDouble(maxExpr);
                            logger.debug("数值约束检查: " + numValue + " <= " + maxValue + " ? " + (numValue <= maxValue));
                            if (numValue > maxValue) {
                                logger.debug("值大于最大约束: " + numValue + " > " + maxValue);
                                return false;
                            }
                        } catch (NumberFormatException e) {
                            logger.warn("最大值约束解析失败，跳过约束检查: " + maxExpr + ", 错误: " + e.getMessage());
                            // 解析失败时不阻止操作，仅记录警告
                        }
                    }
                } catch (NumberFormatException e) {
                    // 如果值不是数字，但设置了数值约束，检查是否是有效的字符串值
                    logger.debug("非数值类型，跳过数值约束检查: " + value);
                    // 对于非数值类型，只要不是数值约束就允许通过
                }
            }
            
            // 长度约束 - 需要根据数据类型来处理
            Integer minLength = limitations.getMinLength();
            Integer maxLength = limitations.getMaxLength();
            
            if (minLength != null || maxLength != null) {
                // 获取变量定义以确定数据类型
                Variable var = getVariableDefinition(getCurrentVariableKey());
                if (var != null && var.getValueType() == ValueType.LIST) {
                    // LIST类型：检查列表元素数量
                    int listSize = parseListSize(value);
                    logger.debug("LIST约束检查: 当前元素数量=" + listSize + ", 最小=" + minLength + ", 最大=" + maxLength);
                    
                    if (minLength != null && minLength > 0 && listSize < minLength) {
                        logger.debug("列表元素数量小于最小约束: " + listSize + " < " + minLength);
                        return false;
                    }
                    if (maxLength != null && maxLength > 0 && listSize > maxLength) {
                        logger.debug("列表元素数量大于最大约束: " + listSize + " > " + maxLength);
                        return false;
                    }
                } else {
                    // STRING类型：检查字符串长度
                    if (minLength != null && minLength > 0 && value.length() < minLength) {
                        logger.debug("字符串长度小于最小长度约束: " + value.length() + " < " + minLength);
                        return false;
                    }
                    if (maxLength != null && maxLength > 0 && value.length() > maxLength) {
                        logger.debug("字符串长度大于最大长度约束: " + value.length() + " > " + maxLength);
                        return false;
                    }
                }
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
     * 清理指定变量的所有缓存
     *
     * @param key 变量键名
     */
    public void invalidateAllCaches(String key) {
        try {
            // 移除对应的值缓存
            valueCache.keySet().removeIf(cacheKey -> cacheKey.endsWith(":" + key));
            // 移除可能相关的表达式缓存
            expressionCache.entrySet().removeIf(entry -> entry.getKey().contains(key));
            logger.debug("已清理变量缓存: " + key);
        } catch (Exception e) {
            logger.error("清理变量缓存失败: " + key, e);
        }
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
    
    /**
     * 获取当前正在验证的变量键
     */
    private String getCurrentVariableKey() {
        return currentValidatingVariable.get();
    }
    
    /**
     * 设置当前正在验证的变量键
     */
    private void setCurrentValidatingVariable(String key) {
        currentValidatingVariable.set(key);
    }
    
    /**
     * 清除当前正在验证的变量键
     */
    private void clearCurrentValidatingVariable() {
        currentValidatingVariable.remove();
    }
    
    /**
     * 解析列表大小
     * 支持多种列表格式：JSON数组、逗号分隔、换行分隔等
     */
    private int parseListSize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        
        value = value.trim();
        
        // 尝试解析 JSON 数组格式
        if (value.startsWith("[") && value.endsWith("]")) {
            try {
                String jsonContent = value.substring(1, value.length() - 1).trim();
                if (jsonContent.isEmpty()) {
                    return 0;
                }
                // 简单计算逗号分隔的元素数量（不考虑嵌套）
                return jsonContent.split(",").length;
            } catch (Exception e) {
                logger.debug("JSON数组解析失败，尝试其他格式: " + value);
            }
        }
        
        // 尝试逗号分隔格式
        if (value.contains(",")) {
            return value.split(",").length;
        }
        
        // 尝试换行分隔格式
        if (value.contains("\n")) {
            String[] lines = value.split("\n");
            int count = 0;
            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    count++;
                }
            }
            return count;
        }
        
        // 单个元素
        return 1;
    }
    
    /**
     * 根据限制条件对值进行自动修正（数值越界则钳制到边界，列表长度超限则截断）
     */
    private String clampValueToLimitations(Variable variable, String value, OfflinePlayer player) {
        if (variable == null || value == null) {
            return value;
        }
        Limitations lim = variable.getLimitations();
        if (lim == null) {
            return value;
        }
        try {
            ValueType type = variable.getValueType();
            if (type == null) {
                type = inferTypeFromValue(value);
            }
            switch (type) {
                case INT:
                case DOUBLE: {
                    double num = parseDoubleOrDefault(value, 0);
                    if (lim.getMinValue() != null) {
                        String minExpr = resolveExpression(lim.getMinValue(), player);
                        num = Math.max(num, parseDoubleOrDefault(minExpr, num));
                    }
                    if (lim.getMaxValue() != null) {
                        String maxExpr = resolveExpression(lim.getMaxValue(), player);
                        num = Math.min(num, parseDoubleOrDefault(maxExpr, num));
                    }
                    if (type == ValueType.INT) {
                        return String.valueOf((int) Math.round(num));
                    } else {
                        return String.valueOf(num);
                    }
                }
                case LIST: {
                    int listSize = parseListSize(value);
                    int minLen = lim.getMinLength() != null ? lim.getMinLength() : -1;
                    int maxLen = lim.getMaxLength() != null ? lim.getMaxLength() : -1;
                    // 兼容 min/max 作为长度约束
                    if (maxLen <= 0 && lim.getMaxValue() != null) {
                        maxLen = parseIntOrDefault(resolveExpression(lim.getMaxValue(), player), -1);
                    }
                    if (minLen <= 0 && lim.getMinValue() != null) {
                        minLen = parseIntOrDefault(resolveExpression(lim.getMinValue(), player), -1);
                    }
                    if (maxLen > 0 && listSize > maxLen) {
                        String[] parts = value.split(",");
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < maxLen && i < parts.length; i++) {
                            if (i > 0) sb.append(",");
                            sb.append(parts[i]);
                        }
                        return sb.toString();
                    }
                    // 不足最小长度暂不自动补齐
                    return value;
                }
                default:
                    return value;
            }
        } catch (Exception e) {
            logger.error("自动钳制值失败: " + value, e);
            return value;
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