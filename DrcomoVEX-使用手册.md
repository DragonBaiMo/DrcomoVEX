## 变量用法
#### [3. 变量的定义与详解](#aCqbj)
#### [5. 高级功能指南](#LN76s)
#### [6. 占位符集成 (PlaceholderAPI)](#hokFq)
## 指令用法
#### [4. 指令用法与权限参考](#HQG77)
---

## 1. 插件总览
### 功能概述
DrcomoVEX 是一款为 Minecraft 服务器设计的、功能强大的变量管理系统。它允许服务器管理员通过简单的配置文件，创建和管理各种动态的、可持久化的数据，而无需编写任何代码。

这个插件的核心目标是提供一个“基于直觉”的变量解决方案，让服务器的数值系统、玩家状态、任务进度等内容的管理变得简单、灵活且高性能。

### 核心特性
+ **直观的变量定义**：通过 YAML 文件即可定义变量，支持多种数据类型（整数、浮点数、字符串、列表）。
+ **灵活的作用域**：变量可以绑定到玩家 (`player`) 或服务器全局 (`global`)。
+ **动态值与表达式**：变量的初始值可以是固定的，也可以是一个动态计算的表达式，支持数学运算，并能引用其他变量和 PlaceholderAPI 的占位符。
+ **周期性重置**：可以轻松实现变量的每日、每周、每月、每分钟重置，或使用 Cron 表达式自定义复杂的重置周期。
+ **强大的指令系统**：提供完整的后台指令，用于查询、设置、增减和重置变量，支持对单个或批量目标进行操作。
+ **高级控制功能**：
    - **严格初始值模式 (Strict Initial Mode)**：控制动态初始值是一次性计算还是周期性更新。
    - **条件门控 (Conditional Gating)**：变量的访问和操作可以被前置条件所限制。
    - **持久化控制**：可将某些变量设置为非持久化，仅在内存中存活。
+ **高性能设计**：内置多级缓存和批量持久化机制，确保在高负载服务器上也能流畅运行。
+ **PlaceholderAPI 集成**：无缝对接 PlaceholderAPI，方便在其他插件中使用 DrcomoVEX 的变量。

### 适用场景
+ 构建服务器经济系统（金币、点券）。
+ 创建玩家属性与统计数据（等级、经验、PVP分数）。
+ 实现每日/每周任务系统。
+ 管理活动状态与玩家进度。
+ 制作动态的服务器信息板（如显示在线人数、TPS）。
+ 任何需要动态、可配置、可持久化数据的场景。

---

## 2. 核心概念与配置结构
### 核心概念
在使用 DrcomoVEX 前，理解以下几个核心概念至关重要。

+ **变量 (Variable)**：插件管理的基本单位。每个变量都有一个唯一的键名（Key），并包含其类型、作用域、初始值等一系列定义。例如，`player_money` 就是一个变量。
+ **键 (Key)**：变量的唯一标识符，在所有配置文件中必须是唯一的。指令和占位符都通过键来操作变量。键名只能包含字母、数字和下划线。
+ **作用域 (Scope)**：决定变量的归属。
    - `player`: 变量属于单个玩家。每个玩家都拥有自己独立的该变量值。
    - `global`: 变量属于整个服务器，所有玩家共享同一个值。
+ **类型 (Type)**：定义变量存储的数据格式。
    - `INT`: 整数。
    - `DOUBLE`: 浮点数（小数）。
    - `STRING`: 字符串（文本）。
    - `LIST`: 字符串列表。
+ **周期 (Cycle)**：定义变量的自动重置规则。可以是预设的关键词，也可以是 Cron 表达式。

### 配置文件结构
DrcomoVEX 的配置主要由以下几个部分组成：

#### 1. 主配置文件: `config.yml`
这是插件的全局配置文件，负责控制数据库连接、数据保存策略、周期任务等核心行为。

