package cn.drcomo.config;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.corelib.config.YamlUtil;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * 数据配置管理器
 * 
 * 管理 data.yml 文件，存储运行时数据和统计信息。
 * 
 * @author BaiMo
 */
public class DataConfigManager {
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final YamlUtil yamlUtil;
    
    private static final String CONFIG_FILE = "data";
    
    public DataConfigManager(DrcomoVEX plugin, DebugUtil logger, YamlUtil yamlUtil) {
        this.plugin = plugin;
        this.logger = logger;
        this.yamlUtil = yamlUtil;
    }
    
    /**
     * 初始化数据配置
     */
    public void initialize() {
        logger.info("正在初始化数据配置...");
        
        // 复制默认数据文件到插件数据文件夹根目录
        yamlUtil.copyYamlFile("data.yml", "");
        // 加载配置
        yamlUtil.loadConfig(CONFIG_FILE);
        
        // 设置默认值
        setDefaults();
        
        logger.info("数据配置初始化完成！");
    }
    
    /**
     * 重载数据配置
     */
    public void reload() {
        logger.info("正在重载数据配置...");
        yamlUtil.loadConfig(CONFIG_FILE);
        setDefaults();
        logger.info("数据配置重载完成！");
    }
    
    /**
     * 设置默认数据值
     */
    private void setDefaults() {
        // 版本信息
        yamlUtil.getString(CONFIG_FILE, "version", "1.0.0");
        yamlUtil.getLong(CONFIG_FILE, "first-run", System.currentTimeMillis());
        yamlUtil.getLong(CONFIG_FILE, "last-startup", System.currentTimeMillis());
        
        // 统计信息
        yamlUtil.getInt(CONFIG_FILE, "statistics.total-startups", 0);
        yamlUtil.getInt(CONFIG_FILE, "statistics.total-players", 0);
        yamlUtil.getInt(CONFIG_FILE, "statistics.total-variables-created", 0);
        yamlUtil.getLong(CONFIG_FILE, "statistics.total-operations", 0L);
        
        // 周期性重置记录
        yamlUtil.getLong(CONFIG_FILE, "cycle.last-daily-reset", 0L);
        yamlUtil.getLong(CONFIG_FILE, "cycle.last-weekly-reset", 0L);
        yamlUtil.getLong(CONFIG_FILE, "cycle.last-monthly-reset", 0L);
        yamlUtil.getLong(CONFIG_FILE, "cycle.last-yearly-reset", 0L);
        
        // 性能统计
        yamlUtil.getDouble(CONFIG_FILE, "performance.average-query-time", 0.0);
        yamlUtil.getInt(CONFIG_FILE, "performance.cache-hit-count", 0);
        yamlUtil.getInt(CONFIG_FILE, "performance.cache-miss-count", 0);
        
        // 更新数据
        yamlUtil.getString(CONFIG_FILE, "update.last-check", "");
        yamlUtil.getString(CONFIG_FILE, "update.latest-version", "1.0.0");
        yamlUtil.getBoolean(CONFIG_FILE, "update.update-available", false);
    }
    
    /**
     * 获取配置文件
     */
    public FileConfiguration getConfig() {
        return yamlUtil.getConfig(CONFIG_FILE);
    }


    
    /**
     * 更新周期重置时间
     */
    public void updateCycleResetTime(String cycleType, long timestamp) {
        yamlUtil.setValue(CONFIG_FILE, "cycle.last-" + cycleType.toLowerCase() + "-reset", timestamp);
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