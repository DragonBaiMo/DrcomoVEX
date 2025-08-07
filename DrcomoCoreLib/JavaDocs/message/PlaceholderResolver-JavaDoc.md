### `PlaceholderResolver.java`

**1. 概述 (Overview)**

* **完整路径:** `cn.drcomo.corelib.message.PlaceholderResolver`
* **核心职责:** 作为函数式接口处理形如 `{key[:args]}` 的内部占位符，按冒号切分参数并返回替换结果。

**2. 公共API方法 (Public API Methods)**

* #### `String resolve(Player player, String[] args)`

    * **功能描述:** 根据当前玩家和参数数组解析占位符。
    * **参数说明:**
        * `player` (`Player`): 当前玩家，可为 `null`。
        * `args` (`String[]`): 通过冒号分隔得到的参数列表，若无参数则为空数组。
    * **返回值:** 解析后的字符串。

**3. 使用示例 (Usage Example)**

```java
messageService.registerInternalPlaceholder("online", (p, a) ->
    String.valueOf(Bukkit.getOnlinePlayers().size())
);
```
