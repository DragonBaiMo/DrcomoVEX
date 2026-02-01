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

    // 记录一次告警，避免刷屏
    private boolean warnedActiveWindow;

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
            int intervalSeconds = Math.max(1, config.getInt("regen.tick-interval-seconds", 1));
            long intervalMillis = intervalSeconds * 1000L;

            long configuredWindow = Math.max(1000, config.getLong("regen.active-window-millis", 15000));
            // 关键修复：activeWindow 不能小于 tick 间隔，否则玩家很容易在下一次 tick 前“失活”，导致 regen 永远不执行。
            // 例如 tick=29s 而 window=15s，会导致除非玩家每 15s 主动触发一次 get，否则 regen 永远不跑。
            long activeWindowMillis = Math.max(configuredWindow, intervalMillis + 1000L);
            if (!warnedActiveWindow && configuredWindow < intervalMillis) {
                warnedActiveWindow = true;
                logger.warn("检测到 regen.active-window-millis(" + configuredWindow + "ms) < regen.tick-interval-seconds(" + intervalSeconds
                        + "s)，已自动提升有效 activeWindow 至 " + activeWindowMillis + "ms，避免 regen 不执行。建议配置 active-window-millis >= "
                        + intervalMillis + "ms");
            }
            int maxPlayers = Math.max(1, config.getInt("regen.max-players-per-tick", 64));
            variablesManager.tickRegenForOnlinePlayers(online, activeWindowMillis, maxPlayers);
        } catch (Exception e) {
            logger.debug("渐进恢复刷新任务异常: " + e.getMessage());
        }
    }
}
