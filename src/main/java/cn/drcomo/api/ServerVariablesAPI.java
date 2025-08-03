package cn.drcomo.api;

import cn.drcomo.managers.*;
import cn.drcomo.model.VariableResult;
import cn.drcomo.model.structure.Variable;
import cn.drcomo.corelib.hook.placeholder.PlaceholderAPIUtil;
import cn.drcomo.corelib.util.DebugUtil;
import org.bukkit.OfflinePlayer;

import java.util.concurrent.CompletableFuture;

/**
 * DrcomoVEX 对外 API 接口
 * 
 * 为其他插件提供与 DrcomoVEX 变量系统交互的能力。
 * 
 * @author BaiMo
 */
public class ServerVariablesAPI {

    private final DebugUtil logger;
    private final VariablesManager variablesManager;
    private final ServerVariablesManager serverVariablesManager;
    private final PlayerVariablesManager playerVariablesManager;

    public ServerVariablesAPI(
            DebugUtil logger,
            VariablesManager variablesManager,
            ServerVariablesManager serverVariablesManager,
            PlayerVariablesManager playerVariablesManager
    ) {
        this.logger = logger;
        this.variablesManager = variablesManager;
        this.serverVariablesManager = serverVariablesManager;
        this.playerVariablesManager = playerVariablesManager;
    }
    
    // ======================== 玩家变量 API ========================
    
    /**
     * 获取玩家变量值
     */
    public CompletableFuture<String> getPlayerVariable(OfflinePlayer player, String key) {
        return playerVariablesManager.getPlayerVariable(player, key)
                .thenApply(result -> result.isSuccess() ? result.getValue() : null);
    }
    
    /**
     * 设置玩家变量值
     */
    public CompletableFuture<Boolean> setPlayerVariable(OfflinePlayer player, String key, String value) {
        return playerVariablesManager.setPlayerVariable(player, key, value)
                .thenApply(VariableResult::isSuccess);
    }
    
    /**
     * 增加玩家变量值
     */
    public CompletableFuture<Boolean> addPlayerVariable(OfflinePlayer player, String key, String addValue) {
        return playerVariablesManager.addPlayerVariable(player, key, addValue)
                .thenApply(VariableResult::isSuccess);
    }
    
    /**
     * 移除玩家变量值
     */
    public CompletableFuture<Boolean> removePlayerVariable(OfflinePlayer player, String key, String removeValue) {
        return playerVariablesManager.removePlayerVariable(player, key, removeValue)
                .thenApply(VariableResult::isSuccess);
    }
    
    /**
     * 重置玩家变量
     */
    public CompletableFuture<Boolean> resetPlayerVariable(OfflinePlayer player, String key) {
        return playerVariablesManager.resetPlayerVariable(player, key)
                .thenApply(VariableResult::isSuccess);
    }
    
    // ======================== 服务器变量 API ========================
    
    /**
     * 获取服务器变量值
     */
    public CompletableFuture<String> getServerVariable(String key) {
        return serverVariablesManager.getServerVariable(key)
                .thenApply(result -> result.isSuccess() ? result.getValue() : null);
    }
    
    /**
     * 设置服务器变量值
     */
    public CompletableFuture<Boolean> setServerVariable(String key, String value) {
        return serverVariablesManager.setServerVariable(key, value)
                .thenApply(VariableResult::isSuccess);
    }
    
    /**
     * 增加服务器变量值
     */
    public CompletableFuture<Boolean> addServerVariable(String key, String addValue) {
        return serverVariablesManager.addServerVariable(key, addValue)
                .thenApply(VariableResult::isSuccess);
    }
    
    /**
     * 移除服务器变量值
     */
    public CompletableFuture<Boolean> removeServerVariable(String key, String removeValue) {
        return serverVariablesManager.removeServerVariable(key, removeValue)
                .thenApply(VariableResult::isSuccess);
    }
    
