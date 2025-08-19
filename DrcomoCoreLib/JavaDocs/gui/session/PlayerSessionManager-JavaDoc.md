### `PlayerSessionManager.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.gui.session.PlayerSessionManager`
  * **核心职责:** 为玩家创建、检索和销毁自定义会话数据，并在超时或玩家离线时自动清理。

**2. 如何实例化 (Initialization)**

  * **核心思想:** 管理器在构造时注入 `Plugin` 与 `DebugUtil`，并自动注册 `PlayerQuitEvent` 监听。
  * **构造函数 1:** `new PlayerSessionManager(plugin, debug)` 使用默认五分钟超时。
  * **构造函数 2:** `new PlayerSessionManager(plugin, debug, timeout)` 自定义超时时长（毫秒，<=0 表示永不过期）。

**3. 公共API方法 (Public API Methods)**

  * #### `void createSession(Player player, T data)`
      * **功能描述:** 为玩家创建或覆盖会话数据。
  * #### `T getSession(Player player)`
      * **返回类型:** `T`
      * **功能描述:** 取得玩家会话数据，不存在或已过期时返回 `null`。
  * #### `void destroySession(Player player)`
      * **功能描述:** 主动移除玩家的会话记录。
  * #### `boolean hasSession(Player player)`
      * **返回类型:** `boolean`
      * **功能描述:** 判断玩家是否拥有活跃会话。
  * #### `void setSessionTimeout(long sessionTimeout)`
      * **功能描述:** 动态调整会话的全局超时时长。

**4. 注意事项 (Cautions)**

  * 会话超时检查在每次访问或创建时触发，超时<=0则不会自动清理。
  * 管理器自动监听 `PlayerQuitEvent`，玩家离线会立即移除其会话。
