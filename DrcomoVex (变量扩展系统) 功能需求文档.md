### **项目名称：DrcomoVex (变量扩展系统) 功能需求文档**

  * **文档版本：** 1.1 (扩展版)
  * **创建日期：** 2025年8月1日
  * **文档作者：** Dragon 
  * **目标概述：** 本文档旨在以前所未有的深度，明确定义 DrcomoVex 插件的核心功能、架构设计与交互细节。项目目标是将现有变量系统彻底重构并升华为一个功能全面、性能卓越、扩展性强的下一代 Minecraft 变量管理框架。DrcomoVex 将作为服务器数据的心脏，为所有复杂玩法、经济系统、任务逻辑和跨服交互提供坚实、可靠且灵活的数据支持，并致力于为服务器管理者与开发者提供极致的使用体验。

-----

### **第一部分：数据持久化与核心架构 (Data Persistence & Core Architecture)**

#### **1. 全面的数据库支持 (Comprehensive Database Support)**

  * **1.1. 功能综述**
    此模块是整个 DrcomoVex 系统的基石。我们将彻底摒弃原有设计中对 YML 等平面文件的依赖，转而采用工业级的数据库系统对所有变量数据进行统一的持久化管理。这一变革旨在从根本上解决数据一致性、并发读写安全、高频操作性能瓶颈以及服务器意外崩溃时的数据丢失风险，为系统的可伸缩性、稳定性与未来的跨服数据同步功能铺平道路。

  * **1.2. 具体需求规格**

      * **1.2.1. 多数据库驱动兼容 (Multi-Database Driver Compatibility)**

          * **首期强制支持：**
              * **MySQL:** 包括所有主流分支，如 MariaDB。这是面向中大型、高并发生产环境服务器的首选。
              * **SQLite:** 作为插件的内置默认选项，适用于小型服务器或测试环境，实现真正的“开箱即用”，无需用户进行任何外部数据库的安装与配置。
          * **配置文件设计 (`config.yml`)**
              * 必须在配置文件中提供一个结构清晰、注释详尽的 `database` 配置节。
              * 配置项应包括：
                  * `type`: 数据库类型 (可填 `mysql` 或 `sqlite`)。
                  * `mysql-settings`:
                      * `host`: 数据库服务器地址。
                      * `port`: 数据库服务器端口。
                      * `database`: 数据库名称。
                      * `username`: 用户名。
                      * `password`: 密码。
                      * `connection-pool`:
                          * `maximum-pool-size`: 最大连接数。
                          * `minimum-idle`: 最小空闲连接数。
                          * `connection-timeout`: 连接超时时间（毫秒）。
                          * `extra-properties`: 用于填写如 `useSSL=false` 等额外 JDBC 连接参数的键值对。
          * **自动降级逻辑 (Auto-Fallback Mechanism)**
              * 插件启动时，若 `type` 配置为 `mysql`，但任何连接参数缺失或连接失败，系统不得直接报错停止运行。
              * 此时，系统应在控制台打印一条明确的警告信息（例如：“[DrcomoVex] MySQL 连接失败，已自动切换至内置的 SQLite 数据库模式。请检查您的 config.yml 配置。所有数据将保存在本地文件中。”），然后无缝切换到 SQLite 模式继续运行。

      * **1.2.2. 数据表的统一化与自动化管理 (Unified & Automated Table Management)**

          * **统一数据模型：** 所有作用域、所有类型的变量（包括 `GLOBAL`, `PLAYER`, `WORLD`, `SERVER` 等，详见后续章节）都必须存储在数据库的同一套表结构中。
          * **自动化表结构部署：**
              * 插件首次在连接一个新数据库时启动，必须能够自动执行 SQL `CREATE TABLE IF NOT EXISTS` 语句，创建所有必需的数据表和索引。
              * 表的字段设计应具备前瞻性，例如，`variable_name`, `scope_type`, `scope_identifier` (如玩家UUID、世界名), `variable_type`, `value_string`, `value_double`, `value_text` 等，以支持不同类型的数据存储。
              * 后续插件更新若涉及数据库表结构变更，应提供平滑的升级方案，例如通过启动时检测版本号并自动执行 `ALTER TABLE` 脚本。

      * **1.2.3. 高性能缓存与写入策略 (High-Performance Caching & Write Strategy)**

          * **缓存核心：** 引入一个多级缓存系统（例如，使用 Google Guava Cache 或 Caffeine），将频繁读写的数据缓存在内存中，作为数据库的快速读取代理。
          * **写入策略——“写后” (Write-Behind Caching):**
              * 当一个变量的值被修改时（通过指令或API），该变更应立即更新至内存缓存，并被标记为“脏数据”（Dirty）。
              * 同时，该变更操作将被放入一个异步处理队列中。
              * 一个独立的、低优先级的“数据库写入线程”将从该队列中批量取出“脏数据”，以合并的、优化的方式（例如，使用 `INSERT ... ON DUPLICATE KEY UPDATE` 或 `REPLACE INTO`）将其写入数据库。
              * 此机制可将成百上千次的微小写入操作合并为少数几次数据库事务，极大降低 I/O 压力，提升服务器主线程的响应速度。
          * **安全关停 (Safe Shutdown):** 服务器正常关闭（`/stop` 指令）时，插件必须确保将缓存和异步队列中的所有“脏数据”全部强制写入数据库后，才能完成卸载流程，确保数据零丢失。

