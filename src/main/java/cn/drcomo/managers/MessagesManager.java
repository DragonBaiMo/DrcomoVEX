package cn.drcomo.managers;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.corelib.message.MessageService;
import cn.drcomo.corelib.color.ColorUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * 消息管理器
 * 
 * 统一管理插件的所有消息发送，支持多语言和占位符解析。
 * 
 * @author BaiMo
 */
public class MessagesManager {
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final MessageService messageService;
    
    public MessagesManager(DrcomoVEX plugin, DebugUtil logger, MessageService messageService) {
        this.plugin = plugin;
        this.logger = logger;
        this.messageService = messageService;
    }
    
    /**
     * 初始化消息管理器
     */
    public void initialize() {
        logger.info("正在初始化消息管理器...");
        
        try {
            // 消息服务已经在主类中初始化
            
            // 注册内部占位符
            registerInternalPlaceholders();
            
            logger.info("消息管理器初始化完成！");
        } catch (Exception e) {
            logger.error("消息管理器初始化失败！", e);
        }
    }
    
    /**
     * 重载消息配置
     */
    public void reload() {
        logger.info("正在重载消息配置...");
        
        try {
            // MessageService 将自动重载配置文件
            logger.info("消息配置重载完成！");
        } catch (Exception e) {
            logger.error("消息配置重载失败！", e);
        }
    }
    
    /**
     * 注册内部占位符
     */
    private void registerInternalPlaceholders() {
        // 插件基本信息
        messageService.registerInternalPlaceholder("plugin_name", (player, args) -> plugin.getName());
        messageService.registerInternalPlaceholder("plugin_version", (player, args) -> plugin.getDescription().getVersion());
        messageService.registerInternalPlaceholder("plugin_author", (player, args) -> "BaiMo");
        
        // 系统信息
        messageService.registerInternalPlaceholder("server_name", (player, args) -> plugin.getServer().getName());
        messageService.registerInternalPlaceholder("server_version", (player, args) -> plugin.getServer().getVersion());
        messageService.registerInternalPlaceholder("online_players", (player, args) -> String.valueOf(plugin.getServer().getOnlinePlayers().size()));
        messageService.registerInternalPlaceholder("max_players", (player, args) -> String.valueOf(plugin.getServer().getMaxPlayers()));
        
        // 变量统计
        messageService.registerInternalPlaceholder("total_variables", (player, args) -> 
                String.valueOf(plugin.getVariablesManager().getAllVariableKeys().size()));
        messageService.registerInternalPlaceholder("database_type", (player, args) -> 
                plugin.getDatabase().getDatabaseType().toUpperCase());
        messageService.registerInternalPlaceholder("database_status", (player, args) -> 
                plugin.getDatabase().isConnectionValid() ? "正常" : "异常");
        
        // 时间信息
        messageService.registerInternalPlaceholder("current_time", (player, args) -> 
                String.valueOf(System.currentTimeMillis()));
        messageService.registerInternalPlaceholder("formatted_time", (player, args) -> 
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()));
        
