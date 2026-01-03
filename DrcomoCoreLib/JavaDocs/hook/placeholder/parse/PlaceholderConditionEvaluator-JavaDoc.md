### `PlaceholderConditionEvaluator.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.hook.placeholder.parse.PlaceholderConditionEvaluator`
  * **核心职责:** 一个轻量级的、专门用于解析和求值布尔条件表达式的引擎。它能够处理包含 PAPI 占位符、数学运算、逻辑运算符（`&&`, `||`）以及多种比较运算符（如 `==`, `>`, `STR_CONTAINS`）的复杂条件字符串，并返回一个布尔结果。这在配置文件中定义动态触发条件（例如，在什么条件下执行某个动作）的场景下极为有用。

**2. 如何实例化 (Initialization)**

  * **核心思想:** 该评估器依赖于多个核心库组件，其实例化过程完美体现了“依赖注入”模式。你需要先创建好 `DebugUtil`、`PlaceholderAPIUtil` 以及可选的异步执行器，然后将它们连同插件实例一起传入。
  * **构造函数:**
    * `public PlaceholderConditionEvaluator(JavaPlugin plugin, DebugUtil debugger, PlaceholderAPIUtil util)`
    * `public PlaceholderConditionEvaluator(JavaPlugin plugin, DebugUtil debugger, PlaceholderAPIUtil util, Executor executor)`
    * `public PlaceholderConditionEvaluator(JavaPlugin plugin, DebugUtil debugger, PlaceholderAPIUtil util, AsyncTaskManager manager)`
  * **代码示例:**
```java
// 在你的子插件 onEnable() 方法中，确保已初始化好依赖项:
JavaPlugin myPlugin = this;
DebugUtil myLogger = new DebugUtil(myPlugin, DebugUtil.LogLevel.INFO);
PlaceholderAPIUtil myPapiUtil = new PlaceholderAPIUtil(myPlugin, "myplugin");
AsyncTaskManager taskManager = new AsyncTaskManager(myPlugin, myLogger);
// (此处应有 myPapiUtil 的占位符注册代码)

// 现在，实例化条件评估器，可传入 AsyncTaskManager 或自定义 Executor
PlaceholderConditionEvaluator conditionEvaluator = new PlaceholderConditionEvaluator(
    myPlugin,
    myLogger,
    myPapiUtil,
    taskManager
);

myLogger.info("条件表达式评估器已准备就绪。");
```
 
