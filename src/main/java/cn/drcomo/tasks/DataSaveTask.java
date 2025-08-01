package cn.drcomo.tasks;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.async.AsyncTaskManager;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 数据定时保存任务。
 * <p>负责以固定频率异步保存服务器与玩家数据。</p>
 */
public class DataSaveTask {

    private final DrcomoVEX plugin;
    private final AsyncTaskManager asyncTaskManager;
    private ScheduledFuture<?> future;

    /**
     * 使用插件主类与异步任务管理器初始化任务。
     *
     * @param plugin           插件主类实例
     * @param asyncTaskManager 异步任务管理器
     */
    public DataSaveTask(DrcomoVEX plugin, AsyncTaskManager asyncTaskManager) {
        this.plugin = plugin;
        this.asyncTaskManager = asyncTaskManager;
    }

    /**
     * 结束当前定时任务。
     */
    public void end() {
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * 启动定时保存任务。
     *
     * @param minutes 间隔分钟数
     */
    public void start(int minutes) {
        future = asyncTaskManager.scheduleAtFixedRate(this::execute, 0L, minutes, TimeUnit.MINUTES);
    }

    /**
     * 执行保存逻辑。
     */
    public void execute() {
        plugin.getConfigsManager().saveServerData();
        plugin.getConfigsManager().savePlayerData();
    }
}