```yaml
# /plugins/DrcomoVEX/config.yml

# 数据库配置
database:
  type: "sqlite" # 支持 "sqlite" 或 "mysql"
  
  # SQLite 配置
  file: "drcomovex.db"
  
  # MySQL 配置
  mysql:
    host: "localhost"
    port: 3306
    # ... 其他MySQL连接信息

# 数据保存配置
data:
  auto-save: true # 是否启用自动保存
  save-interval-minutes: 3 # 自动保存间隔（分钟）
  save-on-player-quit: true # 玩家退出时是否保存数据

# 周期性重置配置
cycle:
  enabled: true # 是否启用周期性重置功能
  check-interval-seconds: 1 # 周期检查的频率（秒），建议保持为1以确保精度
  timezone: "Asia/Shanghai" # 时区设置，影响daily, weekly等重置时间点

# 其他设置
settings:
  # ... 其他插件设置

# 调试配置
debug:
  level: "INFO" # 日志级别: DEBUG, INFO, WARN, ERROR
```

#### 2. 变量定义目录: `variables/`
这是定义所有变量的地方。你可以在 `/plugins/DrcomoVEX/variables/` 目录下创建任意多个 `.yml` 文件，插件会自动加载所有文件。支持子目录结构，便于分类管理。

例如，你可以创建 `economy.yml`, `stats.yml`, `events/summer_event.yml` 等。

每个变量定义文件的基本结构如下：

```yaml
# /plugins/DrcomoVEX/variables/your_file_name.yml

variables:
  # 变量1的键名
  variable_key_1:
    name: "变量的显示名称"
    scope: "player" # 或 "global"
    type: "INT"     # 或 DOUBLE, STRING, LIST
    initial: "初始值或表达式"
    # ... 其他可选配置

  # 变量2的键名
  variable_key_2:
    # ... 定义
```

#### 3. 消息配置文件: `messages.yml`
该文件允许你完全自定义插件的所有指令反馈和提示信息，支持颜色代码和 PlaceholderAPI。

```yaml
# /plugins/DrcomoVEX/messages.yml

messages:
  success:
    # {variable}, {value} 是可被替换的占位符
    get: "&a变量 &e{variable} &a的值: &f{value}"
    set: "&a已将变量 &e{variable} &a设置为: &f{value}"
  error:
    no-permission: "&c你没有权限执行这个操作！"
    variable-not-found: "&c变量 &e{variable} &c不存在！"
  # ... 其他消息
```

---

## 3. 变量的定义与详解
所有变量都在 `/plugins/DrcomoVEX/variables/` 目录下的 `.yml` 文件中定义。本篇文档将详细解释每个配置项的含义和用法。

### 基础结构
一个最基础的变量定义包含 `scope`, `type`, 和 `initial`。

```yaml
variables:
  # 变量的唯一键名
  player_money:
    # [可选] 变量的友好显示名称
    name: "玩家金币"
    
    # [必填] 作用域 (player / global)
    scope: "player"
    
    # [必填] 数据类型 (INT / DOUBLE / STRING / LIST)
    type: "DOUBLE"
    
    # [必填] 初始值或动态表达式
    initial: "100.0" 
```

### 详细配置项说明
#### `name`
+ **类型**: `String`
+ **说明**: 变量的显示名称，主要用于未来可能的UI展示或日志，非必需。

#### `scope`
+ **类型**: `String`
+ **必填**: 是
+ **可选值**:
    - `player`: 玩家变量。每个玩家独立存储一份数据。
    - `global`: 全局变量。全服共享一份数据。

#### `type`
+ **类型**: `String`
+ **必填**: 是
+ **可选值**:
    - `INT`: 整数。例如 `10`, `-5`。
    - `DOUBLE`: 浮点数（小数）。例如 `10.5`, `0.01`。
    - `STRING`: 字符串。可以是任意文本。
    - `LIST`: 字符串列表。在 `add` 和 `remove` 操作中表现为元素的增删。

#### `initial`
+ **类型**: `String` 或 `List` (当 `type` 为 `LIST` 时)
+ **必填**: 是
+ **说明**: 定义变量的初始值。这个值非常强大，可以是：
    1. **静态值**: 一个固定的值。

```yaml
initial: "100" # 对于 INT/DOUBLE
initial: "Welcome!" # 对于 STRING
initial: # 对于 LIST
  - "item1"
  - "item2"
```

    2. **动态表达式**: 一个包含数学运算、其他变量引用或 PlaceholderAPI 占位符的字符串。
        * **数学运算**: 支持 `+`, `-`, `*`, `/`, `^`, `()`。

