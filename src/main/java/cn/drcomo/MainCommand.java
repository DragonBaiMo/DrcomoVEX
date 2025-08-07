package cn.drcomo;

import cn.drcomo.managers.MessagesManager;
import cn.drcomo.managers.PlayerVariablesManager;
import cn.drcomo.managers.RefactoredVariablesManager;
import cn.drcomo.managers.ServerVariablesManager;
import cn.drcomo.model.VariableResult;
import cn.drcomo.corelib.util.DebugUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * DrcomoVEX 主指令处理器
 *
 * 处理所有 /vex 指令的执行和 Tab 补全。
 * 支持子指令: player, global, reload, help
 *
 * 优化说明：
 * 1. 提取了参数校验和权限校验的复用逻辑为私有方法 checkArgs 和 requirePermission
 * 2. 提取了全局变量处理的复用逻辑为私有方法 handleServerVariable
 * 3. 保留并补充注释，重排方法顺序提高可读性
 * 4. 未调用的 parseTargetPlayer 方法已移动至末尾并注释
 *
 * 作者: BaiMo
 */
public class MainCommand implements CommandExecutor, TabCompleter {

    /** 异步回调超时时间（秒） */
    private static final long TIMEOUT_SECONDS = 5L;
    /** 空参数常量，减少重复 new HashMap<>() */
    private static final Map<String, String> EMPTY_PARAMS = Collections.emptyMap();

    // 插件核心引用
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final MessagesManager messagesManager;
    private final RefactoredVariablesManager variablesManager;
    private final ServerVariablesManager serverVariablesManager;
    private final PlayerVariablesManager playerVariablesManager;

    public MainCommand(
            DrcomoVEX plugin,
            DebugUtil logger,
            MessagesManager messagesManager,
            RefactoredVariablesManager variablesManager,
            ServerVariablesManager serverVariablesManager,
            PlayerVariablesManager playerVariablesManager
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.messagesManager = messagesManager;
        this.variablesManager = variablesManager;
        this.serverVariablesManager = serverVariablesManager;
        this.playerVariablesManager = playerVariablesManager;
    }

