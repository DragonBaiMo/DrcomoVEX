package cn.drcomo.bulk;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 玩家批量匹配结果。
 *
 * 记录候选列表、预览信息以及导出所需的映射。
 */
public final class PlayerBulkResult {

    private final List<PlayerVariableCandidate> candidates;
    private final List<String> previews;
    private final Map<String, Map<String, String>> uuidToVariables;
    private final Map<String, String> uuidToName;
    private final int totalMatches;
    private final boolean databaseTimeout;
    private final boolean onlineTimeout;

    public PlayerBulkResult(List<PlayerVariableCandidate> candidates,
                            List<String> previews,
                            Map<String, Map<String, String>> uuidToVariables,
                            Map<String, String> uuidToName,
                            int totalMatches,
                            boolean databaseTimeout,
                            boolean onlineTimeout) {
        this.candidates = Collections.unmodifiableList(Objects.requireNonNull(candidates, "candidates"));
        this.previews = Collections.unmodifiableList(Objects.requireNonNull(previews, "previews"));
        this.uuidToVariables = Collections.unmodifiableMap(Objects.requireNonNull(uuidToVariables, "uuidToVariables"));
        this.uuidToName = Collections.unmodifiableMap(Objects.requireNonNull(uuidToName, "uuidToName"));
        this.totalMatches = totalMatches;
        this.databaseTimeout = databaseTimeout;
        this.onlineTimeout = onlineTimeout;
    }

    public List<PlayerVariableCandidate> getCandidates() {
        return candidates;
    }

    public List<String> getPreviews() {
        return previews;
    }

    public Map<String, Map<String, String>> getUuidToVariables() {
        return uuidToVariables;
    }

    public Map<String, String> getUuidToName() {
        return uuidToName;
    }

    public int getTotalMatches() {
        return totalMatches;
    }

    public boolean isDatabaseTimeout() {
        return databaseTimeout;
    }

    public boolean isOnlineTimeout() {
        return onlineTimeout;
    }
}

