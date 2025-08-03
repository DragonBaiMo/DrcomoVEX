package cn.drcomo;

import cn.drcomo.managers.*;
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
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * DrcomoVEX 主指令处理器
 * 
 * 处理所有 /vex 指令的执行和 Tab 补全。
 * 支持子指令: get, set, add, remove, reset, reload, help
 * 
 * @author BaiMo
 */
public class MainCommand implements CommandExecutor, TabCompleter {
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final MessagesManager messagesManager;
    private final VariablesManager variablesManager;
    private final ServerVariablesManager serverVariablesManager;
    private final PlayerVariablesManager playerVariablesManager;
    
    public MainCommand(
            DrcomoVEX plugin,
            DebugUtil logger,
            MessagesManager messagesManager,
            VariablesManager variablesManager,
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
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "get":
                handleGet(sender, args);
                break;
            case "set":
                handleSet(sender, args);
                break;
            case "add":
                handleAdd(sender, args);
                break;
            case "remove":
                handleRemove(sender, args);
                break;
            case "reset":
                handleReset(sender, args);
                break;
            case "reload":
                handleReload(sender, args);
                break;
            case "help":
                sendHelp(sender);
                break;
            default:
                messagesManager.sendMessage(sender, "error.unknown-command", 
                        Map.of("command", subCommand));
                break;
        }
        
