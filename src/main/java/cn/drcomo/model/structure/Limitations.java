package cn.drcomo.model.structure;

import java.util.Objects;

/**
 * 变量限制配置类
 * 
 * 定义了变量的各种约束条件，包括数值范围、长度限制、
 * 安全检查和自定义验证规则。
 * 
 * @author BaiMo
 */
public class Limitations {
    
    // 数值约束
    private final String minValue;
    private final String maxValue;
    
    // 长度约束
    private final Integer minLength;
    private final Integer maxLength;
    
    // 安全限制
    private final Integer maxRecursionDepth;
    private final Integer maxExpressionLength;
    private final Boolean allowCircularReferences;
    
    // 性能限制
    private final Long maxCacheTime;
    private final Integer maxCacheSize;
    
    // 行为限制
    private final Boolean readOnly;
    private final Boolean persistable;
    private final Boolean exportable;
    
    private Limitations(Builder builder) {
        this.minValue = builder.minValue;
        this.maxValue = builder.maxValue;
        this.minLength = builder.minLength;
        this.maxLength = builder.maxLength;
        this.maxRecursionDepth = builder.maxRecursionDepth;
        this.maxExpressionLength = builder.maxExpressionLength;
        this.allowCircularReferences = builder.allowCircularReferences;
        this.maxCacheTime = builder.maxCacheTime;
        this.maxCacheSize = builder.maxCacheSize;
        this.readOnly = builder.readOnly;
        this.persistable = builder.persistable;
        this.exportable = builder.exportable;
    }
    
    // Getter 方法
    public String getMinValue() {
        return minValue;
    }
    
    public String getMaxValue() {
        return maxValue;
    }
    
    public Integer getMinLength() {
        return minLength;
    }
    
    public Integer getMaxLength() {
        return maxLength;
    }
    
    public Integer getMaxRecursionDepth() {
        return maxRecursionDepth;
    }
    
    public Integer getMaxExpressionLength() {
        return maxExpressionLength;
    }
    
    public Boolean getAllowCircularReferences() {
        return allowCircularReferences;
    }
    
    public Long getMaxCacheTime() {
        return maxCacheTime;
    }
    
    public Integer getMaxCacheSize() {
        return maxCacheSize;
    }
    
    public Boolean getReadOnly() {
        return readOnly;
    }
    
    public Boolean getPersistable() {
        return persistable;
    }
    
    public Boolean getExportable() {
        return exportable;
    }
    
    // 便捷检查方法
    public boolean hasValueConstraints() {
        return minValue != null || maxValue != null;
    }
    
    public boolean hasLengthConstraints() {
        return minLength != null || maxLength != null;
    }
    
    public boolean hasSecurityLimitations() {
        return maxRecursionDepth != null || maxExpressionLength != null || allowCircularReferences != null;
    }
    
    public boolean hasPerformanceLimitations() {
        return maxCacheTime != null || maxCacheSize != null;
    }
    
    public boolean hasBehaviorLimitations() {
        return readOnly != null || persistable != null || exportable != null;
    }
    
    public boolean hasAnyLimitation() {
        return hasValueConstraints() || hasLengthConstraints() || 
               hasSecurityLimitations() || hasPerformanceLimitations() || 
               hasBehaviorLimitations();
    }
    
    public boolean isReadOnly() {
        return readOnly != null && readOnly;
    }
    
    public boolean isPersistable() {
        return persistable == null || persistable; // 默认可持久化
    }
    
    public boolean isExportable() {
        return exportable == null || exportable; // 默认可导出
    }
    
    public boolean allowsCircularReferences() {
        return allowCircularReferences != null && allowCircularReferences;
    }
    
    /**
     * 验证数值是否在约束范围内
     */
    public boolean isValueInRange(double value) {
        if (minValue != null) {
            try {
                double min = Double.parseDouble(minValue);
                if (value < min) return false;
            } catch (NumberFormatException ignored) {
                // 如果不是数字，则跳过验证
            }
        }
        
        if (maxValue != null) {
            try {
                double max = Double.parseDouble(maxValue);
                if (value > max) return false;
            } catch (NumberFormatException ignored) {
                // 如果不是数字，则跳过验证
            }
        }
        
        return true;
    }
    
    /**
     * 验证长度是否在约束范围内
     */
    public boolean isLengthInRange(int length) {
        if (minLength != null && length < minLength) {
            return false;
        }
        
        if (maxLength != null && length > maxLength) {
            return false;
        }
        
        return true;
    }
    
