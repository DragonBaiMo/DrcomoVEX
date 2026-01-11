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
 * 支持标准周期（minute, hour, daily, weekly, monthly, yearly）和自定义Cron表达式。
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
    private static final long FUTURE_DRIFT_MILLIS = 60_000L;
    private ScheduledFuture<?> task;
    private volatile boolean waitForInitializationLogged;

    // 数据库异步调用的并发与超时控制
    private final int dbMaxConcurrency;
    private final long dbTimeoutMillis;
    private final Semaphore dbSemaphore;
    private final int playerDeleteBatchSize;

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
        this.playerDeleteBatchSize = Math.max(100, configsManager.getMainConfig()
                .getInt("cycle.db.player-delete-batch-size", 1000));
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
        int initialDelay = Math.max(0, configsManager.getMainConfig()
                .getInt("cycle.initial-delay-seconds", 0));
        task = plugin.getAsyncTaskManager().scheduleAtFixedRate(
                this::checkCycles, initialDelay, interval, TimeUnit.SECONDS
        );
        logger.info("已启动周期变量检测任务，间隔: " + interval + " 秒，初始延迟: " + initialDelay + " 秒");
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
        if (!variablesManager.isInitialized()) {
            if (!waitForInitializationLogged) {
                logger.info("变量管理器尚未初始化完成，暂缓周期重置检查");
                waitForInitializationLogged = true;
            }
            return;
        }
        if (waitForInitializationLogged) {
            logger.info("变量管理器初始化完成，开始执行周期重置检查");
            waitForInitializationLogged = false;
        }

        ZoneId zone = getValidatedTimeZone();
        ZonedDateTime now = ZonedDateTime.now(zone);
        try {
            processStandardCycles(now);
            processCronCycles(now);
        } catch (DateTimeException e) {
            logger.error("时间计算异常，请检查时区配置: " + zone.getId(), e);
            logBoundaryConditionInfo(now, zone);
        } catch (Exception e) {
            logger.error("周期变量检测任务执行异常", e);
        }
    }

    // ============ 新的窗口驱动周期实现（标准 + Cron 调度入口使用） ============

    /** 长周期优先执行，避免短周期补偿占满时间 */
    private void processStandardCycles(ZonedDateTime now) {
        String[] order = new String[]{"yearly", "monthly", "weekly", "daily", "hour", "minute"};
        for (String type : order) {
            CycleWindow currentWindow = buildCurrentWindow(type, now);
            List<CycleWindow> pending = buildPendingWindows(type, currentWindow, now);
            if (pending.isEmpty()) {
                continue;
            }
            int success = 0;
            for (CycleWindow window : pending) {
                boolean completed = resetVariablesInWindow(type, window, now);
                if (completed) {
                    writeGlobalLastReset(type, window.getStart());
                    success++;
                } else {
                    logger.warn("窗口执行未完全成功，等待下一轮补偿: " + type + " 窗口起点 " + window.getStart());
                    break;
                }
            }
            logger.info("完成标准周期检测: " + type + " 窗口数 " + success + "/" + pending.size());
        }
    }

    /** 统一 Cron 重置入口（窗口化判定） */
    private void processCronCycles(ZonedDateTime now) {
        Set<String> keys = variablesManager.getAllVariableKeys();
        int resetCount = 0;
        for (String key : keys) {
            Variable var = variablesManager.getVariableDefinition(key);
            if (var == null) {
                continue;
            }
            String expr = var.getCycle();
            if (expr == null || !expr.contains(" ")) {
                continue;
            }
            ExecutionTime executionTime = parseCronExecutionTime(expr);
            if (executionTime == null) {
                continue;
            }

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

            Optional<ZonedDateTime> lastExecOpt = executionTime.lastExecution(now);
            if (!lastExecOpt.isPresent()) {
                logger.debug("跳过Cron重置(无法计算上次执行时间): " + key);
                continue;
            }
            ZonedDateTime lastScheduledExec = lastExecOpt.get();

            ZonedDateTime firstModifiedTime = Instant.ofEpochMilli(firstModifiedAtMillis).atZone(now.getZone());
            if (!firstModifiedTime.isBefore(lastScheduledExec)) {
                logger.debug("跳过Cron重置(首次修改在本刻度之后): " + key + " 首改: " + firstModifiedTime + " 本刻度: " + lastScheduledExec);
                continue;
            }

            Long lastResetMillis = getLastResetTime(key);
            ZonedDateTime lastResetTime = (lastResetMillis != null)
                    ? Instant.ofEpochMilli(lastResetMillis).atZone(now.getZone())
                    : null;
            lastResetTime = sanitizeVariableFutureTime(key, lastResetTime, new CycleWindow("cron", lastScheduledExec, null), now, "cron");
            if (lastResetTime != null && !lastResetTime.isBefore(lastScheduledExec)) {
                logger.debug("跳过Cron重置(本刻度已重置): " + key);
                continue;
            }

            Optional<ZonedDateTime> nextExecOpt = executionTime.nextExecution(now);
            String nextInfo = nextExecOpt.map(Object::toString).orElse("N/A");

            logger.info("Cron变量需要重置: " + key +
                    " 上次计划执行: " + lastScheduledExec +
                    " 下次计划执行: " + nextInfo +
                    " 当前时间: " + now +
                    " (" + expr + ")");

            if (safeDeleteAndClear(var.isGlobal(), key, 0, true)) {
                updateVariableResetTime(key, lastScheduledExec.toInstant().toEpochMilli());
                resetCount++;
                try {
                    variablesManager.executeCycleActionsOnReset(var, null);
                } catch (Exception actEx) {
                    logger.error("执行重置动作失败: " + key, actEx);
                }
            } else {
                logger.warn("Cron变量重置失败: " + key);
            }
        }
        if (resetCount > 0) {
            logger.info("已完成 Cron 表达式周期变量重置，共重置 " + resetCount + " 个变量");
        }
    }

    /** 构建当前窗口 */
    private CycleWindow buildCurrentWindow(String cycleType, ZonedDateTime now) {
        ZonedDateTime start = normalizeCycleStart(cycleType, now);
        ZonedDateTime end = advanceCycleStart(start, cycleType);
        return new CycleWindow(cycleType, start, end);
    }

    /** 生成需要补偿的窗口序列 */
    private List<CycleWindow> buildPendingWindows(String cycleType, CycleWindow currentWindow, ZonedDateTime now) {
        List<CycleWindow> result = new ArrayList<>();
        Long lastResetMillis;
        synchronized (yamlUtil) {
            lastResetMillis = getDataLong("cycle.last-" + cycleType.toLowerCase() + "-reset", 0L);
        }
        ZonedDateTime sanitizedLast = sanitizeGlobalLastReset(cycleType, lastResetMillis, currentWindow, now);
        int limit = getCatchUpLimit(cycleType);
        List<ZonedDateTime> pendingStarts = computePendingStarts(cycleType, currentWindow.getStart(), sanitizedLast, limit);
        for (ZonedDateTime start : pendingStarts) {
            ZonedDateTime end = advanceCycleStart(start, cycleType);
            result.add(new CycleWindow(cycleType, start, end));
        }
        return result;
    }

    /** 处理窗口内的变量重置 */
    private boolean resetVariablesInWindow(String cycleType, CycleWindow window, ZonedDateTime now) {
        Set<String> keys = variablesManager.getAllVariableKeys();
        boolean hadBlockingFailure = false;
        int resetCount = 0;
        for (String key : keys) {
            Variable var = variablesManager.getVariableDefinition(key);
            if (var == null) {
                continue;
            }
            String definedCycle = var.getCycle();
            if (definedCycle == null || definedCycle.contains(" ")) {
                continue; // Cron 单独处理
            }
            if (!cycleType.equalsIgnoreCase(definedCycle)) {
                continue;
            }

            Long firstModified = null;
            try {
                firstModified = variablesManager
                        .getFirstModifiedAtAsync(var.isGlobal(), key)
                        .orTimeout(dbTimeoutMillis, TimeUnit.MILLISECONDS)
                        .get(dbTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                logger.warn("跳过重置(首次修改时间查询超时): " + key);
                hadBlockingFailure = true;
                continue;
            } catch (Exception ex) {
                logger.error("查询首次修改时间失败: " + key, ex);
                hadBlockingFailure = true;
                continue;
            }
            if (firstModified == null) {
                logger.debug("跳过重置(变量未曾修改): " + key);
                continue;
            }
            ZonedDateTime firstTime = Instant.ofEpochMilli(firstModified).atZone(now.getZone());
            if (!firstTime.isBefore(window.getStart())) {
                logger.debug("跳过重置(窗口内新创建): " + key + " 首改: " + firstTime + " 窗口: " + window.getStart());
                continue;
            }

            Long lastResetMillis = getLastResetTime(key);
            ZonedDateTime lastResetTime = (lastResetMillis != null)
                    ? Instant.ofEpochMilli(lastResetMillis).atZone(now.getZone())
                    : null;
            lastResetTime = sanitizeVariableFutureTime(key, lastResetTime, window, now, cycleType);
            if (lastResetTime != null && !lastResetTime.isBefore(window.getStart())) {
                logger.debug("跳过重置(窗口已处理): " + key + " 上次: " + lastResetTime + " 窗口: " + window.getStart());
                continue;
            }

            boolean success = false;
            for (int i = 1; i <= 3; i++) {
                if (safeDeleteAndClear(var.isGlobal(), key, i, false)) {
                    success = true;
                    break;
                }
                sleepQuietly(100L * i);
            }
            if (success) {
                updateVariableResetTime(key, window.getStart().toInstant().toEpochMilli());
                resetCount++;
                logger.info("重置变量: " + key +
                        " 周期: " + cycleType +
                        " 窗口起点: " + window.getStart() +
                        " 当前: " + now);
                try {
                    variablesManager.executeCycleActionsOnReset(var, null);
                } catch (Exception actEx) {
                    logger.error("执行重置动作失败: " + key, actEx);
                }
            } else {
                logger.warn("变量 " + key + " 重置失败，已重试3次");
                hadBlockingFailure = true;
            }
        }
        if (hadBlockingFailure) {
            logger.warn("窗口执行存在失败: " + cycleType + " 窗口 " + window.getStart() + " 成功 " + resetCount);
        } else {
            logger.info("窗口执行完成: " + cycleType + " 窗口 " + window.getStart() + " 成功 " + resetCount);
        }
        return !hadBlockingFailure;
    }

    /** 记录全局 last-reset 到窗口起点 */
    private void writeGlobalLastReset(String cycleType, ZonedDateTime start) {
        synchronized (yamlUtil) {
            setDataValue("cycle.last-" + cycleType.toLowerCase() + "-reset", start.toInstant().toEpochMilli());
            saveDataConfig("更新时间戳: " + cycleType);
        }
    }

    /** 纠正全局未来时间记录，返回归一化后的起点 */
    private ZonedDateTime sanitizeGlobalLastReset(String cycleType, Long lastResetMillis, CycleWindow currentWindow, ZonedDateTime now) {
        if (lastResetMillis == null || lastResetMillis <= 0L) {
            return null;
        }
        ZonedDateTime recorded = Instant.ofEpochMilli(lastResetMillis).atZone(now.getZone());
        if (recorded.isAfter(now.plus(Duration.ofMillis(FUTURE_DRIFT_MILLIS)))) {
            ZonedDateTime fallback = retreatCycleStart(currentWindow.getStart(), cycleType);
            if (fallback == null) {
                fallback = currentWindow.getStart().minusSeconds(1);
            }
            synchronized (yamlUtil) {
                setDataValue("cycle.last-" + cycleType.toLowerCase() + "-reset", fallback.toInstant().toEpochMilli());
                saveDataConfig("自动纠正未来周期记录: " + cycleType);
            }
            logger.warn("检测到未来的 " + cycleType + " 全局重置记录，已回调到 " + fallback);
            return fallback;
        }
        return normalizeCycleStart(cycleType, recorded);
    }

    /** 校正变量维度未来时间，确保不会阻断补偿 */
    private ZonedDateTime sanitizeVariableFutureTime(String key, ZonedDateTime recordedTime, CycleWindow window, ZonedDateTime now, String cycleLabel) {
        if (recordedTime == null) {
            return null;
        }
        if (recordedTime.isAfter(now.plus(Duration.ofMillis(FUTURE_DRIFT_MILLIS)))) {
            ZonedDateTime corrected = window.getStart().minusSeconds(1);
            updateVariableResetTime(key, corrected.toInstant().toEpochMilli());
            logger.warn("检测到未来的重置时间记录，已自动回调: 变量=" + key + " 周期=" + cycleLabel +
                    " 原记录=" + recordedTime + " 窗口=" + window.getStart() + " 新记录=" + corrected);
            return corrected;
        }
        return recordedTime;
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

            if (isGlobal) {
                return executeDeleteWithTimeout(
                        "DELETE FROM server_variables WHERE variable_key = ?",
                        key, key
                );
            }

            return deletePlayerVariablesInBatches(key);
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

    /**
     * 分批删除玩家变量，避免一次性删除大量行导致 SQLite 长事务或 MySQL 锁等待超时。
     */
    private boolean deletePlayerVariablesInBatches(String key) {
        // 允许分批操作占用更长时间，但仍设置绝对上限防止无休止重试
        long absoluteDeadline = System.currentTimeMillis()
                + Math.max(dbTimeoutMillis * 3, dbTimeoutMillis + TimeUnit.SECONDS.toMillis(5));
        int totalDeleted = 0;

        // 使用更可靠的数据库类型检测方法（基于实际连接元数据）
        boolean isMySQL = plugin.getDatabase().isMySQL();
        boolean isSQLite = plugin.getDatabase().isSQLite();

        String deleteSql;
        Object[] deleteParams;

        if (isMySQL) {
            // MySQL：直接使用 LIMIT，不需要子查询
            deleteSql = "DELETE FROM player_variables WHERE variable_key = ? LIMIT ?";
            deleteParams = new Object[]{key, playerDeleteBatchSize};
            logger.debug("使用 MySQL 分批删除语句");
        } else if (isSQLite) {
            // SQLite：使用子查询 + LIMIT（SQLite 支持此语法）
            deleteSql = "DELETE FROM player_variables WHERE id IN (" +
                    "SELECT id FROM player_variables WHERE variable_key = ? LIMIT ?" +
                    ")";
            deleteParams = new Object[]{key, playerDeleteBatchSize};
            logger.debug("使用 SQLite 分批删除语句");
        } else {
            // 未知类型：使用通用的 MySQL 语法（大多数数据库都支持）
            logger.warn("无法确定数据库类型，使用通用 MySQL 分批删除语句作为降级方案");
            deleteSql = "DELETE FROM player_variables WHERE variable_key = ? LIMIT ?";
            deleteParams = new Object[]{key, playerDeleteBatchSize};
        }

        while (true) {
            if (System.currentTimeMillis() > absoluteDeadline) {
                logger.warn("删除玩家变量耗时过长，已放弃: " + key + " 已删除 " + totalDeleted + " 行");
                return false;
            }

            logger.debug("执行分批删除玩家变量: SQL=" + deleteSql
                    + "，变量键=" + key + "，批次大小=" + playerDeleteBatchSize);

            Integer deleted = executeDeleteReturningCount(
                    deleteSql,
                    key,
                    deleteParams
            );

            if (deleted == null) {
                return false;
            }

            totalDeleted += deleted;

            if (deleted < playerDeleteBatchSize) {
                if (totalDeleted == 0) {
                    logger.warn("玩家变量分批删除结束但未删除任何数据: " + key);
                    return false;
                }
                logger.info("玩家变量分批删除完成: " + key + " 共删除 " + totalDeleted + " 行");
                return true;
            }

            if (!sleepQuietly(10L)) {
                logger.warn("删除玩家变量被中断: " + key);
                return false;
            }
        }
    }

    /**
     * 执行删除并返回影响行数，为超时和异常提供统一处理。
     */
    private Integer executeDeleteReturningCount(String sql, String key, Object... params) {
        try {
            CompletableFuture<Integer> future = plugin.getDatabase()
                    .executeUpdateAsync(sql, params)
                    .orTimeout(dbTimeoutMillis, TimeUnit.MILLISECONDS);
            return future.get(dbTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            logger.warn("删除变量执行超时: " + key);
        } catch (Exception e) {
            logger.error("执行删除变量语句失败: " + key + " SQL=" + sql, e);
        }
        return null;
    }

    /**
     * 执行简单删除语句（全局变量使用），捕获异常和超时。
     */
    private boolean executeDeleteWithTimeout(String sql, String key, Object... params) throws Exception {
        CompletableFuture<Integer> f = plugin.getDatabase()
                .executeUpdateAsync(sql, params)
                .orTimeout(dbTimeoutMillis, TimeUnit.MILLISECONDS)
                .handle((r, ex) -> {
                    if (ex != null) {
                        logger.error("删除变量失败: " + key, ex);
                        return null;
                    }
                    return r;
                });
        Integer affected = f.get(dbTimeoutMillis, TimeUnit.MILLISECONDS);
        if (affected == null) {
            return false;
        }
        if (affected <= 0) {
            logger.warn("删除全局变量未删除任何数据: " + key);
            return false;
        }
        logger.info("删除全局变量成功: " + key + " 共删除 " + affected + " 行");
        return true;
    }

    /**
     * 安静睡眠，避免过度占用数据库线程。
     */
    private boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
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

    /**
     * 防止变量重置时间记录落在未来导致永远跳过重置，自动回调到当前周期前一秒。
     */
    private ZonedDateTime sanitizeFutureResetTime(
            String key,
            ZonedDateTime recordedTime,
            ZonedDateTime referencePoint,
            ZonedDateTime now,
            String cycleLabel
    ) {
        if (recordedTime == null || referencePoint == null) {
            return recordedTime;
        }
        // 允许极小漂移（1分钟），超过则认为记录异常
        if (recordedTime.isAfter(now.plusMinutes(1))) {
            ZonedDateTime corrected = referencePoint.minusSeconds(1);
            updateVariableResetTime(key, corrected.toInstant().toEpochMilli());
            logger.warn("检测到未来的重置时间记录，已自动回调: " +
                    "变量=" + key +
                    " 周期=" + cycleLabel +
                    " 原记录=" + recordedTime +
                    " 参考点=" + referencePoint +
                    " 当前=" + now +
                    " 新记录=" + corrected);
            return corrected;
        }
        return recordedTime;
    }

    /** 周期窗口对象（闭区间起点，开区间终点） */
    private static final class CycleWindow {
        private final String cycleType;
        private final ZonedDateTime start;
        private final ZonedDateTime end;

        private CycleWindow(String cycleType, ZonedDateTime start, ZonedDateTime end) {
            this.cycleType = cycleType;
            this.start = start;
            this.end = end;
        }

        public String getCycleType() {
            return cycleType;
        }

        public ZonedDateTime getStart() {
            return start;
        }

        public ZonedDateTime getEnd() {
            return end;
        }
    }

    // ============ 时间计算辅助方法 ============

    /** 每分钟周期开始 */
    private ZonedDateTime calculateMinuteCycleStart(ZonedDateTime now) {        
        return normalizeMinute(now);
    }

    /** 每小时周期开始 */
    private ZonedDateTime calculateHourlyCycleStart(ZonedDateTime now) {        
        return normalizeHour(now);
    }

    /** 每日周期开始 */
    private ZonedDateTime calculateDailyCycleStart(ZonedDateTime now) {
        return normalizeDay(now);
    }

    /** 每周周期开始 */
    private ZonedDateTime calculateWeeklyCycleStart(ZonedDateTime now) {        
        try {
            return normalizeWeek(now);
        } catch (Exception e) {
            logger.warn("计算周周期异常，退回至日周期");
            return calculateDailyCycleStart(now);
        }
    }

    /** 每月周期开始 */
    private ZonedDateTime calculateMonthlyCycleStart(ZonedDateTime now) {       
        try {
            return normalizeMonth(now);
        } catch (Exception e) {
            logger.warn("计算月周期异常，退回至日周期");
            return calculateDailyCycleStart(now);
        }
    }

    /** 每年周期开始 */
    private ZonedDateTime calculateYearlyCycleStart(ZonedDateTime now) {        
        try {
            return normalizeYear(now);
        } catch (Exception e) {
            logger.warn("计算年周期异常，退回至日周期");
            return calculateDailyCycleStart(now);
        }
    }

    /**
     * 计算需要补偿执行的周期起点
     */
    private List<ZonedDateTime> expandPendingCycles(String cycleType, ZonedDateTime cycleStart) {
        List<ZonedDateTime> pending = new ArrayList<>();
        String path = "cycle.last-" + cycleType.toLowerCase() + "-reset";
        long lastResetMillis;
        synchronized (yamlUtil) {
            lastResetMillis = getDataLong(path, 0L);
        }

        if (lastResetMillis <= 0L) {
            pending.add(cycleStart);
            return pending;
        }

        ZoneId zone = cycleStart.getZone();
        ZonedDateTime lastReset = Instant.ofEpochMilli(lastResetMillis).atZone(zone);
        ZonedDateTime normalizedLast = normalizeCycleStart(cycleType, lastReset);

        if (normalizedLast.isAfter(cycleStart)) {
            logger.warn("检测到未来的" + cycleType + "重置记录，准备自动纠正: 上次="
                    + normalizedLast + " 当前=" + cycleStart);
            ZonedDateTime corrected = rectifyFutureResetTimestamp(cycleType, cycleStart);
            if (corrected != null) {
                normalizedLast = corrected;
            } else {
                logger.warn("未来时间纠正失败，仍将尝试执行当前周期补偿: " + cycleType);
                pending.add(cycleStart);
                return pending;
            }
        }
        if (!normalizedLast.isBefore(cycleStart)) {
            return pending;
        }

        ZonedDateTime nextStart = advanceCycleStart(normalizedLast, cycleType);
        int limit = getCatchUpLimit(cycleType);
        int steps = 0;

        while (nextStart != null && !nextStart.isAfter(cycleStart)) {
            pending.add(nextStart);
            if (nextStart.equals(cycleStart)) {
                break;
            }
            nextStart = advanceCycleStart(nextStart, cycleType);
            steps++;
            if (steps >= limit) {
                if (nextStart != null && nextStart.isBefore(cycleStart)) {
                    logger.warn("补偿" + cycleType + "周期超过上限 " + limit +
                            " 次，直接跳到当前周期: 上次=" + normalizedLast + " 目标=" + cycleStart);
                    pending.add(cycleStart);
                }
                break;
            }
        }

        if (!pending.isEmpty() && pending.get(pending.size() - 1).isBefore(cycleStart)) {
            pending.add(cycleStart);
        }

        if (pending.isEmpty()) {
            pending.add(cycleStart);
        } else if (pending.size() > 1) {
            logger.warn("检测到 " + cycleType + " 周期补偿 " + pending.size() +
                    " 次，自上次重置 " + normalizedLast + " 至当前起点 " + cycleStart);
        }
        return pending;
    }

    /**
     * 自动纠正记录在未来的 last-reset 时间，避免跳过补偿
     */
    private ZonedDateTime rectifyFutureResetTimestamp(String cycleType, ZonedDateTime cycleStart) {
        ZonedDateTime corrected = retreatCycleStart(cycleStart, cycleType);
        if (corrected == null) {
            corrected = cycleStart.minusSeconds(1);
        }
        corrected = normalizeCycleStart(cycleType, corrected);
        long correctedMillis = corrected.toInstant().toEpochMilli();
        synchronized (yamlUtil) {
            setDataValue("cycle.last-" + cycleType.toLowerCase() + "-reset", correctedMillis);
            saveDataConfig("自动纠正未来周期记录: " + cycleType);
        }
        logger.warn("已自动将 " + cycleType + " 周期的最后重置时间回调至 " + corrected
                + "，请检查服务器时间与数据库删除流程是否正常");
        return corrected;
    }

    /**
     * 统一将任意时间对齐到对应周期的起点
     */
    private ZonedDateTime normalizeCycleStart(String cycleType, ZonedDateTime time) {
        return normalizeCycleStartPure(cycleType, time);
    }

    /** 纯函数：归一化周期起点（可用于测试） */
    static ZonedDateTime normalizeCycleStartPure(String cycleType, ZonedDateTime time) {
        switch (cycleType.toLowerCase()) {
            case "minute":  return normalizeMinute(time);
            case "hour":    return normalizeHour(time);
            case "daily":   return normalizeDay(time);
            case "weekly":  return normalizeWeek(time);
            case "monthly": return normalizeMonth(time);
            case "yearly":  return normalizeYear(time);
            default:        return time;
        }
    }

    /**
     * 根据当前周期起点推算下一次周期的起点
     */
    private ZonedDateTime advanceCycleStart(ZonedDateTime start, String cycleType) {
        return advanceCycleStartPure(start, cycleType);
    }

    /** 纯函数：推进一个周期（可用于测试） */
    static ZonedDateTime advanceCycleStartPure(ZonedDateTime start, String cycleType) {
        switch (cycleType.toLowerCase()) {
            case "minute":  return normalizeMinute(start.plusMinutes(1));
            case "hour":    return normalizeHour(start.plusHours(1));
            case "daily":   return normalizeDay(start.plusDays(1));
            case "weekly":  return normalizeWeek(start.plusWeeks(1));
            case "monthly": return normalizeMonth(start.plusMonths(1));
            case "yearly":  return normalizeYear(start.plusYears(1));
            default:        return null;
        }
    }

    /**
     * 推算上一个周期的起点，用于修正 future 时间
     */
    private ZonedDateTime retreatCycleStart(ZonedDateTime start, String cycleType) {
        return retreatCycleStartPure(start, cycleType);
    }

    /** 纯函数：回退一个周期（可用于测试） */
    static ZonedDateTime retreatCycleStartPure(ZonedDateTime start, String cycleType) {
        switch (cycleType.toLowerCase()) {
            case "minute":  return normalizeMinute(start.minusMinutes(1));
            case "hour":    return normalizeHour(start.minusHours(1));
            case "daily":   return normalizeDay(start.minusDays(1));
            case "weekly":  return normalizeWeek(start.minusWeeks(1));
            case "monthly": return normalizeMonth(start.minusMonths(1));
            case "yearly":  return normalizeYear(start.minusYears(1));
            default:        return null;
        }
    }

    /**
     * 针对不同周期的补偿次数上限
     */
    private int getCatchUpLimit(String cycleType) {
        return getCatchUpLimitPure(cycleType);
    }

    /** 纯函数：补偿上限（可用于测试） */
    static int getCatchUpLimitPure(String cycleType) {
        switch (cycleType.toLowerCase()) {
            case "minute":  return 120; // 最多补偿2小时，避免分钟级循环过长
            case "hour":    return 168; // 最多补偿一周
            case "daily":   return 31;  // 最多补偿一个月
            case "weekly":  return 12;  // 最多补偿一年
            case "monthly": return 24;  // 最多补偿两年
            case "yearly":  return 5;   // 最多补偿五年
            default:        return 1;
        }
    }

    /** 纯函数：计算待补偿的窗口起点序列（含当前窗口），可单测验证 */
    static List<ZonedDateTime> computePendingStarts(String cycleType, ZonedDateTime currentStart, ZonedDateTime lastNormalized, int limit) {
        List<ZonedDateTime> starts = new ArrayList<>();
        ZonedDateTime cursor = (lastNormalized == null) ? currentStart : advanceCycleStartPure(lastNormalized, cycleType);
        int steps = 0;
        while (cursor != null && !cursor.isAfter(currentStart) && steps <= limit) {
            starts.add(cursor);
            if (cursor.equals(currentStart)) {
                break;
            }
            cursor = advanceCycleStartPure(cursor, cycleType);
            steps++;
        }
        if (steps > limit && cursor != null && cursor.isBefore(currentStart)) {
            starts.clear();
            starts.add(currentStart);
        }
        if (starts.isEmpty()) {
            starts.add(currentStart);
        }
        return starts;
    }

    // 基础归一化工具（纯函数，可复用在上述方法中）
    private static ZonedDateTime normalizeMinute(ZonedDateTime time) {
        return time.truncatedTo(ChronoUnit.MINUTES);
    }

    private static ZonedDateTime normalizeHour(ZonedDateTime time) {
        return time.truncatedTo(ChronoUnit.HOURS);
    }

    private static ZonedDateTime normalizeDay(ZonedDateTime time) {
        return time.truncatedTo(ChronoUnit.DAYS);
    }

    private static ZonedDateTime normalizeWeek(ZonedDateTime time) {
        return time.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .truncatedTo(ChronoUnit.DAYS);
    }

    private static ZonedDateTime normalizeMonth(ZonedDateTime time) {
        return time.with(TemporalAdjusters.firstDayOfMonth())
                .truncatedTo(ChronoUnit.DAYS);
    }

    private static ZonedDateTime normalizeYear(ZonedDateTime time) {
        return time.with(TemporalAdjusters.firstDayOfYear())
                .truncatedTo(ChronoUnit.DAYS);
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
