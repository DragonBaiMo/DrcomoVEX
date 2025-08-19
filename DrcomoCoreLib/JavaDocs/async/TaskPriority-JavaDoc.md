### `TaskPriority.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.async.TaskPriority`
  * **核心职责:** 定义异步任务的优先级，数值越小优先级越高。

**2. 枚举值说明 (Enum Constants)**

  * `HIGH` - 高优先级任务，优先执行。
  * `NORMAL` - 普通优先级任务。
  * `LOW` - 低优先级任务，最后执行。

**3. 公共API方法 (Public API Methods)**

  * #### `getLevel()`
      * **返回类型:** `int`
      * **功能描述:** 获取内部优先级数值，数值越小表示优先级越高。

**4. 典型用法 (Typical Usage)**

  * 配合 `AsyncTaskManager` 提交带优先级的任务：
    ```java
    asyncTaskManager.runWithPriority(() -> logger.info("高优先级任务"), TaskPriority.HIGH);
    ```

