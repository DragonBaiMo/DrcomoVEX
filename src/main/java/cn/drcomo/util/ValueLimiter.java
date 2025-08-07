package cn.drcomo.util;

import cn.drcomo.model.structure.Limitations;
import cn.drcomo.model.structure.ValueType;
import cn.drcomo.model.structure.Variable;

import java.util.Arrays;

/**
 * 值限制工具类
 *
 * <p>用于根据 {@link Limitations} 对计算后的结果进行二次校验，
 * 若超出范围则尝试截断到合法边界。</p>
 */
public class ValueLimiter {

    /**
     * 根据限制条件调整值
     *
     * @param variable 变量定义
     * @param value    待调整的值
     * @return 调整后的值，若无法调整则返回 {@code null}
     */
    public static String apply(Variable variable, String value) {
        if (variable == null || value == null) {
            return null;
        }
        Limitations limitations = variable.getLimitations();
        if (limitations == null) {
            return null;
        }
        ValueType type = variable.getValueType();
        if (type == null) {
            type = ValueType.inferFromValue(value);
        }
        switch (type) {
            case INT:
            case DOUBLE:
                return limitNumber(type, value, limitations);
            case STRING:
                return limitString(value, limitations);
            case LIST:
                return limitList(value, limitations);
            default:
                return null;
        }
    }

    // 数值截断
    private static String limitNumber(ValueType type, String value, Limitations lim) {
        double num;
        try {
            num = Double.parseDouble(value);
        } catch (Exception e) {
            return null;
        }
        double min = lim.getMinValue() != null ? parseDouble(lim.getMinValue(), num) : num;
        double max = lim.getMaxValue() != null ? parseDouble(lim.getMaxValue(), num) : num;
        num = Math.max(min, Math.min(max, num));
        if (type == ValueType.INT) {
            return String.valueOf((int) num);
        }
        return String.valueOf(num);
    }

    // 字符串长度截断
    private static String limitString(String value, Limitations lim) {
        int minLen = lim.getMinLength() != null ? lim.getMinLength() : 0;
        int maxLen = lim.getMaxLength() != null ? lim.getMaxLength() : Integer.MAX_VALUE;
        if (value.length() < minLen) {
            return null;
        }
        if (value.length() > maxLen) {
            return value.substring(0, maxLen);
        }
        return value;
    }

    // 列表数量截断
    private static String limitList(String value, Limitations lim) {
        String[] items = value.split(",");
        int minCount = lim.getMinLength() != null ? lim.getMinLength() : 0;
        int maxCount = lim.getMaxLength() != null ? lim.getMaxLength() : Integer.MAX_VALUE;
        if (items.length < minCount) {
            return null;
        }
        if (items.length > maxCount) {
            return String.join(",", Arrays.asList(items).subList(0, maxCount));
        }
        return value;
    }

    private static double parseDouble(String text, double def) {
        try {
            return Double.parseDouble(text);
        } catch (Exception e) {
            return def;
        }
    }
}
