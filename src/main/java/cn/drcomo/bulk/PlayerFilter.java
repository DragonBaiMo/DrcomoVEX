package cn.drcomo.bulk;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家过滤条件。
 *
 * 支持按 UUID 与玩家名（不区分大小写）过滤，若未配置则放行全部玩家。
 */
public final class PlayerFilter {

    private final Set<UUID> uuids;
    private final Set<String> namesLower;

    private PlayerFilter(Set<UUID> uuids, Set<String> namesLower) {
        this.uuids = uuids == null ? Collections.emptySet() : Collections.unmodifiableSet(uuids);
        this.namesLower = namesLower == null ? Collections.emptySet() : Collections.unmodifiableSet(namesLower);
    }

    /**
     * 创建放行全部玩家的过滤器。
     */
    public static PlayerFilter allowAll() {
        return new PlayerFilter(Collections.emptySet(), Collections.emptySet());
    }

    /**
     * 创建包含给定集合的过滤器。
     */
    public static PlayerFilter of(Set<UUID> uuids, Set<String> namesLower) {
        if ((uuids == null || uuids.isEmpty()) && (namesLower == null || namesLower.isEmpty())) {
            return allowAll();
        }
        Set<UUID> uuidSet = uuids == null ? Collections.emptySet() : new HashSet<>(uuids);
        Set<String> nameSet = namesLower == null ? Collections.emptySet() : new HashSet<>(namesLower);
        return new PlayerFilter(uuidSet, nameSet);
    }

    /**
     * 是否存在过滤配置。
     */
    public boolean isEmpty() {
        return uuids.isEmpty() && namesLower.isEmpty();
    }

    /**
     * 判断是否匹配指定玩家。
     */
    public boolean matches(UUID uuid, String name) {
        if (isEmpty()) {
            return true;
        }
        if (uuid != null && uuids.contains(uuid)) {
            return true;
        }
        if (name != null && !name.isEmpty() && namesLower.contains(name.toLowerCase())) {
            return true;
        }
        return false;
    }

    /**
     * 根据玩家名尝试解析 UUID 并加入过滤器。
     */
    public static PlayerFilter fromRawValues(Set<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) {
            return allowAll();
        }
        Set<UUID> uuidSet = new HashSet<>();
        Set<String> nameSet = new HashSet<>();
        for (String raw : rawValues) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String trimmed = raw.trim();
            try {
                uuidSet.add(UUID.fromString(trimmed));
                continue;
            } catch (IllegalArgumentException ignored) {
            }
            OfflinePlayer offline = Bukkit.getOfflinePlayer(trimmed);
            UUID uuid = offline != null ? offline.getUniqueId() : null;
            if (uuid != null) {
                uuidSet.add(uuid);
            } else {
                nameSet.add(trimmed.toLowerCase());
            }
        }
        return of(uuidSet, nameSet);
    }

    @Override
    public String toString() {
        return "PlayerFilter{" +
                "uuids=" + uuids +
                ", namesLower=" + namesLower +
                '}';
    }
}

