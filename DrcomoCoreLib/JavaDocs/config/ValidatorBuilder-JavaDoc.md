### `ValidatorBuilder.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.config.ValidatorBuilder`
  * **核心职责:** 单个配置项的校验构建器，用于为特定配置项设置校验规则。由 `ConfigValidator` 创建并使用链式调用模式。

**2. 获取方式 (How to Obtain)**

  * ValidatorBuilder 实例通过 `ConfigValidator` 的以下方法获取：
    ```java
    ConfigValidator validator = new ConfigValidator(yamlUtil, logger);
    
    // 获取 ValidatorBuilder 实例
    ValidatorBuilder stringBuilder = validator.validateString("path");
    ValidatorBuilder numberBuilder = validator.validateNumber("path");
    ValidatorBuilder enumBuilder = validator.validateEnum("path", EnumClass.class);
    ```

**3. 公共API方法 (Public API Methods)**

  * #### `required()`

      * **返回类型:** `ValidatorBuilder`
      * **功能描述:** 标记当前配置项为必填项，若缺失则校验失败。
      * **参数说明:** 无。
      * **链式调用:** 可继续调用其他约束方法。

  * #### `custom(Predicate<Object> rule, String message)`

      * **返回类型:** `ValidatorBuilder`
      * **功能描述:** 为当前配置项添加自定义校验规则，当 `rule.test(value)` 返回 `false` 时，校验失败并记录 `message`。
      * **参数说明:**
          * `rule` (`Predicate<Object>`): 自定义断言函数。
          * `message` (`String`): 失败时的提示信息。
      * **链式调用:** 可继续调用其他约束方法。

**4. 使用示例 (Usage Examples)**

```java
ConfigValidator validator = new ConfigValidator(yamlUtil, logger);

// 字符串校验 - 必填 + 自定义规则
validator.validateString("server.name")
    .required()
    .custom(name -> name.toString().length() >= 3, "服务器名称长度不能少于3位");

// 数字校验 - 必填 + 范围检查
validator.validateNumber("server.port")
    .required()
    .custom(port -> {
        int p = ((Number) port).intValue();
        return p > 0 && p <= 65535;
    }, "端口号必须在1-65535范围内");

// 枚举校验 - 仅类型检查
validator.validateEnum("server.mode", ServerMode.class);

// 可选配置 - 仅自定义规则
validator.validateString("database.password")
    .custom(pwd -> pwd.toString().length() >= 8, "密码长度不能少于8位");

// 执行校验
ValidationResult result = validator.validate(config);
```

**5. 设计模式说明 (Design Pattern)**

  * **构建器模式 (Builder Pattern):** ValidatorBuilder 使用构建器模式，允许通过链式调用逐步配置校验规则。
  * **流畅接口 (Fluent Interface):** 所有方法都返回 `ValidatorBuilder` 实例，支持方法链式调用。
  * **延迟执行:** 校验规则在构建时仅保存，实际校验在 `ConfigValidator.validate()` 时执行。
