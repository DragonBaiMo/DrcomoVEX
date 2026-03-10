package cn.drcomo.redis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Redis 跨服同步消息体（轻量 JSON 序列化）
 */
public class RedisSyncMessage {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public String type;           // VARIABLE_CHANGE / OFFLINE_MOD_REQUEST / OFFLINE_MOD_RESULT
    public String requestId;      // 请求-响应关联 ID
    public String sourceServerId;
    public String targetServerId;
    public String op;             // GET / SET / ADD / REMOVE / RESET
    public String playerUuid;
    public String playerName;
    public String variableKey;
    public String value;
    public Long updatedAt;
    public Long firstModifiedAt;
    public String error;

    public String toJson() {
        return GSON.toJson(this);
    }

    public static RedisSyncMessage fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return GSON.fromJson(json, RedisSyncMessage.class);
        } catch (Exception ignored) {
            return null;
        }
    }
}