```yaml
initial: "(10 + 5) * 2"
```

        * **引用其他DrcomoVEX变量**: 使用 `${variable_key}` 格式。

```yaml
# 假设已定义 player_level 变量
initial: "${player_level} * 100 + 50"
```

        * **引用PlaceholderAPI占位符**: 使用 `%placeholder%` 格式。

```yaml
initial: "%server_online%"
initial: "%vault_eco_balance%"
```

#### `min` / `max`
+ **类型**: `Number`
+ **说明**: 为 `INT` 或 `DOUBLE` 类型的变量设置数值范围约束。当通过指令修改值超出这个范围时，操作会失败。
+ **示例**:

```yaml
player_level:
  type: "INT"
  initial: "1"
  min: 1
  max: 100
```

#### `cycle`
+ **类型**: `String`
+ **说明**: 设置变量的自动重置周期。到重置时间点，变量的值将恢复为其 `initial` 值。
+ **预设值**:
    - `minute`: 每分钟的第0秒重置。
    - `daily`: 每日重置（根据 `config.yml` 的时区，在 00:00:00 重置）。
    - `weekly`: 每周重置（在周一 00:00:00 重置）。
    - `monthly`: 每月重置（在每月第一天 00:00:00 重置）。
    - `yearly`: 每年重置（在每年第一天 00:00:00 重置）。
+ **Cron 表达式**: 支持标准的 Quartz Cron 表达式，用于实现复杂的周期。
    - `"0 0 12 * * ?"`: 每天中午12点重置。
    - `"0 0/30 * * * ?"`: 每30分钟重置一次。
+ **示例**:

```yaml
daily_task_progress:
  type: "INT"
  initial: "0"
  cycle: "daily"
```

#### `limitations`
+ **类型**: `Object`
+ **说明**: 一个包含高级限制与行为控制的配置块。
+ **子选项**:
    - `read-only` (`Boolean`): 如果为 `true`，该变量将变为只读，无法通过 `set/add/remove/reset` 指令修改。通常用于纯动态计算的变量。
    - `persistable` (`Boolean`): 如果为 `false`，该变量将不会被保存到数据库，其生命周期仅限于服务器本次运行。
    - `strict-initial-mode` (`Boolean`): **[高级]** 严格初始值模式。详见高级功能指南。
+ **示例**:

```yaml
server_version:
  scope: "global"
  type: "STRING"
  initial: "%server_version%"
  limitations:
    read-only: true

session_playtime:
  scope: "player"
  type: "INT"
  initial: "0"
  limitations:
    persistable: false
```

#### `conditions`
+ **类型**: `String` 或 `List<String>`
+ **说明**: **[高级]** 条件门控。设置一个或多个条件表达式，只有当所有条件都返回 `true` 时，该变量才能被获取或修改。布尔解释规则: `"true"` (忽略大小写) 或非零数字为 `true`；其他情况 (如 `"false"`, `0`, 空字符串) 均为 `false`。
+ **注意**: 启用了 `conditions` 的变量会禁用缓存，以确保每次访问都重新评估条件。
+ **示例**:

```yaml
# 假设已定义全局变量 event_switch (值为 "true" 或 "false")
event_reward:
  scope: "player"
  type: "INT"
  initial: "100"
  # 只有当全局活动开关打开时，才能操作此奖励变量
  conditions: "${event_switch}" 

# 多个条件（AND逻辑）
vip_exclusive_kit:
  scope: "player"
  type: "STRING"
  initial: "claimed"
  conditions:
    - "%player_has_permission_vip.kit%" # 检查PAPI权限
    - "${event_switch}" # 检查活动开关
```

---

## 4. 指令用法与权限参考
DrcomoVEX 提供了强大而灵活的指令系统来管理所有变量。

**主指令**: `/vex` (别名: `/dvex`, `/drcomo`)

### 玩家变量操作 (`/vex player ...`)
用于操作 `scope: player` 的变量。