    /**
     * 重置服务器变量
     */
    public CompletableFuture<Boolean> resetServerVariable(String key) {
        return serverVariablesManager.resetServerVariable(key)
                .thenApply(VariableResult::isSuccess);
    }
    
    // ======================== 变量定义 API ========================
    
    /**
     * 获取变量定义
     */
    public Variable getVariableDefinition(String key) {
        return variablesManager.getVariableDefinition(key);
    }
    
    /**
     * 检查变量是否存在
     */
    public boolean hasVariable(String key) {
        return variablesManager.getVariableDefinition(key) != null;
    }
    
    /**
     * 检查是否为玩家变量
     */
    public boolean isPlayerVariable(String key) {
        return playerVariablesManager.isPlayerVariable(key);
    }
    
    /**
     * 检查是否为服务器变量
     */
    public boolean isServerVariable(String key) {
        return serverVariablesManager.isServerVariable(key);
    }
    
    /**
     * 获取所有变量键名
     */
    public java.util.Set<String> getAllVariableKeys() {
        return variablesManager.getAllVariableKeys();
    }
    
    // ======================== PlaceholderAPI 集成 ========================
    
    /**
     * 注册 PlaceholderAPI 占位符
     */
    public void registerPlaceholders(PlaceholderAPIUtil placeholderUtil) {
        // ------------------- 通用验证工具 -------------------
        java.util.function.Function<String, Boolean> isBlank = s -> s == null || s.trim().isEmpty();

        // =============== %drcomovex_var_<key>% ===============
        placeholderUtil.register("var", (player, rawArgs) -> {
            logger.info("占位符 drcomovex_var 输入参数: " + rawArgs);
            String result;
            if (isBlank.apply(rawArgs)) {
                result = "变量名不能为空";
            } else {
                String variableKey = rawArgs; // 直接使用完整变量键
                try {
                    placeholderUtil.splitArgs(rawArgs); // 如需额外参数可在此解析
                } catch (Exception e) {
                    logger.error("占位符 drcomovex_var 参数解析失败，原始输入: " + rawArgs, e);
                    result = "错误";
                    logger.info("占位符 drcomovex_var 输出结果: " + result);
                    if (result == null || result.trim().isEmpty() || result.contains("%")) {
                        logger.debug("占位符 drcomovex_var 解析结果为空或包含未解析占位符，原始输入: " + rawArgs + ", 输出: " + result);
                    }
                    return result;
                }
                if (isPlayerVariable(variableKey)) {
                    try {
                        CompletableFuture<String> future = getPlayerVariable(player, variableKey);
                        String value = future.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                        result = value != null ? value : "0";
                    } catch (java.util.concurrent.TimeoutException e) {
                        logger.error("占位符 drcomovex_var 获取玩家变量超时，参数: " + rawArgs, e);
                        result = "0";
                    } catch (Exception e) {
                        logger.error("占位符 drcomovex_var 获取玩家变量异常，参数: " + rawArgs, e);
                        result = "异常:" + e.getMessage();
                    }
                } else if (isServerVariable(variableKey)) {
                    try {
                        CompletableFuture<String> future = getServerVariable(variableKey);
                        String value = future.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                        result = value != null ? value : "0";
                    } catch (java.util.concurrent.TimeoutException e) {
                        logger.error("占位符 drcomovex_var 获取服务器变量超时，参数: " + rawArgs, e);
                        result = "0";
                    } catch (Exception e) {
                        logger.error("占位符 drcomovex_var 获取服务器变量异常，参数: " + rawArgs, e);
                        result = "异常:" + e.getMessage();
                    }
                } else {
                    result = "变量不存在";
                }
            }
            logger.info("占位符 drcomovex_var 输出结果: " + result);
            if (result == null || result.trim().isEmpty() || result.contains("%")) {
                logger.debug("占位符 drcomovex_var 解析结果为空或包含未解析占位符，原始输入: " + rawArgs + ", 输出: " + result);
            }
            return result;
        });

        // ========= %drcomovex_server_var_<key>% =========
        placeholderUtil.register("server_var", (player, rawArgs) -> {
            logger.info("占位符 drcomovex_server_var 输入参数: " + rawArgs);
            String result;
            if (isBlank.apply(rawArgs)) {
                result = "变量名不能为空";
            } else {
                String variableKey = rawArgs; // 直接使用完整变量键
                try {
                    placeholderUtil.splitArgs(rawArgs); // 如需额外参数可在此解析
                } catch (Exception e) {
                    logger.error("占位符 drcomovex_server_var 参数解析失败，原始输入: " + rawArgs, e);
                    result = "错误";
                    logger.info("占位符 drcomovex_server_var 输出结果: " + result);
                    if (result == null || result.trim().isEmpty() || result.contains("%")) {
                        logger.debug("占位符 drcomovex_server_var 解析结果为空或包含未解析占位符，原始输入: " + rawArgs + ", 输出: " + result);
                    }
                    return result;
                }
                if (isServerVariable(variableKey)) {
                    try {
                        CompletableFuture<String> future = getServerVariable(variableKey);
                        String value = future.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                        result = value != null ? value : "0";
                    } catch (java.util.concurrent.TimeoutException e) {
                        logger.error("占位符 drcomovex_server_var 获取服务器变量超时，参数: " + rawArgs, e);
                        result = "0";
                    } catch (Exception e) {
                        logger.error("占位符 drcomovex_server_var 获取服务器变量异常，参数: " + rawArgs, e);
                        result = "异常:" + e.getMessage();
                    }
                } else {
                    result = "变量不存在";
                }
            }
            logger.info("占位符 drcomovex_server_var 输出结果: " + result);
            if (result == null || result.trim().isEmpty() || result.contains("%")) {
                logger.debug("占位符 drcomovex_server_var 解析结果为空或包含未解析占位符，原始输入: " + rawArgs + ", 输出: " + result);
            }
            return result;
        });

        // ========= %drcomovex_player_var_<key>% =========
        placeholderUtil.register("player_var", (player, rawArgs) -> {
            logger.info("占位符 drcomovex_player_var 输入参数: " + rawArgs);
            String result;
            if (isBlank.apply(rawArgs)) {
                result = "变量名不能为空";
            } else {
                String variableKey = rawArgs; // 直接使用完整变量键
                try {
                    placeholderUtil.splitArgs(rawArgs); // 如需额外参数可在此解析
                } catch (Exception e) {
                    logger.error("占位符 drcomovex_player_var 参数解析失败，原始输入: " + rawArgs, e);
                    result = "错误";
                    logger.info("占位符 drcomovex_player_var 输出结果: " + result);
                    if (result == null || result.trim().isEmpty() || result.contains("%")) {
                        logger.debug("占位符 drcomovex_player_var 解析结果为空或包含未解析占位符，原始输入: " + rawArgs + ", 输出: " + result);
                    }
                    return result;
                }
                if (isPlayerVariable(variableKey)) {
                    try {
                        CompletableFuture<String> future = getPlayerVariable(player, variableKey);
                        String value = future.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                        result = value != null ? value : "0";
                    } catch (java.util.concurrent.TimeoutException e) {
                        logger.error("占位符 drcomovex_player_var 获取玩家变量超时，参数: " + rawArgs, e);
                        result = "0";
                    } catch (Exception e) {
                        logger.error("占位符 drcomovex_player_var 获取玩家变量异常，参数: " + rawArgs, e);
                        result = "异常:" + e.getMessage();
                    }
                } else {
                    result = "变量不存在";
                }
            }
            logger.info("占位符 drcomovex_player_var 输出结果: " + result);
            if (result == null || result.trim().isEmpty() || result.contains("%")) {
                logger.debug("占位符 drcomovex_player_var 解析结果为空或包含未解析占位符，原始输入: " + rawArgs + ", 输出: " + result);
            }
            return result;
        });
    }
}