package cn.drcomo.storage;

import java.util.UUID;

/**
 * 持久化任务基类
 * 
 * 定义持久化任务的基本结构和通用方法。
 * 
 * @author BaiMo
 */
public abstract class PersistenceTask {
    
    protected final String key;
    protected final DirtyFlag dirtyFlag;
    
    public PersistenceTask(String key, DirtyFlag dirtyFlag) {
        this.key = key;
        this.dirtyFlag = dirtyFlag;
    }
    
    public String getKey() {
        return key;
    }
    
    public DirtyFlag getDirtyFlag() {
        return dirtyFlag;
    }
    
    /**
     * 获取任务优先级（数字越小优先级越高）
     */
    public abstract int getPriority();
    
    /**
     * 获取任务类型描述
     */
    public abstract String getTaskType();
    
    @Override
    public String toString() {
        return String.format("%s{key='%s', priority=%d}", 
                getTaskType(), key, getPriority());
    }
}

/**
 * 玩家变量更新任务
 */
class PlayerVariableUpdateTask extends PersistenceTask {
    
    private final UUID playerId;
    private final String variableKey;
    private final VariableValue value;
    
    public PlayerVariableUpdateTask(String key, DirtyFlag dirtyFlag, 
                                   UUID playerId, String variableKey, VariableValue value) {
        super(key, dirtyFlag);
        this.playerId = playerId;
        this.variableKey = variableKey;
        this.value = value;
    }
    
    public UUID getPlayerId() {
        return playerId;
    }
    
    public String getVariableKey() {
        return variableKey;
    }
    
    public VariableValue getValue() {
        return value;
    }
    
    @Override
    public int getPriority() {
        // 热点数据优先级更高
        return value.isHotData() ? 1 : 3;
    }
    
    @Override
    public String getTaskType() {
        return "PlayerVariableUpdate";
    }
}

/**
 * 玩家变量删除任务
 */
class PlayerVariableDeleteTask extends PersistenceTask {
    
    private final UUID playerId;
    private final String variableKey;
    
    public PlayerVariableDeleteTask(String key, DirtyFlag dirtyFlag, 
                                   UUID playerId, String variableKey) {
        super(key, dirtyFlag);
        this.playerId = playerId;
        this.variableKey = variableKey;
    }
    
    public UUID getPlayerId() {
        return playerId;
    }
    
    public String getVariableKey() {
        return variableKey;
    }
    
    @Override
    public int getPriority() {
        // 删除操作优先级较高
        return 2;
    }
    
    @Override
    public String getTaskType() {
        return "PlayerVariableDelete";
    }
}

/**
 * 服务器变量更新任务
 */
class ServerVariableUpdateTask extends PersistenceTask {
    
    private final String variableKey;
    private final VariableValue value;
    
    public ServerVariableUpdateTask(String key, DirtyFlag dirtyFlag, 
                                   String variableKey, VariableValue value) {
        super(key, dirtyFlag);
        this.variableKey = variableKey;
        this.value = value;
    }
    
    public String getVariableKey() {
        return variableKey;
    }
    
    public VariableValue getValue() {
        return value;
    }
    
    @Override
    public int getPriority() {
        // 服务器变量通常优先级较高
        return value.isHotData() ? 1 : 2;
    }
    
    @Override
    public String getTaskType() {
        return "ServerVariableUpdate";
    }
}

/**
 * 服务器变量删除任务
 */
class ServerVariableDeleteTask extends PersistenceTask {
    
    private final String variableKey;
    
    public ServerVariableDeleteTask(String key, DirtyFlag dirtyFlag, String variableKey) {
        super(key, dirtyFlag);
        this.variableKey = variableKey;
    }
    
    public String getVariableKey() {
        return variableKey;
    }
    
    @Override
    public int getPriority() {
        // 服务器变量删除优先级最高
        return 1;
    }
    
    @Override
    public String getTaskType() {
        return "ServerVariableDelete";
    }
}