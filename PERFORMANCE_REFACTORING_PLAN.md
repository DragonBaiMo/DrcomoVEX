# DrcomoVEX 性能重构方案

## 📊 当前问题分析

### 🚨 严重问题
1. **频繁阻塞操作**: `.join()` 调用阻塞线程池
2. **小事务频繁提交**: 每个变量操作都是独立事务
3. **递归数据库访问**: 表达式解析触发多次查询
4. **缓存策略不当**: TTL过短，命中率低

### 📈 影响评估
- **高频更新场景**: 每秒数百次操作时性能急剧下降
- **内存压力**: 大量并发时连接池耗尽
- **用户体验**: 变量操作响应延迟明显

## 🎯 重构目标

### 核心理念
- **内存优先**: 数据主要存储在内存中，延迟持久化
- **批量操作**: 减少数据库事务频率
- **异步无阻塞**: 消除所有同步等待
- **智能缓存**: 多级缓存提升命中率

## 🏗️ 架构设计

### 1. 内存优先存储架构

```java
/**
 * 内存优先变量存储系统
 * 核心思想：内存作为主存储，数据库作为持久化备份
 */
public class VariableMemoryStorage {
    
    // 玩家变量存储: UUID -> 变量键 -> 变量值对象
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, VariableValue>> playerVariables;
    
    // 服务器全局变量存储: 变量键 -> 变量值对象
    private final ConcurrentHashMap<String, VariableValue> serverVariables;
    
    // 脏数据追踪器 - 记录需要持久化的数据
    private final ConcurrentHashMap<String, DirtyFlag> dirtyTracker;
    
    // 内存使用监控
    private final AtomicLong memoryUsage = new AtomicLong(0);
    private static final long MAX_MEMORY_THRESHOLD = 100 * 1024 * 1024; // 100MB
}
```

### 2. 变量值对象设计

```java
/**
 * 增强的变量值对象
 * 包含值、时间戳、脏标记等元数据
 */
public class VariableValue {
    private volatile String value;           // 当前值
    private volatile long lastModified;      // 最后修改时间
    private volatile long lastAccessed;      // 最后访问时间
    private volatile boolean isDirty;        // 是否需要持久化
    private volatile String originalValue;   // 数据库中的原始值
    private final AtomicInteger accessCount = new AtomicInteger(0); // 访问计数
    
    // 标记为脏数据
    public void markDirty() {
        this.isDirty = true;
        this.lastModified = System.currentTimeMillis();
    }
    
    // 清除脏标记（持久化后调用）
    public void clearDirty() {
        this.isDirty = false;
        this.originalValue = this.value;
    }
}
```

### 3. 批量持久化管理器

```java
/**
 * 批量持久化管理器
 * 负责收集脏数据并批量写入数据库
 */
public class BatchPersistenceManager {
    
    private final ScheduledExecutorService scheduler;
    private final HikariConnection database;
    private final Queue<PersistenceTask> writeQueue = new ConcurrentLinkedQueue<>();
    
    // 定时批量持久化（每30-60秒）
    @Scheduled(fixedRate = 30000)
    public CompletableFuture<Void> scheduledFlush() {
        return flushAllDirtyData();
    }
    
    // 批量持久化所有脏数据
    public CompletableFuture<Void> flushAllDirtyData() {
        List<PersistenceTask> tasks = collectDirtyData();
        return batchExecute(tasks);
    }
    
    // 玩家退出时立即持久化该玩家数据
    public CompletableFuture<Void> flushPlayerData(UUID playerId) {
        List<PersistenceTask> playerTasks = collectPlayerDirtyData(playerId);
        return batchExecute(playerTasks).thenRun(() -> 
            cleanupPlayerMemory(playerId)); // 释放内存
    }
    
    // 批量执行数据库操作
    private CompletableFuture<Void> batchExecute(List<PersistenceTask> tasks) {
        if (tasks.isEmpty()) return CompletableFuture.completedFuture(null);
        
        return CompletableFuture.runAsync(() -> {
            try (Connection conn = database.getConnection()) {
                conn.setAutoCommit(false);
                
                // 批量执行玩家变量更新
                batchUpdatePlayerVariables(conn, 
                    tasks.stream()
                         .filter(t -> t.getType() == TaskType.PLAYER_VARIABLE)
                         .collect(toList()));
                
                // 批量执行服务器变量更新
                batchUpdateServerVariables(conn,
                    tasks.stream()
                         .filter(t -> t.getType() == TaskType.SERVER_VARIABLE)
                         .collect(toList()));
                
                conn.commit();
                
                // 标记所有任务为已持久化
                tasks.forEach(task -> task.getVariableValue().clearDirty());
                
            } catch (SQLException e) {
                logger.error("批量持久化失败", e);
                throw new RuntimeException("批量持久化失败", e);
            }
        }, persistenceExecutor);
    }
}
```

