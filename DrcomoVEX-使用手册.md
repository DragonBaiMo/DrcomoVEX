## **文档一：DrcomoVEX 插件核心指南**

### **1. 功能概述**

`DrcomoVEX` 是一个为 Minecraft 服务器设计的、功能强大的变量管理系统。它允许服务器管理员创建、配置和操控各种动态或静态的数值、文本及列表，并将这些变量与玩家、服务器状态乃至其他插件（如 PlaceholderAPI）深度整合。

**核心特性包括：**

  * **多维变量体系**：支持创建玩家专属变量（`player`）和服务器全局变量（`global`）。
  * **丰富的数据类型**：支持整数（`INT`）、浮点数（`DOUBLE`）、字符串（`STRING`）和列表（`LIST`）。
  * **动态表达式计算**：变量的值可以是一个动态计算的表达式，例如 `${player_level} * 10`，实现数值的自动关联。
  * **周期性自动重置**：可以设定变量按分钟、日、周、月、年或自定义 Cron 表达式自动重置为初始值，非常适合用于任务、签到等系统。
  * **PlaceholderAPI 集成**：无缝对接 PlaceholderAPI，可以在变量定义中引用其他插件的占位符（如 `%player_level%`），也可以将本插件的变量作为占位符给其他插件使用。
  * **强大的指令系统**：通过 `/vex` 指令，可以对单个或批量变量进行获取、设置、增减、重置等操作，支持通配符 `*` 和条件筛选。
  * **精细的约束与限制**：可以为变量设置数值范围、长度限制、只读状态、是否持久化存储等高级属性。

### **2. 典型应用场景**

  * **经济系统**：创建 `player_money`, `player_points` 等变量作为玩家货币。
  * **等级与经验**：`player_level`, `player_exp` 记录玩家成长。
  * **每日/每周任务**：利用 `daily_task_progress` 等周期性变量记录玩家任务进度，到点自动重置。
  * **签到系统**：`daily_reward_claimed` 记录玩家当日是否已签到。
  * **动态属性**：`player_combat_power` 根据玩家等级、装备等信息动态计算战斗力。
  * **服务器状态监控**：`server_tps`, `server_online_count` 等全局变量实时反映服务器状态。

### **3. 核心概念说明**

| 概念 | 解释 | 示例 |
| :--- | :--- | :--- |
| **变量 (Variable)** | 一个可配置的数据单元，拥有唯一的键名（Key）和一系列属性。 | `player_money`, `server_motd` |
| **键名 (Key)** | 变量的唯一标识符，在指令和配置中用于引用该变量。 | `player_level` |
| **作用域 (Scope)** | 定义变量归属。`player` 表示每个玩家独立拥有一份；`global` 表示全服共享一份。 | `scope: "player"` |
| **值类型 (Type)** | 变量存储的数据格式，如 `INT` (整数), `STRING` (字符串)。 | `type: "INT"` |
| **初始值 (Initial)** | 变量被创建或重置时的默认值。可以是静态值，也可以是动态表达式。 | `initial: 0` 或 `initial: "${player_level} * 10"` |
| **周期 (Cycle)** | 变量的自动重置规则。可以是预设周期（`daily`）或 Cron 表达式。 | `cycle: "daily"` 或 `cycle: "0 0 * * * ?"` |
| **限制 (Limitations)** | 对变量行为的额外约束，如只读、数值范围等。 | `min: 0`, `max: 100`, `read-only: true` |

-----

## **文档二：主配置文件 `config.yml` 详解**

`config.yml` 是 `DrcomoVEX` 的核心配置文件，负责数据库连接、数据保存策略、周期性任务和调试等级等基础设置。

### **1. 配置结构说明**

```yaml
# 数据库配置
database:
  type: "sqlite" # 数据库类型: sqlite 或 mysql
  
  # SQLite 相关配置 (当 type 为 "sqlite" 时生效)
  file: "drcomovex.db" # SQLite 数据库文件名
  
  # MySQL 相关配置 (当 type 为 "mysql" 时生效)
  mysql:
    host: "localhost"
    port: 3306
    database: "drcomovex"
    username: "root"
    password: "password"
    useSSL: false
  
  # 数据库连接池配置 (主要用于 MySQL)
  pool:
    minimum-idle: 3
    maximum-pool-size: 12
    connection-timeout: 15000
    idle-timeout: 300000
    max-lifetime: 900000

# 数据保存配置
data:
  auto-save: true # 是否启用自动保存
  save-interval-minutes: 3 # 自动保存间隔（分钟）
  save-on-player-quit: true # 玩家退出时是否立即保存其数据

# 周期性重置配置
cycle:
  enabled: true # 是否启用周期性重置功能
  check-interval-seconds: 1 # 检查周期是否到达的时间间隔（秒）
  timezone: "Asia/Shanghai" # 时区设置，影响 daily, weekly 等周期的计算

# 基本设置
settings:
  check-updates: true # 是否检查插件更新
  notify-ops: true # 是否通知 OP 更新信息

# 调试配置
debug:
  level: "INFO" # 日志级别: DEBUG, INFO, WARN, ERROR
```