**3. 公共API方法 (Public API Methods)**

  * #### `calculateMathExpression(String expression)`

      * **返回类型:** `double`
      * **功能描述:** 计算一个纯数学表达式字符串。它内部委托给 `FormulaCalculator` 执行。
      * **参数说明:**
          * `expression` (`String`): 待计算的数学表达式，例如 `"5 * (2 + %player_level%)"`。
      * **使用示例:**
        ```java
        // 注意：此方法在计算前不会解析 PAPI 占位符。
        // 你需要先手动解析。
        String rawExpression = "100 + %player_level% * 5";
        String parsedExpression = myPapiUtil.parse(player, rawExpression); // 假设玩家10级，结果为 "100 + 10 * 5"
        double result = conditionEvaluator.calculateMathExpression(parsedExpression); // result = 150.0
        ```

  * #### `containsMathExpression(String expression)`

      * **返回类型:** `boolean`
      * **功能描述:** 判断一个字符串中是否包含了数学运算符或函数，可用于初步判断是否需要进行数学计算。
      * **参数说明:**
          * `expression` (`String`): 要检查的字符串。

  * #### `checkAllLines(Player player, List<String> lines)`

      * **返回类型:** `boolean`
      * **功能描述:** **同步**地检查一个字符串列表中的所有条件行。只有当所有行都评估为 `true` 时，才返回 `true`（逻辑与 `AND` 的关系）。
      * **参数说明:**
          * `player` (`Player`): PAPI 占位符的上下文玩家。
          * `lines` (`List<String>`): 包含条件表达式的字符串列表。

  * #### `parse(Player player, String expression)`

      * **返回类型:** `boolean`
      * **功能描述:** **同步**地解析并评估单个条件表达式字符串。这是该类的核心同步执行方法。
      * **参数说明:**
          * `player` (`Player`): PAPI 占位符的上下文玩家。
          * `expression` (`String`): 单行条件表达式，例如 `"%player_level% >= 10 && %vault_eco_balance% > 1000"`。
      * **使用示例:**
        ```java
        String condition = "'%player_world_name%' == 'world_nether'";
        try {
            boolean isInNether = conditionEvaluator.parse(player, condition);
            if (isInNether) {
                player.sendMessage("你现在在地狱！");
            }
        } catch (ParseException e) {
            myLogger.error("条件表达式解析失败: " + condition, e);
        }
        ```

  * #### `parseAndEvaluateAsync(String expression, Player player)`

      * **返回类型:** `CompletableFuture<Boolean>`
      * **功能描述:** **异步**地解析并评估单个条件表达式。该方法会将任务交给构造函数中传入的 `Executor` 或 `AsyncTaskManager` 执行，若未提供则回退到 Bukkit 异步调度器，最终通过 `CompletableFuture` 返回结果。
      * **参数说明:**
          * `expression` (`String`): 单行条件表达式。
          * `player` (`Player`): PAPI 占位符的上下文玩家。
      * **使用示例:**
        ```java
        String asyncCondition = "%some_slow_papi_placeholder% == 'expected_value'";
        conditionEvaluator.parseAndEvaluateAsync(asyncCondition, player).thenAccept(result -> {
            // 这个回调会在主线程中执行，可以安全地操作 Bukkit API
            if (result) {
                player.sendMessage("异步条件满足！");
            }
        });
        ```

  * #### `checkAllLinesAsync(Player player, List<String> lines)`

      * **返回类型:** `CompletableFuture<Boolean>`
      * **功能描述:** **异步**地、串行地检查一个列表中的所有条件行。它会逐个异步评估每个条件，一旦有任何一个条件为 `false`，就会立即返回 `false`，实现短路效果。
      * **参数说明:**
          * `player` (`Player`): PAPI 占位符的上下文玩家。
          * `lines` (`List<String>`): 包含条件表达式的字符串列表。

