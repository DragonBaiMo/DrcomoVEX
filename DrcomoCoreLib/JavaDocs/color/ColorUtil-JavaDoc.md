### `ColorUtil.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.color.ColorUtil`
  * **核心职责:** 一个静态工具类，专门用于处理 Minecraft 游戏内的文本颜色代码。
    - 支持传统 `&` 颜色/样式码。
    - 支持 `&#RRGGBB` 十六进制颜色（1.16+ 原生，旧版本自动降级为最接近的传统色）。
    - 支持 CSS 颜色与渐变标签：`<color:...>文本</color>`、`<gradient:c1,c2,...>文本</gradient>`。

**2. 如何实例化 (Initialization)**

  * **核心思想:** 这是一个完全静态的工具类 (`Utility Class`)，所有方法都应通过类名直接调用。它不包含任何状态，因此**不能也不需要**被实例化。
  * **构造函数:** `private ColorUtil()` (私有构造函数，禁止外部实例化)
  * **代码示例:**
    ```java
    // 这是一个静态工具类，请直接通过类名调用其方法。
    // 无需创建实例。

    // 示例：
    String originalText = "&a你好, &#00FF00世界!";
    String translatedText = ColorUtil.translateColors(originalText);
    player.sendMessage(translatedText); // 将发送带有颜色的文本给玩家
    ```

**3. 公共API方法 (Public API Methods)**

  * #### `translateColors(String text)`

      * **返回类型:** `String`
      * **功能描述:** 将包含 `&` 颜色代码和 `&#RRGGBB` 十六进制颜色代码的字符串，翻译成 Minecraft 服务器可以识别并显示的彩色文本。对于不兼容十六进制颜色的旧版服务器，它会优雅地降级为最接近的传统颜色。
      * **参数说明:**
          * `text` (`String`): 包含待转换颜色代码的原始文本。
      * **使用示例:**
        ```java
        String message = "§c警告: &#FFD700您的金币不足！";
        String formattedMessage = ColorUtil.translateColors(message);
        Bukkit.broadcastMessage(formattedMessage);
        ```

      * **支持与标签**：
        - CSS 颜色：`#RGB/#RRGGBB/#RGBA/#RRGGBBAA`、`rgb()/rgba()`、`hsl()/hsla()`、常见命名色（如 `gold`/`rebeccapurple` 等）。
        - 标签语法：
          - 单色：`<color:COLOR>文本</color>`
          - 渐变：`<gradient:C1,C2[,C3...]>文本</gradient>`（多段线性渐变，逐字符插值）
        - 样式叠加：标签内可使用 `&k &l &m &n &o`，`&r` 重置样式。

      * **版本兼容**：
        - 1.16+：`&#RRGGBB` → `§x§R§R§G§G§B§B` 真彩输出。
        - 旧版：自动降级为最接近的传统色（`§0-§f`）。

      * **性能建议**：
        - 渐变会为每个“可见字符”注入颜色码；请对相对静态的消息在配置加载/重载后预解析并缓存，仅在发送时做占位符替换。
        - 示例（简化）：
          ```java
          Map<String, String> cache = new HashMap<>();
          void rebuild(MessageService ms) {
            for (String k : keys) {
              String raw = ms.getRaw(k);
              if (raw != null) cache.put(k, ColorUtil.translateColors(raw));
            }
          }
          ```

      * **更多示例**：
        ```java
        // 单色 + 样式
        String s1 = ColorUtil.translateColors("<color:gold>&l史诗&n物品</color>");
        // 多停靠点渐变 + 样式与重置
        String s2 = ColorUtil.translateColors("<gradient:#ff0000,#ffff00,#00ff00>&o欢迎&r!</gradient>");
        // 命名色 + HSL 渐变
        String s3 = ColorUtil.translateColors("<gradient:rebeccapurple, hsl(200,100%,50%)>Hello</gradient>");
        String plain = ColorUtil.stripColorCodes(s2); // 去除所有颜色/样式/标签
        ```
  * #### `stripColorCodes(String text)`

      * **返回类型:** `String`
      * **功能描述:** 从给定的文本中移除所有类型的颜色代码（包括 `&`、`§` 以及 `&#RRGGBB` 格式），返回一个纯净、不带任何颜色格式的字符串。
      * **参数说明:**
          * `text` (`String`): 包含颜色代码的原始文本。
      * **使用示例:**
        ```java
        String coloredLore = "§a生命值: §c+10";
        String plainLore = ColorUtil.stripColorCodes(coloredLore);
        // plainLore 将会是 "生命值: +10"
        ```
      * **说明**：会移除 `&/§/&#RRGGBB` 颜色码以及 `<color>`/`<gradient>` 标签本体，保留纯文本。

  * #### `initMajorVersion(Server server)`

      * **返回类型:** `void`
      * **功能描述:** 在插件 `onEnable()` 阶段调用一次，解析并缓存 Bukkit 主版本号（如 `1.18.2` → `18`），避免在颜色转换过程中重复解析，降低运行时开销。
      * **参数说明:**
          * `server` (`org.bukkit.Server`): 当前服务器实例。
      * **使用示例:**
        ```java
        @Override
        public void onEnable() {
            ColorUtil.initMajorVersion(getServer());
        }
        ```
      * **性能提示**：避免在运行时多次解析版本号；建议仅在插件启动或版本切换后调用一次。
