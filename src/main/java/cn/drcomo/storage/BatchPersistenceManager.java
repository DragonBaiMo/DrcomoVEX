package cn.drcomo.storage;

import cn.drcomo.database.HikariConnection;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.corelib.async.AsyncTaskManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 批量持久化管理器
 * 
 * 负责收集内存中的脏数据并批量写入数据库，以减少I/O操作频率。
 * 支持定时批量持久化、玩家退出时立即持久化、内存压力触发持久化等多种策略。
 * 
 * @author BaiMo
 */
public class BatchPersistenceManager {
    
    private final DebugUtil logger;
    private final HikariConnection database;
    private final VariableMemoryStorage memoryStorage;
    private final AsyncTaskManager asyncTaskManager;
    
    // 持久化配置
    private final int batchIntervalSeconds;
    private final int maxBatchSize;
    private final double memoryPressureThreshold;
    private final int maxRetries;
    
    // 持久化任务队列
    private final BlockingQueue<PersistenceTask> persistenceQueue;
    private final ExecutorService persistenceExecutor;
    
    // 调度器
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledPersistenceTask;
    
    // 统计信息
    private final AtomicLong totalPersistenceOperations = new AtomicLong(0);
    private final AtomicLong totalPersistedVariables = new AtomicLong(0);
    private final AtomicLong failedPersistenceOperations = new AtomicLong(0);
    private final AtomicLong lastPersistenceTime = new AtomicLong(0);
    
    // 运行状态
    private volatile boolean running = true;
    
