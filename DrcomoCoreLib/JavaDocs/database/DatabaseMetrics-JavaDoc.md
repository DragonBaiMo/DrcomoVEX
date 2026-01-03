### `DatabaseMetrics.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.database.DatabaseMetrics`
  * **核心职责:** 记录 SQLite 数据库执行统计信息，包含连接借出次数、语句执行总数、累计耗时以及预编译语句缓存命中次数。

**2. 如何实例化 (Initialization)**

  * **核心思想:** 由 `SQLiteDB#getMetrics()` 生成并返回，开发者通常直接消费此对象的只读数据。必要时也可通过公共构造函数手动创建。
  * **构造函数:** `public DatabaseMetrics(long borrowedConnections, long executedStatements, long totalExecutionTimeMillis, long statementCacheHits)`
  * **代码示例:**
    ```java
    DatabaseMetrics metrics = db.getMetrics();
    long avg = metrics.getAverageExecutionTimeMillis();
    ```

**3. 公共API方法 (Public API Methods)**

  * #### `getBorrowedConnections()`
      * **返回类型:** `long`
      * **功能描述:** 获取连接被借出的累计次数。
  * #### `getExecutedStatements()`
      * **返回类型:** `long`
      * **功能描述:** 获取执行过的 SQL 语句数量。
  * #### `getTotalExecutionTimeMillis()`
      * **返回类型:** `long`
      * **功能描述:** 获取所有语句执行的累计耗时（毫秒）。
  * #### `getStatementCacheHits()`
      * **返回类型:** `long`
      * **功能描述:** 获取预编译语句缓存命中次数。
  * #### `getAverageExecutionTimeMillis()`
      * **返回类型:** `long`
      * **功能描述:** 计算并返回单条语句的平均执行耗时（毫秒）。
