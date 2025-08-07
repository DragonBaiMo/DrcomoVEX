package cn.drcomo.managers;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.model.VariableResult;
import cn.drcomo.model.structure.Variable;
import cn.drcomo.database.HikariConnection;
import cn.drcomo.corelib.util.DebugUtil;
import org.bukkit.OfflinePlayer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 玩家变量管理器
 * 
 * 专门管理玩家作用域的变量。
 * 
 * @author BaiMo
 */
public class PlayerVariablesManager {

    /** 异步操作超时时间（秒） */
    private static final long TIMEOUT_SECONDS = 5L;
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final RefactoredVariablesManager variablesManager;
    private final HikariConnection database;
    
    public PlayerVariablesManager(
            DrcomoVEX plugin,
            DebugUtil logger,
            RefactoredVariablesManager variablesManager,
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
        return withTimeout(variablesManager.getVariable(player, key))
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
        
        return withTimeout(variablesManager.setVariable(player, key, value));
    }
    
    /**
     * 增加玩家变量值
     */
    public CompletableFuture<VariableResult> addPlayerVariable(OfflinePlayer player, String key, String addValue) {
        logger.debug("玩家变量管理器：开始添加变量 " + key + " 对玩家 " + player.getName() + "，值：" + addValue);
        
        Variable variable = variablesManager.getVariableDefinition(key);
        if (variable == null) {
            logger.debug("变量定义不存在: " + key);
            return CompletableFuture.completedFuture(
                    VariableResult.failure("变量不存在: " + key)
            );
        }
        
        if (!variable.isPlayerScoped()) {
            logger.debug("变量不是玩家作用域: " + key + ", 实际作用域: " + (variable.isPlayerScoped() ? "player" : "server"));
            return CompletableFuture.completedFuture(
                    VariableResult.failure("变量不是玩家作用域: " + key)
            );
        }
        
        logger.debug("变量校验通过，委托给 RefactoredVariablesManager");
        return withTimeout(variablesManager.addVariable(player, key, addValue));
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
        
        return withTimeout(variablesManager.removeVariable(player, key, removeValue));
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
        
        return withTimeout(variablesManager.resetVariable(player, key));
    }
    
    /**
     * 检查是否为玩家变量
     */
    public boolean isPlayerVariable(String key) {
        Variable variable = variablesManager.getVariableDefinition(key);
        return variable != null && variable.isPlayerScoped();
    }

    /**
     * 为异步操作添加超时，避免无反馈的悬挂
     */
    private CompletableFuture<VariableResult> withTimeout(CompletableFuture<VariableResult> future) {
        return future.completeOnTimeout(VariableResult.failure("操作超时"), TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}