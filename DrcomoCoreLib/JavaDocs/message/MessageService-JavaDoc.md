### `MessageService.java`

**1. 概述 (Overview)**

**完整路径:** `cn.drcomo.corelib.message.MessageService`

**核心职责:**
`MessageService` 是插件内所有文本消息的集中式“大脑”，负责本地化、多语言支持、占位符解析、颜色转换、多渠道发送、上下文消息缓存等一整套统一的消息处理逻辑，解决以下常见痛点：

1. **本地化与多语言支持**

   * 从 YAML 语言文件加载键值（支持热重载/切换语言文件）。
   * 全量缓存到内存中，保证读取性能为 O(1)。
   * 语言文件路径按需指定，可结合 `keyPrefix` 自动补全键前缀，防止重复书写。

2. **多层占位符解析（链路清晰，顺序明确）**

   * **格式化替换（Java `String.format` / `{}` 顺序占位符）**：仅在显式调用含格式参数的方法（如 `get(key, args...)`、`sendChat` 等）中执行。
   * **占位符链替换（custom → internal → extra regex → PlaceholderAPI → 颜色）**：由 `parseWithDelimiter` 系列方法驱动，按顺序依次处理并最终执行颜色翻译。

     * **custom**：自定义占位符，支持任意前后缀分隔符（默认 `%` 与 `%`）。
     * **internal**：内部占位符 `{key[:args]}`，通过注册 resolver 扩展。
     * **extra regex**：额外正则替换规则，按注册顺序链式作用。
     * **PlaceholderAPI**：解析 `%player_name%` 等上下文相关占位符。
     * **颜色**：统一调用 `ColorUtil.translateColors` 转换 `&` 颜色符为 `§`。

3. **颜色统一转换与预解析缓存（性能优化）**

   * 所有玩家输出路径都强制颜色转换，外部调用无需手动处理。
   * **默认启用颜色预解析缓存**：在语言文件加载/重载时自动预解析所有颜色标签（`&c`、`&#RRGGBB`、`<gradient:...>` 等）并缓存为 `§` 格式，运行期直接使用缓存，避免重复解析高开销的渐变色标签。
   * 可通过 `setEnableColorPrecaching(false)` 禁用预解析（仅在极特殊场景需要动态颜色时使用）。

4. **多渠道发送**

   * 聊天、ActionBar、Title/SubTitle、广播（支持权限过滤）、上下文聚合分发。
   * 发送 API 会在异步调用时自动切回主线程，保障 Bukkit API 安全。

5. **上下文消息缓存与语义分流**

   * 支持缓存成功流与失败流的消息（`sendContextSuccesses` / `sendContextFailures`），调用后统一发送。
   * 语义分流 API 当前实现一致，便于未来扩展样式而不修改调用方。

6. **日志与容错策略**

   * 缺失 key、格式化失败、空列表、解析异常等情况均会记录日志（warn/error），但不会抛出异常中断调用。
   * 解析失败或缺失内容会退回原文或 null，调用方可安全处理。

7. **性能考量**

   * 全量缓存语言内容，避免频繁 I/O。
   * 如果 resolver 执行昂贵计算（I/O、大量逻辑），建议在 resolver 内自行缓存或限流。
   * 建议使用内置的 `storeMessage*` + `sendContext*` 组合，减少重复循环。

8. **线程安全**

   * 内部数据结构（`HashMap` / `ArrayList`）非线程安全。
   * 多线程环境下读取与写入（如 reload）需上层调度控制，避免竞争。
   * 主线程安全已在发送 API 内保障，但语言文件 reload 建议在主线程执行。
   * 如需并发安全，可考虑 `ConcurrentHashMap` 或加锁。

---

**2. 如何实例化 (Initialization)**

**核心思想:**
`MessageService` 依赖多个组件（日志、YAML 配置加载器、PlaceholderAPI 封装），需要按顺序准备好并组合构造，才能正常工作。

**构造函数签名:**

```java
public MessageService(
    Plugin plugin,
    DebugUtil logger,
    YamlUtil yamlUtil,
    PlaceholderAPIUtil placeholderUtil,
    String langConfigPath,
    String keyPrefix
)
```

**参数说明:**