-----

### **第二部分：变量类型与作用域扩展 (Variable Types & Scopes Expansion)**

#### **2. 周期性变量 (Periodic Variables)**

  * **2.1. 功能综述**
    周期性变量是 DrcomoVex 的一项核心创新功能。它允许服务器管理员创建一种能够按照预设的时间规律（每日、每周、每月）自动重置为其初始值的特殊变量。此功能为实现各类循环性玩法（如每日签到、每周任务积分、每月贡献榜）提供了原生、高效且极其可靠的底层支持，将开发者和服主从繁琐、易错的手动或脚本重置任务中解放出来。

  * **2.2. 详细配置规格 (Detailed Configuration Specification)**
    周期性变量的定义将在变量的配置文件（例如 `variables.yml`）中通过添加一个 `periodic` 属性块来实现。

      * **2.2.1. 配置文件结构示例 (YAML Structure Example)**

        ```yaml
        variables:
          # 示例一：每日签到标记 (每日重置)
          player_daily_signin:
            type: BOOLEAN
            initial_value: false
            scope: PLAYER
            periodic:
              cycle: DAILY
              reset_time: "00:00:00" # 24小时制时间
              timezone: "Asia/Shanghai" # 时区，支持所有Java标准时区ID

          # 示例二：每周活跃度积分 (每周一凌晨4点重置)
          player_weekly_activity:
            type: DOUBLE
            initial_value: 0.0
            scope: PLAYER
            periodic:
              cycle: WEEKLY
              day_of_week: MONDAY # 可选值: MONDAY, TUESDAY, ..., SUNDAY
              reset_time: "04:00:00"
              timezone: "Asia/Shanghai"

          # 示例三：每月VIP福利领取状态 (每月1号重置)
          player_monthly_vip_reward:
            type: STRING
            initial_value: "UNCLAIMED"
            scope: PLAYER
            periodic:
              cycle: MONTHLY
              day_of_month: 1 # 1-31
              reset_time: "00:00:00"
              timezone: "Asia/Shanghai"

          # 示例四：每月最后一天重置的特殊变量
          server_monthly_top_donor:
            type: STRING
            initial_value: "无"
            scope: GLOBAL
            periodic:
              cycle: MONTHLY
              day_of_month: -1 # 特殊值 -1 代表当月的最后一天
              reset_time: "23:59:59"
              timezone: "Asia/Shanghai"
        ```

      * **2.2.2. 配置字段详解**

          * `periodic`: 周期性功能的总开关和配置容器。只有存在此节的变量才会被视为周期性变量。
          * `cycle`: (必须) 定义重置的周期。
              * `DAILY`: 每日。
              * `WEEKLY`: 每周。
              * `MONTHLY`: 每月。
          * `reset_time`: (必须) 定义在周期日当天，执行重置操作的具体时间。格式为 `HH:mm:ss` 的24小时制字符串。
          * `timezone`: (可选，默认为服务器系统时区) 定义 `reset_time` 所基于的时区。必须支持标准的时区ID（例如 `UTC`, `GMT+8`, `America/New_York`）。此项对于全球多区域玩家的服务器至关重要。
          * `day_of_week`: (当 `cycle` 为 `WEEKLY` 时必须) 定义每周的哪一天进行重置。值不区分大小写，支持全名（`MONDAY`）或缩写（`MON`）。
          * `day_of_month`: (当 `cycle` 为 `MONTHLY` 时必须) 定义每月的哪一天进行重置。
              * 有效值为 `1` 到 `31` 的整数。
              * **特殊值 `-1`:** 代表“当月的最后一天”。此设计可优雅地处理大月、小月及闰年的情况（例如，二月将在28或29号重置，四月将在30号重置）。
              * **逻辑健壮性:** 如果配置了一个在某些月份不存在的日期（例如，`31`日），系统在小月时必须自动选择该月的最后一天执行重置。

  * **2.3. 核心调度与执行逻辑 (Core Scheduling & Execution Logic)**

      * **2.3.1. 调度器 (Scheduler)**

          * 插件内部必须维护一个高精度的异步任务调度器。
          * 插件启动时，它会加载所有周期性变量的配置，计算出每个变量下一次的精确重置时间点，并将其注册到调度器中。
          * 调度器会在指定的时间点唤醒，并为对应的变量触发重置流程。

      * **2.3.2. “追赶”机制 (Catch-up Mechanism)**

          * 这是一个关键的容错设计，用于处理服务器在预定重置时间点处于离线状态的情况（例如，日常维护、崩溃重启）。
          * **实现逻辑:**
            1.  插件每次启动时，以及玩家每次登录时，都会检查所有周期性变量。
            2.  对于每个变量，系统会从数据库或一个专门的状态文件中读取其“上一次重置的精确时间戳”。
            3.  系统将此时间戳与当前时间进行比较。如果发现两者之间已经跨过了一个或多个预定的重置时间点，系统将立即为该玩家（或全局）执行一次“补偿性”的重置操作。
            4.  **示例:** 一个每日0点重置的变量，服务器从周一下午停机，到周三上午才重启。当玩家周三上午登录时，系统会检测到错过了周二的重置，并立即为该玩家执行一次重置。

  * **2.4. 重置行为与事件 (Reset Behavior & Events)**

      * **2.4.1. 重置定义**

          * “重置”操作的唯一定义是：将变量的当前值修改为其在配置文件中定义的 `initial_value`。

      * **2.4.2. 事件系统 (`VariableResetEvent`)**

          * 每当一个变量因为周期性规则而被重置时（无论是通过调度器自动触发还是“追赶”机制补偿性触发），都必须触发一个 `VariableResetEvent` 事件。
          * 此事件必须是可取消的 (`Cancellable`)。如果其他插件监听此事件并将其取消，则本次重置操作将不会发生。
          * **事件包含的方法:**
              * `getVariable()`: 返回被重置的变量的配置对象。
              * `getPlayer()`: 如果是玩家变量，返回关联的 `Player` 对象。
              * `getScope()`: 返回变量的作用域。
              * `getOldValue()`: 获取重置前的值。
              * `getNewValue()`: 获取即将被设置的初始值。
              * `getResetCause()`: 返回一个枚举类型，说明触发原因，例如 `AUTOMATIC_SCHEDULE` (自动调度), `CATCH_UP_RESET` (追赶重置), `MANUAL_COMMAND` (手动指令)。
              * `isCancelled()` / `setCancelled(boolean)`: 标准的事件取消方法。

  * **2.5. 交互与查询 (Interaction & Query)**

      * **2.5.1. 管理指令 (Admin Commands)**

          * `/svar force_reset <variable_name> [--player <player_name>]`: 手动强制重置一个指定的周期性变量，不受时间限制。此操作同样会触发 `VariableResetEvent`，其 `getResetCause()` 返回 `MANUAL_COMMAND`。

      * **2.5.2. PlaceholderAPI 集成 (PAPI Integration)**

          * 为提供前端显示能力（如在计分板、Hologram上显示倒计时），DrcomoVex 必须注册一系列新的 PAPI 占位符。
          * **占位符格式:**
              * `%drcomovex_resets_in_seconds_<variable_name>%`: 显示指定变量距离下一次重置的剩余秒数。
              * `%drcomovex_resets_in_formatted_<variable_name>%`: 显示格式化后的剩余时间，例如 `1天2小时3分4秒`。插件应提供一个可自定义此格式的配置文件部分。
              * `%drcomovex_last_reset_time_<variable_name>%`: 显示该变量上一次重置的时间。

