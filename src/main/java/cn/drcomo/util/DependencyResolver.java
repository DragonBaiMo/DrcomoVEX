package cn.drcomo.util;

import cn.drcomo.model.structure.DependencySnapshot;
import cn.drcomo.model.structure.Variable;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.corelib.math.FormulaCalculator;
import org.bukkit.OfflinePlayer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 依赖解析器
 * 
 * 负责处理严格初始值模式下的变量依赖关系，包括：
 * - 循环依赖检测
 * - 依赖快照创建和管理
 * - 拓扑排序计算顺序
 * 
 * @author BaiMo
 */
public class DependencyResolver {
    
    private static final Pattern INTERNAL_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    
    private final DebugUtil logger;
    
    // 变量快照缓存：玩家ID:变量名 -> 快照
    private final Map<String, DependencySnapshot> snapshotCache;
    
    // 正在计算中的变量（用于循环依赖检测）
    private final ThreadLocal<Set<String>> calculatingVariables;
    
    public DependencyResolver(DebugUtil logger) {
        this.logger = logger;
        this.snapshotCache = new ConcurrentHashMap<>();
        this.calculatingVariables = ThreadLocal.withInitial(HashSet::new);
    }
    
    /**
     * 为严格模式变量解析表达式并创建快照
     * 
     * @param variable 变量定义
     * @param player 玩家
     * @param valueProvider 值提供者函数接口
     * @return 依赖快照
     */
    public DependencySnapshot resolveWithSnapshot(Variable variable, OfflinePlayer player, 
                                                 ValueProvider valueProvider) {
        String cacheKey = getCacheKey(player, variable.getKey());
        
        // 检查是否已有快照
        DependencySnapshot existing = snapshotCache.get(cacheKey);
        if (existing != null) {
            logger.debug("使用已缓存的依赖快照: " + variable.getKey());
            return existing;
        }
        
        String expression = variable.getInitial();
        if (expression == null || expression.trim().isEmpty()) {
            return DependencySnapshot.empty(expression, "");
        }
        
        // 检测循环依赖
        String calculatingKey = getCalculatingKey(player, variable.getKey());
        Set<String> calculating = calculatingVariables.get();
        
        if (calculating.contains(calculatingKey)) {
            logger.warn("检测到循环依赖: " + variable.getKey());
            return DependencySnapshot.circular(expression);
        }
        
        try {
            // 标记当前变量正在计算
            calculating.add(calculatingKey);
            
            // 解析依赖并创建快照
            DependencySnapshot snapshot = createSnapshot(expression, player, valueProvider);
            
            // 缓存快照
            snapshotCache.put(cacheKey, snapshot);
            
            logger.debug("创建依赖快照: " + variable.getKey() + " -> " + snapshot);
            return snapshot;
            
        } finally {
            // 清除计算标记
            calculating.remove(calculatingKey);
        }
    }
    
    /**
     * 检查是否有快照缓存
     */
    public boolean hasSnapshot(OfflinePlayer player, String variableKey) {
        String cacheKey = getCacheKey(player, variableKey);
        return snapshotCache.containsKey(cacheKey);
    }
    
    /**
     * 获取快照
     */
    public DependencySnapshot getSnapshot(OfflinePlayer player, String variableKey) {
        String cacheKey = getCacheKey(player, variableKey);
        return snapshotCache.get(cacheKey);
    }
    
    /**
     * 清除指定变量的快照
     */
    public void clearSnapshot(OfflinePlayer player, String variableKey) {
        String cacheKey = getCacheKey(player, variableKey);
        snapshotCache.remove(cacheKey);
        logger.debug("清除依赖快照: " + variableKey);
    }
    
    /**
     * 清除所有快照
     */
    public void clearAllSnapshots() {
        snapshotCache.clear();
        logger.debug("清除所有依赖快照");
    }
    
