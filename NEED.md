# DrcomoCoreLib 功能需求文档

## 概述

本文档说明在实现 **DrcomoVEX** 插件过程中，发现需要在 **DrcomoCoreLib** 中新增或改进的功能。这些功能将增强核心库的能力，为基于直觉设计的变量管理系统提供更好的支持。

---

## 1. MessageService 增强

### 1.1 内部占位符注册功能

**需求描述：** 允许插件注册自定义的内部占位符，而不依赖 PlaceholderAPI。

**所需接口：**
```java
public interface MessageService {
    /**
     * 注册内部占位符
     * @param placeholder 占位符名称（不包含%符号）
     * @param resolver 占位符解析器
     */
    void registerInternalPlaceholder(String placeholder, PlaceholderResolver resolver);
    
    /**
     * 取消注册内部占位符
     * @param placeholder 占位符名称
     */
    void unregisterInternalPlaceholder(String placeholder);
}

@FunctionalInterface
public interface PlaceholderResolver {
    /**
     * 解析占位符
     * @param player 玩家对象，可能为null
     * @param args 额外参数
     * @return 解析结果
     */
    String resolve(Player player, String[] args);
}
```

**使用场景：**
- 插件内部占位符如 `%plugin_version%`、`%total_variables%`
- 动态统计信息如 `%database_status%`、`%cache_hit_rate%`

---

## 2. AsyncTaskManager 增强

### 2.1 任务优先级支持

**需求描述：** 支持异步任务的优先级调度，确保重要任务优先执行。

**所需接口：**
```java
public enum TaskPriority {
    LOW, NORMAL, HIGH, CRITICAL
}

public interface AsyncTaskManager {
    /**
     * 提交带优先级的异步任务
     */
    <T> CompletableFuture<T> submitWithPriority(Supplier<T> task, TaskPriority priority);
    
    /**
     * 提交带优先级的异步任务（无返回值）
     */
    CompletableFuture<Void> runWithPriority(Runnable task, TaskPriority priority);
    
    /**
     * 获取任务队列状态
     */
    TaskQueueStatus getQueueStatus();
}
```

### 2.2 定时任务增强

**需求描述：** 支持更灵活的定时任务，包括 Cron 表达式和延迟执行。

**所需接口：**
```java
public interface AsyncTaskManager {
    /**
     * 基于 Cron 表达式的定时任务
     */
    ScheduledTask scheduleCron(String cronExpression, Runnable task);
    
    /**
     * 延迟执行任务
     */
    ScheduledTask scheduleDelayed(Runnable task, long delay, TimeUnit unit);
    
    /**
     * 取消定时任务
     */
    boolean cancelTask(ScheduledTask task);
}
```

---

## 3. YamlUtil 增强

### 3.1 配置热重载监听

**需求描述：** 监听配置文件变化并自动重载，支持热更新。

**所需接口：**
```java
public interface YamlUtil {
    /**
     * 启用文件监听
     */
    void enableFileWatcher(String configKey, FileChangeListener listener);
    
    /**
     * 禁用文件监听
     */
    void disableFileWatcher(String configKey);
}

@FunctionalInterface
public interface FileChangeListener {
    /**
     * 文件变化回调
     * @param configKey 配置键
     * @param changeType 变化类型（MODIFIED, CREATED, DELETED）
     */
    void onFileChanged(String configKey, FileChangeType changeType);
}
```

### 3.2 配置验证功能

**需求描述：** 对配置文件进行结构和内容验证。

**所需接口：**
```java
public interface YamlUtil {
    /**
     * 验证配置文件结构
     */
    ValidationResult validateConfig(String configKey, ConfigSchema schema);
    
    /**
     * 设置配置默认值
     */
    void setDefaults(String configKey, Map<String, Object> defaults);
}

public class ConfigSchema {
    // 配置字段约束定义
}
```

---

## 4. 数据库工具增强

### 4.1 连接池监控

**需求描述：** 提供数据库连接池的监控和统计功能。

**所需接口：**
```java
public interface DatabaseUtil {
    /**
     * 获取连接池状态
     */
    ConnectionPoolStatus getPoolStatus();
    
    /**
     * 获取数据库性能统计
     */
    DatabaseMetrics getMetrics();
    
    /**
     * 检查连接是否有效
     */
    boolean isConnectionValid();
}

public class ConnectionPoolStatus {
    private int activeConnections;
    private int idleConnections;
    private int totalConnections;
    // getters...
}
```

### 4.2 批量操作支持

**需求描述：** 支持高效的批量数据库操作。

**所需接口：**
```java
public interface DatabaseUtil {
    /**
     * 批量执行更新操作
     */
    CompletableFuture<int[]> batchUpdate(String sql, List<Object[]> paramsList);
    
    /**
     * 批量插入数据
     */
    CompletableFuture<Void> batchInsert(String tableName, List<Map<String, Object>> dataList);
    
    /**
     * 事务支持
     */
    <T> CompletableFuture<T> executeInTransaction(Function<Connection, T> operation);
}
```

---

## 5. CacheUtil 新增模块

### 5.1 基本缓存功能

