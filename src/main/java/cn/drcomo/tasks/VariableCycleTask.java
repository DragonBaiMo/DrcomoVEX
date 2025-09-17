package cn.drcomo.tasks;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.config.ConfigsManager;
import cn.drcomo.corelib.config.YamlUtil;
import org.bukkit.configuration.file.FileConfiguration;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

/**
 * 周期变量检测任务
 *
 * 定期检查带有 cycle 配置的变量，按需重置其值。
 * 支持标准周期（minute, daily, weekly, monthly, yearly）和自定义Cron表达式。
 *
 * 优化说明：
 * 1. 提取重复逻辑到私有方法，如缓存清理、Cron解析等。
 * 2. 保留所有 public 方法和字段签名不变。
 * 3. 按功能模块重排列，增强可读性与维护性。
 * 4. 补充必要注释，未使用逻辑置于末尾并注释隐藏。
 */
public class VariableCycleTask {

    public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final RefactoredVariablesManager variablesManager;
    private final ConfigsManager configsManager;
    private final YamlUtil yamlUtil;
    private final CronParser cronParser;
    private static final String DATA_CONFIG = "data";
    private ScheduledFuture<?> task;

    // 数据库异步调用的并发与超时控制
    private final int dbMaxConcurrency;
    private final long dbTimeoutMillis;
    private final Semaphore dbSemaphore;

