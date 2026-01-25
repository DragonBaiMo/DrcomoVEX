package cn.drcomo.tasks;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.managers.RefactoredVariablesManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * 渐进恢复刷新任务
 */
public class VariableRegenTask {

    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final RefactoredVariablesManager variablesManager;
    private final FileConfiguration config;
    private BukkitTask task;

    public VariableRegenTask(
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

    public void start() {
        boolean enabled = config.getBoolean("regen.enabled", true);
        if (!enabled) {
            logger.info("渐进恢复刷新任务已禁用");
            return;
        }
        int intervalSeconds = Math.max(1, config.getInt("regen.tick-interval-seconds", 1));
        long intervalTicks = intervalSeconds * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, intervalTicks, intervalTicks);
        logger.info("已启动渐进恢复刷新任务，间隔: " + intervalSeconds + " 秒");
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
            logger.info("渐进恢复刷新任务已停止");
        }
    }

    private void tick() {
        try {
            List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
            long activeWindowMillis = Math.max(1000, config.getLong("regen.active-window-millis", 15000));
            int maxPlayers = Math.max(1, config.getInt("regen.max-players-per-tick", 64));
            variablesManager.tickRegenForOnlinePlayers(online, activeWindowMillis, maxPlayers);
        } catch (Exception e) {
            logger.debug("渐进恢复刷新任务异常: " + e.getMessage());
        }
    }
}
