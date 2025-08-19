package cn.drcomo.storage;

import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.database.HikariConnection;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * 内存优先变量存储系统
 * 
 * 核心思想：内存作为主存储，数据库作为持久化备份
 * 所有变量数据优先存储在内存中，延迟写入数据库以减少I/O压力
 * 
 * @author BaiMo
 */
public class VariableMemoryStorage {
    
    private final DebugUtil logger;
    private final HikariConnection database;
    
    // 玩家变量存储: UUID -> 变量键 -> 变量值对象
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, VariableValue>> playerVariables;
    
    // 服务器全局变量存储: 变量键 -> 变量值对象
    private final ConcurrentHashMap<String, VariableValue> serverVariables;

    // 脏数据追踪器 - 记录需要持久化的数据
    private final ConcurrentHashMap<String, DirtyFlag> dirtyTracker;

    // 玩家变量首次修改时间索引: 变量键 -> 最早首次修改时间
    private final ConcurrentHashMap<String, Long> playerFirstModifiedIndex;
    
    // 内存使用监控
    private final AtomicLong memoryUsage = new AtomicLong(0);
    private static final long MAX_MEMORY_THRESHOLD = 256 * 1024 * 1024; // 256MB 默认限制
    private final long maxMemoryThreshold;
    
    // 读写锁保护关键操作
    private final ReadWriteLock memoryLock = new ReentrantReadWriteLock();
    
    // 统计信息
    private final AtomicLong totalOperations = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    
    /**
     * 构造函数
     */
    public VariableMemoryStorage(DebugUtil logger, HikariConnection database) {
        this(logger, database, MAX_MEMORY_THRESHOLD);
    }

    /**
     * 构造函数（指定内存限制）
     */
    public VariableMemoryStorage(DebugUtil logger, HikariConnection database, long maxMemoryThreshold) {
        this.logger = logger;
        this.database = database;
        this.maxMemoryThreshold = maxMemoryThreshold;
        this.playerVariables = new ConcurrentHashMap<>();
        this.serverVariables = new ConcurrentHashMap<>();
        this.dirtyTracker = new ConcurrentHashMap<>();
        this.playerFirstModifiedIndex = new ConcurrentHashMap<>();

        logger.info("内存存储系统初始化完成，最大内存限制: " + (maxMemoryThreshold / 1024 / 1024) + "MB");
    }
    
    /**
     * 获取玩家变量值
     */
    public VariableValue getPlayerVariable(UUID playerId, String key) {
        totalOperations.incrementAndGet();
        
        memoryLock.readLock().lock();
        try {
            ConcurrentHashMap<String, VariableValue> playerVars = playerVariables.get(playerId);
            if (playerVars != null) {
                VariableValue value = playerVars.get(key);
                if (value != null) {
                    cacheHits.incrementAndGet();
                    logger.debug("内存命中玩家变量: " + playerId + ":" + key);
                    return value;
                }
            }
            
            cacheMisses.incrementAndGet();
            return null;
        } finally {
            memoryLock.readLock().unlock();
        }
    }
    
    /**
     * 设置玩家变量值
     */
    public void setPlayerVariable(UUID playerId, String key, String value) {
        memoryLock.writeLock().lock();
        try {
            ConcurrentHashMap<String, VariableValue> playerVars = playerVariables.computeIfAbsent(
                playerId, k -> new ConcurrentHashMap<>());
            
            VariableValue currentValue;
            VariableValue oldValue = playerVars.get(key);
            if (oldValue != null) {
                // 更新现有值
                oldValue.setValue(value);
                currentValue = oldValue;
                if (oldValue.isDirty()) {
                    trackDirtyData(buildPlayerKey(playerId, key), DirtyFlag.Type.PLAYER_VARIABLE);
                }
                logger.debug("更新玩家变量: " + playerId + ":" + key + " = " + value);
            } else {
                // 创建新值
                VariableValue newValue = new VariableValue(value);
                newValue.markDirty(); // 新创建的值需要持久化
                playerVars.put(key, newValue);
                currentValue = newValue;

                // 更新内存使用统计
                updateMemoryUsage(newValue.getEstimatedMemoryUsage());

                // 追踪脏数据
                trackDirtyData(buildPlayerKey(playerId, key), DirtyFlag.Type.PLAYER_VARIABLE);

                logger.debug("创建玩家变量: " + playerId + ":" + key + " = " + value);
            }

            // 更新首次修改索引
            long first = currentValue.getFirstModifiedAt();
            playerFirstModifiedIndex.compute(key, (k, v) -> v == null || first < v ? first : v);

            // 检查内存压力
            checkMemoryPressure();
            
        } finally {
            memoryLock.writeLock().unlock();
        }
    }

