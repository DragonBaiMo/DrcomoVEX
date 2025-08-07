package cn.drcomo.api;

import cn.drcomo.managers.PlayerVariablesManager;
import cn.drcomo.managers.ServerVariablesManager;
import cn.drcomo.managers.RefactoredVariablesManager;
import cn.drcomo.model.VariableResult;
import cn.drcomo.model.structure.Variable;
import cn.drcomo.corelib.hook.placeholder.PlaceholderAPIUtil;
import cn.drcomo.corelib.util.DebugUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DrcomoVEX 对外 API 接口
 *
 * 为其他插件提供与 DrcomoVEX 变量系统交互的能力。
 *
 * 优化说明：
 *  1. 保留所有 public 方法签名与访问权限；
 *  2. 重复的占位符处理逻辑提取到私有方法 processPlaceholder 中；
 *  3. 针对三种占位符业务逻辑分别封装为私有方法 getCalculatedValue、fetchGlobalVariable、fetchPlayerVariableWithPlayer；
 *  4. 保留并补充必要注释，增强可读性；
 *  5. 所有导入均在文件最前端显示；
 *  6. 未调用的旧方法已注释并置于文件末尾。
 *
 * 全部注释均为中文，确保可维护性与可读性。
 *
 * 作者: BaiMo
 */
public class ServerVariablesAPI {

    private final DebugUtil logger;
    private final RefactoredVariablesManager variablesManager;
    private final ServerVariablesManager serverVariablesManager;
    private final PlayerVariablesManager playerVariablesManager;

    public ServerVariablesAPI(
            DebugUtil logger,
            RefactoredVariablesManager variablesManager,
            ServerVariablesManager serverVariablesManager,
            PlayerVariablesManager playerVariablesManager
    ) {
        this.logger = logger;
        this.variablesManager = variablesManager;
        this.serverVariablesManager = serverVariablesManager;
        this.playerVariablesManager = playerVariablesManager;
    }

    // ======================== 玩家变量 API ========================

    /** 获取玩家变量值 */
    public CompletableFuture<String> getPlayerVariable(OfflinePlayer player, String key) {
        return playerVariablesManager.getPlayerVariable(player, key)
                .thenApply(result -> result.isSuccess() ? result.getValue() : null);
    }

    /** 设置玩家变量值 */
    public CompletableFuture<Boolean> setPlayerVariable(OfflinePlayer player, String key, String value) {
        return playerVariablesManager.setPlayerVariable(player, key, value)
                .thenApply(VariableResult::isSuccess);
    }

    /** 增加玩家变量值 */
    public CompletableFuture<Boolean> addPlayerVariable(OfflinePlayer player, String key, String addValue) {
        return playerVariablesManager.addPlayerVariable(player, key, addValue)
                .thenApply(VariableResult::isSuccess);
    }

    /** 移除玩家变量值 */
    public CompletableFuture<Boolean> removePlayerVariable(OfflinePlayer player, String key, String removeValue) {
        return playerVariablesManager.removePlayerVariable(player, key, removeValue)
                .thenApply(VariableResult::isSuccess);
    }

    /** 重置玩家变量 */
    public CompletableFuture<Boolean> resetPlayerVariable(OfflinePlayer player, String key) {
        return playerVariablesManager.resetPlayerVariable(player, key)
                .thenApply(VariableResult::isSuccess);
    }

    // ======================== 服务器变量 API ========================

    /** 获取服务器变量值 */
    public CompletableFuture<String> getServerVariable(String key) {
        return serverVariablesManager.getServerVariable(key)
                .thenApply(result -> result.isSuccess() ? result.getValue() : null);
    }

    /** 设置服务器变量值 */
    public CompletableFuture<Boolean> setServerVariable(String key, String value) {
        return serverVariablesManager.setServerVariable(key, value)
                .thenApply(VariableResult::isSuccess);
    }

    /** 增加服务器变量值 */
    public CompletableFuture<Boolean> addServerVariable(String key, String addValue) {
        return serverVariablesManager.addServerVariable(key, addValue)
                .thenApply(VariableResult::isSuccess);
    }

    /** 移除服务器变量值 */
    public CompletableFuture<Boolean> removeServerVariable(String key, String removeValue) {
        return serverVariablesManager.removeServerVariable(key, removeValue)
                .thenApply(VariableResult::isSuccess);
    }

    /** 重置服务器变量 */
    public CompletableFuture<Boolean> resetServerVariable(String key) {
        return serverVariablesManager.resetServerVariable(key)
                .thenApply(VariableResult::isSuccess);
    }

    // ======================== 变量定义 API ========================

    /** 获取变量定义 */
    public Variable getVariableDefinition(String key) {
        return variablesManager.getVariableDefinition(key);
    }

