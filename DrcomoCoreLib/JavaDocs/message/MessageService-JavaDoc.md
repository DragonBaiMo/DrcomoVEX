### `MessageService.java`

**1. 概述 (Overview)**

* **完整路径:** `cn.drcomo.corelib.message.MessageService`

* **核心职责:**
  MessageService 是插件内所有文本消息的集中式“大脑”，解决以下痛点：

  1. **本地化与多语言支持：** 从 YAML 语言文件加载键值（可热重载/切换），全量缓存在内存以提升性能。
  2. **多层占位符解析：** 支持三类替换体系（顺序区分）：

     * **Java 格式化（`String.format` / `{}` 顺序占位符）：** 仅在显式调用含格式参数的接口（如 `get(key, args...)`、`sendChat`/`sendActionBar` 的 `{}` 替换）时生效；
     * **自定义 / 内部 / 正则 / PlaceholderAPI 链式替换：** 由 `parseWithDelimiter` 系列驱动，按顺序依次处理，并最终执行颜色翻译。
  3. **颜色统一转换：** 所有对玩家的输出路径都强制经过 `ColorUtil.translateColors`，外部不需要手动做颜色替换（`&` 会转为 `§`，已有 `§` 保留）。
  4. **多渠道发送：** 聊天、ActionBar、Title/SubTitle、广播（含权限过滤）、上下文聚合后分发。
  5. **上下文消息缓存与语义分流：** 支持暂存成功/失败/其它上下文相关消息，再按渠道集中发送并自动清理。

* **设计准则（实现隐含体现）：**

  * 公共 API 保持稳定，内部实现可重构。
  * 占位符链路分层清晰，扩展点明确（custom → internal → regex → PlaceholderAPI → 颜色）。
  * 输出颜色覆盖无遗漏。
  * 日志用于追踪与降级，不破坏调用流程。

* **线程安全边界说明：**
  当前使用的是非线程安全的数据结构（`HashMap` / `ArrayList`），因此：

  * 语言文件刷新（`reloadLanguages` / `switchLanguage`）应在主线程执行。
  * 并发读取（如异步发送）时要避免同时进行写操作（reload），调用方需自行同步或在调度层加锁。
  * 若未来需要并发安全，可考虑替换为 `ConcurrentHashMap` 或加显式锁。

---

**2. 如何实例化 (Initialization)**

#### 核心思想

MessageService 不是孤立组件，依赖日志、YAML 配置加载器和 PlaceholderAPI 封装，需按顺序准备后组合构造。

#### 构造函数签名

`public MessageService(Plugin plugin, DebugUtil logger, YamlUtil yamlUtil, PlaceholderAPIUtil placeholderUtil, String langConfigPath, String keyPrefix)`

#### 参数说明

* `plugin`：插件主类实例，当前未直接使用但保留以便未来扩展。
* `logger`：用于输出 info/warn/error 供排查。
* `yamlUtil`：语言文件加载与访问封装。
* `placeholderUtil`：PlaceholderAPI 解析器接口封装（链尾替换 `%…%` 类型占位符）。
* `langConfigPath`：语言文件路径，不包括 `.yml` 后缀（例如 `"languages/zh_CN"` 对应 `languages/zh_CN.yml`）。
* `keyPrefix`：自动附加到查找 key 之前的前缀（`null` 会归一为空字符串）。如果传入的 key 已有该前缀，则不会重复拼接（见 `resolveKey` 逻辑）。

#### 初始化流程

1. 通过 `yamlUtil.loadConfig(langConfigPath)` 载入文件。
2. 遍历所有字符串键，将其缓存到 `messages`。
3. 后续所有 `get`/`parse` 等操作基于内存缓存进行，避免每次 I/O。

#### 示例代码

```java
DebugUtil logger       = new DebugUtil(this, DebugUtil.LogLevel.INFO);
YamlUtil yamlUtil      = new YamlUtil(this, logger);
PlaceholderAPIUtil papi = new PlaceholderAPIUtil(this, "example");

MessageService messageService = new MessageService(
    this,
    logger,
    yamlUtil,
    papi,
    "languages/zh_CN",
    "messages.example."
);

messageService.registerInternalPlaceholder("online", (player, args) ->
    String.valueOf(Bukkit.getOnlinePlayers().size())
);
```

#### 常见误用 & 注意点

