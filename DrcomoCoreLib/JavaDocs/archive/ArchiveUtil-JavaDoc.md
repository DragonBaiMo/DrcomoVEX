### `ArchiveUtil.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.archive.ArchiveUtil`
  * **核心职责:** 提供文件/目录压缩、解压，按日期归档以及旧归档清理等实用方法。
  * **依赖注入:** 构造时需要传入 `DebugUtil` 以输出日志。

**2. 如何实例化 (Initialization)**

  * **核心思想:** 这是一个可实例化的工具类，允许不同插件根据自身需求定制日志级别。
  * **构造函数:** `public ArchiveUtil(DebugUtil logger)`
  * **代码示例:**
    ```java
    DebugUtil logger = new DebugUtil(plugin, DebugUtil.LogLevel.INFO);
    ArchiveUtil archive = new ArchiveUtil(logger);
    ```

**3. 公共API方法 (Public API Methods)**

  * #### `void compress(String sourcePath, String targetZipPath)`

    * **功能描述:** 使用默认级别将文件或目录压缩为 ZIP 文件。
    * **参数说明:**
        * `sourcePath` — 待压缩的文件或目录。
        * `targetZipPath` — 压缩后生成的 ZIP 文件路径。

  * #### `void compress(String sourcePath, String targetZipPath, int level)`

    * **功能描述:** 指定压缩级别进行 ZIP 压缩，级别范围 `-1~9`。
    * **参数说明:**
        * `sourcePath` — 待压缩的文件或目录。
        * `targetZipPath` — 压缩后生成的 ZIP 文件路径。
        * `level` — 压缩级别，`-1` 为默认级别。

  * #### `void extract(String zipPath, String destDir)`

    * **功能描述:** 解压 ZIP 文件到指定目录，如目录不存在会自动创建。
    * **参数说明:**
        * `zipPath` — ZIP 文件路径。
        * `destDir` — 解压目标目录。

  * #### `String archiveByDate(String sourcePath, String archiveDir)`

    * **功能描述:** 以当前日期和时间为文件名压缩指定路径，生成形如 `yyyyMMdd-HHmmss.zip` 的归档文件。
    * **参数说明:**
        * `sourcePath` — 待归档的文件或目录。
        * `archiveDir` — 存放归档文件的目录。
    * **返回值:** 成功时返回生成的 ZIP 路径，失败时返回 `null`。

  * #### `void cleanupOldArchives(String archiveDir, int days)`

    * **功能描述:** 删除指定目录下超过给定天数的 `.zip` 文件，仅针对普通文件生效。
    * **参数说明:**
        * `archiveDir` — 归档目录。
        * `days` — 保留天数，过旧文件将被删除。

  * #### `String formatFileSize(long size)`

    * **功能描述:** 将字节数转换为更易读的格式，如 `1.5 MB`。
    * **参数说明:**
        * `size` — 字节数。
    * **返回值:** 格式化后的字符串。

**4. 典型工作流程 (Typical Workflows)**

  * **备份并清理旧文件**
    ```java
    ArchiveUtil util = new ArchiveUtil(logger);
    // 压缩插件数据文件夹
    String zip = util.archiveByDate("plugins/MyPlugin/data", "backups");
    // 删除30天前的旧备份
    util.cleanupOldArchives("backups", 30);
    // 手动指定压缩级别
    util.compress("logs/latest.log", "logs.zip", 9);
    ```

  * **解压归档进行恢复**
    ```java
    util.extract(zip, "plugins/MyPlugin/data-restore");
    ```
