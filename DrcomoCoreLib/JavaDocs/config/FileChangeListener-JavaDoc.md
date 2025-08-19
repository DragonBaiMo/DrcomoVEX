### `FileChangeListener.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.config.FileChangeListener`
  * **核心职责:** 监听配置文件的创建、修改或删除事件并执行回调。

**2. 典型用法 (Typical Usage)**

  * 通过 `YamlUtil.enableFileWatcher` 注册监听器，实现在配置变更时自动重载或通知。

**3. 公共API方法 (Public API Methods)**

  * #### `onChange(String configKey, FileChangeType type, YamlConfiguration config)`

      * **返回类型:** `void`
      * **功能描述:** 当目标配置文件发生变更时被调用。
      * **参数说明:**
          * `configKey` (`String`): 配置文件名（不含 `.yml`）。
          * `type` (`FileChangeType`): 变更类型。
          * `config` (`YamlConfiguration`): 最新的配置对象。

