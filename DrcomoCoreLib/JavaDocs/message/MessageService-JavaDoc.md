### `MessageService.java`

**1. 概述 (Overview)**

* **完整路径:** `cn.drcomo.corelib.message.MessageService`

* **核心职责:**
  MessageService 是插件内所有文本消息的“集中式大脑”。它解决了以下几个痛点：

  1. **本地化与多语言支持**：从 YAML 语言文件加载键值对，可运行时热重载或切换语言。
  2. **多层占位符解析**：支持标准 Java 格式化 (`String.format`)、用户自定义占位符、内部占位符（形如 `{key}` / `{key:args}`）、正则扩展规则、以及 PlaceholderAPI (%…% )。
  3. **颜色处理**：统一通过 `ColorUtil.translateColors` 将 `&` 样式颜色替换成 Minecraft 可识别的 `§` 颜色码，保证所有对玩家输出都是彩色且一致的。
  4. **多渠道发送**：聊天、ActionBar、Title/SubTitle、权限过滤广播、上下文批量缓存后分发。
  5. **上下文消息聚合**：可以暂存一组消息关联某个上下文（如一个操作、一个玩家请求），再集中以指定方式发出（成功/失败、不同渠道）。

* **设计原则（隐含在实现中）：**

  * 公共 API 稳定（方法签名不变），内部实现可重构。
  * 解析链路明确可拓展（顺序固定、扩展点清晰）。
  * 所有发送最终都走颜色翻译，不漏色。
  * 日志充分用于调试，缺失/异常场景可追溯。

* **颜色说明:**
  所有面向外部的发送（包括 `send`、`broadcast`、`sendRaw` 等）都会在底层强制调用 `ColorUtil.translateColors`，**外部不需要自己再做颜色替换**。如果传入的文本已经包含 `§` 也会原样保留；如果使用 `&` 语法会被转换为 `§`。

---

**2. 如何实例化 (Initialization)**

* **核心思想:**
  MessageService 并非独立存在，它依赖日志、YAML 配置和占位符解析的上下游组件。必须先准备好 `DebugUtil`、`YamlUtil`、`PlaceholderAPIUtil`，再组合构造。

* **构造函数签名:**
  `public MessageService(Plugin plugin, DebugUtil logger, YamlUtil yamlUtil, PlaceholderAPIUtil placeholderUtil, String langConfigPath, String keyPrefix)`

* **参数详细说明:**

  * `plugin`：插件主类实例（当前实现未直接用到但保留以便未来扩展）。
  * `logger`：用于输出调试/警告/错误日志的工具，必须已初始化。
  * `yamlUtil`：负责加载 language 配置文件的辅助类。
  * `placeholderUtil`：PlaceholderAPI 解析器接口封装，用于解析 `%plugin_placeholder%` 类占位符。
  * `langConfigPath`：相对于插件数据目录的语言文件路径，**不包含 `.yml` 后缀**。如 `"languages/zh_CN"` 对应的是 `languages/zh_CN.yml`。
  * `keyPrefix`：自动附加在所有查询键前的前缀，可以为空字符串；便于结构化、避免冲突。

* **初始化流程:**

  1. 加载指定路径的 YAML 配置。
  2. 遍历配置中所有字符串键（包含嵌套），缓存至内存 map `messages`。
  3. 后续所有 `get`/`parse` 会基于此缓存并处理。

* **代码示例:**

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

* **常见误用 & 注意点:**

  * `langConfigPath` 传入时不要加 `.yml`。
  * 若语言文件后续替换（编辑内容），必须调用 `reloadLanguages()` 才能生效。
  * `keyPrefix` 与实际 YAML 定义的键位置要对齐，否则可能找不到键（内部会补全前缀，但如果已经带了前缀则不再重复添加）。

---

**3. 占位符解析机制（内部细节）**

* **完整链路（执行顺序）：**

  1. `String.format`（由 `get(String, Object...)` 提供，先处理 `%s` / `%d` 等标准格式）
  2. **自定义占位符替换**（根据调用时传入的 prefix/suffix 与 custom map 替换，如 `{rank}`、`%foo%`）
  3. **内部占位符**（注册的 `{key}` 或 `{key:args}` 会被匹配并调用对应 resolver）
  4. **额外正则规则**（通过 `addPlaceholderRule` 增加的任意正则捕获与替换）
  5. **PlaceholderAPI 解析**（对 `%player_name%` 这类依赖玩家上下文的占位符做最终替换）
  6. **颜色翻译**（`ColorUtil.translateColors` 将 `&` 变成 `§`）

* **内部占位符注册:**
  `registerInternalPlaceholder(String key, BiFunction<Player, String, String> resolver)`

  * `key` 不带大括号，内部会转为小写做匹配（大小写不敏感）。
  * `args` 是 `{key:args}` 中冒号后的部分。
  * 示例：`{time:HH:mm}` 可通过 “time” 解析器拿到格式化时间。

