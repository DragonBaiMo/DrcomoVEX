**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.database.DatabaseBridge`
  * **核心职责:** 统一封装 MySQL 数据源创建与批量写入逻辑，提供安全的 `REPLACE INTO` 批量同步能力，避免子插件重复配置 HikariCP。

**2. 如何实例化 (Initialization)**

  * **核心思想:** 该类为有状态的服务对象，需要注入 `DebugUtil` 以统一日志输出。
  * **构造函数:** `public DatabaseBridge(DebugUtil debugUtil)`
  * **代码示例:**
    ```java
    DebugUtil logger = new DebugUtil(this, DebugUtil.LogLevel.INFO);
    DatabaseBridge bridge = new DatabaseBridge(logger);

    YamlConfiguration mysqlConfig = new YamlConfiguration();
    mysqlConfig.set("host", "127.0.0.1");
    mysqlConfig.set("port", 3306);
    mysqlConfig.set("database", "player_data");
    mysqlConfig.set("username", "sync_user");
    mysqlConfig.set("password", "secret");

    YamlConfiguration hikari = new YamlConfiguration();
    hikari.set("maximumPoolSize", 5);
    mysqlConfig.set("hikari", hikari);

    bridge.createMysqlDataSource(mysqlConfig);
    ```

**3. 公共API方法 (Public API Methods)**

  * #### `HikariDataSource createMysqlDataSource(YamlConfiguration config)`

      * **返回类型:** `HikariDataSource`
      * **功能描述:** 根据 YAML 配置创建或替换 MySQL 数据源，内部自动读取连接信息、池化参数与数据源属性。
      * **参数说明:**
          * `config` (`YamlConfiguration`): 必填项，至少包含 `host`、`port`、`database`、`username` 字段，可选传入 `password`、`jdbc-parameters`、`hikari` 等扩展配置。

  * #### `int batchReplace(String table, List<Map<String, Object>> rows)`

      * **返回类型:** `int`
      * **功能描述:** 使用预编译的 `REPLACE INTO` 语句批量写入数据，自动根据传入的列名集合生成 SQL 并防止 SQL 注入。
      * **参数说明:**
          * `table` (`String`): 目标表名，可包含 `schema.table` 形式的限定前缀。
          * `rows` (`List<Map<String, Object>>`): 需要写入的数据行集合，键为列名，值为列数据。

  * #### `void close()`

      * **返回类型:** `void`
      * **功能描述:** 关闭并释放内部持有的 Hikari 数据源。
      * **参数说明:** 无。

  * #### `HikariDataSource getDataSource()`

      * **返回类型:** `HikariDataSource`
      * **功能描述:** 获取当前持有的数据源实例，便于在高级场景下执行自定义操作；若尚未创建则返回 `null`。
      * **参数说明:** 无。

**4. 使用建议 (Usage Notes)**

  * 调用 `batchReplace` 前应确保至少调用一次 `createMysqlDataSource` 成功初始化数据源。
  * 列名与表名仅允许字母、数字或下划线，库名和表名可通过 `schema.table` 形式传入。
  * 批量写入完成后可根据需要调用 `close()` 释放连接池资源，例如在插件关闭阶段。
