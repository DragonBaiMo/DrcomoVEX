package cn.drcomo;

import cn.drcomo.managers.MessagesManager;
import cn.drcomo.managers.RefactoredVariablesManager;
import cn.drcomo.managers.ServerVariablesManager;
import cn.drcomo.managers.PlayerVariablesManager;
import cn.drcomo.model.VariableResult;
import cn.drcomo.corelib.util.DebugUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * DrcomoVEX 主指令处理器
 *
 * 处理所有 /vex 指令的执行和 Tab 补全。
 * 支持子指令: player, global, reload, help
 *
 * 优化说明：
 * 1. 提取了获取玩家对象和权限校验的复用逻辑为私有方法 resolvePlayer 和 checkBasePermission
 * 2. 保留并补充注释，重排方法顺序提高可读性
 * 3. 未调用的 parseTargetPlayer 方法已移动至末尾并注释
 *
 * 作者: BaiMo
 */
public class MainCommand implements CommandExecutor, TabCompleter {

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
                // 玩家名补全
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            } else if ("global".equals(scope)) {
                // 全局变量名补全
                return variablesManager.getGlobalVariableKeys().stream()
                        .filter(k -> k.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }
        if (args.length == 4 && "player".equals(args[0].toLowerCase())) {
            // 玩家变量名补全
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
        if (args.length < 2) {
            messagesManager.sendMessage(sender, "error.usage-player-command", new HashMap<>());
            return;
        }
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
        if (args.length < 2) {
            messagesManager.sendMessage(sender, "error.usage-global-command", new HashMap<>());
            return;
        }
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

    /**
     * /vex player get <玩家名> <变量名>
     */
    private void handlePlayerGet(CommandSender sender, String[] args) {
        if (args.length < 4) {
            messagesManager.sendMessage(sender, "error.usage-player-get", new HashMap<>());
            return;
        }
        OfflinePlayer target = resolvePlayer(sender, args[2]);
        if (target == null) return;
        if (!checkBasePermission(sender, "drcomovex.command.player.get", target)) {
            messagesManager.sendMessage(sender, "error.no-permission", new HashMap<>());
            return;
        }
        playerVariablesManager.getPlayerVariable(target, args[3])
                .thenAccept(r -> sendResult(sender, r,
                        Map.of("variable", args[3], "player", target.getName(), "value", r.getValue() != null ? r.getValue() : ""),
                        "success.player-get"))
                .exceptionally(t -> handleException("获取玩家变量失败: " + args[3], t, sender));
    }

    /**
     * /vex player set <玩家名> <变量名> <值>
     */
    private void handlePlayerSet(CommandSender sender, String[] args) {
        if (args.length < 5) {
            messagesManager.sendMessage(sender, "error.usage-player-set", new HashMap<>());
            return;
        }
        OfflinePlayer target = resolvePlayer(sender, args[2]);
        if (target == null) return;
        if (!checkBasePermission(sender, "drcomovex.command.player.set", target)) {
            messagesManager.sendMessage(sender, "error.no-permission", new HashMap<>());
            return;
        }
        String key = args[3], val = args[4];
        playerVariablesManager.setPlayerVariable(target, key, val)
                .thenAccept(r -> sendResult(sender, r,
                        Map.of("variable", key, "player", target.getName(), "value", val),
                        "success.player-set"))
                .exceptionally(t -> handleException("设置玩家变量失败: " + key, t, sender));
    }

    /**
     * /vex player add <玩家名> <变量名> <值>
     */
    private void handlePlayerAdd(CommandSender sender, String[] args) {
        if (args.length < 5) {
            messagesManager.sendMessage(sender, "error.usage-player-add", new HashMap<>());
            return;
        }
        OfflinePlayer target = resolvePlayer(sender, args[2]);
        if (target == null) return;
        if (!checkBasePermission(sender, "drcomovex.command.player.add", target)) {
            messagesManager.sendMessage(sender, "error.no-permission", new HashMap<>());
            return;
        }
        String key = args[3], val = args[4];
        playerVariablesManager.addPlayerVariable(target, key, val)
                .thenAccept(r -> sendResult(sender, r,
                        Map.of("variable", key, "player", target.getName(), "value", val, "new_value", r.getValue() != null ? r.getValue() : ""),
                        "success.player-add"))
                .exceptionally(t -> handleException("增加玩家变量失败: " + key, t, sender));
    }

    /**
     * /vex player remove <玩家名> <变量名> <值>
     */
    private void handlePlayerRemove(CommandSender sender, String[] args) {
        if (args.length < 5) {
            messagesManager.sendMessage(sender, "error.usage-player-remove", new HashMap<>());
            return;
        }
        OfflinePlayer target = resolvePlayer(sender, args[2]);
        if (target == null) return;
        if (!checkBasePermission(sender, "drcomovex.command.player.remove", target)) {
            messagesManager.sendMessage(sender, "error.no-permission", new HashMap<>());
            return;
        }
        String key = args[3], val = args[4];
        playerVariablesManager.removePlayerVariable(target, key, val)
                .thenAccept(r -> sendResult(sender, r,
                        Map.of("variable", key, "player", target.getName(), "value", val, "new_value", r.getValue() != null ? r.getValue() : ""),
                        "success.player-remove"))
                .exceptionally(t -> handleException("移除玩家变量失败: " + key, t, sender));
    }

    /**
     * /vex player reset <玩家名> <变量名>
     */
    private void handlePlayerReset(CommandSender sender, String[] args) {
        if (args.length < 4) {
            messagesManager.sendMessage(sender, "error.usage-player-reset", new HashMap<>());
            return;
        }
        OfflinePlayer target = resolvePlayer(sender, args[2]);
        if (target == null) return;
        if (!checkBasePermission(sender, "drcomovex.command.player.reset", target)) {
            messagesManager.sendMessage(sender, "error.no-permission", new HashMap<>());
            return;
        }
        playerVariablesManager.resetPlayerVariable(target, args[3])
                .thenAccept(r -> sendResult(sender, r,
                        Map.of("variable", args[3], "player", target.getName(), "value", r.getValue() != null ? r.getValue() : ""),
                        "success.player-reset"))
                .exceptionally(t -> handleException("重置玩家变量失败: " + args[3], t, sender));
    }

    // ----------------- 全局指令实现 -----------------

    /**
     * /vex global get <变量名>
     */
    private void handleGlobalGet(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messagesManager.sendMessage(sender, "error.usage-global-get", new HashMap<>());
            return;
        }
        if (!sender.hasPermission("drcomovex.command.global.get")) {
            messagesManager.sendMessage(sender, "error.no-permission", new HashMap<>());
            return;
        }
        String key = args[2];
        serverVariablesManager.getServerVariable(key)
                .thenAccept(r -> sendResult(sender, r,
                        Map.of("variable", key, "value", r.getValue() != null ? r.getValue() : ""),
                        "success.global-get"))
                .exceptionally(t -> handleException("获取全局变量失败: " + key, t, sender));
    }

    /**
     * /vex global set <变量名> <值>
     */
    private void handleGlobalSet(CommandSender sender, String[] args) {
        if (args.length < 4) {
            messagesManager.sendMessage(sender, "error.usage-global-set", new HashMap<>());
            return;
        }
        if (!sender.hasPermission("drcomovex.command.global.set")) {
            messagesManager.sendMessage(sender, "error.no-permission", new HashMap<>());
            return;
        }
        String key = args[2], val = args[3];
        serverVariablesManager.setServerVariable(key, val)
                .thenAccept(r -> sendResult(sender, r,
                        Map.of("variable", key, "value", val),
                        "success.global-set"))
                .exceptionally(t -> handleException("设置全局变量失败: " + key, t, sender));
    }

    /**
     * /vex global add <变量名> <值>
     */
    private void handleGlobalAdd(CommandSender sender, String[] args) {
        if (args.length < 4) {
            messagesManager.sendMessage(sender, "error.usage-global-add", new HashMap<>());
            return;
        }
        if (!sender.hasPermission("drcomovex.command.global.add")) {
            messagesManager.sendMessage(sender, "error.no-permission", new HashMap<>());
            return;
        }
        String key = args[2], val = args[3];
        serverVariablesManager.addServerVariable(key, val)
                .thenAccept(r -> sendResult(sender, r,
                        Map.of("variable", key, "value", val, "new_value", r.getValue() != null ? r.getValue() : ""),
                        "success.global-add"))
                .exceptionally(t -> handleException("增加全局变量失败: " + key, t, sender));
    }

    /**
     * /vex global remove <变量名> <值>
     */
    private void handleGlobalRemove(CommandSender sender, String[] args) {
        if (args.length < 4) {
            messagesManager.sendMessage(sender, "error.usage-global-remove", new HashMap<>());
            return;
        }
        if (!sender.hasPermission("drcomovex.command.global.remove")) {
            messagesManager.sendMessage(sender, "error.no-permission", new HashMap<>());
            return;
        }
        String key = args[2], val = args[3];
        serverVariablesManager.removeServerVariable(key, val)
                .thenAccept(r -> sendResult(sender, r,
                        Map.of("variable", key, "value", val, "new_value", r.getValue() != null ? r.getValue() : ""),
                        "success.global-remove"))
                .exceptionally(t -> handleException("移除全局变量失败: " + key, t, sender));
    }

    /**
     * /vex global reset <变量名>
     */
    private void handleGlobalReset(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messagesManager.sendMessage(sender, "error.usage-global-reset", new HashMap<>());
            return;
        }
        if (!sender.hasPermission("drcomovex.command.global.reset")) {
            messagesManager.sendMessage(sender, "error.no-permission", new HashMap<>());
            return;
        }
        String key = args[2];
        serverVariablesManager.resetServerVariable(key)
                .thenAccept(r -> sendResult(sender, r,
                        Map.of("variable", key, "value", r.getValue() != null ? r.getValue() : ""),
                        "success.global-reset"))
                .exceptionally(t -> handleException("重置全局变量失败: " + key, t, sender));
    }

    // ----------------- 其他命令 -----------------

    /**
     * /vex reload
     */
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("drcomovex.admin.reload")) {
            messagesManager.sendMessage(sender, "error.no-permission", new HashMap<>());
            return;
        }
        plugin.getAsyncTaskManager().submitAsync(() -> {
            try {
                plugin.getConfigsManager().reload();
                variablesManager.reload();
                messagesManager.reload();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    messagesManager.sendMessage(sender, "success.reload", new HashMap<>());
                    logger.info("配置已重载，执行者: " + sender.getName());
                });
            } catch (Exception e) {
                logger.error("重载配置失败", e);
                Bukkit.getScheduler().runTask(plugin, () ->
                        messagesManager.sendMessage(sender, "error.reload-failed", new HashMap<>()));
            }
        });
    }

    /**
     * 发送帮助信息
     */
    private void sendHelp(CommandSender sender) {
        messagesManager.sendMessageList(sender, "help.commands", new HashMap<>());
    }

    // ----------------- 私有复用方法 -----------------

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
        if (r.isSuccess()) {
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
        messagesManager.sendMessage(sender, "error.internal", new HashMap<>());
        return null;
    }
}
