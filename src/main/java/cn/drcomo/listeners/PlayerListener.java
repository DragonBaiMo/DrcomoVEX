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

    @EventHandler
    public void onJoin(PlayerJoinEvent event){
        Player player = event.getPlayer();

        //Create or update player data
        plugin.getPlayerVariablesManager().setJoinPlayerData(player);

        //Update notification
        String latestVersion = plugin.getUpdateCheckerManager().getLatestVersion();
        if(player.isOp() && plugin.getConfigsManager().getMainConfigManager().isUpdateNotify() && !plugin.version.equals(latestVersion)){
            player.sendMessage(MessagesManager.getColoredMessage(plugin.prefix+" &cThere is a new version available. &e(&7"+latestVersion+"&e)"));
            player.sendMessage(MessagesManager.getColoredMessage("&cYou can download it at: &ahttps://modrinth.com/plugin/servervariables"));
        }
    }
}
