package cn.drcomo.managers;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.corelib.message.MessageService;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    /**
     * 时间格式器
     */
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
        messageService.registerInternalPlaceholder("plugin_name", (Player player, String arg) -> plugin.getName());
        messageService.registerInternalPlaceholder("plugin_version", (Player player, String arg) -> plugin.getDescription().getVersion());
        messageService.registerInternalPlaceholder("plugin_author", (Player player, String arg) -> "BaiMo");
        
        // 系统信息
        messageService.registerInternalPlaceholder("server_name", (Player player, String arg) -> plugin.getServer().getName());
        messageService.registerInternalPlaceholder("server_version", (Player player, String arg) -> plugin.getServer().getVersion());
        messageService.registerInternalPlaceholder("online_players", (Player player, String arg) -> String.valueOf(plugin.getServer().getOnlinePlayers().size()));
        messageService.registerInternalPlaceholder("max_players", (Player player, String arg) -> String.valueOf(plugin.getServer().getMaxPlayers()));
        
        // 变量统计
        messageService.registerInternalPlaceholder("total_variables", (Player player, String arg) -> 
                String.valueOf(plugin.getVariablesManager().getAllVariableKeys().size()));
        messageService.registerInternalPlaceholder("database_type", (Player player, String arg) -> 
                plugin.getDatabase().getDatabaseType().toUpperCase());
        messageService.registerInternalPlaceholder("database_status", (Player player, String arg) -> 
                plugin.getDatabase().isConnectionValid() ? "正常" : "异常");
        
        // 时间信息
        messageService.registerInternalPlaceholder("current_time", (Player player, String arg) ->
                String.valueOf(System.currentTimeMillis()));
        messageService.registerInternalPlaceholder("formatted_time", (Player player, String arg) ->
                LocalDateTime.now().format(FORMATTER));
        
        logger.debug("已注册所有内部占位符");
    }

    /**
     * 发送单条消息并替换占位符
     */
    public void sendMessage(CommandSender sender, String messageKey, Map<String, String> placeholders) {
        try {
            Player player = sender instanceof Player ? (Player) sender : null;
            // 使用 parseWithDelimiter 并指定 { 和 } 作为占位符分界符
            String parsedMessage = messageService.parseWithDelimiter(messageKey, player, placeholders, "{", "}");
            if (parsedMessage != null && !parsedMessage.isEmpty()) {
                sender.sendMessage(parsedMessage);
            }
        } catch (Exception e) {
            logger.error("发送消息失败: " + messageKey + " - " + e.getMessage());
            // 降级处理：发送原始消息键
            String fallbackMessage = messageService.getRaw(messageKey);
            if (fallbackMessage != null) {
                sender.sendMessage(fallbackMessage);
            } else {
                sender.sendMessage("§c消息发送失败: " + messageKey);
            }
        }
    }

    /**
     * 发送多条消息并替换占位符
     */
    public void sendMessageList(CommandSender sender, String messageKey, Map<String, String> placeholders) {
        try {
            // 首先获取原始的消息列表
            List<String> templates = messageService.getList(messageKey);
            if (templates != null && !templates.isEmpty()) {
                // 然后调用 sendList 的重载方法，该方法接受一个模板列表并允许指定分界符
                messageService.sendList(sender, templates, placeholders, "{", "}");
            }
        } catch (Exception e) {
            logger.error("发送消息列表失败: " + messageKey + " - " + e.getMessage());
            // 降级处理：发送原始消息列表
            List<String> fallbackMessages = messageService.getList(messageKey);
            if (fallbackMessages != null && !fallbackMessages.isEmpty()) {
                for (String message : fallbackMessages) {
                    sender.sendMessage(message);
                }
            } else {
                sender.sendMessage("§c消息发送失败: " + messageKey);
            }
        }
    }
}