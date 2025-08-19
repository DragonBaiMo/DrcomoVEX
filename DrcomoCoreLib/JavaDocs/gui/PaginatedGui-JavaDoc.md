### `PaginatedGui.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.gui.PaginatedGui`
  * **核心职责:** 提供可分页 GUI 的基础逻辑，包括页数计算、导航槽位和页面渲染钩子，
    并通过 `GUISessionManager` 与 `GuiActionDispatcher` 自动处理会话与事件分派。

**2. 如何实例化 (Initialization)**

  * **核心思想:** 该类是抽象基类，子类需实现页面创建和渲染逻辑。
    构造函数需要注入 `GUISessionManager` 与 `GuiActionDispatcher`，并指定每页大小
    及上一页/下一页按钮的槽位。
  * **构造函数:**
    ```java
    public PaginatedGui(GUISessionManager sessions,
                        GuiActionDispatcher dispatcher,
                        int pageSize,
                        int prevSlot,
                        int nextSlot)
    ```

**3. 公共API方法 (Public API Methods)**

  * #### `open(Player player, String sessionId)`
      * **功能描述:** 创建并打开会话，渲染第一页并注册翻页按钮的点击回调。
      * **参数说明:**
          * `player` (`Player`): 目标玩家。
          * `sessionId` (`String`): 会话标识。

  * #### `showPage(Player player, int page)`
      * **功能描述:** 渲染指定页码的内容，若超出范围将被忽略。
      * **参数说明:**
          * `player` (`Player`): 目标玩家。
          * `page` (`int`): 页码，从 0 开始。

  * #### `showNext(Player player)` / `showPrev(Player player)`
      * **功能描述:** 分别跳转到下一页或上一页。

  * #### `close(Player player)`
      * **功能描述:** 注销翻页回调并关闭会话。

  * #### `getPageCount()` / `getCurrentPage()`
      * **功能描述:** 获取总页数或当前页码。

**4. 扩展点 (Hooks)**

  * `protected abstract int getTotalItemCount(Player player);`
    - 计算条目总数以确定页数。
  * `protected abstract Inventory createInventory(Player player);`
    - 创建用于显示的 `Inventory` 实例。
  * `protected abstract void renderPage(Player player, Inventory inv, int page, int totalPages);`
    - 在指定页码中布置物品或其他元素。

**5. 使用示例 (Usage Example)**

```java
public class MyPaginatedGui extends PaginatedGui {
    public MyPaginatedGui(GUISessionManager s, GuiActionDispatcher d) {
        super(s, d, 45, 45, 53); // 每页45格，45和53为导航按钮槽位
    }

    @Override
    protected int getTotalItemCount(Player p) {
        return getItemsFor(p).size();
    }

    @Override
    protected Inventory createInventory(Player p) {
        return Bukkit.createInventory(p, 54, "My Items");
    }

    @Override
    protected void renderPage(Player p, Inventory inv, int page, int total) {
        List<ItemStack> items = getItemsFor(p);
        int from = page * getPageSize();
        for (int i = 0; i < getPageSize(); i++) {
            int index = from + i;
            inv.setItem(i, index < items.size() ? items.get(index) : null);
        }
    }
}
```
