package cn.drcomo.tasks;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 针对周期窗口纯函数的单元测试，验证补偿与归一化逻辑。
 */
public class VariableCycleTaskTest {

    @Test
    void computePendingStarts_daily_threeDaysGap() {
        ZonedDateTime current = ZonedDateTime.of(2024, 1, 4, 0, 0, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime last = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"));

        List<ZonedDateTime> starts = VariableCycleTask.computePendingStarts("daily", current, last, 31);

        assertEquals(3, starts.size());
        assertEquals(current.minusDays(2), starts.get(0));
        assertEquals(current, starts.get(2));
    }

    @Test
    void computePendingStarts_minute_overLimitClampsToCurrent() {
        ZonedDateTime current = ZonedDateTime.of(2024, 1, 1, 10, 0, 0, 0, ZoneId.of("UTC"));
        ZonedDateTime last = current.minusMinutes(5);

        List<ZonedDateTime> starts = VariableCycleTask.computePendingStarts("minute", current, last, 2);

        assertEquals(1, starts.size());
        assertEquals(current, starts.get(0));
    }

    @Test
    void normalizeWeek_alignsToMonday() {
        // 2024-01-04 是周四，归一化应回到周一
        ZonedDateTime thursday = ZonedDateTime.of(2024, 1, 4, 12, 30, 0, 0, ZoneId.of("Asia/Shanghai"));
        ZonedDateTime normalized = VariableCycleTask.normalizeCycleStartPure("weekly", thursday);

        assertEquals(ChronoUnit.DAYS.between(normalized, thursday), 3);
        assertEquals(0, normalized.getHour());
        assertEquals(0, normalized.getMinute());
        assertEquals(0, normalized.getSecond());
        assertEquals(thursday.getZone(), normalized.getZone());
        assertTrue(normalized.getDayOfWeek().getValue() <= thursday.getDayOfWeek().getValue());
    }
}
