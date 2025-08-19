package cn.drcomo.storage;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 增强的变量值对象
 * 
 * 包含值、时间戳、脏标记等元数据，用于内存优先的存储策略。
 * 支持脏数据追踪、访问统计和智能缓存管理。
 * 
 * @author BaiMo
 */
public class VariableValue {
    
    // 当前值
    private volatile String value;
    
    // 数据库中的原始值（用于检测变化）
    private volatile String originalValue;
    
    // 时间戳
    private volatile long lastModified;
    private volatile long lastAccessed;
    // 首次修改时间
    private volatile long firstModifiedAt;
    private final long createdAt;
    
    // 脏数据标记
    private volatile boolean isDirty;
    
    // 版本号控制（用于缓存验证）
    private final AtomicLong version = new AtomicLong(1);
    
    // 访问统计
    private final AtomicInteger accessCount = new AtomicInteger(0);
    private final AtomicLong totalAccessTime = new AtomicLong(0);
    
    // 内存占用估算（字节）
    private volatile int estimatedMemoryUsage;
    
    // strict 模式编码常量
    private static final String STRICT_PREFIX = "STRICT:";
    private static final String STRICT_SEPARATOR = ":";
    
    /**
     * 构造函数
     */
    public VariableValue(String value) {
        this.value = value;
        this.originalValue = value;
        long currentTime = System.currentTimeMillis();
        this.createdAt = currentTime;
        this.lastModified = currentTime;
        this.firstModifiedAt = currentTime;
        this.lastAccessed = currentTime;
        this.isDirty = false; // 新创建的值认为与数据库同步
        
        updateMemoryUsage();
    }
    
    /**
     * 从数据库加载的构造函数
     */
    public VariableValue(String value, long lastModified) {
        this(value, lastModified, lastModified);
    }

    /**
     * 从数据库加载的构造函数（带首次修改时间）
     */
    public VariableValue(String value, long lastModified, long firstModifiedAt) {
        this.value = value;
        this.originalValue = value;
        this.createdAt = System.currentTimeMillis();
        this.lastModified = lastModified;
        this.firstModifiedAt = firstModifiedAt;
        this.lastAccessed = System.currentTimeMillis();
        this.isDirty = false; // 从数据库加载的值认为已同步
        
        updateMemoryUsage();
    }
    
    
    /**
     * 获取当前值（返回原始存储值）
     */
    public String getValue() {
        updateAccess();
        return value;
    }
    
    /**
     * 获取原始存储的值（包括 strict 编码）
     */
    public String getRawValue() {
        updateAccess();
        return value;
    }
    
    /**
     * 设置值并标记为脏数据
     */
    public void setValue(String newValue) {
        if (newValue == null) {
            newValue = "";
        }
        
        // 只有值真正发生变化时才标记为脏
        if (!newValue.equals(this.value)) {
            this.value = newValue;
            markDirty();
            updateMemoryUsage();
        }
        updateAccess();
    }
    
    /**
     * 标记为脏数据
     */
    public void markDirty() {
        if (!this.isDirty) {
            this.isDirty = true;
            this.lastModified = System.currentTimeMillis();
            // 增加版本号
            this.version.incrementAndGet();
        }
    }
    
    /**
     * 清除脏标记（持久化后调用）
     */
    public void clearDirty() {
        this.isDirty = false;
        this.originalValue = this.value;
    }
    
    /**
     * 获取当前版本号
     */
    public long getVersion() {
        return version.get();
    }
    
    /**
     * 检查版本号是否匹配（用于缓存验证）
     */
    public boolean isVersionValid(long expectedVersion) {
        return version.get() == expectedVersion;
    }
    
    /**
     * 强制递增版本号（用于外部触发的变更）
     */
    public long incrementVersion() {
        this.lastModified = System.currentTimeMillis();
        return version.incrementAndGet();
    }
    
    /**
     * 检查是否为脏数据
     */
    public boolean isDirty() {
        return isDirty;
    }
    
    /**
     * 检查值是否发生了变化
     */
    public boolean hasChanged() {
        if (originalValue == null) {
            return value != null;
        }
        return !originalValue.equals(value);
    }
    
    /**
     * 更新访问记录
     */
    private void updateAccess() {
        this.lastAccessed = System.currentTimeMillis();
        this.accessCount.incrementAndGet();
    }
    
    /**
     * 更新内存使用量估算
     */
    private void updateMemoryUsage() {
        // 估算对象的内存占用
        int valueSize = value != null ? value.length() * 2 : 0; // UTF-16
        int originalValueSize = originalValue != null ? originalValue.length() * 2 : 0;
        
        // 基础对象开销 + 字符串内容 + 时间戳等基础字段
        this.estimatedMemoryUsage = 120 + valueSize + originalValueSize;
    }
    
    /**
     * 获取最后修改时间
     */
    public long getLastModified() {
        return lastModified;
    }
    
