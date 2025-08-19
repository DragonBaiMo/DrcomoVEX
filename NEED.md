# 待补充组件

## 多级缓存管理
- 用途：统筹内存原始数据、表达式结果和最终计算值的多级缓存，减少重复解析和数据库访问。
- 现有实现位置：`src/main/java/cn/drcomo/storage/MultiLevelCacheManager.java`
- 建议接口原型：
```java
public interface MultiLevelCacheService {
    CacheResult getFromCache(OfflinePlayer player, String key, Variable variable);
    void cacheExpression(String expression, OfflinePlayer player, String result);
    void cacheResult(OfflinePlayer player, String key, String originalValue, String result);
    void invalidate(OfflinePlayer player, String key);
}
```

## 批量持久化与脏数据管理
- 用途：收集 `VariableMemoryStorage` 中的脏数据，按策略批量持久化到数据库，并维护 `DirtyFlag` 状态。
- 现有实现位置：`src/main/java/cn/drcomo/storage/BatchPersistenceManager.java` 及 `src/main/java/cn/drcomo/storage/DirtyFlag.java`
- 建议接口原型：
```java
public interface BatchPersistenceService {
    void start();
    void shutdown();
    CompletableFuture<Void> flushAllDirtyData();
    void markDirty(String key, DirtyFlag.Type type);
}
```

## 内存优先变量存储
- 用途：以内存为主存储，追踪变量值与脏标记，降低数据库访问压力。
- 现有实现位置：`src/main/java/cn/drcomo/storage/VariableMemoryStorage.java`
- 建议接口原型：
```java
public interface VariableStorage {
    VariableValue getPlayerVariable(UUID playerId, String key);
    void setPlayerVariable(UUID playerId, String key, String value);
    VariableValue getServerVariable(String key);
    void setServerVariable(String key, String value);
    Map<String, DirtyFlag> getAllDirtyData();
}
```

## DebugUtil 增强需求（建议补充到 DrcomoCoreLib）
- 用途：在热路径（如占位符解析）中只在 DEBUG 级别启用详细日志，减少 INFO 级别的噪音与 IO 开销。
- 现用位置：`src/main/java/cn/drcomo/api/ServerVariablesAPI.java` 中占位符处理流程；`MessagesManager` 初始化与错误处理。
- 建议接口原型：
```java
public final class DebugUtil {
    public LogLevel getLevel();
    public boolean isDebugEnabled(); // 快速分支判断，避免字符串拼接
}
```
备注：当前通过 `getLevel() == LogLevel.DEBUG` 进行判定，可由 `isDebugEnabled()` 直接替换以简化分支与提升可读性。