* `plugin`：插件主类实例，当前未直接使用但保留作扩展用途。
* `logger`：调试与日志输出工具，记录 info/warn/error。
* `yamlUtil`：YAML 文件加载与访问工具，负责语言文件的解析与监听。
* `placeholderUtil`：PlaceholderAPI 封装工具，处理 `%...%` 类上下文占位符。
* `langConfigPath`：语言文件路径（不带 `.yml` 后缀），如 `"languages/zh_CN"` 对应 `languages/zh_CN.yml`。
* `keyPrefix`：查找 key 前自动拼接的前缀，null 会归一为空字符串；`resolveKey` 会检测避免重复拼接。

**初始化流程:**

1. 使用 `yamlUtil.loadConfig(langConfigPath)` 加载语言文件。
2. 将所有键值对缓存到内存中的 `messages`。
3. 所有消息获取与解析方法直接基于内存缓存执行，避免运行时 I/O。

**示例：**

```java
DebugUtil logger        = new DebugUtil(this, DebugUtil.LogLevel.INFO);
YamlUtil yamlUtil       = new YamlUtil(this, logger);
PlaceholderAPIUtil papi = new PlaceholderAPIUtil(this, "example");

MessageService ms = new MessageService(
    this,
    logger,
    yamlUtil,
    papi,
    "languages/zh_CN",
    "messages.example."
);

ms.registerInternalPlaceholder("online", (player, args) ->
    String.valueOf(Bukkit.getOnlinePlayers().size())
);
```

**常见误用与注意点:**

* `langConfigPath` 不可带 `.yml` 后缀。
* 修改语言文件后需调用 `reloadLanguages()` 才能应用新内容。
* 如果 `keyPrefix` 已存在于传入 key 中，不会重复添加。

---

**3. 占位符解析机制（内部细节）**

**解析链路总览：**
占位符替换分为两大类路径：

1. **格式化替换（Java 原生格式化）**
   * 在 `get(key, args...)`、`sendChat`、`sendActionBar` 等显式方法中调用。
   * 使用 `%s` 或 `{}` 顺序占位符，替换逻辑与 Java 标准一致。

2. **占位符链替换（自定义链路）**
   * 由 `parseWithDelimiter` 及其衍生方法驱动，按以下顺序执行：
     1. **Custom 占位符替换**：基于传入的 prefix/suffix（默认 `%...%`）在 custom map 中替换。
     2. **Internal 占位符替换**：`{key[:args]}` 格式，匹配后调用已注册的 resolver。
     3. **Extra Regex 替换**：用户注册的正则规则链式执行，后注册的规则处理前规则的结果。
     4. **PlaceholderAPI 替换**：解析 Bukkit 上下文相关的 `%...%` 占位符。
     5. **颜色解析**：调用 `ColorUtil.translateColors` 处理颜色符。

**占位符注册与管理:**

* **内部占位符注册**

  ```java
  registerInternalPlaceholder(String key, PlaceholderResolver resolver)
  ```

  * key 小写匹配，不带 `{}`。
  * resolver 参数为 `(Player, String[] args)`。
  * 未注册的占位符会原样保留。

* **额外正则规则注册**

  ```java
  addPlaceholderRule(Pattern pattern, BiFunction<Player, Matcher, String> resolver)
  ```

  * 可匹配任意模式（如 `{{var}}`）。
  * 替换顺序严格按注册顺序执行。
  * 需注意链式效果可能造成二次替换或冲突。

* **自定义分隔符**
  * 默认分隔符为 `%` 与 `%`，可通过 `setDefaultCustomDelimiters("{", "}")` 等方法修改。
  * 修改后相关方法（如 `parseList`、`broadcast` 等）会使用新的默认分隔符。

**行为细节与建议:**
* **前缀自动补全逻辑**
  * `resolveKey` 检查传入 key 是否已包含 `keyPrefix`，防止重复拼接。

* **日志策略**
  * 缺失 key：`getRaw` 日志 warn 并返回 null。
  * 格式化失败：捕获 `IllegalFormatException`，error 日志并返回未格式化字符串。
  * 空列表：`getList` 返回空时 warn，提示可能 key 写错或文件缺失。

* **占位符/规则冲突处理**
  * 替换顺序为 custom → internal → regex → PlaceholderAPI → 颜色。
  * Extra regex 为链式，注册顺序影响最终结果。
  * Internal placeholder 与 regex 可能交叠，复杂情况建议加防重逻辑或精确 pattern。