* **扩展规则:**
  `addPlaceholderRule(Pattern pattern, BiFunction<Player, Matcher, String> resolver)`

  * 更自由的模式，比如捕获双大括号 `{{something}}` 或其他复杂逻辑。
  * 每次匹配到时都会调用 resolver 生成替换值。

* **自定义前后缀控制:**
  `parseWithDelimiter` 接受任意 prefix/suffix，例如用 `{}`、`%%`、甚至混合替换不同风格的变量。
  默认弃用方法 `parse(...)` 等价于 `parseWithDelimiter(..., "%", "%")`。

* **容错与日志:**

  * 任何阶段未命中占位符不会抛异常，而是保留原样；
  * 日志会记录格式化失败、缺失 key、解析后结果等，便于调试配置错误。

---

**4. 主要公共 API 方法**

* #### `reloadLanguages()`

  * **返回类型:** `void`
  * **功能描述:** 重新读取当前 `langConfigPath` 指向的 YAML 文件，刷新内存缓存（`messages`）。适用于管理员/指令热重载场景。
  * **副作用:** 清空已有的 messages 缓存并重新填充。
  * **日志:** 记录加载条数与路径。

* #### `switchLanguage(String newPath)`

  * **返回类型:** `void`
  * **功能描述:** 切换语言文件路径到 `newPath`，并立即执行与 `reloadLanguages()` 等价的刷新操作。
  * **使用场景:** 用户切换 UI 语言 / 配置动态切换多语言。

* #### `setKeyPrefix(String newPrefix)`

  * **返回类型:** `void`
  * **功能描述:** 修改后续所有查找 key 时自动追加的前缀，支持传 `null` 清空。
  * **影响:** `getRaw("foo")` 若 prefix 为 `bar.` 则实际查找的是 `bar.foo`（如果原本没加）。

* #### `getRaw(String key)`

  * **返回类型:** `String`
  * **功能描述:** 返回未经占位符解析、格式化或颜色处理的原始配置字符串。
  * **失败情况:** 如果对应键不存在，返回 `null` 并通过 logger.warn 报警。

* #### `get(String key, Object... args)`

  * **返回类型:** `String`
  * **功能描述:** 先获取原始文本，再用 `String.format` 替换 `%s`/`%d` 等格式符。
  * **容错:** 格式化异常会被捕获，返回原始文本并在日志记录错误。

* #### `parseWithDelimiter(String key, Player player, Map<String,String> custom, String prefix, String suffix)`

  * **返回类型:** `String`
  * **功能描述:** 逐层执行占位符替换（如前面所述完整链路），最后统一做颜色翻译并返回可直接发送的字符串。
  * **注意:** 返回值已经包含颜色符 (`§`)，外部直接发给玩家即可。
  * **null 情况:** 如果底层 `get(...)` 失败（键不存在），返回 `null`。

* #### `getList(String key)`

  * **返回类型:** `List<String>`
  * **功能描述:** 直接从 YAML 中获取字符串列表（原始，不解析任何占位符）。
  * **日志:** 若列表为空（可能是键错误或文件未定义），会 warn。

* #### `parseList(String key, Player player, Map<String,String> custom)`

  * **返回类型:** `List<String>`
  * **功能描述:** 遍历 `getList` 得到的每一行，逐行调用内部占位符解析（等价于 `processPlaceholders` + 颜色处理），返回完整的解析列表。

* #### `registerInternalPlaceholder(String key, BiFunction<Player,String,String> resolver)`

  * **返回类型:** `void`
  * **功能描述:** 注册一个内部占位符解析器，后续在文本中出现 `{key}` 或 `{key:args}` 时会由该 resolver 处理。
  * **约定:** `key` 会被统一转为小写用于 lookup，resolver 接收玩家上下文与参数部分。

* #### `setInternalPlaceholderPattern(Pattern pattern)`

  * **返回类型:** `void`
  * **功能描述:** 替换默认内部占位符正则匹配规则，适用于想用不同语法定义内部标记的场景。

* #### `addPlaceholderRule(Pattern pattern, BiFunction<Player,Matcher,String> resolver)`

  * **返回类型:** `void`
  * **功能描述:** 添加一条自定义正则级别的占位符替换规则，优先于 PlaceholderAPI 之前执行。

* #### `send(Player player, String key)`

  * **返回类型:** `void`
  * **功能描述:** 对指定 key 解析（默认 `%...%` 样式、自定义为空），并发送给玩家（聊天）。

* #### `send(CommandSender target, String key, Map<String,String> custom)`

  * **返回类型:** `void`
  * **功能描述:** 通用单行发送接口，支持玩家或控制台、支持自定义占位符。

