package cn.drcomo;

import cn.drcomo.bulk.PlayerBulkCollector;
import cn.drcomo.bulk.PlayerBulkHelper;
import cn.drcomo.bulk.PlayerBulkRequest;
import cn.drcomo.bulk.PlayerBulkResult;
import cn.drcomo.bulk.PlayerFilter;
import cn.drcomo.bulk.PlayerVariableCandidate;
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
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
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
    private static final List<String> VAR_OPS = List.of("get", "set", "add", "give", "remove", "reset");
    // 插件核心引用
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final MessagesManager messagesManager;
    private final RefactoredVariablesManager variablesManager;
    private final ServerVariablesManager serverVariablesManager;
    private final PlayerVariablesManager playerVariablesManager;
    private final PlayerBulkCollector playerBulkCollector;

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
        this.playerBulkCollector = new PlayerBulkCollector(
                plugin,
                logger,
                variablesManager,
                playerVariablesManager,
                DB_QUERY_TIMEOUT_SECONDS,
                MAX_PREVIEW_EXAMPLES
        );
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
            case "give":   handlePlayerGive(sender, args);   break;
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

    /** /vex player give <玩家名|UUID> <变量名> <值> [--offline] */
    private void handlePlayerGive(CommandSender sender, String[] args) {
        // give 作为 add 的语义别名，完全复用 add 流程，确保单人与批量逻辑保持一致
        handlePlayerAdd(sender, args);
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
        String spec = args[3];
        if (PlayerBulkHelper.isWildcard(spec) || PlayerBulkHelper.hasCondition(spec)) {
            handlePlayerSinglePatternReset(sender, args, allowOffline);
            return;
        }
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
        if (PlayerBulkHelper.isWildcard(spec) || PlayerBulkHelper.hasCondition(spec)) {
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
        if (PlayerBulkHelper.isWildcard(spec)) {
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
        if (PlayerBulkHelper.isWildcard(spec)) {
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
        if (PlayerBulkHelper.isWildcard(spec)) {
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
        if (PlayerBulkHelper.isWildcard(spec)) {
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
        boolean dbOnly = hasFlag(args, 4, "--db-only");
        boolean onlineOnly = hasFlag(args, 4, "--online-only");
        boolean includeDatabase = !onlineOnly;
        boolean includeOnline = !dbOnly;
        Optional<String> outOpt = parseOutFile(args, 4);
        PlayerFilter playerFilter = parsePlayerFilter(args, 4);

        Optional<PlayerBulkHelper.ValueCondition> condition = PlayerBulkHelper.parseCondition(spec);
        String glob = PlayerBulkHelper.extractGlob(spec);

        PlayerBulkRequest request = new PlayerBulkRequest(
                spec,
                includeDatabase,
                includeOnline,
                MAX_PREVIEW_EXAMPLES,
                MAX_BULK_LIMIT,
                playerFilter
        );

        playerBulkCollector.collect(request)
                .thenAccept(result -> {
                    if (result.getTotalMatches() == 0) {
                        messagesManager.sendMessage(sender, "error.no-candidates", EMPTY_PARAMS);
                        return;
                    }
                    if (result.isDatabaseTimeout()) {
                        logger.warn("批量查询时数据库阶段存在超时或异常，结果可能不完整");
                    }
                    if (result.isOnlineTimeout()) {
                        logger.warn("批量查询时在线玩家扫描存在超时或异常，结果可能不完整");
                    }

                    messagesManager.sendMessage(sender, "success.player-query-summary",
                            Map.of("count", String.valueOf(result.getTotalMatches())));
                    for (String line : result.getPreviews()) {
                        messagesManager.sendMessage(sender, "info.player-query-item", Map.of("player", "", "value", line));
                    }

                    outOpt.ifPresent(name -> {
                        try {
                            File outFile = exportPlayerQueryToYaml(name, glob, condition,
                                    result.getUuidToVariables(), result.getUuidToName(), result.getTotalMatches());
                            messagesManager.sendMessage(sender, "success.player-query-saved",
                                    Map.of("file", "exports/" + outFile.getName(),
                                           "players", String.valueOf(result.getUuidToVariables().size()),
                                           "matches", String.valueOf(result.getTotalMatches())));
                        } catch (Exception ex) {
                            logger.error("导出查询结果到 YML 失败", ex);
                            messagesManager.sendMessage(sender, "error.export-failed",
                                    Map.of("reason", ex.getMessage() != null ? ex.getMessage() : "未知错误"));
                        }
                    });
                })
                .exceptionally(t -> handleException("批量查询玩家变量失败", t, sender));
    }

    // ----- 玩家 批量写入(set/add/remove/reset) -----
    private void handlePlayerBatchWrite(CommandSender sender, String[] args, String action) {
        // 语法：/vex player <action> * <变量通配> [<值或增量>] [-n] [--limit N]
        String normalizedAction = normalizePlayerAction(action);
        String spec = args[3];

        boolean requiresValue = !"reset".equalsIgnoreCase(normalizedAction);
        if (requiresValue && args.length < 5) {
            messagesManager.sendMessage(sender, "error.invalid-number", Map.of("value", ""));
            return;
        }
        String valueArg = requiresValue ? args[4] : null;
        int flagStart = requiresValue ? 5 : 4;

        boolean dryRun = parseDryRun(args, flagStart);
        int limit = parseLimit(args, flagStart);
        boolean dbOnly = hasFlag(args, flagStart, "--db-only");
        boolean onlineOnly = hasFlag(args, flagStart, "--online-only");
        boolean includeDatabase = !onlineOnly;
        boolean includeOnline = !dbOnly;

        String basePerm = "drcomovex.command.player." + normalizedAction;
        if (!requireBulkOthersPermission(sender, basePerm)) return;

        Optional<PlayerBulkHelper.ValueCondition> condition = PlayerBulkHelper.parseCondition(spec);
        String glob = PlayerBulkHelper.extractGlob(spec);

        PlayerFilter playerFilter = parsePlayerFilter(args, flagStart);

        PlayerBulkRequest request = new PlayerBulkRequest(
                spec,
                includeDatabase,
                includeOnline,
                MAX_PREVIEW_EXAMPLES,
                MAX_BULK_LIMIT,
                playerFilter
        );

        playerBulkCollector.collect(request).thenAccept(result -> {
            if (result.getTotalMatches() == 0 || result.getCandidates().isEmpty()) {
                messagesManager.sendMessage(sender, "error.no-candidates", EMPTY_PARAMS);
                return;
            }
            if (result.isDatabaseTimeout()) {
                logger.warn("批量写入前的数据库扫描存在超时或异常，候选集可能不完整");
            }
            if (result.isOnlineTimeout()) {
                logger.warn("批量写入前的在线扫描存在超时或异常，候选集可能不完整");
            }

            int affected = Math.min(limit, result.getCandidates().size());
            if (affected <= 0) {
                messagesManager.sendMessage(sender, "error.no-candidates", EMPTY_PARAMS);
                return;
            }

            if (dryRun) {
                messagesManager.sendMessage(sender, "success.player-bulk-dryrun",
                        Map.of("count", String.valueOf(Math.min(result.getTotalMatches(), affected)),
                               "action", action,
                               "variable", glob));
                int preview = Math.min(affected, MAX_PREVIEW_EXAMPLES);
                for (int i = 0; i < preview; i++) {
                    PlayerVariableCandidate candidate = result.getCandidates().get(i);
                    String oldValue = candidate.getCurrentValue() != null ? candidate.getCurrentValue() : "";
                    String newVal = computeNewValuePreview(normalizedAction, oldValue, args);
                    messagesManager.sendMessage(sender, "info.player-bulk-preview-item",
                            Map.of("player", resolveCandidateName(result, candidate),
                                   "old", oldValue,
                                   "new", newVal));
                }
                return;
            }

            List<CompletableFuture<VariableResult>> writeFutures = new ArrayList<>();
            for (int i = 0; i < affected; i++) {
                PlayerVariableCandidate candidate = result.getCandidates().get(i);
                OfflinePlayer target = Bukkit.getOfflinePlayer(candidate.getPlayerId());
                if (target == null) {
                    continue;
                }
                CompletableFuture<VariableResult> future;
                switch (normalizedAction) {
                    case "set":
                        future = playerVariablesManager.setPlayerVariable(target, candidate.getVariableKey(), valueArg);
                        break;
                    case "add":
                        future = playerVariablesManager.addPlayerVariable(target, candidate.getVariableKey(), valueArg);
                        break;
                    case "remove":
                        future = playerVariablesManager.removePlayerVariable(target, candidate.getVariableKey(), valueArg);
                        break;
                    case "reset":
                        future = playerVariablesManager.resetPlayerVariable(target, candidate.getVariableKey());
                        break;
                    default:
                        continue;
                }
                writeFutures.add(future);
            }

            if (writeFutures.isEmpty()) {
                messagesManager.sendMessage(sender, "error.no-candidates", EMPTY_PARAMS);
                return;
            }

            CompletableFuture.allOf(writeFutures.toArray(new CompletableFuture[0])).whenComplete((ignored, throwable) -> {
                int success = 0;
                int failed = 0;
                for (CompletableFuture<VariableResult> future : writeFutures) {
                    try {
                        VariableResult r = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        if (r != null && r.isSuccess()) {
                            success++;
                        } else {
                            failed++;
                        }
                    } catch (Exception e) {
                        failed++;
                    }
                }
                messagesManager.sendMessage(sender, "success.player-bulk-done",
                        Map.of("success", String.valueOf(success),
                               "failed", String.valueOf(failed),
                               "variable", glob));
            });
        }).exceptionally(t -> handleException("批量写入玩家变量失败", t, sender));
    }

    // ----- 全局 批量查询 -----
    private void handleGlobalBatchGet(CommandSender sender, String[] args) {
        String spec = args[2];
        Optional<PlayerBulkHelper.ValueCondition> cond = PlayerBulkHelper.parseCondition(spec);
        String glob = PlayerBulkHelper.extractGlob(spec);
        Pattern regex = PlayerBulkHelper.globToRegex(glob);

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
                if (r != null && r.isSuccess() && r.getValue() != null
                        && PlayerBulkHelper.matchCondition(r.getValue(), cond)) {
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

        Pattern regex = PlayerBulkHelper.globToRegex(glob);
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

    /**
     * 单玩家的通配重置流程：允许通过通配符批量重置指定玩家的多个变量。
     */
    private void handlePlayerSinglePatternReset(CommandSender sender, String[] args, boolean allowOffline) {
        OfflinePlayer target = resolvePlayer(sender, args[2], allowOffline);
        if (target == null) {
            return;
        }

        if (!checkBasePermission(sender, "drcomovex.command.player.reset", target)) {
            messagesManager.sendMessage(sender, "error.no-permission", EMPTY_PARAMS);
            return;
        }

        String spec = args[3];
        int flagStart = 4;
        boolean dryRun = parseDryRun(args, flagStart);
        int limit = parseLimit(args, flagStart);
        boolean dbOnly = hasFlag(args, flagStart, "--db-only");
        boolean onlineOnly = hasFlag(args, flagStart, "--online-only");
        boolean includeDatabase = !onlineOnly;
        boolean includeOnline = !dbOnly;

        Set<UUID> uuidSet = Collections.singleton(target.getUniqueId());
        Set<String> nameSet = (target.getName() != null && !target.getName().isEmpty())
                ? Collections.singleton(target.getName().toLowerCase())
                : Collections.emptySet();
        PlayerFilter filter = PlayerFilter.of(uuidSet, nameSet);

        PlayerBulkRequest request = new PlayerBulkRequest(
                spec,
                includeDatabase,
                includeOnline,
                MAX_PREVIEW_EXAMPLES,
                Math.min(limit, MAX_BULK_LIMIT),
                filter
        );

        playerBulkCollector.collect(request).thenAccept(result -> {
            List<PlayerVariableCandidate> candidates = result.getCandidates().stream()
                    .filter(c -> target.getUniqueId().equals(c.getPlayerId()))
                    .collect(Collectors.toList());
            if (candidates.isEmpty()) {
                messagesManager.sendMessage(sender, "error.no-candidates", EMPTY_PARAMS);
                return;
            }
            if (result.isDatabaseTimeout()) {
                logger.warn("批量重置时数据库阶段存在超时或异常，结果可能不完整");
            }
            if (result.isOnlineTimeout()) {
                logger.warn("批量重置时在线扫描存在超时或异常，结果可能不完整");
            }

            int affected = Math.min(limit, candidates.size());
            if (affected <= 0) {
                messagesManager.sendMessage(sender, "error.no-candidates", EMPTY_PARAMS);
                return;
            }

            if (dryRun) {
                messagesManager.sendMessage(sender, "success.player-bulk-dryrun",
                        Map.of("count", String.valueOf(affected),
                               "action", "reset",
                               "variable", spec));
                int preview = Math.min(affected, MAX_PREVIEW_EXAMPLES);
                for (int i = 0; i < preview; i++) {
                    PlayerVariableCandidate candidate = candidates.get(i);
                    String oldVal = candidate.getCurrentValue() != null ? candidate.getCurrentValue() : "";
                    messagesManager.sendMessage(sender, "info.player-bulk-preview-item",
                            Map.of("player", resolveCandidateName(result, candidate),
                                   "old", oldVal,
                                   "new", ""));
                }
                return;
            }

            List<CompletableFuture<VariableResult>> futures = new ArrayList<>();
            for (int i = 0; i < affected; i++) {
                PlayerVariableCandidate candidate = candidates.get(i);
                futures.add(playerVariablesManager.resetPlayerVariable(target, candidate.getVariableKey()));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((ignored, throwable) -> {
                int success = 0;
                int failed = 0;
                for (CompletableFuture<VariableResult> future : futures) {
                    try {
                        VariableResult r = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        if (r != null && r.isSuccess()) {
                            success++;
                        } else {
                            failed++;
                        }
                    } catch (Exception e) {
                        failed++;
                    }
                }
                messagesManager.sendMessage(sender, "success.player-bulk-done",
                        Map.of("success", String.valueOf(success),
                               "failed", String.valueOf(failed),
                               "variable", spec));
            });
        }).exceptionally(t -> handleException("单玩家批量重置失败", t, sender));
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

    private String normalizePlayerAction(String action) {
        if (action == null) {
            return "";
        }
        String lower = action.toLowerCase();
        if ("give".equals(lower)) {
            return "add";
        }
        return lower;
    }

    private String resolveCandidateName(PlayerBulkResult result, PlayerVariableCandidate candidate) {
        String uuid = candidate.getPlayerId().toString();
        String fromResult = result.getUuidToName().get(uuid);
        if (fromResult != null && !fromResult.isEmpty()) {
            return fromResult;
        }
        String candidateName = candidate.getPlayerName();
        if (candidateName != null && !candidateName.isEmpty()) {
            return candidateName;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(candidate.getPlayerId());
        if (offline != null && offline.getName() != null) {
            return offline.getName();
        }
        return uuid;
    }

    private PlayerFilter parsePlayerFilter(String[] args, int startIndex) {
        Set<String> raw = new HashSet<>();
        for (int i = startIndex; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                continue;
            }
            if (arg.startsWith("--player:")) {
                collectFilterValues(raw, arg.substring("--player:".length()));
            } else if (arg.startsWith("--players:")) {
                collectFilterValues(raw, arg.substring("--players:".length()));
            } else if (arg.startsWith("--target:")) {
                collectFilterValues(raw, arg.substring("--target:".length()));
            } else if ("--player".equalsIgnoreCase(arg) || "--players".equalsIgnoreCase(arg) || "--target".equalsIgnoreCase(arg)) {
                if (i + 1 < args.length) {
                    collectFilterValues(raw, args[i + 1]);
                    i++;
                }
            }
        }
        return PlayerFilter.fromRawValues(raw);
    }

    private void collectFilterValues(Set<String> raw, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        String[] pieces = part.split(",");
        for (String piece : pieces) {
            if (piece != null && !piece.isBlank()) {
                raw.add(piece.trim());
            }
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
                                         Optional<PlayerBulkHelper.ValueCondition> cond,
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
