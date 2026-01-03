### `YamlUtil.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.config.YamlUtil`
  * **核心职责:** 一个强大的 YAML 配置文件管理工具类。它封装了 Bukkit 插件开发中与 `*.yml` 文件交互的几乎所有常见操作，包括：自动创建插件数据文件夹、从 JAR 中复制默认配置、加载/重载/保存配置、以及提供一系列带默认值的便捷读取方法，并集成了详细的日志记录。

**2. 如何实例化 (Initialization)**

  * **核心思想:** `YamlUtil` 被设计为每个插件持有一个实例。它需要 `Plugin` 实例来定位数据文件夹和资源，需要 `DebugUtil` 实例来输出操作日志。一旦实例化，它就可以管理该插件的所有 `.yml` 配置文件。
  * **构造函数:** `public YamlUtil(Plugin plugin, DebugUtil logger)`
  * **代码示例:**
    ```java
    // 在你的子插件 onEnable() 方法中:
    Plugin myPlugin = this;
    DebugUtil myLogger = new DebugUtil(myPlugin, DebugUtil.LogLevel.INFO);

    // 实例化 YamlUtil
    YamlUtil yamlUtil = new YamlUtil(myPlugin, myLogger);

    // --- 使用 YamlUtil ---

    // 1. 确保某个子目录存在
    yamlUtil.ensureDirectory("playerdata");

    // 2. 从 JAR 包的 resources 目录下复制默认配置
    // 假设你的 JAR 中有 /resources/config.yml 和 /resources/messages.yml
    // 这行代码会将它们复制到 /plugins/YourPlugin/ 目录下（如果文件尚不存在）
    myYamlUtil.copyDefaults("", ""); // 第一个参数是JAR内目录，第二个是插件数据文件夹内目录

    // 3. 加载一个配置文件到内存缓存
    yamlUtil.loadConfig("config"); // 加载 config.yml

    // 4. 读取配置项（如果不存在，会使用默认值写入并保存）
    boolean featureEnabled = yamlUtil.getBoolean("config", "features.auto-heal.enabled", true);
    String welcomeMessage = yamlUtil.getString("messages", "welcome", "&a欢迎您, %player_name%!");

    myLogger.info("YAML 配置工具已初始化，并加载了默认配置。");
    ```