#### 1. 获取玩家变量
+ **指令**: `/vex player get <玩家名|UUID> <变量键> [--offline]`
+ **权限**: `drcomovex.command.player.get`
+ **操作他人权限**: `drcomovex.command.player.get.others`
+ **说明**: 获取指定玩家的变量值。
+ **参数**:
    - `--offline`: 允许查询离线玩家的数据。

#### 2. 设置玩家变量
+ **指令**: `/vex player set <玩家名|UUID> <变量键> <值> [--offline]`
+ **权限**: `drcomovex.command.player.set`
+ **操作他人权限**: `drcomovex.command.player.set.others`
+ **说明**: 直接设定玩家的变量值。

#### 3. 增加玩家变量值
+ **指令**: `/vex player add <玩家名|UUID> <变量键> <值> [--offline]`
+ **权限**: `drcomovex.command.player.add`
+ **操作他人权限**: `drcomovex.command.player.add.others`
+ **说明**:
    - **数值类型 (INT/DOUBLE)**: 将原值与 `<值>` 相加。
    - **字符串类型 (STRING)**: 将 `<值>` 附加到原字符串末尾。
    - **列表类型 (LIST)**: 将 `<值>` 作为一个新元素添加到列表中。

#### 4. 移除玩家变量值
+ **指令**: `/vex player remove <玩家名|UUID> <变量键> <值> [--offline]`
+ **权限**: `drcomovex.command.player.remove`
+ **操作他人权限**: `drcomovex.command.player.remove.others`
+ **说明**:
    - **数值类型 (INT/DOUBLE)**: 将原值减去 `<值>`。
    - **字符串类型 (STRING)**: 从原字符串中移除所有匹配的 `<值>`。
    - **列表类型 (LIST)**: 从列表中移除第一个完全匹配 `<值>` 的元素。

#### 5. 重置玩家变量
+ **指令**: `/vex player reset <玩家名|UUID> <变量键> [--offline]`
+ **权限**: `drcomovex.command.player.reset`
+ **操作他人权限**: `drcomovex.command.player.reset.others`
+ **说明**: 将玩家的变量值恢复到其配置的 `initial` 值。

### 全局变量操作 (`/vex global ...`)
用于操作 `scope: global` 的变量。用法与玩家变量指令类似，但无需指定玩家名。

+ `/vex global get <变量键>` (权限: `drcomovex.command.global.get`)
+ `/vex global set <变量键> <值>` (权限: `drcomovex.command.global.set`)
+ `/vex global add <变量键> <值>` (权限: `drcomovex.command.global.add`)
+ `/vex global remove <变量键> <值>` (权限: `drcomovex.command.global.remove`)
+ `/vex global reset <变量键>` (权限: `drcomovex.command.global.reset`)

### 批量与通配符操作
指令支持使用通配符 `*` 和条件进行批量操作，极大提升管理效率。

#### 批量查询
+ **指令**: `/vex player get * <变量通配[:条件]> [--out:文件名.yml] [--db-only | --online-only]`
+ **说明**:
    - `<变量通配>`: 可以是 `*` (所有变量), `*_money` (以后缀结尾), `prefix_*` (以前缀开头), `*word*` (包含)。
    - `[:条件]`: 可选，用于筛选数值。例如 `player_score:>=100` (查询分数大于等于100的)。支持 `>` `>=` `<` `<=` `==` `!=`。
    - `--out:文件名.yml`: 将查询结果导出到 `plugins/DrcomoVEX/exports/` 目录。
    - `--db-only`: 仅查询数据库中的数据。
    - `--online-only`: 仅查询在线玩家内存中的数据。

#### 批量写入
+ **指令**: `/vex player <set|add|remove|reset> * <变量通配> [<值>] [-n] [--limit N]`
+ **说明**:
    - `-n` 或 `--dry-run`: 预演模式。只显示会受影响的玩家和变量，不实际执行操作。
    - `--limit N`: 限制本次操作最多影响的玩家数量。

### 管理指令
#### 重载插件
+ **指令**: `/vex reload`
+ **权限**: `drcomovex.admin.reload`
+ **说明**: 完全重载插件的配置，包括 `config.yml`, `messages.yml` 和所有 `variables/` 下的变量定义。

