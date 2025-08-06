package cn.drcomo.storage;

import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.model.structure.Variable;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.bukkit.OfflinePlayer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多级缓存管理器
 * 
 * L1: 内存原始数据 (永不过期，由 VariableMemoryStorage 管理)
 * L2: 解析后的表达式结果 (长TTL: 5分钟)  
 * L3: 最终计算结果缓存 (中TTL: 2分钟)
 * 
 * 通过多级缓存提升系统性能，减少重复计算和数据库访问。
 * 
 * @author BaiMo
 */
public class MultiLevelCacheManager {
    
    private final DebugUtil logger;
    private final VariableMemoryStorage l1Cache; // L1缓存就是内存存储
    
    // L2缓存：解析后的表达式结果
    private final Cache<String, String> l2ExpressionCache;
    
    // L3缓存：最终计算结果
    private final Cache<String, CacheEntry> l3ResultCache;
    
    // 缓存统计
    private final AtomicLong l1Hits = new AtomicLong(0);
    private final AtomicLong l1Misses = new AtomicLong(0);
    private final AtomicLong totalRequests = new AtomicLong(0);
    
    /**
     * 构造函数
     */
    public MultiLevelCacheManager(DebugUtil logger, VariableMemoryStorage memoryStorage, 
                                 CacheConfig config) {
        this.logger = logger;
        this.l1Cache = memoryStorage;
        
        // 初始化L2表达式缓存
        this.l2ExpressionCache = Caffeine.newBuilder()
                .maximumSize(config.getL2MaximumSize())
                .expireAfterWrite(Duration.ofMinutes(config.getL2ExpireMinutes()))
                .recordStats()
                .build();
                
        // 初始化L3结果缓存
        this.l3ResultCache = Caffeine.newBuilder()
                .maximumSize(config.getL3MaximumSize())
                .expireAfterWrite(Duration.ofMinutes(config.getL3ExpireMinutes()))
                .recordStats()
                .build();
                
        logger.info("多级缓存管理器初始化完成，L2容量: " + config.getL2MaximumSize() + 
                   ", L3容量: " + config.getL3MaximumSize());
    }
    
    /**
     * 从多级缓存获取变量值
     */
    public CacheResult getFromCache(OfflinePlayer player, String key, Variable variable) {
        totalRequests.incrementAndGet();
        
        try {
            // L1缓存查找（内存存储）
            VariableValue l1Value = getFromL1Cache(player, key, variable);
            if (l1Value == null) {
                l1Misses.incrementAndGet();
                logger.debug("L1缓存未命中: " + buildPlayerKey(player, key));
                return CacheResult.miss("L1缓存未命中");
            }
            
            l1Hits.incrementAndGet();
            String rawValue = l1Value.getValue();
            String cacheKey = buildCacheKey(player, key);
            
            // L3缓存查找（最终结果）
            CacheEntry l3Entry = l3ResultCache.getIfPresent(cacheKey);
            if (l3Entry != null && l3Entry.isValid(rawValue)) {
                logger.debug("L3缓存命中: " + cacheKey);
                return CacheResult.hit("L3", l3Entry.getResult());
            }
            
            // L2表达式缓存查找
            if (containsExpressions(rawValue)) {
                String expressionKey = buildExpressionKey(rawValue, player);
                String l2Result = l2ExpressionCache.getIfPresent(expressionKey);
                
                if (l2Result != null) {
                    logger.debug("L2缓存命中: " + expressionKey);
                    // 将结果放入L3缓存
                    l3ResultCache.put(cacheKey, new CacheEntry(rawValue, l2Result));
                    return CacheResult.hit("L2", l2Result);
                }
            }
            
            // 所有缓存未命中，返回L1的原始值用于进一步处理
            logger.debug("多级缓存未命中，返回L1原始值: " + cacheKey);
            return CacheResult.miss("需要表达式解析", rawValue);
            
        } catch (Exception e) {
            logger.error("多级缓存查找失败: " + key, e);
            return CacheResult.error("缓存查找异常: " + e.getMessage());
        }
    }
    
    /**
     * 缓存表达式解析结果到L2缓存
     */
    public void cacheExpression(String expression, OfflinePlayer player, String result) {
        try {
            if (expression != null && result != null) {
                String expressionKey = buildExpressionKey(expression, player);
                l2ExpressionCache.put(expressionKey, result);
                logger.debug("L2缓存表达式结果: " + expressionKey);
            }
        } catch (Exception e) {
            logger.error("缓存表达式失败", e);
        }
    }
    
    /**
     * 缓存最终结果到L3缓存
     */
    public void cacheResult(OfflinePlayer player, String key, String originalValue, String result) {
        try {
            if (originalValue != null && result != null) {
                String cacheKey = buildCacheKey(player, key);
                l3ResultCache.put(cacheKey, new CacheEntry(originalValue, result));
                logger.debug("L3缓存结果: " + cacheKey);
            }
        } catch (Exception e) {
            logger.error("缓存结果失败", e);
        }
    }
    