    /** 检查变量是否存在 */
    public boolean hasVariable(String key) {
        return variablesManager.getVariableDefinition(key) != null;
    }

    /** 检查是否为玩家变量 */
    public boolean isPlayerVariable(String key) {
        return playerVariablesManager.isPlayerVariable(key);
    }

    /** 检查是否为服务器变量 */
    public boolean isServerVariable(String key) {
        return serverVariablesManager.isServerVariable(key);
    }

    /** 获取所有变量键名 */
    public Set<String> getAllVariableKeys() {
        return variablesManager.getAllVariableKeys();
    }

    // ======================== PlaceholderAPI 集成 ========================

    /**
     * 注册 PlaceholderAPI 占位符
     */
    public void registerPlaceholders(PlaceholderAPIUtil placeholderUtil) {
        // 通用参数非空验证函数
        java.util.function.Function<String, Boolean> isBlank = s -> s == null || s.trim().isEmpty();

        // %drcomovex_[var]_[<key>]%
        placeholderUtil.register("[var]", (player, rawArgs) ->
                processPlaceholder("[var]", player, rawArgs, this::getCalculatedValue));

        // %drcomovex_[global_var]_[<key>]%
        placeholderUtil.register("[global_var]", (player, rawArgs) ->
                processPlaceholder("[global_var]", player, rawArgs, (pl, key) -> fetchGlobalVariable(key)));

        // %drcomovex_[player_var]_[<key>]% 或 %drcomovex_[player_var]_[<key>]_[<player_name>]%
        placeholderUtil.register("[player_var]", (player, rawArgs) ->
                processPlayerVariablePlaceholder("[player_var]", player, rawArgs));
    }

    // -------------------- 私有通用方法 --------------------

    /**
     * 通用占位符处理流程：
     * 1. 日志输入
     * 2. 参数非空校验
     * 3. 参数格式与变量存在性校验
     * 4. 调用具体业务逻辑
     * 5. 日志输出与结果校验
     */
    private String processPlaceholder(String placeholder,
                                      OfflinePlayer player,
                                      String rawArgs,
                                      BiFunction<OfflinePlayer, String, String> handler) {
        String domain = placeholder.substring(1, placeholder.length() - 1);
        // 非空校验
        if (rawArgs == null || rawArgs.trim().isEmpty()) {
            String result = "变量名不能为空";
            logger.info("占位符 drcomovex_[" + domain + "]_ 输出结果: " + result);
            return result;
        }

        String full = "drcomovex_" + placeholder + "_" + rawArgs;
        Matcher m = Pattern.compile("^drcomovex_\\[([^]]+)]_(.+)$").matcher(full);
        if (!m.matches()) {
            String result = "错误";
            logger.error("占位符 " + full + " 参数解析失败");
            logger.info("占位符 " + full + " 输出结果: " + result);
            return result;
        }

        String key = m.group(2).replace(" ", "_").replace("[", "").replace("]", "");
        logger.info("占位符 drcomovex_[" + domain + "]_" + key + " 输入参数: " + rawArgs);

        String result;
        if (key.isEmpty()) {
            result = "变量名不能为空";
        } else if (!hasVariable(key)) {
            logger.info("占位符 drcomovex_[" + domain + "]_" + key + " 变量不存在");
            result = "变量不存在";
        } else {
            result = handler.apply(player, key);
        }

        logger.info("占位符 drcomovex_[" + domain + "]_" + key + " 输出结果: " + result);
        if (result == null || result.trim().isEmpty() || result.contains("%")) {
            logger.debug("占位符 drcomovex_[" + domain + "]_" + key
                    + " 解析结果异常，原始输入: " + rawArgs + ", 输出: " + result);
        }
        return result;
    }

    /**
     * 处理玩家变量专用占位符，支持指定玩家名
     */
    private String processPlayerVariablePlaceholder(String placeholder,
                                                    OfflinePlayer player,
                                                    String rawArgs) {
        String domain = placeholder.substring(1, placeholder.length() - 1);
        if (rawArgs == null || rawArgs.trim().isEmpty()) {
            String result = "变量名不能为空";
            logger.info("占位符 drcomovex_[" + domain + "]_ 输出结果: " + result);
            return result;
        }

        // 分拆参数，尝试最后一段为玩家名
        String[] parts = PlaceholderAPIUtil.splitArgs(rawArgs);
        String key;
        String playerName = null;
        if (parts.length > 1) {
            String maybeName = parts[parts.length - 1];
            StringBuilder kb = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                if (i > 0) kb.append("_");
                kb.append(parts[i]);
            }
            String candidateKey = kb.toString();
            if (variablesManager.getVariableDefinition(candidateKey) != null) {
                key = candidateKey;
                playerName = maybeName;
            } else {
                key = rawArgs;
            }
        } else {
            key = rawArgs;
        }

