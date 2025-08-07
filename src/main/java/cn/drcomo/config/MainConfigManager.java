package cn.drcomo.config;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.corelib.config.YamlUtil;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * 主配置管理器
 * 
 * 管理 config.yml 文件，包含插件的主要设置项。
 * 
 * @author BaiMo
 */
public class MainConfigManager {
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final YamlUtil yamlUtil;
    
    private static final String CONFIG_FILE = "config";
    
    public MainConfigManager(DrcomoVEX plugin, DebugUtil logger, YamlUtil yamlUtil) {
        this.plugin = plugin;
        this.logger = logger;
        this.yamlUtil = yamlUtil;
    }
    
    /**
     * 初始化主配置
     */
    public void initialize() {
        logger.info("正在初始化主配置...");
        
        // 复制默认配置文件到插件数据文件夹根目录
        yamlUtil.copyYamlFile("config.yml", "");
        yamlUtil.copyYamlFile("messages.yml", "");
        
        // 加载配置
        yamlUtil.loadConfig(CONFIG_FILE);
        
        // 设置默认值
        setDefaults();
        
        logger.info("主配置初始化完成！");
    }
    
    /**
     * 重载主配置
     */
    public void reload() {
        logger.info("正在重载主配置...");
        yamlUtil.loadConfig(CONFIG_FILE);
        setDefaults();
        logger.info("主配置重载完成！");
    }
    
    /**
     * 设置默认配置值
     */
    private void setDefaults() {
        // 数据库配置
        yamlUtil.getString(CONFIG_FILE, "database.type", "sqlite");
        yamlUtil.getString(CONFIG_FILE, "database.file", "drcomovex.db");
        yamlUtil.getString(CONFIG_FILE, "database.mysql.host", "localhost");
        yamlUtil.getInt(CONFIG_FILE, "database.mysql.port", 3306);
        yamlUtil.getString(CONFIG_FILE, "database.mysql.database", "drcomovex");
        yamlUtil.getString(CONFIG_FILE, "database.mysql.username", "root");
        yamlUtil.getString(CONFIG_FILE, "database.mysql.password", "password");
        yamlUtil.getBoolean(CONFIG_FILE, "database.mysql.useSSL", false);
        
        // 连接池配置
        yamlUtil.getInt(CONFIG_FILE, "database.pool.minimum-idle", 2);
        yamlUtil.getInt(CONFIG_FILE, "database.pool.maximum-pool-size", 10);
        yamlUtil.getLong(CONFIG_FILE, "database.pool.connection-timeout", 30000L);
        yamlUtil.getLong(CONFIG_FILE, "database.pool.idle-timeout", 600000L);
        yamlUtil.getLong(CONFIG_FILE, "database.pool.max-lifetime", 1800000L);
        
        // 数据保存配置
        yamlUtil.getBoolean(CONFIG_FILE, "data.auto-save", true);
        yamlUtil.getInt(CONFIG_FILE, "data.save-interval-minutes", 5);
        yamlUtil.getBoolean(CONFIG_FILE, "data.save-on-player-quit", true);
        
        // 周期性重置配置
        yamlUtil.getBoolean(CONFIG_FILE, "cycle.enabled", true);
        yamlUtil.getInt(CONFIG_FILE, "cycle.check-interval-minutes", 1);
        yamlUtil.getString(CONFIG_FILE, "cycle.timezone", "Asia/Shanghai");
        
        
        // 更新检查配置
        yamlUtil.getBoolean(CONFIG_FILE, "settings.check-updates", true);
        yamlUtil.getBoolean(CONFIG_FILE, "settings.notify-ops", true);
        
        // 调试配置
        yamlUtil.getString(CONFIG_FILE, "debug.level", "INFO");
        
    }
    
    /**
     * 获取配置文件
     */
    public FileConfiguration getConfig() {
        return yamlUtil.getConfig(CONFIG_FILE);
    }
    
    // 快捷访问方法
    public String getString(String path, String def) {
        return yamlUtil.getString(CONFIG_FILE, path, def);
    }
    
    public int getInt(String path, int def) {
        return yamlUtil.getInt(CONFIG_FILE, path, def);
    }
    
    public boolean getBoolean(String path, boolean def) {
        return yamlUtil.getBoolean(CONFIG_FILE, path, def);
    }
    
    public long getLong(String path, long def) {
        return yamlUtil.getLong(CONFIG_FILE, path, def);
    }
    
    public double getDouble(String path, double def) {
        return yamlUtil.getDouble(CONFIG_FILE, path, def);
    }
}