    /**
     * 验证表达式长度
     */
    public boolean isExpressionLengthValid(String expression) {
        if (maxExpressionLength == null || expression == null) {
            return true;
        }
        return expression.length() <= maxExpressionLength;
    }
    
    /**
     * 获取有效的递归深度
     */
    public int getEffectiveRecursionDepth(int defaultDepth) {
        return maxRecursionDepth != null ? maxRecursionDepth : defaultDepth;
    }
    
    /**
     * 获取有效的缓存时间
     */
    public long getEffectiveCacheTime(long defaultTime) {
        return maxCacheTime != null ? maxCacheTime : defaultTime;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Limitations that = (Limitations) o;
        return Objects.equals(minValue, that.minValue) &&
               Objects.equals(maxValue, that.maxValue) &&
               Objects.equals(minLength, that.minLength) &&
               Objects.equals(maxLength, that.maxLength) &&
               Objects.equals(maxRecursionDepth, that.maxRecursionDepth) &&
               Objects.equals(maxExpressionLength, that.maxExpressionLength) &&
               Objects.equals(allowCircularReferences, that.allowCircularReferences) &&
               Objects.equals(maxCacheTime, that.maxCacheTime) &&
               Objects.equals(maxCacheSize, that.maxCacheSize) &&
               Objects.equals(readOnly, that.readOnly) &&
               Objects.equals(persistable, that.persistable) &&
               Objects.equals(exportable, that.exportable);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(minValue, maxValue, minLength, maxLength, 
                           maxRecursionDepth, maxExpressionLength, allowCircularReferences,
                           maxCacheTime, maxCacheSize, readOnly, persistable, exportable);
    }
    
    @Override
    public String toString() {
        return "Limitations{" +
                "minValue='" + minValue + '\'' +
                ", maxValue='" + maxValue + '\'' +
                ", minLength=" + minLength +
                ", maxLength=" + maxLength +
                ", readOnly=" + readOnly +
                ", persistable=" + persistable +
                '}';
    }
    
    /**
     * 限制条件构建器
     */
    public static class Builder {
        private String minValue;
        private String maxValue;
        private Integer minLength;
        private Integer maxLength;
        private Integer maxRecursionDepth;
        private Integer maxExpressionLength;
        private Boolean allowCircularReferences;
        private Long maxCacheTime;
        private Integer maxCacheSize;
        private Boolean readOnly;
        private Boolean persistable;
        private Boolean exportable;
        
        public Builder minValue(String minValue) {
            this.minValue = minValue;
            return this;
        }
        
        public Builder maxValue(String maxValue) {
            this.maxValue = maxValue;
            return this;
        }
        
        public Builder minLength(Integer minLength) {
            this.minLength = minLength;
            return this;
        }
        
        public Builder maxLength(Integer maxLength) {
            this.maxLength = maxLength;
            return this;
        }
        
        public Builder maxRecursionDepth(Integer maxRecursionDepth) {
            this.maxRecursionDepth = maxRecursionDepth;
            return this;
        }
        
        public Builder maxExpressionLength(Integer maxExpressionLength) {
            this.maxExpressionLength = maxExpressionLength;
            return this;
        }
        
        public Builder allowCircularReferences(Boolean allowCircularReferences) {
            this.allowCircularReferences = allowCircularReferences;
            return this;
        }
        
        public Builder maxCacheTime(Long maxCacheTime) {
            this.maxCacheTime = maxCacheTime;
            return this;
        }
        
        public Builder maxCacheSize(Integer maxCacheSize) {
            this.maxCacheSize = maxCacheSize;
            return this;
        }
        
        public Builder readOnly(Boolean readOnly) {
            this.readOnly = readOnly;
            return this;
        }
        
        public Builder persistable(Boolean persistable) {
            this.persistable = persistable;
            return this;
        }
        
        public Builder exportable(Boolean exportable) {
            this.exportable = exportable;
            return this;
        }
        
        public Limitations build() {
            return new Limitations(this);
        }
    }
    
    /**
     * 创建空的限制条件
     */
    public static Limitations empty() {
        return new Builder().build();
    }
    
    /**
     * 创建只读限制条件
     */
    public static Limitations readOnly() {
        return new Builder().readOnly(true).build();
    }
    
    /**
     * 创建数值范围限制条件
     */
    public static Limitations numericRange(String min, String max) {
        return new Builder().minValue(min).maxValue(max).build();
    }
    
    /**
     * 创建长度范围限制条件
     */
    public static Limitations lengthRange(Integer min, Integer max) {
        return new Builder().minLength(min).maxLength(max).build();
    }
}