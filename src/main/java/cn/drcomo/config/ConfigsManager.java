package cn.drcomo.config;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.corelib.config.YamlUtil;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.Arrays;

/**
 * 配置管理器
 * 
 * 统一管理插件的所有配置文件，包括主配置、数据配置和玩家配置。
 * 
 * @author BaiMo
 */
public class ConfigsManager {
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final YamlUtil yamlUtil;
    
    private MainConfigManager mainConfigManager;
    private DataConfigManager dataConfigManager;
    
    public ConfigsManager(DrcomoVEX plugin, DebugUtil logger, YamlUtil yamlUtil) {
        this.plugin = plugin;
        this.logger = logger;
        this.yamlUtil = yamlUtil;
    }
    
    /**
     * 初始化所有配置管理器
     */
    public void initialize() {
        logger.info("正在初始化配置管理系统...");
        
        // 创建需要的目录
        createDirectories();
        
        // 初始化各个配置管理器
        mainConfigManager = new MainConfigManager(plugin, logger, yamlUtil);
        dataConfigManager = new DataConfigManager(plugin, logger, yamlUtil);
        
        // 初始化配置
        mainConfigManager.initialize();
        dataConfigManager.initialize();
        
        logger.info("配置管理系统初始化完成！");
    }
    
    /**
     * 重载所有配置
     */
    public void reload() {
        logger.info("正在重载所有配置...");
        
        mainConfigManager.reload();
        dataConfigManager.reload();
        
        logger.info("所有配置已重载完成！");
    }
    
    /**
     * 创建需要的目录并初始化默认文件
     */
    private void createDirectories() {
        // 主目录
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        
        // 变量配置目录 - 动态判断是否需要拷贝默认文件
        File variablesFolder = new File(dataFolder, "variables");
        if (!variablesFolder.exists()) {
            logger.info("首次运行，正在初始化变量配置目录...");
            // 从 JAR 包拷贝整个 variables 目录的内容, ensureFolderAndCopyDefaults 会自动创建目录
            yamlUtil.ensureFolderAndCopyDefaults("variables", "variables");
            logger.info("默认变量配置文件已拷贝完成！");
        }
    }
    
    // Getter 方法
    public MainConfigManager getMainConfigManager() {
        return mainConfigManager;
    }
    
    public DataConfigManager getDataConfigManager() {
        return dataConfigManager;
    }
    
    // 快捷访问方法
    public FileConfiguration getMainConfig() {
        return mainConfigManager.getConfig();
    }
    
    public FileConfiguration getDataConfig() {
        return dataConfigManager.getConfig();
    }
}