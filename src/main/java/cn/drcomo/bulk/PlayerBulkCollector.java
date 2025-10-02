package cn.drcomo.bulk;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.managers.PlayerVariablesManager;
import cn.drcomo.managers.RefactoredVariablesManager;
import cn.drcomo.model.VariableResult;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 玩家批量匹配收集器。
 *
 * 将数据库查询与在线内存扫描统一封装，输出候选列表及导出信息，便于批量查询与写操作直接复用。
 */
public class PlayerBulkCollector {

    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final RefactoredVariablesManager variablesManager;
    private final PlayerVariablesManager playerVariablesManager;
    private final long dbQueryTimeoutSeconds;
    private final int maxPreviewExamples;

    public PlayerBulkCollector(DrcomoVEX plugin,
                               DebugUtil logger,
                               RefactoredVariablesManager variablesManager,
                               PlayerVariablesManager playerVariablesManager,
                               long dbQueryTimeoutSeconds,
                               int maxPreviewExamples) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.variablesManager = Objects.requireNonNull(variablesManager, "variablesManager");
        this.playerVariablesManager = Objects.requireNonNull(playerVariablesManager, "playerVariablesManager");
        this.dbQueryTimeoutSeconds = dbQueryTimeoutSeconds;
        this.maxPreviewExamples = maxPreviewExamples;
    }

    /**
     * 执行批量收集。
     *
     * @param request 请求参数
     * @return 异步返回匹配结果
     */
    public CompletableFuture<PlayerBulkResult> collect(PlayerBulkRequest request) {
        Objects.requireNonNull(request, "request");

        Optional<PlayerBulkHelper.ValueCondition> condition = PlayerBulkHelper.parseCondition(request.getRawSpec());
        String glob = PlayerBulkHelper.extractGlob(request.getRawSpec());
        Pattern regex = PlayerBulkHelper.globToRegex(glob);

        List<String> matchedKeys = variablesManager.getPlayerVariableKeys().stream()
                .filter(k -> regex.matcher(k).matches())
                .collect(Collectors.toList());
        if (matchedKeys.isEmpty()) {
            return CompletableFuture.completedFuture(new PlayerBulkResult(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    0,
                    false,
                    false));
        }

        Set<String> seen = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger total = new AtomicInteger();
        List<String> previews = Collections.synchronizedList(new ArrayList<>());
        Map<String, Map<String, String>> uuidToVars = Collections.synchronizedMap(new HashMap<>());
        Map<String, String> uuidToName = Collections.synchronizedMap(new HashMap<>());
        List<PlayerVariableCandidate> candidates = Collections.synchronizedList(new ArrayList<>());

        AtomicBoolean dbTimeout = new AtomicBoolean(false);
        AtomicBoolean onlineTimeout = new AtomicBoolean(false);

        List<CompletableFuture<Void>> tasks = new ArrayList<>();

        PlayerFilter filter = request.getPlayerFilter();

        if (request.isIncludeDatabase()) {
            boolean wildcard = PlayerBulkHelper.isWildcard(glob);
            String like = glob
                    .replace("\\", "\\\\")
                    .replace("_", "\\_")
                    .replace("%", "\\%");
            like = like.replace('*', '%');
            String sqlKey = wildcard ? like : glob;

            CompletableFuture<Void> dbFuture = plugin.getDatabase().queryPlayerVariablesByKeyAsync(sqlKey, wildcard)
                    .thenAccept(rows -> {
                        if (rows == null) {
                            return;
                        }
                        for (String[] row : rows) {
                            if (row == null || row.length < 3) {
                                continue;
                            }
                            String playerId = row[0];
                            String key = row[1];
                            String value = row[2];
                            if (playerId == null || key == null || value == null) {
                                continue;
                            }
                            UUID uuid;
                            try {
                                uuid = UUID.fromString(playerId);
                            } catch (IllegalArgumentException e) {
                                uuid = null;
                            }
                            String resolvedName = resolvePlayerNameSafely(playerId);
                            if (!filter.matches(uuid, resolvedName)) {
                                continue;
                            }
                            String actualValue = PlayerBulkHelper.normalizeStoredValue(value);
                            if (!PlayerBulkHelper.matchCondition(actualValue, condition)) {
                                continue;
                            }
                            String uniq = playerId + "::" + key;
                            if (seen.add(uniq)) {
                                total.incrementAndGet();
                                if (previews.size() < Math.min(request.getPreviewLimit(), maxPreviewExamples)) {
                                    previews.add("玩家UUID=" + playerId + " 变量=" + key + " 值=" + actualValue);
                                }
                                uuidToVars.computeIfAbsent(playerId, k -> Collections.synchronizedMap(new HashMap<>()))
                                        .put(key, actualValue);
                                String finalName = resolvedName == null ? "" : resolvedName;
                                uuidToName.computeIfAbsent(playerId, id -> finalName);
                                if (candidates.size() < request.getCandidateLimit()) {
                                    if (uuid != null) {
                                        candidates.add(new PlayerVariableCandidate(uuid,
                                                uuidToName.get(playerId),
                                                key,
                                                actualValue,
                                                PlayerVariableCandidate.Source.DATABASE));
                                    }
                                }
                            }
                        }
                    })
                    .orTimeout(dbQueryTimeoutSeconds, TimeUnit.SECONDS)
                    .exceptionally(t -> {
                        dbTimeout.set(true);
                        logger.warn("数据库批量查询超时或失败: " + (t != null ? t.getMessage() : "未知错误"));
                        return null;
                    });
            tasks.add(dbFuture);
        }

        if (request.isIncludeOnline()) {
            List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
            if (!filter.isEmpty()) {
                onlinePlayers.removeIf(player -> !filter.matches(player.getUniqueId(), player.getName()));
            }
            if (!onlinePlayers.isEmpty()) {
                List<CompletableFuture<VariableResult>> valueFutures = new ArrayList<>();
                for (Player player : onlinePlayers) {
                    for (String key : matchedKeys) {
                        String uniq = player.getUniqueId() + "::" + key;
                        if (seen.contains(uniq)) {
                            continue;
                        }
                        CompletableFuture<VariableResult> future = playerVariablesManager.getPlayerVariable(player, key)
                                .thenApply(result -> {
                                    if (result != null && result.isSuccess() && result.getValue() != null) {
                                        String actualValue = PlayerBulkHelper.normalizeStoredValue(result.getValue());
                                        if (PlayerBulkHelper.matchCondition(actualValue, condition) && seen.add(uniq)) {
                                            total.incrementAndGet();
                                            if (previews.size() < Math.min(request.getPreviewLimit(), maxPreviewExamples)) {
                                                previews.add("玩家=" + player.getName() + " 变量=" + key + " 值=" + actualValue);
                                            }
                                            String uuid = player.getUniqueId().toString();
                                            uuidToVars.computeIfAbsent(uuid, k -> Collections.synchronizedMap(new HashMap<>()))
                                                    .put(key, actualValue);
                                            uuidToName.putIfAbsent(uuid, player.getName());
                                            if (candidates.size() < request.getCandidateLimit()) {
                                                candidates.add(new PlayerVariableCandidate(player.getUniqueId(),
                                                        player.getName(),
                                                        key,
                                                        actualValue,
                                                        PlayerVariableCandidate.Source.ONLINE));
                                            }
                                        }
                                    }
                                    return result;
                                });
                        valueFutures.add(future);
                    }
                }
                if (!valueFutures.isEmpty()) {
                    CompletableFuture<Void> onlineFuture = CompletableFuture.allOf(valueFutures.toArray(new CompletableFuture[0]))
                            .orTimeout(dbQueryTimeoutSeconds, TimeUnit.SECONDS)
                            .exceptionally(t -> {
                                onlineTimeout.set(true);
                                logger.warn("在线玩家内存扫描超时或失败: " + (t != null ? t.getMessage() : "未知错误"));
                                return null;
                            });
                    tasks.add(onlineFuture);
                }
            }
        }

        CompletableFuture<Void> combined;
        if (tasks.isEmpty()) {
            combined = CompletableFuture.completedFuture(null);
        } else {
            combined = CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]));
        }

        return combined.handle((ignored, throwable) -> {
            if (throwable != null) {
                logger.warn("批量收集过程中出现异常: " + throwable.getMessage());
            }
            return new PlayerBulkResult(
                    new ArrayList<>(candidates),
                    new ArrayList<>(previews),
                    new HashMap<>(uuidToVars),
                    new HashMap<>(uuidToName),
                    total.get(),
                    dbTimeout.get(),
                    onlineTimeout.get());
        });
    }

    private String resolvePlayerNameSafely(String uuidStr) {
        try {
            UUID uuid = UUID.fromString(uuidStr);
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            if (offlinePlayer != null && offlinePlayer.getName() != null) {
                return offlinePlayer.getName();
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