**3. 公共API方法 (Public API Methods)**

  * #### `ensureFolderAndCopyDefaults(String resourceFolder, String relativePath, String... excludedNames)`

      * **返回类型:** `void`
      * **功能描述:** 若插件数据文件夹内某目标目录不存在，则创建该目录，并从 JAR 内指定资源文件夹复制其全部文件及层级结构到该目录，实现一次性批量初始化。此方法会动态判断目标文件夹是否存在：若不存在，则拷贝整个文件夹内容；若已存在，则保持不变。默认会跳过 `plugin.yml` 与所有 `.sql` 文件，可通过 `excludedNames` 追加排除项。通常用于插件首次加载时的资源初始化。
      * **参数说明:**
          * `resourceFolder` (`String`): JAR 内资源文件夹路径，例如 `"templates"` 或 `"assets/lang"`。
          * `relativePath` (`String`): 数据文件夹内目标目录，相对插件根目录，空字符串表示根目录。
          * `excludedNames` (`String...`): 额外需要排除的文件名。

  * #### `ensureDirectory(String relativePath)`

      * **返回类型:** `void`
      * **功能描述:** 确保在插件的数据文件夹下，指定的相对路径目录存在。如果不存在，会自动创建。
      * **参数说明:**
          * `relativePath` (`String`): 相对于插件数据文件夹的路径，例如 `"data"` 或 `"logs/archive"`。

  * #### `copyDefaults(String resourceFolder, String relativePath)`

      * **返回类型:** `void`
      * **功能描述:** 从插件 JAR 内指定的资源文件夹（包含其子目录）复制所有 `.yml` 文件到插件数据文件夹中的目标目录，并保留原有目录层级。仅当目标文件不存在时才会执行复制。
      * **参数说明:**
          * `resourceFolder` (`String`): JAR 包内的源文件夹路径（如 `"config"`，空字符串表示 JAR 根目录）。
          * `relativePath` (`String`): 插件数据文件夹内的目标文件夹路径，可为空字符串表示插件根目录。

  * #### `copyYamlFile(String resourcePath, String relativePath)`

      * **返回类型:** `void`
      * **功能描述:** 复制插件 JAR 内指定的单个 `.yml` 文件到插件数据文件夹的目标目录，若目标文件已存在则跳过。
      * **参数说明:**
          * `resourcePath` (`String`): 资源文件在 JAR 内的完整路径，例如 `"config/example.yml"`。
          * `relativePath` (`String`): 插件数据文件夹内的目标目录，相对插件根目录，空字符串表示根目录。

  * #### `loadConfig(String fileName)`

      * **返回类型:** `void`
      * **功能描述:** 加载一个指定的 `.yml` 文件，并将其内容解析为一个 `YamlConfiguration` 对象，缓存在内存中。
      * **参数说明:**
          * `fileName` (`String`): 文件名，**不**包含 `.yml` 后缀。

  * #### `loadAllConfigsInFolder(String folderPath)`

      * **返回类型:** `Map<String, YamlConfiguration>`
      * **功能描述:** 扫描指定目录下的所有 `.yml` 文件并逐个加载，返回的映射以文件名为键，`YamlConfiguration` 为值，同时写入内部缓存。
      * **参数说明:**
          * `folderPath` (`String`): 相对于插件数据文件夹的目录路径。
      * **返回值:** `Map<文件名, 配置对象>`

  * #### `reloadConfig(String fileName)`

      * **返回类型:** `void`
      * **功能描述:** 从磁盘重新加载指定的配置文件，覆盖内存中的旧缓存。
      * **参数说明:**
          * `fileName` (`String`): 文件名（不含.yml）。

  * #### `saveConfig(String fileName, boolean force)`

      * **返回类型:** `void`
      * **功能描述:** 将内存中缓存的指定配置对象，保存回磁盘上的 `.yml` 文件。如果 `force` 为 `false`（或调用无参版本 `saveConfig(fileName)`），则仅在配置被修改过（即“脏”状态）时才会保存。
      * **参数说明:**
          * `fileName` (`String`): 文件名（不含.yml）。
          * `force` (`boolean`): 是否强制保存，无视“脏”状态。

  * #### `saveAllDirtyConfigs()`

      * **返回类型:** `void`
      * **功能描述:** 将所有在内存中被修改过（“脏”）的配置文件一次性全部保存到磁盘。**强烈建议在插件的 `onDisable` 方法中调用此方法**，以确保所有更改都得到持久化。

  * #### `getConfig(String fileName)`

      * **返回类型:** `YamlConfiguration`
      * **功能描述:** 获取一个已加载的 `YamlConfiguration` 实例。如果该配置尚未被加载，此方法会先自动调用 `loadConfig`。
      * **参数说明:**
          * `fileName` (`String`): 文件名（不含.yml）。

  * #### `getString(String fileName, String path, String def)`

      * **返回类型:** `String`
      * **功能描述:** 从指定配置文件中读取一个字符串。如果路径不存在，会将 `def`（默认值）写入**内存中的配置**，并将该配置标记为“脏”状态，然后返回 `def`。注意：此操作**不会**立即保存文件。
      * **参数说明:**
          * `fileName` (`String`): 文件名。
          * `path` (`String`): YAML 中的路径，例如 `"database.host"`。
          * `def` (`String`): 默认值。

  * #### `getInt`, `getBoolean`, `getDouble`, `getLong`, `getStringList`

      * **功能描述:** 与 `getString` 类似，分别用于读取整数、布尔值、双精度浮点数、长整数和字符串列表。当路径不存在时，它们都会将默认值写入内存并标记配置为“脏”，但不会立即保存文件。

  * #### `setValue(String fileName, String path, Object value)`

      * **返回类型:** `void`
      * **功能描述:** 在指定的配置中设置一个路径的值。此操作仅修改内存中的配置并将其标记为“脏”，**不会**立即保存到磁盘。
      * **参数说明:**
          * `fileName` (`String`): 文件名。
          * `path` (`String`): 路径。
          * `value` (`Object`): 要设置的值。

  * #### `contains(String fileName, String path)`

      * **返回类型:** `boolean`
      * **功能描述:** 检查指定配置中是否包含某个路径。
      * **参数说明:**
          * `fileName` (`String`): 文件名。
          * `path` (`String`): 路径。

  * #### `getKeys(String fileName, String path)`

      * **返回类型:** `Set<String>`
      * **功能描述:** 获取指定路径下的所有直接子节点的键（keys）。
      * **参数说明:**
          * `fileName` (`String`): 文件名。
          * `path` (`String`): 路径。

  * #### `getSection(String fileName, String path)`

      * **返回类型:** `ConfigurationSection`
      * **功能描述:** 获取指定路径对应的整个配置节（`ConfigurationSection`）。
      * **参数说明:**
          * `fileName` (`String`): 文件名。
          * `path` (`String`): 路径。

  * #### `setDefaults(String configKey, Map<String, Object> defaults)`

      * **返回类型:** `void`
      * **功能描述:** 为指定配置文件批量写入默认值，仅在路径不存在时才会设置对应的默认值。
      * **参数说明:**
          * `configKey` (`String`): 配置文件名（不含 `.yml`）。
          * `defaults` (`Map<String, Object>`): 键为配置路径、值为默认值的映射。

  * #### `validateConfig(String configKey, ConfigSchema schema)`

      * **返回类型:** `ValidationResult`
      * **功能描述:** 使用 `ConfigSchema` 声明的规则对指定配置文件进行结构校验，返回包含错误列表的 `ValidationResult`。
      * **参数说明:**
          * `configKey` (`String`): 配置文件名（不含 `.yml`）。
          * `schema` (`ConfigSchema`): 配置结构声明接口，实现后在其中配置校验规则。

