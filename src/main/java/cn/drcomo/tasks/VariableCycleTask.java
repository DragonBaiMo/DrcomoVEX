package cn.drcomo.tasks;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.config.ConfigsManager;
import cn.drcomo.config.DataConfigManager;
import cn.drcomo.managers.RefactoredVariablesManager;
import cn.drcomo.model.structure.Variable;
import cn.drcomo.corelib.util.DebugUtil;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 周期变量检测任务
 *
 * 定期检查带有 cycle 配置的变量，按需重置其值。
 *
 * @author BaiMo
 */
public class VariableCycleTask {

    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final RefactoredVariablesManager variablesManager;
    private final ConfigsManager configsManager;
    private ScheduledFuture<?> task;

    public VariableCycleTask(
            DrcomoVEX plugin,
            DebugUtil logger,
            RefactoredVariablesManager variablesManager,
            ConfigsManager configsManager
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.variablesManager = variablesManager;
        this.configsManager = configsManager;
    }

    /**
     * 启动周期检测任务
     */
    public void start() {
        if (!configsManager.getMainConfig().getBoolean("cycle.enabled", true)) {
            logger.info("周期重置功能已禁用");
            return;
        }

        int interval = configsManager.getMainConfig().getInt("cycle.check-interval-minutes", 1);
        task = plugin.getAsyncTaskManager().scheduleAtFixedRate(
                this::checkCycles,
                0,
                interval,
                TimeUnit.MINUTES
        );
        logger.info("已启动周期变量检测任务，间隔: " + interval + " 分钟");
    }

    /**
     * 停止周期检测任务
     */
    public void stop() {
        if (task != null && !task.isCancelled()) {
            task.cancel(false);
            logger.info("周期变量检测任务已停止");
        }
    }

    /**
     * 检查并重置变量
     */
    private void checkCycles() {
        try {
            String zoneId = configsManager.getMainConfig().getString("cycle.timezone", "Asia/Shanghai");
            ZoneId zone = ZoneId.of(zoneId);
            ZonedDateTime now = ZonedDateTime.now(zone);

            checkAndReset("daily", now.truncatedTo(ChronoUnit.DAYS));
            checkAndReset("weekly", now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).truncatedTo(ChronoUnit.DAYS));
            checkAndReset("monthly", now.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS));
            checkAndReset("yearly", now.withDayOfYear(1).truncatedTo(ChronoUnit.DAYS));
        } catch (Exception e) {
            logger.error("周期变量检测任务异常", e);
        }
    }

    /**
     * 检查某个周期并执行重置
     */
    private void checkAndReset(String cycleType, ZonedDateTime cycleStart) {
        DataConfigManager dataConfig = configsManager.getDataConfigManager();
        long lastReset;
        synchronized (dataConfig) {
            lastReset = dataConfig.getLong("cycle.last-" + cycleType + "-reset", 0L);
        }

        long startMillis = cycleStart.toInstant().toEpochMilli();
        if (lastReset < startMillis) {
            resetVariables(cycleType.toUpperCase());
            synchronized (dataConfig) {
                dataConfig.updateCycleResetTime(cycleType, startMillis);
            }
            logger.info("已完成 " + cycleType + " 周期变量重置");
        }
    }

    /**
     * 重置指定周期的所有变量
     */
    private void resetVariables(String cycle) {
        Set<String> keys = variablesManager.getAllVariableKeys();
        for (String key : keys) {
            Variable variable = variablesManager.getVariableDefinition(key);
            if (variable == null) {
                continue;
            }
            String cfg = variable.getCycle();
            if (cfg == null || cfg.trim().isEmpty()) {
                continue;
            }
            if (cfg.contains(" ")) {
                // TODO: 支持 Cron 表达式周期重置
                continue;
            }
            if (cycle.equalsIgnoreCase(cfg)) {
                try {
                    if (variable.isGlobal()) {
                        plugin.getDatabase().executeUpdateAsync(
                                "DELETE FROM server_variables WHERE variable_key = ?",
                                key
                        ).join();
                    } else {
                        plugin.getDatabase().executeUpdateAsync(
                                "DELETE FROM player_variables WHERE variable_key = ?",
                                key
                        ).join();
                    }
                    variablesManager.invalidateAllCaches(key);
                    logger.debug("已重置变量: " + key);
                } catch (Exception e) {
                    logger.error("重置变量失败: " + key, e);
                }
            }
        }
    }
}