#### **3. 动态变量 (Dynamic Variables)**

  * **3.1. 功能综述**
    动态变量是 DrcomoVex 的一项核心表现力功能。它打破了传统变量值只能是静态数据的局限，允许服务器管理员在一个变量的值（通常是字符串类型）中嵌入其他变量的引用或标准化的占位符（如 PlaceholderAPI）。当该变量被外部系统请求时，DrcomoVex 会在瞬间实时地解析这些占位符，并将它们替换为对应的数据，最终返回一个动态生成的、内容丰富的字符串。此功能是构建高度信息化、个性化和情境化游戏界面的利器，例如，可以轻松创建一个动态显示玩家姓名、生命值、等级和所在世界名称的“玩家信息面板”变量。

  * **3.2. 核心实现机制 (Core Implementation Mechanism)**

      * **3.2.1. 解析触发时机：即时解析 (Parsing Trigger Point: On-the-fly Resolution)**

          * **绝对原则：** 动态变量的解析操作**必须**在“变量被请求时”（即通过 API `getVariable()` 或指令 `/svar get` 调用时）触发，而**绝不能**在“变量被设置时”执行。
          * **原因与重要性：** 这一设计确保了变量所呈现的数据具有最高的实时性。例如，一个变量值为 `"玩家 %player_name% 的当前生命值: %player_health%/%player_max_health%"`，每次获取该变量，`%player_health%` 都会被实时替换为玩家当前的生命值，而不是设置变量那一刻的旧数据。

      * **3.2.2. 支持的占位符类型 (Supported Placeholder Types)**

          * **PlaceholderAPI (PAPI) 原生集成：**
              * DrcomoVex 必须能自动检测并无缝集成服务器上已安装的 PlaceholderAPI。
              * 系统必须能够正确解析任何有效的 PAPI 占位符，包括由其他插件注册的占位符。
          * **内部变量引用 (Internal Variable Reference)：**
              * 系统需支持引用 DrcomoVex 内部管理的其他变量。
              * 为避免与 PAPI 语法冲突，内部引用必须使用一套独特且清晰的语法，建议采用 `${scope:variable_name}` 的格式。
                  * **示例 1：** 引用一个全局变量 `${global:server_event_name}`。
                  * **示例 2：** 引用目标玩家自己的另一个变量 `${player:player_title}`。
                  * **示例 3：** 引用指定玩家的变量 `${player.Notch:player_kills}`。
              * 这种设计允许管理员创建复杂的、层层嵌套的“组合变量”。

      * **3.2.3. 递归解析与安全防护 (Recursive Parsing & Safety Measures)**

          * **递归深度限制 (Recursion Depth Limit)：**
              * 为防止因变量之间过度嵌套引用（例如，变量A引用B，B引用C，...，直至Z）而导致的性能问题或栈溢出，必须实现一个递归解析的深度限制。
              * 此限制值应可在 `config.yml` 中配置，例如 `dynamic-variables.max-recursion-depth`，并设置一个合理的默认值，如 `10`。
              * 当解析深度超过此限制时，系统必须立即停止解析，并返回一条明确的错误提示字符串，例如 `"[DrcomoVex: Error - Recursion depth limit exceeded]"`。
          * **循环引用检测 (Circular Reference Detection)：**
              * 这是防止服务器死锁或崩溃的至关重要的安全机制。
              * 在处理每一次 `get` 请求的生命周期内，系统必须维护一个解析路径的记录。
              * 如果在解析链中发现一个变量被重复请求（例如，A -\> B -\> C -\> A），系统必须判定为循环引用。
              * 一旦检测到循环引用，必须立即中断解析过程，并返回一条明确的错误提示字符串，例如 `"[DrcomoVex: Error - Circular reference detected in variable 'A']"`。
          * **解析超时机制 (Parsing Timeout)：**
              * 考虑到某些 PAPI 占位符的解析可能涉及缓慢的外部操作（如查询一个远程网页或数据库），必须为单次变量的完整解析过程设置一个超时阈值。
              * 此阈值应可在 `config.yml` 中配置，例如 `dynamic-variables.parsing-timeout-milliseconds`，默认值可设为 `50` 毫秒。
              * 若解析时间超出该阈值，操作将被强制中止，并返回错误提示，例如 `"[DrcomoVex: Error - Parsing timed out]"`。

  * **3.3. 应用场景与配置 (Use Cases & Configuration)**

      * **适用变量类型 (Applicable Variable Types)**
          * 动态解析功能主要应用于 `STRING` 类型的变量。
          * 对于 `LIST` 类型，可以考虑一个扩展功能：允许列表中的每一个字符串元素都支持动态解析。
      * **配置开关 (Configuration Switch)**
          * 为了避免不必要的性能开销，应在变量的定义中提供一个布尔类型的开关，用于启用或禁用动态解析功能。
          * **示例 (`variables.yml`)：**
            ```yaml
            variables:
              player_info_panel:
                type: STRING
                scope: PLAYER
                dynamic_parsing: true # 启用动态解析
                initial_value: "§e玩家: §f%player_name% §8| §e称号: §f${player:player_title}"
              
              static_message:
                type: STRING
                scope: GLOBAL
                dynamic_parsing: false # (或不写此行，默认为false)
                initial_value: "这是一个不会被解析的静态欢迎语。"
            ```

