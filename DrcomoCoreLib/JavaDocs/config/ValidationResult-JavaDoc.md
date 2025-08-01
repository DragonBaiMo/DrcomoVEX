### `ValidationResult.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.config.ValidationResult`
  * **核心职责:** 封装配置校验结果的数据类，包含校验是否成功以及详细的错误信息列表。

**2. 获取方式 (How to Obtain)**

  * ValidationResult 实例通过 `ConfigValidator.validate()` 方法获取：
    ```java
    ConfigValidator validator = new ConfigValidator(yamlUtil, logger);
    // ... 设置校验规则 ...
    ValidationResult result = validator.validate(config);
    ```

**3. 公共API方法 (Public API Methods)**

  * #### `isSuccess()`

      * **返回类型:** `boolean`
      * **功能描述:** 判断配置校验是否全部通过。
      * **参数说明:** 无。
      * **返回值说明:** `true` 表示所有校验项都通过，`false` 表示至少有一项校验失败。

  * #### `getErrors()`

      * **返回类型:** `List<String>`
      * **功能描述:** 获取所有校验错误信息的只读列表。
      * **参数说明:** 无。
      * **返回值说明:** 不可修改的错误信息列表，若校验全部通过则为空列表。

**4. 使用示例 (Usage Examples)**

```java
// 执行配置校验
ValidationResult result = validator.validate(config);

// 方式1: 简单判断
if (result.isSuccess()) {
    logger.info("配置校验通过，可以继续启动");
} else {
    logger.error("配置校验失败，请检查配置文件");
    return; // 停止启动
}

// 方式2: 详细错误处理
if (!result.isSuccess()) {
    logger.error("发现 " + result.getErrors().size() + " 个配置错误:");
    for (String error : result.getErrors()) {
        logger.error("  - " + error);
    }
    
    // 可以根据错误数量决定处理方式
    if (result.getErrors().size() > 5) {
        logger.error("错误过多，建议重新检查配置文件");
    }
}

// 方式3: 与其他组件集成
if (!result.isSuccess()) {
    // 发送错误报告
    errorReporter.reportConfigErrors(result.getErrors());
    
    // 或者写入错误日志文件
    errorLogger.logErrors("config-validation-errors.log", result.getErrors());
}
```

**5. 数据特性 (Data Characteristics)**

  * **不可变性 (Immutable):** ValidationResult 创建后内容不可修改。
  * **线程安全 (Thread-Safe):** 可以在多线程环境中安全使用。
  * **只读集合:** `getErrors()` 返回的列表不可修改，确保数据完整性。
