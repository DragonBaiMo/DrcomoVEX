package cn.drcomo.managers.components;

import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.model.structure.EffectiveParams;
import cn.drcomo.model.structure.ParameterGroup;
import cn.drcomo.model.structure.Variable;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.function.BiFunction;

/**
 * 参数组解析器
 *
 * 负责根据玩家上下文评估条件，返回匹配的参数组或有效参数。
 */
public class ParameterGroupResolver {

    private final DebugUtil logger;

    // 条件评估器（复用 RefactoredVariablesManager 中的逻辑）
    private final BiFunction<OfflinePlayer, List<String>, Boolean> conditionEvaluator;

    public ParameterGroupResolver(DebugUtil logger,
                                   BiFunction<OfflinePlayer, List<String>, Boolean> conditionEvaluator) {
        this.logger = logger;
        this.conditionEvaluator = conditionEvaluator;
    }

    /**
     * 解析变量的有效参数
     *
     * @param variable 变量定义
     * @param player   玩家上下文（全局变量可为 null）
     * @return 有效参数对象
     */
    public EffectiveParams resolve(Variable variable, OfflinePlayer player) {
        if (variable == null) {
            return null;
        }

        // 无参数组，直接返回基础配置
        if (!variable.hasGroups()) {
            return EffectiveParams.fromBase(variable);
        }

        // 按优先级顺序检查组（Variable 构造时已排序）
        ParameterGroup matchedGroup = null;
        for (ParameterGroup group : variable.getGroups()) {
            if (evaluateGroupConditions(player, group)) {
                matchedGroup = group;
                logger.debug("变量 " + variable.getKey() + " 匹配到参数组: "
                    + group.getName() + " (priority=" + group.getPriority() + ")");
                break;  // 第一个匹配即生效
            }
        }

        return EffectiveParams.from(variable, matchedGroup);
    }

    /**
     * 评估参数组的条件
     */
    private boolean evaluateGroupConditions(OfflinePlayer player, ParameterGroup group) {
        if (!group.hasConditions()) {
            // 无条件，默认匹配
            return true;
        }

        try {
            return conditionEvaluator.apply(player, group.getConditions());
        } catch (Exception e) {
            logger.warn("参数组条件评估异常: " + group.getName() + " - " + e.getMessage());
            return false;
        }
    }

    /**
     * 快速检查变量是否有任何参数组
     */
    public boolean hasGroups(Variable variable) {
        return variable != null && variable.hasGroups();
    }
}
