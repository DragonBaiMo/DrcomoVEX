package cn.drcomo.util;

import cn.drcomo.model.structure.Limitations;
import cn.drcomo.model.structure.EffectiveParams;
import cn.drcomo.model.structure.ValueType;
import cn.drcomo.model.structure.Variable;
import cn.drcomo.corelib.math.NumberUtil;

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

    /**
     * 根据参数组的有效参数调整值
     *
     * @param params 有效参数（包含 group 覆盖）
     * @param value  待调整的值
     * @return 调整后的值，若无法调整则返回 {@code null}
     */
    public static String apply(EffectiveParams params, String value) {
        if (params == null || value == null) {
            return null;
        }
        Limitations limitations = params.getLimitations();
        ValueType type = params.getValueType();
        if (type == null) {
            type = ValueType.inferFromValue(value);
        }
        String minOverride = params.getMin();
        String maxOverride = params.getMax();
        if (limitations == null && minOverride == null && maxOverride == null) {
            return null;
        }
        switch (type) {
            case INT:
            case DOUBLE:
                return limitNumber(type, value, limitations, minOverride, maxOverride);
            case STRING:
                return limitString(value, limitations, minOverride, maxOverride);
            case LIST:
                return limitList(value, limitations, minOverride, maxOverride);
            default:
                return null;
        }
    }

    // 数值截断
    private static String limitNumber(ValueType type, String value, Limitations lim) {
        if (type == ValueType.INT) {
            Integer num = NumberUtil.parseInt(value);
            if (num == null) {
                return null;
            }
            int min = lim != null && lim.getMinValue() != null
                    ? NumberUtil.parseInt(lim.getMinValue(), Integer.MIN_VALUE)
                    : Integer.MIN_VALUE;
            int max = lim != null && lim.getMaxValue() != null
                    ? NumberUtil.parseInt(lim.getMaxValue(), Integer.MAX_VALUE)
                    : Integer.MAX_VALUE;
            num = Math.max(min, Math.min(max, num));
            return String.valueOf(num);
        }
        Double num = NumberUtil.parseDouble(value);
        if (num == null) {
            return null;
        }
        double min = lim != null && lim.getMinValue() != null
                ? NumberUtil.parseDouble(lim.getMinValue(), -Double.MAX_VALUE)
                : -Double.MAX_VALUE;
        double max = lim != null && lim.getMaxValue() != null
                ? NumberUtil.parseDouble(lim.getMaxValue(), Double.MAX_VALUE)
                : Double.MAX_VALUE;
        num = Math.max(min, Math.min(max, num));
        return String.valueOf(num);
    }

    private static String limitNumber(ValueType type, String value, Limitations lim, String minOverride, String maxOverride) {
        if (type == ValueType.INT) {
            Integer num = NumberUtil.parseInt(value);
            if (num == null) {
                return null;
            }
            int min = minOverride != null ? NumberUtil.parseInt(minOverride, Integer.MIN_VALUE)
                    : (lim != null && lim.getMinValue() != null
                        ? NumberUtil.parseInt(lim.getMinValue(), Integer.MIN_VALUE)
                        : Integer.MIN_VALUE);
            int max = maxOverride != null ? NumberUtil.parseInt(maxOverride, Integer.MAX_VALUE)
                    : (lim != null && lim.getMaxValue() != null
                        ? NumberUtil.parseInt(lim.getMaxValue(), Integer.MAX_VALUE)
                        : Integer.MAX_VALUE);
            num = Math.max(min, Math.min(max, num));
            return String.valueOf(num);
        }
        Double num = NumberUtil.parseDouble(value);
        if (num == null) {
            return null;
        }
        double min = minOverride != null ? NumberUtil.parseDouble(minOverride, -Double.MAX_VALUE)
                : (lim != null && lim.getMinValue() != null
                    ? NumberUtil.parseDouble(lim.getMinValue(), -Double.MAX_VALUE)
                    : -Double.MAX_VALUE);
        double max = maxOverride != null ? NumberUtil.parseDouble(maxOverride, Double.MAX_VALUE)
                : (lim != null && lim.getMaxValue() != null
                    ? NumberUtil.parseDouble(lim.getMaxValue(), Double.MAX_VALUE)
                    : Double.MAX_VALUE);
        num = Math.max(min, Math.min(max, num));
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

    private static String limitString(String value, Limitations lim, String minOverride, String maxOverride) {
        int minLen = minOverride != null ? NumberUtil.parseInt(minOverride, 0)
                : (lim != null && lim.getMinLength() != null ? lim.getMinLength() : 0);
        int maxLen = maxOverride != null ? NumberUtil.parseInt(maxOverride, Integer.MAX_VALUE)
                : (lim != null && lim.getMaxLength() != null ? lim.getMaxLength() : Integer.MAX_VALUE);
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

    private static String limitList(String value, Limitations lim, String minOverride, String maxOverride) {
        String[] items = value.split(",");
        int minCount = minOverride != null ? NumberUtil.parseInt(minOverride, 0)
                : (lim != null && lim.getMinLength() != null ? lim.getMinLength() : 0);
        int maxCount = maxOverride != null ? NumberUtil.parseInt(maxOverride, Integer.MAX_VALUE)
                : (lim != null && lim.getMaxLength() != null ? lim.getMaxLength() : Integer.MAX_VALUE);
        if (items.length < minCount) {
            return null;
        }
        if (items.length > maxCount) {
            return String.join(",", Arrays.asList(items).subList(0, maxCount));
        }
        return value;
    }

    // 解析逻辑已整合至 NumberUtil
}
