package cn.drcomo.managers.components;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.hook.placeholder.PlaceholderAPIUtil;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.model.structure.Variable;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.BiFunction;

/**
 * 周期动作执行组件
 * - 负责解析与执行变量的 cycle-actions
 * - 保持与原有逻辑一致，拆分出独立职责，提升可维护性
 */
public class ActionExecutor {

    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final PlaceholderAPIUtil placeholderUtil;
    /**
     * 内部变量解析委托：由上层管理器提供，以便复用其对 ${var} 的解析实现
     */
    private final BiFunction<String, OfflinePlayer, String> internalVarResolver;

    public ActionExecutor(DrcomoVEX plugin,
                          DebugUtil logger,
                          PlaceholderAPIUtil placeholderUtil,
                          BiFunction<String, OfflinePlayer, String> internalVarResolver) {
        this.plugin = plugin;
        this.logger = logger;
        this.placeholderUtil = placeholderUtil;
        this.internalVarResolver = internalVarResolver;
    }

    /**
     * 在变量重置后执行 cycle-actions
     * - 当变量作用域为 player 时：对指定玩家执行；若未传入玩家（如周期任务批量重置），对所有在线玩家各执行一次
     * - 当变量作用域为 global 时：仅以控制台身份执行一次；占位符解析上下文使用传入玩家，若为空则任选一个在线玩家
     */
    public void executeCycleActionsOnReset(Variable variable, OfflinePlayer contextPlayer) {
        if (variable == null || !variable.hasCycleActions()) return;
        List<String> actions = variable.getCycleActions();
        if (actions == null || actions.isEmpty()) return;

        if (variable.isPlayerScoped()) {
            if (contextPlayer != null && isOnline(contextPlayer)) {
                Player p = contextPlayer.getPlayer();
                executeActionsForPlayer(variable, p);
            } else {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    executeActionsForPlayer(variable, p);
                }
            }
        } else if (variable.isGlobal()) {
            OfflinePlayer placeholderCtx = contextPlayer;
            if (placeholderCtx == null) {
                for (Player p : Bukkit.getOnlinePlayers()) { placeholderCtx = p; break; }
            }
            executeActionsAsConsole(variable, placeholderCtx);
        }
    }

    /** 对某个玩家上下文顺序执行整套动作，支持 [delay] 若干 ticks */
    private void executeActionsForPlayer(Variable variable, Player player) {
        if (player == null) return;
        List<String> actions = variable.getCycleActions();
        if (actions == null || actions.isEmpty()) return;

        final int[] idx = {0};
        Runnable runner = new Runnable() {
            @Override public void run() {
                // 结束条件
                if (idx[0] >= actions.size()) return;
                String raw = actions.get(idx[0]);
                if (isBlank(raw)) { idx[0]++; Bukkit.getScheduler().runTask(plugin, this); return; }

                String[] parsed = parseAction(raw);
                if (parsed == null) { logger.warn("无法解析周期动作: " + raw); idx[0]++; Bukkit.getScheduler().runTask(plugin, this); return; }
                String mode = parsed[0];

                if ("delay".equals(mode)) {
                    int ticks = 0;
                    try { ticks = Math.max(0, Integer.parseInt(parsed[1].trim())); } catch (Exception ignored) { }
                    idx[0]++;
                    Bukkit.getScheduler().runTaskLater(plugin, this, ticks);
                    return;
                }

                String cmd = parsed.length > 1 ? parsed[1] : "";
                if (isBlank(cmd)) { logger.warn("空指令被忽略: " + raw); idx[0]++; Bukkit.getScheduler().runTask(plugin, this); return; }
                String resolved = resolveActionPlaceholders(cmd, player);
                final String finalCmd = stripLeadingSlash(resolved);

                switch (mode) {
                    case "player":
                        Bukkit.dispatchCommand(player, finalCmd);
                        break;
                    case "op": {
                        boolean prev = player.isOp();
                        try {
                            if (!prev) player.setOp(true);
                            Bukkit.dispatchCommand(player, finalCmd);
                        } finally {
                            if (!prev) player.setOp(false);
                        }
                        break;
                    }
                    case "console":
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
                        break;
                    default:
                        logger.warn("未知的动作执行模式: " + mode + ", 原始: " + raw);
                }

                idx[0]++;
                Bukkit.getScheduler().runTask(plugin, this);
            }
        };
        // 将整套序列作为一个任务开始执行
        Bukkit.getScheduler().runTask(plugin, runner);
    }

    /** 以控制台身份顺序执行整套动作（仅一次），占位符解析上下文可为任意玩家 */
    private void executeActionsAsConsole(Variable variable, OfflinePlayer ctx) {
        CommandSender console = Bukkit.getConsoleSender();
        List<String> actions = variable.getCycleActions();
        if (actions == null || actions.isEmpty()) return;

        final int[] idx = {0};
        Runnable runner = new Runnable() {
            @Override public void run() {
                if (idx[0] >= actions.size()) return;
                String raw = actions.get(idx[0]);
                if (isBlank(raw)) { idx[0]++; Bukkit.getScheduler().runTask(plugin, this); return; }

                String[] parsed = parseAction(raw);
                if (parsed == null) { logger.warn("无法解析周期动作: " + raw); idx[0]++; Bukkit.getScheduler().runTask(plugin, this); return; }
                String mode = parsed[0];
                if ("delay".equals(mode)) {
                    int ticks = 0;
                    try { ticks = Math.max(0, Integer.parseInt(parsed[1].trim())); } catch (Exception ignored) { }
                    idx[0]++;
                    Bukkit.getScheduler().runTaskLater(plugin, this, ticks);
                    return;
                }

                String cmd = parsed.length > 1 ? parsed[1] : "";
                if (isBlank(cmd)) { logger.warn("空指令被忽略: " + raw); idx[0]++; Bukkit.getScheduler().runTask(plugin, this); return; }
                String resolved = resolveActionPlaceholders(cmd, ctx);
                final String finalCmd = stripLeadingSlash(resolved);
                Bukkit.dispatchCommand(console, finalCmd);

                idx[0]++;
                Bukkit.getScheduler().runTask(plugin, this);
            }
        };
        Bukkit.getScheduler().runTask(plugin, runner);
    }

    /** 解析动作前缀，返回 [mode, command]；支持 [player]/[op]/[console] 以及 [delay] ticks */
    private String[] parseAction(String raw) {
        String s = raw.trim();
        if (s.startsWith("[player]")) {
            return new String[]{"player", s.substring(8).trim()};
        } else if (s.startsWith("[op]")) {
            return new String[]{"op", s.substring(4).trim()};
        } else if (s.startsWith("[console]")) {
            return new String[]{"console", s.substring(9).trim()};
        } else if (s.startsWith("[delay]")) {
            return new String[]{"delay", s.substring(7).trim()};
        }
        return new String[]{"console", s};
    }

    /** 去掉命令前导斜杠 */
    private String stripLeadingSlash(String cmd) {
        if (cmd == null) return "";
        String c = cmd.trim();
        return c.startsWith("/") ? c.substring(1) : c;
    }

    /**
     * 解析动作行中的占位符与内部变量
     * - 优先解析内部变量 ${var}
     * - 若存在玩家上下文且在线，再解析 PlaceholderAPI
     */
    private String resolveActionPlaceholders(String text, OfflinePlayer ctx) {
        String t = internalVarResolver.apply(text, ctx);
        if (isOnline(ctx)) {
            try {
                t = placeholderUtil.parse(ctx.getPlayer(), t);
            } catch (Exception e) {
                logger.debug("PAPI 解析失败(动作行)：" + e.getMessage());
            }
        }
        return t;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 是否在线（避免 NPE 与 PAPI 无上下文问题） */
    private boolean isOnline(OfflinePlayer player) {
        return player != null && player.isOnline() && player.getPlayer() != null;
    }
}