    /**
     * 从持久化数据加载玩家变量
     */
    public void loadPlayerVariable(UUID playerId, String key, String value, long lastModified, long firstModifiedAt) {
        memoryLock.writeLock().lock();
        try {
            ConcurrentHashMap<String, VariableValue> playerVars = playerVariables.computeIfAbsent(
                    playerId, k -> new ConcurrentHashMap<>());

            VariableValue newValue = new VariableValue(value, lastModified, firstModifiedAt);
            playerVars.put(key, newValue);

            // 更新内存使用统计
            updateMemoryUsage(newValue.getEstimatedMemoryUsage());

            // 更新首次修改索引
            playerFirstModifiedIndex.compute(key, (k, v) -> v == null || firstModifiedAt < v ? firstModifiedAt : v);
        } finally {
            memoryLock.writeLock().unlock();
        }
    }

    /**
     * 获取服务器变量值
     */
    public VariableValue getServerVariable(String key) {
        totalOperations.incrementAndGet();
        
        memoryLock.readLock().lock();
        try {
            VariableValue value = serverVariables.get(key);
            if (value != null) {
                cacheHits.incrementAndGet();
                logger.debug("内存命中服务器变量: " + key);
                return value;
            }
            
            cacheMisses.incrementAndGet();
            return null;
        } finally {
            memoryLock.readLock().unlock();
        }
    }

    /**
     * 获取所有玩家中指定变量的最早首次修改时间
     *
     * 优先从内存索引查询，若索引不存在则回退到数据库并缓存结果。
     *
     * @param key 变量键
     * @return 最早首次修改时间，若不存在返回 null
     */
    public Long getPlayerFirstModifiedAt(String key) {
        // 先查索引
        Long indexed = playerFirstModifiedIndex.get(key);
        if (indexed != null) {
            return indexed;
        }

        // 索引缺失时回退到数据库
        try {
            String val = database
                    .queryValueAsync("SELECT MIN(first_modified_at) FROM player_variables WHERE variable_key = ?", key)
                    .join();
            if (val != null) {
                long first = Long.parseLong(val);
                playerFirstModifiedIndex.putIfAbsent(key, first);
                return first;
            }
        } catch (Exception e) {
            logger.error("查询玩家变量首次修改时间失败: " + key, e);
        }
        return null;
    }

    /**
     * 异步获取所有玩家中指定变量的最早首次修改时间
     *
     * 优先从内存索引返回，若缺失则异步查询数据库并缓存结果。
     *
     * @param key 变量键
     * @return CompletableFuture<Long> 最早首次修改时间，若不存在返回 null
     */
    public CompletableFuture<Long> getPlayerFirstModifiedAtAsync(String key) {
        Long indexed = playerFirstModifiedIndex.get(key);
        if (indexed != null) {
            return CompletableFuture.completedFuture(indexed);
        }

        return database
                .queryValueAsync("SELECT MIN(first_modified_at) FROM player_variables WHERE variable_key = ?", key)
                .thenApply(val -> {
                    try {
                        if (val != null) {
                            long first = Long.parseLong(val);
                            playerFirstModifiedIndex.putIfAbsent(key, first);
                            return first;
                        }
                    } catch (Exception parseEx) {
                        logger.error("解析首次修改时间失败: " + key + ", 值=" + val, parseEx);
                    }
                    return null;
                })
                .exceptionally(ex -> {
                    logger.error("异步查询玩家变量首次修改时间失败: " + key, ex);
                    return null;
                });
    }

    /**
     * 设置服务器变量值
     */
    public void setServerVariable(String key, String value) {
        memoryLock.writeLock().lock();
        try {
            VariableValue oldValue = serverVariables.get(key);
            if (oldValue != null) {
                // 更新现有值
                oldValue.setValue(value);
                if (oldValue.isDirty()) {
                    trackDirtyData(buildServerKey(key), DirtyFlag.Type.SERVER_VARIABLE);
                }
                logger.debug("更新服务器变量: " + key + " = " + value);
            } else {
                // 创建新值
                VariableValue newValue = new VariableValue(value);
                newValue.markDirty(); // 新创建的值需要持久化
                serverVariables.put(key, newValue);
                
                // 更新内存使用统计
                updateMemoryUsage(newValue.getEstimatedMemoryUsage());
                
                // 追踪脏数据
                trackDirtyData(buildServerKey(key), DirtyFlag.Type.SERVER_VARIABLE);
                
                logger.debug("创建服务器变量: " + key + " = " + value);
            }
            
            // 检查内存压力
            checkMemoryPressure();
            
        } finally {
            memoryLock.writeLock().unlock();
        }
    }

