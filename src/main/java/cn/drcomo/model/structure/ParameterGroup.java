package cn.drcomo.model.structure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 变量参数组定义
 *
 * 用于在匹配特定条件时覆盖变量的初始值、约束和周期配置。
 * 按 priority 降序排序，优先级越高的组先应用。
 */
public class ParameterGroup implements Comparable<ParameterGroup> {

    private final String name;
    private final int priority;
    private final List<String> conditions;
    private final String initial;
    private final String min;
    private final String max;
    private final String cycle;
    private final RegenRule regenRule;
    private final String regenRaw;

    private ParameterGroup(Builder builder) {
        this.name = builder.name;
        this.priority = builder.priority;
        if (builder.conditions == null) {
            this.conditions = Collections.emptyList();
        } else {
            this.conditions = Collections.unmodifiableList(new ArrayList<>(builder.conditions));
        }
        this.initial = builder.initial;
        this.min = builder.min;
        this.max = builder.max;
        this.cycle = builder.cycle;
        this.regenRule = builder.regenRule;
        this.regenRaw = builder.regenRaw;
    }

    public String getName() {
        return name;
    }

    public int getPriority() {
        return priority;
    }

    public List<String> getConditions() {
        return conditions;
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

    /**
     * 是否存在任何覆盖参数。
     */
    public boolean hasAnyOverride() {
        return initial != null || min != null || max != null || cycle != null || regenRule != null || regenRaw != null;
    }

    /**
     * 是否配置了条件门控。
     */
    public boolean hasConditions() {
        return conditions != null && !conditions.isEmpty();
    }

    @Override
    public int compareTo(ParameterGroup other) {
        if (other == null) {
            return -1;
        }
        int cmp = Integer.compare(other.priority, this.priority);
        if (cmp != 0) {
            return cmp;
        }
        cmp = compareNullable(this.name, other.name);
        if (cmp != 0) {
            return cmp;
        }
        cmp = compareNullable(this.initial, other.initial);
        if (cmp != 0) {
            return cmp;
        }
        cmp = compareNullable(this.min, other.min);
        if (cmp != 0) {
            return cmp;
        }
        cmp = compareNullable(this.max, other.max);
        if (cmp != 0) {
            return cmp;
        }
        cmp = compareNullable(this.cycle, other.cycle);
        if (cmp != 0) {
            return cmp;
        }
        cmp = compareNullable(this.regenRaw, other.regenRaw);
        if (cmp != 0) {
            return cmp;
        }
        return Integer.compare(this.conditions.hashCode(), other.conditions.hashCode());
    }

    private int compareNullable(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ParameterGroup that = (ParameterGroup) o;
        return priority == that.priority &&
                Objects.equals(name, that.name) &&
                Objects.equals(conditions, that.conditions) &&
                Objects.equals(initial, that.initial) &&
                Objects.equals(min, that.min) &&
                Objects.equals(max, that.max) &&
                Objects.equals(cycle, that.cycle) &&
                Objects.equals(regenRule, that.regenRule) &&
                Objects.equals(regenRaw, that.regenRaw);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, priority, conditions, initial, min, max, cycle, regenRule, regenRaw);
    }

    @Override
    public String toString() {
        return "ParameterGroup{" +
                "name='" + name + '\'' +
                ", priority=" + priority +
                ", conditions=" + conditions +
                '}';
    }

    /**
     * 参数组构建器
     */
    public static class Builder {
        private String name;
        private int priority = 0;
        private List<String> conditions;
        private String initial;
        private String min;
        private String max;
        private String cycle;
        private RegenRule regenRule;
        private String regenRaw;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder conditions(List<String> conditions) {
            if (conditions == null) {
                this.conditions = null;
            } else {
                this.conditions = new ArrayList<>(conditions);
            }
            return this;
        }

        public Builder addCondition(String condition) {
            if (condition == null) {
                return this;
            }
            if (this.conditions == null) {
                this.conditions = new ArrayList<>();
            }
            this.conditions.add(condition);
            return this;
        }

        public Builder initial(String initial) {
            this.initial = initial;
            return this;
        }

        public Builder min(String min) {
            this.min = min;
            return this;
        }

        public Builder max(String max) {
            this.max = max;
            return this;
        }

        public Builder cycle(String cycle) {
            this.cycle = cycle;
            return this;
        }

        public Builder regen(RegenRule regenRule, String raw) {
            this.regenRule = regenRule;
            this.regenRaw = raw;
            return this;
        }

        public ParameterGroup build() {
            return new ParameterGroup(this);
        }
    }
}