#### 查看帮助
+ **指令**: `/vex help`
+ **权限**: `drcomovex.command.help`
+ **说明**: 显示所有可用指令的帮助信息。

---

## 5. 高级功能指南
DrcomoVEX 提供了一些高级功能，用于处理复杂的逻辑和性能优化。

### 严格初始值模式 (Strict Initial Mode)
此模式用于精确控制**动态初始值** (`initial` 包含表达式) 的计算时机。

+ **配置**: 在变量的 `limitations` 块中设置 `strict-initial-mode: true`。

#### 行为区别
| 场景 | 默认模式 (strict-initial-mode: false) | 严格模式 (strict-initial-mode: true) |
| --- | --- | --- |
| **无 **`cycle`** 配置** | `initial` 表达式的值会**实时变化**。每次获取变量时都会重新计算。 | `initial` 表达式的值**只在变量首次被创建时计算一次**，之后其值便固定下来。 |
| **有 **`cycle`** 配置 (例如 **`daily`**)** | `initial` 表达式的值**实时变化**，并且在周期点重置。 | `initial` 表达式的值是固定的，**只在每个重置周期点（如每日0点）重新计算一次**。 |


#### 用途与案例
##### 案例1: 一次性新手礼包等级奖励
**需求**: 玩家首次加入服务器时，根据他当时的等级 (`player_level`) 给予一次性的金币奖励，此后即使等级提升，这个奖励值也不再改变。

**实现**:

```yaml
variables:
  first_join_bonus:
    name: "首次加入奖励金币"
    scope: "player"
    type: "INT"
    initial: "100 + ${player_level} * 50" # 动态计算
    limitations:
      strict-initial-mode: true # 启用严格模式，无cycle
      read-only: true # 设为只读，防止被修改
```

+ **效果**: 当玩家首次触发 `first_join_bonus` 变量时，插件会计算 `100 + ${player_level} * 50` 并将结果永久存下。之后无论玩家等级如何变化，`first_join_bonus` 的值都保持不变。

##### 案例2: 每日任务奖励
**需求**: 每日任务的奖励金额根据玩家每天首次上线时的等级决定，当天内保持不变。

**实现**:

```yaml
variables:
  daily_mission_reward:
    name: "每日任务奖励"
    scope: "player"
    type: "INT"
    initial: "200 + ${player_level} * 10"
    cycle: "daily" # 每日重置
    limitations:
      strict-initial-mode: true # 启用严格模式，有cycle
```

+ **效果**: 在每日0点重置后，玩家当天第一次获取此变量时，插件会根据其当前等级计算奖励金额并固定下来。在第二天的0点之前，即使玩家升级，这个奖励值也不会变。到了第二天0点，会再次重新计算。

### 条件门控 (Conditional Gating)
此功能允许你为变量设置访问和操作的前置条件。

+ **配置**: 在变量定义中添加 `conditions` 字段。

#### 工作原理
+ 在对变量进行任何操作（get, set, add, remove, reset）之前，插件会首先解析 `conditions` 中的所有表达式。
+ 只有当**所有**表达式的最终结果都为 `true` 时，操作才会继续。
+ 任何一个条件不满足，操作就会失败，并提示“不满足访问条件”。
+ **布尔解释规则**: `"true"` (忽略大小写) 或非零数字被视为 `true`。 `"false"`, `0`, 空字符串或无法解析为数字的文本被视为 `false`。

#### 用途与案例
##### 案例1: VIP 专属每日礼包
**需求**: 只有拥有 `myplugin.vip` 权限的玩家才能领取每日礼包。

**实现**:

```yaml
variables:
  # 礼包领取状态变量
  daily_vip_kit_claimed:
    name: "VIP每日礼包领取状态"
    scope: "player"
    type: "STRING" # 使用 "true"/"false" 字符串
    initial: "false"
    cycle: "daily"
    # 条件: 玩家必须有 myplugin.vip 权限
    conditions: "%player_has_permission_myplugin.vip%"
```

