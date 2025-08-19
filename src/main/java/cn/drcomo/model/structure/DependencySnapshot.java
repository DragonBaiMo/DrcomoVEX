package cn.drcomo.model.structure;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 依赖快照类
 * 
 * 用于严格初始值模式下记录变量的依赖关系和计算时的快照值。
 * 确保在首次计算后，依赖变量的值保持一致，不受后续变化影响。
 * 
 * @author BaiMo
 */
public class DependencySnapshot {
    
    // 快照中记录的依赖变量值
    private final Map<String, String> dependencyValues;
    
    // 快照创建时间
    private final long snapshotTime;
    
    // 原始表达式
    private final String originalExpression;
    
    // 计算结果
    private final String calculatedValue;
    
    // 是否包含循环依赖
    private final boolean hasCircularDependency;
    
    public DependencySnapshot(String originalExpression, String calculatedValue, 
                            Map<String, String> dependencyValues, 
                            boolean hasCircularDependency) {
        this.originalExpression = originalExpression;
        this.calculatedValue = calculatedValue;
        this.dependencyValues = new ConcurrentHashMap<>(dependencyValues);
        this.snapshotTime = System.currentTimeMillis();
        this.hasCircularDependency = hasCircularDependency;
    }
    
    /**
     * 获取快照中的依赖变量值
     */
    public String getDependencyValue(String variableKey) {
        return dependencyValues.get(variableKey);
    }
    
    /**
     * 检查是否包含指定的依赖变量
     */
    public boolean hasDependency(String variableKey) {
        return dependencyValues.containsKey(variableKey);
    }
    
    /**
     * 获取所有依赖变量的键集合
     */
    public java.util.Set<String> getDependencyKeys() {
        return dependencyValues.keySet();
    }
    
    /**
     * 获取快照创建时间
     */
    public long getSnapshotTime() {
        return snapshotTime;
    }
    
    /**
     * 获取原始表达式
     */
    public String getOriginalExpression() {
        return originalExpression;
    }
    
    /**
     * 获取计算结果
     */
    public String getCalculatedValue() {
        return calculatedValue;
    }
    
    /**
     * 检查是否包含循环依赖
     */
    public boolean hasCircularDependency() {
        return hasCircularDependency;
    }
    
    /**
     * 检查快照是否有效（依赖变量数量 > 0 且无循环依赖）
     */
    public boolean isValid() {
        return !hasCircularDependency && !dependencyValues.isEmpty();
    }
    
    /**
     * 获取依赖变量数量
     */
    public int getDependencyCount() {
        return dependencyValues.size();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DependencySnapshot snapshot = (DependencySnapshot) o;
        return Objects.equals(originalExpression, snapshot.originalExpression) &&
               Objects.equals(dependencyValues, snapshot.dependencyValues);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(originalExpression, dependencyValues);
    }
    
    @Override
    public String toString() {
        return "DependencySnapshot{" +
                "expression='" + originalExpression + '\'' +
                ", result='" + calculatedValue + '\'' +
                ", dependencies=" + dependencyValues.size() +
                ", circular=" + hasCircularDependency +
                ", time=" + snapshotTime +
                '}';
    }
    
    /**
     * 创建空快照（用于无依赖的情况）
     */
    public static DependencySnapshot empty(String originalExpression, String calculatedValue) {
        return new DependencySnapshot(originalExpression, calculatedValue, 
                new ConcurrentHashMap<>(), false);
    }
    
    /**
     * 创建循环依赖快照（用于检测到循环依赖的情况）
     */
    public static DependencySnapshot circular(String originalExpression) {
        return new DependencySnapshot(originalExpression, originalExpression, 
                new ConcurrentHashMap<>(), true);
    }
}