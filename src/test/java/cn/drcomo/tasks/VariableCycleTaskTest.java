package cn.drcomo.tasks;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.config.ConfigsManager;
import cn.drcomo.corelib.async.AsyncTaskManager;
import cn.drcomo.corelib.config.YamlUtil;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.database.HikariConnection;
import cn.drcomo.managers.RefactoredVariablesManager;
import cn.drcomo.model.structure.ValueType;
import cn.drcomo.model.structure.Variable;
import cn.drcomo.model.structure.VariableType;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 针对 VariableCycleTask 的关键场景单元测试。
 *
 * 这些测试聚焦于周期重置在极端情况下的可靠性：
 * 1. 数据库未返回任何行（玩家离线、尚未持久化）时也应视为成功完成重置；
 * 2. 刷新过程中发生异常（模拟崩服）后下一轮应能够恢复并完成补偿；
 * 3. 发现未来时间戳时应自动矫正并完成补偿，避免更新时间指向未来。
 */
@ExtendWith(MockitoExtension.class)
public class VariableCycleTaskTest {

    @TempDir
    Path tempDir;

    @Mock
    private DrcomoVEX plugin;

    @Mock
    private DebugUtil logger;

    @Mock
    private RefactoredVariablesManager variablesManager;

    @Mock
    private ConfigsManager configsManager;

    @Mock
    private YamlUtil yamlUtil;

    @Mock
    private AsyncTaskManager asyncTaskManager;

    @Mock
    private HikariConnection database;

    private FileConfiguration mainConfig;
    private YamlConfiguration dataConfig;
    private TestableVariableCycleTask task;

    @BeforeEach
    void setUp() {
        // 初始化主配置
        mainConfig = new YamlConfiguration();
        mainConfig.set("cycle.enabled", true);
        mainConfig.set("cycle.check-interval-seconds", 60);
        mainConfig.set("cycle.initial-delay-seconds", 0);
        mainConfig.set("cycle.timezone", "Asia/Shanghai");
        mainConfig.set("cycle.db.max-concurrency", 2);
        mainConfig.set("cycle.db.timeout-millis", 3000L);
        mainConfig.set("cycle.db.player-delete-batch-size", 1000);

        dataConfig = new YamlConfiguration();

        File dataFolder = tempDir.toFile();

        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getAsyncTaskManager()).thenReturn(asyncTaskManager);
        lenient().when(plugin.getDatabase()).thenReturn(database);
        when(configsManager.getMainConfig()).thenReturn(mainConfig);

        lenient().when(database.isMySQL()).thenReturn(false);
        lenient().when(database.isSQLite()).thenReturn(true);

        when(yamlUtil.getConfig("data")).thenReturn(dataConfig);
        when(yamlUtil.getLong(ArgumentMatchers.eq("data"), anyString(), ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(1);
                    long def = invocation.getArgument(2);
                    return dataConfig.contains(path) ? dataConfig.getLong(path) : def;
                });
        doAnswer(invocation -> {
            String configName = invocation.getArgument(0);
            String path = invocation.getArgument(1);
            Object value = invocation.getArgument(2);
            if ("data".equals(configName)) {
                dataConfig.set(path, value);
            }
            return null;
        }).when(yamlUtil).setValue(anyString(), anyString(), any());

        doNothing().when(yamlUtil).copyYamlFile(anyString(), anyString());
        doNothing().when(yamlUtil).loadConfig("data");

