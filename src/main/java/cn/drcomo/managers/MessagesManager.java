package cn.drcomo.managers;

import cn.drcomo.corelib.color.ColorUtil;
import cn.drcomo.corelib.hook.placeholder.PlaceholderAPIUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 负责处理消息前缀、颜色转换与占位符解析的工具管理器。
 * <p>基于核心库的 {@link ColorUtil} 以及 {@link PlaceholderAPIUtil}，
 * 为玩家或控制台发送经过颜色与占位符解析的消息。</p>
 */
public class MessagesManager {

    private final String prefix;
    private final PlaceholderAPIUtil placeholderUtil;

    /**
     * 使用指定前缀与 PAPI 工具创建消息管理器。
     *
     * @param prefix          消息前缀，支持传统与十六进制颜色代码
     * @param placeholderUtil 核心库提供的占位符解析工具
     */
    public MessagesManager(String prefix, PlaceholderAPIUtil placeholderUtil) {
        this.prefix = ColorUtil.translateColors(prefix);
        this.placeholderUtil = placeholderUtil;
    }

    /**
     * 向接收者发送消息，可选择是否附带前缀。
     *
     * @param sender     接收消息的命令发送者
     * @param message    待发送的消息内容
     * @param withPrefix 是否在消息前附带统一前缀
     */
    public void sendMessage(CommandSender sender, String message, boolean withPrefix) {
        if (message == null || message.isEmpty()) {
            return;
        }
        String msg = withPrefix ? this.prefix + message : message;
        Player player = sender instanceof Player ? (Player) sender : null;
        sender.sendMessage(translate(msg, player));
    }

    /**
     * 对消息执行占位符解析并转换颜色。
     *
     * @param message 原始消息文本
     * @param player  用于解析占位符的玩家对象，可为 {@code null}
     * @return 处理后的彩色文本
     */
    public String translate(String message, Player player) {
        String result = message;
        if (player != null) {
            result = placeholderUtil.parse(player, result);
        }
        return ColorUtil.translateColors(result);
    }

    /**
     * 获取当前统一前缀。
     *
     * @return 已转换颜色代码的前缀文本
     */
    public String getPrefix() {
        return prefix;
    }
}

