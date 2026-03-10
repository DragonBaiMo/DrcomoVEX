package cn.drcomo.sync;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.config.YamlUtil;
import cn.drcomo.corelib.util.DebugUtil;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨服同步本地数据存储（last_event_id 等）
 */
public class CrossServerSyncStore {

    private static final String CONFIG_FILE = "cross-server-sync";
    private final DrcomoVEX plugin;
    private final YamlUtil yamlUtil;
    private final DebugUtil logger;
    private final ConcurrentHashMap<String, Long> lastEventIdCache = new ConcurrentHashMap<>();

    public CrossServerSyncStore(DrcomoVEX plugin, YamlUtil yamlUtil, DebugUtil logger) {
        this.plugin = plugin;
        this.yamlUtil = yamlUtil;
        this.logger = logger;
    }

    public void initialize() {
        try {
            yamlUtil.copyYamlFile("cross-server-sync.yml", "");
            yamlUtil.loadConfig(CONFIG_FILE);
            // 确保结构存在
            if (!yamlUtil.getConfig(CONFIG_FILE).isConfigurationSection("last-event-id")) {
                yamlUtil.getConfig(CONFIG_FILE).createSection("last-event-id");
            }
        } catch (Exception e) {
            logger.warn("初始化跨服同步本地存储失败: " + e.getMessage());
        }
    }

    public long getLastEventId(String serverId) {
        try {
            Long cached = lastEventIdCache.get(serverId);
            if (cached != null) {
                return cached;
            }
            yamlUtil.getConfig(CONFIG_FILE);
            long value = yamlUtil.getLong(CONFIG_FILE, "last-event-id." + serverId, 0L);
            lastEventIdCache.put(serverId, value);
            return value;
        } catch (Exception e) {
            logger.debug("读取跨服同步 last_event_id 失败: " + e.getMessage());
            return 0L;
        }
    }

    public synchronized void setLastEventId(String serverId, long lastEventId) {
        try {
            lastEventIdCache.put(serverId, lastEventId);
            yamlUtil.setValue(CONFIG_FILE, "last-event-id." + serverId, lastEventId);
        } catch (Exception e) {
            logger.debug("保存跨服同步 last_event_id 失败: " + e.getMessage());
        }
    }

    public synchronized void flush() {
        try {
            FileConfiguration cfg = yamlUtil.getConfig(CONFIG_FILE);
            cfg.save(plugin.getDataFolder().toPath().resolve("cross-server-sync.yml").toFile());
        } catch (Exception e) {
            logger.debug("写出跨服同步本地存储失败: " + e.getMessage());
        }
    }
}