**需求描述：** 提供通用的缓存工具，支持过期时间和最大容量限制。

**所需接口：**
```java
public interface CacheUtil {
    /**
     * 创建缓存实例
     */
    <K, V> Cache<K, V> createCache(String name, CacheConfig config);
    
    /**
     * 获取已存在的缓存
     */
    <K, V> Cache<K, V> getCache(String name);
    
    /**
     * 销毁缓存
     */
    void destroyCache(String name);
    
    /**
     * 获取所有缓存统计
     */
    Map<String, CacheStats> getAllCacheStats();
}

public interface Cache<K, V> {
    V get(K key);
    void put(K key, V value);
    void put(K key, V value, long expireAfter, TimeUnit unit);
    void remove(K key);
    void clear();
    CacheStats getStats();
    int size();
}
```

### 5.2 缓存配置

**需求描述：** 灵活的缓存配置选项。

**所需类：**
```java
public class CacheConfig {
    private long maximumSize = 10000;
    private long expireAfterWrite = 10; // 分钟
    private TimeUnit expireUnit = TimeUnit.MINUTES;
    private boolean recordStats = true;
    private CacheEvictionPolicy evictionPolicy = CacheEvictionPolicy.LRU;
    
    // builder pattern methods...
}
```

---

## 6. ExpressionUtil 新增模块

### 6.1 表达式解析引擎

**需求描述：** 安全的表达式解析和计算引擎，支持变量引用和数学运算。

**所需接口：**
```java
public interface ExpressionUtil {
    /**
     * 解析表达式
     */
    ParsedExpression parseExpression(String expression);
    
    /**
     * 计算表达式值
     */
    Object evaluateExpression(ParsedExpression expression, VariableContext context);
    
    /**
     * 验证表达式安全性
     */
    ValidationResult validateExpression(String expression, SecurityPolicy policy);
}

public interface VariableContext {
    Object getVariable(String name);
    void setVariable(String name, Object value);
    boolean hasVariable(String name);
}

public class SecurityPolicy {
    private int maxRecursionDepth = 10;
    private int maxExpressionLength = 1000;
    private boolean allowCircularReferences = false;
    private Set<String> allowedFunctions = new HashSet<>();
    
    // getters and setters...
}
```

---

## 7. UpdateChecker 增强

### 7.1 更新策略配置

**需求描述：** 支持不同的更新检查策略和通知方式。

**所需接口：**
```java
public interface UpdateChecker {
    /**
     * 设置更新策略
     */
    void setUpdatePolicy(UpdatePolicy policy);
    
    /**
     * 手动检查更新
     */
    CompletableFuture<UpdateResult> checkForUpdates();
    
    /**
     * 注册更新监听器
     */
    void addUpdateListener(UpdateListener listener);
}

public class UpdatePolicy {
    private boolean autoCheck = true;
    private long checkInterval = 24; // 小时
    private boolean notifyOps = true;
    private boolean downloadAutomatically = false;
    
    // getters and setters...
}
```

---

## 8. MetricsUtil 新增模块

### 8.1 性能监控

**需求描述：** 内置的性能监控和统计功能。

**所需接口：**
```java
public interface MetricsUtil {
    /**
     * 记录计数指标
     */
    void incrementCounter(String name);
    void incrementCounter(String name, long value);
    
    /**
     * 记录计时指标
     */
    Timer.Context startTimer(String name);
    void recordTime(String name, long duration, TimeUnit unit);
    
    /**
     * 记录仪表指标
     */
    void recordGauge(String name, double value);
    
    /**
     * 获取指标统计
     */
    MetricsSnapshot getSnapshot();
    
    /**
     * 导出指标数据
     */
    void exportMetrics(MetricsExporter exporter);
}
```

---

## 9. 优先级建议

### 高优先级
1. **MessageService 内部占位符注册** - 核心功能依赖
2. **CacheUtil 模块** - 性能优化关键
3. **ExpressionUtil 模块** - 动态变量计算核心

### 中优先级
4. **AsyncTaskManager 优先级支持** - 任务调度优化
5. **YamlUtil 热重载监听** - 配置管理增强
6. **数据库连接池监控** - 运维支持

### 低优先级
7. **UpdateChecker 策略配置** - 功能完善
8. **MetricsUtil 监控模块** - 运维辅助
9. **数据库批量操作** - 性能优化

---

## 10. 兼容性说明

- 所有新增接口应保持向后兼容
- 建议采用 `@Since` 注解标注版本
- 提供默认实现以避免破坏现有插件
- 考虑使用 SPI 机制支持可插拔实现

---

## 11. 实现建议

1. **模块化设计** - 每个功能模块独立，支持按需加载
2. **配置驱动** - 所有功能都应支持配置开关
3. **异常处理** - 完善的异常处理和降级机制
4. **文档完善** - 提供详细的 JavaDoc 和使用示例
5. **单元测试** - 为每个新增功能编写充分的单元测试

---

**文档版本：** 1.0.0  
**创建日期：** 2025-01-03  
**作者：** BaiMo  
**联系方式：** [项目 GitHub Issues](https://github.com/DragonBaiMo/DrcomoVEX/issues)