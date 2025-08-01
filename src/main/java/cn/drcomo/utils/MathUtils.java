package cn.drcomo.utils;

/**
 * 数学相关的简单工具方法集合。
 */
public final class MathUtils {

    private MathUtils() {
        // 工具类不应被实例化
    }

    /**
     * 对两个字符串形式的数字执行加法或减法。
     *
     * @param value1 第一个数值字符串
     * @param value2 第二个数值字符串
     * @param add    {@code true} 为加法，{@code false} 为减法
     * @return 计算结果
     * @throws NumberFormatException 当任一输入无法解析为数字时抛出
     */
    public static double getDoubleSum(String value1, String value2, boolean add) {
        double numericValue = Double.parseDouble(value1);
        return add ? Double.parseDouble(value2) + numericValue : Double.parseDouble(value2) - numericValue;
    }

    /**
     * 判断字符串是否可解析为数字。
     * <p>逻辑摘自 org.apache.commons.lang3.math.NumberUtils。</p>
     *
     * @param str 待判断的字符串
     * @return 若可解析为数字则返回 {@code true}
     */
    public static boolean isParsable(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        } else if (str.charAt(str.length() - 1) == '.') {
            return false;
        } else if (str.charAt(0) == '-') {
            return str.length() == 1 ? false : withDecimalsParsing(str, 1);
        } else {
            return withDecimalsParsing(str, 0);
        }
    }

    /**
     * 检查字符串是否只包含数字和最多一个小数点。
     *
     * @param str      待检测字符串
     * @param beginIdx 开始检查的索引位置
     * @return 是否通过校验
     */
    private static boolean withDecimalsParsing(String str, int beginIdx) {
        int decimalPoints = 0;

        for (int i = beginIdx; i < str.length(); ++i) {
            boolean isDecimalPoint = str.charAt(i) == '.';
            if (isDecimalPoint) {
                ++decimalPoints;
            }

            if (decimalPoints > 1) {
                return false;
            }

            if (!isDecimalPoint && !Character.isDigit(str.charAt(i))) {
                return false;
            }
        }

        return true;
    }
}