### 4. 异步写入队列

```java
/**
 * 异步写入队列管理器
 * 消除所有阻塞操作，提供高性能异步写入
 */
public class AsyncWriteQueue {
    
    private final BlockingQueue<WriteTask> writeQueue = new LinkedBlockingQueue<>(10000);
    private final ExecutorService writeWorkers;
    private volatile boolean running = true;
    
    // 提交写入任务（非阻塞）
    public CompletableFuture<Void> submitWrite(WriteTask task) {
        if (!writeQueue.offer(task)) {
            // 队列满时的降级策略
            return handleQueueFull(task);
        }
        return task.getFuture();
    }
    
    // 工作线程持续处理写入队列
    private void processWriteQueue() {
        List<WriteTask> batch = new ArrayList<>();
        
        while (running) {
            try {
                WriteTask task = writeQueue.take(); // 阻塞等待任务
                batch.add(task);
                
                // 收集批次任务（最多100个或等待时间超过100ms）
                long batchStartTime = System.currentTimeMillis();
                while (batch.size() < 100 && 
                       System.currentTimeMillis() - batchStartTime < 100) {
                    WriteTask nextTask = writeQueue.poll(10, TimeUnit.MILLISECONDS);
                    if (nextTask != null) {
                        batch.add(nextTask);
                    } else {
                        break;
                    }
                }
                
                // 批量执行写入
                processBatch(batch);
                batch.clear();
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
```

### 5. 多级缓存系统

```java
/**
 * 多级缓存管理器
 * L1: 内存原始数据 (永不过期)
 * L2: 解析表达式结果 (长TTL: 5分钟)
 * L3: 计算结果缓存 (中TTL: 2分钟)
 */
public class MultiLevelCacheManager {
    
    // L1缓存：内存中的原始数据（即VariableMemoryStorage）
    private final VariableMemoryStorage l1Cache;
    
    // L2缓存：解析后的表达式结果
    private final Cache<String, String> l2ExpressionCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .recordStats()
            .build();
    
    // L3缓存：最终计算结果
    private final Cache<String, String> l3ResultCache = Caffeine.newBuilder()
            .maximumSize(5000)
            .expireAfterWrite(Duration.ofMinutes(2))
            .recordStats()
            .build();
    
    public String getFromCache(String key, OfflinePlayer player, Variable variable) {
        // L1缓存查找
        VariableValue value = l1Cache.get(player, key);
        if (value != null) {
            String cacheKey = buildCacheKey(player, key);
            
            // L3缓存查找
            String result = l3ResultCache.getIfPresent(cacheKey);
            if (result != null) {
                return result;
            }
            
            // L2表达式缓存查找
            String expression = value.getValue();
            String expressionKey = "expr:" + expression.hashCode() + ":" + 
                                  (player != null ? player.getUniqueId() : "server");
            
            String resolvedExpression = l2ExpressionCache.getIfPresent(expressionKey);
            if (resolvedExpression != null) {
                l3ResultCache.put(cacheKey, resolvedExpression);
                return resolvedExpression;
            }
        }
        
        return null; // 缓存未命中
    }
}
```

## 🚀 实施计划

