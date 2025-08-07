package cn.drcomo.util;

import cn.drcomo.model.structure.Limitations;
import cn.drcomo.model.structure.Variable;
import cn.drcomo.model.structure.ValueType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ValueLimiter 工具类测试
 */
public class ValueLimiterTest {

    @Test
    public void testIntClamp() {
        Limitations lim = new Limitations.Builder().minValue("0").maxValue("100").build();
        Variable var = new Variable.Builder("test-int").valueType(ValueType.INT).initial("0").limitations(lim).build();
        String result = ValueLimiter.apply(var, "150");
        assertEquals("100", result);
    }

    @Test
    public void testStringTruncate() {
        Limitations lim = new Limitations.Builder().maxLength(5).build();
        Variable var = new Variable.Builder("test-str").valueType(ValueType.STRING).initial("").limitations(lim).build();
        String result = ValueLimiter.apply(var, "abcdef");
        assertEquals("abcde", result);
    }

    @Test
    public void testListTruncate() {
        Limitations lim = new Limitations.Builder().maxLength(3).build();
        Variable var = new Variable.Builder("test-list").valueType(ValueType.LIST).initial("").limitations(lim).build();
        String result = ValueLimiter.apply(var, "a,b,c,d,e");
        assertEquals("a,b,c", result);
    }

    @Test
    public void testStringTooShort() {
        Limitations lim = new Limitations.Builder().minLength(2).build();
        Variable var = new Variable.Builder("test-short").valueType(ValueType.STRING).initial("").limitations(lim).build();
        assertNull(ValueLimiter.apply(var, "a"));
    }
}