    /**
     * 从持久化数据加载服务器变量
     */
    public void loadServerVariable(String key, String value, long lastModified, long firstModifiedAt) {
        memoryLock.writeLock().lock();
        try {
            VariableValue newValue = new VariableValue(value, lastModified, firstModifiedAt);
            serverVariables.put(key, newValue);

            // 更新内存使用统计
            updateMemoryUsage(newValue.getEstimatedMemoryUsage());
        } finally {
            memoryLock.writeLock().unlock();
        }
    }
    
    /**
     * 删除玩家变量
     */
    public boolean removePlayerVariable(UUID playerId, String key) {
        memoryLock.writeLock().lock();
        try {
            ConcurrentHashMap<String, VariableValue> playerVars = playerVariables.get(playerId);
            if (playerVars != null) {
                VariableValue removedValue = playerVars.remove(key);
                if (removedValue != null) {
                    // 更新内存使用统计
                    updateMemoryUsage(-removedValue.getEstimatedMemoryUsage());
                    
                    // 标记为需要从数据库删除
                    trackDirtyData(buildPlayerKey(playerId, key), DirtyFlag.Type.PLAYER_VARIABLE_DELETE);
                    
                    logger.debug("删除玩家变量: " + playerId + ":" + key);
                    return true;
                }
            }
            return false;
        } finally {
            memoryLock.writeLock().unlock();
        }
    }
    
    /**
     * 删除服务器变量
     */
    public boolean removeServerVariable(String key) {
        memoryLock.writeLock().lock();
        try {
            VariableValue removedValue = serverVariables.remove(key);
            if (removedValue != null) {
                // 更新内存使用统计
                updateMemoryUsage(-removedValue.getEstimatedMemoryUsage());
                
                // 标记为需要从数据库删除
                trackDirtyData(buildServerKey(key), DirtyFlag.Type.SERVER_VARIABLE_DELETE);
                
                logger.debug("删除服务器变量: " + key);
                return true;
            }
            return false;
        } finally {
            memoryLock.writeLock().unlock();
        }
    }
    
    /**
     * 获取玩家的所有变量（用于批量操作）
     */
    public Map<String, VariableValue> getPlayerVariables(UUID playerId) {
        memoryLock.readLock().lock();
        try {
            ConcurrentHashMap<String, VariableValue> playerVars = playerVariables.get(playerId);
            if (playerVars != null) {
                return new HashMap<>(playerVars); // 返回副本避免并发修改
            }
            return Collections.emptyMap();
        } finally {
            memoryLock.readLock().unlock();
        }
    }
    
    /**
     * 获取所有服务器变量（用于批量操作）
     */
    public Map<String, VariableValue> getServerVariables() {
        memoryLock.readLock().lock();
        try {
            return new HashMap<>(serverVariables); // 返回副本避免并发修改
        } finally {
            memoryLock.readLock().unlock();
        }
    }
    
    /**
     * 获取所有脏数据
     */
    public Map<String, DirtyFlag> getAllDirtyData() {
        return new HashMap<>(dirtyTracker);
    }
    
