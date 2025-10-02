package cn.drcomo.bulk;

import java.util.Objects;
import java.util.UUID;

/**
 * 玩家变量候选项。
 *
 * 封装批量匹配得到的玩家、变量及其当前值，供后续查询或写操作复用。
 */
public final class PlayerVariableCandidate {

    /** 数据来源 */
    public enum Source {
        /** 来自数据库批量扫描 */
        DATABASE,
        /** 来自在线内存扫描 */
        ONLINE
    }

    private final UUID playerId;
    private final String playerName;
    private final String variableKey;
    private final String currentValue;
    private final Source source;

    public PlayerVariableCandidate(UUID playerId, String playerName, String variableKey, String currentValue, Source source) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.playerName = playerName;
        this.variableKey = Objects.requireNonNull(variableKey, "variableKey");
        this.currentValue = currentValue;
        this.source = Objects.requireNonNull(source, "source");
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getVariableKey() {
        return variableKey;
    }

    public String getCurrentValue() {
        return currentValue;
    }

    public Source getSource() {
        return source;
    }
}

