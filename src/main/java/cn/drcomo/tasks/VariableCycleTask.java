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
 * 支持标准周期（daily, weekly, monthly, yearly）和自定义Cron表达式。
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
                this::checkCycles, 0, interval, TimeUnit.SECONDS);
        logger.info("已启动周期变量检测任务，间隔: " + interval + " 秒");
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
            runSafeReset("minute", calculateMinuteCycleStart(now));
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

        /** 执行具体重置 - 基于正确的时间对比逻辑 */
        private void performResets() {
            Set<String> keys = variablesManager.getAllVariableKeys();
            ZonedDateTime now = ZonedDateTime.now(getValidatedTimeZone());
            
            for (String key : keys) {
                Variable var = variablesManager.getVariableDefinition(key);
                if (var != null && cycleType.equalsIgnoreCase(var.getCycle())) {
                    
                    // 1. 获取变量首次修改时间
                    Long firstModified = variablesManager.getFirstModifiedAt(var.isGlobal(), key);
                    if (firstModified == null) {
                        logger.debug("跳过重置(变量未曾修改): " + key);
                        continue;
                    }
                    
                    ZonedDateTime firstTime = Instant.ofEpochMilli(firstModified).atZone(now.getZone());
                    
                    // 2. 获取最后重置时间，如果没有则使用首次修改时间作为基准
                    Long lastResetTimeMillis = getLastResetTime(key);
                    ZonedDateTime baseTime = lastResetTimeMillis != null ? 
                        Instant.ofEpochMilli(lastResetTimeMillis).atZone(now.getZone()) : firstTime;
                    
                    // 3. 基于基准时间计算下次重置时间
                    ZonedDateTime nextResetTime = calculateNextResetTime(baseTime, cycleType);
                    
                    // 4. 检查是否到了重置时间
                    if (now.isBefore(nextResetTime)) {
                        logger.debug("跳过重置(未到重置时间): " + key + 
                                  " 基准时间: " + baseTime + 
                                  " 下次重置: " + nextResetTime + 
                                  " 当前: " + now);
                        continue;
                    }
                    
                    // 5. 执行重置
                    if (resetSingleVariableSafe(var, key)) {
                        updateVariableResetTime(key, nextResetTime.toInstant().toEpochMilli());
                        resetList.add(key);
                        logger.info("重置变量: " + key + 
                                  " (基准时间: " + baseTime + 
                                  " → 重置时间: " + nextResetTime + 
                                  " 当前: " + now + ")");
                    }
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
                    // 强化缓存清理：多步骤彻底清理
                    logger.info("正在重置变量: " + key + " (第" + i + "次尝试)");
                    
                    // 1. 清理内存和缓存
                    variablesManager.removeVariableFromMemoryAndCache(key);
                    
                    // 2. 额外的全缓存清理
                    variablesManager.invalidateAllCaches(key);
                    
                    // 3. 强制等待，确保异步清理完成
                    try {
                        Thread.sleep(50); // 短暂等待确保缓存清理完成
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    
                    // 4. 验证重置是否成功（调试用）
                    logger.info("变量 " + key + " 重置完成，数据库记录已删除，所有缓存已清理");
                    return true;
                }
                sleepMillis(100 * i);
            }
            logger.warn("变量 " + key + " 重置失败，已重试3次");
            return false;
        }

        /** 获取长时间关闭阈值 */
        private long getDangerousThreshold(String type) {
            switch (type.toLowerCase()) {
                case "minute":  return 60L * 60_000; // 60分钟
                case "daily":   return 7L * 24 * 3600_000;
                case "weekly":  return 28L * 24 * 3600_000;
                case "monthly": return 180L * 24 * 3600_000;
                case "yearly":  return 3L * 365 * 24 * 3600_000;
                default:        return Long.MAX_VALUE;
            }
        }

        /** 获取一个周期的毫秒数 */
        private long getCycleDuration(String type) {
            switch (type.toLowerCase()) {
                case "minute":  return 60_000; // 1分钟
                case "daily":   return 24L * 3600_000;
                case "weekly":  return 7L * 24 * 3600_000;
                case "monthly": return 30L * 24 * 3600_000;
                case "yearly":  return 365L * 24 * 3600_000;
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
        List<String> resetList = new ArrayList<>();
        
        for (String key : keys) {
            Variable var = variablesManager.getVariableDefinition(key);
            String expr = var == null ? null : var.getCycle();
            if (expr != null && expr.contains(" ")) {
                
                // 获取下次重置时间用于记录
                ZonedDateTime nextResetTime = getCronNextResetTime(expr, key, now, var);
                if (nextResetTime != null && shouldResetCron(expr, key, now, var)) {
                    if (resetSingleVariable(var, key)) {
                        updateVariableResetTime(key, nextResetTime.toInstant().toEpochMilli());
                        resetList.add(key);
                    }
                }
            }
        }
        if (!resetList.isEmpty()) {
            logger.info("已完成 Cron表达式 周期变量重置，共重置 " + resetList.size() + " 个变量");
        }
    }

    /** 获取Cron表达式的下次重置时间 */
    private ZonedDateTime getCronNextResetTime(String expr, String key, ZonedDateTime now, Variable var) {
        try {
            Cron cron = cronParser.parse(expr);
            ExecutionTime et = ExecutionTime.forCron(cron);
            
            Long firstModified = variablesManager.getFirstModifiedAt(var.isGlobal(), key);
            if (firstModified == null) return null;
            
            ZonedDateTime firstTime = Instant.ofEpochMilli(firstModified).atZone(now.getZone());
            Long lastResetTimeMillis = getLastResetTime(key);
            ZonedDateTime baseTime = lastResetTimeMillis != null ? 
                Instant.ofEpochMilli(lastResetTimeMillis).atZone(now.getZone()) : firstTime;
            
            return et.nextExecution(baseTime).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 判断Cron表达式变量是否应重置 - 基于正确的时间对比逻辑 */
    private boolean shouldResetCron(String expr, String key, ZonedDateTime now, Variable var) {
        try {
            Cron cron = cronParser.parse(expr);
            ExecutionTime et = ExecutionTime.forCron(cron);
            
            // 1. 获取变量的首次修改时间
            Long firstModified = variablesManager.getFirstModifiedAt(var.isGlobal(), key);
            if (firstModified == null) {
                logger.debug("跳过Cron重置(变量未曾修改): " + key);
                return false; // 变量从未被修改过
            }
            
            ZonedDateTime firstTime = Instant.ofEpochMilli(firstModified).atZone(now.getZone());
            
            // 2. 获取最后重置时间，如果没有则使用首次修改时间作为基准
            Long lastResetTimeMillis = getLastResetTime(key);
            ZonedDateTime baseTime = lastResetTimeMillis != null ? 
                Instant.ofEpochMilli(lastResetTimeMillis).atZone(now.getZone()) : firstTime;
            
            // 3. 基于基准时间计算下一个Cron触发时间
            Optional<ZonedDateTime> nextResetTime = et.nextExecution(baseTime);
            if (!nextResetTime.isPresent()) {
                logger.debug("跳过Cron重置(无法计算下次执行时间): " + key);
                return false; // 无法计算下次执行时间
            }
            
            // 4. 检查是否已经到了重置时间
            if (now.isBefore(nextResetTime.get())) {
                logger.debug("跳过Cron重置(未到重置时间): " + key + 
                          " 基准时间: " + baseTime +
                          " 下次重置时间: " + nextResetTime.get() + 
                          " 当前时间: " + now);
                return false; // 还未到重置时间
            }
            
            logger.info("Cron变量需要重置: " + key + 
                       " 基准时间: " + baseTime + 
                       " 应重置时间: " + nextResetTime.get() + 
                       " 当前时间: " + now + 
                       " (" + expr + ")");
            return true;
            
        } catch (Exception e) {
            logger.error("解析Cron表达式失败: " + expr + " (变量: " + key + ")", e);
            return false;
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
            // 强化缓存清理：与标准周期重置保持一致
            logger.info("正在重置Cron变量: " + key);
            
            // 1. 清理内存和缓存
            variablesManager.removeVariableFromMemoryAndCache(key);
            
            // 2. 额外的全缓存清理
            variablesManager.invalidateAllCaches(key);
            
            // 3. 强制等待，确保异步清理完成
            try {
                Thread.sleep(50); // 短暂等待确保缓存清理完成
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            
            logger.info("Cron变量 " + key + " 重置完成，数据库记录已删除，所有缓存已清理");
        }
        return ok;
    }


    /** 检查功能开关 */
    private boolean isCycleEnabled() {
        return configsManager.getMainConfig().getBoolean("cycle.enabled", true);
    }

    /** 获取检查间隔（秒） */
    private int getCheckInterval() {
        return configsManager.getMainConfig().getInt("cycle.check-interval-seconds", 60);
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

    /** 每分钟周期开始 */
    private ZonedDateTime calculateMinuteCycleStart(ZonedDateTime now) {
        return now.truncatedTo(ChronoUnit.MINUTES);
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

    // ============ 时间对比逻辑的辅助方法 ============

    /** 计算变量下次应该重置的时间 */
    private ZonedDateTime calculateNextResetTime(ZonedDateTime firstTime, String cycleType) {
        switch (cycleType.toLowerCase()) {
            case "minute":
                // 下一个整分钟
                return firstTime.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
            case "daily":
                // 下一天的00:00
                return firstTime.truncatedTo(ChronoUnit.DAYS).plusDays(1);
            case "weekly":
                // 下周一的00:00
                return firstTime.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                              .truncatedTo(ChronoUnit.DAYS);
            case "monthly":
                // 下个月1号的00:00
                return firstTime.with(TemporalAdjusters.firstDayOfNextMonth())
                              .truncatedTo(ChronoUnit.DAYS);
            case "yearly":
                // 下一年1月1号的00:00
                return firstTime.with(TemporalAdjusters.firstDayOfNextYear())
                              .truncatedTo(ChronoUnit.DAYS);
            default:
                logger.warn("未知的周期类型: " + cycleType + ", 返回一天后");
                return firstTime.plusDays(1);
        }
    }


    /** 更新变量的重置时间记录 */
    private void updateVariableResetTime(String key, long resetTime) {
        DataConfigManager dataConfig = configsManager.getDataConfigManager();
        String resetKey = "cycle.variable." + key + ".last-reset-time";
        
        synchronized (dataConfig) {
            dataConfig.getConfig().set(resetKey, resetTime);
            plugin.getAsyncTaskManager().submitAsync(() -> {
                try {
                    dataConfig.getConfig().save(plugin.getDataFolder().toPath().resolve("data.yml").toFile());
                    logger.debug("更新变量重置时间记录: " + key + " -> " + 
                               Instant.ofEpochMilli(resetTime));
                } catch (Exception e) {
                    logger.error("保存变量重置时间失败: " + key, e);
                }
            });
        }
    }

    /** 获取变量的最后重置时间 */
    private Long getLastResetTime(String key) {
        DataConfigManager dataConfig = configsManager.getDataConfigManager();
        String resetKey = "cycle.variable." + key + ".last-reset-time";
        
        synchronized (dataConfig) {
            long time = dataConfig.getLong(resetKey, 0L);
            return time > 0 ? time : null;
        }
    }
}