* `langConfigPath` 不能带 `.yml` 后缀。
* 修改语言文件后必须调用 `reloadLanguages()` 才生效；否则内存仍使用旧缓存。
* `keyPrefix` 如果已存在于 key 中，则不会重复添加（例如 prefix=`"a."` 时 `getRaw("a.foo")` 实查 `"a.foo"`，`getRaw("foo")` 实查 `"a.foo"`）。

---

**3. 占位符解析机制（内部细节）**

#### 总体区分

有两套不同替换路径容易混淆：

* **格式化替换（Java `String.format` / `{}` 占位符）：** 只在 `get(key, args...)`、`sendChat`、`sendActionBar` 等显式使用顺序占位符的方法里生效，不包括 `parseWithDelimiter` 默认链路。
* **占位符链（custom → internal → regex → PlaceholderAPI → 颜色）：** 由 `parseWithDelimiter` 及其调用链处理，Java `%`/`{}` 不是自动发生，若需两者混合需显式组合。

#### 完整替换链路（`processPlaceholdersWithDelimiter` 的实际顺序）

1. **自定义占位符替换（prefix/suffix + custom map）**：例如传入前后缀 `%`，`%foo%` 会用 custom map 中 `foo` 替换。
2. **内部占位符 `{key[:args]}`**：使用 `internalPlaceholderPattern`（默认 `\{([a-zA-Z0-9_]+)(?::([^}]*))?\}`）匹配，调用注册的 resolver（key 小写化）。
3. **额外正则规则（extraPlaceholderRules）**：按注册顺序逐条替换，结果再给下一条规则使用（链式）。
4. **PlaceholderAPI 替换**：最终替换 `%player_name%` 等依赖上下文的占位符。
5. **颜色翻译**：调用方在输出前会统一走 `ColorUtil.translateColors`。

> 注意：`String.format`（如 `%s`）不会在这条链上自动执行；若模板需要同时做 Java 格式化和占位符链，需手动前置进行格式化再给 parse 进一步处理。

#### 内部占位符注册

* 方法：`registerInternalPlaceholder(String key, BiFunction<Player, String, String> resolver)`
* 说明：

  * key 不带大括号，内部统一小写匹配。
  * resolver 接收玩家（可能为 null）和冒号后的 args。
  * 例如：`{time:HH:mm}` 可用 key=`"time"`，resolver 负责按 args 格式化时间。

#### 额外正则规则注册

* 方法：`addPlaceholderRule(Pattern pattern, BiFunction<Player, Matcher, String> resolver)`
* 说明：

  * 支持任意复杂模式（如 `{{something}}`）。
  * 替换按注册顺序进行，后续规则看到的是前一个规则替换后的内容。
  * 冲突示例：规则 A 把 `{{x}}` 变成 `{{y}}`，规则 B 又匹配 `{{y}}` → 最终结果由 A+B 链式决定，注册顺序影响输出。

#### 自定义前后缀

* 方法：`parseWithDelimiter(...)`
* 说明：可指定任意 prefix/suffix（如 `%`、`{}`、`<<>>` 等）控制 custom 替换语法。
* 兼容旧接口：`parse(...)` 等价于 `parseWithDelimiter(..., "%", "%")`，已标记为 deprecated。

#### 容错与降级

* 缺失 key / 没注册的 internal placeholder / regex 没匹配到不会抛异常，而是保留原文。
* 格式化失败、缺失内容会通过 logger 输出 warn/error，但不会中断上层逻辑。

---

**4. 主要公共 API 方法**

#### 语言与前缀控制

  * #### `reloadLanguages()`

      * **返回类型:** `void`
      * **功能描述:** 从磁盘重新加载当前语言文件（由 `langConfigPath` 指定），并刷新内存中的消息缓存。此操作会先清空旧缓存，再从文件加载新内容。
      * **调用建议:** 当语言文件在外部被修改后，或需要通过指令热重载配置时调用。建议在主线程执行以避免线程安全问题。

  * #### `switchLanguage(String newPath)`

      * **返回类型:** `void`
      * **功能描述:** 动态切换到另一个语言文件。该方法会更新内部的 `langConfigPath`，然后自动调用 `reloadLanguages()` 来加载新文件。
      * **参数说明:**
          * `newPath` (`String`): 新的语言文件路径，相对于插件数据文件夹，且**不**包含 `.yml` 后缀（例如 `"languages/en_US"`）。

  * #### `setKeyPrefix(String newPrefix)`

      * **返回类型:** `void`
      * **功能描述:** 动态设置或更改所有消息键（key）的统一前缀。在后续调用 `get`、`send` 等方法时，此前缀会自动拼接到传入的键之前（除非键本身已包含该前缀）。
      * **参数说明:**
          * `newPrefix` (`String`): 新的键前缀。如果传入 `null`，将被视为空字符串 `""`。

