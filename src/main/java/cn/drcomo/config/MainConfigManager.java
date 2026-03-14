package cn.drcomo.config;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.corelib.config.YamlUtil;
import cn.drcomo.corelib.config.ValidationResult;
// 已移除文件监听功能，无需导入 YamlConfiguration
import org.bukkit.configuration.file.FileConfiguration;

/**
 * 主配置管理器
 * 
 * 管理 config.yml 文件，包含插件的主要设置项。
 * 
 * @author BaiMo
 */
public class MainConfigManager {
    
    private final DrcomoVEX plugin; // 预留未来扩展使用
    private final DebugUtil logger;
    private final YamlUtil yamlUtil;
    // 已移除文件监听句柄
    
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
        logger.debug("主配置所属插件: " + plugin.getName());
        
        // 复制默认配置文件到插件数据文件夹根目录
        yamlUtil.copyYamlFile("config.yml", "");
        yamlUtil.copyYamlFile("messages.yml", "");
        
        // 加载配置
        yamlUtil.loadConfig(CONFIG_FILE);
        
        // 设置默认值
        setDefaults();

        // 结构校验
        validateStructure();

        // 不启用文件监听
        
        logger.info("主配置初始化完成！");
    }
    
    /**
     * 重载主配置
     */
    public void reload() {
        logger.info("正在重载主配置...");
        yamlUtil.loadConfig(CONFIG_FILE);
        setDefaults();
        validateStructure();
        // 不启用文件监听
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
        yamlUtil.getInt(CONFIG_FILE, "cycle.check-interval-seconds", 60);
        yamlUtil.getString(CONFIG_FILE, "cycle.timezone", "Asia/Shanghai");
        
        
        // 更新检查配置
        yamlUtil.getBoolean(CONFIG_FILE, "settings.check-updates", true);
        yamlUtil.getBoolean(CONFIG_FILE, "settings.notify-ops", true);

        // 全局变量数据库拉取同步（无需 server-id）
        yamlUtil.getBoolean(CONFIG_FILE, "settings.global-db-sync.enabled", true);
        yamlUtil.getInt(CONFIG_FILE, "settings.global-db-sync.poll-interval-millis", 1000);
        yamlUtil.getLong(CONFIG_FILE, "settings.global-db-sync.query-timeout-millis", 5000L);

        // MySQL 事件表跨服同步（旧兼容方案）
        yamlUtil.getBoolean(CONFIG_FILE, "settings.cross-server-sync.enabled", false);
        yamlUtil.getString(CONFIG_FILE, "settings.cross-server-sync.server-id", "");
        yamlUtil.getInt(CONFIG_FILE, "settings.cross-server-sync.poll-interval-ms", 250);
        yamlUtil.getInt(CONFIG_FILE, "settings.cross-server-sync.batch-size", 500);
        yamlUtil.getInt(CONFIG_FILE, "settings.cross-server-sync.retention-days", 7);
        yamlUtil.getBoolean(CONFIG_FILE, "settings.cross-server-sync.fail-closed-on-db-error", true);
        yamlUtil.getInt(CONFIG_FILE, "settings.cross-server-sync.cleanup-interval-seconds", 180);
        yamlUtil.getInt(CONFIG_FILE, "settings.cross-server-sync.cleanup-safety-margin", 1000);
        yamlUtil.getBoolean(CONFIG_FILE, "settings.cross-server-sync.ignore-stale-consumers", false);
        yamlUtil.getInt(CONFIG_FILE, "settings.cross-server-sync.consumer-stale-days", 7);

        // Redis 跨服同步配置（可选）
        yamlUtil.getBoolean(CONFIG_FILE, "settings.redis-sync.enabled", false);
        yamlUtil.getString(CONFIG_FILE, "settings.redis-sync.host", "127.0.0.1");
        yamlUtil.getInt(CONFIG_FILE, "settings.redis-sync.port", 6379);
        yamlUtil.getString(CONFIG_FILE, "settings.redis-sync.password", "");
        yamlUtil.getInt(CONFIG_FILE, "settings.redis-sync.database", 0);
        yamlUtil.getString(CONFIG_FILE, "settings.redis-sync.server-id", "");
        yamlUtil.getInt(CONFIG_FILE, "settings.redis-sync.heartbeat-interval-seconds", 60);
        yamlUtil.getInt(CONFIG_FILE, "settings.redis-sync.online-ttl-seconds", 300);
        yamlUtil.getLong(CONFIG_FILE, "settings.redis-sync.request-timeout-millis", 3000L);
        yamlUtil.getInt(CONFIG_FILE, "settings.redis-sync.server-id-claim-ttl-seconds", 300);
        yamlUtil.getInt(CONFIG_FILE, "settings.redis-sync.pool.max-total", 8);
        yamlUtil.getInt(CONFIG_FILE, "settings.redis-sync.pool.max-idle", 4);
        yamlUtil.getInt(CONFIG_FILE, "settings.redis-sync.pool.min-idle", 1);
        yamlUtil.getLong(CONFIG_FILE, "settings.redis-sync.pool.timeout-millis", 3000L);

        // 调试配置
        yamlUtil.getString(CONFIG_FILE, "debug.level", "INFO");
        
    }

    /**
     * 使用 ConfigSchema + ConfigValidator 校验配置结构
     */
    private void validateStructure() {
        try {
            ValidationResult result = yamlUtil.validateConfig(CONFIG_FILE, new MainConfigSchema());
            if (!result.isSuccess()) {
                logger.warn("主配置结构校验失败，错误数: " + result.getErrors().size());
                for (String err : result.getErrors()) {
                    logger.warn(" - " + err);
                }
            } else {
                logger.info("主配置结构校验通过");
            }
        } catch (Throwable t) {
            logger.error("执行主配置结构校验时出现异常", t);
        }
    }

    // 不启用配置文件监听功能
    
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
