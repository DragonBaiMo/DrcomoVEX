# DrcomoVEX

## 插件介绍

DrcomoVEX 是一个面向 Paper/Spigot 服务器的变量管理插件，支持玩家变量、全局变量、表达式计算、周期重置、渐进恢复、条件门控与 PlaceholderAPI 集成。

## 优势

- 支持 `player` 与 `global` 两种作用域
- 支持数学表达式、变量引用与 PlaceholderAPI 占位符
- 支持 `daily`、`weekly`、`monthly`、Cron 等周期重置
- 支持 `regen` 渐进恢复规则
- 支持 Redis 跨服同步
- 支持无 `server-id` 的全局变量数据库拉取同步
- 支持 MySQL 事件表跨服同步兼容模式

## 使用方法

1. 将插件放入 `plugins` 目录，并确保服务端已安装 `DrcomoCoreLib`
2. 按需安装 `PlaceholderAPI`、`Vault`、`PlayerPoints`
3. 在 `config.yml` 中配置数据库
4. 在 `variables/*.yml` 中定义变量
5. 重启服务器或执行 `/vex reload`

### MySQL 跨服同步配置

如果主人使用的是多子服共用一个 MySQL，而没有启用 Redis，请在 `config.yml` 中开启：

```yml
settings:
  cross-server-sync:
    enabled: true
    server-id: "survival-1"
```

注意：

- `database.type` 必须为 `mysql`
- 每个子服都必须配置不同的 `server-id`
- 如果已经启用 `settings.redis-sync.enabled: true`，则优先使用 Redis，同步链路不会走 MySQL 事件表

### 全局变量无 server-id 同步

如果主人只关心 `scope=global` 的变量跨服一致性，现在插件会默认开启数据库拉取同步：

```yml
settings:
  global-db-sync:
    enabled: true
    poll-interval-millis: 1000
```

说明：

- 这套同步不需要配置 `settings.cross-server-sync.server-id`
- 只处理 `global` 变量
- 需要多子服共用同一个 MySQL
- 玩家变量跨服逻辑保持原样，不受这套同步影响

## 命令

- `/vex help` 查看帮助
- `/vex reload` 重载插件
- `/vex player get <玩家> <变量>`
- `/vex player set <玩家> <变量> <值>`
- `/vex player add <玩家> <变量> <值>`
- `/vex player remove <玩家> <变量> <值>`
- `/vex player reset <玩家> <变量>`
- `/vex global get <变量>`
- `/vex global set <变量> <值>`
- `/vex global add <变量> <值>`
- `/vex global remove <变量> <值>`
- `/vex global reset <变量>`

## 权限

- `drcomovex.command.*` 指令权限总节点
- `drcomovex.command.help` 查看帮助
- `drcomovex.command.get`
- `drcomovex.command.set`
- `drcomovex.command.add`
- `drcomovex.command.remove`
- `drcomovex.command.reset`
- `drcomovex.admin.*` 管理权限总节点
- `drcomovex.admin.reload` 重载插件

## 更新说明

### 1.2.0

- 新增仅针对 `global` 变量的数据库拉取同步
- 新增全局变量写穿数据库逻辑，减少跨服可见延迟
- 这套全局同步不再依赖 `settings.cross-server-sync.server-id`
- 保持玩家变量跨服逻辑不变
- 将插件版本提升到 `1.2.0`

### 1.1.0

- 修复了仅配置数据库时，全局变量不会自动跨服同步的问题
- 新增 MySQL 事件表跨服同步所需的默认配置项
- 新增 `player_variable_change_events` 与 `player_variable_change_consumers` 表结构
- 在变量写入、删除、重置时补充事件表写入逻辑
- 将插件版本提升到 `1.1.0`