**4. 内置功能详解 (Built-in Features)**

  * #### **4.1 支持的比较运算符 (Comparison Operators)**

    该评估器支持丰富的比较运算符，能够处理数值、字符串和布尔值的各种比较需求：

    | 运算符 | 名称 | 适用类型 | 功能描述 | 使用示例 |
    |--------|------|----------|----------|----------|
    | `>` | 大于 | 数值/字符串 | 数值比较或字符串字典序比较 | `%player_level% > 10` |
    | `>=` | 大于等于 | 数值/字符串 | 数值比较或字符串字典序比较 | `%vault_eco_balance% >= 1000.5` |
    | `<` | 小于 | 数值/字符串 | 数值比较或字符串字典序比较 | `%player_health% < 5` |
    | `<=` | 小于等于 | 数值/字符串 | 数值比较或字符串字典序比较 | `%player_food_level% <= 10` |
    | `==` | 等于 | 数值/字符串/布尔 | 严格相等比较 | `%player_world_name% == 'world'` |
    | `!=` | 不等于 | 数值/字符串/布尔 | 严格不等比较 | `%player_gamemode% != 'CREATIVE'` |
    | `>>` | 字符串包含 | 字符串 | 左侧字符串包含右侧字符串 | `%player_name% >> 'Admin'` |
    | `!>>` | 字符串不包含 | 字符串 | 左侧字符串不包含右侧字符串 | `%player_permission_group% !>> 'banned'` |
    | `<<` | 字符串被包含 | 字符串 | 右侧字符串包含左侧字符串 | `'VIP' << %player_permission_group%` |
    | `!<<` | 字符串不被包含 | 字符串 | 右侧字符串不包含左侧字符串 | `'guest' !<< %player_permission_group%` |

    **运算符优先级说明:**
    - 词法分析器按照运算符长度优先匹配（长的优先），确保 `!>>` 不会被误识别为 `!` + `>>`

    | 规范运算符 | 等价写法（别名） | 说明 |
    |-----------|------------------|------|
    | `>=` | `>=`, `=>` | 大于等于 |
    | `<=` | `<=`, `=<` | 小于等于 |
    | `!=` | `!=`, `<>`, `=!` | 不等于 |
    | `==` | `==` | 等于 |
    | `>>` | `>>` | 左包含右（字符串） |
    | `!>>` | `!>>`, `>>!` | 左不包含右（字符串） |
    | `<<` | `<<` | 左被右包含（字符串） |
    | `!<<` | `!<<`, `<<!` | 左不被右包含（字符串） |

    - 使用建议：
      - 新增配置请优先使用规范写法（如 `>=`、`<=`、`!=`、`==`）。
      - 旧配置中的 `=>`、`=<`、`<>`、`=!` 将被自动识别并按等价规则处理。

  * #### **4.2 逻辑运算符 (Logical Operators)**

    | 运算符 | 名称 | 功能描述 | 使用示例 |
    |--------|------|----------|----------|
    | `&&` | 逻辑与 | 两个条件都为真时返回真 | `%player_level% >= 10 && %vault_eco_balance% > 1000` |
    | `\|\|` | 逻辑或 | 任一条件为真时返回真 | `%player_world_name% == 'world' \|\| %player_world_name% == 'world_nether'` |

    **逻辑运算符优先级:**
    - `&&` (AND) 的优先级高于 `\|\|` (OR)
    - 支持使用括号 `()` 改变运算优先级

  * #### **4.3 数据类型自动识别 (Automatic Type Detection)**

    评估器会根据操作数的内容自动选择合适的比较方式：

    **数值比较:**
    ```java
    // 当左右操作数都是数字时，进行数值比较
    "%player_level% > 10"           // 数值比较：玩家等级大于10
    "%vault_eco_balance% >= 1000.5" // 支持小数比较
    ```

    **布尔值比较:**
    ```java
    // 当操作数为 "true" 或 "false" 时，进行布尔比较
    "%some_boolean_placeholder% == true"
    "%player_is_flying% != false"
    ```

    **字符串比较:**
    ```java
    // 其他情况下进行字符串比较
    "%player_world_name% == 'world_nether'"     // 字符串相等
    "%player_name% >> 'Admin'"                  // 字符串包含
    "%player_displayname% < 'ZZZ'"              // 字典序比较
    ```

  * #### **4.4 占位符解析 (Placeholder Resolution)**

    - **PAPI 占位符支持:** 通过 `PlaceholderAPIUtil` 自动解析所有 `%placeholder%` 格式的占位符
    - **解析时机:** 在比较运算执行前，所有占位符都会被解析为实际值
    - **嵌套支持:** 支持占位符的嵌套解析

    ```java
    // 示例：复杂占位符表达式
    String condition = "%player_level% >= %config_min_level% && " +
                      "'%player_world_name%' == '%config_allowed_world%'";
    ```

  * #### **4.5 数学表达式集成 (Math Expression Integration)**

    通过 `FormulaCalculator` 提供强大的数学计算能力：

    **支持的数学函数:**
    - 基础运算：`+`, `-`, `*`, `/`, `%` (取模)
    - 数学函数：`min()`, `max()`, `floor()`, `ceil()`, `round()`, `abs()`, `pow()`, `sqrt()`
    - 三角函数：`sin()`, `cos()`, `tan()`
    - 对数函数：`log()`, `ln()`

    ```java
    // 在条件表达式中使用数学计算
    "max(%player_level%, 10) >= 15"
    "floor(%vault_eco_balance% / 100) > 50"
    "pow(%player_level%, 2) >= 400"
    ```

  * #### **4.6 括号支持 (Parentheses Support)**

    支持使用括号改变运算优先级和逻辑分组：

    ```java
    // 复杂的逻辑表达式
    "(%player_level% >= 10 && %vault_eco_balance% > 1000) || " +
    "(%player_permission_group% >> 'VIP' && %player_world_name% == 'vip_world')"
    ```