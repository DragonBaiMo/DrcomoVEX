package cn.drcomo;

import cn.drcomo.config.ConfigsManager;
import cn.drcomo.managers.*;
import cn.drcomo.listeners.PlayerListener;
import cn.drcomo.api.ServerVariablesAPI;
import cn.drcomo.tasks.DataSaveTask;
import cn.drcomo.tasks.VariableCycleTask;
import cn.drcomo.database.HikariConnection;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.corelib.config.YamlUtil;
import cn.drcomo.corelib.async.AsyncTaskManager;
import cn.drcomo.corelib.message.MessageService;
import cn.drcomo.corelib.hook.placeholder.PlaceholderAPIUtil;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;

/**
 * DrcomoVEX 变量扩展系统 主类
 * 
 * 这是一个基于直觉设计的服务器变量管理系统，支持智能类型推断、
 * 动态表达式计算、周期性重置和全能指令操作。
 * 
 * @author BaiMo
 * @version 1.0.0
 */
public class DrcomoVEX extends JavaPlugin {
    
    private static DrcomoVEX instance;
    
    // 核心工具类
    private DebugUtil logger;
    private YamlUtil yamlUtil;
    private AsyncTaskManager asyncTaskManager;
    private MessageService messageService;
    private PlaceholderAPIUtil placeholderUtil;
    
    // 业务管理器
    private ConfigsManager configsManager;
    private RefactoredVariablesManager variablesManager;
    private ServerVariablesManager serverVariablesManager;
    private PlayerVariablesManager playerVariablesManager;
    private MessagesManager messagesManager;

    // 数据库连接
    private HikariConnection database;
    
    // 定时任务
    private DataSaveTask dataSaveTask;
    private VariableCycleTask variableCycleTask;
    
    @Override
    public void onEnable() {
        instance = this;
        
        // 1. 初始化核心工具链
        initializeCoreTools();
        
        // 2. 初始化配置管理
        initializeConfigs();
        
        // 3. 初始化数据库连接
        initializeDatabase();
        
        // 4. 初始化业务管理器
        initializeManagers();
        
        // 5. 注册事件监听器
        registerListeners();
        
        // 6. 注册指令处理器
        registerCommands();
        
        // 7. 启动定时任务
        startScheduledTasks();
        
        // 8. 注册API接口
        registerAPI();

        logger.info("DrcomoVEX 变量扩展系统已成功启动！");
        logger.info("版本: 1.0.0 (代号: 直觉)");
        logger.info("感谢使用 DrcomoVEX - 让变量管理变得直观而强大！");
    }
    
    @Override
    public void onDisable() {
        logger.info("正在关闭 DrcomoVEX 变量扩展系统...");
        
        // 1. 停止定时任务
        if (dataSaveTask != null) {
            dataSaveTask.stop();
        }
        if (variableCycleTask != null) {
            variableCycleTask.stop();
        }
        
        // 2. 关闭高性能变量管理器（会自动持久化所有数据并关闭数据库）
        if (variablesManager != null) {
            try {
                // 使用超时等待关闭流程完成，确保数据完全持久化但避免无限阻塞
                variablesManager.shutdown().get(15, java.util.concurrent.TimeUnit.SECONDS);
                logger.info("变量管理器已安全关闭");
            } catch (java.util.concurrent.TimeoutException e) {
                logger.error("变量管理器关闭超时，强制继续关闭流程", e);
                // 超时时直接关闭数据库
                if (database != null) {
                    try {
                        database.close();
                    } catch (Exception dbEx) {
                        logger.error("强制关闭数据库时发生异常", dbEx);
                    }
                }
            } catch (Exception e) {
                logger.error("变量管理器关闭异常", e);
                // 异常时也尝试关闭数据库
                if (database != null) {
                    try {
                        database.close();
                    } catch (Exception dbEx) {
                        logger.error("异常情况下关闭数据库时发生异常", dbEx);
                    }
                }
            }
        } else {
            // 如果变量管理器为空，直接关闭数据库
            if (database != null) {
                database.close();
            }
        }
        
        // 4. 停止文件监听
        if (yamlUtil != null) {
            yamlUtil.stopAllWatches();
        }
        
        // 5. 关闭异步任务管理器
        if (asyncTaskManager != null) {
            asyncTaskManager.close();
        }
        
        logger.info("DrcomoVEX 已安全关闭，感谢使用！");
    }
    
    /**
     * 初始化核心工具链
     */
    private void initializeCoreTools() {
        // 日志工具
        logger = new DebugUtil(this, DebugUtil.LogLevel.INFO);
        // 配置工具
        yamlUtil = new YamlUtil(this, logger);
        
        // 异步任务管理器
        asyncTaskManager = AsyncTaskManager.newBuilder(this, logger)
                .poolSize(8)  // 增加到8个线程，提升并发处理能力
                .build();
        
        // PlaceholderAPI工具
        placeholderUtil = new PlaceholderAPIUtil(this, "drcomovex");
        
        // 消息服务
        messageService = new MessageService(
                this, logger, yamlUtil, placeholderUtil,
                "messages", "messages."
        );
    }
    