### 阶段一：基础架构重构 (第1-2周)

1. **创建内存存储系统**
   - 实现 `VariableMemoryStorage`
   - 设计 `VariableValue` 对象
   - 添加脏数据追踪机制

2. **消除阻塞操作**
   - 移除所有 `.join()` 调用
   - 重构为纯异步操作
   - 实现异步写入队列

### 阶段二：批量持久化实现 (第3周)

1. **批量持久化管理器**
   - 实现定时批量写入
   - 玩家退出时立即持久化
   - 内存压力触发机制

2. **数据库批量操作优化**
   - 实现批量 INSERT/UPDATE
   - 优化SQL语句性能
   - 添加事务管理

### 阶段三：缓存系统优化 (第4周)

1. **多级缓存实现**
   - 重构现有缓存逻辑
   - 实现L1/L2/L3缓存体系
   - 添加缓存统计监控

2. **预加载机制**
   - 玩家上线时预加载数据
   - 热点数据智能预测
   - 关联数据预加载

### 阶段四：监控和调优 (第5周)

1. **性能监控**
   - 添加详细的性能指标
   - 实现内存使用监控
   - 数据库操作统计

2. **压力测试和调优**
   - 模拟高并发场景
   - 性能瓶颈分析
   - 参数调优

## 📈 预期性能提升

### 数据库操作优化
- **写入频率**: 从每次操作1次 → 批量30-60秒1次
- **事务数量**: 减少95%以上的数据库事务
- **连接池压力**: 显著降低峰值连接数

### 响应时间改善  
- **变量读取**: 从数据库查询时间 → 内存访问（微秒级）
- **变量写入**: 从阻塞等待 → 立即返回（异步处理）
- **表达式解析**: 多级缓存命中率提升至90%+

### 内存和CPU优化
- **内存使用**: 可控的内存增长，避免内存泄漏
- **CPU压力**: 减少数据库连接开销
- **线程利用**: 避免线程池阻塞

## ⚠️ 风险评估和应对

### 数据一致性风险
- **风险**: 内存数据与数据库不同步
- **应对**: 严格的脏数据追踪，定期一致性检查

### 内存溢出风险  
- **风险**: 大量玩家数据占用过多内存
- **应对**: 内存阈值监控，LRU淘汰机制

### 数据丢失风险
- **风险**: 服务器异常关闭导致未持久化数据丢失
- **应对**: 优雅关闭机制，紧急持久化策略

## 🔧 配置优化建议

```yaml
# 新增性能配置
performance:
  memory-storage:
    # 内存存储启用
    enabled: true
    # 最大内存使用量 (MB)
    max-memory-mb: 256
    # 玩家数据过期时间（分钟，玩家离线后）
    player-data-expire-minutes: 30
  
  batch-persistence:
    # 批量持久化间隔（秒）
    batch-interval-seconds: 30
    # 最大批次大小
    max-batch-size: 1000
    # 内存压力阈值触发持久化 (%)
    memory-pressure-threshold: 80
  
  async-queue:
    # 异步写入队列大小
    queue-size: 10000
    # 工作线程数
    worker-threads: 2
    # 批处理大小
    batch-size: 100
  
  cache:
    l2-expression-cache:
      expire-minutes: 5
      maximum-size: 10000
    l3-result-cache:
      expire-minutes: 2
      maximum-size: 5000
```

## 📊 性能监控指标

### 关键性能指标 (KPI)
1. **内存使用率**: 当前内存占用/最大内存限制
2. **缓存命中率**: L1/L2/L3各级缓存的命中率  
3. **批量持久化效率**: 平均批次大小，持久化延迟
4. **队列积压**: 异步写入队列的积压情况
5. **数据库连接使用**: 连接池的活跃连接数

### 告警阈值
- 内存使用率 > 85%
- L1缓存命中率 < 95%
- 写入队列积压 > 5000
- 批量持久化延迟 > 10秒

这个重构方案将显著提升系统在高并发场景下的性能表现，减少I/O压力，提供更好的用户体验。