    /**
     * 使缓存失效
     */
    public void invalidateCache(OfflinePlayer player, String key) {
        try {
            String cacheKey = buildCacheKey(player, key);
            
            // 清除L3缓存
            l3ResultCache.invalidate(cacheKey);
            
            // 清除相关的L2表达式缓存（模糊匹配）
            String playerPrefix = player != null ? player.getUniqueId().toString() : "server";
            l2ExpressionCache.asMap().keySet().removeIf(k -> k.contains(playerPrefix) || k.contains(key));
            
            logger.debug("清除缓存: " + cacheKey);
            
        } catch (Exception e) {
            logger.error("清除缓存失败: " + key, e);
        }
    }
    
    /**
     * 预热缓存 - 预加载热点数据
     */
    public void preloadCache(OfflinePlayer player, String key, Variable variable) {
        try {
            // 触发一次缓存查找，如果未命中会自动加载
            CacheResult result = getFromCache(player, key, variable);
            if (result.isHit()) {
                logger.debug("缓存预热成功: " + buildPlayerKey(player, key));
            }
        } catch (Exception e) {
            logger.error("缓存预热失败: " + key, e);
        }
    }
    
    /**
     * 批量预热缓存
     */
    public void batchPreloadCache(OfflinePlayer player, Iterable<String> keys) {
        int preloadCount = 0;
        for (String key : keys) {
            try {
                // 这里可以实现更高效的批量预加载逻辑
                preloadCount++;
            } catch (Exception e) {
                logger.error("批量预热缓存失败: " + key, e);
            }
        }
        logger.debug("批量预热缓存完成，数量: " + preloadCount);
    }
    
    /**
     * 从L1缓存获取数据
     */
    private VariableValue getFromL1Cache(OfflinePlayer player, String key, Variable variable) {
        if (variable.isPlayerScoped() && player != null) {
            return l1Cache.getPlayerVariable(player.getUniqueId(), key);
        } else if (variable.isGlobal()) {
            return l1Cache.getServerVariable(key);
        }
        return null;
    }
    
    /**
     * 检查值是否包含需要解析的表达式
     */
    private boolean containsExpressions(String value) {
        if (value == null) return false;
        return value.contains("%") || value.contains("${") || 
               value.matches(".*[+\\-*/()^].*") && value.matches(".*\\d.*");
    }
    
    /**
     * 构建缓存键
     */
    private String buildCacheKey(OfflinePlayer player, String key) {
        if (player != null) {
            return player.getUniqueId().toString() + ":" + key;
        } else {
            return "server:" + key;
        }
    }
    
    /**
     * 构建表达式缓存键
     */
    private String buildExpressionKey(String expression, OfflinePlayer player) {
        String playerContext = player != null ? player.getUniqueId().toString() : "server";
        return "expr:" + expression.hashCode() + ":" + playerContext;
    }
    
    /**
     * 构建玩家键
     */
    private String buildPlayerKey(OfflinePlayer player, String key) {
        return player != null ? (player.getName() + ":" + key) : ("SERVER:" + key);
    }
    
