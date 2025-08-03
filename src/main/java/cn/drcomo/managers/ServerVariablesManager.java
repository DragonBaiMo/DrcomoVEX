package cn.drcomo.managers;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.model.VariableResult;
import cn.drcomo.model.structure.Variable;
import cn.drcomo.database.HikariConnection;
import cn.drcomo.corelib.util.DebugUtil;
import org.bukkit.OfflinePlayer;

import java.util.concurrent.CompletableFuture;

/**
 * 服务器变量管理器
 * 
 * 专门管理全局作用域的服务器变量。
 * 
 * @author BaiMo
 */
public class ServerVariablesManager {
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final VariablesManager variablesManager;
    private final HikariConnection database;
    
    public ServerVariablesManager(
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
     * 初始化服务器变量管理器
     */
    public void initialize() {
        logger.info("正在初始化服务器变量管理器...");
        logger.info("服务器变量管理器初始化完成！");
    }
    
    /**
     * 获取服务器变量值
     */
    public CompletableFuture<VariableResult> getServerVariable(String key) {
        return variablesManager.getVariable(null, key)
                .thenApply(result -> {
                    if (result.isSuccess()) {
                        // 检查是否为全局变量
                        Variable variable = variablesManager.getVariableDefinition(key);
                        if (variable != null && !variable.isGlobal()) {
                            return VariableResult.failure("变量不是全局作用域: " + key);
                        }
                    }
                    return result;
                });
    }
    
    /**
     * 设置服务器变量值
     */
    public CompletableFuture<VariableResult> setServerVariable(String key, String value) {
        Variable variable = variablesManager.getVariableDefinition(key);
        if (variable == null) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("变量不存在: " + key)
            );
        }
        
        if (!variable.isGlobal()) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("变量不是全局作用域: " + key)
            );
        }
        
        return variablesManager.setVariable(null, key, value);
    }
    
    /**
     * 增加服务器变量值
     */
    public CompletableFuture<VariableResult> addServerVariable(String key, String addValue) {
        Variable variable = variablesManager.getVariableDefinition(key);
        if (variable == null) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("变量不存在: " + key)
            );
        }
        
        if (!variable.isGlobal()) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("变量不是全局作用域: " + key)
            );
        }
        
        return variablesManager.addVariable(null, key, addValue);
    }
    
    /**
     * 移除服务器变量值
     */
    public CompletableFuture<VariableResult> removeServerVariable(String key, String removeValue) {
        Variable variable = variablesManager.getVariableDefinition(key);
        if (variable == null) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("变量不存在: " + key)
            );
        }
        
        if (!variable.isGlobal()) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("变量不是全局作用域: " + key)
            );
        }
        
        return variablesManager.removeVariable(null, key, removeValue);
    }
    
    /**
     * 重置服务器变量
     */
    public CompletableFuture<VariableResult> resetServerVariable(String key) {
        Variable variable = variablesManager.getVariableDefinition(key);
        if (variable == null) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("变量不存在: " + key)
            );
        }
        
        if (!variable.isGlobal()) {
            return CompletableFuture.completedFuture(
                    VariableResult.failure("变量不是全局作用域: " + key)
            );
        }
        
        return variablesManager.resetVariable(null, key);
    }
    
    /**
     * 检查是否为服务器变量
     */
    public boolean isServerVariable(String key) {
        Variable variable = variablesManager.getVariableDefinition(key);
        return variable != null && variable.isGlobal();
    }
}