+ **效果**:
    - 非VIP玩家尝试 `get`, `set` 此变量时，会直接失败。
    - 你可以让另一个插件在玩家执行 `/kit vip` 时，先检查 `daily_vip_kit_claimed` 的值。如果为 `false`，则发放礼包并将其 `set` 为 `true`。非VIP玩家因为无法通过条件检查，所以永远不能操作此变量。

##### 案例2: 全局活动期间的特殊商店
**需求**: 只有当一个全局变量 `global_event_active` 为 `true` 时，玩家才能购买特殊商品（通过修改一个 `purchased_special_item` 变量来记录）。

**实现**:

```yaml
variables:
  # 全局活动开关
  global_event_active:
    scope: "global"
    type: "STRING"
    initial: "false"

  # 玩家购买记录
  purchased_special_item:
    scope: "player"
    type: "STRING"
    initial: "false"
    conditions:
      - "${global_event_active}" # 必须全局活动开启
      - "%vault_eco_balance_formatted% >= 1000" # 且玩家金币>=1000
```

+ **效果**: 只有在服务器管理员将 `global_event_active` 设为 `true`，并且玩家有足够金币时，才能成功地将 `purchased_special_item` 变量从 `false` 设为 `true`，从而完成购买逻辑。

---

## 6. 占位符集成 (PlaceholderAPI)
DrcomoVEX 与 [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) (PAPI) 进行了深度集成。你不仅可以在 DrcomoVEX 的 `initial` 表达式中使用 PAPI 的占位符，还可以将 DrcomoVEX 的变量作为 PAPI 占位符在其他插件（如计分板、聊天格式插件）中使用。

### 使用 DrcomoVEX 变量作为占位符
安装 PlaceholderAPI后，你可以在任何支持 PAPI 的插件中使用以下格式的占位符。

#### 格式
+ **通用格式**: `%drcomovex_[var]_[变量键]%`
    - 这个格式会自动判断变量是 `player` 还是 `global` 作用域。
    - **示例**: `%drcomovex_[var]_player_money%`
+ **玩家变量专用**: `%drcomovex_[player]_[变量键]%`
    - 明确指定获取玩家变量。
    - **示例**: `%drcomovex_[player]_player_level%`
+ **全局变量专用**: `%drcomovex_[global]_[变量键]%`
    - 明确指定获取全局变量。
    - **示例**: `%drcomovex_[global]_server_motd%`
+ **(兼容) 短格式**: `var`, `player`, `global` 两边的方括号 `[]` 是可选的。
    - `%drcomovex_var_player_money%` 也是有效的。

#### 获取其他玩家的变量值
PAPI 的标准格式也支持获取其他玩家的数据，DrcomoVEX 同样支持此功能。

+ **格式**: `%drcomovex_[player]_[变量键]_[玩家名]%`
+ **示例**: `%drcomovex_[player]_player_money_Notch%` 将会显示玩家 `Notch` 的金币数量。

### 实际应用案例
#### 案例1: 在计分板上显示玩家数据
假设你在计分板插件 (如 DeluxeScoreboard) 中配置记分板：

```yaml
# DeluxeScoreboard config.yml
...
lines:
- '&e&l个人信息'
- '&7等级: &f%drcomovex_var_player_level%'
- '&7金币: &6%drcomovex_var_player_money%'
- '&7战斗力: &c%drcomovex_var_player_combat_power%'
- ''
- '&e&l服务器信息'
- '&7在线: &f%drcomovex_global_server_online_count%'
- '&7公告: &a%drcomovex_global_server_motd%'
```

#### 案例2: 在聊天格式中显示称号
假设你在聊天插件 (如 EssentialsXChat) 中配置聊天格式：

```yaml
# EssentialsXChat config.yml
...
format: '[&f%drcomovex_list_player_titles_0%&r] {DISPLAYNAME}: {MESSAGE}'
```

+ **注意**: 对于 `LIST` 类型的变量，使用 `%drcomovex_list_<变量键>_<索引>%` 可以获取列表中特定位置的元素（索引从0开始）。

### PAPI 重新加载
如果你在服务器运行时修改了 DrcomoVEX 的变量定义，需要执行 `/papi reload` 来让 PlaceholderAPI 重新识别新的占位符。

