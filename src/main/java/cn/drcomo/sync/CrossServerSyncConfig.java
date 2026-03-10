package cn.drcomo.sync;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 跨服同步配置
 */
public class CrossServerSyncConfig {

    private final boolean enabled;
    private final String serverId;
    private final int pollIntervalMs;
    private final int batchSize;
    private final int retentionDays;
    private final boolean failClosedOnDbError;
    private final int cleanupIntervalSeconds;
    private final int safetyMargin;
    private final int consumerStaleDays;
    private final boolean ignoreStaleConsumers;

    public CrossServerSyncConfig(FileConfiguration config) {
        this.enabled = config.getBoolean("settings.cross-server-sync.enabled", false);
        this.serverId = config.getString("settings.cross-server-sync.server-id", "").trim();
        this.pollIntervalMs = Math.max(100, config.getInt("settings.cross-server-sync.poll-interval-ms", 250));
        this.batchSize = Math.max(50, config.getInt("settings.cross-server-sync.batch-size", 500));
        this.retentionDays = Math.max(1, config.getInt("settings.cross-server-sync.retention-days", 7));
        this.failClosedOnDbError = config.getBoolean("settings.cross-server-sync.fail-closed-on-db-error", true);
        this.cleanupIntervalSeconds = Math.max(60, config.getInt("settings.cross-server-sync.cleanup-interval-seconds", 180));
        this.safetyMargin = Math.max(100, config.getInt("settings.cross-server-sync.cleanup-safety-margin", 1000));
        this.ignoreStaleConsumers = config.getBoolean("settings.cross-server-sync.ignore-stale-consumers", false);
        this.consumerStaleDays = Math.max(1, config.getInt("settings.cross-server-sync.consumer-stale-days", 7));
    }

    public boolean isEnabled() { return enabled; }

    public String getServerId() { return serverId; }

    public int getPollIntervalMs() { return pollIntervalMs; }

    public int getBatchSize() { return batchSize; }

    public int getRetentionDays() { return retentionDays; }

    public boolean isFailClosedOnDbError() { return failClosedOnDbError; }

    public int getCleanupIntervalSeconds() { return cleanupIntervalSeconds; }

    public int getSafetyMargin() { return safetyMargin; }

    public boolean isIgnoreStaleConsumers() { return ignoreStaleConsumers; }

    public int getConsumerStaleDays() { return consumerStaleDays; }
}
