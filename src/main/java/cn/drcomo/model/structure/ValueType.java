package cn.drcomo.model.structure;

/**
 * 变量值类型枚举
 * 
 * 定义了DrcomoVEX支持的所有变量值类型，包括基础类型和复合类型。
 * 支持智能类型推断和显式类型定义。
 * 
 * @author BaiMo
 */
public enum ValueType {
    
    /**
     * 整数类型
     * 范围: Long.MIN_VALUE 到 Long.MAX_VALUE
     */
    INT("整数", "int", "integer"),
    
    /**
     * 浮点数类型
     * 精度: 双精度浮点数
     */
    DOUBLE("浮点数", "double", "float", "number"),
    
    /**
     * 字符串类型
     * 支持任意长度的UTF-8文本
     */
    STRING("字符串", "string", "text", "str"),
    
    /**
     * 列表类型
     * 字符串列表，支持动态添加和删除
     */
    LIST("列表", "list", "array");
    
    private final String displayName;
    private final String[] aliases;
    
    ValueType(String displayName, String... aliases) {
        this.displayName = displayName;
        this.aliases = aliases;
    }
    
    /**
     * 获取显示名称
     */
    public String getDisplayName() {
        return displayName;
    }
    
    /**
     * 获取别名列表
     */
    public String[] getAliases() {
        return aliases;
    }
    
    /**
     * 是否为数值类型
     */
    public boolean isNumeric() {
        return this == INT || this == DOUBLE;
    }
    
    /**
     * 是否支持长度约束
     */
    public boolean supportsLengthConstraints() {
        return this == STRING || this == LIST;
    }
    
    /**
     * 是否支持数值约束
     */
    public boolean supportsNumericConstraints() {
        return this == INT || this == DOUBLE;
    }
    
    /**
     * 根据字符串解析类型
     * 支持类型名称和别名的大小写不敏感匹配
     */
    public static ValueType fromString(String typeStr) {
        if (typeStr == null || typeStr.trim().isEmpty()) {
            return null;
        }
        
        String normalized = typeStr.trim().toLowerCase();
        
        // 首先尝试匹配枚举名称
        try {
            return ValueType.valueOf(normalized.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            // 继续匹配别名
        }
        
        // 匹配别名
        for (ValueType type : values()) {
            for (String alias : type.aliases) {
                if (alias.equalsIgnoreCase(normalized)) {
                    return type;
                }
            }
        }
        
        return null;
    }
    
    /**
     * 根据初始值智能推断类型
     */
    public static ValueType inferFromValue(Object value) {
        if (value == null) {
            return STRING; // 默认为字符串类型
        }
        
        if (value instanceof String) {
            String str = (String) value;
            
            // 尝试解析为数字
            if (isInteger(str)) {
                return INT;
            } else if (isDouble(str)) {
                return DOUBLE;
            } else {
                return STRING;
            }
        }
        
        if (value instanceof Number) {
            if (value instanceof Integer || value instanceof Long) {
                return INT;
            } else {
                return DOUBLE;
            }
        }
        
        if (value instanceof Iterable) {
            return LIST;
        }
        
        // 默认为字符串
        return STRING;
    }
    
    /**
     * 检查字符串是否为整数
     */
    private static boolean isInteger(String str) {
        if (str == null || str.trim().isEmpty()) {
            return false;
        }
        
        try {
            Long.parseLong(str.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    /**
     * 检查字符串是否为浮点数
     */
    private static boolean isDouble(String str) {
        if (str == null || str.trim().isEmpty()) {
            return false;
        }
        
        try {
            Double.parseDouble(str.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    /**
     * 验证值是否符合当前类型
     */
    public boolean isValidValue(Object value) {
        if (value == null) {
            return true; // null值对所有类型都有效
        }
        
        switch (this) {
            case INT:
                if (value instanceof String) {
                    return isInteger((String) value);
                }
                return value instanceof Integer || value instanceof Long;
                
            case DOUBLE:
                if (value instanceof String) {
                    return isDouble((String) value);
                }
                return value instanceof Number;
                
            case STRING:
                return true; // 所有值都可以转换为字符串
                
            case LIST:
                return value instanceof Iterable || value instanceof String;
                
            default:
                return false;
        }
    }
    
    /**
     * 将值转换为当前类型
     */
    public Object convertValue(Object value) throws IllegalArgumentException {
        if (value == null) {
            return getDefaultValue();
        }
        
        switch (this) {
            case INT:
                if (value instanceof String) {
                    try {
                        return Long.parseLong(((String) value).trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("无法将 '" + value + "' 转换为整数");
                    }
                }
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
                throw new IllegalArgumentException("无法将 " + value.getClass().getSimpleName() + " 转换为整数");
                
            case DOUBLE:
                if (value instanceof String) {
                    try {
                        return Double.parseDouble(((String) value).trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("无法将 '" + value + "' 转换为浮点数");
                    }
                }
                if (value instanceof Number) {
                    return ((Number) value).doubleValue();
                }
                throw new IllegalArgumentException("无法将 " + value.getClass().getSimpleName() + " 转换为浮点数");
                
            case STRING:
                return value.toString();
                
            case LIST:
                if (value instanceof Iterable) {
                    return value;
                }
                // 将单个值包装为列表
                return java.util.Collections.singletonList(value.toString());
                
            default:
                throw new IllegalArgumentException("未知的值类型: " + this);
        }
    }
    
    /**
     * 获取类型的默认值
     */
    public Object getDefaultValue() {
        switch (this) {
            case INT:
                return 0L;
            case DOUBLE:
                return 0.0;
            case STRING:
                return "";
            case LIST:
                return new java.util.ArrayList<>();
            default:
                return null;
        }
    }
    
    @Override
    public String toString() {
        return displayName;
    }
}