    public VariableCycleTask(
            DrcomoVEX plugin,
            DebugUtil logger,
            RefactoredVariablesManager variablesManager,
            ConfigsManager configsManager,
            YamlUtil yamlUtil
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.variablesManager = variablesManager;
        this.configsManager = configsManager;
        this.yamlUtil = yamlUtil;
        this.cronParser = new CronParser(
                CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ)
        );
        // 读取并发与超时配置（默认: 并发2，超时3000ms）
        this.dbMaxConcurrency = Math.max(1, configsManager.getMainConfig()
                .getInt("cycle.db.max-concurrency", 2));
        this.dbTimeoutMillis = Math.max(500L, configsManager.getMainConfig()
                .getLong("cycle.db.timeout-millis", 3000L));
        this.dbSemaphore = new Semaphore(dbMaxConcurrency);
        initializeDataConfig();
    }
    
    /** 初始化数据配置文件 */
    private void initializeDataConfig() {
        // 确保 data.yml 文件存在，如果不存在则创建
        yamlUtil.copyYamlFile("data.yml", "");
        yamlUtil.loadConfig(DATA_CONFIG);
    }
    
    /** 获取数据配置 */
    private FileConfiguration getDataConfig() {
        return yamlUtil.getConfig(DATA_CONFIG);
    }
    
    /** 获取配置值 */
    private long getDataLong(String path, long defaultValue) {
        return yamlUtil.getLong(DATA_CONFIG, path, defaultValue);
    }
    
    /** 设置配置值 */
    private void setDataValue(String path, Object value) {
        yamlUtil.setValue(DATA_CONFIG, path, value);
    }
    
    /** 保存数据配置 */
    private void saveDataConfig(String action) {
        try {
            getDataConfig().save(plugin.getDataFolder().toPath().resolve("data.yml").toFile());
            logger.debug(action);
        } catch (Exception e) {
            throw new RuntimeException("无法保存配置：" + action, e);
        }
    }

    /** 启动周期检测任务 */
    public void start() {
        if (!isCycleEnabled()) {
            logger.info("周期重置功能已禁用");
            return;
        }
        int interval = getCheckInterval();
        task = plugin.getAsyncTaskManager().scheduleAtFixedRate(
                this::checkCycles, 0, interval, TimeUnit.SECONDS
        );
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

    /**
     * 真正执行重置的类，封装原子性与回滚逻辑
     */
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
            // 尽量缩短持锁时间：仅创建检查点与最终更新时间戳时持锁
            synchronized (yamlUtil) {
                if (!needReset()) return;
                createCheckpoint();
            }

            // 非持锁区执行数据库相关操作
            performResets();

            synchronized (yamlUtil) {
                updateTimestamp();
                clearCheckpoint();
                logger.info("安全重置完成 - " + cycleType +
                        " 周期，重置了 " + resetList.size() + " 个变量");
            }
        }

        /** 判断是否需要重置 */
        private boolean needReset() {
            originalLastReset = getDataLong(lockKey, 0L);
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
            // 对齐本次运行的“周期起点”，例如：
            // minute: 当前分钟的00秒；daily: 当日00:00:00；weekly: 本周一00:00:00；
            // monthly: 本月1日00:00:00；yearly: 本年1月1日00:00:00。
            ZonedDateTime cycleStart = Instant.ofEpochMilli(startMillis).atZone(now.getZone());

            for (String key : keys) {
                Variable var = variablesManager.getVariableDefinition(key);
                if (var != null && cycleType.equalsIgnoreCase(var.getCycle())) {
                    // 读取首次修改时间（跨玩家取 MIN），用于避免“本周期新创建即被重置”
                    Long firstModified = null;
                    try {
                        firstModified = variablesManager
                                .getFirstModifiedAtAsync(var.isGlobal(), key)
                                .orTimeout(dbTimeoutMillis, TimeUnit.MILLISECONDS)
                                .get(dbTimeoutMillis, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException te) {
                        logger.warn("跳过重置(首次修改时间查询超时): " + key);
                        continue;
                    } catch (Exception ex) {
                        logger.error("查询首次修改时间失败: " + key, ex);
                        continue;
                    }
                    if (firstModified == null) {
                        logger.debug("跳过重置(变量未曾修改): " + key);
                        continue;
                    }
                    ZonedDateTime firstTime = Instant.ofEpochMilli(firstModified).atZone(now.getZone());

                    // 若该变量在本周期起点之后(含)才出现/首次修改，则本周期不重置
                    if (!firstTime.isBefore(cycleStart)) {
                        logger.debug("跳过重置(本周期新创建): " + key + " 首改: " + firstTime + " 周期起: " + cycleStart);
                        continue;
                    }

                    // 已于本周期处理过则跳过（保证幂等，避免同日重复重置）
                    Long lastResetMillis = getLastResetTime(key);
                    if (lastResetMillis != null) {
                        ZonedDateTime lastResetTime = Instant.ofEpochMilli(lastResetMillis).atZone(now.getZone());
                        if (!lastResetTime.isBefore(cycleStart)) {
                            logger.debug("跳过重置(本周期已处理): " + key + " 上次: " + lastResetTime + " 周期起: " + cycleStart);
                            continue;
                        }
                    }

                    // 安全重试删除并清理缓存
                    boolean success = false;
                    for (int i = 1; i <= 3; i++) {
                        if (safeDeleteAndClear(var.isGlobal(), key, i, false)) {
                            success = true;
                            break;
                        }
                        sleepMillis(100L * i);
                    }
                    if (success) {
                        // 将变量的 last-reset-time 对齐记录到“本周期起点”
                        updateVariableResetTime(key, startMillis);
                        resetList.add(key);
                        logger.info("重置变量: " + key +
                                " 周期: " + cycleType +
                                " 周期起点: " + cycleStart +
                                " 当前: " + now + ")");
                        // 执行周期动作（玩家作用域：对所有在线玩家各执行一次；全局作用域：仅控制台执行一次）
                        try {
                            variablesManager.executeCycleActionsOnReset(var, null);
                        } catch (Exception actEx) {
                            logger.error("执行重置动作失败: " + key, actEx);
                        }
                    } else {
                        logger.warn("变量 " + key + " 重置失败，已重试3次");
                    }
                }
            }
        }

        /** 创建崩服保护检查点 */
        private void createCheckpoint() {
            getDataConfig().set("cycle.checkpoint-" + cycleType, System.currentTimeMillis());
            saveDataConfig("创建重置检查点: " + cycleType);
        }

        /** 清理检查点 */
        private void clearCheckpoint() {
            getDataConfig().set("cycle.checkpoint-" + cycleType, null);
            saveDataConfig("清理重置检查点: " + cycleType);
        }

        /** 更新时间戳 */
        private void updateTimestamp() throws Exception {
            setDataValue("cycle.last-" + cycleType.toLowerCase() + "-reset", startMillis);
            saveDataConfig("更新时间戳: " + cycleType);
            timeUpdated = true;
        }

        /** 回滚 */
        private void rollback() {
            try {
                synchronized (yamlUtil) {
                    if (timeUpdated && originalLastReset >= 0) {
                        setDataValue("cycle.last-" + cycleType.toLowerCase() + "-reset", originalLastReset);
                    }
                    getDataConfig().set("cycle.rollback-" + cycleType, System.currentTimeMillis());
                    saveDataConfig("回滚检查点: " + cycleType);
                }
            } catch (Exception ex) {
                logger.error("回滚操作失败: " + cycleType, ex);
            }
        }


        /** 线程睡眠工具 */
        private void sleepMillis(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        /** 获取长时间未运行阈值（毫秒） */
        private long getDangerousThreshold(String type) {
            switch (type.toLowerCase()) {
                case "minute":  return 60L * 60_000;        // 60分钟
                case "daily":   return 7L * 24 * 3_600_000; // 7天
                case "weekly":  return 28L * 24 * 3_600_000;
                case "monthly": return 180L * 24 * 3_600_000;
                case "yearly":  return 3L * 365 * 24 * 3_600_000;
                default:        return Long.MAX_VALUE;
            }
        }
    }

    // ============ Cron表达式周期重置 ============

    /** 重置所有 Cron 表达式变量 */
    private void resetCronVariables(ZonedDateTime now) {
        Set<String> keys = variablesManager.getAllVariableKeys();
        List<String> resetList = new ArrayList<>();

        for (String key : keys) {
            Variable var = variablesManager.getVariableDefinition(key);
            String expr = (var == null) ? null : var.getCycle();
            if (expr != null && expr.contains(" ")) {
                if (var == null) {
                    continue;
                }
                ExecutionTime executionTime = parseCronExecutionTime(expr);
                if (executionTime == null) continue;

                // 若变量从未被修改过，则不参与 Cron 重置
                Long firstModifiedAtMillis = null;
                try {
                    firstModifiedAtMillis = variablesManager
                            .getFirstModifiedAtAsync(var.isGlobal(), key)
                            .orTimeout(dbTimeoutMillis, TimeUnit.MILLISECONDS)
                            .get(dbTimeoutMillis, TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    logger.warn("跳过Cron重置(首次修改时间查询超时): " + key);
                    continue;
                } catch (Exception ex) {
                    logger.error("查询首次修改时间失败: " + key, ex);
                    continue;
                }
                if (firstModifiedAtMillis == null) {
                    logger.debug("跳过Cron重置(变量未曾修改): " + key);
                    continue;
                }

                // 计算当前时间点对应的上一次计划执行时间（对齐 Cron Tick，例如 0 * * * * ? => 每分钟00秒）
                Optional<ZonedDateTime> lastExecOpt = executionTime.lastExecution(now);
                if (!lastExecOpt.isPresent()) {
                    logger.debug("跳过Cron重置(无法计算上次执行时间): " + key);
                    continue;
                }
                ZonedDateTime lastScheduledExec = lastExecOpt.get();

                // 读取该变量最后一次重置记录时间与首次修改时间
                ZonedDateTime firstModifiedTime = Instant.ofEpochMilli(firstModifiedAtMillis).atZone(now.getZone());
                Long lastResetMillis = getLastResetTime(key);
                ZonedDateTime lastResetTime = (lastResetMillis != null)
                        ? Instant.ofEpochMilli(lastResetMillis).atZone(now.getZone())
                        : null;

                // 判定规则（防止“新创建后立刻被当前刻度重置”）：
                // 1) 若本刻度已重置过(最后重置时间 >= 本刻度计划时间)，则跳过
                if (lastResetTime != null && !lastResetTime.isBefore(lastScheduledExec)) {
                    logger.debug("跳过Cron重置(本刻度已重置): " + key);
                    continue;
                }
                // 2) 若变量在本刻度计划时间之后才创建/首次修改(首次修改时间 > 本刻度计划时间)，则跳过，避免“首次添加即被当前刻度清空”
                if (firstModifiedTime.isAfter(lastScheduledExec)) {
                    logger.debug("跳过Cron重置(首次修改在本刻度之后): " + key +
                            " 首改: " + firstModifiedTime + " 本刻度: " + lastScheduledExec);
                    continue;
                }

                // 仅用于日志展示下一次计划时间，非判定依据
                Optional<ZonedDateTime> nextExecOpt = executionTime.nextExecution(now);
                String nextInfo = nextExecOpt.map(Object::toString).orElse("N/A");

                logger.info("Cron变量需要重置: " + key +
                        " 上次计划执行: " + lastScheduledExec +
                        " 下次计划执行: " + nextInfo +
                        " 当前时间: " + now +
                        " (" + expr + ")");

                if (safeDeleteAndClear(var.isGlobal(), key, 0, true)) {
                    // 对齐记录到“上次计划执行时间”，确保同一分钟内只重置一次
                    updateVariableResetTime(key, lastScheduledExec.toInstant().toEpochMilli());
                    resetList.add(key);
                    // 执行周期动作（玩家作用域：对所有在线玩家各执行一次；全局作用域：仅控制台执行一次）
                    try {
                        variablesManager.executeCycleActionsOnReset(var, null);
                    } catch (Exception actEx) {
                        logger.error("执行重置动作失败: " + key, actEx);
                    }
                }
            }
        }
        if (!resetList.isEmpty()) {
            logger.info("已完成 Cron 表达式周期变量重置，共重置 " + resetList.size() + " 个变量");
        }
    }

    // ============ 公共工具方法 ============

    /** 删除变量数据库记录（带并发与超时控制） */
    private boolean deleteVariableFromDb(boolean isGlobal, String key) {
        boolean acquired = false;
        try {
            if (!dbSemaphore.tryAcquire(dbTimeoutMillis, TimeUnit.MILLISECONDS)) {
                logger.warn("删除变量等待并发许可超时: " + key);
                return false;
            }
            acquired = true;

            String sql = isGlobal
                    ? "DELETE FROM server_variables WHERE variable_key = ?"
                    : "DELETE FROM player_variables WHERE variable_key = ?";

            CompletableFuture<Boolean> f = plugin.getDatabase()
                    .executeUpdateAsync(sql, key)
                    .orTimeout(dbTimeoutMillis, TimeUnit.MILLISECONDS)
                    .handle((r, ex) -> {
                        if (ex != null) {
                            logger.error("删除变量失败: " + key, ex);
                            return false;
                        }
                        return true;
                    });

            return f.get(dbTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            logger.warn("删除变量超时: " + key);
            return false;
        } catch (Exception e) {
            logger.error("删除变量异常: " + key, e);
            return false;
        } finally {
            if (acquired) {
                dbSemaphore.release();
            }
        }
    }

    /** 检查功能开关 */
    private boolean isCycleEnabled() {
        return configsManager.getMainConfig()
                .getBoolean("cycle.enabled", true);
    }

    /** 获取检查间隔（秒） */
    private int getCheckInterval() {
        return configsManager.getMainConfig()
                .getInt("cycle.check-interval-seconds", 60);
    }

    /** 校验并返回时区 */
    private ZoneId getValidatedTimeZone() {
        String zid = configsManager.getMainConfig()
                .getString("cycle.timezone", DEFAULT_TIMEZONE);
        try {
            return ZoneId.of(zid);
        } catch (ZoneRulesException e) {
            logger.warn("配置时区无效: " + zid + "，使用默认: " + DEFAULT_TIMEZONE);
            try {
                return ZoneId.of(DEFAULT_TIMEZONE);
            } catch (ZoneRulesException ex) {
                logger.error("默认时区也无效，使用系统默认时区", ex);
                return ZoneId.systemDefault();
            }
        }
    }

    /** 更新变量的重置时间记录 */
    private void updateVariableResetTime(String key, long resetTime) {
        String resetKey = "cycle.variable." + key + ".last-reset-time";
        synchronized (yamlUtil) {
            getDataConfig().set(resetKey, resetTime);
            plugin.getAsyncTaskManager().submitAsync(() -> {
                try {
                    getDataConfig().save(plugin.getDataFolder().toPath().resolve("data.yml").toFile());
                    logger.debug("更新变量重置时间记录: " + key +
                            " -> " + Instant.ofEpochMilli(resetTime));
                } catch (Exception e) {
                    logger.error("保存变量重置时间失败: " + key, e);
                }
            });
        }
    }

    /** 获取变量的最后重置时间 */
    private Long getLastResetTime(String key) {
        String resetKey = "cycle.variable." + key + ".last-reset-time";
        synchronized (yamlUtil) {
            long time = getDataLong(resetKey, 0L);
            return time > 0 ? time : null;
        }
    }

    // ============ 时间计算辅助方法 ============

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
            logger.info("闰年: " + now.toLocalDate().isLeapYear() +
                    "，月: " + now.getMonthValue() +
                    "，日: " + now.getDayOfMonth());
            logger.info("周几: " + now.getDayOfWeek() +
                    "，年中日: " + now.getDayOfYear());
            logger.info("========================");
        } catch (Exception e) {
            logger.error("记录边界条件信息失败", e);
        }
    }

    // ============ 私有工具方法 ============

    /**
     * 执行删除并清理缓存，支持重试与 Cron 标识
     *
     * @param isGlobal 是否全局变量
     * @param key      变量键
     * @param attempt  尝试次数（标准周期模式有效）
     * @param isCron   是否 Cron 重置
     * @return 删除并清理成功返回 true
     */
    private boolean safeDeleteAndClear(
            boolean isGlobal, String key, int attempt, boolean isCron) {
        if (deleteVariableFromDb(isGlobal, key)) {
            String label = isCron ? "Cron变量" : "变量";
            String attemptInfo = isCron ? "" : " (第" + attempt + "次尝试)";
            logger.info("正在重置" + label + ": " + key + attemptInfo);
            clearCachesAndWait(key);
            logger.info(label + " " + key + " 重置完成，数据库记录已删除，所有缓存已清理");
            return true;
        }
        return false;
    }

    /** 清理内存和缓存并短暂等待，确保异步完成 */
    private void clearCachesAndWait(String key) {
        variablesManager.removeVariableFromMemoryAndCache(key);
        variablesManager.invalidateGlobalCaches(key);
        try {
            Thread.sleep(50); // 短暂等待确保缓存清理完成
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** 解析 Cron 表达式并获取执行时间对象 */
    private ExecutionTime parseCronExecutionTime(String expr) {
        try {
            Cron cron = cronParser.parse(expr);
            return ExecutionTime.forCron(cron);
        } catch (Exception e) {
            logger.error("解析Cron表达式失败: " + expr, e);
            return null;
        }
    }

    // getBaseTime 已不再需要（Cron 重置基于 lastExecution 对齐），移除以简化逻辑
}