### **2. 参数详解**

#### **`database` (数据库)**

  * `type`: 决定插件使用哪种数据库存储数据。
      * `sqlite`: 轻量级文件数据库，无需额外安装，数据存储在 `plugins/DrcomoVEX/drcomovex.db` 文件中，适合中小型服务器。
      * `mysql`: 关系型数据库，性能更强，适合大型或跨服服务器。需要您自行搭建 MySQL 服务。
  * `mysql`: 仅当 `type` 为 `mysql` 时需要配置此部分，填入您的 MySQL 服务器连接信息。
  * `pool`: 连接池配置，优化与 MySQL 的连接性能。通常保持默认即可，专业用户可根据服务器负载调整。

#### **`data` (数据保存)**

  * `auto-save`: 是否按 `save-interval-minutes` 定义的周期自动将内存中的变量数据保存到数据库。强烈建议保持 `true`。
  * `save-interval-minutes`: 自动保存的周期，单位为分钟。较小的值能减少数据丢失风险，但会增加磁盘 I/O。
  * `save-on-player-quit`: 玩家离开服务器时，是否立即将其所有变量数据写入数据库。建议保持 `true`，确保玩家数据不丢失。

#### **`cycle` (周期性重置)**

  * `enabled`: 周期性重置功能的总开关。若为 `false`，所有变量的 `cycle` 配置都将失效。
  * `check-interval-seconds`: 插件检查是否有变量需要重置的频率。`1` 表示每秒检查一次，能保证重置的精确性。可以适当调大（如 `60`）以降低性能消耗，但这可能导致重置时间有最多 `60` 秒的延迟。
  * `timezone`: **极为重要**。用于计算 `daily`, `weekly` 等预设周期的准确时间点。请设置为您服务器所在地的时区ID，如中国的 `Asia/Shanghai`，美国的 `America/New_York`。

#### **`debug` (调试)**

  * `level`: 控制插件在控制台输出日志的详细程度。
      * `INFO`: 默认级别，只输出关键信息。
      * `DEBUG`: 输出非常详细的调试信息，用于排查问题，平时不建议开启。
      * `WARN`: 只输出警告信息。
      * `ERROR`: 只输出错误信息。

-----

## **文档三：变量定义文件 `variables/*.yml` 详解**

所有变量都在 `plugins/DrcomoVEX/variables/` 目录下的 `.yml` 文件中定义。您可以创建多个文件来分类管理变量，例如 `economy.yml`, `tasks.yml` 等。

### **1. 文件结构**

每个变量定义文件都以 `variables:` 作为根节点，其下是各个变量的定义。

```yaml
variables:
  # 变量1的定义
  variable_key_1:
    # ...属性...

  # 变量2的定义
  variable_key_2:
    # ...属性...
```

### **2. 变量属性详解**

| 属性 | 类型 | 是否必须 | 描述 |
| :--- | :--- | :--- | :--- |
| **`name`** | 字符串 | 否 | 变量的显示名称，方便识别。 |
| **`scope`** | 字符串 | 否 | **作用域**。`"player"` (默认) 或 `"global"`。 |
| **`type`** | 字符串 | 否 | **值类型**。`"INT"`, `"DOUBLE"`, `"STRING"`, `"LIST"`。若不填，会根据 `initial` 自动推断。 |
| **`initial`** | 任意 | 是 | **初始值**。可以是静态值，也可以是包含占位符和计算的动态表达式字符串。 |
| **`cycle`** | 字符串 | 否 | **重置周期**。预设值：`"minute"`, `"daily"`, `"weekly"`, `"monthly"`, `"yearly"`；或 Quartz Cron 表达式。 |
| **`min`** | 数值 | 否 | **最小值**。仅对 `INT` 和 `DOUBLE` 类型有效。 |
| **`max`** | 数值 | 否 | **最大值** 或 **最大长度/数量**。对 `INT`, `DOUBLE` 是最大值；对 `STRING` 是最大长度；对 `LIST` 是最大条目数。 |
| **`limitations`** | 对象 | 否 | **高级限制**。 |
| `limitations.read-only` | 布尔 | 否 | `true` 则变量无法通过指令修改。 |
| `limitations.persistable` | 布尔 | 否 | `false` 则变量的值不会被保存到数据库，每次重启都恢复初始值。 |

