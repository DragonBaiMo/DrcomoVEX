package cn.drcomo.model.structure;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 变量定义类
 * 
 * 存储一个变量的完整定义信息，包括类型、约束、默认值和周期配置。
 * 这是系统中所有变量操作的“规则书”。
 * 
 * @author BaiMo
 */
public class Variable {
    
    private final String key;
    private final String name;
    private final String scope;
    private final ValueType valueType;
    private final VariableType variableType;
    private final String initial;
    private final String min;
    private final String max;
    private final String cycle;
    private final Limitations limitations;
    
    // 编译时计算的元数据
    private final boolean isDynamic;
    private final boolean isPeriodic;
    private final boolean hasConstraints;
    
    private Variable(Builder builder) {
        this.key = builder.key;
        this.name = builder.name;
        this.scope = builder.scope;
        this.valueType = builder.valueType;
        this.variableType = builder.variableType;
        this.initial = builder.initial;
        this.min = builder.min;
        this.max = builder.max;
        this.cycle = builder.cycle;
        this.limitations = builder.limitations;
        
        // 计算元数据
        this.isDynamic = variableType.isDynamic();
        this.isPeriodic = variableType.isPeriodic();
        this.hasConstraints = (min != null && !min.trim().isEmpty()) || 
                             (max != null && !max.trim().isEmpty()) ||
                             (limitations != null && limitations.hasAnyLimitation());
    }
    
    // Getter 方法
    public String getKey() {
        return key;
    }
    
    public String getName() {
        return name != null ? name : key;
    }
    
    public String getScope() {
        return scope;
    }
    
    public ValueType getValueType() {
        return valueType;
    }
    
    public VariableType getVariableType() {
        return variableType;
    }
    
    public String getInitial() {
        return initial;
    }
    
    public String getMin() {
        return min;
    }
    
    public String getMax() {
        return max;
    }
    
    public String getCycle() {
        return cycle;
    }
    
    public Limitations getLimitations() {
        return limitations;
    }
    
    public boolean isDynamic() {
        return isDynamic;
    }
    
    public boolean isPeriodic() {
        return isPeriodic;
    }
    
    public boolean hasConstraints() {
        return hasConstraints;
    }
    
    /**
     * 检查是否为全局变量
     */
    public boolean isGlobal() {
        return "global".equalsIgnoreCase(scope);
    }
    
