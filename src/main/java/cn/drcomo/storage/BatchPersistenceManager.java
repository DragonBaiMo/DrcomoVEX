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
 * 优化后将重复逻辑抽取到私有方法，提高代码复用性和可维护性
 * 新增方法：
 *   - clearDirtyFlags：批量清除脏标记
 *   - handleRetriesOnFailure：统一处理持久化失败重试逻辑
 *   - submitAsyncOperations：简化 SQLite 异步操作提交
 *
 * 作者: BaiMo
 */
public class BatchPersistenceManager {

    // --- 成员变量 ---
    private final DebugUtil logger;
    private final HikariConnection database;
    private final VariableMemoryStorage memoryStorage;
    private final AsyncTaskManager asyncTaskManager;

    // 持久化配置
    private final int batchIntervalSeconds;
    private final int maxBatchSize;
    private final double memoryPressureThreshold;
    private final int maxRetries;

    // 持久化任务队列与线程池
    private final BlockingQueue<PersistenceTask> persistenceQueue;
    private final ExecutorService persistenceExecutor;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledPersistenceTask;

    // 统计信息
    private final AtomicLong totalPersistenceOperations = new AtomicLong(0);
    private final AtomicLong totalPersistedVariables = new AtomicLong(0);
    private final AtomicLong failedPersistenceOperations = new AtomicLong(0);
    private final AtomicLong lastPersistenceTime = new AtomicLong(0);

    // 运行状态
    private volatile boolean running = true;

    // --- 构造函数 ---
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

    // --- 公共方法 ---

    /**
     * 启动批量持久化管理器
     */
    public void start() {
        logger.info("启动批量持久化管理器...");
        // 启动工作线程
        for (int i = 0; i < 2; i++) {
            persistenceExecutor.submit(this::persistenceWorker);
        }
        // 启动定时批量持久化
        scheduledPersistenceTask = scheduler.scheduleAtFixedRate(
                this::scheduledPersistence,
                batchIntervalSeconds,
                batchIntervalSeconds,
                TimeUnit.SECONDS);
        logger.info("批量持久化管理器启动完成，间隔: " + batchIntervalSeconds + "秒");
    }

