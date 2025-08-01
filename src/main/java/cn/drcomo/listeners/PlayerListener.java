package cn.drcomo.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import cn.drcomo.DrcomoVEX;
import cn.drcomo.managers.MessagesManager;

public class PlayerListener implements Listener {

    private DrcomoVEX plugin;
    public PlayerListener(DrcomoVEX plugin) {
        this.plugin = plugin;
    }

    /**
     * 玩家加入服务器事件处理。
     * <p>用于初始化玩家数据并在需要时提示更新信息。</p>
     *
     * @param event 玩家加入事件
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event){
        Player player = event.getPlayer();

        //Create or update player data
        plugin.getPlayerVariablesManager().setJoinPlayerData(player);

        //Update notification
        String latestVersion = plugin.getUpdateCheckerManager().getLatestVersion();
        if(player.isOp() && plugin.getConfigsManager().getMainConfigManager().isUpdateNotify() && !plugin.version.equals(latestVersion)){
            MessagesManager msg = plugin.getMessagesManager();
            msg.sendMessage(player, "&cThere is a new version available. &e(&7" + latestVersion + "&e)", true);
            msg.sendMessage(player, "&cYou can download it at: &ahttps://modrinth.com/plugin/servervariables", false);
        }
    }
}
