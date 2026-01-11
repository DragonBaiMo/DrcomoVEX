package cn.drcomo.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * DrcomoVEX 重载后事件
 * <p>
 * 在执行 /vex reload 命令时，于 onEnable() 调用完成后触发。
 * 依赖 DrcomoVEX 的插件可以监听此事件来重新获取 API 引用或刷新缓存。
 * </p>
 * <p>
 * 注意：此事件在主线程中同步触发。
 * </p>
 *
 * @author BaiMo
 */
public class VexPostReloadEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    public VexPostReloadEvent() {
        super(false); // 同步事件，在主线程触发
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