### **3. 实际配置示例**

#### **示例1：基础玩家金币变量**

```yaml
variables:
  player_money:
    name: "玩家金币"
    scope: "player"
    type: "DOUBLE"
    initial: 100.0
    min: 0
    max: 1000000.0
```

  * **解读**：
      * 这是一个名为 `player_money` 的玩家变量。
      * 每个玩家初始拥有 `100.0` 金币。
      * 金币数量不能低于 `0`，不能高于 `1,000,000.0`。

#### **示例2：每日任务进度（每日重置）**

```yaml
variables:
  daily_kill_zombies:
    name: "每日击杀僵尸数"
    scope: "player"
    type: "INT"
    initial: 0
    cycle: "daily"
```

  * **解读**：
      * `daily_kill_zombies` 用于记录玩家每日击杀僵尸的数量。
      * `cycle: "daily"` 确保这个变量在 `config.yml` 设定的时区的每天零点自动重置为 `0`。

#### **示例3：动态计算的玩家战斗力**

```yaml
variables:
  player_level:
    name: "玩家等级"
    scope: "player"
    type: "INT"
    initial: 1
    
  player_combat_power:
    name: "玩家战斗力"
    scope: "player"
    type: "INT"
    initial: "${player_level} * 100 + %player_health% * 10"
    limitations:
      read-only: true
```

  * **解读**：
      * `player_combat_power` 是一个只读的动态变量。
      * 它的值由公式 `${player_level} * 100 + %player_health% * 10` 实时计算得出。
          * `${player_level}` 引用了本插件定义的另一个变量 `player_level`。
          * `%player_health%` 引用了 PlaceholderAPI 提供的玩家生命值占位符。

#### **示例4：使用 Cron 表达式的全局变量**

```yaml
variables:
  event_status:
    name: "活动状态"
    scope: "global"
    type: "STRING"
    initial: "未开始"
    # Cron 表达式：每周六、周日的18点到22点之间，每5分钟
    cycle: "0 */5 18-22 * * SAT,SUN"
```

  * **解读**：
      * `event_status` 是一个全局变量，用于标识服务器活动状态。
      * 通过 Cron 表达式，这个变量会在每周六、日的 18:00 至 22:55 之间，每隔5分钟就被重置为 `"未开始"`。您可以在活动开始时通过指令修改它，活动期间它会保持您修改的值，直到下个5分钟周期点再次被重置。

-----

## **文档四：指令用法 `/vex` 大全**

`/vex` 是插件的主指令，用于管理所有变量。别名：`/dvex`, `/drcomo`。

### **1. 指令结构**

```
/vex <scope> <operation> [parameters...] [flags]
```

  * **`scope`**: `player` 或 `global`
  * **`operation`**: `get`, `set`, `add`, `remove`, `reset`
  * **`parameters`**: 操作所需的参数，如玩家名、变量名、值。
  * **`flags`**: 可选标志，如 `-n` (预演) 或 `--limit N` (限制数量)。

### **2. 玩家变量操作 (`/vex player ...`)**

| 指令 | 权限 | 描述 |
| :--- | :--- | :--- |
| `/vex player get <玩家> <变量>` | `drcomovex.command.player.get` | 获取指定玩家的变量值。 |
| `/vex player set <玩家> <变量> <值>` | `drcomovex.command.player.set` | 设置玩家变量的值。 |
| `/vex player add <玩家> <变量> <值>` | `drcomovex.command.player.add` | 为玩家的数值或列表变量增加值。 |
| `/vex player remove <玩家> <变量> <值>`| `drcomovex.command.player.remove`| 从玩家的数值或列表变量移除值。 |
| `/vex player reset <玩家> <变量>` | `drcomovex.command.player.reset`| 将玩家的变量重置为初始值。 |

  * 操作其他玩家需要额外权限，如 `drcomovex.command.player.set.others`。

### **3. 全局变量操作 (`/vex global ...`)**