* **性能优化建议** **(重点)**
  * 对相对静态的键，建议在配置加载阶段提前调用 `ColorUtil.translateColors` 进行颜色解析，并缓存结果。
  * 占位符 resolver 中涉及昂贵操作应加缓存或限流。

**容错语义：**
* `getRaw`：缺失返回 null。
* `get`：格式化异常降级返回原文。
* `parseWithDelimiter`：缺失 key 返回 null。
* 发送接口：遇到 null 多数会静默跳过。
* 非 Player 调用 Player 上下文方法时，player 参数为 null，占位符解析会退化执行。

---

**4. 主要公共 API 方法**

#### 语言与前缀控制

  * #### `reloadLanguages()`

      * **返回类型:** `void`
      * **功能描述:** 从磁盘重新加载当前语言文件（由 `langConfigPath` 指定），并刷新内存中的消息缓存。此操作会先清空旧缓存，再从文件加载新内容。**如果启用了颜色预解析（默认启用），会自动预解析所有消息的颜色标签并缓存。**
      * **调用建议:** 当语言文件在外部被修改后，或需要通过指令热重载配置时调用。建议在主线程执行以避免线程安全问题。
      * **性能优化:** 默认已启用颜色预解析缓存，重载后无需手动处理颜色转换。

  * #### `switchLanguage(String newPath)`

      * **返回类型:** `void`
      * **功能描述:** 动态切换到另一个语言文件。该方法会更新内部的 `langConfigPath`，然后自动调用 `reloadLanguages()` 来加载新文件。**会自动重新预解析新语言文件的颜色。**
      * **参数说明:**
          * `newPath` (`String`): 新的语言文件路径，相对于插件数据文件夹，且**不**包含 `.yml` 后缀（例如 `"languages/en_US"`）。

  * #### `setKeyPrefix(String newPrefix)`

      * **返回类型:** `void`
      * **功能描述:** 动态设置或更改所有消息键（key）的统一前缀。在后续调用 `get`、`send` 等方法时，此前缀会自动拼接到传入的键之前（除非键本身已包含该前缀）。
      * **参数说明:**
          * `newPrefix` (`String`): 新的键前缀。如果传入 `null`，将被视为空字符串 `""`。

  * #### `setEnableColorPrecaching(boolean enable)`

      * **返回类型:** `void`
      * **功能描述:** 启用或禁用颜色预解析缓存机制。**默认已启用**，在语言文件加载/重载时会预先解析所有消息的颜色标签（`&c`、`&#RRGGBB`、`<gradient:...>` 等）并缓存为 `§` 格式。运行期发送消息时直接使用缓存，避免重复解析高开销的渐变色标签。
      * **参数说明:**
          * `enable` (`boolean`): `true` 启用预解析（默认），`false` 禁用。
      * **使用场景:**
          * 大多数情况保持默认启用即可，可显著提升性能。
          * 仅在极特殊场景（如需要动态生成颜色渐变）时禁用。
      * **性能收益:** 对包含渐变色的消息，可减少 80%+ 的颜色解析开销。

