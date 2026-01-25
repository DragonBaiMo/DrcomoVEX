package cn.drcomo.model.structure;

import java.util.Objects;

/**
 * 运行时生效的变量参数封装
 *
 * 将基础变量定义与匹配到的参数组合并后得到的有效参数。
 */
public class EffectiveParams {

    private final Variable baseVariable;
    private final ParameterGroup matchedGroup;
    private final String initial;
    private final String min;
    private final String max;
    private final String cycle;
    private final RegenRule regenRule;
    private final String regenRaw;

    private EffectiveParams(Variable baseVariable, ParameterGroup matchedGroup, String initial,
                            String min, String max, String cycle, RegenRule regenRule, String regenRaw) {
        this.baseVariable = baseVariable;
        this.matchedGroup = matchedGroup;
        this.initial = initial;
        this.min = min;
        this.max = max;
        this.cycle = cycle;
        this.regenRule = regenRule;
        this.regenRaw = regenRaw;
    }

    public static EffectiveParams from(Variable variable, ParameterGroup group) {
        if (variable == null) {
            throw new IllegalArgumentException("基础变量不能为空");
        }
        if (group == null) {
            return fromBase(variable);
        }

        String initial = group.getInitial() != null ? group.getInitial() : variable.getInitial();
        String min = group.getMin() != null ? group.getMin() : variable.getMin();
        String max = group.getMax() != null ? group.getMax() : variable.getMax();
        String cycle = group.getCycle() != null ? group.getCycle() : variable.getCycle();
        RegenRule regenRule = group.getRegenRule() != null ? group.getRegenRule() : variable.getRegenRule();
        String regenRaw = group.getRegenRaw() != null ? group.getRegenRaw() : variable.getRegenRaw();

        return new EffectiveParams(variable, group, initial, min, max, cycle, regenRule, regenRaw);
    }

    public static EffectiveParams fromBase(Variable variable) {
        if (variable == null) {
            throw new IllegalArgumentException("基础变量不能为空");
        }
        return new EffectiveParams(variable, null, variable.getInitial(), variable.getMin(), variable.getMax(),
                variable.getCycle(), variable.getRegenRule(), variable.getRegenRaw());
    }

    public Variable getBaseVariable() {
        return baseVariable;
    }

    public ParameterGroup getMatchedGroup() {
        return matchedGroup;
    }

    public String getInitial() {
        return initial;
    }

    public String getMin() {
        return min;
    }

    public String getMax() {
        return max;
    }

    public String getCycle() {
        return cycle;
    }

    public RegenRule getRegenRule() {
        return regenRule;
    }

    public String getRegenRaw() {
        return regenRaw;
    }

    // 委托方法
    public String getKey() {
        return baseVariable.getKey();
    }

    public String getName() {
        return baseVariable.getName();
    }

    public String getScope() {
        return baseVariable.getScope();
    }

    public ValueType getValueType() {
        return baseVariable.getValueType();
    }

    public VariableType getVariableType() {
        return baseVariable.getVariableType();
    }

    public Limitations getLimitations() {
        return baseVariable.getLimitations();
    }

    public boolean hasRegenRule() {
        return regenRule != null && !regenRule.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EffectiveParams that = (EffectiveParams) o;
        return Objects.equals(baseVariable, that.baseVariable) &&
                Objects.equals(matchedGroup, that.matchedGroup) &&
                Objects.equals(initial, that.initial) &&
                Objects.equals(min, that.min) &&
                Objects.equals(max, that.max) &&
                Objects.equals(cycle, that.cycle) &&
                Objects.equals(regenRule, that.regenRule) &&
                Objects.equals(regenRaw, that.regenRaw);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseVariable, matchedGroup, initial, min, max, cycle, regenRule, regenRaw);
    }

    @Override
    public String toString() {
        return "EffectiveParams{" +
                "key='" + baseVariable.getKey() + '\'' +
                ", group=" + (matchedGroup != null ? matchedGroup.getName() : "base") +
                '}';
    }
}
