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
            // 预加载玩家变量数据到内存（高性能优化）
            plugin.getVariablesManager().handlePlayerJoin(event.getPlayer())
                    .thenRun(() -> logger.debug("玩家 " + event.getPlayer().getName() + " 变量数据预加载完成"))
                    .exceptionally(throwable -> {
                        logger.error("玩家变量数据预加载失败: " + event.getPlayer().getName(), throwable);
                        return null;
                    });
            
            logger.debug("玩家 " + event.getPlayer().getName() + " 登入，变量数据预加载中");
            
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
            // 立即持久化玩家变量数据（高性能优化）
            plugin.getVariablesManager().handlePlayerQuit(event.getPlayer())
                    .thenRun(() -> logger.debug("玩家 " + event.getPlayer().getName() + " 变量数据持久化完成"))
                    .exceptionally(throwable -> {
                        logger.error("玩家变量数据持久化失败: " + event.getPlayer().getName(), throwable);
                        return null;
                    });
            
            logger.debug("玩家 " + event.getPlayer().getName() + " 退出，变量数据持久化中");
            
        } catch (Exception e) {
            logger.error("处理玩家退出事件失败: " + event.getPlayer().getName(), e);
        }
    }
}