#### 获取与解析

  * #### `getRaw(String key)` 

      * **返回类型:** `String`
      * **功能描述:** 根据键名获取在语言文件中定义的、未经任何处理的原始字符串。此方法会经过 `resolveKey` 自动添加前缀。
      * **参数说明:**
          * `key` (`String`): 消息键。
      * **返回值:** 找到则返回原始字符串（未解析颜色），否则返回 `null` 并记录警告。
      * **注意:** 直接返回原始字符串，未进行任何颜色处理。

  * #### `getRawWithColor(String key)` 

      * **返回类型:** `String`
      * **功能描述:** 根据键名获取消息字符串，**如果启用了颜色预解析（默认启用），则直接返回缓存的已解析颜色的字符串**。性能优于 `getRaw()`。
      * **参数说明:**
          * `key` (`String`): 消息键。
      * **返回值:** 找到则返回字符串（已预解析颜色），否则返回 `null` 并记录警告。
      * **性能优势:** 启用预解析后，渐变色等高开销标签已在配置加载时解析完毕，此方法直接返回缓存结果。

  * #### `get(String key, Object... args)` 

      * **返回类型:** `String`
      * **功能描述:** 获取原始字符串后，使用 `String.format` 进行 Java 风格的格式化（替换 `%s`, `%d` 等）。此方法**不**执行占位符链解析。
      * **参数说明:**
          * `key` (`String`): 消息键。
          * `args` (`Object...`): 用于格式化字符串的可变参数。
      * **返回值:** 格式化后的字符串。若原始消息未找到，返回错误提示；若格式化失败，返回原始字符串并记录错误。
      * **注意:** 直接返回格式化后的字符串，未进行任何颜色处理。

  * #### `getWithColor(String key, Object... args)` 

      * **返回类型:** `String`
      * **功能描述:** 使用 `String.format` 格式化后返回，**支持颜色预解析缓存**。如果启用了预解析，直接使用缓存的已解析颜色的模板字符串。
      * **参数说明:**
          * `key` (`String`): 消息键。
          * `args` (`Object...`): 用于格式化字符串的可变参数。
      * **返回值:** 格式化后的字符串（已预解析颜色）。若原始消息未找到，返回错误提示；若格式化失败，返回原始字符串并记录错误。
      * **性能优势:** 启用预解析后避免重复解析颜色标签。

  * #### `parseWithDelimiter(String key, Player player, Map<String, String> custom, String prefix, String suffix)`

      * **返回类型:** `String`
      * **功能描述:** 获取指定键的消息，并执行完整的占位符解析链（自定义占位符 -> 内部占位符 -> 正则规则 -> PlaceholderAPI），最后进行颜色代码翻译。这是最核心的消息处理方法。
      * **参数说明:**
          * `key` (`String`): 消息键。
          * `player` (`Player`): 消息接收者，用于 PlaceholderAPI 上下文。可为 `null`。
          * `custom` (`Map<String, String>`): 自定义占位符的键值对。
          * `prefix` (`String`): 自定义占位符的前缀，例如 `"%"`。
          * `suffix` (`String`): 自定义占位符的后缀，例如 `"%"`。
      * **返回值:** 完全处理好、可直接发送给玩家的最终字符串。若键不存在则返回 `null`。

  * #### `getList(String key)`

      * **返回类型:** `List<String>`
      * **功能描述:** 从语言文件中获取一个字符串列表，通常用于定义多行消息（如 Hologram、Lore 等）。
      * **参数说明:**
          * `key` (`String`): 消息列表的键。
      * **返回值:** 原始的字符串列表。若键不存在或对应的值不是列表，返回空列表。

  * #### `parseList(String key, Player player, Map<String, String> custom)`

      * **返回类型:** `List<String>`
      * **功能描述:** 获取一个消息列表，并对其中的每一行字符串独立执行完整的占位符解析和颜色翻译。
      * **参数说明:**
          * `key` (`String`): 消息列表的键。
          * `player` (`Player`): 消息接收者上下文。
          * `custom` (`Map<String, String>`): 自定义占位符，作用于列表中的每一行。
      * **返回值:** 解析完成的字符串列表，可直接逐行发送。