#### 获取与解析

  * #### `getRaw(String key)`

      * **返回类型:** `String`
      * **功能描述:** 根据键名获取在语言文件中定义的、未经任何处理的原始字符串。此方法会经过 `resolveKey` 自动添加前缀。
      * **参数说明:**
          * `key` (`String`): 消息键。
      * **返回值:** 找到则返回原始字符串，否则返回 `null` 并记录警告。

  * #### `get(String key, Object... args)`

      * **返回类型:** `String`
      * **功能描述:** 获取原始字符串后，使用 `String.format` 进行 Java 风格的格式化（替换 `%s`, `%d` 等）。此方法**不**执行占位符链解析。
      * **参数说明:**
          * `key` (`String`): 消息键。
          * `args` (`Object...`): 用于格式化字符串的可变参数。
      * **返回值:** 格式化后的字符串。若原始消息未找到，返回错误提示；若格式化失败，返回原始字符串并记录错误。

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

  * #### `broadcastList(String key, Map<String, String> custom, String permission)`

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

---

**5. 行为细节与建议**

#### 前缀自动补全逻辑

`resolveKey` 会判断传入的 key 是否已经以 prefix 开头，避免重复拼接，从而稳健处理 `keyPrefix` 边界。

#### 日志策略

* 缺失 key：在 `getRaw` 中 `messages.get(...)` 失败会 `logger.warn("未找到原始消息，键: " + actual)` 并返回 null。
* 格式化失败：`get(String, ...)` 捕获 `IllegalFormatException`，记录 error 并退回原始未格式化字符串。
* 空列表：`getList` 返回空会 warn 提示可能是 key 写错或文件缺失。
* 解析链路异常不会抛出，仅会在对应 resolver 内部导致结果保持原样（调用方安全无崩溃）。

#### 占位符/规则冲突处理

* 替换顺序为：custom → internal placeholder → extra regex → PlaceholderAPI → 颜色。
* extra regex 是链式作用，后注册规则看到的输入包括前面规则替换后的内容，注册顺序决定最终覆盖路径，需谨慎设计避免意外叠加。
* internal placeholder 与 regex 可能产生交叠，建议在复杂情况中在 resolver 里加防重逻辑或使用更精确 pattern。

#### 调用推荐（语义用法区分）

* 想用语言键直接消息：`send(player, key)`。
* 需要自定义变量但不依赖 language file：`send(player, template, custom, prefix, suffix)`。
* 简单格式化：`sendChat(player, "Hello {}!", name)`。
* 多行权限化广播：`broadcastList(key, custom, permission)`。
* 临时短提示：ActionBar 相关方法根据来源（key / template / `{}`）选用对应重载。
* Title + SubTitle 复杂组合使用带 custom 或 `{}` 形式的重载。

#### 性能考量

* 语言内容全量缓存，读取 O(1)。
* 占位符 resolver 本身如果执行昂贵计算（I/O / 大量逻辑）需要在 resolver 内做缓存或限流。
* 上下文缓存避免外部重复 loop，优先用内置的 `storeMessage*` + `sendContext*` 组合。

#### 容错语义汇总

* `getRaw`：缺失返回 null。
* `get`：格式化异常降级返回原始文本。
* `parseWithDelimiter`：缺失 key 返回 null。
* 发送接口遇到 null 结果大多数静默 skip（内部有 null 检查）。
* 非 `Player` 传入需要 `Player` 上下文的方法时会传入 null，后续占位符解析会在尽量退化下执行（如 PlaceholderAPI 可能无法解析特定上下文占位符）。

#### 上下文流别名说明

`sendContextFailures` / `sendContextSuccesses` 是语义分化 alias，当前行为一致，用于上层逻辑区分错误/成功流，但保持同一实现以便未来在样式上差异化扩展而不改调用方。