#### **4. 列表/数组型变量 (List/Array Type Variables)**

  * **4.1. 功能综述**
    列表变量为 DrcomoVex 引入了处理“集合”数据的能力。它允许创建一个可以存储有序的、多个同类型元素（目前主要为字符串或数字）的变量。此功能对于需要记录一系列不确定数量数据的场景是不可或缺的，例如，管理玩家拥有的所有称号、记录一个任务需要收集的物品列表、存储一个家的多个传送点坐标等。

  * **4.2. 数据结构与存储**

      * 在数据库层面，列表的值可以以标准化的格式（如 JSON Array 字符串 `["item1", "item2", "item3"]`）存储在 `value_text` 或类似的文本字段中，以保证通用性和可读性。
      * 在内存缓存中，它应该被表示为 Java 的 `List` 对象，以获得最佳的操作性能。

  * **4.3. 完整的指令集支持 (Comprehensive Command Set Support)**
    必须提供一套完整、直观且功能强大的指令来管理列表变量。所有指令都应支持标准的参数，如 `-p <player_name>` 和 `-s` (silent)。

      * `/svar listadd <variable_name> <value_to_add>`: 向指定列表的末尾添加一个新元素。
      * `/svar listaddat <variable_name> <index> <value_to_add>`: 在指定索引位置插入一个新元素，原有元素及后续元素依次后移。
      * `/svar listremove <variable_name> <value_to_remove>`: 从列表中移除第一个匹配指定值的元素。
      * `/svar listremoveat <variable_name> <index>`: 移除指定索引位置的元素。
      * `/svar listget <variable_name> <index>`: 获取并显示指定索引位置的元素。索引从 `0` 开始。
      * `/svar listset <variable_name> <index> <new_value>`: 修改指定索引位置的元素为新的值。
      * `/svar listcontains <variable_name> <value>`: 检查列表是否包含指定元素，并返回布尔结果（`true` 或 `false`）。
      * `/svar listsize <variable_name>`: 获取并显示列表的当前大小（元素数量）。
      * `/svar listclear <variable_name>`: 清空整个列表，移除所有元素。

  * **4.4. 强大的 API 支持 (Robust API Support)**
    为了让其他开发者能够充分利用此功能，必须在 `DrcomoVexAPI` 中提供与指令功能一一对应的、更为强大的 API 方法。

      * `List<String> getList(String variableName, UUID playerUUID)`: 获取一个完整的列表。
      * `void addToList(String variableName, UUID playerUUID, String value)`: 添加元素。
      * `void removeFromList(String variableName, UUID playerUUID, String value)`: 移除元素。
      * `String getFromList(String variableName, UUID playerUUID, int index)`: 按索引获取。
      * `int getListSize(String variableName, UUID playerUUID)`: 获取大小。
      * 所有修改列表的操作都应触发 `VariableChangeEvent`，事件中应包含修改前和修改后的完整列表。

