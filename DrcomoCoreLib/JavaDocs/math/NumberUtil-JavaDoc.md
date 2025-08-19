### `NumberUtil.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.math.NumberUtil`
  * **核心职责:** 提供基础的数值判断与加法运算工具，避免在各模块间重复实现相同逻辑。

**2. 如何实例化 (Initialization)**

  * **核心思想:** 这是一个完全静态的工具类，所有方法都应通过类名直接调用，禁止实例化。
  * **构造函数:** `private NumberUtil()` (私有构造函数，防止外部创建实例)
  * **代码示例:**
    ```java
    String userInput = "42";
    if (NumberUtil.isNumeric(userInput)) {
        double result = NumberUtil.add(10, Double.parseDouble(userInput));
        System.out.println("结果: " + result);
    }
    ```

**3. 公共API方法 (Public API Methods)**

  * #### `isNumeric(String input)`

      * **返回类型:** `boolean`
      * **功能描述:** 判断给定字符串是否可以解析为 `double` 数值；支持前后空白与正负号。
      * **参数说明:**
          * `input` (`String`): 待检测的字符串，可为空或包含空白字符。

  * #### `add(double a, double b)`

      * **返回类型:** `double`
      * **功能描述:** 对两个双精度数执行加法运算并返回结果。
      * **参数说明:**
          * `a` (`double`): 第一个加数。
          * `b` (`double`): 第二个加数。

  * #### `parseInt(String input, int defaultValue)`

      * **返回类型:** `int`
      * **功能描述:** 尝试将字符串解析为整数；当输入为空或解析失败时返回 `defaultValue`。
      * **参数说明:**
          * `input` (`String`): 待解析的字符串，可为空或包含空白字符。
          * `defaultValue` (`int`): 解析失败时返回的默认值。
  * #### `parseDouble(String input, double defaultValue)`

      * **返回类型:** `double`
      * **功能描述:** 尝试将字符串解析为双精度浮点数；当输入为空或解析失败时返回 `defaultValue`。
      * **参数说明:**
          * `input` (`String`): 待解析的字符串，可为空或包含空白字符。
          * `defaultValue` (`double`): 解析失败时返回的默认值。

  * #### `parseInt(String input)`

      * **返回类型:** `Integer`
      * **功能描述:** 尝试将字符串解析为整数；当输入为空或解析失败时返回 `null`。
      * **参数说明:**
          * `input` (`String`): 待解析的字符串，可为空或包含空白字符。

  * #### `parseDouble(String input)`

      * **返回类型:** `Double`
      * **功能描述:** 尝试将字符串解析为双精度浮点数；当输入为空或解析失败时返回 `null`。
      * **参数说明:**
          * `input` (`String`): 待解析的字符串，可为空或包含空白字符。