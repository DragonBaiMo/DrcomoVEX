package cn.drcomo.tasks;

import cn.drcomo.storage.VariableValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 针对全局变量数据库拉取同步中的删除保护逻辑进行测试。
 */
public class GlobalVariableSyncTaskTest {

    @Test
    void shouldApplyDelete_whenCurrentOlderAndClean_returnsTrue() {
        VariableValue value = new VariableValue("100", 100L, 100L);

        assertTrue(GlobalVariableSyncTask.shouldApplyDelete(value, 100L));
        assertTrue(GlobalVariableSyncTask.shouldApplyDelete(value, 101L));
    }

    @Test
    void shouldApplyDelete_whenCurrentDirty_returnsFalse() {
        VariableValue value = new VariableValue("100", 100L, 100L);
        value.markDirty();

        assertFalse(GlobalVariableSyncTask.shouldApplyDelete(value, 100L));
    }

    @Test
    void shouldApplyDelete_whenNoLastSeenTimestamp_returnsFalse() {
        VariableValue value = new VariableValue("100", 100L, 100L);

        assertFalse(GlobalVariableSyncTask.shouldApplyDelete(value, null));
    }
}