    /**
     * 清除指定玩家的所有快照
     */
    public void clearPlayerSnapshots(OfflinePlayer player) {
        String playerPrefix = getPlayerPrefix(player);
        snapshotCache.entrySet().removeIf(entry -> entry.getKey().startsWith(playerPrefix));
        logger.debug("清除玩家依赖快照: " + player.getName());
    }
    
    /**
     * 创建依赖快照
     */
    private DependencySnapshot createSnapshot(String expression, OfflinePlayer player, 
                                            ValueProvider valueProvider) {
        Map<String, String> dependencyValues = new HashMap<>();
        Set<String> dependencies = extractDependencies(expression);
        
        for (String depKey : dependencies) {
            try {
                String depValue = valueProvider.getValue(player, depKey);
                if (depValue != null) {
                    dependencyValues.put(depKey, depValue);
                }
            } catch (Exception e) {
                logger.debug("获取依赖变量值失败: " + depKey + " - " + e.getMessage());
                dependencyValues.put(depKey, "0"); // 提供默认值避免计算失败
            }
        }
        
        // 替换表达式中的依赖变量
        String resolvedExpression = substituteVariables(expression, dependencyValues);
        
        // 计算最终结果（这里简化处理，实际应该调用FormulaCalculator）
        String result = calculateResult(resolvedExpression);
        
        return new DependencySnapshot(expression, result, dependencyValues, false);
    }
    
    /**
     * 提取表达式中的依赖变量
     */
    private Set<String> extractDependencies(String expression) {
        Set<String> dependencies = new HashSet<>();
        Matcher matcher = INTERNAL_VAR_PATTERN.matcher(expression);
        
        while (matcher.find()) {
            String varKey = matcher.group(1);
            dependencies.add(varKey);
        }
        
        return dependencies;
    }
    
    /**
     * 替换表达式中的变量引用
     */
    private String substituteVariables(String expression, Map<String, String> values) {
        String result = expression;
        
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String varKey = entry.getKey();
            String varValue = entry.getValue();
            
            // 替换 ${varKey} 为实际值
            result = result.replace("${" + varKey + "}", varValue);
        }
        
        return result;
    }
    
    /**
     * 计算表达式结果
     */
    private String calculateResult(String expression) {
        try {
            // 如果是纯数字，直接返回
            if (expression.matches("^-?\\d+(\\.\\d+)?$")) {
                return expression;
            }
            
            // 如果包含数学运算，使用FormulaCalculator
            if (expression.matches(".*[+\\-*/()^].*") && expression.matches(".*\\d.*")) {
                double result = FormulaCalculator.calculate(expression);
                return String.valueOf(result);
            }
            
            // 其他情况直接返回原表达式
            return expression;
        } catch (Exception e) {
            logger.debug("表达式计算失败: " + expression + " - " + e.getMessage());
            return expression;
        }
    }
    
    /**
     * 获取缓存键
     */
    private String getCacheKey(OfflinePlayer player, String variableKey) {
        return getPlayerPrefix(player) + ":" + variableKey;
    }
    
    /**
     * 获取计算键（用于循环检测）
     */
    private String getCalculatingKey(OfflinePlayer player, String variableKey) {
        return getCacheKey(player, variableKey);
    }
    
    /**
     * 获取玩家前缀
     */
    private String getPlayerPrefix(OfflinePlayer player) {
        return player != null ? "player:" + player.getUniqueId() : "global";
    }
    
    /**
     * 值提供者接口
     */
    @FunctionalInterface
    public interface ValueProvider {
        String getValue(OfflinePlayer player, String variableKey) throws Exception;
    }
    
    /**
     * 获取快照统计信息
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalSnapshots", snapshotCache.size());
        
        int validSnapshots = 0;
        int circularSnapshots = 0;
        
        for (DependencySnapshot snapshot : snapshotCache.values()) {
            if (snapshot.hasCircularDependency()) {
                circularSnapshots++;
            } else if (snapshot.isValid()) {
                validSnapshots++;
            }
        }
        
        stats.put("validSnapshots", validSnapshots);
        stats.put("circularSnapshots", circularSnapshots);
        
        return stats;
    }
}