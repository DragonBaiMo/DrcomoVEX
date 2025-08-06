package cn.drcomo.storage;

/**
 * 脏数据标记类
 * 
 * 用于追踪需要持久化到数据库的变量数据。
 * 记录数据的操作类型、创建时间等元信息。
 * 
 * @author BaiMo
 */
public class DirtyFlag {
    
    private final Type type;
    private final long createdAt;
    private volatile int retryCount;
    private volatile long lastRetryAt;
    
    /**
     * 脏数据操作类型
     */
    public enum Type {
        PLAYER_VARIABLE,        // 玩家变量更新/创建
        SERVER_VARIABLE,        // 服务器变量更新/创建
        PLAYER_VARIABLE_DELETE, // 玩家变量删除
        SERVER_VARIABLE_DELETE  // 服务器变量删除
    }
    
    /**
     * 构造函数
     */
    public DirtyFlag(Type type, long createdAt) {
        this.type = type;
        this.createdAt = createdAt;
        this.retryCount = 0;
        this.lastRetryAt = 0;
    }
    
    /**
     * 获取操作类型
     */
    public Type getType() {
        return type;
    }
    
    /**
     * 获取创建时间
     */
    public long getCreatedAt() {
        return createdAt;
    }
    
    /**
     * 获取重试次数
     */
    public int getRetryCount() {
        return retryCount;
    }
    
    /**
     * 获取最后重试时间
     */
    public long getLastRetryAt() {
        return lastRetryAt;
    }
    
    /**
     * 增加重试次数
     */
    public void incrementRetry() {
        this.retryCount++;
        this.lastRetryAt = System.currentTimeMillis();
    }
    
    /**
     * 检查是否为删除操作
     */
    public boolean isDeleteOperation() {
        return type == Type.PLAYER_VARIABLE_DELETE || type == Type.SERVER_VARIABLE_DELETE;
    }
    
    /**
     * 检查是否为玩家变量
     */
    public boolean isPlayerVariable() {
        return type == Type.PLAYER_VARIABLE || type == Type.PLAYER_VARIABLE_DELETE;
    }
    
    /**
     * 检查是否为服务器变量
     */
    public boolean isServerVariable() {
        return type == Type.SERVER_VARIABLE || type == Type.SERVER_VARIABLE_DELETE;
    }
    
    /**
     * 获取等待持久化的时间（毫秒）
     */
    public long getPendingDuration() {
        return System.currentTimeMillis() - createdAt;
    }
    
    /**
     * 检查是否需要紧急处理（等待时间过长）
     */
    public boolean isUrgent(long urgentThresholdMs) {
        return getPendingDuration() > urgentThresholdMs;
    }
    
    /**
     * 检查是否重试次数过多
     */
    public boolean hasExcessiveRetries(int maxRetries) {
        return retryCount >= maxRetries;
    }
    
    @Override
    public String toString() {
        return String.format("DirtyFlag{type=%s, pending=%dms, retries=%d}", 
                type, getPendingDuration(), retryCount);
    }
}