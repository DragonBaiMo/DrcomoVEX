package cn.drcomo;

import cn.drcomo.managers.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import cn.drcomo.api.ServerVariablesAPI;

import cn.drcomo.config.ConfigsManager;
import cn.drcomo.database.MySQLConnection;
import cn.drcomo.listeners.PlayerListener;
import cn.drcomo.model.internal.UpdateCheckerResult;
import cn.drcomo.tasks.DataSaveTask;
import cn.drcomo.utils.ServerVersion;
import cn.drcomo.corelib.hook.placeholder.PlaceholderAPIUtil;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.corelib.config.YamlUtil;
import cn.drcomo.corelib.async.AsyncTaskManager;


public class DrcomoVEX extends JavaPlugin {

    public static ServerVersion serverVersion;
    private final PluginDescriptionFile pdfFile = getDescription();
    public String version = pdfFile.getVersion();

    private VariablesManager variablesManager;
    private ServerVariablesManager serverVariablesManager;
    private PlayerVariablesManager playerVariablesManager;
    private MessagesManager messagesManager;
    private ConfigsManager configsManager;
    private UpdateCheckerManager updateCheckerManager;

    private DataSaveTask dataSaveTask;

    private MySQLConnection mySQLConnection;
    private DebugUtil logger;
    private YamlUtil yamlUtil;
    private PlaceholderAPIUtil placeholderUtil;
    private cn.drcomo.corelib.message.MessageService messageService;
    private AsyncTaskManager asyncTaskManager;

    /**
     * 插件启用时的初始化逻辑。
     * <p>负责实例化核心库工具、加载配置并注册监听与指令。</p>
     */
    public void onEnable(){
        setVersion();

        this.logger = new DebugUtil(this, DebugUtil.LogLevel.INFO);
        this.yamlUtil = new YamlUtil(this, logger);
        this.placeholderUtil = new PlaceholderAPIUtil(this, getName().toLowerCase());
        // 创建消息服务（默认语言文件路径可根据配置调整）
        String languagePath = "languages/zh_CN";
        String keyPrefix = "messages.";
        this.messageService = new cn.drcomo.corelib.message.MessageService(
                this,
                logger,
                yamlUtil,
                placeholderUtil,
                languagePath,
                keyPrefix
        );
        this.asyncTaskManager = new AsyncTaskManager(this, logger);

        this.variablesManager = new VariablesManager(this);
        this.serverVariablesManager = new ServerVariablesManager(this);
        this.playerVariablesManager = new PlayerVariablesManager(this);
        registerCommands();
        registerEvents();

        this.configsManager = new ConfigsManager(this, yamlUtil);
        this.configsManager.configure();

        // 初始化 API
        new ServerVariablesAPI(this);

        // -----------------------------
        // 通过核心库 PlaceholderAPIUtil 注册占位符
        // 前缀将自动使用在 onEnable 早期创建的 placeholderUtil 标识符
        // %<plugin>_globalvalue_<variable>%
        placeholderUtil.register("globalvalue", (player, rawArgs) ->
                ServerVariablesAPI.getServerVariableValue(rawArgs));

        placeholderUtil.register("globaldisplay", (player, rawArgs) ->
                ServerVariablesAPI.getServerVariableDisplay(rawArgs));

        placeholderUtil.register("value_otherplayer", (player, rawArgs) -> {
            int idx = rawArgs.indexOf(":");
            if (idx == -1) {
                return "";
            }
            String playerName = rawArgs.substring(0, idx);
            String variable = rawArgs.substring(idx + 1);
            return ServerVariablesAPI.getPlayerVariableValue(playerName, variable);
        });

        placeholderUtil.register("display_otherplayer", (player, rawArgs) -> {
            int idx = rawArgs.indexOf(":");
            if (idx == -1) {
                return "";
            }
            String playerName = rawArgs.substring(0, idx);
            String variable = rawArgs.substring(idx + 1);
            return ServerVariablesAPI.getPlayerVariableDisplay(playerName, variable);
        });

        placeholderUtil.register("value", (player, rawArgs) -> {
            if (player == null) {
                return "";
            }
            return ServerVariablesAPI.getPlayerVariableValue(player.getName(), rawArgs);
        });

        placeholderUtil.register("display", (player, rawArgs) -> {
            if (player == null) {
                return "";
            }
            return ServerVariablesAPI.getPlayerVariableDisplay(player.getName(), rawArgs);
        });

        placeholderUtil.register("initial_value", (player, rawArgs) ->
                ServerVariablesAPI.getVariableInitialValue(rawArgs));

        if(configsManager.getMainConfigManager().isMySQL()){
            mySQLConnection = new MySQLConnection(this);
            mySQLConnection.setupMySql();
        }

        String prefix = messagesManager.getPrefix();
        logger.info(messagesManager.translate(prefix + " &eHas been enabled! &fVersion: " + version, null));
        logger.info(messagesManager.translate(prefix + " &eThanks for using my plugin!   &f~Ajneb97", null));

        updateCheckerManager = new UpdateCheckerManager(version);
        updateMessage(updateCheckerManager.check());
    }

