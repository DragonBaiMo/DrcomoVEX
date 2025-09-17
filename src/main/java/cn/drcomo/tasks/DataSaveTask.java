package cn.drcomo.tasks;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.managers.RefactoredVariablesManager;
import cn.drcomo.corelib.util.DebugUtil;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 数据保存任务
 * 
 * 定时保存所有变量数据到数据库。
 * 
 * @author BaiMo
 */
public class DataSaveTask {
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final RefactoredVariablesManager variablesManager;
    private final FileConfiguration config;
    
    private ScheduledFuture<?> saveTask;
    
    public DataSaveTask(
            DrcomoVEX plugin,
            DebugUtil logger,
            RefactoredVariablesManager variablesManager,
            FileConfiguration config
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.variablesManager = variablesManager;
        this.config = config;
    }
    
    /**
     * 启动定时保存任务
     */
    public void start() {
        // 检查是否启用自动保存
        if (!config.getBoolean("data.auto-save", true)) {
            logger.info("自动保存已禁用");
            return;
        }
        
        // 获取保存间隔（分钟）
        int saveIntervalMinutes = config.getInt("data.save-interval-minutes", 5);

        // 启动定时任务
        saveTask = plugin.getAsyncTaskManager().scheduleAtFixedRate(
                this::performSave,
                saveIntervalMinutes, // 初始延迟
                saveIntervalMinutes, // 重复间隔
                TimeUnit.MINUTES
        );
        
        logger.info("已启动数据自动保存任务，间隔: " + saveIntervalMinutes + " 分钟");
    }
    
    /**
     * 停止定时保存任务
     */
    public void stop() {
        if (saveTask != null && !saveTask.isCancelled()) {
            plugin.getAsyncTaskManager().cancelTask(saveTask);
            logger.info("数据自动保存任务已停止");
        }

        // 关闭时必须同步等待数据保存完成，防止数据丢失
        logger.info("关闭前执行最终数据保存...");
        try {
            long startTime = System.currentTimeMillis();

            // 仅等待持久化写入完成，最多阻塞 10 秒，避免主线程长时间卡死
            variablesManager.saveAllData(false).get(10, TimeUnit.SECONDS);

            long duration = System.currentTimeMillis() - startTime;
            logger.info("关闭前数据保存完成，耗时: " + duration + "ms");

            // 异步触发 SQLite 刷盘，关闭时使用简化操作，避免阻塞主线程
            try {
                plugin.getDatabase().flushDatabase(true)  // 关闭时使用简化版本
                        .exceptionally(t -> {
                            logger.warn("关闭阶段触发SQLite刷盘失败或超时(忽略继续): " + (t.getMessage() != null ? t.getMessage() : String.valueOf(t)));
                            return null;
                        });
            } catch (Exception ignore) {
                // 忽略刷盘触发中的异常，保证关闭流程继续
            }

        } catch (TimeoutException e) {
            logger.error("关闭前数据保存超时！可能存在数据丢失风险", e);
        } catch (Exception e) {
            logger.error("关闭前数据保存失败！", e);
        }
    }
    
    /**
     * 执行数据保存
     */
    private void performSave() {
        long startTime = System.currentTimeMillis();
        
        // 异步保存数据，避免阻塞定时任务线程
        variablesManager.saveAllData()
            .thenRun(() -> {
                long duration = System.currentTimeMillis() - startTime;
                logger.debug("数据保存完成，耗时: " + duration + "ms");
            })
            .exceptionally(throwable -> {
                logger.error("数据保存任务异常", throwable);
                return null;
            });
    }
    
    /**
     * 手动触发保存
     */
    public void saveNow() {
        plugin.getAsyncTaskManager().submitAsync(this::performSave);
    }
    
    /**
     * 检查任务是否正在运行
     */
    public boolean isRunning() {
        return saveTask != null && !saveTask.isCancelled();
    }
}
