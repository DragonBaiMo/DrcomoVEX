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
     * 发送单条消息并替换占位符
     */
    public void sendMessage(CommandSender sender, String messageKey, Map<String, String> placeholders) {
        Player player = sender instanceof Player ? (Player) sender : null;
        // 使用 parseWithDelimiter 并指定 { 和 } 作为占位符分界符
        String parsedMessage = messageService.parseWithDelimiter(messageKey, player, placeholders, "{", "}");
        if (parsedMessage != null && !parsedMessage.isEmpty()) {
            sender.sendMessage(parsedMessage);
        }
    }

    /**
     * 发送多条消息并替换占位符
     */
    public void sendMessageList(CommandSender sender, String messageKey, Map<String, String> placeholders) {
        // 首先获取原始的消息列表
        List<String> templates = messageService.getList(messageKey);
        if (templates != null && !templates.isEmpty()) {
            // 然后调用 sendList 的重载方法，该方法接受一个模板列表并允许指定分界符
            messageService.sendList(sender, templates, placeholders, "{", "}");
        }
    }
}