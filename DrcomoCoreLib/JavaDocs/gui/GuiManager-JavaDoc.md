### `GuiManager.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.gui.GuiManager`
  * **核心职责:** 提供与 GUI 操作相关的辅助能力，包括危险点击检测、光标清理以及安全播放音效；

**2. 如何实例化 (Initialization)**

  * **核心思想:** 这是一个*可实例化*的服务类，每个子插件应为其创建独立实例，并通过构造函数注入 `DebugUtil` 以获得符合自身日志级别的输出。
  * **构造函数:** `public GuiManager(DebugUtil logger)`
  * **代码示例:**
    ```java
    // 在你的子插件 onEnable() 方法中
    DebugUtil logger = new DebugUtil(this, DebugUtil.LogLevel.DEBUG);

    GuiManager guiManager = new GuiManager(logger);
    ```

**3. 公共API方法 (Public API Methods)**

  * #### `void clearCursor(Player player, InventoryClickEvent event)`

      * **功能描述:** 清空玩家鼠标光标上的物品堆并更新背包视图；内部已做**热路径优化**，仅当光标确有物品时才执行清空与刷新，避免不必要的 UI 同步成本；若出现异常将记录到日志。
      * **参数说明:**
          * `player` (`Player`): 目标玩家。
          * `event` (`InventoryClickEvent`): 原始事件对象。

  * #### `void safePlaySound(Player player, Sound sound, float volume, float pitch)`

      * **功能描述:** 在指定玩家位置播放音效；如播放失败（版本兼容或其他异常），异常将被捕获并记录。
      * **参数说明:**
          * `player` (`Player`): 目标玩家。
          * `sound` (`Sound`): 音效枚举。
          * `volume` (`float`): 音量 (0.0 ~ 1.0)。
          * `pitch` (`float`): 音调 (0.5 ~ 2.0)。

**4. 注意事项 (Cautions)**
  * **光标清理边界：**
    - 清理光标物品时应先判断业务场景，避免误删玩家重要物品。
    - 建议只在自定义 GUI 区域内调用 clearCursor，玩家背包操作应放行。

  * **危险点击检测局限：**
    - isDangerousClick 仅基于 ClickType 判断，部分特殊操作需业务层二次校验。
    - 对于复杂交互（如拖拽、批量操作），建议结合事件类型和业务逻辑综合判断。

  * **批量物品处理建议：**
    - 大量物品操作建议分批处理，避免主线程卡顿。
    - 物品发放失败时应有兜底逻辑（如自动掉落）。

  * **与业务层协作：**
    - GuiManager 只做通用处理，具体业务规则应由调用方实现。

  * **跨版本兼容：**
    - 不同 Minecraft / Bukkit 版本的 `ClickType` 枚举可能变动，升级核心库或 Spigot 版本后，应复核 `isDangerousClick` 判定规则。

**5. 性能隐患与优化建议 (Performance Risks & Optimization)**

- **高频事件**：背包事件与移动事件并列为顶级高频，禁止在主线程执行 O(n²) 搜索或 NBT 深拷贝。将静态常量（如允许操作的 material 列表）缓存到 `static final Set<Material>`，避免每次事件重建。
- **同步 vs. 异步**：所有 Bukkit API 的物品栈修改必须在主线程。若业务逻辑需异步（数据库 / HTTP），请先复制轻量数据异步处理，再把结果 `Bukkit.getScheduler().runTask(plugin, () -> {...})` 回主线程写入物品。
- **反射调用开销**：反射仅用于初始化时探测 & 缓存 `MethodHandle`，运行期用缓存对象调用。

**6. 统一拦截建议（与 ClickTypeUtil/InventoryAction 配合）**
- 在 GUI 入口统一拦截以下高风险操作：
  - Shift-Click、数字键（HOTBAR_SWAP/HOTBAR_MOVE_AND_READD）、COLLECT_TO_CURSOR、MOVE_TO_OTHER_INVENTORY
  - 拖拽（InventoryDragEvent）
  - Drop/Control-Drop、副手交换
- 鼠标光标上的物品堆命中危险交互时应立刻调用 `clearCursor(player, event)`，避免残留造成数据错乱或复制漏洞。