    // ----------------- CommandExecutor -----------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String scope = args[0].toLowerCase();
        switch (scope) {
            case "player":
                handlePlayerCommand(sender, args);
                break;
            case "global":
                handleGlobalCommand(sender, args);
                break;
            case "reload":
                handleReload(sender);
                break;
            case "help":
                sendHelp(sender);
                break;
            default:
                messagesManager.sendMessage(sender, "error.unknown-command",
                        Map.of("command", scope));
                sendHelp(sender);
        }
        return true;
    }

    // ----------------- TabCompleter -----------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("player", "global", "reload", "help").stream()
                    .filter(cmd -> cmd.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String scope = args[0].toLowerCase();
            if ("player".equals(scope) || "global".equals(scope)) {
                return Arrays.asList("get", "set", "add", "remove", "reset").stream()
                        .filter(cmd -> cmd.startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
        if (args.length == 3) {
            String scope = args[0].toLowerCase();
            if ("player".equals(scope)) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            } else if ("global".equals(scope)) {
                return variablesManager.getGlobalVariableKeys().stream()
                        .filter(k -> k.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }
        if (args.length == 4 && "player".equalsIgnoreCase(args[0])) {
            return variablesManager.getPlayerVariableKeys().stream()
                    .filter(k -> k.toLowerCase().startsWith(args[3].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    // ----------------- 主逻辑分发 -----------------

    /**
     * 分发玩家相关子指令
     * 格式: /vex player <get|set|add|remove|reset> ...
     */
    private void handlePlayerCommand(CommandSender sender, String[] args) {
        if (!checkArgs(sender, args, 2, "error.usage-player-command")) return;
        switch (args[1].toLowerCase()) {
            case "get":    handlePlayerGet(sender, args);    break;
            case "set":    handlePlayerSet(sender, args);    break;
            case "add":    handlePlayerAdd(sender, args);    break;
            case "remove": handlePlayerRemove(sender, args); break;
            case "reset":  handlePlayerReset(sender, args);  break;
            default:
                messagesManager.sendMessage(sender, "error.unknown-player-subcommand",
                        Map.of("command", args[1]));
        }
    }

    /**
     * 分发全局相关子指令
     * 格式: /vex global <get|set|add|remove|reset> ...
     */
    private void handleGlobalCommand(CommandSender sender, String[] args) {
        if (!checkArgs(sender, args, 2, "error.usage-global-command")) return;
        switch (args[1].toLowerCase()) {
            case "get":    handleGlobalGet(sender, args);    break;
            case "set":    handleGlobalSet(sender, args);    break;
            case "add":    handleGlobalAdd(sender, args);    break;
            case "remove": handleGlobalRemove(sender, args); break;
            case "reset":  handleGlobalReset(sender, args);  break;
            default:
                messagesManager.sendMessage(sender, "error.unknown-global-subcommand",
                        Map.of("command", args[1]));
        }
    }

    // ----------------- 玩家指令实现 -----------------

    /** /vex player get <玩家名> <变量名> */
    private void handlePlayerGet(CommandSender sender, String[] args) {
        if (!checkArgs(sender, args, 4, "error.usage-player-get")) return;
        OfflinePlayer target = resolvePlayer(sender, args[2]);
        if (target == null) return;
        if (!checkBasePermission(sender, "drcomovex.command.player.get", target)) {
            messagesManager.sendMessage(sender, "error.no-permission", EMPTY_PARAMS);
            return;
        }
        playerVariablesManager.getPlayerVariable(target, args[3])
                .completeOnTimeout(VariableResult.failure("操作超时"), TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((r, t) -> {
                    if (t != null) {
                        handleException("获取玩家变量失败: " + args[3], t, sender);
                    } else {
                        sendResult(sender, r,
                                Map.of("variable", args[3], "player", target.getName(), "value", r.getValue() != null ? r.getValue() : ""),
                                "success.player-get");
                    }
                });
    }

    /** /vex player set <玩家名> <变量名> <值> */
    private void handlePlayerSet(CommandSender sender, String[] args) {
        if (!checkArgs(sender, args, 5, "error.usage-player-set")) return;
        OfflinePlayer target = resolvePlayer(sender, args[2]);
        if (target == null) return;
        if (!checkBasePermission(sender, "drcomovex.command.player.set", target)) {
            messagesManager.sendMessage(sender, "error.no-permission", EMPTY_PARAMS);
            return;
        }
        String key = args[3], val = args[4];
        playerVariablesManager.setPlayerVariable(target, key, val)
                .completeOnTimeout(VariableResult.failure("操作超时"), TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((r, t) -> {
                    if (t != null) {
                        handleException("设置玩家变量失败: " + key, t, sender);
                    } else {
                        sendResult(sender, r,
                                Map.of("variable", key, "player", target.getName(), "value", val),
                                "success.player-set");
                    }
                });
    }

    /** /vex player add <玩家名> <变量名> <值> */
    private void handlePlayerAdd(CommandSender sender, String[] args) {
        if (!checkArgs(sender, args, 5, "error.usage-player-add")) return;
        OfflinePlayer target = resolvePlayer(sender, args[2]);
        if (target == null) return;
        if (!checkBasePermission(sender, "drcomovex.command.player.add", target)) {
            messagesManager.sendMessage(sender, "error.no-permission", EMPTY_PARAMS);
            return;
        }
        String key = args[3], val = args[4];
        logger.debug("开始执行玩家变量添加操作: player=" + target.getName() + ", key=" + key + ", value=" + val);
        playerVariablesManager.addPlayerVariable(target, key, val)
                .completeOnTimeout(VariableResult.failure("操作超时"), TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((r, t) -> {
                    logger.debug("玩家变量添加操作完成: result=" + (r != null ? r.isSuccess() : "null") +
                            ", throwable=" + (t != null ? t.getMessage() : "null"));
                    if (t != null) {
                        logger.debug("异步操作异常，准备发送错误消息");
                        handleException("增加玩家变量失败: " + key, t, sender);
                    } else {
                        logger.debug("异步操作成功，准备发送结果消息: success=" + r.isSuccess());
                        sendResult(sender, r,
                                Map.of("variable", key, "player", target.getName(),
                                        "value", val, "new_value", r.getValue() != null ? r.getValue() : ""),
                                "success.player-add");
                    }
                });
        logger.debug("异步操作已提交，等待回调");
    }

    /** /vex player remove <玩家名> <变量名> <值> */
    private void handlePlayerRemove(CommandSender sender, String[] args) {
        if (!checkArgs(sender, args, 5, "error.usage-player-remove")) return;
        OfflinePlayer target = resolvePlayer(sender, args[2]);
        if (target == null) return;
        if (!checkBasePermission(sender, "drcomovex.command.player.remove", target)) {
            messagesManager.sendMessage(sender, "error.no-permission", EMPTY_PARAMS);
            return;
        }
        String key = args[3], val = args[4];
        playerVariablesManager.removePlayerVariable(target, key, val)
                .completeOnTimeout(VariableResult.failure("操作超时"), TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((r, t) -> {
                    if (t != null) {
                        handleException("移除玩家变量失败: " + key, t, sender);
                    } else {
                        sendResult(sender, r,
                                Map.of("variable", key, "player", target.getName(),
                                        "value", val, "new_value", r.getValue() != null ? r.getValue() : ""),
                                "success.player-remove");
                    }
                });
    }

    /** /vex player reset <玩家名> <变量名> */
    private void handlePlayerReset(CommandSender sender, String[] args) {
        if (!checkArgs(sender, args, 4, "error.usage-player-reset")) return;
        OfflinePlayer target = resolvePlayer(sender, args[2]);
        if (target == null) return;
        if (!checkBasePermission(sender, "drcomovex.command.player.reset", target)) {
            messagesManager.sendMessage(sender, "error.no-permission", EMPTY_PARAMS);
            return;
        }
        String key = args[3];
        playerVariablesManager.resetPlayerVariable(target, key)
                .completeOnTimeout(VariableResult.failure("操作超时"), TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((r, t) -> {
                    if (t != null) {
                        handleException("重置玩家变量失败: " + key, t, sender);
                    } else {
                        sendResult(sender, r,
                                Map.of("variable", key, "player", Objects.requireNonNull(target.getName()),
                                        "value", r.getValue() != null ? r.getValue() : ""),
                                "success.player-reset");
                    }
                });
    }

    // ----------------- 全局指令实现 -----------------

    /** /vex global get <变量名> */
    private void handleGlobalGet(CommandSender sender, String[] args) {
        if (!checkArgs(sender, args, 3, "error.usage-global-get")) return;
        if (!requirePermission(sender, "drcomovex.command.global.get", "error.no-permission")) return;
        String key = args[2];
        handleServerVariable(
                serverVariablesManager.getServerVariable(key),
                sender,
                Map.of("variable", key, "value", ""),
                "success.global-get",
                "获取全局变量失败: " + key
        );
    }

    /** /vex global set <变量名> <值> */
    private void handleGlobalSet(CommandSender sender, String[] args) {
        if (!checkArgs(sender, args, 4, "error.usage-global-set")) return;
        if (!requirePermission(sender, "drcomovex.command.global.set", "error.no-permission")) return;
        String key = args[2], val = args[3];
        handleServerVariable(
                serverVariablesManager.setServerVariable(key, val),
                sender,
                Map.of("variable", key, "value", val),
                "success.global-set",
                "设置全局变量失败: " + key
        );
    }

    /** /vex global add <变量名> <值> */
    private void handleGlobalAdd(CommandSender sender, String[] args) {
        if (!checkArgs(sender, args, 4, "error.usage-global-add")) return;
        if (!requirePermission(sender, "drcomovex.command.global.add", "error.no-permission")) return;
        String key = args[2], val = args[3];
        handleServerVariable(
                serverVariablesManager.addServerVariable(key, val),
                sender,
                Map.of("variable", key, "value", val, "new_value", ""),
                "success.global-add",
                "增加全局变量失败: " + key
        );
    }

    /** /vex global remove <变量名> <值> */
    private void handleGlobalRemove(CommandSender sender, String[] args) {
        if (!checkArgs(sender, args, 4, "error.usage-global-remove")) return;
        if (!requirePermission(sender, "drcomovex.command.global.remove", "error.no-permission")) return;
        String key = args[2], val = args[3];
        handleServerVariable(
                serverVariablesManager.removeServerVariable(key, val),
                sender,
                Map.of("variable", key, "value", val, "new_value", ""),
                "success.global-remove",
                "移除全局变量失败: " + key
        );
    }

    /** /vex global reset <变量名> */
    private void handleGlobalReset(CommandSender sender, String[] args) {
        if (!checkArgs(sender, args, 3, "error.usage-global-reset")) return;
        if (!requirePermission(sender, "drcomovex.command.global.reset", "error.no-permission")) return;
        String key = args[2];
        handleServerVariable(
                serverVariablesManager.resetServerVariable(key),
                sender,
                Map.of("variable", key, "value", ""),
                "success.global-reset",
                "重置全局变量失败: " + key
        );
    }

    // ----------------- 其他命令 -----------------

    /** /vex reload */
    private void handleReload(CommandSender sender) {
        if (!requirePermission(sender, "drcomovex.admin.reload", "error.no-permission")) return;
        // 必须在主线程执行，因为 PlaceholderAPI 等插件的注销事件要求同步执行
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                logger.info("开始执行完全重载（onDisable + onEnable）...");
                plugin.onDisable();
                plugin.onEnable();
                messagesManager.sendMessage(sender, "success.reload", EMPTY_PARAMS);
                logger.info("完全重载成功，执行者: " + sender.getName());
            } catch (Exception e) {
                logger.error("重载失败", e);
                messagesManager.sendMessage(sender, "error.reload-failed", EMPTY_PARAMS);
            }
        });
    }

    /** 发送帮助信息 */
    private void sendHelp(CommandSender sender) {
        messagesManager.sendMessageList(sender, "help.commands", EMPTY_PARAMS);
    }

    // ----------------- 私有复用方法 -----------------

    /**
     * 参数长度校验，若不满足则发送错误用法并返回 false
     */
    private boolean checkArgs(CommandSender sender, String[] args, int minLength, String usageKey) {
        if (args.length < minLength) {
            messagesManager.sendMessage(sender, usageKey, EMPTY_PARAMS);
            return false;
        }
        return true;
    }

    /**
     * 权限校验，若无权限则发送错误提示并返回 false
     */
    private boolean requirePermission(CommandSender sender, String perm, String errorKey) {
        if (!sender.hasPermission(perm)) {
            messagesManager.sendMessage(sender, errorKey, EMPTY_PARAMS);
            return false;
        }
        return true;
    }

    /**
     * 解析并验证目标玩家，若无效则发送错误消息并返回 null
     */
    private OfflinePlayer resolvePlayer(CommandSender sender, String playerName) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(playerName);
        if (p == null || !p.hasPlayedBefore()) {
            messagesManager.sendMessage(sender, "error.player-not-found", Map.of("player", playerName));
            return null;
        }
        return p;
    }

    /**
     * 检查基础权限：包括操作自身和操作他人 (.others)
     */
    private boolean checkBasePermission(CommandSender sender, String basePerm, OfflinePlayer target) {
        if (!sender.hasPermission(basePerm)) {
            return false;
        }
        if (sender instanceof Player && !sender.getName().equals(target.getName())) {
            return sender.hasPermission(basePerm + ".others");
        }
        return true;
    }

    /**
     * 统一处理异步结果：成功发送指定消息，失败发送 error.operation-failed
     */
    private void sendResult(CommandSender sender, VariableResult r, Map<String, String> params, String successKey) {
        logger.debug("准备发送结果消息: success=" + r.isSuccess() + ", value=" + r.getValue() + ", error=" + r.getErrorMessage());
        if (r.isSuccess()) {
            if (r.getValue() == null) {
                logger.warn("变量操作成功但返回值为空");
                messagesManager.sendMessage(sender, "error.operation-failed", Map.of("reason", "返回值为空"));
                return;
            }
            messagesManager.sendMessage(sender, successKey, params);
        } else {
            messagesManager.sendMessage(sender, "error.operation-failed", Map.of("reason", r.getErrorMessage()));
        }
    }

    /**
     * 统一处理异常：记录日志并发送内部错误
     */
    private Void handleException(String logPrefix, Throwable t, CommandSender sender) {
        logger.error(logPrefix, t);
        messagesManager.sendMessage(sender, "error.internal", EMPTY_PARAMS);
        return null;
    }

    /**
     * 统一处理全局变量异步调用结果，简化重复逻辑
     */
    private void handleServerVariable(
            CompletionStage<VariableResult> stage,
            CommandSender sender,
            Map<String, String> params,
            String successKey,
            String errorLogPrefix
    ) {
        stage.thenAccept(r -> sendResult(sender, r, params, successKey))
                .exceptionally(t -> handleException(errorLogPrefix, t, sender));
    }
}