#### **5. 变量作用域扩展 (Expanded Variable Scopes)**

  * **5.1. 功能综述**
    变量作用域定义了“一个变量在何种情境下生效”。通过扩展作用域，DrcomoVex 将能够实现更加精细和强大的数据隔离与覆盖，以完美适应从单体服务器到复杂的多世界、跨服网络等各种服务器架构。

  * **5.2. 新增作用域定义**
    在现有的 `GLOBAL` 和 `PLAYER` 基础上，新增以下作用域：

      * **`WORLD`**:
          * **定义：** 变量的值与一个特定的 Minecraft 世界绑定。当玩家处于该世界时，此变量的值才会生效。
          * **应用场景：** 创建不同世界拥有不同规则的玩法，例如，“资源世界”的PVP伤害倍率变量，“主城世界”的禁止飞行变量。
      * **`SERVER`**:
          * **定义：** 在使用 BungeeCord 或 Velocity 代理的跨服网络环境下，变量的值与一个特定的子服务器实例绑定。
          * **应用场景：** 为不同的子服务器（如“生存服”、“游戏服”、“创造服”）设置不同的欢迎消息、经济汇率等。

  * **5.3. 变量解析的优先级覆盖规则 (Priority & Override Rules for Resolution)**
    这是作用域系统的核心逻辑。当系统尝试获取一个变量的值时，必须严格遵循一个清晰定义的、从最具体到最通用的优先级顺序进行查找。

      * **标准解析链（以玩家变量为例）：**

        1.  **`PLAYER_WORLD`**: 首先，查找是否存在一个为该玩家**并且**为该玩家当前所在世界定义的变量。这是最高优先级。
        2.  **`PLAYER_SERVER`**: 如果上一级未找到，则查找是否存在一个为该玩家**并且**为当前子服务器定义的变量。
        3.  **`PLAYER`**: 如果上一级未找到，则查找是否存在一个仅为该玩家定义的、不限世界或服务器的通用变量。
        4.  **`WORLD`**: 如果上一级仍未找到，则查找是否存在一个为当前世界定义的全局变量。
        5.  **`SERVER`**: 如果上一级仍未找到，则查找是否存在一个为当前子服务器定义的全局变量。
        6.  **`GLOBAL`**: 如果以上所有层级都未找到，则最后查找全局变量。
        7.  **`INITIAL_VALUE`**: 如果连全局变量都未定义或未赋值，则返回该变量在配置文件中定义的 `initial_value`。
        8.  **`NULL` / `ERROR`**: 如果连 `initial_value` 都没配置，则最终返回 `null` 或抛出一个明确的“变量未定义”错误。

      * **配置方式：** 在定义变量时，可以通过 `scope` 字段来指定其基础作用域，并通过额外的字段来指定具体的标识符。

        ```yaml
        variables:
          pvp_multiplier:
            type: DOUBLE
            initial_value: 1.0
            scope: WORLD # 基础作用域是世界
            scope_identifier: "world_nether" # 绑定到名为 world_nether 的世界
        ```

  * **5.4. 动态变量约束 (Dynamic Variable Constraints)**

    * **5.4.1. 功能综述**
    此功能是 DrcomoVex 变量系统在灵活性上的又一次巨大飞跃。它允许服务器管理员在定义数值类型变量（如 `DOUBLE`, `INTEGER`）的约束条件（`min_value`, `max_value`）以及 `initial_value` 时，不再局限于静态的固定数值，而是可以填入包含 PlaceholderAPI (PAPI) 占位符和基础数学运算的字符串。系统将在\*\*每一次对变量进行写操作时（如 set, add, sub）\*\*实时解析这些字符串，从而为不同的玩家或在不同的情境下，动态地生成一套独一无二的、个性化的约束规则。

    * **5.4.2. 核心实现逻辑：即时解析与应用**

      * **解析时机：** 动态约束的解析**必须**在“写操作执行时”触发。例如，当执行 `/svar set player_mana 50 -p:Notch` 指令时，系统会立即针对玩家 Notch 解析 `player_mana` 变量所配置的 `min_value` 和 `max_value` 字符串。
      * **解析流程：**
        1.  **获取约束字符串：** 系统读取变量配置文件中的 `max_value` 字符串，例如 `"%player_level% * 10 + 50"`。
        2.  **PAPI 解析：** 将字符串中的 PAPI 占位符针对目标玩家进行解析。假设玩家 Notch 当前等级为 15 级，字符串变为 `"15 * 10 + 50"`。
        3.  **数学表达式求值：** 系统内置一个轻量级的数学表达式求值器，计算该字符串的结果，得到最终的动态最大值为 `200`。
        4.  **应用约束：** 使用这个动态计算出的最大值 `200`（以及同样方式计算出的最小值）来判断玩家输入的 `50` 是否合法。如果合法，则成功设置；否则，操作失败并向操作者返回明确的错误信息（如：“操作失败，值必须在 [min, max] 之间”）。
      * **对 `initial_value` 的应用：** 当一个新玩家首次被赋予一个带有动态 `initial_value` 的变量时，系统会执行同样的解析流程，来决定他的初始值。

    * **5.4.3. 配置文件示例**

    ```yaml
    variables:
      # 示例：玩家法力值。最大值与玩家等级挂钩，初始值为最大值的50%。
      player_mana:
        type: DOUBLE
        scope: PLAYER
        # 初始值是基于动态最大值的表达式
        initial_value: "(${variable:max_value}) * 0.5" 
        # 最小值是固定的
        min_value: 0
        # 最大值是一个包含PAPI占位符和运算的动态表达式
        max_value: "%player_level% * 10 + 50"

      # 示例：公会贡献度。基础值(初始值)由玩家的Vault余额决定。
      guild_contribution:
        type: INTEGER
        scope: PLAYER
        # 初始值直接与玩家金钱挂钩，取其万分之一
        initial_value: "%vault_eco_balance% / 10000"
        min_value: 0
        # 无上限
        # max_value: (不填写) 
    ```

    * **5.4.4. 内部变量引用 (`${variable:max_value}`)**
    为了实现类似“初始值为最大值的一半”这种逻辑，系统需支持一种特殊的内部变量引用语法 `${variable:property}`，仅在 `initial_value` 的动态表达式中使用，用于引用该变量自身的其他动态约束属性。

    * **5.4.5. 错误处理**

      * 如果 PAPI 占位符无效或返回非数字内容，导致数学表达式无法求值，则本次写操作必须失败。
      * 系统必须向操作者返回一条清晰的错误信息，指明是哪个约束（`min_value` 或 `max_value`）的表达式解析失败。

