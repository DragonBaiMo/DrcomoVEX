### `JsonUtil.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.json.JsonUtil`
  * **核心职责:** 一个封装了 Gson 的通用 JSON 工具类，提供对象与 JSON 字符串的相互转换，以及文件读写、校验与格式化等功能。

**2. 如何实例化 (Initialization)**

  * **核心思想:** 每个插件应创建自己的 `JsonUtil` 实例，通过构造函数注入 `DebugUtil`，可选地传入自定义 `Gson`。
  * **构造函数:** `public JsonUtil(DebugUtil logger)`  
    `public JsonUtil(DebugUtil logger, Gson gson)`
  * **代码示例:**
    ```java
    DebugUtil logger = new DebugUtil(this, DebugUtil.LogLevel.DEBUG);
    JsonUtil jsonUtil = new JsonUtil(logger);

    Map<String, Object> data = Map.of("name", "DrComo", "level", 99);
    Path file = Paths.get(getDataFolder().toString(), "data.json");
    jsonUtil.writeJsonFile(file, data);
    ```

**3. 公共API方法 (Public API Methods)**

  * #### `toJson(Object obj)`
      * **返回类型:** `String`
      * **功能描述:** 将对象序列化为 JSON 字符串。

  * #### `fromJson(String json, Class<T> clazz)`
      * **返回类型:** `<T> T`
      * **功能描述:** 按给定类解析 JSON 字符串。

  * #### `fromJson(String json, TypeToken<T> type)`
      * **返回类型:** `<T> T`
      * **功能描述:** 使用 `TypeToken` 解析带泛型的复杂对象。

  * #### `readJsonFile(Path path, Class<T> clazz)`
      * **返回类型:** `<T> T`
      * **功能描述:** 从文件读取 JSON 并解析成指定类型。

  * #### `readJsonFile(Path path, TypeToken<T> type)`
      * **返回类型:** `<T> T`
      * **功能描述:** 读取文件并按 `TypeToken` 解析。

  * #### `writeJsonFile(Path path, Object obj)`
      * **返回类型:** `void`
      * **功能描述:** 将对象以 JSON 形式写入文件，自动创建缺失的目录。

  * #### `isValidJson(String json)`
      * **返回类型:** `boolean`
      * **功能描述:** 判断字符串是否是合法 JSON。

  * #### `prettyPrint(String json)`
      * **返回类型:** `String`
      * **功能描述:** 以缩进格式返回更易阅读的 JSON 字符串。

**4. 典型用法 (Typical Usage)**

  * 在插件加载时创建 `JsonUtil` 实例，配合其他工具持久化数据；
  * 当解析或写入失败时，通过 `DebugUtil` 输出详细日志，方便排查问题。
