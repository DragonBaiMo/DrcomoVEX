### `ConnectionPoolStatus.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.database.ConnectionPoolStatus`
  * **核心职责:** 封装 HikariCP 连接池的当前状态，包括总连接数、活跃连接数和空闲连接数。

**2. 如何实例化 (Initialization)**

  * **核心思想:** 通常由 `SQLiteDB#getPoolStatus()` 创建并返回，调用方一般只需读取其中的数据。如需手动构造，可使用其公共构造函数。
  * **构造函数:** `public ConnectionPoolStatus(int totalConnections, int activeConnections, int idleConnections)`
  * **代码示例:**
    ```java
    ConnectionPoolStatus status = db.getPoolStatus();
    int idle = status.getIdleConnections();
    ```

**3. 公共API方法 (Public API Methods)**

  * #### `getTotalConnections()`
      * **返回类型:** `int`
      * **功能描述:** 获取连接池中总连接数。
  * #### `getActiveConnections()`
      * **返回类型:** `int`
      * **功能描述:** 获取当前被占用的连接数量。
  * #### `getIdleConnections()`
      * **返回类型:** `int`
      * **功能描述:** 获取当前空闲的连接数量。