-----

### **第三部分：用户交互与开发者生态 (User Interaction & Developer Ecosystem)**

#### **6. 指令参数优化 (Command Parameter Optimization)**

  * **6.1. 功能综述**
    此项改进旨在对 DrcomoVex 的所有用户指令进行一次彻底的现代化革命。我们将摒弃传统插件中僵硬的、严重依赖参数位置的指令格式，转而全面拥抱一种更灵活、更具可读性、更符合现代命令行工具设计哲学的“标志-参数” (Flag-Argument) 语法。这次升级将极大地提升服务器管理员的操作效率和容错率，降低学习成本，并为未来指令功能的扩展奠定坚实的基础。

  * **6.2. 新指令语法设计 (New Command Syntax Design)**

      * **6.2.1. 核心原则**

          * **参数位置无关性:** 除了核心的操作动词（如 `set`, `get`, `add`）和变量名外，所有修饰性的参数（如指定玩家、作用域、是否静默）都应是位置无关的。
          * **清晰的标志:** 每个参数都由一个简短（单破折线 `-`）或完整（双破折线 `--`）的标志引导。
          * **键值对格式:** 对于需要值的参数，采用 `标志:值` 或 `标志 值` 的格式。

      * **6.2.2. 指令语法详尽示例**

        | 操作类型 | 旧指令格式（将被废弃） | 新指令格式 (DrcomoVex) | 语法解析与说明 |
        | :--- | :--- | :--- | :--- |
        | **设置玩家变量** | `/svar set player_kills Notch 100` | `/svar set player_kills 100 -p:Notch` \<br\>或 \<br\> `/svar set player_kills 100 --player Notch` | `-p` 和 `--player` 等效，均为指定玩家的标志。值 `100` 紧跟在变量名之后，逻辑清晰。 |
        | **静默操作** | `/svar set player_kills Notch 101 silent:true` | `/svar set player_kills 101 -p:Notch -s` \<br\>或 \<br\> `/svar set player_kills 101 --silent --player:Notch` | `-s` 或 `--silent` 是一个布尔标志，无需提供值，它的出现即代表“是”。其位置可以任意调换。 |
        | **带作用域的操作** | `/svar set world_rule_pve world_the_end false` | `/svar set world_rule_pve false --scope:WORLD --world:world_the_end` | `--scope` 明确指定作用域类型。`--world` (或 `--server` 等) 用于提供该作用域的具体标识符。 |
        | **复杂组合操作** | (无直接对应) | `/svar listadd player_unlocked_kits "diamond_kit" -p:Steve -s` | 一个组合了 `listadd` 操作、字符串值（带引号以处理空格）、指定玩家和静默标志的复杂指令，新语法下依然保持极高的可读性。 |

      * **6.2.3. 向后兼容性策略 (Backward Compatibility Strategy)**

          * **可选的兼容模式:** 在 `config.yml` 中提供一个名为 `commands.use-legacy-syntax` 的布尔选项，默认为 `false`。
          * **启用兼容模式时:** 当此选项设置为 `true` 时，插件将同时支持新旧两种指令格式。这为老用户提供了一个平滑的过渡期。
          * **废弃警告:** 当服务器管理员使用旧格式的指令时，系统应在控制台（仅对OP或有权限者可见）打印一条一次性的、友好的废弃警告，例如：“[DrcomoVex] 您正在使用即将废弃的旧指令格式。为了获得更好的体验和未来的功能支持，我们强烈建议您切换到新的标志-参数格式。输入 /svar help 查看新用法。”
          * **最终移除:** 在未来的某个主要版本更新（例如 v2.0 -\> v3.0）中，将彻底移除对旧语法的支持，并删除此配置选项。

      * **6.2.4. 智能指令补全 (Intelligent Command Completion)**

          * 指令的 Tab 补全功能是提升用户体验的关键，必须进行全面的重写以匹配新语法。
          * **情境感知补全:**
              * 当输入 `/svar set` 后，Tab 应优先补全已定义的变量名。
              * 当输入变量名和值后，Tab 应补全所有可用的标志，如 `-p`, `--scope`, `-s`。
              * 当输入 `--scope:` 后，Tab 应补全所有可用的作用域类型，如 `PLAYER`, `WORLD`, `GLOBAL`。
              * 当输入 `-p:` 后，Tab 应补全当前在线的玩家名。
              * 当输入 `--world:` 后，Tab 应补全服务器上所有世界的名称。
          * 这种上下文感知的补全机制能像一个智能向导一样，引导用户正确地构建出复杂的指令。

