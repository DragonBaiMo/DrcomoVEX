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
 * 统一管理插件的所有配置文件，包括主配置和变量配置。
 * 
 * @author BaiMo
 */
public class ConfigsManager {
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final YamlUtil yamlUtil;
    
    private MainConfigManager mainConfigManager;
    private VariablesConfigManager variablesConfigManager;
    
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
        
        // 初始化配置管理器
        mainConfigManager = new MainConfigManager(plugin, logger, yamlUtil);
        variablesConfigManager = new VariablesConfigManager(plugin, logger, yamlUtil);
        
        // 初始化配置
        mainConfigManager.initialize();
        variablesConfigManager.initialize();
        
        logger.info("配置管理系统初始化完成！");
    }
    
    /**
     * 重载所有配置
     */
    public void reload() {
        logger.info("正在重载所有配置...");
        
        mainConfigManager.reload();
        variablesConfigManager.reload();
        
        logger.info("所有配置已重载完成！");
    }
    
    /**
     * 创建需要的目录
     */
    private void createDirectories() {
        // 主目录
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        
        // 变量配置目录现在由 VariablesConfigManager 负责处理
    }
    
    // Getter 方法
    public MainConfigManager getMainConfigManager() {
        return mainConfigManager;
    }
    
    public VariablesConfigManager getVariablesConfigManager() {
        return variablesConfigManager;
    }
    
    // 快捷访问方法
    public FileConfiguration getMainConfig() {
        return mainConfigManager.getConfig();
    }
    
    /**
     * 获取变量配置统计信息
     * 
     * @return 配置统计信息字符串
     */
    public String getVariableConfigStats() {
        return variablesConfigManager.getConfigStats();
    }
}