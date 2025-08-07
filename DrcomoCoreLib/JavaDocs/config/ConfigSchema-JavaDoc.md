### `ConfigSchema.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.config.ConfigSchema`
  * **核心职责:** 以函数式方式声明配置结构，并借助 `ConfigValidator` 构建校验规则。

**2. 使用方式 (Usage)**

  * 开发者实现此接口，在 `configure` 方法中调用 `validator.validateXxx()` 声明各配置项及约束。
  * 可配合 `YamlUtil.validateConfig` 方法执行结构校验，获取 `ValidationResult`。

**3. 公共API方法 (Public API Methods)**

  * #### `configure(ConfigValidator validator)`

      * **返回类型:** `void`
      * **功能描述:** 在给定的 `ConfigValidator` 上声明配置项及其校验规则。
      * **参数说明:**
          * `validator` (`ConfigValidator`): 配置校验器实例。