| 指令 | 权限 | 描述 |
| :--- | :--- | :--- |
| `/vex global get <变量>` | `drcomovex.command.global.get` | 获取全局变量的值。 |
| `/vex global set <变量> <值>` | `drcomovex.command.global.set` | 设置全局变量的值。 |
| `/vex global add <变量> <值>` | `drcomovex.command.global.add` | 为全局变量增加值。 |
| `/vex global remove <变量> <值>` | `drcomovex.command.global.remove`| 从全局变量移除值。 |
| `/vex global reset <变量> <值>` | `drcomovex.command.global.reset`| 将全局变量重置为初始值。 |

### **4. 批量与通配符操作**

这是 `DrcomoVEX` 非常强大的功能，允许对多个玩家或变量进行批量操作。

  * **玩家通配符**: 在玩家名字段使用 `*` 代表所有在线玩家。
  * **变量通配符**: 在变量名中使用 `*` 匹配任意字符序列。
      * `*_money` 匹配所有以 `_money` 结尾的变量。
      * `daily_*` 匹配所有以 `daily_` 开头的变量。
      * `*kill*` 匹配所有包含 `kill` 的变量。
  * **条件筛选**: 在变量通配符后附加 `:[条件]` 来筛选数值。支持 `>` `>=` `<` `<=` `==` `!=`。
  * **预演模式**: 在指令末尾添加 `-n` 或 `--dry-run`，指令将只显示会影响哪些目标，而不会实际执行。
  * **数量限制**: 在指令末尾添加 `--limit <数量>` 来限制批量操作影响的目标数量。

#### **批量操作示例**

  * **查询所有在线玩家的金币**

    ```
    /vex player get * player_money
    ```

  * **给所有在线玩家增加 100 经验值，但只影响前 50 人**

    ```
    /vex player add * player_exp 100 --limit 50
    ```

  * **重置所有每日任务变量**

    ```
    /vex player reset * daily_*
    ```

  * **查询所有金币大于 10000 的玩家的金币数量**

    ```
    /vex player get * player_money:>10000
    ```

  * **预演：将所有玩家等级低于10级的玩家等级设置为10**

    ```
    /vex player set * player_level:<10 10 -n
    ```

### **5. 其他指令**

| 指令 | 权限 | 描述 |
| :--- | :--- | :--- |
| `/vex reload` | `drcomovex.admin.reload` | 重载所有配置文件。 |
| `/vex help` | `drcomovex.command.help` | 显示帮助信息。 |

-----

## **文档五：PlaceholderAPI 集成指南**

`DrcomoVEX` 与 PlaceholderAPI 深度集成，既能“消费”也能“生产”占位符。

### **1. 在 DrcomoVEX 中使用其他插件的占位符**

您可以在变量的 `initial` 表达式中直接使用标准的 PlaceholderAPI 占位符。

**示例：创建一个显示玩家 Vault 插件余额的变量**

```yaml
# 在 variables/default.yml 中
variables:
  player_vault_balance:
    name: "玩家Vault余额"
    scope: "player"
    type: "DOUBLE"
    initial: "%vault_eco_balance%" # 直接引用 Vault 的占位符
    limitations:
      read-only: true
```

现在，`player_vault_balance` 的值会实时反映玩家的 Vault 余额。

### **2. 将 DrcomoVEX 变量作为占位符给其他插件使用**

`DrcomoVEX` 提供了三种格式的占位符供其他插件（如计分板、聊天格式化插件）使用。

| 占位符格式 | 描述 | 示例 |
| :--- | :--- | :--- |
| `%drcomovex_[var]_[变量名]%` | **通用格式**。自动判断变量是 `player` 还是 `global` 类型并返回值。对于 `player` 变量，它会根据当前上下文的玩家返回值。 | `%drcomovex_[var]_player_money%` |
| `%drcomovex_[global]_[变量名]%` | **全局变量专用**。强制获取一个 `global` 变量的值。 | `%drcomovex_[global]_server_motd%` |
| `%drcomovex_[player]_[变量名]%` | **玩家变量专用**。强制获取一个 `player` 变量的值。 | `%drcomovex_[player]_player_level%` |
| `%drcomovex_[player]_[变量名]_[玩家名]%` | **指定玩家的变量**。获取指定玩家的变量值，而不是当前上下文的玩家。 | `%drcomovex_[player]_player_level_Notch%` |

  * **注意**: `[var]`, `[global]`, `[player]` 两侧的方括号是占位符格式的一部分，必须保留。

**使用场景示例（以 DeluxeMenus 为例）**

