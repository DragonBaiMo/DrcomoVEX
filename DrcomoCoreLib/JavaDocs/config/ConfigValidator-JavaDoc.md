### `ConfigValidator.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.config.ConfigValidator`
  * **核心职责:** 一个通用的配置校验器，允许开发者以链式方式声明配置项的类型、是否必填以及自定义校验规则，最终给出校验结果。

**2. 如何实例化 (Initialization)**

  * **构造函数:** `public ConfigValidator(YamlUtil yamlUtil, DebugUtil logger)`
  * **代码示例:**
    ```java
    YamlUtil yamlUtil = new YamlUtil(plugin, logger);
    yamlUtil.loadConfig("config");
    ConfigValidator validator = new ConfigValidator(yamlUtil, logger);
    ```

**3. 公共API方法 (Public API Methods)**

  * #### `validateString(String path)`

      * **返回类型:** `ValidatorBuilder`
      * **功能描述:** 声明一个字符串类型的配置项，返回的 `ValidatorBuilder` 可继续设置 `required()` 或 `custom()` 约束。
      * **参数说明:**
          * `path` (`String`): 配置路径。

  * #### `validateNumber(String path)`

      * **返回类型:** `ValidatorBuilder`
      * **功能描述:** 声明一个数字类型的配置项，允许校验整数或浮点数字符串。
      * **参数说明:**
          * `path` (`String`): 配置路径。

  * #### `validateEnum(String path, Class<E> enumClass)`

      * **返回类型:** `ValidatorBuilder`
      * **功能描述:** 声明一个枚举类型的配置项，值将按不区分大小写的方式解析为指定枚举。
      * **参数说明:**
          * `path` (`String`): 配置路径。
          * `enumClass` (`Class<E>`): 期望的枚举类型。

  * #### `validate(Configuration config)`

      * **返回类型:** `ValidationResult`
      * **功能描述:** 针对给定的 `Configuration` 执行所有已声明的校验，并返回结果对象，包含是否通过及所有错误信息。
      * **参数说明:**
          * `config` (`Configuration`): 要校验的配置实例。

**4. 完整工作流程 (Complete Workflow)**

```java
// 1. 准备配置和依赖
YamlUtil yamlUtil = new YamlUtil(plugin, logger);
yamlUtil.loadConfig("config");
ConfigValidator validator = new ConfigValidator(yamlUtil, logger);

// 2. 声明校验规则（返回 ValidatorBuilder 进行链式配置）
validator.validateString("server.name").required();
validator.validateNumber("server.port").required()
    .custom(port -> ((Number) port).intValue() > 0, "端口必须大于0");
validator.validateEnum("server.mode", ServerMode.class);

// 3. 执行校验（返回 ValidationResult）
ValidationResult result = validator.validate(yamlUtil.getConfig());

// 4. 处理结果
if (result.isSuccess()) {
    logger.info("配置校验通过");
} else {
    for (String error : result.getErrors()) {
        logger.error("配置错误: " + error);
    }
}
```

**5. 相关类说明 (Related Classes)**

  * **ValidatorBuilder:** 由 `validateXxx()` 方法返回，用于设置具体的校验约束。[查看详细文档](./ValidatorBuilder-JavaDoc.md)
  * **ValidationResult:** 由 `validate()` 方法返回，包含校验结果和错误信息。[查看详细文档](./ValidationResult-JavaDoc.md)
  * **YamlUtil:** 提供配置文件加载功能，ConfigValidator 需要其提供的 Configuration 对象。

