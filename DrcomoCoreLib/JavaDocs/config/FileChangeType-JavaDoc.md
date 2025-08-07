### `FileChangeType.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.config.FileChangeType`
  * **核心职责:** 表示配置文件变更的具体类型。

**2. 枚举常量 (Enum Constants)**

  * `CREATE` —— 文件被创建时触发。
  * `MODIFY` —— 文件内容被修改时触发。
  * `DELETE` —— 文件被删除时触发。

**3. 使用场景 (Usage)**

  * 与 `FileChangeListener` 搭配使用，以区分不同的文件事件类型。

