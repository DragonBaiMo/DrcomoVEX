package cn.drcomo.tasks;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.config.ConfigsManager;
import cn.drcomo.config.DataConfigManager;
import cn.drcomo.managers.RefactoredVariablesManager;
import cn.drcomo.model.structure.Variable;
import cn.drcomo.corelib.util.DebugUtil;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.time.*;
import java.time.zone.ZoneRulesException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 周期变量检测任务
 *
 * 定期检查带有 cycle 配置的变量，按需重置其值。
 * 支持标准周期（DAILY, WEEKLY, MONTHLY, YEARLY）和Cron表达式。
 *
 * 优化说明：
 * 1. 提取重复逻辑到私有方法，如数据库删除、配置保存等。
 * 2. 保留所有public方法和字段签名不变。
 * 3. 按功能模块重排列，增强可读性与维护性。
 * 4. 补充必要注释，未使用逻辑置于末尾并注释。
 */
public class VariableCycleTask {

    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final RefactoredVariablesManager variablesManager;
    private final ConfigsManager configsManager;
    private final CronParser cronParser;
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
        this.cronParser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));
    }

    /** 启动周期检测任务 */
    public void start() {
        if (!isCycleEnabled()) {
            logger.info("周期重置功能已禁用");
            return;
        }
        int interval = getCheckInterval();
        task = plugin.getAsyncTaskManager().scheduleAtFixedRate(
                this::checkCycles, 0, interval, TimeUnit.MINUTES);
        logger.info("已启动周期变量检测任务，间隔: " + interval + " 分钟");
    }

    /** 停止周期检测任务 */
    public void stop() {
        if (task != null && !task.isCancelled()) {
            task.cancel(false);
            logger.info("周期变量检测任务已停止");
        }
    }

    /** 核心检查流程 */
    private void checkCycles() {
        ZoneId zone = getValidatedTimeZone();
        ZonedDateTime now = ZonedDateTime.now(zone);
        try {
            runSafeReset("daily", calculateDailyCycleStart(now));
            runSafeReset("weekly", calculateWeeklyCycleStart(now));
            runSafeReset("monthly", calculateMonthlyCycleStart(now));
            runSafeReset("yearly", calculateYearlyCycleStart(now));
            resetCronVariables(now);
        } catch (DateTimeException e) {
            logger.error("时间计算异常，请检查时区配置: " + zone.getId(), e);
            logBoundaryConditionInfo(now, zone);
        } catch (Exception e) {
            logger.error("周期变量检测任务执行异常", e);
        }
    }

    // ============ 标准周期重置 ============

    /** 安全执行重置流程 */
    private void runSafeReset(String cycleType, ZonedDateTime cycleStart) {
        new ResetOperation(cycleType, cycleStart).executeSafely();
    }

    /** 真正执行重置的类，封装原子性与回滚逻辑 */
    private class ResetOperation {
        private final String cycleType;
        private final long startMillis;
        private final String lockKey;
        private long originalLastReset;
        private boolean timeUpdated;
        private final List<String> resetList = new ArrayList<>();

        public ResetOperation(String cycleType, ZonedDateTime cycleStart) {
            this.cycleType = cycleType;
            this.startMillis = cycleStart.toInstant().toEpochMilli();
            this.lockKey = "cycle.last-" + cycleType + "-reset";
        }

        /** 对外统一调用方法，捕获异常并回滚 */
        public void executeSafely() {
            try {
                execute();
            } catch (Exception e) {
                logger.error("周期重置操作失败: " + cycleType, e);
                rollback();
            }
        }

        /** 执行重置流程 */
        private void execute() throws Exception {
            DataConfigManager dataConfig = configsManager.getDataConfigManager();
            synchronized (dataConfig) {
                if (!needReset(dataConfig)) return;
                createCheckpoint(dataConfig);
                performResets();
                updateTimestamp(dataConfig);
                clearCheckpoint(dataConfig);
                logger.info("安全重置完成 - " + cycleType + " 周期，重置了 " + resetList.size() + " 个变量");
            }
        }

        /** 判断是否需要重置 */
        private boolean needReset(DataConfigManager dataConfig) {
            originalLastReset = dataConfig.getLong(lockKey, 0L);
            if (originalLastReset >= startMillis) {
                logger.debug("跳过重置 - 已重置过：" + cycleType);
                return false;
            }
            long gap = startMillis - originalLastReset;
            if (gap > getDangerousThreshold(cycleType)) {
                logger.warn("长时间未运行，执行 " + cycleType + " 重置");
            }
            return true;
        }

        /** 执行具体重置 */
        private void performResets() {
            Set<String> keys = variablesManager.getAllVariableKeys();
            for (String key : keys) {
                Variable var = variablesManager.getVariableDefinition(key);
                if (var != null && cycleType.equalsIgnoreCase(var.getCycle())
                        && resetSingleVariableSafe(var, key)) {
                    resetList.add(key);
                }
            }
        }

        /** 创建崩服保护检查点 */
        private void createCheckpoint(DataConfigManager dataConfig) {
            dataConfig.getConfig().set("cycle.checkpoint-" + cycleType, System.currentTimeMillis());
            saveDataConfig(dataConfig, "创建重置检查点: " + cycleType);
        }

        /** 清理检查点 */
        private void clearCheckpoint(DataConfigManager dataConfig) {
            dataConfig.getConfig().set("cycle.checkpoint-" + cycleType, null);
            saveDataConfig(dataConfig, "清理重置检查点: " + cycleType);
        }

        /** 更新时间戳 */
        private void updateTimestamp(DataConfigManager dataConfig) throws Exception {
            dataConfig.updateCycleResetTime(cycleType, startMillis);
            timeUpdated = true;
            saveDataConfig(dataConfig, "更新时间戳: " + cycleType);
        }

        /** 回滚 */
        private void rollback() {
            try {
                DataConfigManager dataConfig = configsManager.getDataConfigManager();
                synchronized (dataConfig) {
                    if (timeUpdated && originalLastReset >= 0) {
                        dataConfig.updateCycleResetTime(cycleType, originalLastReset);
                    }
                    dataConfig.getConfig().set("cycle.rollback-" + cycleType, System.currentTimeMillis());
                    saveDataConfig(dataConfig, "回滚检查点: " + cycleType);
                }
            } catch (Exception ex) {
                logger.error("回滚操作失败: " + cycleType, ex);
            }
        }

        /** 私有工具：保存配置并记录 */
        private void saveDataConfig(DataConfigManager dataConfig, String action) {
            try {
                dataConfig.getConfig().save(plugin.getDataFolder().toPath().resolve("data.yml").toFile());
                logger.debug(action);
            } catch (Exception e) {
                throw new RuntimeException("无法保存配置：" + action, e);
            }
        }

        /** 变量安全重置（含重试） */
        private boolean resetSingleVariableSafe(Variable var, String key) {
            for (int i = 1; i <= 3; i++) {
                if (deleteVariableFromDb(var.isGlobal(), key)) {
                    variablesManager.invalidateAllCaches(key);
                    logger.debug("重置变量成功: " + key + " (尝试 " + i + "/3)");
                    return true;
                }
                sleepMillis(100 * i);
            }
            return false;
        }

        /** 获取长时间关闭阈值 */
        private long getDangerousThreshold(String type) {
            switch (type.toLowerCase()) {
                case "daily":   return 7L * 24 * 3600_000;
                case "weekly":  return 28L * 24 * 3600_000;
                case "monthly": return 180L * 24 * 3600_000;
                case "yearly":  return 3L * 365 * 24 * 3600_000;
                default:        return Long.MAX_VALUE;
            }
        }

        /** 线程睡眠工具 */
        private void sleepMillis(long ms) {
            try { Thread.sleep(ms); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }

    // ============ Cron表达式周期重置 ============

    /** 重置所有Cron表达式变量 */
    private void resetCronVariables(ZonedDateTime now) {
        Set<String> keys = variablesManager.getAllVariableKeys();
        int count = 0;
        for (String key : keys) {
            Variable var = variablesManager.getVariableDefinition(key);
            String expr = var == null ? null : var.getCycle();
            if (expr != null && expr.contains(" ") && shouldResetCron(expr, key, now)) {
                if (resetSingleVariable(var, key)) {
                    updateLastResetTime(key, now.toInstant().toEpochMilli());
                    count++;
                }
            }
        }
        if (count > 0) {
            logger.info("已完成 Cron表达式 周期变量重置，共重置 " + count + " 个变量");
        }
    }

    /** 判断Cron表达式变量是否应重置 */
    private boolean shouldResetCron(String expr, String key, ZonedDateTime now) {
        try {
            Cron cron = cronParser.parse(expr);
            ExecutionTime et = ExecutionTime.forCron(cron);
            DataConfigManager dataConfig = configsManager.getDataConfigManager();
            long last = synchronizedGetLong(dataConfig, "cycle.cron." + key + ".last-reset", 0L);
            ZonedDateTime lastTime = Instant.ofEpochMilli(last).atZone(now.getZone());
            Optional<ZonedDateTime> next = et.nextExecution(lastTime);
            return next.isPresent() && !next.get().isAfter(now);
        } catch (Exception e) {
            logger.error("解析Cron表达式失败: " + expr + " (变量: " + key + ")", e);
            return false;
        }
    }

    /** 更新Cron变量最后重置时间 */
    private void updateLastResetTime(String key, long time) {
        DataConfigManager dataConfig = configsManager.getDataConfigManager();
        synchronized (dataConfig) {
            dataConfig.getConfig().set("cycle.cron." + key + ".last-reset", time);
            plugin.getAsyncTaskManager().submitAsync(() ->
                saveDataConfigAsync(dataConfig, "更新Cron变量重置时间记录: " + key + " -> " + time)
            );
        }
    }

    /** 私有：异步保存DataConfig */
    private void saveDataConfigAsync(DataConfigManager dataConfig, String action) {
        try {
            dataConfig.getConfig().save(plugin.getDataFolder().toPath().resolve("data.yml").toFile());
            logger.debug(action);
        } catch (Exception e) {
            logger.error("更新Cron变量重置时间失败: " + action + ", 错误: " + e.getMessage());
        }
    }

    // ============ 公共工具方法 ============

    /** 删除变量数据库记录 */
    private boolean deleteVariableFromDb(boolean isGlobal, String key) {
        try {
            String sql = isGlobal
                    ? "DELETE FROM server_variables WHERE variable_key = ?"
                    : "DELETE FROM player_variables WHERE variable_key = ?";
            plugin.getDatabase().executeUpdateAsync(sql, key).join();
            return true;
        } catch (Exception e) {
            logger.error("删除变量失败: " + key, e);
            return false;
        }
    }

    /** 重置单个变量（仅用于Cron逻辑） */
    private boolean resetSingleVariable(Variable var, String key) {
        if (var == null) return false;
        boolean ok = deleteVariableFromDb(var.isGlobal(), key);
        if (ok) {
            variablesManager.invalidateAllCaches(key);
            logger.debug("已重置变量: " + key);
        }
        return ok;
    }

    /** 安全获取配置值 */
    private long synchronizedGetLong(DataConfigManager cfg, String path, long def) {
        synchronized (cfg) {
            return cfg.getLong(path, def);
        }
    }

    /** 检查功能开关 */
    private boolean isCycleEnabled() {
        return configsManager.getMainConfig().getBoolean("cycle.enabled", true);
    }

    /** 获取检查间隔 */
    private int getCheckInterval() {
        return configsManager.getMainConfig().getInt("cycle.check-interval-minutes", 1);
    }

    /** 校验并返回时区 */
    private ZoneId getValidatedTimeZone() {
        String zid = configsManager.getMainConfig().getString("cycle.timezone", DEFAULT_TIMEZONE);
        try {
            return ZoneId.of(zid);
        } catch (ZoneRulesException e) {
            logger.warn("配置时区无效: " + zid + ", 使用默认: " + DEFAULT_TIMEZONE);
            try { return ZoneId.of(DEFAULT_TIMEZONE); }
            catch (ZoneRulesException ex) {
                logger.error("默认时区也无效，使用系统默认时区", ex);
                return ZoneId.systemDefault();
            }
        }
    }

    /** 每日周期开始 */
    private ZonedDateTime calculateDailyCycleStart(ZonedDateTime now) {
        return now.truncatedTo(ChronoUnit.DAYS);
    }

    /** 每周周期开始 */
    private ZonedDateTime calculateWeeklyCycleStart(ZonedDateTime now) {
        try {
            return now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                      .truncatedTo(ChronoUnit.DAYS);
        } catch (Exception e) {
            logger.warn("计算周周期异常，退回至日周期");
            return calculateDailyCycleStart(now);
        }
    }

    /** 每月周期开始 */
    private ZonedDateTime calculateMonthlyCycleStart(ZonedDateTime now) {
        try {
            return now.with(TemporalAdjusters.firstDayOfMonth())
                      .truncatedTo(ChronoUnit.DAYS);
        } catch (Exception e) {
            logger.warn("计算月周期异常，退回至日周期");
            return calculateDailyCycleStart(now);
        }
    }

    /** 每年周期开始 */
    private ZonedDateTime calculateYearlyCycleStart(ZonedDateTime now) {
        try {
            return now.with(TemporalAdjusters.firstDayOfYear())
                      .truncatedTo(ChronoUnit.DAYS);
        } catch (Exception e) {
            logger.warn("计算年周期异常，退回至日周期");
            return calculateDailyCycleStart(now);
        }
    }

    /** 记录边界条件信息 */
    private void logBoundaryConditionInfo(ZonedDateTime now, ZoneId zone) {
        try {
            logger.info("=== 边界条件调试信息 ===");
            logger.info("当前时间: " + now + "，时区: " + zone);
            logger.info("闰年: " + now.toLocalDate().isLeapYear()
                    + "，月: " + now.getMonthValue()
                    + "，日: " + now.getDayOfMonth());
            logger.info("周几: " + now.getDayOfWeek()
                    + "，年中日: " + now.getDayOfYear());
            logger.info("========================");
        } catch (Exception e) {
            logger.error("记录边界条件信息失败", e);
        }
    }
}