#### 发送接口（聊天 / ActionBar / Title）

  * #### `send(CommandSender target, String key, Map<String, String> custom)`

      * **返回类型:** `void`
      * **功能描述:** 解析语言文件中的消息并将其作为聊天消息发送给指定目标（玩家或控制台）。这是最常用的发送方法之一。
      * **参数说明:**
          * `target` (`CommandSender`): 消息接收者。
          * `key` (`String`): 语言文件中的消息键。
          * `custom` (`Map<String, String>`): 自定义占位符键值对。

  * #### `send(Player player, String template, Map<String, String> custom, String prefix, String suffix)`

      * **返回类型:** `void`
      * **功能描述:** 直接使用给定的字符串模板进行解析并发送，不经过语言文件查询。适用于动态生成的消息内容。
      * **参数说明:**
          * `player` (`Player`): 目标玩家。
          * `template` (`String`): 消息模板原文。
          * `custom` (`Map<String, String>`): 自定义占位符。
          * `prefix` (`String`): 占位符前缀。
          * `suffix` (`String`): 占位符后缀。

  * #### `sendChat(Player player, String template, Object... args)`

      * **返回类型:** `void`
      * **功能描述:** 使用 `{}` 作为顺序占位符，对模板进行快速替换后，作为聊天消息发送给玩家。
      * **参数说明:**
          * `player` (`Player`): 目标玩家。
          * `template` (`String`): 含 `{}` 占位符的消息模板。
          * `args` (`Object...`): 按顺序替换 `{}` 的参数。

  * #### `sendActionBar(Player player, String template, ...)`

      * **返回类型:** `void`
      * **功能描述:** 将解析后的消息通过 ActionBar 发送给玩家。提供多个重载版本，分别支持从语言键、直接模板+自定义占位符、直接模板+`{}`顺序占位符三种方式生成内容。
      * **参数说明:** (以 `sendActionBar(Player, String, Map, String, String)` 为例)
          * `player` (`Player`): 目标玩家。
          * `template` (`String`): 消息模板。
          * `custom` (`Map<String, String>`): 自定义占位符。
          * `prefix` (`String`): 占位符前缀。
          * `suffix` (`String`): 占位符后缀。

  * #### `sendTitle(Player player, String titleTemplate, String subTemplate, ...)`

      * **返回类型:** `void`
      * **功能描述:** 将解析后的主标题和副标题通过 Title/SubTitle 的形式发送给玩家。同样提供多个重载版本，支持从语言键、直接模板+自定义占位符、直接模板+`{}`顺序占位符三种方式生成内容。
      * **参数说明:** (以 `sendTitle(Player, String, String, Map, String, String)` 为例)
          * `player` (`Player`): 目标玩家。
          * `titleTemplate` (`String`): 主标题模板。
          * `subTemplate` (`String`): 副标题模板。
          * `custom` (`Map<String, String>`): 自定义占位符（同时作用于主副标题）。
          * `prefix` (`String`): 占位符前缀。
          * `suffix` (`String`): 占位符后缀。

#### 列表 / 批量发送

  * #### `sendList(CommandSender target, String key, Map<String, String> custom)`

      * **返回类型:** `void`
      * **功能描述:** 解析语言文件中一个键对应的消息列表，并将每一行作为单独的聊天消息发送给目标。
      * **参数说明:**
          * `target` (`CommandSender`): 消息接收者。
          * `key` (`String`): 语言文件中的消息列表键。
          * `custom` (`Map<String, String>`): 自定义占位符，作用于列表中的每一行。

  * #### `sendList(CommandSender target, List<String> templates, Map<String, String> custom, String prefix, String suffix)`

      * **返回类型:** `void`
      * **功能描述:** 对一个给定的字符串模板列表进行逐行解析，并将结果作为多行聊天消息发送。
      * **参数说明:**
          * `target` (`CommandSender`): 消息接收者。
          * `templates` (`List<String>`): 原始模板列表。
          * `custom` (`Map<String, String>`): 自定义占位符。
          * `prefix` (`String`): 占位符前缀。
          * `suffix` (`String`): 占位符后缀。

  * #### `sendRaw(CommandSender target, String rawMessage)`

      * **返回类型:** `void`
      * **功能描述:** 发送一条未经任何占位符解析的原始字符串，但依然会进行颜色代码翻译。
      * **参数说明:**
          * `target` (`CommandSender`): 消息接收者。
          * `rawMessage` (`String`): 要发送的原始消息。

  * #### `sendRawList(CommandSender target, List<String> rawMessages)`

      * **返回类型:** `void`
      * **功能描述:** 发送一个原始字符串列表，对每行仅做颜色翻译后逐一发送。
      * **参数说明:**
          * `target` (`CommandSender`): 消息接收者。
          * `rawMessages` (`List<String>`): 要发送的原始消息列表。

