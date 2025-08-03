package cn.drcomo.tasks;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.managers.VariablesManager;
import cn.drcomo.corelib.util.DebugUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

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
    private final VariablesManager variablesManager;
    private final FileConfiguration config;
    
    private BukkitTask saveTask;
    
    public DataSaveTask(
            DrcomoVEX plugin,
            DebugUtil logger,
            VariablesManager variablesManager,
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
        long saveIntervalTicks = saveIntervalMinutes * 20L * 60L; // 转换为 ticks
        
        // 启动定时任务
        saveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::performSave,
                saveIntervalTicks, // 初始延迟
                saveIntervalTicks  // 重复间隔
        );
        
        logger.info("已启动数据自动保存任务，间隔: " + saveIntervalMinutes + " 分钟");
    }
    
    /**
     * 停止定时保存任务
     */
    public void stop() {
        if (saveTask != null && !saveTask.isCancelled()) {
            saveTask.cancel();
            logger.info("数据自动保存任务已停止");
        }
        
        // 最后保存一次
        performSave();
    }
    
    /**
     * 执行数据保存
     */
    private void performSave() {
        try {
            long startTime = System.currentTimeMillis();
            
            // 保存所有变量数据
            variablesManager.saveAllData();
            
            long duration = System.currentTimeMillis() - startTime;
            logger.debug("数据保存完成，耗时: " + duration + "ms");
            
        } catch (Exception e) {
            logger.error("数据保存任务异常", e);
        }
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