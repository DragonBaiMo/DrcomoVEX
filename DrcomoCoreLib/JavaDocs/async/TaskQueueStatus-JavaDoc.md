### `TaskQueueStatus.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.async.TaskQueueStatus`
  * **核心职责:** 描述优先级任务队列当前的各优先级任务数量，可用于监控与调试。

**2. 如何实例化 (Initialization)**

  * **核心思想:** 通常通过 `AsyncTaskManager#getQueueStatus()` 获取实例，开发者一般无需手动创建。
  * **构造函数:** `public TaskQueueStatus(int highCount, int normalCount, int lowCount)`
  * **代码示例:**
    ```java
    TaskQueueStatus status = asyncTaskManager.getQueueStatus();
    logger.info("高优先级: " + status.getHighCount());
    ```

**3. 公共API方法 (Public API Methods)**

  * #### `getHighCount()`
      * **返回类型:** `int`
      * **功能描述:** 获取高优先级任务数量。
  * #### `getNormalCount()`
      * **返回类型:** `int`
      * **功能描述:** 获取普通优先级任务数量。
  * #### `getLowCount()`
      * **返回类型:** `int`
      * **功能描述:** 获取低优先级任务数量。
  * #### `getTotal()`
      * **返回类型:** `int`
      * **功能描述:** 获取队列中的任务总数。

**4. 典型用法 (Typical Usage)**

  * 调用 `AsyncTaskManager#getQueueStatus()` 监控优先级队列负载：
    ```java
    TaskQueueStatus status = asyncTaskManager.getQueueStatus();
    logger.debug(status.toString());
    ```