---

## 7. 示例配置文件
以下是多个场景的示例变量定义文件，你可以将它们直接放入 `plugins/DrcomoVEX/variables/` 目录中进行学习和修改。

### 示例 1: 基础经济变量 (`economy.yml`)
```yaml
# 示例1: 基础经济系统
# 包含两种核心货币：金币 (可游戏内获取) 和点券 (通常通过充值获取)。

variables:
  # 玩家金币
  # - 作用域: player (每个玩家独立)
  # - 类型: DOUBLE (支持小数)
  # - 初始值: 100.0
  # - 约束: 最小为0，最大为1亿
  player_coins:
    name: "玩家金币"
    scope: "player"
    type: "DOUBLE"
    initial: "100.0"
    min: 0
    max: 100000000

  # 玩家点券
  # - 作用域: player
  # - 类型: INT (通常为整数)
  # - 初始值: 0
  # - 约束: 最小为0
  player_points:
    name: "玩家点券"
    scope: "player"
    type: "INT"
    initial: "0"
    min: 0

  # 全局税率
  # - 作用域: global (全服统一)
  # - 类型: DOUBLE
  # - 初始值: 0.05 (代表5%)
  # - 约束: 范围在0到1之间
  # - 高级: 设置为只读，只能通过后台指令修改，防止游戏内逻辑意外更改
  global_tax_rate:
    name: "全局交易税率"
    scope: "global"
    type: "DOUBLE"
    initial: "0.05"
    min: 0.0
    max: 1.0
    limitations:
      read-only: true

  # 玩家交易税后收入 (动态计算)
  # - 这是一个只读的动态变量，它引用了其他变量
  # - 用于方便地在其他插件中通过占位符获取计算结果
  # - 假设有一个场景需要计算玩家出售物品价值1000金币时的税后收入
  player_trade_income_example:
    name: "交易收入示例"
    scope: "player"
    type: "DOUBLE"
    initial: "1000 * (1 - ${global_tax_rate})" # 引用全局税率变量
    limitations:
      read-only: true
```

### 示例 2: 玩家统计与段位 (`stats.yml`)
```yaml
# 示例2: 玩家统计与段位系统
# 描述玩家的PVP数据，并根据分数动态计算段位。

variables:
  # 玩家PVP击杀数
  player_pvp_kills:
    name: "PVP击杀数"
    scope: "player"
    type: "INT"
    initial: "0"
    min: 0

  # 玩家PVP死亡数
  player_pvp_deaths:
    name: "PVP死亡数"
    scope: "player"
    type: "INT"
    initial: "0"
    min: 0

  # 玩家PVP分数 (动态计算)
  # - 每次击杀得10分，每次死亡扣5分
  player_pvp_score:
    name: "PVP分数"
    scope: "player"
    type: "INT"
    initial: "(${player_pvp_kills} * 10) - (${player_pvp_deaths} * 5)"
    limitations:
      read-only: true # 分数是计算出来的，应该是只读的

  # 玩家KDA (Kill/Death/Assist) (动态计算)
  # - 死亡为0时，KDA等于击杀数，避免除以0的错误
  player_pvp_kda:
    name: "PVP KDA"
    scope: "player"
    type: "DOUBLE"
    # 使用 PlaceholderAPI 的 %math_...% 来实现条件逻辑
    initial: "%math_(${player_pvp_deaths} > 0) ? ${player_pvp_kills}/${player_pvp_deaths} : ${player_pvp_kills}%"
    limitations:
      read-only: true

  # 玩家段位 (动态计算)
  # - 这是一个更复杂的例子，使用 PAPI 的 Relational Placeholders
  # - 根据PVP分数显示不同的段位名称
  player_pvp_rank:
    name: "PVP段位"
    scope: "player"
    type: "STRING"
    initial: >-
      %rel_drcomovex_var_player_pvp_score_>=_2000_?_&c&l王者%
      %rel_drcomovex_var_player_pvp_score_>=_1500_&_<%_2000_?_&e&l钻石%
      %rel_drcomovex_var_player_pvp_score_>=_1000_&_<%_1500_?_&b&l铂金%
      %rel_drcomovex_var_player_pvp_score_>=_500_&_<%_1000_?_&a&l黄金%
      %rel_drcomovex_var_player_pvp_score_>=_0_&_<%_500_?_&f&l白银%
      %rel_drcomovex_var_player_pvp_score_<_0_?_&8&l青铜%
    limitations:
      read-only: true
```