#### **7. 更强大的 `get` 指令 (More Powerful `get` Command)**

  * **7.1. 功能综述**
    我们将把基础的 `/svar get` 指令从一个只能查询单个值的简单工具，升格为一个强大的、支持模糊搜索、多条件过滤和灵活输出的数据检索终端。这将使得服务器管理员能够以前所未有的效率，对服务器内庞大的变量数据进行洞察和管理。

  * **7.2. 查询能力扩展**

      * **7.2.1. 模糊查询 (Fuzzy Search)**

          * **通配符支持:** 必须支持星号 `*` 作为通配符，匹配零个或多个任意字符。
          * **示例:**
              * `/svar get *kills*`: 获取并显示所有变量名中包含 "kills" 字符串的变量及其值（例如 `player_kills`, `zombie_mob_kills`）。
              * `/svar get player_stats_*`: 获取所有以 "player\_stats\_" 开头的变量。

      * **7.2.2. 多条件批量查询与过滤 (Batch Query with Multi-Conditional Filtering)**

          * `get` 指令必须能够接受所有在“设置”指令中使用的标志（如 `--scope`, `-p`, `--world`）作为过滤器。
          * **示例:**
              * `/svar get * --scope:PLAYER -p:Notch`: 获取玩家 Notch 的所有已赋值的变量。
              * `/svar get * --type:LIST`: 获取服务器上所有类型为 `LIST` 的变量。
              * `/svar get pvp_* --scope:WORLD --world:world_nether`: 获取下界（world\_nether）中，所有以 `pvp_` 开头的世界作用域变量。

  * **7.3. 格式化输出 (Formatted Output)**

      * **7.3.1. 自动分页系统 (Automatic Pagination System)**

          * 当查询结果数量超过一定阈值（例如，聊天框一页可显示的数量，通常是 10 条）时，系统必须自动将结果分割成多个页面。
          * **输出样式:**
            ```
            [DrcomoVex] 查询结果 (第 1/3 页):
            - global:server_motd = "欢迎来到我们的服务器!"
            - player.Notch:player_kills = 150
            - player.Notch:player_balance = 5230.5
            ...
            使用 /svar get * --page 2 查看下一页。
            ```
          * **翻页指令:** 必须提供 `--page <页码>` 标志，允许用户直接跳转到指定页面。

      * **7.3.2. 可定制的输出格式 (Customizable Output Format)**

          * 在 `messages.yml` 或类似的语言文件中，提供可供管理员自定义的查询结果输出格式模板。
          * **示例 (`messages.yml`):**
            ```yaml
            get-command:
              header: "§e[DrcomoVex] §f查询结果 (第 %page%/%total_pages% 页):"
              line-format: "§7- §b%scope%§f.§a%identifier%§f:§e%variable_name% §f= §d%value%"
              footer: "§7使用 §c/svar get <query> --page %next_page% §7查看下一页。"
            ```
          * `%scope%`, `%identifier%` (如玩家名), `%variable_name%`, `%value%` 等都是将被系统动态替换的占位符。