    /**
     * 插件卸载时的收尾逻辑。
     * <p>保存变量数据并输出关闭信息。</p>
     */
    public void onDisable(){
        this.configsManager.saveServerData();
        this.configsManager.savePlayerData();
        if(asyncTaskManager != null){
            asyncTaskManager.shutdown();
        }
        logger.info(messagesManager.translate(messagesManager.getPrefix() + " &eHas been disabled! &fVersion: " + version, null));
    }

    public void setVersion(){
        String packageName = Bukkit.getServer().getClass().getPackage().getName();
        String bukkitVersion = Bukkit.getServer().getBukkitVersion().split("-")[0];
        switch(bukkitVersion){
            case "1.20.5":
            case "1.20.6":
                serverVersion = ServerVersion.v1_20_R4;
                break;
            case "1.21":
            case "1.21.1":
                serverVersion = ServerVersion.v1_21_R1;
                break;
            case "1.21.2":
            case "1.21.3":
                serverVersion = ServerVersion.v1_21_R2;
                break;
            case "1.21.4":
                serverVersion = ServerVersion.v1_21_R3;
                break;
            case "1.21.5":
                serverVersion = ServerVersion.v1_21_R4;
                break;
            case "1.21.6":
            case "1.21.7":
            case "1.21.8":
                serverVersion = ServerVersion.v1_21_R5;
                break;
            default:
                try{
                    serverVersion = ServerVersion.valueOf(packageName.replace("org.bukkit.craftbukkit.", ""));
                }catch(Exception e){
                    serverVersion = ServerVersion.v1_21_R5;
                }
        }
    }

    public VariablesManager getVariablesManager() {
        return variablesManager;
    }

    public ServerVariablesManager getServerVariablesManager() {
        return serverVariablesManager;
    }

    public MessagesManager getMessagesManager() {
        return messagesManager;
    }

    public void setMessagesManager(MessagesManager messagesManager) {
        this.messagesManager = messagesManager;
    }

    public ConfigsManager getConfigsManager() {
        return configsManager;
    }

    public PlayerVariablesManager getPlayerVariablesManager() {
        return playerVariablesManager;
    }
    public void registerEvents() {
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new PlayerListener(this), this);
    }

    public DataSaveTask getDataSaveTask() {
        return dataSaveTask;
    }

    public void setDataSaveTask(DataSaveTask dataSaveTask) {
        this.dataSaveTask = dataSaveTask;
    }
    public UpdateCheckerManager getUpdateCheckerManager() {
        return updateCheckerManager;
    }

    public void registerCommands(){
        this.getCommand("servervariables").setExecutor(new MainCommand(this));
    }

    public MySQLConnection getMySQLConnection() {
        return mySQLConnection;
    }

    /**
     * 获取调试日志工具。
     *
     * @return 核心库提供的调试日志实例
     */
    public DebugUtil getDebug() {
        return logger;
    }

    /**
     * 获取 YAML 配置工具实例。
     *
     * @return {@link YamlUtil} 实例
     */
    public YamlUtil getYamlUtil() {
        return yamlUtil;
    }

    /**
     * 获取占位符解析工具。
     *
     * @return {@link PlaceholderAPIUtil} 实例
     */
    public PlaceholderAPIUtil getPlaceholderUtil() {
        return placeholderUtil;
    }

    /**
     * 获取消息服务实例。
     *
     * @return MessageService 实例
     */
    public cn.drcomo.corelib.message.MessageService getMessageService() {
        return messageService;
    }

    /**
     * 获取异步任务管理器。
     *
     * @return 异步任务管理器实例
     */
    public AsyncTaskManager getAsyncTaskManager() {
        return asyncTaskManager;
    }

    /**
     * 根据更新检查结果输出提示信息。
     *
     * @param result 更新检查返回结果
     */
    public void updateMessage(UpdateCheckerResult result){
        if(!result.isError()){
            String latestVersion = result.getLatestVersion();
            if(latestVersion != null){
                logger.info(messagesManager.translate("&cThere is a new version available. &e(&7" + latestVersion + "&e)", null));
                logger.info(messagesManager.translate("&cYou can download it at: &fhttps://modrinth.com/plugin/servervariables", null));
            }
        }else{
            logger.error(messagesManager.translate(messagesManager.getPrefix() + " &cError while checking update.", null));
        }

    }
}