        when(asyncTaskManager.submitAsync(any(Runnable.class))).thenAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return CompletableFuture.completedFuture(null);
        });

        doNothing().when(variablesManager).invalidateGlobalCaches(anyString());
        doNothing().when(variablesManager).removeVariableFromMemoryAndCache(anyString());
        doNothing().when(variablesManager).executeCycleActionsOnReset(any(), any());
        when(variablesManager.isInitialized()).thenReturn(true);

        task = new TestableVariableCycleTask(plugin, logger, variablesManager, configsManager, yamlUtil);
    }

    /**
     * 场景：玩家长期离线或数据尚未持久化导致数据库删除返回0行。
     * 期望：仍然视为成功并推进周期时间戳，同时清空内存缓存。
     */
    @Test
    void shouldCompleteResetWhenDeletionReturnsZeroRows() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        ZonedDateTime now = ZonedDateTime.of(2024, 3, 15, 10, 0, 0, 0, zone);
        task.setTimeSupplier(() -> now);

        Variable variable = new Variable.Builder("dailyVar")
                .scope("player")
                .valueType(ValueType.STRING)
                .variableType(VariableType.PERIODIC)
                .cycle("daily")
                .build();

        when(variablesManager.getAllVariableKeys())
                .thenAnswer(invocation -> new LinkedHashSet<>(Collections.singleton("dailyVar")));
        when(variablesManager.getVariableDefinition(anyString())).thenReturn(variable);
        when(variablesManager.getFirstModifiedAtAsync(false, "dailyVar"))
                .thenReturn(CompletableFuture.completedFuture(now.minusDays(3).toInstant().toEpochMilli()));

        task.planDeleteCounts(0);

        long previous = now.minusDays(1).truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli();
        dataConfig.set("cycle.last-daily-reset", previous);
        dataConfig.set("cycle.variable.dailyVar.last-reset-time", previous);

        runCheckCycles();

        long expected = now.truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli();
        long actualGlobal = dataConfig.getLong("cycle.last-daily-reset");
        long actualVariable = dataConfig.getLong("cycle.variable.dailyVar.last-reset-time");
        int dbCallCount = task.getDeletionCallCount();
        assertEquals(expected, actualGlobal,
                "应推进到当前周期起点，实际=" + actualGlobal + "，数据库调用次数=" + dbCallCount);
        assertEquals(expected, actualVariable,
                "变量最后重置时间应更新，实际=" + actualVariable);
        assertEquals(1, dbCallCount, "本周期应触发一次删除");
        assertEquals(1, task.getBatchDeletionCallCount(), "应执行一次批量删除");

        verify(variablesManager, times(1)).removeVariableFromMemoryAndCache("dailyVar");
        verify(variablesManager, times(1)).invalidateGlobalCaches("dailyVar");
        verify(variablesManager, times(1)).executeCycleActionsOnReset(variable, null);
    }

    /**
     * 场景：周期重置过程中数据库异常，模拟崩服后再次启动继续补偿。
     * 期望：第一次失败不会篡改时间戳，第二次恢复后完整补偿并更新记录。
     */
    @Test
    void shouldRecoverAfterFailureDuringReset() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        ZonedDateTime now = ZonedDateTime.of(2024, 6, 2, 9, 30, 0, 0, zone);
        task.setTimeSupplier(() -> now);

        Variable variable = new Variable.Builder("dailyVar")
                .scope("player")
                .valueType(ValueType.STRING)
                .variableType(VariableType.PERIODIC)
                .cycle("daily")
                .build();

        when(variablesManager.getAllVariableKeys())
                .thenAnswer(invocation -> new LinkedHashSet<>(Collections.singleton("dailyVar")));
        when(variablesManager.getVariableDefinition(anyString())).thenReturn(variable);
        when(variablesManager.getFirstModifiedAtAsync(false, "dailyVar"))
                .thenReturn(CompletableFuture.completedFuture(now.minusDays(5).toInstant().toEpochMilli()));

        task.planDeletionResults(false, false, false, false, false, false, false, false, false);
        task.setDeletionBehavior(() -> false);

        long previous = now.minusDays(3).truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli();
        dataConfig.set("cycle.last-daily-reset", previous);
        dataConfig.set("cycle.variable.dailyVar.last-reset-time", previous);

        // 第一次执行：失败
        runCheckCycles();

        assertEquals(previous, dataConfig.getLong("cycle.last-daily-reset"), "失败后应保持原有时间");
        verify(variablesManager, times(0)).removeVariableFromMemoryAndCache("dailyVar");
        assertEquals(9, task.getDeletionCallCount(), "第一次补偿应尝试三轮各三次删除");

        // 第二次执行：成功
        task.setDeletionBehavior(null);
        task.planDeletionResults();
        task.planDeleteCounts(0, 0, 0);
        runCheckCycles();

        long expected = now.truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli();
        assertEquals(expected, dataConfig.getLong("cycle.last-daily-reset"), "恢复后应补偿至当前周期");
        assertEquals(expected, dataConfig.getLong("cycle.variable.dailyVar.last-reset-time"));
        assertEquals(12, task.getDeletionCallCount(), "成功补偿应再追加三次删除");
        assertEquals(3, task.getBatchDeletionCallCount(), "成功补偿应触发三次批量删除");
        verify(variablesManager, times(3)).removeVariableFromMemoryAndCache("dailyVar");
        verify(variablesManager, times(3)).invalidateGlobalCaches("dailyVar");
        verify(variablesManager, times(3)).executeCycleActionsOnReset(variable, null);
    }

    /**
     * 场景：检测到未来时间戳（可能由于系统时间跳变或异常写入）。
     * 期望：自动回退并补偿，最终时间戳与当前周期保持一致。
     */
    @Test
    void shouldRectifyFutureTimestampAndCatchUp() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        ZonedDateTime now = ZonedDateTime.of(2024, 1, 8, 22, 15, 0, 0, zone);
        task.setTimeSupplier(() -> now);

        Variable variable = new Variable.Builder("dailyVar")
                .scope("player")
                .valueType(ValueType.STRING)
                .variableType(VariableType.PERIODIC)
                .cycle("daily")
                .build();

        when(variablesManager.getAllVariableKeys())
                .thenAnswer(invocation -> new LinkedHashSet<>(Collections.singleton("dailyVar")));
        when(variablesManager.getVariableDefinition(anyString())).thenReturn(variable);
        when(variablesManager.getFirstModifiedAtAsync(false, "dailyVar"))
                .thenReturn(CompletableFuture.completedFuture(now.minusDays(10).toInstant().toEpochMilli()));

        task.planDeleteCounts(0);

        long future = now.plusDays(1).truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli();
        dataConfig.set("cycle.last-daily-reset", future);
        dataConfig.set("cycle.variable.dailyVar.last-reset-time", future);

        runCheckCycles();

        long expected = now.truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli();
        assertEquals(expected, dataConfig.getLong("cycle.last-daily-reset"), "应自动纠正未来时间戳");
        assertEquals(expected, dataConfig.getLong("cycle.variable.dailyVar.last-reset-time"));
        assertEquals(1, task.getDeletionCallCount(), "纠正未来时间仅需一次删除");
        assertEquals(1, task.getBatchDeletionCallCount(), "应执行一次批量删除");
        verify(variablesManager, times(1)).removeVariableFromMemoryAndCache("dailyVar");
        verify(variablesManager, times(1)).invalidateGlobalCaches("dailyVar");
        verify(variablesManager, times(1)).executeCycleActionsOnReset(variable, null);
    }

    /**
     * 场景：全局变量的删除语句返回0行（可能由于服务端已提前清理）。
     * 期望：仍应视为成功并继续推进周周期。
     */
    @Test
    void shouldHandleGlobalDeletionWhenNoRowsAffected() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        ZonedDateTime now = ZonedDateTime.of(2024, 4, 15, 12, 0, 0, 0, zone);
        task.setTimeSupplier(() -> now);

        Variable variable = new Variable.Builder("weeklyGlobal")
                .scope("global")
                .valueType(ValueType.STRING)
                .variableType(VariableType.PERIODIC)
                .cycle("weekly")
                .build();

        when(variablesManager.getAllVariableKeys())
                .thenAnswer(invocation -> new LinkedHashSet<>(Collections.singleton("weeklyGlobal")));
        when(variablesManager.getVariableDefinition(anyString())).thenReturn(variable);
        when(variablesManager.getFirstModifiedAtAsync(true, "weeklyGlobal"))
                .thenReturn(CompletableFuture.completedFuture(now.minusWeeks(4).toInstant().toEpochMilli()));

        task.planDeleteCounts(0, 0);

        long previous = now.minusWeeks(2)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .truncatedTo(ChronoUnit.DAYS)
                .toInstant()
                .toEpochMilli();
        dataConfig.set("cycle.last-weekly-reset", previous);
        dataConfig.set("cycle.variable.weeklyGlobal.last-reset-time", previous);

        runCheckCycles();

        long expected = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .truncatedTo(ChronoUnit.DAYS)
                .toInstant()
                .toEpochMilli();
        assertEquals(expected, dataConfig.getLong("cycle.last-weekly-reset"), "应推进至本周起点");
        assertEquals(expected, dataConfig.getLong("cycle.variable.weeklyGlobal.last-reset-time"));
        assertEquals(2, task.getDeletionCallCount(), "两周补偿应触发两次删除");
        assertEquals(0, task.getBatchDeletionCallCount(), "全局变量不应走批量删除路径");
        assertEquals(2, task.getGlobalDeletionCallCount(), "应执行两次全局删除语句");

        verify(variablesManager, times(2)).removeVariableFromMemoryAndCache("weeklyGlobal");
        verify(variablesManager, times(2)).invalidateGlobalCaches("weeklyGlobal");
        verify(variablesManager, times(2)).executeCycleActionsOnReset(variable, null);
    }

    /**
     * 场景：玩家变量数据量巨大，需要多批次删除才能完成。
     * 期望：能够持续分批，直至批次删除数量小于阈值后成功退出。
     */
    @Test
    void shouldProcessPlayerDeletionAcrossMultipleBatches() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        ZonedDateTime now = ZonedDateTime.of(2024, 5, 20, 18, 0, 0, 0, zone);
        task.setTimeSupplier(() -> now);

        Variable variable = new Variable.Builder("dailyHeavyPlayer")
                .scope("player")
                .valueType(ValueType.STRING)
                .variableType(VariableType.PERIODIC)
                .cycle("daily")
                .build();

        when(variablesManager.getAllVariableKeys())
                .thenAnswer(invocation -> new LinkedHashSet<>(Collections.singleton("dailyHeavyPlayer")));
        when(variablesManager.getVariableDefinition(anyString())).thenReturn(variable);
        when(variablesManager.getFirstModifiedAtAsync(false, "dailyHeavyPlayer"))
                .thenReturn(CompletableFuture.completedFuture(now.minusDays(10).toInstant().toEpochMilli()));

        task.planDeleteCounts(1000, 1000, 500);

        long previous = now.minusDays(1).truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli();
        dataConfig.set("cycle.last-daily-reset", previous);
        dataConfig.set("cycle.variable.dailyHeavyPlayer.last-reset-time", previous);

        runCheckCycles();

        long expected = now.truncatedTo(ChronoUnit.DAYS).toInstant().toEpochMilli();
        assertEquals(expected, dataConfig.getLong("cycle.last-daily-reset"), "应完成当日补偿");
        assertEquals(expected, dataConfig.getLong("cycle.variable.dailyHeavyPlayer.last-reset-time"));
        assertEquals(1, task.getDeletionCallCount(), "仅当日补偿应删除一次");
        assertEquals(3, task.getBatchDeletionCallCount(), "应进行三次批量删除");

        verify(variablesManager, times(1)).removeVariableFromMemoryAndCache("dailyHeavyPlayer");
        verify(variablesManager, times(1)).invalidateGlobalCaches("dailyHeavyPlayer");
        verify(variablesManager, times(1)).executeCycleActionsOnReset(variable, null);
    }

    private void runCheckCycles() {
        try {
            Method method = VariableCycleTask.class.getDeclaredMethod("checkCycles");
            method.setAccessible(true);
            method.invoke(task);
        } catch (Exception e) {
            throw new RuntimeException("执行周期检测失败", e);
        }
    }

    private static class TestableVariableCycleTask extends VariableCycleTask {

        private Supplier<ZonedDateTime> timeSupplier;
        private final Deque<Boolean> plannedDeletionResults = new ArrayDeque<>();
        private final Deque<Integer> plannedDeleteCounts = new ArrayDeque<>();
        private Supplier<Boolean> deletionBehavior;
        private final AtomicInteger deletionCalls = new AtomicInteger();
        private final AtomicInteger batchDeletionCalls = new AtomicInteger();
        private final AtomicInteger globalDeletionCalls = new AtomicInteger();

        private TestableVariableCycleTask(
                DrcomoVEX plugin,
                DebugUtil logger,
                RefactoredVariablesManager variablesManager,
                ConfigsManager configsManager,
                YamlUtil yamlUtil
        ) {
            super(plugin, logger, variablesManager, configsManager, yamlUtil);
        }

        void setTimeSupplier(Supplier<ZonedDateTime> timeSupplier) {
            this.timeSupplier = timeSupplier;
        }

        void planDeletionResults(boolean... results) {
            plannedDeletionResults.clear();
            for (boolean r : results) {
                plannedDeletionResults.addLast(r);
            }
        }

        void planDeleteCounts(Integer... counts) {
            plannedDeleteCounts.clear();
            if (counts != null) {
                for (Integer c : counts) {
                    plannedDeleteCounts.addLast(c);
                }
            }
        }

        void setDeletionBehavior(Supplier<Boolean> behavior) {
            this.deletionBehavior = behavior;
        }

        int getDeletionCallCount() {
            return deletionCalls.get();
        }

        int getBatchDeletionCallCount() {
            return batchDeletionCalls.get();
        }

        int getGlobalDeletionCallCount() {
            return globalDeletionCalls.get();
        }

        @Override
        protected ZonedDateTime getCurrentTime(ZoneId zone) {
            if (timeSupplier != null) {
                return timeSupplier.get().withZoneSameInstant(zone);
            }
            return super.getCurrentTime(zone);
        }

        @Override
        protected boolean performDeletion(boolean isGlobal, String key) {
            deletionCalls.incrementAndGet();
            if (!plannedDeletionResults.isEmpty()) {
                return plannedDeletionResults.pollFirst();
            }
            if (deletionBehavior != null) {
                return deletionBehavior.get();
            }
            return super.performDeletion(isGlobal, key);
        }

        @Override
        protected Integer executeDeleteReturningCountHook(String sql, String key, Object... params) {
            batchDeletionCalls.incrementAndGet();
            if (!plannedDeleteCounts.isEmpty()) {
                return plannedDeleteCounts.pollFirst();
            }
            return super.executeDeleteReturningCountHook(sql, key, params);
        }

        @Override
        protected boolean executeDeleteWithTimeoutHook(String sql, String key, Object... params) {
            globalDeletionCalls.incrementAndGet();
            if (!plannedDeleteCounts.isEmpty()) {
                Integer count = plannedDeleteCounts.pollFirst();
                return count != null;
            }
            try {
                return super.executeDeleteWithTimeoutHook(sql, key, params);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
