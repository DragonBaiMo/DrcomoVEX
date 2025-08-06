package cn.drcomo.api;

import cn.drcomo.managers.PlayerVariablesManager;
import cn.drcomo.managers.ServerVariablesManager;
import cn.drcomo.managers.RefactoredVariablesManager;
import cn.drcomo.model.VariableResult;
import cn.drcomo.model.structure.Variable;
import cn.drcomo.corelib.hook.placeholder.PlaceholderAPIUtil;
import cn.drcomo.corelib.util.DebugUtil;
import org.bukkit.OfflinePlayer;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * DrcomoVEX 对外 API 接口
 *
 * 为其他插件提供与 DrcomoVEX 变量系统交互的能力。
 *
 * 优化说明：
 *  1. 保留所有 public 方法签名与访问权限；
 *  2. 重复的占位符处理逻辑提取到私有方法 processPlaceholder 中；
 *  3. 针对三种占位符业务逻辑分别封装为私有方法 getDefaultInitial、fetchGlobalVariable、fetchPlayerVariable；
 *  4. 保留并补充必要注释，增强可读性；
 *  5. 所有导入均显示在文件最前端；
 *
 * @author BaiMo
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

        // =============== %drcomovex_var_<key>% ===============
        // 返回变量的默认值(initial)
        placeholderUtil.register("var", (player, rawArgs) ->
                processPlaceholder(placeholderUtil, "drcomovex_var", player, rawArgs,
                        (pl, key) -> getDefaultInitial(key)));

        // ========= %drcomovex_global_var_<key>% =========
        // 强制以全局身份获取变量值
        placeholderUtil.register("global_var", (player, rawArgs) ->
                processPlaceholder(placeholderUtil, "drcomovex_global_var", player, rawArgs,
                        (pl, key) -> fetchGlobalVariable(key)));

        // ========= %drcomovex_player_var_<key>% =========
        // 强制以玩家身份获取变量值
        placeholderUtil.register("player_var", (player, rawArgs) ->
                processPlaceholder(placeholderUtil, "drcomovex_player_var", player, rawArgs,
                        (pl, key) -> fetchPlayerVariable(pl, key)));
    }

    // -------------------- 私有通用方法 --------------------

    /**
     * 通用占位符处理流程：日志输入 -> 非空校验 -> 参数解析 -> 业务逻辑 -> 日志输出 -> 结果校验
     *
     * @param placeholder 占位符名称 (不含 %)
     * @param player      Bukkit 玩家对象（全局可传 null）
     * @param rawArgs     原始参数字符串
     * @param handler     具体业务逻辑，返回处理结果
     */
    private String processPlaceholder(PlaceholderAPIUtil util,
                                      String placeholder,
                                      OfflinePlayer player,
                                      String rawArgs,
                                      BiFunction<OfflinePlayer, String, String> handler) {
        logger.info("占位符 " + placeholder + " 输入参数: " + rawArgs);

        String result;
        // 1. 非空校验
        if (rawArgs == null || rawArgs.trim().isEmpty()) {
            result = "变量名不能为空";
        } else {
            // 2. 参数解析，可抛出异常
            try {
                util.splitArgs(rawArgs);
            } catch (Exception e) {
                logger.error("占位符 " + placeholder + " 参数解析失败，原始输入: " + rawArgs, e);
                result = "错误";
                logger.info("占位符 " + placeholder + " 输出结果: " + result);
                // 3. 结果中含 % 或为空时输出调试日志
                if (result == null || result.trim().isEmpty() || result.contains("%")) {
                    logger.debug("占位符 " + placeholder
                            + " 解析结果异常，原始输入: " + rawArgs + ", 输出: " + result);
                }
                return result;
            }
            // 4. 执行业务逻辑
            result = handler.apply(player, rawArgs);
        }

        // 5. 日志输出与结果校验
        logger.info("占位符 " + placeholder + " 输出结果: " + result);
        if (result == null || result.trim().isEmpty() || result.contains("%")) {
            logger.debug("占位符 " + placeholder
                    + " 解析结果异常，原始输入: " + rawArgs + ", 输出: " + result);
        }
        return result;
    }

    /**
     * 获取变量定义的默认初始值
     */
    private String getDefaultInitial(String key) {
        Variable var = variablesManager.getVariableDefinition(key);
        if (var != null) {
            String initial = var.getInitial();
            return initial != null ? initial : "0";
        }
        return "变量不存在";
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
                CompletableFuture<VariableResult> future =
                        variablesManager.getVariable(null, key);
                VariableResult vr = future.get(500, TimeUnit.MILLISECONDS);
                return vr.isSuccess() ? vr.getValue() : "0";
            } catch (Exception e) {
                logger.error("占位符 drcomovex_global_var 获取全局变量异常，参数: " + key, e);
                return e instanceof java.util.concurrent.TimeoutException ? "0" : "异常:" + e.getMessage();
            }
        } else if (var != null) {
            logger.debug("占位符 drcomovex_global_var 变量 " + key + " 不是全局类型，返回空字符串");
            return "";
        }
        return "变量不存在";
    }

    /**
     * 以玩家身份获取变量值
     */
    private String fetchPlayerVariable(OfflinePlayer player, String key) {
        if (!hasVariable(key)) {
            return "变量不存在";
        }
        Variable var = variablesManager.getVariableDefinition(key);
        if (var != null && var.isPlayerScoped()) {
            try {
                CompletableFuture<VariableResult> future =
                        variablesManager.getVariable(player, key);
                VariableResult vr = future.get(500, TimeUnit.MILLISECONDS);
                return vr.isSuccess() ? vr.getValue() : "0";
            } catch (Exception e) {
                logger.error("占位符 drcomovex_player_var 获取玩家变量异常，参数: " + key, e);
                return e instanceof java.util.concurrent.TimeoutException ? "0" : "异常:" + e.getMessage();
            }
        } else if (var != null) {
            logger.debug("占位符 drcomovex_player_var 变量 " + key + " 不是玩家类型，返回空字符串");
            return "";
        }
        return "变量不存在";
    }
}
