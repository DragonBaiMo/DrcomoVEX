package cn.drcomo.managers;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.model.VariableResult;
import cn.drcomo.model.structure.ScopeType;
import cn.drcomo.model.structure.Variable;
import cn.drcomo.database.HikariConnection;
import cn.drcomo.corelib.util.DebugUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Optional;

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
    private final RefactoredVariablesManager variablesManager;
    private final HikariConnection database;
    
    public ServerVariablesManager(
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
     * 初始化服务器变量管理器
     */
    public void initialize() {
        logger.info("正在初始化服务器变量管理器...");
        logger.info("服务器变量管理器初始化完成！");
    }
    
    /**
     * 获取用于占位符解析的玩家对象
     * 对于全局变量，如果需要占位符解析，使用随机在线玩家
     */
    private OfflinePlayer getPlayerForPlaceholders() {
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        if (onlinePlayers.isEmpty()) {
            logger.debug("没有在线玩家可用于全局变量占位符解析");
            return null;
        }
        
        // 随机选择一个在线玩家用于占位符解析
        Player[] players = onlinePlayers.toArray(new Player[0]);
        Player randomPlayer = players[ThreadLocalRandom.current().nextInt(players.length)];
        logger.debug("选择玩家用于全局变量占位符解析: " + randomPlayer.getName());
        return randomPlayer;
    }
    
    /**
     * 获取服务器变量值
     */
    public CompletableFuture<VariableResult> getServerVariable(String key) {
        OfflinePlayer playerForPlaceholders = getPlayerForPlaceholders();
        return variablesManager.getVariable(playerForPlaceholders, key)
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
        Optional<VariableResult> validation = variablesManager.validateScope(key, ScopeType.GLOBAL);
        if (validation.isPresent()) {
            return CompletableFuture.completedFuture(validation.get());
        }
        OfflinePlayer playerForPlaceholders = getPlayerForPlaceholders();
        return variablesManager.setVariable(playerForPlaceholders, key, value);
    }
    
    /**
     * 增加服务器变量值
     */
    public CompletableFuture<VariableResult> addServerVariable(String key, String addValue) {
        Optional<VariableResult> validation = variablesManager.validateScope(key, ScopeType.GLOBAL);
        if (validation.isPresent()) {
            return CompletableFuture.completedFuture(validation.get());
        }
        OfflinePlayer playerForPlaceholders = getPlayerForPlaceholders();
        return variablesManager.addVariable(playerForPlaceholders, key, addValue);
    }
    
    /**
     * 移除服务器变量值
     */
    public CompletableFuture<VariableResult> removeServerVariable(String key, String removeValue) {
        Optional<VariableResult> validation = variablesManager.validateScope(key, ScopeType.GLOBAL);
        if (validation.isPresent()) {
            return CompletableFuture.completedFuture(validation.get());
        }
        OfflinePlayer playerForPlaceholders = getPlayerForPlaceholders();
        return variablesManager.removeVariable(playerForPlaceholders, key, removeValue);
    }
    
    /**
     * 重置服务器变量
     */
    public CompletableFuture<VariableResult> resetServerVariable(String key) {
        Optional<VariableResult> validation = variablesManager.validateScope(key, ScopeType.GLOBAL);
        if (validation.isPresent()) {
            return CompletableFuture.completedFuture(validation.get());
        }
        OfflinePlayer playerForPlaceholders = getPlayerForPlaceholders();
        return variablesManager.resetVariable(playerForPlaceholders, key);
    }
    
    /**
     * 检查是否为服务器变量
     */
    public boolean isServerVariable(String key) {
        Variable variable = variablesManager.getVariableDefinition(key);
        return variable != null && variable.isGlobal();
    }
}