    /**
     * 检查是否为玩家变量
     */
    public boolean isPlayerScoped() {
        return "player".equalsIgnoreCase(scope) || scope == null;
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
     */
    public boolean isCacheable() {
        return variableType.isCacheable();
    }
    
    /**
     * 获取推荐的缓存时间
     */
    public long getRecommendedCacheTime() {
        return variableType.getRecommendedCacheTime();
    }
    
    /**
     * 检查初始值是否包含动态表达式
     */
    public boolean hasInitialExpression() {
        return initial != null && (initial.contains("%") || initial.contains("${"));
    }
    
    /**
     * 检查约束是否包含动态表达式
     */
    public boolean hasConstraintExpressions() {
        return (min != null && (min.contains("%") || min.contains("${"))) ||
               (max != null && (max.contains("%") || max.contains("${")));
    }
    
    /**
     * 检查是否启用严格初始值模式
     */
    public boolean isStrictInitialMode() {
        return limitations != null && limitations.isStrictInitialMode();
    }
    
    /**
     * 检查初始值是否应该实时更新
     * 
     * @return true表示应该实时更新，false表示应该限制更新
     */
    public boolean shouldUpdateInitialValueRealTime() {
        // 如果启用严格初始值模式
        if (isStrictInitialMode()) {
            // 如果有周期配置，则按周期更新
            if (isPeriodic) {
                return false; // 只在周期重置时更新
            } else {
                // 如果没有周期配置，则只在第一次初始化时更新
                return false; // 只在首次获得变量时更新
            }
        }
        
        // 默认情况下，包含动态表达式的初始值应该实时更新
        return hasInitialExpression();
    }
    
    /**
     * 检查初始值是否应该在周期重置时更新
     * 
     * @return true表示应该在周期重置时更新
     */
    public boolean shouldUpdateInitialValueOnCycle() {
        return isStrictInitialMode() && isPeriodic;
    }
    
    /**
     * 检查初始值是否应该只在首次初始化时更新
     * 
     * @return true表示只在首次初始化时更新
     */
    public boolean shouldUpdateInitialValueOnceOnly() {
        return isStrictInitialMode() && !isPeriodic && hasInitialExpression();
    }
    
    /**
     * 检查是否需要依赖快照
     * 
     * @return true表示需要创建和使用依赖快照
     */
    public boolean needsDependencySnapshot() {
        return isStrictInitialMode() && hasInitialExpression();
    }
    
    /**
     * 检查是否应该使用快照值而不是实时计算
     * 
     * @return true表示应该优先使用快照值
     */
    public boolean shouldUseSnapshotValue() {
        return isStrictInitialMode() && hasInitialExpression();
    }
    
    /**
     * 获取变量类型描述
     */
    public String getTypeDescription() {
        return valueType.getDisplayName() + " - " + variableType.getDescription();
    }
    
    /**
     * 验证配置的合理性
     */
    public List<String> validate() {
        List<String> errors = new java.util.ArrayList<>();
        
        // 检查必填字段
        if (key == null || key.trim().isEmpty()) {
            errors.add("变量键名不能为空");
        }
        
        if (initial == null || initial.trim().isEmpty()) {
            errors.add("初始值不能为空");
        }
        
        // 检查约束配置的合理性
        if (valueType == ValueType.STRING) {
            if ((min != null && !min.trim().isEmpty() && !hasConstraintExpressions()) ||
                (max != null && !max.trim().isEmpty() && !hasConstraintExpressions())) {
                errors.add("警告: 字符串类型不支持 min/max 数值约束，将被忽略");
            }
        }
        
        // 检查周期配置
        if (isPeriodic && (cycle == null || cycle.trim().isEmpty())) {
            errors.add("周期性变量必须配置 cycle 字段");
        }
        
        return errors;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Variable variable = (Variable) o;
        return Objects.equals(key, variable.key) &&
               Objects.equals(scope, variable.scope);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(key, scope);
    }
    
    @Override
    public String toString() {
        return "Variable{" +
                "key='" + key + '\'' +
                ", name='" + name + '\'' +
                ", scope='" + scope + '\'' +
                ", valueType=" + valueType +
                ", variableType=" + variableType +
                ", isDynamic=" + isDynamic +
                ", isPeriodic=" + isPeriodic +
                '}';
    }
    
    /**
     * 变量构建器
     */
    public static class Builder {
        private String key;
        private String name;
        private String scope = "player"; // 默认为玩家作用域
        private ValueType valueType;
        private VariableType variableType;
        private String initial;
        private String min;
        private String max;
        private String cycle;
        private Limitations limitations;
        
        public Builder(String key) {
            this.key = key;
        }
        
        public Builder name(String name) {
            this.name = name;
            return this;
        }
        
        public Builder scope(String scope) {
            this.scope = scope != null ? scope : "player";
            return this;
        }
        
        public Builder valueType(ValueType valueType) {
            this.valueType = valueType;
            return this;
        }
        
        public Builder variableType(VariableType variableType) {
            this.variableType = variableType;
            return this;
        }
        
        public Builder initial(String initial) {
            this.initial = initial;
            return this;
        }
        
        public Builder min(String min) {
            this.min = min;
            return this;
        }
        
        public Builder max(String max) {
            this.max = max;
            return this;
        }
        
        public Builder cycle(String cycle) {
            this.cycle = cycle;
            return this;
        }
        
        public Builder limitations(Limitations limitations) {
            this.limitations = limitations;
            return this;
        }
        
        /**
         * 自动推导类型并构建变量
         */
        public Variable build() {
            // 自动推导 ValueType
            if (valueType == null && initial != null) {
                valueType = ValueType.inferFromValue(initial);
            }
            if (valueType == null) {
                valueType = ValueType.STRING; // 默认类型
            }
            
            // 自动推导 VariableType
            if (variableType == null) {
                boolean hasDynamicExpression = (initial != null && 
                    (initial.contains("%") || initial.contains("${")));
                boolean hasCycle = (cycle != null && !cycle.trim().isEmpty());
                variableType = VariableType.inferType(hasDynamicExpression, hasCycle);
            }
            
            return new Variable(this);
        }
    }
}