```yaml
# 在 DeluxeMenus 的菜单配置中
item:
  material: GOLD_INGOT
  display_name: '&e我的金币'
  lore:
    - '&7当前金币: &f%drcomovex_[var]_player_money%'
    - '&7服务器公告: &a%drcomovex_[global]_server_motd%'
```

-----

## **示例配置文件合集**

### **示例一：基础经济与等级系统 (`economy.yml`)**

```yaml
# /plugins/DrcomoVEX/variables/economy.yml
variables:
  # 玩家金币
  player_money:
    name: "玩家金币"
    scope: "player"
    type: "DOUBLE"
    initial: 100.0
    min: 0
    max: 9999999.0
    
  # 玩家点券
  player_points:
    name: "玩家点券"
    scope: "player"
    type: "INT"
    initial: 0
    min: 0

  # 玩家等级
  player_level:
    name: "玩家等级"
    scope: "player"
    type: "INT"
    initial: 1
    min: 1
    max: 100

  # 玩家经验
  player_exp:
    name: "玩家经验"
    scope: "player"
    type: "INT"
    initial: 0
    min: 0

  # 升级所需经验（动态计算）
  exp_to_next_level:
    name: "升级所需经验"
    scope: "player"
    type: "INT"
    # 表达式：100 * (玩家等级 ^ 1.5)
    initial: "100 * pow(${player_level}, 1.5)"
    limitations:
      read-only: true
```

### **示例二：任务与签到系统 (`tasks.yml`)**

```yaml
# /plugins/DrcomoVEX/variables/tasks.yml
variables:
  # 每日签到状态
  daily_check_in:
    name: "每日签到"
    scope: "player"
    type: "STRING" # 使用 "true" / "false"
    initial: "false"
    cycle: "daily" # 每日重置

  # 每日击杀10个僵尸的任务进度
  task_kill_10_zombies:
    name: "任务-击杀僵尸"
    scope: "player"
    type: "INT"
    initial: 0
    cycle: "daily"
    max: 10
    
  # 每周在线时长（分钟）
  weekly_playtime:
    name: "周在线时长"
    scope: "player"
    type: "INT"
    initial: 0
    cycle: "weekly" # 每周重置

  # 每小时可领取的在线奖励状态
  hourly_reward_status:
    name: "每小时在线奖励"
    scope: "player"
    type: "STRING"
    initial: "available" # 可领取
    # Cron: 每小时的0分0秒重置
    cycle: "0 0 * * * ?"
```

### **示例三：服务器状态监控 (`server_stats.yml`)**

```yaml
# /plugins/DrcomoVEX/variables/server_stats.yml
variables:
  # 服务器公告
  server_motd:
    name: "服务器公告"
    scope: "global"
    type: "STRING"
    initial: "&a欢迎来到我们的服务器！&e祝您游戏愉快！"
    
  # 服务器TPS（动态只读）
  server_tps:
    name: "服务器TPS"
    scope: "global"
    type: "DOUBLE"
    initial: "%server_tps_1%"
    limitations:
      read-only: true
      
  # 在线人数（动态只读）
  server_online:
    name: "在线人数"
    scope: "global"
    type: "INT"
    initial: "%server_online%"
    limitations:
      read-only: true
      
  # 是否开启双倍经验活动
  double_exp_event:
    name: "双倍经验活动"
    scope: "global"
    type: "STRING" # 使用 "true" / "false"
    initial: "false"
```

### **示例四：PVP 与玩家属性 (`pvp.yml`)**

```yaml
# /plugins/DrcomoVEX/variables/pvp.yml
variables:
  # 玩家击杀数
  player_kills:
    name: "玩家击杀数"
    scope: "player"
    type: "INT"
    initial: 0
    min: 0

  # 玩家死亡数
  player_deaths:
    name: "玩家死亡数"
    scope: "player"
    type: "INT"
    initial: 0
    min: 0
    
  # K/D 比率 (动态计算)
  player_kdr:
    name: "K/D 比率"
    scope: "player"
    type: "DOUBLE"
    # 表达式: 击杀数 / (死亡数，但如果死亡数为0则视为1，避免除零错误)
    initial: "${player_kills} / max(1, ${player_deaths})"
    limitations:
      read-only: true

  # 玩家 PVP 开关
  pvp_enabled:
    name: "PVP开关"
    scope: "player"
    type: "STRING"
    initial: "true"
    
  # 不会被保存的临时PVP保护状态
  pvp_protection_temp:
    name: "临时PVP保护"
    scope: "player"
    type: "STRING"
    initial: "false"
    limitations:
      persistable: false # 重启后失效
```