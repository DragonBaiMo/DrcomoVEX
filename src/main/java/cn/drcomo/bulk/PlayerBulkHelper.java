package cn.drcomo.bulk;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 玩家批量操作帮助工具。
 *
 * 封装通配解析、条件判断等通用逻辑，供指令与批量组件复用。
 * 所有方法均为静态方法，调用方无需实例化。
 */
public final class PlayerBulkHelper {

    /** 严格模式值前缀，需与存储层保持一致 */
    public static final String STRICT_PREFIX = "STRICT:";
    /** 严格模式值分隔符 */
    public static final char STRICT_SEPARATOR = ':';

    private PlayerBulkHelper() {
    }

    /**
     * 判断是否包含通配符。
     *
     * @param spec 原始变量匹配串
     * @return true 表示包含通配符
     */
    public static boolean isWildcard(String spec) {
        return spec != null && spec.contains("*");
    }

    /**
     * 判断是否包含条件过滤。
     *
     * @param spec 原始变量匹配串
     * @return true 表示包含条件表达式
     */
    public static boolean hasCondition(String spec) {
        return spec != null && spec.contains(":");
    }

    /**
     * 将通配表达式转换为正则表达式。
     *
     * @param glob 通配表达式
     * @return 正则 Pattern
     */
    public static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        sb.append('^');
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*': sb.append(".*"); break;
                case '.': sb.append("\\."); break;
                case '?': sb.append('.'); break;
                case '+': case '(': case ')': case '[': case ']': case '{': case '}': case '^': case '$': case '|': case '\\':
                    sb.append('\\').append(c); break;
                default: sb.append(c);
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    /**
     * 将数据库中严格模式存储的值还原为可读内容。
     *
     * @param raw 原始值
     * @return 还原后的值
     */
    public static String normalizeStoredValue(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        if (!raw.startsWith(STRICT_PREFIX)) {
            return raw;
        }
        int lastSep = raw.lastIndexOf(STRICT_SEPARATOR);
        if (lastSep <= STRICT_PREFIX.length()) {
            return raw;
        }
        return raw.substring(STRICT_PREFIX.length(), lastSep);
    }

    /**
     * 解析条件表达式。
     *
     * @param spec 原始变量匹配串
     * @return 条件对象
     */
    public static Optional<ValueCondition> parseCondition(String spec) {
        int idx = spec.lastIndexOf(':');
        if (idx < 0) {
            return Optional.empty();
        }
        String cond = spec.substring(idx + 1).trim();
        try {
            if (cond.startsWith(">=")) {
                return Optional.of(new ValueCondition(ValueCondition.Op.GE, Double.parseDouble(cond.substring(2))));
            }
            if (cond.startsWith("<=")) {
                return Optional.of(new ValueCondition(ValueCondition.Op.LE, Double.parseDouble(cond.substring(2))));
            }
            if (cond.startsWith("==")) {
                return Optional.of(new ValueCondition(ValueCondition.Op.EQ, Double.parseDouble(cond.substring(2))));
            }
            if (cond.startsWith("!=")) {
                return Optional.of(new ValueCondition(ValueCondition.Op.NE, Double.parseDouble(cond.substring(2))));
            }
            if (cond.startsWith(">")) {
                return Optional.of(new ValueCondition(ValueCondition.Op.GT, Double.parseDouble(cond.substring(1))));
            }
            if (cond.startsWith("<")) {
                return Optional.of(new ValueCondition(ValueCondition.Op.LT, Double.parseDouble(cond.substring(1))));
            }
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * 提取通配部分，去除条件表达式。
     *
     * @param spec 原始变量匹配串
     * @return 通配部分
     */
    public static String extractGlob(String spec) {
        int idx = spec.lastIndexOf(':');
        if (idx < 0) {
            return spec;
        }
        return spec.substring(0, idx);
    }

    /**
     * 判断目标值是否满足条件。
     *
     * @param value      目标值
     * @param condition  条件
     * @return true 表示满足
     */
    public static boolean matchCondition(String value, Optional<ValueCondition> condition) {
        if (condition.isEmpty()) {
            return true;
        }
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            double v = Double.parseDouble(value);
            ValueCondition c = condition.get();
            switch (c.op) {
                case GT:
                    return v > c.threshold;
                case GE:
                    return v >= c.threshold;
                case LT:
                    return v < c.threshold;
                case LE:
                    return v <= c.threshold;
                case EQ:
                    return v == c.threshold;
                case NE:
                    return v != c.threshold;
                default:
                    return false;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 批量值过滤条件对象。
     */
    public static final class ValueCondition {
        /** 运算类型 */
        public enum Op { GT, GE, LT, LE, EQ, NE }

        /** 运算类型 */
        public final Op op;
        /** 比较阈值 */
        public final double threshold;

        public ValueCondition(Op op, double threshold) {
            this.op = Objects.requireNonNull(op, "op");
            this.threshold = threshold;
        }
    }
}