#### 广播

  * #### `broadcast(String key, Map<String, String> custom, String permission)`

      * **返回类型:** `void`
      * **功能描述:** 向服务器上的所有玩家（或拥有特定权限的玩家）广播一条消息。消息内容从语言文件获取并解析。
      * **参数说明:**
          * `key` (`String`): 语言文件中的消息键。
          * `custom` (`Map<String, String>`): 自定义占位符。
          * `permission` (`String`): 权限节点。如果提供，则只有拥有此权限的玩家才会收到广播。可为 `null`。

  * #### `broadcast(String template, Map<String, String> custom, String prefix, String suffix, String permission)`

      * **返回类型:** `void`
      * **功能描述:** 使用直接的模板向全服（或部分玩家）广播，不查询语言文件。
      * **参数说明:**
          * `template` (`String`): 消息模板。
          * `custom` (`Map<String, String>`): 自定义占位符。
          * `prefix` (`String`): 占位符前缀。
          * `suffix` (`String`): 占位符后缀。
          * `permission` (`String`): 可选的权限节点。

  * #### `broadcast(String key, Map<String, String> custom, String permission)`
  
      * 行为更新：该方法会使用通过 `setDefaultCustomDelimiters` 配置的默认分隔符对 custom map 进行解析（默认仍为 `%`），兼容旧版本行为并可按需切换到 `{}`。

  * #### `broadcastByKey(String key, Map<String, String> custom, String prefix, String suffix)`

      * **返回类型:** `void`
      * **功能描述:** 使用语言键并指定分隔符解析，然后向全服广播。
      * **参数说明:** 同 `parseWithDelimiter` 的 `prefix/suffix` 规则。

  * #### `broadcastByKey(String key, Map<String, String> custom, String prefix, String suffix, String permission)`

      * **返回类型:** `void`
      * **功能描述:** 使用语言键并指定分隔符解析，然后仅向拥有指定权限的玩家广播。
      * **参数说明:** 同上，并附加 `permission`。

      * **返回类型:** `void`
      * **功能描述:** 广播一个来自语言文件的多行消息列表，每行独立发送。
      * **参数说明:**
          * `key` (`String`): 消息列表的键。
          * `custom` (`Map<String, String>`): 自定义占位符。
          * `permission` (`String`): 可选的权限节点。

#### 上下文消息（聚合 / 分流）

  * #### `storeMessage(Object context, String key, Map<String, String> custom)`

      * **返回类型:** `void`
      * **功能描述:** 将一条解析后的消息暂存到由 `context` 对象标识的缓存区中，而不是立即发送。用于聚合多个步骤产生的消息。
      * **参数说明:**
          * `context` (`Object`): 任意用作上下文标识符的对象（如 `Player` 实例、`UUID` 或自定义命令对象）。
          * `key` (`String`): 消息键。
          * `custom` (`Map<String, String>`): 自定义占位符。

  * #### `storeMessageList(Object context, String key, Map<String, String> custom)`

      * **返回类型:** `void`
      * **功能描述:** 将一个解析后的多行消息列表暂存到上下文缓存中。
      * **参数说明:**
          * `context` (`Object`): 上下文标识符。
          * `key` (`String`): 消息列表的键。
          * `custom` (`Map<String, String>`): 自定义占位符。

  * #### `hasMessages(Object context)`

      * **返回类型:** `boolean`
      * **功能描述:** 检查指定的上下文缓存中是否包含任何待发送的消息。
      * **参数说明:**
          * `context` (`Object`): 上下文标识符。
      * **返回值:** 如果缓存中有消息，则为 `true`，否则为 `false`。

  * #### `countMessages(Object context)`

      * **返回类型:** `int`
      * **功能描述:** 获取指定上下文缓存中的消息数量。
      * **参数说明:**
          * `context` (`Object`): 上下文标识符。
      * **返回值:** 缓存的消息行数。

  * #### `sendContext(Object context, Player player, String channel)`

      * **返回类型:** `void`
      * **功能描述:** 将指定上下文缓存中的所有消息，通过特定渠道（如聊天、ActionBar）一次性发送给玩家，并清空该上下文的缓存。
      * **参数说明:**
          * `context` (`Object`): 上下文标识符。
          * `player` (`Player`): 消息接收者。
          * `channel` (`String`): 发送渠道，支持 `"chat"`, `"actionbar"`, `"title"`。

  * #### `sendContextSuccesses(Object context, Player player)` / `sendContextFailures(Object context, Player player)`

      * **返回类型:** `void`
      * **功能描述:** `sendContext` 的语义化别名，用于在代码逻辑中清晰地标识发送的是成功反馈还是失败反馈。当前它们的实现与 `sendContext(context, player, "chat")` 等价，但为未来扩展不同渠道或样式提供了接口。
      * **参数说明:**
          * `context` (`Object`): 上下文标识符。
          * `player` (`Player`): 消息接收者。