### 示例 3: 活动与任务变量 (`events.yml`)
```yaml
# 示例3: 活动与任务系统变量
# 用于管理一个夏日活动的状态和玩家的每日任务进度。

variables:
  # 全局夏日活动开关
  # - 管理员通过指令 /vex global set summer_event_active true 开启活动
  summer_event_active:
    name: "夏日活动开关"
    scope: "global"
    type: "STRING" # 使用 "true" 或 "false" 字符串作为布尔值
    initial: "false"

  # 每日任务: 收集西瓜
  # - 每日自动重置为0
  player_daily_quest_watermelon:
    name: "每日任务-收集西瓜"
    scope: "player"
    type: "INT"
    initial: "0"
    cycle: "daily" # 每日重置
    min: 0
    max: 50 # 任务目标是50个

  # 每日任务完成状态 (动态计算)
  # - 当收集数量达到50时，状态变为 "true"
  player_daily_quest_watermelon_completed:
    name: "每日任务-西瓜-完成状态"
    scope: "player"
    type: "STRING"
    initial: "%math_(${player_daily_quest_watermelon} >= 50) ? true : false%"
    limitations:
      read-only: true

  # 夏日活动积分
  # - 玩家在活动期间通过各种行为获得，活动结束后可能会清空
  player_event_points_summer:
    name: "夏日活动积分"
    scope: "player"
    type: "INT"
    initial: "0"
    min: 0
    # 条件门控：只有在夏日活动开启时，才能获取或修改此积分
    conditions: "${summer_event_active}"
```

### 示例 4: 高级逻辑与组合用法 (`advanced.yml`)
```yaml
# 示例4: 高级逻辑与组合用法
# 展示 strict-initial-mode, conditions 和 复杂周期的组合使用。

variables:
  # 一次性的开服元老称号
  # - 需求: 只有在服务器开服第一周内加入的玩家才能获得一个 "开服元老" 称号。
  # - 实现: 使用 PAPI 的 %server_time_yyyy-MM-dd% 和条件判断。
  #   这里假设你有办法记录玩家首次加入时间 (例如另一个PAPI变量 %firstplayed_...%)
  #   为了简化，我们用一个严格模式变量来模拟这个效果。
  player_is_veteran:
    name: "是否为开服元老"
    scope: "player"
    type: "STRING"
    # 假设服务器开服日期为 2024-01-01
    # 使用PAPI的JavaScript占位符进行日期比较
    initial: "%javascript_new Date() < new Date('2024-01-08') ? 'true' : 'false'%"
    limitations:
      strict-initial-mode: true # 关键！只在玩家首次被记录时计算一次
      read-only: true

  # 每周末双倍金币开关 (Cron周期)
  # - 每周六和周日的0点开启，周一0点关闭
  # - 这通常由外部任务调度插件控制，这里用一个变量来代表其状态
  global_weekend_bonus_active:
    name: "周末双倍金币开关"
    scope: "global"
    type: "STRING"
    initial: "false"
    # 通过外部脚本或指令在周六0点设为true, 周一0点设为false

  # 玩家在线奖励
  # - 基础奖励为10金币，如果恰逢周末双倍活动，则奖励翻倍
  player_online_reward:
    name: "在线奖励"
    scope: "player"
    type: "INT"
    initial: "%math_(${global_weekend_bonus_active} == true) ? 20 : 10%"
    limitations:
      read-only: true

  # 临时PVP保护状态
  # - 玩家复活后获得，仅在内存中有效，持续1分钟
  #   (注意：此插件本身不计时，需要外部机制配合)
  player_pvp_protection:
    name: "临时PVP保护"
    scope: "player"
    type: "STRING"
    initial: "false"
    limitations:
      persistable: false # 不保存到数据库
```

