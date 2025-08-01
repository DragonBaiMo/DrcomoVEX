package cn.drcomo.managers;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.command.CommandSender;
import cn.drcomo.utils.OtherUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessagesManager {

    private String prefix;
    public MessagesManager(String prefix) {
        this.prefix = prefix;
    }

    public void sendMessage(CommandSender sender, String message, boolean prefix) {
        if(!message.isEmpty()) {
            if(prefix) {
                sender.sendMessage(getColoredMessage(this.prefix+message));
            }else {
                sender.sendMessage(getColoredMessage(message));
            }
        }
    }

    public static String getColoredMessage(String message) {
        if(OtherUtils.isNew()) {
            Pattern pattern = Pattern.compile("#[a-fA-F0-9]{6}");
            Matcher match = pattern.matcher(message);

            while(match.find()) {
                String color = message.substring(match.start(),match.end());
                message = message.replace(color, ChatColor.of(color)+"");

                match = pattern.matcher(message);
            }
        }

        message = ChatColor.translateAlternateColorCodes('&', message);
        return message;
    }
}
