package cn.drcomo.bulk;

import java.util.Objects;

/**
 * 玩家批量收集请求参数。
 */
public final class PlayerBulkRequest {

    private final String rawSpec;
    private final boolean includeDatabase;
    private final boolean includeOnline;
    private final int previewLimit;
    private final int candidateLimit;
    private final PlayerFilter playerFilter;

    public PlayerBulkRequest(String rawSpec,
                             boolean includeDatabase,
                             boolean includeOnline,
                             int previewLimit,
                             int candidateLimit,
                             PlayerFilter playerFilter) {
        this.rawSpec = Objects.requireNonNull(rawSpec, "rawSpec");
        this.includeDatabase = includeDatabase;
        this.includeOnline = includeOnline;
        this.previewLimit = previewLimit;
        this.candidateLimit = candidateLimit;
        this.playerFilter = playerFilter == null ? PlayerFilter.allowAll() : playerFilter;
    }

    public String getRawSpec() {
        return rawSpec;
    }

    public boolean isIncludeDatabase() {
        return includeDatabase;
    }

    public boolean isIncludeOnline() {
        return includeOnline;
    }

    public int getPreviewLimit() {
        return previewLimit;
    }

    public int getCandidateLimit() {
        return candidateLimit;
    }

    public PlayerFilter getPlayerFilter() {
        return playerFilter;
    }
}
