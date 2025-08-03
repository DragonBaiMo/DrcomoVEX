package cn.drcomo.managers;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.model.VariableResult;
import cn.drcomo.model.structure.Variable;
import cn.drcomo.database.HikariConnection;
import cn.drcomo.corelib.util.DebugUtil;
import org.bukkit.OfflinePlayer;

import java.util.concurrent.CompletableFuture;

/**
 * 玩家变量管理器
 * 
 * 专门管理玩家作用域的变量。
 * 
 * @author BaiMo
 */
public class PlayerVariablesManager {
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final VariablesManager variablesManager;
    private final HikariConnection database;
    
    public PlayerVariablesManager(
            DrcomoVEX plugin,
            DebugUtil logger,
            VariablesManager variablesManager,
            HikariConnection database
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.variablesManager = variablesManager;
        this.database = database;
    }
    
    /**
     * 初始化玩家变量管理器
     */
    public void initialize() {
        logger.info("正在初始化玩家变量管理器...");
        logger.info("玩家变量管理器初始化完成！");
    }
    
    /**
     * 获取玩家变量值
     */
    public CompletableFuture<VariableResult> getPlayerVariable(OfflinePlayer player, String key) {
        return variablesManager.getVariable(player, key)
                .thenApply(result -> {
                    if (result.isSuccess()) {
                        // 检查是否为玩家变量
                        Variable variable = variablesManager.getVariableDefinition(key);
                        if (variable != null && !variable.isPlayerScoped()) {
                            return VariableResult.failure("变量不是玩家作用域: " + key);
                        }
                    }
                    return result;
                });
    }
    
    /**
     * 设置玩家变量值
     */
    public CompletableFuture<VariableResult> setPlayerVariable(OfflinePlayer player, String key, String value) {
        Variable variable = variablesManager.getVariableDefinition(key);
        if (variable == null) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("变量不存在: " + key)
            );
        }
        
        if (!variable.isPlayerScoped()) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("变量不是玩家作用域: " + key)
            );
        }
        
        return variablesManager.setVariable(player, key, value);
    }
    
    /**
     * 增加玩家变量值
     */
    public CompletableFuture<VariableResult> addPlayerVariable(OfflinePlayer player, String key, String addValue) {
        Variable variable = variablesManager.getVariableDefinition(key);
        if (variable == null) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("变量不存在: " + key)
            );
        }
        
        if (!variable.isPlayerScoped()) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("变量不是玩家作用域: " + key)
            );
        }
        
        return variablesManager.addVariable(player, key, addValue);
    }
    
    /**
     * 移除玩家变量值
     */
    public CompletableFuture<VariableResult> removePlayerVariable(OfflinePlayer player, String key, String removeValue) {
        Variable variable = variablesManager.getVariableDefinition(key);
        if (variable == null) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("变量不存在: " + key)
            );
        }
        
        if (!variable.isPlayerScoped()) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("变量不是玩家作用域: " + key)
            );
        }
        
        return variablesManager.removeVariable(player, key, removeValue);
    }
    
    /**
     * 重置玩家变量
     */
    public CompletableFuture<VariableResult> resetPlayerVariable(OfflinePlayer player, String key) {
        Variable variable = variablesManager.getVariableDefinition(key);
        if (variable == null) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("变量不存在: " + key)
            );
        }
        
        if (!variable.isPlayerScoped()) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("变量不是玩家作用域: " + key)
            );
        }
        
        return variablesManager.resetVariable(player, key);
    }
    
    /**
     * 检查是否为玩家变量
     */
    public boolean isPlayerVariable(String key) {
        Variable variable = variablesManager.getVariableDefinition(key);
        return variable != null && variable.isPlayerScoped();
    }
    
    /**
     * 清理玩家数据
     */
    public CompletableFuture<Integer> clearPlayerData(OfflinePlayer player) {
        return database.deleteAsync("player_variables", "player_uuid = ?", player.getUniqueId().toString());
    }
    
    /**
     * 导出玩家数据
     */
    public CompletableFuture<java.util.Map<String, String>> exportPlayerData(OfflinePlayer player) {
        // TODO: 实现玩家数据导出逻辑
        return CompletableFuture.completedFuture(new java.util.HashMap<>());
    }
}