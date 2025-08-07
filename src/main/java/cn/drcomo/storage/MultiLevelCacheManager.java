package cn.drcomo.storage;

import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.model.structure.Variable;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.bukkit.OfflinePlayer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

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
    
    // 变量依赖关系追踪
    private final ConcurrentHashMap<String, Set<String>> dependencyGraph = new ConcurrentHashMap<>();
    
    // 表达式模式匹配器
    private final Pattern variablePattern = Pattern.compile("\\$\\{([^}]+)\\}");
    private final Pattern placeholderPattern = Pattern.compile("%([^%]+)%");
    
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
            boolean hasExpr = containsExpressions(rawValue);
            boolean hasPlaceholder = containsPlaceholders(rawValue);
            
            // L3缓存查找（最终结果） - 带版本验证
            CacheEntry l3Entry = l3ResultCache.getIfPresent(cacheKey);
            if (l3Entry != null && l3Entry.isValid(rawValue, l1Value.getVersion())) {
                logger.debug("L3缓存命中（版本" + l1Value.getVersion() + "): " + cacheKey);
                return CacheResult.hit("L3", l3Entry.getResult());
            } else if (l3Entry != null) {
                // 缓存存在但版本不匹配，清除失效的缓存
                l3ResultCache.invalidate(cacheKey);
                logger.debug("版本不匹配，清除L3缓存: " + cacheKey + 
                          ", 缓存版本: " + l3Entry.getVersion() + 
                          ", 当前版本: " + l1Value.getVersion());
            }
            
            // L2表达式缓存查找（跳过PlaceholderAPI占位符）
            if (hasExpr && !hasPlaceholder) {
                String expressionKey = buildExpressionKey(rawValue, player);
                String l2Result = l2ExpressionCache.getIfPresent(expressionKey);
                
                if (l2Result != null) {
                    logger.debug("L2缓存命中: " + expressionKey);
                    // 将结果放入L3缓存（带版本号）
                    l3ResultCache.put(cacheKey, new CacheEntry(rawValue, l2Result, l1Value.getVersion()));
                    return CacheResult.hit("L2", l2Result);
                }
            } else if (hasPlaceholder) {
                logger.debug("跳过L2缓存（包含PlaceholderAPI占位符）: " + buildPlayerKey(player, key));
            }

            // 所有缓存未命中，返回L1的原始值用于进一步处理
            if (hasPlaceholder) {
                logger.debug("多级缓存未命中（包含占位符，将及时解析），版本" + l1Value.getVersion() + ": " + cacheKey);
            } else {
                logger.debug("多级缓存未命中，返回L1原始值（版本" + l1Value.getVersion() + "): " + cacheKey);
            }
            return CacheResult.miss("需要表达式解析", rawValue);
            
        } catch (Exception e) {
            logger.error("多级缓存查找失败: " + key, e);
            return CacheResult.error("缓存查找异常: " + e.getMessage());
        }
    }
    
    /**
     * 缓存表达式解析结果到L2缓存（优化版 - 跳过占位符表达式）
     */
    public void cacheExpression(String expression, OfflinePlayer player, String result) {
        try {
            if (expression != null && result != null && !containsPlaceholders(expression)) {
                String expressionKey = buildExpressionKey(expression, player);
                l2ExpressionCache.put(expressionKey, result);
                logger.debug("L2缓存表达式结果: " + expressionKey);
            } else if (containsPlaceholders(expression)) {
                logger.debug("跳过L2缓存（包含PlaceholderAPI占位符）: " + expression);
            }
        } catch (Exception e) {
            logger.error("缓存表达式失败", e);
        }
    }
    
    /**
     * 精确清除L2缓存中与特定变量相关的项
     */
    private void invalidateL2CacheForVariable(OfflinePlayer player, String key) {
        String playerContext = player != null ? player.getUniqueId().toString() : "server";
        
        // 清除直接相关的表达式缓存（更精确的匹配）
        l2ExpressionCache.asMap().keySet().removeIf(cacheKey -> {
            // 解析缓存键格式: "expr:hashCode:playerContext"
            String[] parts = cacheKey.split(":", 3);
            if (parts.length == 3 && "expr".equals(parts[0])) {
                return playerContext.equals(parts[2]);
            }
            return false;
        });
    }
    
    /**
     * 递归清除依赖变量的缓存
     */
    private void invalidateDependentCaches(OfflinePlayer player, String key) {
        // 寻找所有依赖此变量的其他变量
        String targetKey = key;
        
        dependencyGraph.entrySet().removeIf(entry -> {
            if (entry.getValue().contains(targetKey)) {
                String dependentCacheKey = entry.getKey();
                
                // 清除依赖变量的L3缓存
                l3ResultCache.invalidate(dependentCacheKey);
                
                // 解析出依赖变量的信息进行递归清理
                String[] keyParts = dependentCacheKey.split(":", 2);
                if (keyParts.length == 2) {
                    String dependentKey = keyParts[1];
                    invalidateL2CacheForVariable(player, dependentKey);
                }
                
                logger.debug("清除依赖变量缓存: " + dependentCacheKey);
                return true; // 移除已处理的依赖关系
            }
            return false;
        });
    }
    
    /**
     * 缓存最终结果到L3缓存
     */
    public void cacheResult(OfflinePlayer player, String key, String originalValue, String result) {
        try {
            if (originalValue != null && result != null && !containsPlaceholders(originalValue)) {
                String cacheKey = buildCacheKey(player, key);
                l3ResultCache.put(cacheKey, new CacheEntry(originalValue, result));
                logger.debug("L3缓存结果: " + cacheKey);
            } else if (containsPlaceholders(originalValue)) {
                logger.debug("跳过L3缓存（包含占位符，及时解析）: " + originalValue);
            }
        } catch (Exception e) {
            logger.error("缓存结果失败", e);
        }
    }
    
    /**
     * 使缓存失效（优化版 - 支持依赖关系追踪）
     */
    public void invalidateCache(OfflinePlayer player, String key) {
        try {
            String cacheKey = buildCacheKey(player, key);
            
            // 清除L3缓存
            l3ResultCache.invalidate(cacheKey);
            
            // 精确清除相关的L2表达式缓存
            invalidateL2CacheForVariable(player, key);
            
            // 递归清除依赖此变量的其他变量缓存
            invalidateDependentCaches(player, key);
            
            logger.debug("清除缓存及依赖项: " + cacheKey);
            
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
     * 检查表达式是否包含PlaceholderAPI占位符（%placeholder%）
     */
    private boolean containsPlaceholders(String value) {
        if (value == null) return false;
        return value.contains("%");
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
     * 清空所有缓存
     */
    public void clearAllCaches() {
        try {
            // 清空L1内存缓存
            if (l1Cache != null) {
                l1Cache.clearAllCaches();
                logger.debug("L1内存缓存已清空");
            }
            
            // 检查缓存对象是否还可用
            if (l2ExpressionCache != null) {
                l2ExpressionCache.invalidateAll();
                logger.debug("L2表达式缓存已清空");
            }
            
            if (l3ResultCache != null) {
                l3ResultCache.invalidateAll();
                logger.debug("L3结果缓存已清空");
            }
            
            // 清空依赖关系图
            dependencyGraph.clear();
            logger.debug("变量依赖关系图已清空");
            
            logger.info("清空所有多级缓存完成（含L1/L2/L3缓存和占位符优化）");
        } catch (IllegalStateException e) {
            // 插件关闭时可能发生的类加载器关闭异常
            if (e.getMessage() != null && e.getMessage().contains("zip file closed")) {
                logger.debug("插件正在关闭，跳过缓存清理操作");
            } else {
                logger.error("清空缓存失败: " + e.getMessage());
            }
        } catch (Exception e) {
            logger.error("清空缓存失败", e);
        }
    }
    
    /**
     * 缓存条目类（增强版 - 支持版本验证）
     */
    private static class CacheEntry {
        private final String originalValue;
        private final String result;
        private final long timestamp;
        private final long version; // 版本号支持
        
        // 旧的构造函数（兼容性）
        public CacheEntry(String originalValue, String result) {
            this(originalValue, result, 0L);
        }
        
        // 新的构造函数（带版本号）
        public CacheEntry(String originalValue, String result, long version) {
            this.originalValue = originalValue;
            this.result = result;
            this.timestamp = System.currentTimeMillis();
            this.version = version;
        }
        
        public String getResult() {
            return result;
        }
        
        public long getVersion() {
            return version;
        }
        
        // 旧的验证方法（兼容性）
        public boolean isValid(String currentOriginalValue) {
            return isValid(currentOriginalValue, 0L);
        }
        
        // 新的验证方法（带版本号验证）
        public boolean isValid(String currentOriginalValue, long currentVersion) {
            // 检查原始值是否发生变化
            boolean valueMatches = originalValue != null && originalValue.equals(currentOriginalValue);
            
            // 如果有版本号，也要检查版本是否匹配
            if (version > 0 && currentVersion > 0) {
                return valueMatches && (version == currentVersion);
            }
            
            // 如果没有版本号，只检查值
            return valueMatches;
        }
        
        public long getAge() {
            return System.currentTimeMillis() - timestamp;
        }
        
        @Override
        public String toString() {
            return String.format("CacheEntry{value='%s', version=%d, age=%dms}", 
                    originalValue, version, getAge());
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