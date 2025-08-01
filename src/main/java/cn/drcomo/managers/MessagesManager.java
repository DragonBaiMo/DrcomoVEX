package cn.drcomo.managers;

import cn.drcomo.corelib.color.ColorUtil;
import org.bukkit.command.CommandSender;

/**
 * 负责处理消息前缀与颜色代码的工具管理器。
 * <p>使用 {@link ColorUtil} 对文本进行颜色转换，并将消息发送给指定的命令发送者。</p>
 */
public class MessagesManager {

    private final String prefix;

    /**
     * 使用指定前缀创建消息管理器。
     *
     * @param prefix 消息前缀，支持传统与十六进制颜色代码
     */
    public MessagesManager(String prefix) {
        this.prefix = ColorUtil.translateColors(prefix);
    }

    /**
     * 向接收者发送消息，可选择是否附带前缀。
     *
     * @param sender     接收消息的命令发送者
     * @param message    待发送的消息内容
     * @param withPrefix 是否在消息前附带统一前缀
     */
    public void sendMessage(CommandSender sender, String message, boolean withPrefix) {
        if (!message.isEmpty()) {
            String msg = withPrefix ? this.prefix + message : message;
            sender.sendMessage(getColoredMessage(msg));
        }
    }

    /**
     * 将文本中的颜色代码转换为 Bukkit 可识别的彩色文本。
     *
     * @param message 原始消息文本
     * @return 已转换颜色代码的文本
     */
    public static String getColoredMessage(String message) {
        return ColorUtil.translateColors(message);
    }
}