    /**
     * 构造函数
     */
    public BatchPersistenceManager(
            DebugUtil logger,
            HikariConnection database,
            VariableMemoryStorage memoryStorage,
            AsyncTaskManager asyncTaskManager,
            PersistenceConfig config) {
        
        this.logger = logger;
        this.database = database;
        this.memoryStorage = memoryStorage;
        this.asyncTaskManager = asyncTaskManager;
        
        // 配置参数
        this.batchIntervalSeconds = config.getBatchIntervalSeconds();
        this.maxBatchSize = config.getMaxBatchSize();
        this.memoryPressureThreshold = config.getMemoryPressureThreshold();
        this.maxRetries = config.getMaxRetries();
        
        // 初始化队列和线程池
        this.persistenceQueue = new LinkedBlockingQueue<>(config.getQueueSize());
        this.persistenceExecutor = Executors.newFixedThreadPool(
                config.getWorkerThreads(), 
                r -> {
                    Thread t = new Thread(r, "BatchPersistence-Worker");
                    t.setDaemon(true);
                    return t;
                });
        
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                r -> {
                    Thread t = new Thread(r, "BatchPersistence-Scheduler");
                    t.setDaemon(true);
                    return t;
                });
    }
    
    /**
     * 启动批量持久化管理器
     */
    public void start() {
        logger.info("启动批量持久化管理器...");
        
        // 启动工作线程
        for (int i = 0; i < 2; i++) { // 默认2个工作线程
            persistenceExecutor.submit(this::persistenceWorker);
        }
        
        // 启动定时批量持久化
        scheduledPersistenceTask = scheduler.scheduleAtFixedRate(
                this::scheduledPersistence,
                batchIntervalSeconds,
                batchIntervalSeconds,
                TimeUnit.SECONDS
        );
        
        logger.info("批量持久化管理器启动完成，间隔: " + batchIntervalSeconds + "秒");
    }
    
    /**
     * 停止批量持久化管理器
     */
    public void shutdown() {
        logger.info("正在关闭批量持久化管理器...");
        running = false;
        
        // 取消定时任务
        if (scheduledPersistenceTask != null) {
            scheduledPersistenceTask.cancel(false);
        }
        
        // 立即持久化所有脏数据
        try {
            flushAllDirtyData().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("关闭时持久化数据失败", e);
        }
        
        // 关闭线程池
        scheduler.shutdown();
        persistenceExecutor.shutdown();
        
        try {
            if (!persistenceExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                persistenceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            persistenceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("批量持久化管理器已关闭");
    }
    
    /**
     * 定时批量持久化
     */
    private void scheduledPersistence() {
        try {
            flushAllDirtyData().whenComplete((result, throwable) -> {
                if (throwable != null) {
                    logger.error("定时批量持久化失败", throwable);
                } else {
                    logger.debug("定时批量持久化完成");
                }
            });
        } catch (Exception e) {
            logger.error("定时批量持久化异常", e);
        }
    }
    
    /**
     * 批量持久化所有脏数据
     */
    public CompletableFuture<Void> flushAllDirtyData() {
        return CompletableFuture.runAsync(() -> {
            try {
                Map<String, DirtyFlag> allDirtyData = memoryStorage.getAllDirtyData();
                if (allDirtyData.isEmpty()) {
                    return;
                }
                
                logger.debug("开始批量持久化，脏数据数量: " + allDirtyData.size());
                
                List<PersistenceTask> tasks = collectPersistenceTasks(allDirtyData);
                executeBatchPersistence(tasks);
                
                lastPersistenceTime.set(System.currentTimeMillis());
                
            } catch (Exception e) {
                logger.error("批量持久化所有数据失败", e);
                failedPersistenceOperations.incrementAndGet();
                throw new RuntimeException("批量持久化失败", e);
            }
        }, persistenceExecutor);
    }
    
    /**
     * 玩家退出时立即持久化该玩家数据
     */
    public CompletableFuture<Void> flushPlayerData(UUID playerId) {
        return CompletableFuture.runAsync(() -> {
            try {
                Map<String, DirtyFlag> playerDirtyData = memoryStorage.getPlayerDirtyData(playerId);
                if (playerDirtyData.isEmpty()) {
                    return;
                }
                
                logger.debug("玩家退出，立即持久化数据: " + playerId + "，脏数据数量: " + playerDirtyData.size());
                
                List<PersistenceTask> tasks = collectPersistenceTasks(playerDirtyData);
                executeBatchPersistence(tasks);
                
                // 清理玩家内存数据
                memoryStorage.cleanupPlayerData(playerId, false);
                
                logger.debug("玩家数据持久化完成: " + playerId);
                
            } catch (Exception e) {
                logger.error("持久化玩家数据失败: " + playerId, e);
                failedPersistenceOperations.incrementAndGet();
                throw new RuntimeException("持久化玩家数据失败", e);
            }
        }, persistenceExecutor);
    }
    
    /**
     * 内存压力触发的持久化
     */
    public CompletableFuture<Void> flushOnMemoryPressure() {
        return CompletableFuture.runAsync(() -> {
            try {
                VariableMemoryStorage.MemoryStats stats = memoryStorage.getMemoryStats();
                if (stats.getMemoryUsagePercent() < memoryPressureThreshold) {
                    return; // 内存压力不够，不执行持久化
                }
                
                logger.warn("内存压力触发持久化，当前使用率: " + String.format("%.1f%%", stats.getMemoryUsagePercent()));
                
                // 优先持久化访问频率低的数据
                Map<String, DirtyFlag> dirtyData = memoryStorage.getAllDirtyData();
                List<PersistenceTask> tasks = collectPersistenceTasks(dirtyData);
                
                // 按优先级排序（冷数据优先持久化）
                tasks.sort((t1, t2) -> {
                    // 简单的优先级策略：按照脏数据等待时间排序
                    return Long.compare(t2.getDirtyFlag().getPendingDuration(), 
                                       t1.getDirtyFlag().getPendingDuration());
                });
                
                executeBatchPersistence(tasks);
                
                logger.info("内存压力持久化完成");
                
            } catch (Exception e) {
                logger.error("内存压力持久化失败", e);
                failedPersistenceOperations.incrementAndGet();
                throw new RuntimeException("内存压力持久化失败", e);
            }
        }, persistenceExecutor);
    }
    
    /**
     * 收集持久化任务
     */
    private List<PersistenceTask> collectPersistenceTasks(Map<String, DirtyFlag> dirtyData) {
        List<PersistenceTask> tasks = new ArrayList<>();
        
        for (Map.Entry<String, DirtyFlag> entry : dirtyData.entrySet()) {
            String key = entry.getKey();
            DirtyFlag flag = entry.getValue();
            
            try {
                PersistenceTask task = createPersistenceTask(key, flag);
                if (task != null) {
                    tasks.add(task);
                }
            } catch (Exception e) {
                logger.error("创建持久化任务失败: " + key, e);
            }
        }
        
        return tasks;
    }
    
    /**
     * 创建持久化任务
     */
    private PersistenceTask createPersistenceTask(String key, DirtyFlag flag) {
        if (flag.isPlayerVariable()) {
            String[] parts = key.split(":", 3);
            if (parts.length != 3) {
                logger.warn("无效的玩家变量键: " + key);
                return null;
            }
            
            UUID playerId = UUID.fromString(parts[1]);
            String varKey = parts[2];
            
            if (flag.isDeleteOperation()) {
                return new PlayerVariableDeleteTask(key, flag, playerId, varKey);
            } else {
                VariableValue value = memoryStorage.getPlayerVariable(playerId, varKey);
                if (value != null) {
                    return new PlayerVariableUpdateTask(key, flag, playerId, varKey, value);
                }
            }
            
        } else if (flag.isServerVariable()) {
            String varKey = key.substring(7); // 移除 "server:" 前缀
            
            if (flag.isDeleteOperation()) {
                return new ServerVariableDeleteTask(key, flag, varKey);
            } else {
                VariableValue value = memoryStorage.getServerVariable(varKey);
                if (value != null) {
                    return new ServerVariableUpdateTask(key, flag, varKey, value);
                }
            }
        }
        
        return null;
    }
    
    /**
     * 执行批量持久化
     */
    private void executeBatchPersistence(List<PersistenceTask> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        
        // 按类型分组批处理
        Map<Class<? extends PersistenceTask>, List<PersistenceTask>> tasksByType = 
                tasks.stream().collect(Collectors.groupingBy(PersistenceTask::getClass));
        
        totalPersistenceOperations.incrementAndGet();
        
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            
            // 批量处理玩家变量更新
            List<PersistenceTask> playerUpdateTasks = tasksByType.get(PlayerVariableUpdateTask.class);
            if (playerUpdateTasks != null && !playerUpdateTasks.isEmpty()) {
                batchUpdatePlayerVariables(connection, playerUpdateTasks);
                totalPersistedVariables.addAndGet(playerUpdateTasks.size());
            }
            
            // 批量处理服务器变量更新
            List<PersistenceTask> serverUpdateTasks = tasksByType.get(ServerVariableUpdateTask.class);
            if (serverUpdateTasks != null && !serverUpdateTasks.isEmpty()) {
                batchUpdateServerVariables(connection, serverUpdateTasks);
                totalPersistedVariables.addAndGet(serverUpdateTasks.size());
            }
            
            // 批量处理玩家变量删除
            List<PersistenceTask> playerDeleteTasks = tasksByType.get(PlayerVariableDeleteTask.class);
            if (playerDeleteTasks != null && !playerDeleteTasks.isEmpty()) {
                batchDeletePlayerVariables(connection, playerDeleteTasks);
                totalPersistedVariables.addAndGet(playerDeleteTasks.size());
            }
            
            // 批量处理服务器变量删除
            List<PersistenceTask> serverDeleteTasks = tasksByType.get(ServerVariableDeleteTask.class);
            if (serverDeleteTasks != null && !serverDeleteTasks.isEmpty()) {
                batchDeleteServerVariables(connection, serverDeleteTasks);
                totalPersistedVariables.addAndGet(serverDeleteTasks.size());
            }
            
            connection.commit();
            
            // 清除所有任务的脏标记
            for (PersistenceTask task : tasks) {
                memoryStorage.clearDirtyFlag(task.getKey());
            }
            
            logger.debug("批量持久化完成，处理任务数: " + tasks.size());
            
        } catch (SQLException e) {
            logger.error("批量持久化执行失败", e);
            failedPersistenceOperations.incrementAndGet();
            
            // 增加重试次数
            for (PersistenceTask task : tasks) {
                task.getDirtyFlag().incrementRetry();
                if (task.getDirtyFlag().hasExcessiveRetries(maxRetries)) {
                    logger.error("任务重试次数过多，放弃持久化: " + task.getKey());
                    memoryStorage.clearDirtyFlag(task.getKey());
                }
            }
            
            throw new RuntimeException("批量持久化执行失败", e);
        }
    }
    
    /**
     * 批量更新玩家变量
     */
    private void batchUpdatePlayerVariables(Connection connection, List<PersistenceTask> tasks) throws SQLException {
        String sql = "INSERT OR REPLACE INTO player_variables (player_uuid, variable_key, value, created_at, updated_at) VALUES (?, ?, ?, ?, ?)";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            long currentTime = System.currentTimeMillis();
            
            for (PersistenceTask task : tasks) {
                if (task instanceof PlayerVariableUpdateTask) {
                    PlayerVariableUpdateTask updateTask = (PlayerVariableUpdateTask) task;
                    
                    stmt.setString(1, updateTask.getPlayerId().toString());
                    stmt.setString(2, updateTask.getVariableKey());
                    stmt.setString(3, updateTask.getValue().getValue());
                    stmt.setLong(4, currentTime);
                    stmt.setLong(5, currentTime);
                    stmt.addBatch();
                }
            }
            
            int[] results = stmt.executeBatch();
            logger.debug("批量更新玩家变量完成，影响行数: " + Arrays.stream(results).sum());
        }
    }
    
    /**
     * 批量更新服务器变量
     */
    private void batchUpdateServerVariables(Connection connection, List<PersistenceTask> tasks) throws SQLException {
        String sql = "INSERT OR REPLACE INTO server_variables (variable_key, value, created_at, updated_at) VALUES (?, ?, ?, ?)";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            long currentTime = System.currentTimeMillis();
            
            for (PersistenceTask task : tasks) {
                if (task instanceof ServerVariableUpdateTask) {
                    ServerVariableUpdateTask updateTask = (ServerVariableUpdateTask) task;
                    
                    stmt.setString(1, updateTask.getVariableKey());
                    stmt.setString(2, updateTask.getValue().getValue());
                    stmt.setLong(3, currentTime);
                    stmt.setLong(4, currentTime);
                    stmt.addBatch();
                }
            }
            
            int[] results = stmt.executeBatch();
            logger.debug("批量更新服务器变量完成，影响行数: " + Arrays.stream(results).sum());
        }
    }
    
    /**
     * 批量删除玩家变量
     */
    private void batchDeletePlayerVariables(Connection connection, List<PersistenceTask> tasks) throws SQLException {
        String sql = "DELETE FROM player_variables WHERE player_uuid = ? AND variable_key = ?";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (PersistenceTask task : tasks) {
                if (task instanceof PlayerVariableDeleteTask) {
                    PlayerVariableDeleteTask deleteTask = (PlayerVariableDeleteTask) task;
                    
                    stmt.setString(1, deleteTask.getPlayerId().toString());
                    stmt.setString(2, deleteTask.getVariableKey());
                    stmt.addBatch();
                }
            }
            
            int[] results = stmt.executeBatch();
            logger.debug("批量删除玩家变量完成，影响行数: " + Arrays.stream(results).sum());
        }
    }
    
    /**
     * 批量删除服务器变量
     */
    private void batchDeleteServerVariables(Connection connection, List<PersistenceTask> tasks) throws SQLException {
        String sql = "DELETE FROM server_variables WHERE variable_key = ?";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (PersistenceTask task : tasks) {
                if (task instanceof ServerVariableDeleteTask) {
                    ServerVariableDeleteTask deleteTask = (ServerVariableDeleteTask) task;
                    
                    stmt.setString(1, deleteTask.getVariableKey());
                    stmt.addBatch();
                }
            }
            
            int[] results = stmt.executeBatch();
            logger.debug("批量删除服务器变量完成，影响行数: " + Arrays.stream(results).sum());
        }
    }
    
    /**
     * 持久化工作线程
     */
    private void persistenceWorker() {
        logger.debug("持久化工作线程启动");
        
        while (running) {
            try {
                // 从队列获取任务（阻塞等待）
                PersistenceTask task = persistenceQueue.poll(1, TimeUnit.SECONDS);
                if (task != null) {
                    // 收集批次任务
                    List<PersistenceTask> batch = new ArrayList<>();
                    batch.add(task);
                    
                    // 尝试收集更多任务形成批次
                    long batchStartTime = System.currentTimeMillis();
                    while (batch.size() < maxBatchSize && 
                           (System.currentTimeMillis() - batchStartTime) < 100) {
                        PersistenceTask nextTask = persistenceQueue.poll(10, TimeUnit.MILLISECONDS);
                        if (nextTask != null) {
                            batch.add(nextTask);
                        } else {
                            break;
                        }
                    }
                    
                    // 执行批量持久化
                    try {
                        executeBatchPersistence(batch);
                    } catch (Exception e) {
                        logger.error("工作线程执行批量持久化失败", e);
                    }
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("持久化工作线程异常", e);
            }
        }
        
        logger.debug("持久化工作线程退出");
    }
    
    /**
     * 获取持久化统计信息
     */
    public PersistenceStats getPersistenceStats() {
        return new PersistenceStats(
                totalPersistenceOperations.get(),
                totalPersistedVariables.get(),
                failedPersistenceOperations.get(),
                lastPersistenceTime.get(),
                persistenceQueue.size(),
                memoryStorage.getAllDirtyData().size()
        );
    }
    
    /**
     * 持久化统计信息类
     */
    public static class PersistenceStats {
        private final long totalOperations;
        private final long totalPersistedVariables;
        private final long failedOperations;
        private final long lastPersistenceTime;
        private final int queueSize;
        private final int pendingDirtyData;
        
        public PersistenceStats(long totalOperations, long totalPersistedVariables, 
                               long failedOperations, long lastPersistenceTime,
                               int queueSize, int pendingDirtyData) {
            this.totalOperations = totalOperations;
            this.totalPersistedVariables = totalPersistedVariables;
            this.failedOperations = failedOperations;
            this.lastPersistenceTime = lastPersistenceTime;
            this.queueSize = queueSize;
            this.pendingDirtyData = pendingDirtyData;
        }
        
        // Getters
        public long getTotalOperations() { return totalOperations; }
        public long getTotalPersistedVariables() { return totalPersistedVariables; }
        public long getFailedOperations() { return failedOperations; }
        public long getLastPersistenceTime() { return lastPersistenceTime; }
        public int getQueueSize() { return queueSize; }
        public int getPendingDirtyData() { return pendingDirtyData; }
        
        public double getSuccessRate() {
            return totalOperations > 0 ? (1.0 - (double) failedOperations / totalOperations) * 100 : 100.0;
        }
        
        @Override
        public String toString() {
            return String.format("PersistenceStats{operations=%d, persisted=%d, failed=%d, " +
                               "queue=%d, pending=%d, success=%.1f%%}",
                    totalOperations, totalPersistedVariables, failedOperations,
                    queueSize, pendingDirtyData, getSuccessRate());
        }
    }
    
    /**
     * 持久化配置类
     */
    public static class PersistenceConfig {
        private int batchIntervalSeconds = 30;
        private int maxBatchSize = 1000;
        private double memoryPressureThreshold = 80.0;
        private int maxRetries = 3;
        private int queueSize = 10000;
        private int workerThreads = 2;
        
        // Getters and Setters
        public int getBatchIntervalSeconds() { return batchIntervalSeconds; }
        public PersistenceConfig setBatchIntervalSeconds(int batchIntervalSeconds) { 
            this.batchIntervalSeconds = batchIntervalSeconds; 
            return this;
        }
        
        public int getMaxBatchSize() { return maxBatchSize; }
        public PersistenceConfig setMaxBatchSize(int maxBatchSize) { 
            this.maxBatchSize = maxBatchSize; 
            return this;
        }
        
        public double getMemoryPressureThreshold() { return memoryPressureThreshold; }
        public PersistenceConfig setMemoryPressureThreshold(double memoryPressureThreshold) { 
            this.memoryPressureThreshold = memoryPressureThreshold; 
            return this;
        }
        
        public int getMaxRetries() { return maxRetries; }
        public PersistenceConfig setMaxRetries(int maxRetries) { 
            this.maxRetries = maxRetries; 
            return this;
        }
        
        public int getQueueSize() { return queueSize; }
        public PersistenceConfig setQueueSize(int queueSize) { 
            this.queueSize = queueSize; 
            return this;
        }
        
        public int getWorkerThreads() { return workerThreads; }
        public PersistenceConfig setWorkerThreads(int workerThreads) { 
            this.workerThreads = workerThreads; 
            return this;
        }
    }
}