        logger.debug("已注册所有内部占位符");
    }
    
    /**
     * 发送单条消息
     *
     * <p>发送前会自动转换颜色代码。</p>
     */
    public void sendMessage(CommandSender sender, String messageKey) {
        sendMessage(sender, messageKey, null);
    }

    /**
     * 发送单条消息并替换占位符
     *
     * <p>发送前会自动转换颜色代码。</p>
     */
    public void sendMessage(CommandSender sender, String messageKey, Map<String, String> placeholders) {
        try {
            if (sender instanceof Player) {
                // 使用PlaceholderAPIUtil的parse方法来处理{}格式占位符
                String rawMessage = messageService.getRaw(messageKey);
                if (rawMessage != null) {
                    // 先用PlaceholderAPIUtil处理{}占位符，再用MessageService处理内置占位符
                    String message = ((DrcomoVEX) plugin).getPlaceholderUtil().parse((Player) sender, rawMessage, placeholders != null ? placeholders : new java.util.HashMap<>());
                    message = ColorUtil.translateColors(message);
                    messageService.sendRaw((Player) sender, message);
                }
            } else {
                // 对于控制台，获取原始消息并手动处理占位符
                String rawMessage = messageService.getRaw(messageKey);
                if (rawMessage != null) {
                    String message = ((DrcomoVEX) plugin).getPlaceholderUtil().parse(null, rawMessage, placeholders != null ? placeholders : new java.util.HashMap<>());
                    if (message != null && !message.trim().isEmpty()) {
                        message = ColorUtil.translateColors(message);
                        // 移除颜色码
                        message = message.replaceAll("§[0-9a-fk-or]", "");
                        sender.sendMessage(message);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("发送消息失败: " + messageKey, e);
            // 发送备用消息
            sender.sendMessage("§c消息发送失败，请联系管理员");
        }
    }
    
    /**
     * 发送多条消息
     *
     * <p>发送前会自动转换颜色代码。</p>
     */
    public void sendMessageList(CommandSender sender, String messageKey) {
        sendMessageList(sender, messageKey, null);
    }

    /**
     * 发送多条消息并替换占位符
     *
     * <p>发送前会自动转换颜色代码。</p>
     */
    public void sendMessageList(CommandSender sender, String messageKey, Map<String, String> placeholders) {
        try {
            if (sender instanceof Player) {
                // 获取原始消息列表
                List<String> rawMessages = messageService.getList(messageKey);
                if (rawMessages != null && !rawMessages.isEmpty()) {
                    for (String rawMessage : rawMessages) {
                        if (rawMessage != null && !rawMessage.trim().isEmpty()) {
                            // 对每条消息使用PlaceholderAPIUtil处理{}占位符
                            String message = ((DrcomoVEX) plugin).getPlaceholderUtil().parse((Player) sender, rawMessage, placeholders != null ? placeholders : new java.util.HashMap<>());
                            message = ColorUtil.translateColors(message);
                            messageService.sendRaw((Player) sender, message);
                        }
                    }
                }
            } else {
                // 对于控制台，获取列表消息并逐条发送
                List<String> rawMessages = messageService.getList(messageKey);
                if (rawMessages != null && !rawMessages.isEmpty()) {
                    for (String rawMessage : rawMessages) {
                        if (rawMessage != null && !rawMessage.trim().isEmpty()) {
                            String message = ((DrcomoVEX) plugin).getPlaceholderUtil().parse(null, rawMessage, placeholders != null ? placeholders : new java.util.HashMap<>());
                            if (message != null) {
                                message = ColorUtil.translateColors(message);
                                // 移除颜色码
                                message = message.replaceAll("§[0-9a-fk-or]", "");
                                sender.sendMessage(message);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("发送消息列表失败: " + messageKey, e);
            // 发送备用消息
            sender.sendMessage("§c消息发送失败，请联系管理员");
        }
    }
    
    /**
     * 发送 ActionBar 消息
     *
     * <p>发送前会自动转换颜色代码。</p>
     */
    public void sendActionBar(Player player, String messageKey) {
        sendActionBar(player, messageKey, null);
    }

    /**
     * 发送 ActionBar 消息并替换占位符
     *
     * <p>发送前会自动转换颜色代码。</p>
     */
    public void sendActionBar(Player player, String messageKey, Map<String, String> placeholders) {
        try {
            // 获取原始消息并使用PlaceholderAPIUtil处理{}占位符
            String rawMessage = messageService.getRaw(messageKey);
            if (rawMessage != null) {
                String message = ((DrcomoVEX) plugin).getPlaceholderUtil().parse(player, rawMessage, placeholders != null ? placeholders : new java.util.HashMap<>());
                message = ColorUtil.translateColors(message);
                messageService.sendRaw(player, message);
            }
        } catch (Exception e) {
            logger.error("发送 ActionBar 消息失败: " + messageKey, e);
        }
    }
    
    /**
     * 发送 Title 消息
     *
     * <p>发送前会自动转换颜色代码。</p>
     */
    public void sendTitle(Player player, String titleKey, String subtitleKey) {
        sendTitle(player, titleKey, subtitleKey, null);
    }

    /**
     * 发送 Title 消息并替换占位符
     *
     * <p>发送前会自动转换颜色代码。</p>
     */
    public void sendTitle(Player player, String titleKey, String subtitleKey, Map<String, String> placeholders) {
        try {
            // 获取原始消息并使用PlaceholderAPIUtil处理{}占位符
            String rawTitle = messageService.getRaw(titleKey);
            String rawSubtitle = messageService.getRaw(subtitleKey);
            
            String title = null;
            String subtitle = null;
            
            if (rawTitle != null) {
                title = ((DrcomoVEX) plugin).getPlaceholderUtil().parse(player, rawTitle, placeholders != null ? placeholders : new java.util.HashMap<>());
                if (title != null) {
                    title = ColorUtil.translateColors(title);
                }
            }
            if (rawSubtitle != null) {
                subtitle = ((DrcomoVEX) plugin).getPlaceholderUtil().parse(player, rawSubtitle, placeholders != null ? placeholders : new java.util.HashMap<>());
                if (subtitle != null) {
                    subtitle = ColorUtil.translateColors(subtitle);
                }
            }

            if (title != null || subtitle != null) {
                player.sendTitle(title != null ? title : "", subtitle != null ? subtitle : "", 10, 70, 20);
            }
        } catch (Exception e) {
            logger.error("发送 Title 消息失败: " + titleKey + ", " + subtitleKey, e);
        }
    }
    
    /**
     * 只解析消息不发送
     */
    public String parseMessage(Player player, String messageKey) {
        return parseMessage(player, messageKey, null);
    }
    
    /**
     * 只解析消息不发送并替换占位符
     */
    public String parseMessage(Player player, String messageKey, Map<String, String> placeholders) {
        try {
            // 获取原始消息并使用PlaceholderAPIUtil处理{}占位符
            String rawMessage = messageService.getRaw(messageKey);
            if (rawMessage != null) {
                return ((DrcomoVEX) plugin).getPlaceholderUtil().parse(player, rawMessage, placeholders != null ? placeholders : new java.util.HashMap<>());
            }
            return null;
        } catch (Exception e) {
            logger.error("解析消息失败: " + messageKey, e);
            return "§c消息解析失败";
        }
    }
    
    /**
     * 解析消息列表
     */
    public List<String> parseMessageList(Player player, String messageKey) {
        return parseMessageList(player, messageKey, null);
    }
    
    /**
     * 解析消息列表并替换占位符
     */
    public List<String> parseMessageList(Player player, String messageKey, Map<String, String> placeholders) {
        try {
            return messageService.parseList(messageKey, player, placeholders);
        } catch (Exception e) {
            logger.error("解析消息列表失败: " + messageKey, e);
            return java.util.Collections.singletonList("§c消息解析失败");
        }
    }
    
    /**
     * 检查消息是否存在
     */
    public boolean hasMessage(String messageKey) {
        try {
            String message = messageService.parse(messageKey, null, null);
            return message != null && !message.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * 向所有在线玩家广播消息
     */
    public void broadcast(String messageKey) {
        broadcast(messageKey, null);
    }
    
    /**
     * 向所有在线玩家广播消息并替换占位符
     */
    public void broadcast(String messageKey, Map<String, String> placeholders) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            sendMessage(player, messageKey, placeholders);
        }
    }
    
    /**
     * 向有权限的玩家广播消息
     */
    public void broadcastToPermission(String permission, String messageKey) {
        broadcastToPermission(permission, messageKey, null);
    }
    
    /**
     * 向有权限的玩家广播消息并替换占位符
     */
    public void broadcastToPermission(String permission, String messageKey, Map<String, String> placeholders) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.hasPermission(permission)) {
                sendMessage(player, messageKey, placeholders);
            }
        }
    }
    
    /**
     * 获取 MessageService 实例
     */
    public MessageService getMessageService() {
        return messageService;
    }
}