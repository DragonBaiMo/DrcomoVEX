package cn.drcomo.sync;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线玩家追踪器（线程安全）
 */
public class OnlinePlayerTracker {

    private final Set<UUID> onlinePlayers = ConcurrentHashMap.newKeySet();

    public void markOnline(UUID playerId) {
        if (playerId != null) {
            onlinePlayers.add(playerId);
        }
    }

    public void markOffline(UUID playerId) {
        if (playerId != null) {
            onlinePlayers.remove(playerId);
        }
    }

    public boolean isOnline(UUID playerId) {
        return playerId != null && onlinePlayers.contains(playerId);
    }
}