    /**
     * 停止批量持久化管理器
     */
    public void shutdown() {
        logger.info("正在关闭批量持久化管理器...");
        running = false;
        if (scheduledPersistenceTask != null) {
            scheduledPersistenceTask.cancel(false);
        }
        try {
            flushAllDirtyData().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("关闭时持久化数据失败", e);
        }
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
     * 批量持久化所有脏数据
     */
    public CompletableFuture<Void> flushAllDirtyData() {
        return CompletableFuture.runAsync(() -> {
            try {
                Map<String, DirtyFlag> allDirty = memoryStorage.getAllDirtyData();
                if (allDirty.isEmpty()) return;
                logger.debug("开始批量持久化，脏数据数量: " + allDirty.size());
                List<PersistenceTask> tasks = collectPersistenceTasks(allDirty);
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
                Map<String, DirtyFlag> dirty = memoryStorage.getPlayerDirtyData(playerId);
                if (dirty.isEmpty()) return;
                logger.debug("玩家退出，立即持久化数据: " + playerId + "，脏数据数量: " + dirty.size());
                List<PersistenceTask> tasks = collectPersistenceTasks(dirty);
                executeBatchPersistence(tasks);
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
                if (stats.getMemoryUsagePercent() < memoryPressureThreshold) return;
                logger.warn("内存压力触发持久化，当前使用率: " +
                        String.format("%.1f%%", stats.getMemoryUsagePercent()));
                List<PersistenceTask> tasks = collectPersistenceTasks(memoryStorage.getAllDirtyData());
                // 冷数据优先：按照等待时间降序
                tasks.sort(Comparator.comparingLong(
                        (PersistenceTask t) -> t.getDirtyFlag().getPendingDuration()).reversed());
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

    // --- 私有辅助方法 ---

    /**
     * 定时批量持久化
     */
    private void scheduledPersistence() {
        try {
            flushAllDirtyData().whenComplete((res, err) -> {
                if (err != null) {
                    logger.error("定时批量持久化失败", err);
                } else {
                    logger.debug("定时批量持久化完成");
                }
            });
        } catch (Exception e) {
            logger.error("定时批量持久化异常", e);
        }
    }

    /**
     * 工作线程主循环
     */
    private void persistenceWorker() {
        logger.debug("持久化工作线程启动");
        while (running) {
            try {
                PersistenceTask first = persistenceQueue.poll(1, TimeUnit.SECONDS);
                if (first == null) continue;
                List<PersistenceTask> batch = new ArrayList<>();
                batch.add(first);
                long start = System.currentTimeMillis();
                while (batch.size() < maxBatchSize &&
                        System.currentTimeMillis() - start < 100) {
                    PersistenceTask next = persistenceQueue.poll(10, TimeUnit.MILLISECONDS);
                    if (next == null) break;
                    batch.add(next);
                }
                try {
                    executeBatchPersistence(batch);
                } catch (Exception e) {
                    logger.error("工作线程执行批量持久化失败", e);
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
     * 收集持久化任务
     */
    private List<PersistenceTask> collectPersistenceTasks(Map<String, DirtyFlag> dirtyData) {
        List<PersistenceTask> tasks = new ArrayList<>();
        for (Map.Entry<String, DirtyFlag> e : dirtyData.entrySet()) {
            try {
                PersistenceTask task = createPersistenceTask(e.getKey(), e.getValue());
                if (task != null) tasks.add(task);
            } catch (Exception ex) {
                logger.error("创建持久化任务失败: " + e.getKey(), ex);
            }
        }
        return tasks;
    }

    /**
     * 统一执行批量持久化
     */
    private void executeBatchPersistence(List<PersistenceTask> tasks) {
        if (tasks.isEmpty()) return;
        totalPersistenceOperations.incrementAndGet();
        // 分组
        Map<Class<? extends PersistenceTask>, List<PersistenceTask>> byType =
                tasks.stream().collect(Collectors.groupingBy(PersistenceTask::getClass));
        if ("sqlite".equals(database.getDatabaseType())) {
            try {
                executeSQLiteBatchPersistence(byType, tasks);
            } catch (RuntimeException e) {
                handleRetriesOnFailure(tasks, e);
                throw e;
            }
        } else {
            try {
                executeMySQLBatchPersistence(byType, tasks);
            } catch (RuntimeException e) {
                handleRetriesOnFailure(tasks, e);
                throw e;
            }
        }
    }

    /**
     * 执行 SQLite 批量持久化（异步方式）
     */
    private void executeSQLiteBatchPersistence(
            Map<Class<? extends PersistenceTask>, List<PersistenceTask>> byType,
            List<PersistenceTask> allTasks) {

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        // 玩家更新
        submitAsyncOperations(byType.get(PlayerVariableUpdateTask.class),
                task -> database.executeUpdateAsync(
                        "INSERT OR REPLACE INTO player_variables " +
                                "(player_uuid, variable_key, value, created_at, updated_at, first_modified_at) VALUES (?,?,?,?,?,?)",
                        ((PlayerVariableUpdateTask) task).getPlayerId().toString(),
                        ((PlayerVariableUpdateTask) task).getVariableKey(),
                        ((PlayerVariableUpdateTask) task).getValue().getRawValue(), // 使用原始值（包含 strict 编码）
                        ((PlayerVariableUpdateTask) task).getValue().getCreatedAt(),
                        ((PlayerVariableUpdateTask) task).getValue().getLastModified(),
                        ((PlayerVariableUpdateTask) task).getValue().getFirstModifiedAt()),
                futures);
        // 服务器更新
        submitAsyncOperations(byType.get(ServerVariableUpdateTask.class),
                task -> database.executeUpdateAsync(
                        "INSERT OR REPLACE INTO server_variables " +
                                "(variable_key, value, created_at, updated_at, first_modified_at) VALUES (?,?,?,?,?)",
                        ((ServerVariableUpdateTask) task).getVariableKey(),
                        ((ServerVariableUpdateTask) task).getValue().getRawValue(), // 使用原始值（包含 strict 编码）
                        ((ServerVariableUpdateTask) task).getValue().getCreatedAt(),
                        ((ServerVariableUpdateTask) task).getValue().getLastModified(),
                        ((ServerVariableUpdateTask) task).getValue().getFirstModifiedAt()),
                futures);
        // 玩家删除
        submitAsyncOperations(byType.get(PlayerVariableDeleteTask.class),
                task -> database.deleteAsync(
                        "player_variables",
                        "player_uuid = ? AND variable_key = ?",
                        ((PlayerVariableDeleteTask) task).getPlayerId().toString(),
                        ((PlayerVariableDeleteTask) task).getVariableKey()),
                futures);
        // 服务器删除
        submitAsyncOperations(byType.get(ServerVariableDeleteTask.class),
                task -> database.deleteAsync(
                        "server_variables",
                        "variable_key = ?",
                        ((ServerVariableDeleteTask) task).getVariableKey()),
                futures);

        totalPersistedVariables.addAndGet(futures.size());
        // 异步等待完成并清除标记
        CompletableFuture<Void> allComplete = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .orTimeout(15, TimeUnit.SECONDS)  // 减少单次操作超时时间
            .thenRun(() -> {
                clearDirtyFlags(allTasks);
                logger.debug("SQLite 批量持久化完成，处理任务数: " + allTasks.size());
            })
            .exceptionally(throwable -> {
                logger.error("SQLite 批量持久化执行失败", throwable);
                // 发生异常时，部分数据可能已保存，但为安全起见不清除脏标记
                handleRetriesOnFailure(allTasks, new RuntimeException("批量持久化异常", throwable));
                return null;
            });
        
        // 同步等待 - 但时间更短，避免长时间阻塞
        try {
            allComplete.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("SQLite 批量持久化最终等待失败", e);
            throw new RuntimeException("SQLite 批量持久化最终等待失败", e);
        }
    }

    /**
     * 批量提交异步操作到 futures 列表
     */
    private <T extends PersistenceTask> void submitAsyncOperations(
            List<T> tasks,
            java.util.function.Function<T, CompletableFuture<Integer>> op,
            List<CompletableFuture<Void>> futures) {
        if (tasks == null || tasks.isEmpty()) return;
        for (T task : tasks) {
            futures.add(op.apply(task).thenApply(r -> null));
        }
    }

    /**
     * 执行 MySQL 批量持久化（同步事务方式）
     */
    private void executeMySQLBatchPersistence(
            Map<Class<? extends PersistenceTask>, List<PersistenceTask>> byType,
            List<PersistenceTask> allTasks) {

        try (Connection conn = database.getConnection()) {
            conn.setAutoCommit(false);
            // 玩家更新
            List<PersistenceTask> pu = byType.get(PlayerVariableUpdateTask.class);
            if (pu != null && !pu.isEmpty()) {
                batchUpdatePlayerVariables(conn, pu);
                totalPersistedVariables.addAndGet(pu.size());
            }
            // 服务器更新
            List<PersistenceTask> su = byType.get(ServerVariableUpdateTask.class);
            if (su != null && !su.isEmpty()) {
                batchUpdateServerVariables(conn, su);
                totalPersistedVariables.addAndGet(su.size());
            }
            // 玩家删除
            List<PersistenceTask> pd = byType.get(PlayerVariableDeleteTask.class);
            if (pd != null && !pd.isEmpty()) {
                batchDeletePlayerVariables(conn, pd);
                totalPersistedVariables.addAndGet(pd.size());
            }
            // 服务器删除
            List<PersistenceTask> sd = byType.get(ServerVariableDeleteTask.class);
            if (sd != null && !sd.isEmpty()) {
                batchDeleteServerVariables(conn, sd);
                totalPersistedVariables.addAndGet(sd.size());
            }
            conn.commit();
            clearDirtyFlags(allTasks);
            logger.debug("MySQL 批量持久化完成，处理任务数: " + allTasks.size());
        } catch (SQLException e) {
            logger.error("MySQL 批量持久化执行失败", e);
            throw new RuntimeException("MySQL 批量持久化执行失败", e);
        }
    }

    /**
     * 批量清除脏标记
     */
    private void clearDirtyFlags(List<PersistenceTask> tasks) {
        for (PersistenceTask t : tasks) {
            memoryStorage.clearDirtyFlag(t.getKey());
        }
    }

    /**
     * 统一处理失败重试逻辑
     */
    private void handleRetriesOnFailure(List<PersistenceTask> tasks, RuntimeException e) {
        failedPersistenceOperations.incrementAndGet();
        for (PersistenceTask t : tasks) {
            t.getDirtyFlag().incrementRetry();
            if (t.getDirtyFlag().hasExcessiveRetries(maxRetries)) {
                logger.error("任务重试次数过多，放弃持久化: " + t.getKey());
                memoryStorage.clearDirtyFlag(t.getKey());
            }
        }
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
            String varKey = key.substring("server:".length());
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

    // --- MySQL 批处理辅助方法 ---

    private void batchUpdatePlayerVariables(Connection conn, List<PersistenceTask> tasks) throws SQLException {
        String sql = "INSERT INTO player_variables " +
                "(player_uuid, variable_key, value, created_at, updated_at, first_modified_at) VALUES (?,?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE value = VALUES(value), updated_at = VALUES(updated_at)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (PersistenceTask t : tasks) {
                PlayerVariableUpdateTask u = (PlayerVariableUpdateTask) t;
                VariableValue v = u.getValue();
                stmt.setString(1, u.getPlayerId().toString());
                stmt.setString(2, u.getVariableKey());
                stmt.setString(3, v.getRawValue()); // 使用原始值（包含 strict 编码）
                stmt.setLong(4, v.getCreatedAt());
                stmt.setLong(5, v.getLastModified());
                stmt.setLong(6, v.getFirstModifiedAt());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private void batchUpdateServerVariables(Connection conn, List<PersistenceTask> tasks) throws SQLException {
        String sql = "INSERT INTO server_variables " +
                "(variable_key, value, created_at, updated_at, first_modified_at) VALUES (?,?,?,?,?) " +
                "ON DUPLICATE KEY UPDATE value = VALUES(value), updated_at = VALUES(updated_at)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (PersistenceTask t : tasks) {
                ServerVariableUpdateTask u = (ServerVariableUpdateTask) t;
                VariableValue v = u.getValue();
                stmt.setString(1, u.getVariableKey());
                stmt.setString(2, v.getRawValue()); // 使用原始值（包含 strict 编码）
                stmt.setLong(3, v.getCreatedAt());
                stmt.setLong(4, v.getLastModified());
                stmt.setLong(5, v.getFirstModifiedAt());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private void batchDeletePlayerVariables(Connection conn, List<PersistenceTask> tasks) throws SQLException {
        String sql = "DELETE FROM player_variables WHERE player_uuid = ? AND variable_key = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (PersistenceTask t : tasks) {
                PlayerVariableDeleteTask d = (PlayerVariableDeleteTask) t;
                stmt.setString(1, d.getPlayerId().toString());
                stmt.setString(2, d.getVariableKey());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private void batchDeleteServerVariables(Connection conn, List<PersistenceTask> tasks) throws SQLException {
        String sql = "DELETE FROM server_variables WHERE variable_key = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (PersistenceTask t : tasks) {
                ServerVariableDeleteTask d = (ServerVariableDeleteTask) t;
                stmt.setString(1, d.getVariableKey());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    // --- 嵌套类：统计信息 ---
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

        public long getTotalOperations() { return totalOperations; }
        public long getTotalPersistedVariables() { return totalPersistedVariables; }
        public long getFailedOperations() { return failedOperations; }
        public long getLastPersistenceTime() { return lastPersistenceTime; }
        public int getQueueSize() { return queueSize; }
        public int getPendingDirtyData() { return pendingDirtyData; }

        public double getSuccessRate() {
            return totalOperations > 0
                    ? (1.0 - (double) failedOperations / totalOperations) * 100
                    : 100.0;
        }

        @Override
        public String toString() {
            return String.format(
                    "PersistenceStats{operations=%d, persisted=%d, failed=%d, queue=%d, pending=%d, success=%.1f%%}",
                    totalOperations, totalPersistedVariables, failedOperations,
                    queueSize, pendingDirtyData, getSuccessRate());
        }
    }

    // --- 嵌套类：配置 ---
    public static class PersistenceConfig {
        private int batchIntervalSeconds = 30;
        private int maxBatchSize = 200;  // 减少批次大小，避免单次操作时间过长
        private double memoryPressureThreshold = 80.0;
        private int maxRetries = 3;
        private int queueSize = 10000;
        private int workerThreads = 4;  // 增加到4个工作线程，提升持久化并发能力

        public int getBatchIntervalSeconds() { return batchIntervalSeconds; }
        public PersistenceConfig setBatchIntervalSeconds(int seconds) {
            this.batchIntervalSeconds = seconds; return this;
        }
        public int getMaxBatchSize() { return maxBatchSize; }
        public PersistenceConfig setMaxBatchSize(int size) {
            this.maxBatchSize = size; return this;
        }
        public double getMemoryPressureThreshold() { return memoryPressureThreshold; }
        public PersistenceConfig setMemoryPressureThreshold(double t) {
            this.memoryPressureThreshold = t; return this;
        }
        public int getMaxRetries() { return maxRetries; }
        public PersistenceConfig setMaxRetries(int r) {
            this.maxRetries = r; return this;
        }
        public int getQueueSize() { return queueSize; }
        public PersistenceConfig setQueueSize(int size) {
            this.queueSize = size; return this;
        }
        public int getWorkerThreads() { return workerThreads; }
        public PersistenceConfig setWorkerThreads(int threads) {
            this.workerThreads = threads; return this;
        }
    }
}