    /**
     * 获取指定玩家的脏数据
     */
    public Map<String, DirtyFlag> getPlayerDirtyData(UUID playerId) {
        String prefix = "player:" + playerId + ":";
        return dirtyTracker.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
    
    /**
     * 清除脏数据标记（持久化完成后调用）
     */
    public void clearDirtyFlag(String key) {
        DirtyFlag flag = dirtyTracker.remove(key);
        if (flag != null) {
            // 同时清除对应变量的脏标记
            if (key.startsWith("player:")) {
                String[] parts = key.split(":", 3);
                if (parts.length == 3) {
                    UUID playerId = UUID.fromString(parts[1]);
                    String varKey = parts[2];
                    VariableValue value = getPlayerVariable(playerId, varKey);
                    if (value != null) {
                        value.clearDirty();
                    }
                }
            } else if (key.startsWith("server:")) {
                String varKey = key.substring(7);
                VariableValue value = getServerVariable(varKey);
                if (value != null) {
                    value.clearDirty();
                }
            }
            
            logger.debug("清除脏数据标记: " + key);
        }
    }
    
    /**
     * 清理玩家内存数据（玩家离线时调用）
     */
    public void cleanupPlayerData(UUID playerId, boolean preserveDirtyData) {
        memoryLock.writeLock().lock();
        try {
            ConcurrentHashMap<String, VariableValue> playerVars = playerVariables.get(playerId);
            if (playerVars != null) {
                if (!preserveDirtyData) {
                    // 计算释放的内存
                    long freedMemory = playerVars.values().stream()
                            .mapToLong(VariableValue::getEstimatedMemoryUsage)
                            .sum();
                    
                    // 移除玩家数据
                    playerVariables.remove(playerId);
                    updateMemoryUsage(-freedMemory);
                    
                    // 清理相关的脏数据标记
                    String prefix = "player:" + playerId + ":";
                    dirtyTracker.keySet().removeIf(key -> key.startsWith(prefix));
                    
                    logger.debug("完全清理玩家数据: " + playerId + "，释放内存: " + (freedMemory / 1024) + "KB");
                } else {
                    // 只清理非脏数据，保留需要持久化的数据
                    playerVars.entrySet().removeIf(entry -> !entry.getValue().isDirty());
                    logger.debug("部分清理玩家数据: " + playerId + "，保留脏数据");
                }
            }
        } finally {
            memoryLock.writeLock().unlock();
        }
    }
    
    /**
     * 获取内存使用统计
     */
    public MemoryStats getMemoryStats() {
        memoryLock.readLock().lock();
        try {
            int playerCount = playerVariables.size();
            int totalPlayerVariables = playerVariables.values().stream()
                    .mapToInt(Map::size)
                    .sum();
            int serverVariableCount = serverVariables.size();
            int dirtyDataCount = dirtyTracker.size();
            
            long currentMemory = memoryUsage.get();
            double memoryUsagePercent = (double) currentMemory / maxMemoryThreshold * 100;
            
            // 计算缓存命中率
            long totalOps = totalOperations.get();
            double hitRate = totalOps > 0 ? (double) cacheHits.get() / totalOps * 100 : 0;
            
            return new MemoryStats(
                    playerCount,
                    totalPlayerVariables,
                    serverVariableCount,
                    dirtyDataCount,
                    currentMemory,
                    memoryUsagePercent,
                    hitRate
            );
        } finally {
            memoryLock.readLock().unlock();
        }
    }
    
    /**
     * 追踪脏数据
     */
    private void trackDirtyData(String key, DirtyFlag.Type type) {
        dirtyTracker.put(key, new DirtyFlag(type, System.currentTimeMillis()));
    }
    
    /**
     * 更新内存使用统计
     */
    private void updateMemoryUsage(long delta) {
        memoryUsage.addAndGet(delta);
    }
    
    /**
     * 检查内存压力
     */
    private void checkMemoryPressure() {
        long currentUsage = memoryUsage.get();
        if (currentUsage > maxMemoryThreshold * 0.8) { // 80% 阈值
            logger.warn("内存使用率过高: " + (currentUsage * 100 / maxMemoryThreshold) + "%，当前使用: " + 
                       (currentUsage / 1024 / 1024) + "MB");
            
            if (currentUsage > maxMemoryThreshold * 0.95) { // 95% 阈值
                logger.error("内存使用率严重过高，可能需要紧急清理冷数据");
                // 这里可以触发紧急清理机制
                emergencyCleanup();
            }
        }
    }
    
    /**
     * 紧急内存清理
     */
    private void emergencyCleanup() {
        memoryLock.writeLock().lock();
        try {
            logger.warn("开始紧急内存清理...");
            
            long beforeCleanup = memoryUsage.get();
            int cleanedCount = 0;
            
            // 清理冷数据（30分钟未访问的非脏数据）
            long coldThreshold = 30 * 60 * 1000; // 30分钟
            
            // 清理玩家变量中的冷数据
            for (Map.Entry<UUID, ConcurrentHashMap<String, VariableValue>> playerEntry : playerVariables.entrySet()) {
                ConcurrentHashMap<String, VariableValue> playerVars = playerEntry.getValue();
                Iterator<Map.Entry<String, VariableValue>> iterator = playerVars.entrySet().iterator();
                
                while (iterator.hasNext()) {
                    Map.Entry<String, VariableValue> varEntry = iterator.next();
                    VariableValue value = varEntry.getValue();
                    
                    if (!value.isDirty() && value.isColdData(coldThreshold)) {
                        updateMemoryUsage(-value.getEstimatedMemoryUsage());
                        iterator.remove();
                        cleanedCount++;
                    }
                }
            }
            
            // 清理服务器变量中的冷数据
            Iterator<Map.Entry<String, VariableValue>> serverIterator = serverVariables.entrySet().iterator();
            while (serverIterator.hasNext()) {
                Map.Entry<String, VariableValue> entry = serverIterator.next();
                VariableValue value = entry.getValue();
                
                if (!value.isDirty() && value.isColdData(coldThreshold)) {
                    updateMemoryUsage(-value.getEstimatedMemoryUsage());
                    serverIterator.remove();
                    cleanedCount++;
                }
            }
            
            long afterCleanup = memoryUsage.get();
            long freedMemory = beforeCleanup - afterCleanup;
            
            logger.warn("紧急清理完成，清理变量数: " + cleanedCount + 
                       "，释放内存: " + (freedMemory / 1024 / 1024) + "MB");
                       
        } finally {
            memoryLock.writeLock().unlock();
        }
    }
    
    /**
     * 构建玩家变量键
     */
    private String buildPlayerKey(UUID playerId, String key) {
        return "player:" + playerId + ":" + key;
    }
    
    /**
     * 构建服务器变量键
     */
    private String buildServerKey(String key) {
        return "server:" + key;
    }
    
    /**
     * 清空所有缓存数据（配置重载时使用）
     */
    public void clearAllCaches() {
        memoryLock.writeLock().lock();
        try {
            int playerCount = playerVariables.size();
            int playerVarCount = playerVariables.values().stream()
                    .mapToInt(map -> map.size()).sum();
            int serverVarCount = serverVariables.size();
            int dirtyCount = dirtyTracker.size();
            
            // 清空所有数据
            playerVariables.clear();
            serverVariables.clear();
            dirtyTracker.clear();
            memoryUsage.set(0);
            
            // 重置统计信息
            totalOperations.set(0);
            cacheHits.set(0);
            cacheMisses.set(0);
            
            logger.info("清空L1内存缓存完成: " + 
                       playerCount + "个玩家, " +
                       playerVarCount + "个玩家变量, " + 
                       serverVarCount + "个服务器变量, " +
                       dirtyCount + "个脏数据标记");
                       
        } finally {
            memoryLock.writeLock().unlock();
        }
    }
    
    /**
     * 内存统计信息类
     */
    public static class MemoryStats {
        private final int playerCount;
        private final int totalPlayerVariables;
        private final int serverVariableCount;
        private final int dirtyDataCount;
        private final long memoryUsage;
        private final double memoryUsagePercent;
        private final double cacheHitRate;
        
        public MemoryStats(int playerCount, int totalPlayerVariables, int serverVariableCount, 
                          int dirtyDataCount, long memoryUsage, double memoryUsagePercent, double cacheHitRate) {
            this.playerCount = playerCount;
            this.totalPlayerVariables = totalPlayerVariables;
            this.serverVariableCount = serverVariableCount;
            this.dirtyDataCount = dirtyDataCount;
            this.memoryUsage = memoryUsage;
            this.memoryUsagePercent = memoryUsagePercent;
            this.cacheHitRate = cacheHitRate;
        }
        
        // Getters
        public int getPlayerCount() { return playerCount; }
        public int getTotalPlayerVariables() { return totalPlayerVariables; }
        public int getServerVariableCount() { return serverVariableCount; }
        public int getDirtyDataCount() { return dirtyDataCount; }
        public long getMemoryUsage() { return memoryUsage; }
        public double getMemoryUsagePercent() { return memoryUsagePercent; }
        public double getCacheHitRate() { return cacheHitRate; }
        
        @Override
        public String toString() {
            return String.format("MemoryStats{players=%d, playerVars=%d, serverVars=%d, dirty=%d, " +
                               "memory=%.1fMB(%.1f%%), hitRate=%.1f%%}",
                    playerCount, totalPlayerVariables, serverVariableCount, dirtyDataCount,
                    memoryUsage / 1024.0 / 1024.0, memoryUsagePercent, cacheHitRate);
        }
    }
}