        return true;
    }
    
    /**
     * 处理 get 指令
     * 格式: /vex get <变量名> [-p:玩家ID] 或 /vex get <变量名> <玩家名>
     */
    private void handleGet(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messagesManager.sendMessage(sender, "error.usage-get", new HashMap<>());
            return;
        }
        
        String variableKey = args[1];
        
        // 解析参数：支持两种格式
        OfflinePlayer targetPlayer;
        
        // 检查是否使用 -p: 格式
        boolean hasPlayerFlag = false;
        for (String arg : args) {
            if (arg.startsWith("-p:")) {
                hasPlayerFlag = true;
                break;
            }
        }
        
        if (hasPlayerFlag) {
            // 格式: /vex get <变量名> -p:<玩家名>
            targetPlayer = parseTargetPlayer(sender, args);
        } else if (args.length >= 3) {
            // 格式: /vex get <变量名> <玩家名>
            String playerName = args[2];
            targetPlayer = Bukkit.getOfflinePlayer(playerName);
            if (targetPlayer == null || !targetPlayer.hasPlayedBefore()) {
                messagesManager.sendMessage(sender, "error.player-not-found",
                        Map.of("player", playerName));
                return;
            }
        } else {
            // 格式: /vex get <变量名>（查询执行者自己）
            targetPlayer = parseTargetPlayer(sender, args);
        }
        
        if (targetPlayer == null) {
            return;
        }
        
        // 检查权限
        if (!hasPermission(sender, "drcomovex.command.get", targetPlayer)) {
            messagesManager.sendMessage(sender, "error.no-permission", new HashMap<>());
            return;
        }
        
        // 异步获取变量值
        variablesManager.getVariable(targetPlayer, variableKey)
                .thenAccept(result -> {
                    if (result.isSuccess()) {
                        messagesManager.sendMessage(sender, "success.get",
                                Map.of(
                                        "variable", variableKey,
                                        "player", targetPlayer.getName(),
                                        "value", result.getValue()
                                ));
                    } else {
                        messagesManager.sendMessage(sender, "error.variable-not-found",
                                Map.of("variable", variableKey));
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("获取变量失败: " + variableKey, throwable);
                    messagesManager.sendMessage(sender, "error.internal", new HashMap<>());
                    return null;
                });
    }
    
    /**
     * 处理 set 指令
     * 格式: /vex set <变量名> <值> [-p:玩家ID] 或 /vex set <变量名> <玩家名> <值>
     */
    private void handleSet(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messagesManager.sendMessage(sender, "error.usage-set", new HashMap<>());
            return;
        }
        
        String variableKey = args[1];
        
        // 解析参数：支持两种格式
        String value;
        OfflinePlayer targetPlayer;
        
        // 检查是否使用 -p: 格式
        boolean hasPlayerFlag = false;
        for (String arg : args) {
            if (arg.startsWith("-p:")) {
                hasPlayerFlag = true;
                break;
            }
        }
        
        if (hasPlayerFlag) {
            // 格式: /vex set <变量名> <值> -p:<玩家名>
            value = args[2];
            targetPlayer = parseTargetPlayer(sender, args);
        } else if (args.length >= 4) {
            // 格式: /vex set <变量名> <玩家名> <值>
            String playerName = args[2];
            value = args[3];
            targetPlayer = Bukkit.getOfflinePlayer(playerName);
            if (targetPlayer == null || !targetPlayer.hasPlayedBefore()) {
                messagesManager.sendMessage(sender, "error.player-not-found",
                        Map.of("player", playerName));
                return;
            }
        } else {
            // 格式: /vex set <变量名> <值>（操作执行者自己）
            value = args[2];
            targetPlayer = parseTargetPlayer(sender, args);
        }
        
        if (targetPlayer == null) {
            return;
        }
        
        // 检查权限
        if (!hasPermission(sender, "drcomovex.command.set", targetPlayer)) {
            messagesManager.sendMessage(sender, "error.no-permission", new HashMap<>());
            return;
        }
        
        // 异步设置变量值
        variablesManager.setVariable(targetPlayer, variableKey, value)
                .thenAccept(result -> {
                    if (result.isSuccess()) {
                        messagesManager.sendMessage(sender, "success.set",
                                Map.of(
                                        "variable", variableKey,
                                        "player", targetPlayer.getName(),
                                        "value", value
                                ));
                    } else {
                        messagesManager.sendMessage(sender, "error.operation-failed",
                                Map.of("reason", result.getErrorMessage()));
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("设置变量失败: " + variableKey, throwable);
                    messagesManager.sendMessage(sender, "error.internal", new HashMap<>());
                    return null;
                });
    }
    
    /**
     * 处理 add 指令
     * 格式: /vex add <变量名> <值> [-p:玩家ID] 或 /vex add <变量名> <玩家名> <值>
     */
    private void handleAdd(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messagesManager.sendMessage(sender, "error.usage-add", new HashMap<>());
            return;
        }
        
        String variableKey = args[1];
        
        // 解析参数：支持两种格式
        String value;
        OfflinePlayer targetPlayer;
        
        // 检查是否使用 -p: 格式
        boolean hasPlayerFlag = false;
        for (String arg : args) {
            if (arg.startsWith("-p:")) {
                hasPlayerFlag = true;
                break;
            }
        }
        
        if (hasPlayerFlag) {
            // 格式: /vex add <变量名> <值> -p:<玩家名>
            value = args[2];
            targetPlayer = parseTargetPlayer(sender, args);
        } else if (args.length >= 4) {
            // 格式: /vex add <变量名> <玩家名> <值>
            String playerName = args[2];
            value = args[3];
            targetPlayer = Bukkit.getOfflinePlayer(playerName);
            if (targetPlayer == null || !targetPlayer.hasPlayedBefore()) {
                messagesManager.sendMessage(sender, "error.player-not-found",
                        Map.of("player", playerName));
                return;
            }
        } else {
            // 格式: /vex add <变量名> <值>（操作执行者自己）
            value = args[2];
            targetPlayer = parseTargetPlayer(sender, args);
        }
        
        if (targetPlayer == null) {
            return;
        }
        
        // 检查权限
        if (!hasPermission(sender, "drcomovex.command.add", targetPlayer)) {
            messagesManager.sendMessage(sender, "error.no-permission", new HashMap<>());
            return;
        }
        
        // 异步增加变量值
        variablesManager.addVariable(targetPlayer, variableKey, value)
                .thenAccept(result -> {
                    if (result.isSuccess()) {
                        messagesManager.sendMessage(sender, "success.add",
                                Map.of(
                                        "variable", variableKey,
                                        "player", targetPlayer.getName(),
                                        "value", value,
                                        "new_value", result.getValue()
                                ));
                    } else {
                        messagesManager.sendMessage(sender, "error.operation-failed",
                                Map.of("reason", result.getErrorMessage()));
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("增加变量失败: " + variableKey, throwable);
                    messagesManager.sendMessage(sender, "error.internal", new HashMap<>());
                    return null;
                });
    }
    
    /**
     * 处理 remove 指令
     * 格式: /vex remove <变量名> <值> [-p:玩家ID] 或 /vex remove <变量名> <玩家名> <值>
     */
    private void handleRemove(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messagesManager.sendMessage(sender, "error.usage-remove", new HashMap<>());
            return;
        }
        
        String variableKey = args[1];
        
        // 解析参数：支持两种格式
        String value;
        OfflinePlayer targetPlayer;
        
        // 检查是否使用 -p: 格式
        boolean hasPlayerFlag = false;
        for (String arg : args) {
            if (arg.startsWith("-p:")) {
                hasPlayerFlag = true;
                break;
            }
        }
        
        if (hasPlayerFlag) {
            // 格式: /vex remove <变量名> <值> -p:<玩家名>
            value = args[2];
            targetPlayer = parseTargetPlayer(sender, args);
        } else if (args.length >= 4) {
            // 格式: /vex remove <变量名> <玩家名> <值>
            String playerName = args[2];
            value = args[3];
            targetPlayer = Bukkit.getOfflinePlayer(playerName);
            if (targetPlayer == null || !targetPlayer.hasPlayedBefore()) {
                messagesManager.sendMessage(sender, "error.player-not-found",
                        Map.of("player", playerName));
                return;
            }
        } else {
            // 格式: /vex remove <变量名> <值>（操作执行者自己）
            value = args[2];
            targetPlayer = parseTargetPlayer(sender, args);
        }
        
        if (targetPlayer == null) {
            return;
        }
        
        // 检查权限
        if (!hasPermission(sender, "drcomovex.command.remove", targetPlayer)) {
            messagesManager.sendMessage(sender, "error.no-permission", new HashMap<>());
            return;
        }
        
        // 异步移除变量值
        variablesManager.removeVariable(targetPlayer, variableKey, value)
                .thenAccept(result -> {
                    if (result.isSuccess()) {
                        messagesManager.sendMessage(sender, "success.remove",
                                Map.of(
                                        "variable", variableKey,
                                        "player", targetPlayer.getName(),
                                        "value", value,
                                        "new_value", result.getValue()
                                ));
                    } else {
                        messagesManager.sendMessage(sender, "error.operation-failed",
                                Map.of("reason", result.getErrorMessage()));
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("移除变量失败: " + variableKey, throwable);
                    messagesManager.sendMessage(sender, "error.internal", new HashMap<>());
                    return null;
                });
    }
    
    /**
     * 处理 reset 指令
     * 格式: /vex reset <变量名> [-p:玩家ID] 或 /vex reset <变量名> <玩家名>
     */
    private void handleReset(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messagesManager.sendMessage(sender, "error.usage-reset", new HashMap<>());
            return;
        }
        
        String variableKey = args[1];
        
        // 解析参数：支持两种格式
        OfflinePlayer targetPlayer;
        
        // 检查是否使用 -p: 格式
        boolean hasPlayerFlag = false;
        for (String arg : args) {
            if (arg.startsWith("-p:")) {
                hasPlayerFlag = true;
                break;
            }
        }
        
        if (hasPlayerFlag) {
            // 格式: /vex reset <变量名> -p:<玩家名>
            targetPlayer = parseTargetPlayer(sender, args);
        } else if (args.length >= 3) {
            // 格式: /vex reset <变量名> <玩家名>
            String playerName = args[2];
            targetPlayer = Bukkit.getOfflinePlayer(playerName);
            if (targetPlayer == null || !targetPlayer.hasPlayedBefore()) {
                messagesManager.sendMessage(sender, "error.player-not-found",
                        Map.of("player", playerName));
                return;
            }
        } else {
            // 格式: /vex reset <变量名>（重置执行者自己）
            targetPlayer = parseTargetPlayer(sender, args);
        }
        
        if (targetPlayer == null) {
            return;
        }
        
        // 检查权限
        if (!hasPermission(sender, "drcomovex.command.reset", targetPlayer)) {
            messagesManager.sendMessage(sender, "error.no-permission", new HashMap<>());
            return;
        }
        
        // 异步重置变量
        variablesManager.resetVariable(targetPlayer, variableKey)
                .thenAccept(result -> {
                    if (result.isSuccess()) {
                        messagesManager.sendMessage(sender, "success.reset",
                                Map.of(
                                        "variable", variableKey,
                                        "player", targetPlayer.getName(),
                                        "value", result.getValue()
                                ));
                    } else {
                        messagesManager.sendMessage(sender, "error.operation-failed",
                                Map.of("reason", result.getErrorMessage()));
                    }
                })
                .exceptionally(throwable -> {
                    logger.error("重置变量失败: " + variableKey, throwable);
                    messagesManager.sendMessage(sender, "error.internal", new HashMap<>());
                    return null;
                });
    }
    
    /**
     * 处理 reload 指令
     * 格式: /vex reload
     */
    private void handleReload(CommandSender sender, String[] args) {
        // 检查权限
        if (!sender.hasPermission("drcomovex.admin.reload")) {
            messagesManager.sendMessage(sender, "error.no-permission", new HashMap<>());
            return;
        }
        
        // 异步重载配置
        plugin.getAsyncTaskManager().submitAsync(() -> {
            try {
                // 重载配置
                plugin.getConfigsManager().reload();
                
                // 重载变量定义
                variablesManager.reload();
                
                // 重载消息
                messagesManager.reload();
                
                // 在主线程中发送消息
                Bukkit.getScheduler().runTask(plugin, () -> {
                    messagesManager.sendMessage(sender, "success.reload", new HashMap<>());
                    logger.info("配置已重载，执行者: " + sender.getName());
                });
                
            } catch (Exception e) {
                logger.error("重载配置失败", e);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    messagesManager.sendMessage(sender, "error.reload-failed", new HashMap<>());
                });
            }
        });
    }
    
    /**
     * 发送帮助信息
     */
    private void sendHelp(CommandSender sender) {
        messagesManager.sendMessageList(sender, "help.commands", new HashMap<>());
    }
    
    /**
     * 解析目标玩家
     */
    private OfflinePlayer parseTargetPlayer(CommandSender sender, String[] args) {
        // 查找 -p: 参数
        String targetPlayerName = null;
        for (String arg : args) {
            if (arg.startsWith("-p:")) {
                targetPlayerName = arg.substring(3);
                break;
            }
        }
        
        // 如果没有指定目标玩家，默认为命令执行者
        if (targetPlayerName == null) {
            if (sender instanceof Player) {
                return (Player) sender;
            } else {
                messagesManager.sendMessage(sender, "error.console-specify-player", new HashMap<>());
                return null;
            }
        }
        
        // 获取目标玩家
        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetPlayerName);
        if (targetPlayer == null || !targetPlayer.hasPlayedBefore()) {
            messagesManager.sendMessage(sender, "error.player-not-found",
                    Map.of("player", targetPlayerName));
            return null;
        }
        
        return targetPlayer;
    }
    
    /**
     * 检查权限
     */
    private boolean hasPermission(CommandSender sender, String permission, OfflinePlayer targetPlayer) {
        // 基本权限检查
        if (!sender.hasPermission(permission)) {
            return false;
        }
        
        // 如果操作其他玩家，需要额外权限
        if (sender instanceof Player && !sender.getName().equals(targetPlayer.getName())) {
            return sender.hasPermission(permission + ".others");
        }
        
        return true;
    }
    
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (args.length == 1) {
            // 子指令补全
            List<String> subCommands = Arrays.asList("get", "set", "add", "remove", "reset", "reload", "help");
            return subCommands.stream()
                    .filter(cmd -> cmd.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        
        if (args.length == 2 && !args[0].equalsIgnoreCase("reload") && !args[0].equalsIgnoreCase("help")) {
            // 变量名补全
            return variablesManager.getAllVariableKeys().stream()
                    .filter(key -> key.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        
        if (args.length >= 3) {
            // 玩家名补全 (-p:参数)
            for (String arg : args) {
                if (arg.startsWith("-p:")) {
                    return Collections.emptyList();
                }
            }
            
            // 提供 -p: 提示
            if (sender.hasPermission("drcomovex.command." + args[0].toLowerCase() + ".others")) {
                completions.add("-p:");
            }
        }
        
        return completions;
    }
}