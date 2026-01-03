### `FormulaCalculator.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.math.FormulaCalculator`
  * **核心职责:** 基于 `exp4j` 的静态数学表达式计算工具。支持变量、标准运算符与一组自定义函数。

**2. 使用方式 (Usage)**

  * 这是一个完全静态的工具类，请直接通过类名调用。

  * 示例：
  ```java
  double a = FormulaCalculator.calculate("3 * (log(100) + 5^2)");
  Map<String, Double> vars = new HashMap<>();
  vars.put("x", 10.0);
  vars.put("y", 5.0);
  double b = FormulaCalculator.calculate("sqrt(x^2 + y^2)", vars);
  ```

**3. 公共API方法 (Public API Methods)**

  * `calculate(String formula)`
    * 返回类型: `double`
    * 功能: 计算不含变量的表达式。
    * 参数: `formula` 表达式字符串。
    * 异常: `IllegalArgumentException` 当语法无效或无法计算。

  * `calculate(String formula, Map<String, Double> variables)`
    * 返回类型: `double`
    * 功能: 计算含变量的表达式（变量名可直接出现在表达式中，或以`{name}`形式被预替换）。
    * 参数: `formula` 表达式；`variables` 变量名到数值的映射。

  * `containsMathExpression(String formula)`
    * 返回类型: `boolean`
    * 功能: 判断是否包含数学运算符或已注册的函数名。

  * `getAvailableFunctions()`
    * 返回类型: `String[]`
    * 功能: 返回当前可用的自定义函数名称数组。

  * `validateFormula(String formula)`
    * 返回类型: `boolean`
    * 功能: 仅验证语法是否可被构建，不执行计算。

**4. 自定义函数库 (Functions)**

以下函数均可在表达式中直接使用（函数名大小写敏感）：

  * min(a, b)
    * 返回两数较小值。
    * 示例：`min(3, 5)` → 3

  * max(a, b)
    * 返回两数较大值。
    * 示例：`max(3, 5)` → 5

  * floor(x)
    * 向下取整。
    * 示例：`floor(3.7)` → 3

  * ceil(x)
    * 向上取整。
    * 示例：`ceil(3.1)` → 4

  * round(x)
    * 四舍五入到整数（等价于 `Math.round`）。
    * 示例：`round(2.5)` → 3

  * abs(x)
    * 绝对值。
    * 示例：`abs(-3.2)` → 3.2

  * sqrt(x)
    * 平方根。
    * 示例：`sqrt(9)` → 3

  * pow(a, b)
    * 幂运算 `a^b`。
    * 示例：`pow(2, 3)` → 8

  * sin(x)、cos(x)、tan(x)
    * 三角函数（弧度制）。
    * 示例：`sin(π/2)`（注意：表达式中 `π` 已被标准化为 `Math.PI`）

  * log(x)
    * 以 10 为底的对数 `log10(x)`。
    * 示例：`log(100)` → 2

  * ln(x)
    * 自然对数 `ln(x)`（以 e 为底）。
    * 示例：`ln(e)` → 1

  * clamp(value, min, max)
    * 将 value 约束在 `[min, max]` 区间内。
    * 示例：`clamp(120, 0, 100)` → 100

  * lerp(start, end, t)
    * 线性插值：`start + t * (end - start)`。
    * 示例：`lerp(0, 10, 0.3)` → 3

  * percentage(value, total)
    * 计算百分比：`(value / total) * 100`。
    * 示例：`percentage(25, 200)` → 12.5

  * random(min, max, decimals)
    * 生成 `[min, max)` 的均匀分布随机数；当 `min == max` 时返回该值。
    * 参数：`decimals < 0` 不处理小数；`>= 0` 则四舍五入保留小数位，最大裁剪为 15。
    * 示例：`random(90, 110, 1)`（≈ 等价于在 100±10 范围随机并保留 1 位小数）

**5. 预处理与变量替换**

  * 预处理会移除空格，并将 `×` 替换为 `*`、`÷` 替换为 `/`、`π` 替换为 `Math.PI`、`e` 替换为 `Math.E`。
  * 变量可直接在表达式中以名称出现；若使用自定义占位如 `{name}`，会在计算前尝试用 `variables` 中的值替换。

**6. 性能与使用建议**

  * 随机值：`random` 每次计算都会生成新值；如需同一上下文稳定值，建议在“配置重载阶段”预解析并缓存，发送阶段直接读取缓存结果，避免重复随机。
  * 小数位：`random` 的小数处理基于 `BigDecimal` 的 `HALF_UP`；`decimals` 建议在 0~15 之间。
  * 线程安全：内部使用 `ThreadLocalRandom` 与不可变函数表，可安全用于并发场景。