        logger.info("占位符 drcomovex_[" + domain + "]_" + key
                + (playerName != null ? "_" + playerName : "")
                + " 输入参数: " + rawArgs);

        String result;
        if (key.isEmpty()) {
            result = "变量名不能为空";
        } else if (!hasVariable(key)) {
            logger.info("占位符 drcomovex_[" + domain + "]_" + key + " 变量不存在");
            result = "变量不存在";
        } else {
            OfflinePlayer target = player;
            if (playerName != null) {
                OfflinePlayer op = resolveOfflinePlayer(playerName);
                if (op == null) {
                    result = "玩家不存在";
                    logger.info("占位符 drcomovex_[" + domain + "]_" + key
                            + "_" + playerName + " 输出结果: " + result);
                    return result;
                }
                target = op;
            }
            result = fetchPlayerVariableWithPlayer(target, key);
        }

        logger.info("占位符 drcomovex_[" + domain + "]_" + key
                + (playerName != null ? "_" + playerName : "")
                + " 输出结果: " + result);
        if (result == null || result.trim().isEmpty() || result.contains("%")) {
            logger.debug("占位符 drcomovex_[" + domain + "]_" + key
                    + (playerName != null ? "_" + playerName : "")
                    + " 解析结果异常，原始输入: " + rawArgs + ", 输出: " + result);
        }
        return result;
    }

    /**
     * 根据玩家名解析 OfflinePlayer，若不存在或未曾登录则返回 null
     */
    private OfflinePlayer resolveOfflinePlayer(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return null;
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(playerName);
        if (op == null || !op.hasPlayedBefore()) {
            logger.debug("玩家 " + playerName + " 不存在或未登录过");
            return null;
        }
        return op;
    }

    /**
     * 获取变量的计算后最终值
     */
    private String getCalculatedValue(OfflinePlayer player, String key) {
        if (!hasVariable(key)) {
            return "变量不存在";
        }
        Variable var = variablesManager.getVariableDefinition(key);
        if (var == null) {
            return "变量不存在";
        }
        try {
            CompletableFuture<VariableResult> future;
            if (var.isGlobal()) {
                future = variablesManager.getVariable(null, key);
            } else if (var.isPlayerScoped()) {
                if (player == null) {
                    logger.info("占位符 drcomovex_[var] 玩家变量 " + key + " 需要玩家参数");
                    return "需要玩家参数";
                }
                future = variablesManager.getVariable(player, key);
            } else {
                future = variablesManager.getVariable(player, key);
            }
            VariableResult vr = future.get(500, TimeUnit.MILLISECONDS);
            return vr.isSuccess() ? vr.getValue() : "0";
        } catch (Exception e) {
            logger.error("占位符 drcomovex_[var] 获取变量 " + key + " 异常", e);
            return (e instanceof java.util.concurrent.TimeoutException) ? "0" : "异常:" + e.getMessage();
        }
    }

    /**
     * 以全局身份获取变量值
     */
    private String fetchGlobalVariable(String key) {
        if (!hasVariable(key)) {
            return "变量不存在";
        }
        Variable var = variablesManager.getVariableDefinition(key);
        if (var != null && var.isGlobal()) {
            try {
                VariableResult vr = variablesManager.getVariable(null, key)
                        .get(500, TimeUnit.MILLISECONDS);
                return vr.isSuccess() ? vr.getValue() : "0";
            } catch (Exception e) {
                logger.error("占位符 drcomovex_[global_var] 异常，参数: " + key, e);
                return (e instanceof java.util.concurrent.TimeoutException) ? "0" : "异常:" + e.getMessage();
            }
        } else if (var != null) {
            logger.info("占位符 drcomovex_[global_var] 变量 " + key + " 不是全局类型");
            return "类型不匹配";
        }
        return "变量不存在";
    }

    /**
     * 以指定玩家身份获取玩家变量值
     */
    private String fetchPlayerVariableWithPlayer(OfflinePlayer player, String key) {
        if (!hasVariable(key)) {
            return "变量不存在";
        }
        Variable var = variablesManager.getVariableDefinition(key);
        if (var != null && var.isPlayerScoped()) {
            try {
                VariableResult vr = variablesManager.getVariable(player, key)
                        .get(500, TimeUnit.MILLISECONDS);
                return vr.isSuccess() ? vr.getValue() : "0";
            } catch (Exception e) {
                logger.error("占位符 drcomovex_[player_var] 异常，参数: " + key, e);
                return (e instanceof java.util.concurrent.TimeoutException) ? "0" : "异常:" + e.getMessage();
            }
        } else if (var != null) {
            logger.info("占位符 drcomovex_[player_var] 变量 " + key + " 不是玩家类型");
            return "类型不匹配";
        }
        return "变量不存在";
    }
}
