package cn.drcomo.managers;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.corelib.message.MessageService;
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
            messageService.reloadLanguages();
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
     */
    public void sendMessage(CommandSender sender, String messageKey) {
        sendMessage(sender, messageKey, null);
    }

    /**
     * 发送单条消息并替换占位符
     */
    public void sendMessage(CommandSender sender, String messageKey, Map<String, String> placeholders) {
        messageService.send(sender, messageKey, placeholders);
    }

    /**
     * 发送多条消息
     */
    public void sendMessageList(CommandSender sender, String messageKey) {
        sendMessageList(sender, messageKey, null);
    }

    /**
     * 发送多条消息并替换占位符
     */
    public void sendMessageList(CommandSender sender, String messageKey, Map<String, String> placeholders) {
        messageService.sendList(sender, messageKey, placeholders);
    }
    
    /**
     * 发送 ActionBar 消息
     */
    public void sendActionBar(Player player, String messageKey) {
        sendActionBar(player, messageKey, null);
    }

    /**
     * 发送 ActionBar 消息并替换占位符
     */
    public void sendActionBar(Player player, String messageKey, Map<String, String> placeholders) {
        messageService.sendActionBar(player, messageKey, placeholders);
    }

    /**
     * 发送 Title 消息
     */
    public void sendTitle(Player player, String titleKey, String subtitleKey) {
        sendTitle(player, titleKey, subtitleKey, null);
    }

    /**
     * 发送 Title 消息并替换占位符
     */
    public void sendTitle(Player player, String titleKey, String subtitleKey, Map<String, String> placeholders) {
        messageService.sendTitle(player, titleKey, subtitleKey, placeholders);
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
        return messageService.parse(messageKey, player, placeholders);
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
        return messageService.parseList(messageKey, player, placeholders);
    }

    /**
     * 检查消息是否存在
     */
    public boolean hasMessage(String messageKey) {
        String raw = messageService.getRaw(messageKey);
        return raw != null && !raw.trim().isEmpty();
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
        messageService.broadcast(messageKey, placeholders, null);
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
        messageService.broadcast(messageKey, placeholders, permission);
    }

    /**
     * 获取 MessageService 实例
     */
    public MessageService getMessageService() {
        return messageService;
    }
}