* #### `sendList(CommandSender target, String key)`

  * **返回类型:** `void`
  * **功能描述:** 发送一组解析后的多行消息（不含自定义占位符）。

* #### `sendList(CommandSender target, String key, Map<String,String> custom)`

  * **返回类型:** `void`
  * **功能描述:** 同上，含自定义占位符。

* #### `broadcast(String key)`

  * **返回类型:** `void`
  * **功能描述:** 向所有在线玩家广播一条消息（不针对单个玩家解析 PlaceholderAPI）。

* #### `broadcast(String key, Map<String,String> custom, String permission)`

  * **返回类型:** `void`
  * **功能描述:** 只对拥有指定权限的玩家广播，消息对每个目标会独立解析（可用 PAPI 等上下文相关占位符）。

* #### `broadcastList(String key, Map<String,String> custom, String permission)`

  * **返回类型:** `void`
  * **功能描述:** 权限过滤的多行广播。

* #### `sendRaw(CommandSender target, String rawMessage)`

  * **返回类型:** `void`
  * **功能描述:** 直接发送文本（未经键查找、占位符或格式化），但仍会做颜色翻译。

* #### `sendRawList(CommandSender target, List<String> rawMessages)`

  * **返回类型:** `void`
  * **功能描述:** 原样多行发送，每行自动颜色翻译。

* #### `sendOptimizedChat(Player player, List<String> messages)`

  * **返回类型:** `void`
  * **功能描述:** 批量聊天（本质调用逐行发送的封装），用于上下文一次性输出多条。

* #### `sendActionBar(Player player, String key, Map<String,String> custom)`

  * **返回类型:** `void`
  * **功能描述:** 解析并通过 ActionBar 显示短消息（会被后续覆盖）。

* #### `sendStagedActionBar(Player player, List<String> messages)`

  * **返回类型:** `void`
  * **功能描述:** 依次快速推送多条 ActionBar 内容，适合短时间内连续提示。

* #### `sendTitle(Player player, String titleKey, String subKey, Map<String,String> custom)`

  * **返回类型:** `void`
  * **功能描述:** 同时发送主标题与副标题，均经过完整解析与颜色转换。

* #### `sendStagedTitle(Player player, List<String> titles, List<String> subtitles)`

  * **返回类型:** `void`
  * **功能描述:** 依次发送多组 Title/SubTitle 组合（两者数量取最小）。

* #### `storeMessage(Object context, String key, Map<String,String> custom)`

  * **返回类型:** `void`
  * **功能描述:** 将单行解析后的消息暂存到某个上下文中，不立即发送。

* #### `storeMessageList(Object context, String key, Map<String,String> custom)`

  * **返回类型:** `void`
  * **功能描述:** 将解析后的多行消息列表缓存到上下文。

* #### `hasMessages(Object context)`

  * **返回类型:** `boolean`
  * **功能描述:** 判断该上下文是否存在未发送消息。

* #### `countMessages(Object context)`

  * **返回类型:** `int`
  * **功能描述:** 返回当前上下文缓存的消息数量。

* #### `sendContext(Object context, Player player, String channel)`

  * **返回类型:** `void`
  * **功能描述:** 将该上下文的缓存按照指定渠道（"chat" / "actionbar" / "title"）发送后清空。

* #### `sendContextFailures(Object context, Player player)` / `sendContextSuccesses(Object context, Player player)`

  * **返回类型:** `void`
  * **功能描述:** 语义分化的调用（实现上等同于 chat 发送），方便上层区分成功/失败流并复用同一上下文缓存。

---

**5. 行为细节与建议**

* **前缀自动补全逻辑:** `resolveKey` 会判断传入 key 是否已包含前缀，避免重复拼接。
* **日志策略:**

  * 缺失 key / 空列表 / 格式化失败 均会写入 `logger.warn` 或 `logger.error`。
  * 成功获取/格式化会在 debug 级别记录（可在调试时开启以追踪消息来源）。
* **占位符冲突处理:**
  自定义与内部、额外规则在顺序上有层次，内部冲突时后注册的不会覆盖已匹配区域（遵循正则替换顺序和查找匹配）。
* **调用推荐:**

  * 若消息模板包含变量建议用 `parseWithDelimiter` 明确 prefix/suffix 与 custom map。
  * 多行消息用 `parseList` + `sendList` 组合，避免单条自己循环。
  * 权限广播优先用带 permission 版本避免无意义的全服遍历。
* **性能考量:**

  * 消息缓存是内存结构（`HashMap` + `List`），频繁调用时推荐复用 `Player` 上下文避免重复解析大量相同内容。
  * 内部占位符/扩展规则中尽量避免做重 I/O、阻塞或复杂计算（可由占位符 resolver 侧做限流或缓存）。