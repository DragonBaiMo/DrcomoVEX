package cn.drcomo.model.structure;

/**
 * 变量类型枚举
 * 
 * 定义了变量的行为特征，包括静态、动态和周期性变量。
 * 这些类型可以组合使用，实现复杂的变量行为。
 * 
 * @author BaiMo
 */
public enum VariableType {
    
    /**
     * 静态变量
     * 值不会自动变化，仅通过手动操作修改
     */
    STATIC("静态变量", false, false),
    
    /**
     * 动态变量
     * 值通过表达式实时计算，支持占位符和内部变量引用
     */
    DYNAMIC("动态变量", true, false),
    
    /**
     * 周期性变量
     * 在指定时间间隔自动重置为初始值
     */
    PERIODIC("周期性变量", false, true),
    
    /**
     * 动态周期性变量
     * 同时具备动态计算和周期重置特性
     */
    DYNAMIC_PERIODIC("动态周期性变量", true, true);
    
    private final String displayName;
    private final boolean isDynamic;
    private final boolean isPeriodic;
    
    VariableType(String displayName, boolean isDynamic, boolean isPeriodic) {
        this.displayName = displayName;
        this.isDynamic = isDynamic;
        this.isPeriodic = isPeriodic;
    }
    
    /**
     * 获取显示名称
     */
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * 是否为动态变量
     */
    public boolean isDynamic() {
        return isDynamic;
    }
    
    /**
     * 是否为周期性变量
     */
    public boolean isPeriodic() {
        return isPeriodic;
    }
    
    /**
     * 是否为静态变量
     */
    public boolean isStatic() {
        return !isDynamic && !isPeriodic;
    }
    
    /**
     * 根据特征推导变量类型
     * 
     * @param hasDynamicExpression 是否包含动态表达式
     * @param hasCycle 是否配置了周期
     * @return 相应的变量类型
     */
    public static VariableType inferType(boolean hasDynamicExpression, boolean hasCycle) {
        if (hasDynamicExpression && hasCycle) {
            return DYNAMIC_PERIODIC;
        } else if (hasDynamicExpression) {
            return DYNAMIC;
        } else if (hasCycle) {
            return PERIODIC;
        } else {
            return STATIC;
        }
    }
    
    /**
     * 检查是否需要实时计算
     */
    public boolean needsRealTimeCalculation() {
        return isDynamic;
    }
    
    /**
     * 检查是否需要周期检查
     */
    public boolean needsPeriodicCheck() {
        return isPeriodic;
    }
    
    /**
     * 检查是否可以缓存
     * 动态变量的值需要实时计算，但可以短时间缓存结果
     */
    public boolean isCacheable() {
        return true; // 所有类型都可以缓存，只是缓存时间不同
    }
    
    /**
     * 获取推荐的缓存时间（毫秒）
     */
    public long getRecommendedCacheTime() {
        switch (this) {
            case STATIC:
                return 600000; // 10分钟
            case PERIODIC:
                return 300000; // 5分钟
            case DYNAMIC:
                return 30000;  // 30秒
            case DYNAMIC_PERIODIC:
                return 30000;  // 30秒
            default:
                return 60000;  // 1分钟
        }
    }
    
    /**
     * 获取类型描述
     */
    public String getDescription() {
        StringBuilder desc = new StringBuilder();
        
        if (isDynamic && isPeriodic) {
            desc.append("实时计算且定期重置");
        } else if (isDynamic) {
            desc.append("实时计算");
        } else if (isPeriodic) {
            desc.append("定期重置");
        } else {
            desc.append("静态存储");
        }
        
        return desc.toString();
    }
    
    @Override
    public String toString() {
        return displayName + " (" + getDescription() + ")";
    }
}