    /**
     * 初始化配置管理
     */
    private void initializeConfigs() {
        configsManager = new ConfigsManager(this, logger, yamlUtil);
        configsManager.initialize();

        // 从配置设置日志级别，确保 debug.level 生效
        try {
            String levelStr = configsManager.getMainConfig().getString("debug.level", "INFO");
            if (levelStr != null) {
                cn.drcomo.corelib.util.DebugUtil.LogLevel lvl = cn.drcomo.corelib.util.DebugUtil.LogLevel.valueOf(levelStr.trim().toUpperCase());
                logger.setLevel(lvl);
                logger.info("已应用日志级别: " + lvl);
            }
        } catch (Exception e) {
            logger.warn("解析日志级别失败，使用默认 INFO。请检查 config.yml 的 debug.level 配置");
        }
    }
    
    /**
     * 初始化数据库连接
     */
    private void initializeDatabase() {
        database = new HikariConnection(this, logger, configsManager, asyncTaskManager);
        database.initialize();
    }
    
    /**
     * 初始化业务管理器
     */
    private void initializeManagers() {
        // 消息管理器
        messagesManager = new MessagesManager(this, logger, messageService);
        
        // 变量管理器 (核心) - 高性能重构版
        variablesManager = new RefactoredVariablesManager(
                this, logger, yamlUtil, asyncTaskManager, 
                placeholderUtil, database
        );
        
        // 服务器变量管理器
        serverVariablesManager = new ServerVariablesManager(
                this, logger, variablesManager, database
        );
        
        // 玩家变量管理器
        playerVariablesManager = new PlayerVariablesManager(
                this, logger, variablesManager, database
        );
        
        // 初始化所有管理器
        messagesManager.initialize();
        
        // 异步初始化变量管理器
        variablesManager.initialize().thenRun(() -> {
            logger.info("高性能变量管理器初始化完成！");
            loadPersistedData();
        }).exceptionally(throwable -> {
            logger.error("变量管理器初始化失败！", throwable);
            return null;
        });
        
        serverVariablesManager.initialize();
        playerVariablesManager.initialize();
    }

    /**
     * 从数据库加载持久化变量数据到内存
     */
    private void loadPersistedData() {
        if (variablesManager != null) {
            variablesManager.loadPersistedData();
        }
    }
    
    /**
     * 注册事件监听器
     */
    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(
                new PlayerListener(this, logger, playerVariablesManager, serverVariablesManager),
                this
        );
    }
    
    /**
     * 注册指令处理器
     */
    private void registerCommands() {
        MainCommand mainCommand = new MainCommand(
                this, logger, messagesManager, variablesManager,
                serverVariablesManager, playerVariablesManager
        );
        getCommand("vex").setExecutor(mainCommand);
        getCommand("vex").setTabCompleter(mainCommand);
    }
    
    /**
     * 启动定时任务
     */
    private void startScheduledTasks() {
        dataSaveTask = new DataSaveTask(
                this, logger, variablesManager,
                configsManager.getMainConfig()
        );
        dataSaveTask.start();

        variableCycleTask = new VariableCycleTask(
                this, logger, variablesManager, configsManager, yamlUtil
        );
        variableCycleTask.start();
    }
    
    /**
     * 注册API接口
     */
    private void registerAPI() {
        ServerVariablesAPI api = new ServerVariablesAPI(
                logger, variablesManager, serverVariablesManager, playerVariablesManager
        );
        
        // 注册 PlaceholderAPI 扩展（PlaceholderAPIUtil 已自行处理 PAPI 是否可用）
        api.registerPlaceholders(placeholderUtil);
    }
    
    // Getter方法
    public static DrcomoVEX getInstance() {
        return instance;
    }
    

    public YamlUtil getYamlUtil() {
        return yamlUtil;
    }
    
    public AsyncTaskManager getAsyncTaskManager() {
        return asyncTaskManager;
    }
    
    public MessageService getMessageService() {
        return messageService;
    }
    
    public PlaceholderAPIUtil getPlaceholderUtil() {
        return placeholderUtil;
    }
    
    public ConfigsManager getConfigsManager() {
        return configsManager;
    }
    
    public RefactoredVariablesManager getVariablesManager() {
        return variablesManager;
    }
    
    public ServerVariablesManager getServerVariablesManager() {
        return serverVariablesManager;
    }
    
    public PlayerVariablesManager getPlayerVariablesManager() {
        return playerVariablesManager;
    }
    
    public MessagesManager getMessagesManager() {
        return messagesManager;
    }
    
    public HikariConnection getDatabase() {
        return database;
    }
}