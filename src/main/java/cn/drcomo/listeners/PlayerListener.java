package cn.drcomo.listeners;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.managers.PlayerVariablesManager;
import cn.drcomo.corelib.util.DebugUtil;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家事件监听器
 * 
 * 处理玩家登入和退出事件，管理玩家数据的加载和保存。
 * 
 * @author BaiMo
 */
public class PlayerListener implements Listener {
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final PlayerVariablesManager playerVariablesManager;
    
    public PlayerListener(
            DrcomoVEX plugin,
            DebugUtil logger,
            PlayerVariablesManager playerVariablesManager
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.playerVariablesManager = playerVariablesManager;
    }
    
    /**
     * 玩家加入事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        try {
            // 加载玩家配置
            plugin.getConfigsManager().getPlayerConfigsManager()
                    .loadPlayerConfig(event.getPlayer().getUniqueId());
            
            // 更新最后登入时间
            plugin.getConfigsManager().getPlayerConfigsManager()
                    .setPlayerValue(event.getPlayer().getUniqueId(), "last-seen", System.currentTimeMillis());
            
            // 增加登入次数
            int currentLogins = plugin.getConfigsManager().getPlayerConfigsManager()
                    .getPlayerInt(event.getPlayer().getUniqueId(), "statistics.total-logins", 0);
            plugin.getConfigsManager().getPlayerConfigsManager()
                    .setPlayerValue(event.getPlayer().getUniqueId(), "statistics.total-logins", currentLogins + 1);
            
            logger.debug("玩家 " + event.getPlayer().getName() + " 登入，数据已加载");
            
        } catch (Exception e) {
            logger.error("处理玩家登入事件失败: " + event.getPlayer().getName(), e);
        }
    }
    
    /**
     * 玩家退出事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        try {
            // 更新最后离线时间
            plugin.getConfigsManager().getPlayerConfigsManager()
                    .setPlayerValue(event.getPlayer().getUniqueId(), "last-seen", System.currentTimeMillis());
            
            // 如果配置了退出时保存数据
            if (plugin.getConfigsManager().getMainConfig().getBoolean("data.save-on-player-quit", true)) {
                // 保存玩家配置
                plugin.getConfigsManager().getPlayerConfigsManager()
                        .savePlayerConfig(event.getPlayer().getUniqueId());
                
                logger.debug("玩家 " + event.getPlayer().getName() + " 退出，数据已保存");
            }
            
            // 卸载玩家配置（节省内存）
            plugin.getAsyncTaskManager().submitAsync(() -> {
                try {
                    // 延迟5秒卸载，防止玩家快速重连
                    Thread.sleep(5000);
                    plugin.getConfigsManager().getPlayerConfigsManager()
                            .unloadPlayerConfig(event.getPlayer().getUniqueId());
                } catch (Exception e) {
                    logger.error("延迟卸载玩家配置失败", e);
                }
            });
            
        } catch (Exception e) {
            logger.error("处理玩家退出事件失败: " + event.getPlayer().getName(), e);
        }
    }
}