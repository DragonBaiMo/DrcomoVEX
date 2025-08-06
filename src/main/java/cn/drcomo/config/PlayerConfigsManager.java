package cn.drcomo.config;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.corelib.config.YamlUtil;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 玩家配置管理器
 * 
 * 管理各个玩家的个人配置文件，存储在 players/ 目录下。
 * 
 * @author BaiMo
 */
public class PlayerConfigsManager {
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final YamlUtil yamlUtil;
    
    // 缓存已加载的玩家配置
    private final ConcurrentMap<UUID, String> loadedConfigs = new ConcurrentHashMap<>();
    
    public PlayerConfigsManager(DrcomoVEX plugin, DebugUtil logger, YamlUtil yamlUtil) {
        this.plugin = plugin;
        this.logger = logger;
        this.yamlUtil = yamlUtil;
    }
    
    /**
     * 初始化玩家配置管理器
     */
    public void initialize() {
        logger.info("正在初始化玩家配置管理器...");
        
        // 创建玩家目录
        File playersFolder = new File(plugin.getDataFolder(), "players");
        if (!playersFolder.exists()) {
            playersFolder.mkdirs();
        }
        
        logger.info("玩家配置管理器初始化完成！");
    }
    
    /**
     * 重载所有玩家配置
     */
    public void reload() {
        logger.info("正在重载所有玩家配置...");
        
        // 重载已加载的配置
        for (String configName : loadedConfigs.values()) {
            yamlUtil.loadConfig(configName);
        }
        
        logger.info("所有玩家配置已重载完成！");
    }
    
    /**
     * 获取玩家配置文件
     */
    public FileConfiguration getPlayerConfig(OfflinePlayer player) {
        return getPlayerConfig(player.getUniqueId());
    }
    
    /**
     * 获取玩家配置文件
     */
    public FileConfiguration getPlayerConfig(UUID playerUUID) {
        String configName = getPlayerConfigName(playerUUID);
        
        // 如果尚未加载，先加载
        if (!loadedConfigs.containsKey(playerUUID)) {
            loadPlayerConfig(playerUUID);
        }
        
        return yamlUtil.getConfig(configName);
    }
    
    /**
     * 加载玩家配置
     */
    public void loadPlayerConfig(UUID playerUUID) {
        String configName = getPlayerConfigName(playerUUID);
        
        // 加载配置文件
        yamlUtil.loadConfig(configName);
        
        // 记录已加载
        loadedConfigs.put(playerUUID, configName);
        
        // 设置默认值
        setPlayerDefaults(configName, playerUUID);
        
        logger.debug("已加载玩家配置: " + playerUUID);
    }
    
    /**
     * 卸载玩家配置
     */
    public void unloadPlayerConfig(UUID playerUUID) {
        String configName = loadedConfigs.remove(playerUUID);
        if (configName != null) {
            // 这里可以添加卸载逻辑，但 YamlUtil 可能不支持直接卸载
            logger.debug("已卸载玩家配置: " + playerUUID);
        }
    }
    
    /**
     * 保存玩家配置
     */
    public void savePlayerConfig(UUID playerUUID) {
        String configName = loadedConfigs.get(playerUUID);
        if (configName != null) {
            // YamlUtil 在设置值时已自动保存，这里可以添加额外的保存逻辑
            logger.debug("已保存玩家配置: " + playerUUID);
        }
    }
    
    /**
     * 检查玩家配置是否存在
     */
    public boolean hasPlayerConfig(UUID playerUUID) {
        File configFile = new File(plugin.getDataFolder(), "players/" + playerUUID + ".yml");
        return configFile.exists();
    }
    
    /**
     * 删除玩家配置
     */
    public boolean deletePlayerConfig(UUID playerUUID) {
        // 先卸载
        unloadPlayerConfig(playerUUID);
        
        // 删除文件
        File configFile = new File(plugin.getDataFolder(), "players/" + playerUUID + ".yml");
        if (configFile.exists()) {
            boolean deleted = configFile.delete();
            if (deleted) {
                logger.info("已删除玩家配置文件: " + playerUUID);
            }
            return deleted;
        }
        return false;
    }
    
    /**
     * 获取玩家配置名称
     */
    private String getPlayerConfigName(UUID playerUUID) {
        return "players/" + playerUUID;
    }
    
    /**
     * 设置玩家默认配置
     */
    private void setPlayerDefaults(String configName, UUID playerUUID) {
        // 玩家基本信息
        yamlUtil.getString(configName, "uuid", playerUUID.toString());
        yamlUtil.getLong(configName, "first-join", System.currentTimeMillis());
        yamlUtil.getLong(configName, "last-seen", System.currentTimeMillis());
        
        // 玩家统计
        yamlUtil.getInt(configName, "statistics.total-logins", 0);
        yamlUtil.getLong(configName, "statistics.total-playtime", 0L);
        yamlUtil.getInt(configName, "statistics.variables-accessed", 0);
        yamlUtil.getInt(configName, "statistics.operations-performed", 0);
        
        // 玩家设置
        yamlUtil.getBoolean(configName, "settings.receive-notifications", true);
        yamlUtil.getString(configName, "settings.language", "zh_CN");
        
    }
    
    // 快捷访问方法
    public String getPlayerString(UUID playerUUID, String path, String def) {
        String configName = getPlayerConfigName(playerUUID);
        if (!loadedConfigs.containsKey(playerUUID)) {
            loadPlayerConfig(playerUUID);
        }
        return yamlUtil.getString(configName, path, def);
    }
    
    public int getPlayerInt(UUID playerUUID, String path, int def) {
        String configName = getPlayerConfigName(playerUUID);
        if (!loadedConfigs.containsKey(playerUUID)) {
            loadPlayerConfig(playerUUID);
        }
        return yamlUtil.getInt(configName, path, def);
    }
    
    public boolean getPlayerBoolean(UUID playerUUID, String path, boolean def) {
        String configName = getPlayerConfigName(playerUUID);
        if (!loadedConfigs.containsKey(playerUUID)) {
            loadPlayerConfig(playerUUID);
        }
        return yamlUtil.getBoolean(configName, path, def);
    }
    
    public long getPlayerLong(UUID playerUUID, String path, long def) {
        String configName = getPlayerConfigName(playerUUID);
        if (!loadedConfigs.containsKey(playerUUID)) {
            loadPlayerConfig(playerUUID);
        }
        return yamlUtil.getLong(configName, path, def);
    }
    
    public void setPlayerValue(UUID playerUUID, String path, Object value) {
        String configName = getPlayerConfigName(playerUUID);
        if (!loadedConfigs.containsKey(playerUUID)) {
            loadPlayerConfig(playerUUID);
        }
        yamlUtil.setValue(configName, path, value);
    }
    
    /**
     * 获取已加载的配置数量
     */
    public int getLoadedConfigCount() {
        return loadedConfigs.size();
    }
}