    /**
     * 获取最后访问时间
     */
    public long getLastAccessed() {
        return lastAccessed;
    }

    /**
     * 获取首次修改时间
     */
    public long getFirstModifiedAt() {
        return firstModifiedAt;
    }
    
    /**
     * 获取创建时间
     */
    public long getCreatedAt() {
        return createdAt;
    }
    
    // ======================== 严格初始值模式相关方法 ========================
    
    /**
     * 检查当前值是否为严格模式编码的值
     */
    public boolean isStrictEncoded() {
        return value != null && value.startsWith(STRICT_PREFIX);
    }
    
    /**
     * 解码严格模式的实际值
     */
    public String getActualValue() {
        if (!isStrictEncoded()) {
            return value;
        }
        
        String[] parts = value.split(STRICT_SEPARATOR, 3);
        if (parts.length >= 2) {
            return parts[1]; // 返回实际值部分
        }
        return value; // 格式异常时返回原始值
    }
    
    /**
     * 获取严格模式的计算时间戳
     */
    public long getStrictComputedAt() {
        if (!isStrictEncoded()) {
            return 0;
        }
        
        String[] parts = value.split(STRICT_SEPARATOR, 3);
        if (parts.length >= 3) {
            try {
                return Long.parseLong(parts[2]);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
    
    /**
     * 检查是否已完成严格模式计算
     */
    public boolean isStrictComputed() {
        return isStrictEncoded();
    }
    
    /**
     * 设置严格模式的值
     * @param actualValue 实际的计算值
     */
    public void setStrictValue(String actualValue) {
        long timestamp = System.currentTimeMillis();
        String encodedValue = STRICT_PREFIX + actualValue + STRICT_SEPARATOR + timestamp;
        setValue(encodedValue);
    }
    
    /**
     * 重置严格模式状态（转换为普通值）
     */
    public void resetStrictMode() {
        if (isStrictEncoded()) {
            String actualValue = getActualValue();
            setValue(actualValue); // 直接设置为实际值，移除 strict 编码
        }
    }
    
    /**
     * 检查是否应该使用严格模式的值
     */
    public boolean shouldUseStrictValue() {
        return isStrictComputed();
    }
    
    /**
     * 获取访问次数
     */
    public int getAccessCount() {
        return accessCount.get();
    }
    
    /**
     * 获取平均访问时间间隔
     */
    public long getAverageAccessInterval() {
        int count = accessCount.get();
        if (count <= 1) {
            return 0;
        }
        return (lastAccessed - createdAt) / count;
    }
    
    /**
     * 获取估算的内存使用量
     */
    public int getEstimatedMemoryUsage() {
        return estimatedMemoryUsage;
    }
    
    /**
     * 检查是否为热点数据（频繁访问）
     */
    public boolean isHotData() {
        long timeSinceCreation = System.currentTimeMillis() - createdAt;
        if (timeSinceCreation < 60000) { // 创建不到1分钟
            return accessCount.get() > 10;
        }
        
        // 每分钟访问超过5次认为是热点数据
        return (accessCount.get() * 60000.0 / timeSinceCreation) > 5.0;
    }
    
    /**
     * 检查是否为冷数据（长时间未访问）
     */
    public boolean isColdData(long coldThresholdMs) {
        return (System.currentTimeMillis() - lastAccessed) > coldThresholdMs;
    }
    
    /**
     * 获取数据生命周期阶段
     */
    public DataLifecycle getLifecycleStage() {
        long now = System.currentTimeMillis();
        long timeSinceLastAccess = now - lastAccessed;
        long timeSinceCreation = now - createdAt;
        
        if (timeSinceCreation < 60000) { // 1分钟内
            return DataLifecycle.NEW;
        } else if (isHotData()) {
            return DataLifecycle.HOT;
        } else if (timeSinceLastAccess > 1800000) { // 30分钟未访问
            return DataLifecycle.COLD;
        } else {
            return DataLifecycle.WARM;
        }
    }
    
    /**
     * 数据生命周期枚举
     */
    public enum DataLifecycle {
        NEW,    // 新创建的数据
        HOT,    // 热点数据（频繁访问）
        WARM,   // 温数据（正常访问）
        COLD    // 冷数据（长时间未访问）
    }
    
    /**
     * 克隆对象（用于快照）
     */
    public VariableValue clone() {
        VariableValue cloned = new VariableValue(this.value, this.lastModified, this.firstModifiedAt);
        cloned.originalValue = this.originalValue;
        cloned.isDirty = this.isDirty;
        cloned.accessCount.set(this.accessCount.get());
        cloned.version.set(this.version.get());
        return cloned;
    }
    
    @Override
    public String toString() {
        return String.format("VariableValue{value='%s', version=%d, isDirty=%b, accessCount=%d, lifecycle=%s}", 
                value, version.get(), isDirty, accessCount.get(), getLifecycleStage());
    }
}