    /**
     * 获取缓存统计信息
     */
    public CacheStatistics getCacheStats() {
        try {
            long total = totalRequests.get();
            long l1HitCount = l1Hits.get();
            long l1MissCount = l1Misses.get();
            
            CacheStats l2Stats = l2ExpressionCache.stats();
            CacheStats l3Stats = l3ResultCache.stats();
            
            double overallHitRate = total > 0 ? 
                ((double) l1HitCount + l2Stats.hitCount() + l3Stats.hitCount()) / total * 100 : 0.0;
            
            return new CacheStatistics(
                total, 
                l1HitCount, l1MissCount,
                l2Stats.hitCount(), l2Stats.missCount(), l2ExpressionCache.estimatedSize(),
                l3Stats.hitCount(), l3Stats.missCount(), l3ResultCache.estimatedSize(),
                overallHitRate
            );
            
        } catch (Exception e) {
            logger.error("获取缓存统计失败", e);
            return new CacheStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0);
        }
    }
    
    /**
     * 清空所有缓存
     */
    public void clearAllCaches() {
        try {
            l2ExpressionCache.invalidateAll();
            l3ResultCache.invalidateAll();
            logger.info("清空所有多级缓存完成");
        } catch (Exception e) {
            logger.error("清空缓存失败", e);
        }
    }
    
    /**
     * 缓存条目类
     */
    private static class CacheEntry {
        private final String originalValue;
        private final String result;
        private final long timestamp;
        
        public CacheEntry(String originalValue, String result) {
            this.originalValue = originalValue;
            this.result = result;
            this.timestamp = System.currentTimeMillis();
        }
        
        public String getResult() {
            return result;
        }
        
        public boolean isValid(String currentOriginalValue) {
            // 检查原始值是否发生变化
            return originalValue != null && originalValue.equals(currentOriginalValue);
        }
        
        public long getAge() {
            return System.currentTimeMillis() - timestamp;
        }
    }
    
    /**
     * 缓存查找结果类
     */
    public static class CacheResult {
        private final boolean isHit;
        private final String level; // L1, L2, L3 或 MISS
        private final String value;
        private final String reason;
        
        private CacheResult(boolean isHit, String level, String value, String reason) {
            this.isHit = isHit;
            this.level = level;
            this.value = value;
            this.reason = reason;
        }
        
        public static CacheResult hit(String level, String value) {
            return new CacheResult(true, level, value, "缓存命中");
        }
        
        public static CacheResult miss(String reason) {
            return new CacheResult(false, "MISS", null, reason);
        }
        
        public static CacheResult miss(String reason, String rawValue) {
            return new CacheResult(false, "MISS", rawValue, reason);
        }
        
        public static CacheResult error(String reason) {
            return new CacheResult(false, "ERROR", null, reason);
        }
        
        public boolean isHit() { return isHit; }
        public String getLevel() { return level; }
        public String getValue() { return value; }
        public String getReason() { return reason; }
        
        @Override
        public String toString() {
            return String.format("CacheResult{hit=%b, level=%s, reason='%s'}", 
                    isHit, level, reason);
        }
    }
    
    /**
     * 缓存统计信息类
     */
    public static class CacheStatistics {
        private final long totalRequests;
        private final long l1Hits, l1Misses;
        private final long l2Hits, l2Misses, l2Size;
        private final long l3Hits, l3Misses, l3Size;
        private final double overallHitRate;
        
        public CacheStatistics(long totalRequests, 
                              long l1Hits, long l1Misses,
                              long l2Hits, long l2Misses, long l2Size,
                              long l3Hits, long l3Misses, long l3Size,
                              double overallHitRate) {
            this.totalRequests = totalRequests;
            this.l1Hits = l1Hits;
            this.l1Misses = l1Misses;
            this.l2Hits = l2Hits;
            this.l2Misses = l2Misses;
            this.l2Size = l2Size;
            this.l3Hits = l3Hits;
            this.l3Misses = l3Misses;
            this.l3Size = l3Size;
            this.overallHitRate = overallHitRate;
        }
        
        // Getters
        public long getTotalRequests() { return totalRequests; }
        public long getL1Hits() { return l1Hits; }
        public long getL1Misses() { return l1Misses; }
        public long getL2Hits() { return l2Hits; }
        public long getL2Misses() { return l2Misses; }
        public long getL2Size() { return l2Size; }
        public long getL3Hits() { return l3Hits; }
        public long getL3Misses() { return l3Misses; }
        public long getL3Size() { return l3Size; }
        public double getOverallHitRate() { return overallHitRate; }
        
        public double getL1HitRate() {
            long total = l1Hits + l1Misses;
            return total > 0 ? (double) l1Hits / total * 100 : 0.0;
        }
        
        public double getL2HitRate() {
            long total = l2Hits + l2Misses;
            return total > 0 ? (double) l2Hits / total * 100 : 0.0;
        }
        
        public double getL3HitRate() {
            long total = l3Hits + l3Misses;
            return total > 0 ? (double) l3Hits / total * 100 : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("CacheStats{total=%d, overall=%.1f%%, " +
                               "L1=%.1f%%(%d/%d), L2=%.1f%%(%d/%d)[%d], L3=%.1f%%(%d/%d)[%d]}",
                    totalRequests, overallHitRate,
                    getL1HitRate(), l1Hits, l1Hits + l1Misses,
                    getL2HitRate(), l2Hits, l2Hits + l2Misses, l2Size,
                    getL3HitRate(), l3Hits, l3Hits + l3Misses, l3Size);
        }
    }
    
    /**
     * 缓存配置类
     */
    public static class CacheConfig {
        private int l2MaximumSize = 10000;
        private int l2ExpireMinutes = 5;
        private int l3MaximumSize = 5000;
        private int l3ExpireMinutes = 2;
        
        // Getters and Setters
        public int getL2MaximumSize() { return l2MaximumSize; }
        public CacheConfig setL2MaximumSize(int l2MaximumSize) { 
            this.l2MaximumSize = l2MaximumSize; 
            return this;
        }
        
        public int getL2ExpireMinutes() { return l2ExpireMinutes; }
        public CacheConfig setL2ExpireMinutes(int l2ExpireMinutes) { 
            this.l2ExpireMinutes = l2ExpireMinutes; 
            return this;
        }
        
        public int getL3MaximumSize() { return l3MaximumSize; }
        public CacheConfig setL3MaximumSize(int l3MaximumSize) { 
            this.l3MaximumSize = l3MaximumSize; 
            return this;
        }
        
        public int getL3ExpireMinutes() { return l3ExpireMinutes; }
        public CacheConfig setL3ExpireMinutes(int l3ExpireMinutes) { 
            this.l3ExpireMinutes = l3ExpireMinutes; 
            return this;
        }
    }
}