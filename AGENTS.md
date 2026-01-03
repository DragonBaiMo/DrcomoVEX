# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

DrcomoVEX 是一个 Minecraft Paper/Spigot 插件，提供高度工程化的变量管理系统。支持动态表达式计算、周期性重置、条件门控、渐进恢复等高级功能，采用多级缓存与异步持久化实现高性能。

## 构建命令

```powershell
# 构建（生成普通 JAR 和 Shade JAR）
mvn clean package

# 跳过测试构建
mvn clean package -DskipTests

# 运行单个测试
mvn test -Dtest=TestClassName

# 运行特定测试方法
mvn test -Dtest=TestClassName#methodName
```

## 核心架构

### 分层结构

```
cn.drcomo
├── api/                    # 对外 API（ServerVariablesAPI）
├── bulk/                   # 批量操作（排行榜、批量奖励）
├── config/                 # 配置管理
│   ├── ConfigsManager          # 配置总管理器
│   ├── MainConfigManager       # 主配置（config.yml）
│   └── VariablesConfigManager  # 变量定义配置
├── database/               # 数据库层（HikariCP 连接池）
├── managers/               # 核心业务管理
│   ├── RefactoredVariablesManager  # 变量计算引擎（核心）
│   ├── PlayerVariablesManager      # 玩家作用域变量
│   ├── ServerVariablesManager      # 全局作用域变量
│   └── components/
│       ├── ActionExecutor          # 指令执行器
│       └── VariableDefinitionLoader # 变量定义加载器
├── model/structure/        # 数据模型
│   ├── Variable            # 变量定义实体
│   ├── ValueType           # 类型枚举（INT/DOUBLE/STRING/LIST）
│   ├── ScopeType           # 作用域枚举（PLAYER/GLOBAL）
│   └── RegenRule           # 恢复规则解析
├── storage/                # 存储与缓存
│   ├── MultiLevelCacheManager  # 多级缓存
│   └── BatchPersistenceManager # 异步批量持久化
└── tasks/                  # 定时任务（数据保存、周期重置）
```

### 核心业务流程

#### 变量系统

1. **定义加载**：从 `variables/*.yml` 加载变量定义，支持子目录
2. **表达式计算**：使用 exp4j 进行数学运算，支持：
   - 跨变量引用：`${var_name}`
   - PAPI 占位符：`%placeholder%`
   - 数学运算：`+`, `-`, `*`, `/`, `^`, `()`
3. **恢复机制**：`regen` 规则支持 `1/5m@08:00-22:00` 格式
4. **条件门控**：`conditions` 配置访问前置条件

#### 数据流

- **登录**：`PlayerListener` → 预加载变量 → 从数据库/缓存同步
- **退出**：即时持久化 → 清理内存缓存
- **变更**：标记 DirtyFlag → `BatchPersistenceManager` 异步写入

### 关键依赖

| 依赖 | 用途 |
|------|------|
| `DrcomoCoreLib` | 底层工具库（异步、配置、消息、日志、GUI） |
| `exp4j` | 数学表达式计算 |
| `HikariCP` | 数据库连接池 |
| `Caffeine` | 高性能本地缓存 |
| `cron-utils` | Cron 表达式解析 |

## 配置文件结构

```
plugins/DrcomoVEX/
├── config.yml              # 主配置（数据库、保存策略、周期任务）
├── messages.yml            # 消息本地化
└── variables/              # 变量定义目录
    ├── default.yml
    └── *.yml               # 支持任意子目录
```

### 变量定义关键字段

```yaml
variables:
  variable_key:
    scope: "player"         # player | global
    type: "INT"             # INT | DOUBLE | STRING | LIST
    initial: "100"          # 静态值或动态表达式
    min: 0                  # 数值下限
    max: 100                # 数值上限
    cycle: "daily"          # 重置周期（minute/daily/weekly/monthly/yearly/Cron）
    regen: "1/5m@08:00-22:00"  # 渐进恢复规则
    conditions:             # 访问条件（AND 逻辑）
      - "${event_switch}"
    limitations:
      read-only: true       # 只读
      persistable: false    # 不持久化
      strict-initial-mode: true  # 严格初始值模式
    cycle-actions:          # 重置后执行的动作
      - "[console] broadcast 已重置"
```

## 开发约定

### 线程安全

- 世界/实体/API 访问必须在主线程
- 耗时 IO/网络操作使用异步 + Scheduler 回调主线程
- 变量操作通过 Manager 层保证线程安全

### DrcomoCoreLib 使用

所有工具类需通过 `new` 实例化并注入依赖：

```java
// 日志
DebugUtil logger = new DebugUtil(this, DebugUtil.LogLevel.INFO);

// 配置
YamlUtil yamlUtil = new YamlUtil(this, logger);

// 异步任务
AsyncTaskManager asyncManager = AsyncTaskManager.newBuilder(this, logger).build();
```

详细 API 文档见 `DrcomoCoreLib/JavaDocs/` 目录。

### 权限节点命名

格式：`drcomovex.<category>.<action>[.others]`

示例：
- `drcomovex.command.player.get`
- `drcomovex.command.player.get.others`
- `drcomovex.admin.reload`
