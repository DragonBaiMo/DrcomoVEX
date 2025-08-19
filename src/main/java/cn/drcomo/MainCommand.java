package cn.drcomo;

import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.managers.MessagesManager;
import cn.drcomo.managers.PlayerVariablesManager;
import cn.drcomo.managers.RefactoredVariablesManager;
import cn.drcomo.managers.ServerVariablesManager;
import cn.drcomo.model.VariableResult;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * DrcomoVEX 主指令处理器
 *
 * 处理所有 /vex 指令的执行和 Tab 补全。
 * 支持子指令: player, global, reload, help
 *
 * 优化说明：
 * 1. 提取了参数/权限校验的复用逻辑（checkArgs / requirePermission / checkBasePermission 等）
 * 2. 提取了玩家“单人写操作”的公共流程为 handlePlayerSingleWrite，减少 set/add/remove/reset 的重复代码
 * 3. 提取了导出、通配/条件处理等工具方法，集中管理
 * 4. 保留并补充注释，重排方法顺序提高可读性；功能保持不变
 *
 * 作者: BaiMo
 */
public class MainCommand implements CommandExecutor, TabCompleter {

    /** 异步回调超时时间（秒） */
    private static final long TIMEOUT_SECONDS = 5L;
    /** 批量查询数据库超时时间（秒） */
    private static final long DB_QUERY_TIMEOUT_SECONDS = 15L;
    /** 批量操作默认限制 */
    private static final int DEFAULT_BULK_LIMIT = 100;
    /** 批量操作硬上限 */
    private static final int MAX_BULK_LIMIT = 1000;
    /** 预览示例最大行数 */
    private static final int MAX_PREVIEW_EXAMPLES = 10;
    /** 空参数常量，减少重复 new HashMap<>() */
    private static final Map<String, String> EMPTY_PARAMS = Collections.emptyMap();
    /** 根子指令列表 */
    private static final List<String> ROOT_SUBS = List.of("player", "global", "reload", "help");
    /** 变量操作列表 */
    private static final List<String> VAR_OPS = List.of("get", "set", "add", "remove", "reset");

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
                messagesManager.sendMessage(sender, "error.unknown-command", Map.of("command", scope));
                sendHelp(sender);
        }
        return true;
    }

    // ----------------- TabCompleter -----------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], ROOT_SUBS, new ArrayList<>());
        }
        if (args.length == 2) {
            String scope = args[0].toLowerCase();
            if ("player".equals(scope) || "global".equals(scope)) {
                return StringUtil.copyPartialMatches(args[1], VAR_OPS, new ArrayList<>());
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
                messagesManager.sendMessage(sender, "error.unknown-player-subcommand", Map.of("command", args[1]));
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
                messagesManager.sendMessage(sender, "error.unknown-global-subcommand", Map.of("command", args[1]));
        }
    }

    // ----------------- 玩家指令实现 -----------------

    /** /vex player get <玩家名|UUID> <变量名> [--offline] */
    private void handlePlayerGet(CommandSender sender, String[] args) {
        if (!checkArgs(sender, args, 4, "error.usage-player-get")) return;

        // 批量模式：玩家为 *
        if ("*".equals(args[2])) {
            handlePlayerBatchGet(sender, args);
            return;
        }

        boolean allowOffline = hasFlag(args, 4, "--offline");
        OfflinePlayer target = resolvePlayer(sender, args[2], allowOffline);
        if (target == null) return;

        if (!checkBasePermission(sender, "drcomovex.command.player.get", target)) {
            messagesManager.sendMessage(sender, "error.no-permission", EMPTY_PARAMS);
            return;
        }

        String key = args[3];
        playerVariablesManager.getPlayerVariable(target, key)
                .completeOnTimeout(VariableResult.failure("操作超时"), TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((r, t) -> {
                    if (t != null) {
                        handleException("获取玩家变量失败: " + key, t, sender);
                    } else {
                        sendResult(sender, r,
                                Map.of("variable", key,
                                       "player", target.getName(),
                                       "value", r.getValue() != null ? r.getValue() : ""),
                                "success.player-get");
                    }
                });
    }

    /** /vex player set <玩家名|UUID> <变量名> <值> [--offline] */
    private void handlePlayerSet(CommandSender sender, String[] args) {
        if (!checkArgs(sender, args, 5, "error.usage-player-set")) return;
        if ("*".equals(args[2])) {
            handlePlayerBatchWrite(sender, args, "set");
            return;
        }
        boolean allowOffline = hasFlag(args, 5, "--offline");
        handlePlayerSingleWrite(
                sender, args,
                "drcomovex.command.player.set",
                /*needsValue*/ true,
                (target, key, value) -> playerVariablesManager.setPlayerVariable(target, key, value),
                (targetName, key, value, resultValue) -> Map.of(
                        "variable", key,
                        "player", targetName,
                        "value", value
                ),
                "success.player-set",
                "设置玩家变量失败",
                allowOffline
        );
    }

    /** /vex player add <玩家名|UUID> <变量名> <值> [--offline] */
    private void handlePlayerAdd(CommandSender sender, String[] args) {
        if (!checkArgs(sender, args, 5, "error.usage-player-add")) return;
        if ("*".equals(args[2])) {
            handlePlayerBatchWrite(sender, args, "add");
            return;
        }
        boolean allowOffline = hasFlag(args, 5, "--offline");
        handlePlayerSingleWrite(
                sender, args,
                "drcomovex.command.player.add",
                /*needsValue*/ true,
                (target, key, value) -> {
                    logger.debug("开始执行玩家变量添加操作: player=" + target.getName() + ", key=" + key + ", value=" + value);
                    return playerVariablesManager.addPlayerVariable(target, key, value)
                            .whenComplete((r, t) -> logger.debug("玩家变量添加操作完成: result=" + (r != null ? r.isSuccess() : "null")
                                    + ", throwable=" + (t != null ? t.getMessage() : "null")));
                },
                (targetName, key, value, resultValue) -> Map.of(
                        "variable", key,
                        "player", targetName,
                        "value", value,
                        "new_value", resultValue != null ? resultValue : ""
                ),
                "success.player-add",
                "增加玩家变量失败",
                allowOffline
        );
        logger.debug("异步操作已提交，等待回调");
    }

    /** /vex player remove <玩家名|UUID> <变量名> <值> [--offline] */
    private void handlePlayerRemove(CommandSender sender, String[] args) {
        if (!checkArgs(sender, args, 5, "error.usage-player-remove")) return;
        if ("*".equals(args[2])) {
            handlePlayerBatchWrite(sender, args, "remove");
            return;
        }
        boolean allowOffline = hasFlag(args, 5, "--offline");
        handlePlayerSingleWrite(
                sender, args,
                "drcomovex.command.player.remove",
                /*needsValue*/ true,
                (target, key, value) -> playerVariablesManager.removePlayerVariable(target, key, value),
                (targetName, key, value, resultValue) -> Map.of(
                        "variable", key,
                        "player", targetName,
                        "value", value,
                        "new_value", resultValue != null ? resultValue : ""
                ),
                "success.player-remove",
                "移除玩家变量失败",
                allowOffline
        );
    }

    /** /vex player reset <玩家名|UUID> <变量名> [--offline] */
    private void handlePlayerReset(CommandSender sender, String[] args) {
        if (!checkArgs(sender, args, 4, "error.usage-player-reset")) return;
        if ("*".equals(args[2])) {
            handlePlayerBatchWrite(sender, args, "reset");
            return;
        }
        boolean allowOffline = hasFlag(args, 4, "--offline");
        handlePlayerSingleWrite(
                sender, args,
                "drcomovex.command.player.reset",
                /*needsValue*/ false,
                (target, key, value) -> playerVariablesManager.resetPlayerVariable(target, key),
                (targetName, key, value, resultValue) -> Map.of(
                        "variable", key,
                        "player", targetName,
                        "value", resultValue != null ? resultValue : ""
                ),
                "success.player-reset",
                "重置玩家变量失败",
                allowOffline
        );
    }

    // ----------------- 全局指令实现 -----------------

    /** /vex global get <变量名> */
    private void handleGlobalGet(CommandSender sender, String[] args) {
        if (!checkArgs(sender, args, 3, "error.usage-global-get")) return;
        if (!requirePermission(sender, "drcomovex.command.global.get", "error.no-permission")) return;

        String spec = args[2];
        if (isWildcard(spec) || hasCondition(spec)) {
            handleGlobalBatchGet(sender, args);
            return;
        }

        String key = spec;
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

        String spec = args[2];
        if (isWildcard(spec)) {
            handleGlobalBatchWrite(sender, args, "set");
            return;
        }

        String key = spec, val = args[3];
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

        String spec = args[2];
        if (isWildcard(spec)) {
            handleGlobalBatchWrite(sender, args, "add");
            return;
        }

        String key = spec, val = args[3];
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

        String spec = args[2];
        if (isWildcard(spec)) {
            handleGlobalBatchWrite(sender, args, "remove");
            return;
        }

        String key = spec, val = args[3];
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

        String spec = args[2];
        if (isWildcard(spec)) {
            handleGlobalBatchWrite(sender, args, "reset");
            return;
        }

        String key = spec;
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
        messagesManager.sendMessageList(sender, "help.flags", EMPTY_PARAMS);
    }

    // ----------------- 批量/通配实现（玩家/全局） -----------------

    // ----- 玩家 批量查询 -----
    private void handlePlayerBatchGet(CommandSender sender, String[] args) {
        String spec = args[3];
        if (!requireBulkOthersPermission(sender, "drcomovex.command.player.get")) return;

        Optional<ValueCondition> cond = parseCondition(spec);
        String glob = extractGlob(spec);
        boolean dbOnly = hasFlag(args, 4, "--db-only");
        boolean onlineOnly = hasFlag(args, 4, "--online-only");
        boolean doDb = !onlineOnly;        // 默认执行数据库扫描
        boolean doOnline = !dbOnly;        // 默认也执行在线内存扫描
        Optional<String> outOpt = parseOutFile(args, 4);

        // 将通配 * 转 SQL LIKE：* -> %，并对 _ 和 % 转义（仅用于 LIKE 查询）
        String like = glob.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%").replace('*', '%');
        boolean wildcard = isWildcard(glob);
        String sqlKey = wildcard ? like : glob; // 等值查询必须使用原始 glob，不能带转义符

        // 预先计算匹配的变量键（用于在线玩家内存值补全）
        Pattern regex = globToRegex(glob);
        List<String> matchedKeys = doOnline
                ? variablesManager.getPlayerVariableKeys().stream().filter(k -> regex.matcher(k).matches()).collect(Collectors.toList())
                : Collections.emptyList();

        CompletableFuture<Object[]> base = new CompletableFuture<>();
        Set<String> seen = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger count = new AtomicInteger();
        List<String> previews = Collections.synchronizedList(new ArrayList<>());
        Map<String, Map<String, String>> uuidToVars = Collections.synchronizedMap(new HashMap<>());
        Map<String, String> uuidToName = Collections.synchronizedMap(new HashMap<>());

        CompletableFuture<Void> dbFuture = CompletableFuture.completedFuture(null);
        if (doDb) {
            dbFuture = plugin.getDatabase().queryPlayerVariablesByKeyAsync(sqlKey, wildcard)
                    .thenAccept(rows -> {
                        if (rows != null) {
                            for (String[] row : rows) {
                                String playerId = row[0];
                                String key = row[1];
                                String value = row[2];
                                if (value != null && matchCondition(value, cond)) {
                                    String uniq = playerId + "::" + key;
                                    if (seen.add(uniq)) {
                                        count.incrementAndGet();
                                        if (previews.size() < MAX_PREVIEW_EXAMPLES) {
                                            previews.add("玩家UUID=" + playerId + " 变量=" + key + " 值=" + value);
                                        }
                                        uuidToVars.computeIfAbsent(playerId, k -> Collections.synchronizedMap(new HashMap<>()))
                                                .put(key, value);
                                    }
                                }
                            }
                        }
                    })
                    .orTimeout(DB_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(t -> {
                        logger.warn("数据库批量查询超时或失败: " + t.getMessage());
                        return null;
                    });
        }

        CompletableFuture<Void> onlineFuture = CompletableFuture.completedFuture(null);
        if (doOnline && !matchedKeys.isEmpty()) {
            List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Player p : online) {
                for (String key : matchedKeys) {
                    String uniq = p.getUniqueId() + "::" + key;
                    if (seen.contains(uniq)) continue;
                    futures.add(playerVariablesManager.getPlayerVariable(p, key).thenAccept(r -> {
                        if (r != null && r.isSuccess() && r.getValue() != null && matchCondition(r.getValue(), cond)) {
                            if (seen.add(uniq)) {
                                count.incrementAndGet();
                                if (previews.size() < MAX_PREVIEW_EXAMPLES) {
                                    previews.add("玩家=" + p.getName() + " 变量=" + key + " 值=" + r.getValue());
                                }
                                String uuid = p.getUniqueId().toString();
                                uuidToVars.computeIfAbsent(uuid, k -> Collections.synchronizedMap(new HashMap<>()))
                                        .put(key, r.getValue());
                                uuidToName.putIfAbsent(uuid, p.getName());
                            }
                        }
                    }));
                }
            }
            onlineFuture = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(DB_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(t -> {
                        logger.warn("在线内存扫描超时或失败: " + t.getMessage());
                        return null;
                    });
        }

        CompletableFuture.allOf(dbFuture, onlineFuture)
                .thenAccept(v -> base.complete(new Object[]{ count.get(), previews }))
                .exceptionally(t -> { base.completeExceptionally(t); return null; });

        base.thenAccept(result -> {
            int c = (int) result[0];
            @SuppressWarnings("unchecked") List<String> pv = (List<String>) result[1];
            if (c == 0) {
                messagesManager.sendMessage(sender, "error.no-candidates", EMPTY_PARAMS);
                return;
            }
            messagesManager.sendMessage(sender, "success.player-query-summary", Map.of("count", String.valueOf(c)));
            for (String line : pv) {
                messagesManager.sendMessage(sender, "info.player-query-item", Map.of("player", "", "value", line));
            }
            outOpt.ifPresent(name -> {
                try {
                    File outFile = exportPlayerQueryToYaml(name, glob, cond, uuidToVars, uuidToName, c);
                    messagesManager.sendMessage(sender, "success.player-query-saved",
                            Map.of("file", "exports/" + outFile.getName(),
                                   "players", String.valueOf(uuidToVars.size()),
                                   "matches", String.valueOf(c)));
                } catch (Exception ex) {
                    logger.error("导出查询结果到 YML 失败", ex);
                    messagesManager.sendMessage(sender, "error.export-failed", Map.of("reason", ex.getMessage() != null ? ex.getMessage() : "未知错误"));
                }
            });
        }).exceptionally(t -> {
            String msg = t != null && t.getMessage() != null ? t.getMessage() : "未知错误";
            if (msg.toLowerCase().contains("timeout") || msg.toLowerCase().contains("timed")) {
                messagesManager.sendMessage(sender, "error.operation-failed", Map.of("reason", "数据库查询超时"));
                return null;
            }
            return handleException("批量查询玩家变量失败", t, sender);
        });
    }

    // ----- 玩家 批量写入(set/add/remove/reset) -----
    private void handlePlayerBatchWrite(CommandSender sender, String[] args, String action) {
        // 语法：/vex player <action> * <变量通配> [<值或增量>] [-n] [--limit N]
        String spec = args[3];
        boolean dryRun = parseDryRun(args, 4);
        int limit = parseLimit(args, 4);

        String basePerm = "drcomovex.command.player." + action;
        if (!requireBulkOthersPermission(sender, basePerm)) return;

        Optional<ValueCondition> cond = parseCondition(spec);
        String glob = extractGlob(spec);
        Pattern regex = globToRegex(glob);
        List<String> matchedKeys = variablesManager.getPlayerVariableKeys().stream()
                .filter(k -> regex.matcher(k).matches())
                .collect(Collectors.toList());
        if (matchedKeys.isEmpty()) {
            messagesManager.sendMessage(sender, "error.no-candidates", EMPTY_PARAMS);
            return;
        }
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());

        List<Map<String, String>> candidates = new ArrayList<>();
        List<CompletableFuture<Void>> scanFutures = new ArrayList<>();
        for (Player p : players) {
            for (String key : matchedKeys) {
                scanFutures.add(playerVariablesManager.getPlayerVariable(p, key).thenAccept(r -> {
                    if (r != null && r.isSuccess() && r.getValue() != null && matchCondition(r.getValue(), cond)) {
                        if (candidates.size() < MAX_BULK_LIMIT) {
                            Map<String, String> item = new HashMap<>();
                            item.put("player", p.getName());
                            item.put("key", key);
                            item.put("old", r.getValue());
                            candidates.add(item);
                        }
                    }
                }));
            }
        }
        CompletableFuture.allOf(scanFutures.toArray(new CompletableFuture[0])).whenComplete((vv, tt) -> {
            if (tt != null) {
                handleException("批量扫描玩家变量失败", tt, sender);
                return;
            }
            int affected = Math.min(candidates.size(), limit);
            String variableShow = glob;

            if (dryRun) {
                messagesManager.sendMessage(sender, "success.player-bulk-dryrun",
                        Map.of("count", String.valueOf(affected), "action", action, "variable", variableShow));
                int preview = Math.min(affected, MAX_PREVIEW_EXAMPLES);
                for (int i = 0; i < preview; i++) {
                    Map<String, String> it = candidates.get(i);
                    String newVal = computeNewValuePreview(action, it.get("old"), args);
                    messagesManager.sendMessage(sender, "info.player-bulk-preview-item",
                            Map.of("player", it.get("player"), "old", it.get("old"), "new", newVal));
                }
                return;
            }

            // 执行写入
            List<CompletableFuture<VariableResult>> writeFutures = new ArrayList<>();
            for (int i = 0; i < affected; i++) {
                Map<String, String> it = candidates.get(i);
                Player p = Bukkit.getPlayerExact(it.get("player"));
                if (p == null) continue;
                String key = it.get("key");
                switch (action) {
                    case "set":
                        if (args.length < 5) { messagesManager.sendMessage(sender, "error.invalid-number", Map.of("value", "")); return; }
                        writeFutures.add(playerVariablesManager.setPlayerVariable(p, key, args[4]));
                        break;
                    case "add":
                        if (args.length < 5) { messagesManager.sendMessage(sender, "error.invalid-number", Map.of("value", "")); return; }
                        writeFutures.add(playerVariablesManager.addPlayerVariable(p, key, args[4]));
                        break;
                    case "remove":
                        if (args.length < 5) { messagesManager.sendMessage(sender, "error.invalid-number", Map.of("value", "")); return; }
                        writeFutures.add(playerVariablesManager.removePlayerVariable(p, key, args[4]));
                        break;
                    case "reset":
                        writeFutures.add(playerVariablesManager.resetPlayerVariable(p, key));
                        break;
                    default:
                        break;
                }
            }
            CompletableFuture.allOf(writeFutures.toArray(new CompletableFuture[0])).whenComplete((v3, t3) -> {
                int success = 0; int failed = 0;
                for (CompletableFuture<VariableResult> f : writeFutures) {
                    try {
                        VariableResult r = f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        if (r != null && r.isSuccess()) success++; else failed++;
                    } catch (Exception e) { failed++; }
                }
                messagesManager.sendMessage(sender, "success.player-bulk-done",
                        Map.of("success", String.valueOf(success),
                               "failed", String.valueOf(failed),
                               "variable", variableShow));
            });
        });
    }

    // ----- 全局 批量查询 -----
    private void handleGlobalBatchGet(CommandSender sender, String[] args) {
        String spec = args[2];
        Optional<ValueCondition> cond = parseCondition(spec);
        String glob = extractGlob(spec);
        Pattern regex = globToRegex(glob);

        List<String> matchedKeys = variablesManager.getGlobalVariableKeys().stream()
                .filter(k -> regex.matcher(k).matches())
                .collect(Collectors.toList());
        if (matchedKeys.isEmpty()) {
            messagesManager.sendMessage(sender, "error.no-candidates", EMPTY_PARAMS);
            return;
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        AtomicInteger count = new AtomicInteger();
        List<String> previews = new ArrayList<>();
        for (String key : matchedKeys) {
            futures.add(serverVariablesManager.getServerVariable(key).thenAccept(r -> {
                if (r != null && r.isSuccess() && r.getValue() != null && matchCondition(r.getValue(), cond)) {
                    count.incrementAndGet();
                    if (previews.size() < MAX_PREVIEW_EXAMPLES) {
                        previews.add("变量=" + key + " 值=" + r.getValue());
                    }
                }
            }));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((v, t) -> {
            if (t != null) { handleException("批量查询全局变量失败", t, sender); return; }
            messagesManager.sendMessage(sender, "success.player-query-summary", Map.of("count", String.valueOf(count.get())));
            for (String line : previews) {
                messagesManager.sendMessage(sender, "info.player-query-item", Map.of("player", "", "value", line));
            }
        });
    }

    // ----- 全局 批量写入 -----
    private void handleGlobalBatchWrite(CommandSender sender, String[] args, String action) {
        // 语法：/vex global <action> <变量通配> <值或增量?> [-n]
        String glob = args[2];
        boolean dryRun = parseDryRun(args, 3);

        Pattern regex = globToRegex(glob);
        List<String> matchedKeys = variablesManager.getGlobalVariableKeys().stream()
                .filter(k -> regex.matcher(k).matches())
                .collect(Collectors.toList());
        if (matchedKeys.isEmpty()) {
            messagesManager.sendMessage(sender, "error.no-candidates", EMPTY_PARAMS);
            return;
        }

        if (dryRun) {
            messagesManager.sendMessage(sender, "success.global-bulk-dryrun",
                    Map.of("action", action, "variable", glob, "target", args.length >= 4 ? args[3] : ""));
            int preview = Math.min(matchedKeys.size(), MAX_PREVIEW_EXAMPLES);
            for (int i = 0; i < preview; i++) {
                messagesManager.sendMessage(sender, "info.player-query-item", Map.of("player", "", "value", matchedKeys.get(i)));
            }
            return;
        }

        List<CompletableFuture<VariableResult>> futures = new ArrayList<>();
        for (String key : matchedKeys) {
            switch (action) {
                case "set":
                    if (args.length < 4) { messagesManager.sendMessage(sender, "error.invalid-number", Map.of("value", "")); return; }
                    futures.add(serverVariablesManager.setServerVariable(key, args[3]));
                    break;
                case "add":
                    if (args.length < 4) { messagesManager.sendMessage(sender, "error.invalid-number", Map.of("value", "")); return; }
                    futures.add(serverVariablesManager.addServerVariable(key, args[3]));
                    break;
                case "remove":
                    if (args.length < 4) { messagesManager.sendMessage(sender, "error.invalid-number", Map.of("value", "")); return; }
                    futures.add(serverVariablesManager.removeServerVariable(key, args[3]));
                    break;
                case "reset":
                    futures.add(serverVariablesManager.resetServerVariable(key));
                    break;
                default:
                    break;
            }
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((v, t) -> {
            int success = 0; int failed = 0;
            for (CompletableFuture<VariableResult> f : futures) {
                try {
                    VariableResult r = f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (r != null && r.isSuccess()) success++; else failed++;
                } catch (Exception e) { failed++; }
            }
            messagesManager.sendMessage(sender, "success.global-bulk-done",
                    Map.of("variable", glob, "result", success + "/" + (success + failed)));
        });
    }

    // ----------------- 私有复用方法 -----------------

    /**
     * 玩家“单人写操作”公共流程：
     * - 解析玩家
     * - 权限校验（含 .others）
     * - 调用指定操作并统一处理回调/异常
     *
     * @param needsValue 是否需要值参数（set/add/remove 为 true，reset 为 false）
     * @param op         真实变量操作实现（返回 CompletionStage<VariableResult>）
     * @param paramBuilder 根据不同操作构造消息占位参数
     * @param successKey 成功消息键
     * @param errorLogPrefix 日志前缀
     */
    private void handlePlayerSingleWrite(
            CommandSender sender,
            String[] args,
            String basePerm,
            boolean needsValue,
            PlayerVarOp op,
            ParamBuilder paramBuilder,
            String successKey,
            String errorLogPrefix,
            boolean allowOffline
    ) {
        // args: /vex player <action> <player> <key> [<value>]
        OfflinePlayer target = resolvePlayer(sender, args[2], allowOffline);
        if (target == null) return;

        if (!checkBasePermission(sender, basePerm, target)) {
            messagesManager.sendMessage(sender, "error.no-permission", EMPTY_PARAMS);
            return;
        }

        String key = args[3];
        String value = needsValue ? args[4] : null;

        op.apply(target, key, value)
        .toCompletableFuture() // 关键：先转 CompletableFuture
        .completeOnTimeout(VariableResult.failure("操作超时"), TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .whenComplete((r, t) -> {
            if (t != null) {
                handleException(errorLogPrefix + ": " + key, t, sender);
            } else if (r == null) {
                messagesManager.sendMessage(sender, "error.internal", EMPTY_PARAMS);
            } else {
                Map<String, String> params = paramBuilder.build(
                        Objects.requireNonNullElse(target.getName(), ""),
                        key,
                        value,
                        r.getValue()
                );
                sendResult(sender, r, params, successKey);
            }
        });

    }

    /** 参数长度校验，若不满足则发送错误用法并返回 false */
    private boolean checkArgs(CommandSender sender, String[] args, int minLength, String usageKey) {
        if (args.length < minLength) {
            messagesManager.sendMessage(sender, usageKey, EMPTY_PARAMS);
            return false;
        }
        return true;
    }

    /** 权限校验，若无权限则发送错误提示并返回 false */
    private boolean requirePermission(CommandSender sender, String perm, String errorKey) {
        if (!sender.hasPermission(perm)) {
            messagesManager.sendMessage(sender, errorKey, EMPTY_PARAMS);
            return false;
        }
        return true;
    }

    /** 检查基础权限：包括操作自身和操作他人 (.others) */
    private boolean checkBasePermission(CommandSender sender, String basePerm, OfflinePlayer target) {
        if (!sender.hasPermission(basePerm)) return false;
        if (sender instanceof Player && !sender.getName().equals(target.getName())) {
            return sender.hasPermission(basePerm + ".others");
        }
        return true;
    }

    /** 解析并验证目标玩家（优先按玩家名，其次按 UUID），若无效则发送错误消息并返回 null */
    private OfflinePlayer resolvePlayer(CommandSender sender, String playerArg, boolean allowOffline) {
        // 1) 在线玩家精确名称匹配
        Player online = Bukkit.getPlayerExact(playerArg);
        if (online != null) {
            return online;
        }

        // 2) 历史离线玩家名称匹配（忽略大小写；默认不支持离线，除非 allowOffline=true）
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            String name = op.getName();
            if (name != null && name.equalsIgnoreCase(playerArg)) {
                if (allowOffline) return op;
                if (op.hasPlayedBefore()) return op;
            }
        }

        // 3) 按 UUID 解析（默认不支持离线，除非 allowOffline=true）
        try {
            UUID uuid = UUID.fromString(playerArg);
            OfflinePlayer byUuid = Bukkit.getOfflinePlayer(uuid);
            if (byUuid != null) {
                if (allowOffline) return byUuid;
                if (byUuid.isOnline() || byUuid.hasPlayedBefore()) return byUuid;
            }
        } catch (IllegalArgumentException ignored) {
            // 非合法 UUID，忽略
        }

        messagesManager.sendMessage(sender, "error.player-not-found", Map.of("player", playerArg));
        return null;
    }

    /** 统一处理异步结果：成功发送指定消息，失败发送 error.operation-failed */
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

    /** 统一处理异常：记录日志并发送内部错误 */
    private Void handleException(String logPrefix, Throwable t, CommandSender sender) {
        logger.error(logPrefix, t);
        messagesManager.sendMessage(sender, "error.internal", EMPTY_PARAMS);
        return null;
    }

    /**
     * 统一处理全局变量异步调用结果，简化重复逻辑
     * - 将结果值回填到 "value"/"new_value"（调用方未提供或为空时）
     */
    private void handleServerVariable(
            CompletionStage<VariableResult> stage,
            CommandSender sender,
            Map<String, String> params,
            String successKey,
            String errorLogPrefix
    ) {
        stage.thenAccept(r -> {
                    if (r == null) {
                        messagesManager.sendMessage(sender, "error.internal", EMPTY_PARAMS);
                        return;
                    }
                    Map<String, String> merged = new HashMap<>(params);
                    String resultVal = r.getValue();
                    if (resultVal != null && !resultVal.isEmpty()) {
                        merged.put("value", resultVal);
                        String newVal = merged.get("new_value");
                        if (newVal == null || newVal.isEmpty()) {
                            merged.put("new_value", resultVal);
                        }
                    }
                    sendResult(sender, r, merged, successKey);
                })
                .exceptionally(t -> handleException(errorLogPrefix, t, sender));
    }

    // ----------------- 通配/条件/参数 工具方法 -----------------

    private boolean requireBulkOthersPermission(CommandSender sender, String basePerm) {
        // 批量操作等同于操作他人，要求 others 权限
        return requirePermission(sender, basePerm, "error.no-permission")
                && requirePermission(sender, basePerm + ".others", "error.no-permission");
    }

    private boolean isWildcard(String spec) {
        return spec != null && spec.contains("*");
    }

    private boolean hasCondition(String spec) {
        return spec != null && spec.contains(":");
    }

    private Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        sb.append('^');
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*': sb.append(".*"); break;
                case '.': sb.append("\\."); break;
                case '?': sb.append('.'); break;
                case '+': case '(': case ')': case '[': case ']': case '{': case '}': case '^': case '$': case '|': case '\\':
                    sb.append('\\').append(c); break;
                default: sb.append(c);
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static class ValueCondition {
        enum Op { GT, GE, LT, LE, EQ, NE }
        final Op op; final double threshold;
        ValueCondition(Op op, double threshold) { this.op = op; this.threshold = threshold; }
    }

    private Optional<ValueCondition> parseCondition(String spec) {
        int idx = spec.lastIndexOf(':');
        if (idx < 0) return Optional.empty();
        String cond = spec.substring(idx + 1).trim();
        try {
            if (cond.startsWith(">=")) return Optional.of(new ValueCondition(ValueCondition.Op.GE, Double.parseDouble(cond.substring(2))));
            if (cond.startsWith("<=")) return Optional.of(new ValueCondition(ValueCondition.Op.LE, Double.parseDouble(cond.substring(2))));
            if (cond.startsWith("==")) return Optional.of(new ValueCondition(ValueCondition.Op.EQ, Double.parseDouble(cond.substring(2))));
            if (cond.startsWith("!=")) return Optional.of(new ValueCondition(ValueCondition.Op.NE, Double.parseDouble(cond.substring(2))));
            if (cond.startsWith(">"))  return Optional.of(new ValueCondition(ValueCondition.Op.GT, Double.parseDouble(cond.substring(1))));
            if (cond.startsWith("<"))  return Optional.of(new ValueCondition(ValueCondition.Op.LT, Double.parseDouble(cond.substring(1))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private String extractGlob(String spec) {
        int idx = spec.lastIndexOf(':');
        return idx < 0 ? spec : spec.substring(0, idx);
    }

    private boolean matchCondition(String value, Optional<ValueCondition> condition) {
        if (condition.isEmpty()) return true;
        try {
            double v = Double.parseDouble(value);
            ValueCondition c = condition.get();
            switch (c.op) {
                case GT: return v > c.threshold;
                case GE: return v >= c.threshold;
                case LT: return v < c.threshold;
                case LE: return v <= c.threshold;
                case EQ: return v == c.threshold;
                case NE: return v != c.threshold;
                default: return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private int parseLimit(String[] args, int startIndex) {
        int limit = DEFAULT_BULK_LIMIT;
        for (int i = startIndex; i < args.length; i++) {
            if ("--limit".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                try {
                    limit = Math.min(Integer.parseInt(args[i + 1]), MAX_BULK_LIMIT);
                } catch (NumberFormatException ignored) {}
            }
        }
        return limit;
    }

    private boolean parseDryRun(String[] args, int startIndex) {
        for (int i = startIndex; i < args.length; i++) {
            if ("-n".equalsIgnoreCase(args[i]) || "--dry-run".equalsIgnoreCase(args[i])) return true;
        }
        return false;
    }

    private boolean hasFlag(String[] args, int startIndex, String flag) {
        for (int i = startIndex; i < args.length; i++) {
            if (flag.equalsIgnoreCase(args[i])) return true;
        }
        return false;
    }

    private Optional<String> parseOutFile(String[] args, int startIndex) {
        for (int i = startIndex; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--out:")) {
                String v = a.substring("--out:".length()).trim();
                if (!v.isEmpty()) return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    /**
     * 将批量查询结果导出为 YML 文件（保存到插件数据目录 exports/ 下）
     */
    private File exportPlayerQueryToYaml(String suggestedName,
                                         String glob,
                                         Optional<ValueCondition> cond,
                                         Map<String, Map<String, String>> uuidToVars,
                                         Map<String, String> uuidToName,
                                         int totalMatches) throws IOException {
        String fileName = suggestedName == null ? "player_query.yml" : suggestedName;
        if (!fileName.toLowerCase().endsWith(".yml")) fileName = fileName + ".yml";
        fileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        File dir = new File(plugin.getDataFolder(), "exports");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, fileName);

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("summary.variable_pattern", glob);
        yaml.set("summary.condition", cond.map(c -> c.op + ":" + c.threshold).orElse(""));
        yaml.set("summary.total_matches", totalMatches);
        yaml.set("summary.unique_players", uuidToVars.size());

        for (Map.Entry<String, Map<String, String>> e : uuidToVars.entrySet()) {
            String uuid = e.getKey();
            String name = uuidToName.getOrDefault(uuid, "");
            String base = "players." + uuid;
            if (!name.isEmpty()) yaml.set(base + ".name", name);
            String varsBase = base + ".variables";
            for (Map.Entry<String, String> kv : e.getValue().entrySet()) {
                yaml.set(varsBase + "." + kv.getKey(), kv.getValue());
            }
        }
        yaml.save(out);
        return out;
    }

    /**
     * Dry-run 计算“新值”的预览逻辑（不影响原值）
     */
    private String computeNewValuePreview(String action, String oldVal, String[] args) {
        try {
            switch (action) {
                case "set":    return args.length >= 5 ? args[4] : oldVal;
                case "add":    return args.length >= 5 ? String.valueOf(Double.parseDouble(oldVal) + Double.parseDouble(args[4])) : oldVal;
                case "remove": return args.length >= 5 ? String.valueOf(Double.parseDouble(oldVal) - Double.parseDouble(args[4])) : oldVal;
                case "reset":  return "";
                default:       return oldVal;
            }
        } catch (Exception e) {
            return oldVal;
        }
    }

    // ----------------- 私有函数式接口 -----------------

    /**
     * 玩家变量写操作函数式接口
     */
    @FunctionalInterface
    private interface PlayerVarOp {
        CompletionStage<VariableResult> apply(OfflinePlayer target, String key, String value);
    }

    /**
     * 构造消息占位参数函数式接口
     * targetName/key/value/resultValue -> params
     */
    @FunctionalInterface
    private interface ParamBuilder {
        Map<String, String> build(String targetName, String key, String value, String resultValue);
    }
}