#### **8. 扩展事件系统 (Extended Event System)**

  * **8.1. 功能综述**
    事件系统是 DrcomoVex 作为“框架”而非“工具”的核心体现。我们将构建一个全面、精细且高度可控的事件系统，为其他插件的开发者打开一扇深入 DrcomoVex 核心逻辑的窗户。通过监听和（在适当情况下）修改或取消这些事件，开发者可以将他们插件的功能与 DrcomoVex 的变量生命周期进行无缝、深度的集成，创造出无限的可能性。

  * **8.2. 新增事件详解**
    所有 DrcomoVex 的事件都应继承自 Bukkit 的标准 `Event` 类，并酌情实现 `Cancellable` 接口。

      * **8.2.1. `PreVariableCreateEvent` (变量创建前事件)**

          * **触发时机:** 在一个全新的变量（即数据库中尚不存在其定义）即将被创建并写入配置文件之前触发。
          * **可取消性 (`Cancellable`):** 是。如果此事件被取消，变量将不会被创建。
          * **包含的上下文信息:**
              * `getVariableName()`: 获取即将被创建的变量名。
              * `getProperties()`: 获取一个包含所有待创建属性（如 `type`, `scope`, `initial_value`）的可修改的 Map。开发者可以通过修改这个 Map 来动态改变即将创建的变量的属性。
              * `getCreator()`: 获取触发创建的操作者（`CommandSender`），可能是玩家或控制台。
          * **应用场景:** 一个权限管理插件可以监听此事件，阻止没有特定权限的玩家创建变量。一个游戏玩法插件可以动态地为所有新创建的 `PLAYER` 变量添加一个默认的 `periodic` 周期性重置属性。

      * **8.2.2. `PostVariableCreateEvent` (变量创建后事件)**

          * **触发时机:** 在一个新变量成功被创建并保存后触发。
          * **可取消性 (`Cancellable`):** 否。这是一个通知性事件。
          * **包含的上下文信息:**
              * `getVariable()`: 返回一个代表已创建变量的只读对象，包含其所有最终属性。
              * `getCreator()`: 获取创建者。
          * **应用场景:** 一个日志插件可以监听此事件，记录下每一次变量创建的操作。

      * **8.2.3. `PreVariableDeleteEvent` (变量删除前事件)**

          * **触发时机:** 在一个变量的定义即将从配置文件中被永久删除之前触发。
          * **可取消性 (`Cancellable`):** 是。如果此事件被取消，变量将不会被删除。
          * **包含的上下文信息:**
              * `getVariable()`: 返回将被删除的变量的只读对象。
              * `getDeleter()`: 获取删除操作的发起者。
          * **应用场景:** 一个核心玩法插件可以监听此事件，取消对某些核心变量（如 `player_balance`）的删除操作，防止服务器经济系统被意外破坏。

      * **8.2.4. `PostVariableDeleteEvent` (变量删除后事件)**

          * **触发时机:** 在一个变量的定义被成功删除后触发。
          * **可取消性 (`Cancellable`):** 否。
          * **包含的上下文信息:**
              * `getVariableName()`: 返回被删除的变量名（因为对象已不存在）。
              * `getDeleter()`: 获取删除者。
          * **应用场景:** 插件可以监听此事件来清理与该变量相关的其他数据或缓存。

  * **8.3. 现有事件的强化**

      * **8.3.1. `VariableChangeEvent` (变量值改变事件)**

          * **上下文增强:** 必须确保此事件提供最完整的上下文。
              * `getVariable()`: 返回变量的定义对象。
              * `getHolder()`: 返回一个代表变量持有者的对象，可以是 `Player` 或 `GlobalHolder` 等。
              * `getScope()`: 返回此次变更发生的具体作用域。
              * `getOldValue()`: 获取旧值。
              * `getNewValue()` / `setNewValue(Object newValue)`: 获取新值，并允许监听者修改即将被设置的新值。
              * `getCause()`: 返回一个详细说明变更原因的枚举，例如 `PLAYER_COMMAND`, `API_CALL`, `PERIODIC_RESET`, `PLUGIN_SYSTEM`。
          * **可取消性 (`Cancellable`):** 是。取消此事件将阻止值的变更。

      * **8.3.2. `VariableResetEvent` (周期性重置事件)**

          * 此事件已在 `2.4.2` 节中详细定义，此处重申其关键性：必须确保其作为 `VariableChangeEvent` 的一个特殊子类或拥有独立的、完整的上下文信息，并且是可取消的。
