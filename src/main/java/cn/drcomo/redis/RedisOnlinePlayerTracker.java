package cn.drcomo.redis;

import cn.drcomo.corelib.util.DebugUtil;

import java.util.UUID;

/**
 * Redis 在线玩家追踪器
 *
 * 键模型：
 * - vex:online:<uuid> => <serverId> (EX=ttl)
 */
public class RedisOnlinePlayerTracker {

    private static final String ONLINE_PREFIX = "vex:online:";

    private final DebugUtil logger;
    private final RedisConnection redis;
    private final String serverId;
    private final int onlineTtlSeconds;

    public RedisOnlinePlayerTracker(DebugUtil logger, RedisConnection redis, String serverId, int onlineTtlSeconds) {
        this.logger = logger;
        this.redis = redis;
        this.serverId = serverId;
        this.onlineTtlSeconds = Math.max(30, onlineTtlSeconds);
    }

    public boolean isEnabled() {
        return redis != null && redis.isReady();
    }

    public void markOnline(UUID playerId) {
        if (playerId == null || !isEnabled()) {
            return;
        }
        redis.setEx(buildOnlineKey(playerId), onlineTtlSeconds, serverId);
    }

    public void refreshOnline(UUID playerId) {
        markOnline(playerId);
    }

    public void markOffline(UUID playerId) {
        if (playerId == null || !isEnabled()) {
            return;
        }
        redis.del(buildOnlineKey(playerId));
    }

    public String getPlayerServer(UUID playerId) {
        if (playerId == null || !isEnabled()) {
            return null;
        }
        return redis.get(buildOnlineKey(playerId));
    }

    public boolean isOnlineAnywhere(UUID playerId) {
        return getPlayerServer(playerId) != null;
    }

    public boolean isOnlineInThisServer(UUID playerId) {
        String target = getPlayerServer(playerId);
        return target != null && target.equals(serverId);
    }

    public String getServerId() {
        return serverId;
    }

    public int getOnlineTtlSeconds() {
        return onlineTtlSeconds;
    }

    private String buildOnlineKey(UUID playerId) {
        return ONLINE_PREFIX + playerId;
    }
}