* #### `watchConfig(String configName, Consumer<YamlConfiguration> onChange)`

    * **返回类型:** `YamlUtil.ConfigWatchHandle`
    * **功能描述:** 使用高效的共享后台线程监听配置文件变更。当文件内容被修改时，会自动在服务器主线程中重载该文件并安全地执行回调函数。
    * **参数说明:**
        * `configName` (`String`): 文件名（不含 `.yml`）。
        * `onChange` (`Consumer<YamlConfiguration>`): 变更后的回调，参数为最新配置。

  * **代码示例：**

    ```java
    // 开始监听配置文件
    YamlUtil.ConfigWatchHandle handle =
        yamlUtil.watchConfig("config", cfg -> logger.info("配置已更新"));

    // 在插件关闭或不再需要时停止监听
    handle.close();
    ```

  * #### `enableFileWatcher(String configKey, FileChangeListener listener)`

      * **返回类型:** `void`
      * **功能描述:** 启用指定配置文件的变更监听，内部复用 `watchConfig` 并在文件修改时回调 `FileChangeListener`。
      * **参数说明:**
          * `configKey` (`String`): 配置文件名（不含 `.yml`）。
          * `listener` (`FileChangeListener`): 变更回调，提供变更类型与最新配置。

  * #### `disableFileWatcher(String configKey)`

      * **返回类型:** `void`
      * **功能描述:** 关闭指定配置文件的监听，内部调用 `stopWatching` 清理资源。
      * **参数说明:**
          * `configKey` (`String`): 配置文件名（不含 `.yml`）。

  * #### `stopAllWatches()`

      * **返回类型:** `void`
      * **功能描述:** 关闭并清理所有由 `watchConfig` 创建的监听器，并同时清空内
        部 JAR 条目缓存。**已由 `close()` 方法涵盖，建议在插件卸载时直接调用 `close()`。**

  * #### `close()`

      * **返回类型:** `void`
      * **功能描述:** 关闭 `WatchService` 并中断、等待监听线程退出，同时清理内
        部缓存。**必须在插件 `onDisable()` 调用**，否则可能导致线程与资源泄漏。

  * #### `clearJarCache()`

      * **返回类型:** `void`
      * **功能描述:** 主动清空 JAR 内目录条目缓存，释放内存。若未使用监听功
        能，也可在插件卸载时单独调用。

  * #### `getValue(String path, Class<T> type, T defaultValue)`

      * **返回类型:** `<T>`
      * **功能描述:** 从默认 `config.yml` 中按给定类型读取值。若路径不存在或类型不符，会写入并返回 `defaultValue`。
      * **参数说明:**
          * `path` (`String`): 配置路径。
          * `type` (`Class<T>`): 期望的类型，例如 `String.class`。
          * `defaultValue` (`T`): 默认值。


**4. 与消息颜色预解析协同（最佳实践）**

  * 要点：颜色/渐变解析较耗时，应前移到“配置重载/监听回调”阶段完成一次性预解析。
  * 做法：挑选相对静态的键，`ColorUtil.translateColors(...)` 后写入业务侧缓存；发送阶段仅做占位符替换。
  * 收益：降低高频发送的 CPU 开销。

```java
// 极简思路：变更→重载→回调内重建缓存
Map<String, String> cache = new HashMap<>();
void rebuild(MessageService ms, List<String> keys) {
  cache.clear();
  for (String k : keys) {
    String raw = ms.getRaw(k);
    if (raw != null) cache.put(k, ColorUtil.translateColors(raw));
  }
}
yamlUtil.watchConfig("languages/zh_CN", cfg -> rebuild(messageService, keys));
// 发送：papi.replace(player, cache.getOrDefault(key, messageService.getRaw(key)));
```