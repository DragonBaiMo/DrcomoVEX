### `SkullUtil.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.util.SkullUtil`
  * **核心职责:** 根据 URL 或 base64 字符串生成带自定义纹理的玩家頭顱 `ItemStack`。内部封装 `GameProfile`、`SkullMeta` 等实现细节，并通过 `DebugUtil` 输出异常日志。

**2. 如何实例化 (Initialization)**

  * **核心思想:** 该类无状态，构造时只需注入 `DebugUtil` 以便记录日志，可在多个模块中复用。
  * **代码示例:**
    ```java
    DebugUtil logger = new DebugUtil(this, DebugUtil.LogLevel.INFO);
    SkullUtil skullUtil = new SkullUtil(logger);
    ```

**3. 公共API方法 (Public API Methods)**

  * #### `ItemStack fromUrl(String url)`
      * **功能描述:** 传入纹理 URL，返回带有该纹理的玩家头颅物品。
      * **参数说明:** `url` (`String`): 形如 `http://textures.minecraft.net/texture/...` 的地址。
      * **返回类型:** `ItemStack` - 带自定义纹理的玩家头颅物品，若 URL 为空或异常则返回普通玩家头颅。

  * #### `ItemStack fromBase64(String base64)`
      * **功能描述:** 传入已编码的纹理 Base64 字符串，返回玩家头颅物品。
      * **参数说明:** `base64` (`String`): 已编码的纹理 Base64 字符串。
      * **返回类型:** `ItemStack` - 带自定义纹理的玩家头颅物品，若处理失败则返回普通玩家头颅。

**4. 创建自定义头像示例 (Usage Example)**

```java
String url = "http://textures.minecraft.net/texture/<texture-id>";
ItemStack skull = skullUtil.fromUrl(url);
player.getInventory().addItem(skull);
```
