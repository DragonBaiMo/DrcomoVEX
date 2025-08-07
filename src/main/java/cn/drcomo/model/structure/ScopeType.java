package cn.drcomo.model.structure;

/**
 * 变量作用域类型枚举
 * <p>
 * 用于区分变量是玩家作用域还是全局作用域。
 */
public enum ScopeType {
    /** 玩家作用域变量 */
    PLAYER,
    /** 全局作用域变量 */
    GLOBAL
}
