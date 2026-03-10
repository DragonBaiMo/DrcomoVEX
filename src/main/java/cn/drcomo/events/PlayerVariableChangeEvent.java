package cn.drcomo.events;

import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 玩家变量变更事件
 * <p>
 * 当玩家作用域的变量发生变化时触发（set/add/remove/reset/regen）。
 * 此事件为异步事件，在变量写入内存并清除缓存后触发。
 * 监听方若需要调用 Bukkit API（如 sendMessage、物品修改），请使用调度器切回主线程执行。
 * </p>
 *
 * @author BaiMo
 */
public class PlayerVariableChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final OfflinePlayer player;
    private final String variableKey;
    private final String oldValue;
    private final String newValue;
    private final ChangeReason reason;

    /**
     * 变量变更原因
     */
    public enum ChangeReason {
        /** 直接设置值 */
        SET,
        /** 增加值 */
        ADD,
        /** 减少值 */
        REMOVE,
        /** 重置为默认值 */
        RESET,
        /** 周期性恢复 */
        REGEN,
        /** 远端子服同步 */
        REMOTE_SYNC,
        /** 其他/未知 */
        OTHER
    }

    /**
     * 构造函数
     *
     * @param player      变量所属玩家
     * @param variableKey 变量键名
     * @param oldValue    旧值（可能为 null）
     * @param newValue    新值
     * @param reason      变更原因
     * @param async       是否异步触发
     */
    public PlayerVariableChangeEvent(
            @NotNull OfflinePlayer player,
            @NotNull String variableKey,
            @Nullable String oldValue,
            @NotNull String newValue,
            @NotNull ChangeReason reason,
            boolean async
    ) {
        super(async);
        this.player = player;
        this.variableKey = variableKey;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.reason = reason;
    }

    /**
     * 获取变量所属玩家
     */
    @NotNull
    public OfflinePlayer getPlayer() {
        return player;
    }

    /**
     * 获取变量键名
     */
    @NotNull
    public String getVariableKey() {
        return variableKey;
    }

    /**
     * 获取旧值
     *
     * @return 旧值，如果是新创建的变量则为 null
     */
    @Nullable
    public String getOldValue() {
        return oldValue;
    }

    /**
     * 获取新值
     */
    @NotNull
    public String getNewValue() {
        return newValue;
    }

    /**
     * 获取变更原因
     */
    @NotNull
    public ChangeReason getReason() {
        return reason;
    }

    @Override
    @NotNull
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
