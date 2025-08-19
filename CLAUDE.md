# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

DrcomoVEX 是一个基于直觉设计的 Minecraft 服务器变量管理系统，版本 1.0.0（代号：直觉），作者：BaiMo。

该项目为 Paper/Spigot 1.18.2 插件，支持智能类型推断、动态表达式计算、周期性重置和全能指令操作。

## 构建和开发命令

```bash
# 构建项目
mvn clean package

# 编译和运行测试
mvn compile

# 清理构建文件
mvn clean

# 查看依赖树
mvn dependency:tree
```

注意：项目目前没有单元测试（无 src/test 目录）。

## 核心架构

### 分层设计
- **API层**: `cn.drcomo.api.ServerVariablesAPI` - 对外API接口
- **管理器层**: `cn.drcomo.managers.*` - 业务逻辑管理
- **存储层**: `cn.drcomo.storage.*` - 内存存储和缓存管理  
- **模型层**: `cn.drcomo.model.*` - 数据模型和结构定义
- **任务层**: `cn.drcomo.tasks.*` - 定时任务和数据保存

### 核心组件

#### 变量管理器 (RefactoredVariablesManager)
- 高性能变量管理核心，支持异步操作
- 位置：`src/main/java/cn/drcomo/managers/RefactoredVariablesManager.java`
- 集成多级缓存、批量持久化、表达式计算

#### 存储系统
- **VariableMemoryStorage**: 内存优先存储，追踪脏数据
- **MultiLevelCacheManager**: 多级缓存管理（原始数据、表达式结果、最终值）
- **BatchPersistenceManager**: 批量持久化管理，减少数据库访问

#### 数据库连接 (HikariConnection)
- 支持 SQLite 和 MySQL
- 使用 HikariCP 连接池
- 位置：`src/main/java/cn/drcomo/database/HikariConnection.java`

### 变量类型和特性

支持的数据类型：
- `INT`: 整数
- `DOUBLE`: 浮点数  
- `STRING`: 字符串
- `LIST`: 列表

支持的作用域：
- `player`: 玩家变量
- `global`: 全局/服务器变量

高级特性：
- 动态表达式计算（如：`"${player_level} * 10 + ${player_money} / 100"`）
- 周期性重置（支持 Cron 表达式）
- PlaceholderAPI 占位符解析
- 批量操作和通配符匹配

## 关键配置文件

### config.yml
主配置文件，包含：
- 数据库连接配置（SQLite/MySQL）
- 数据保存策略
- 周期性重置配置
- 调试设置

### variables/default.yml  
变量定义文件，定义所有服务器变量和玩家变量的结构、类型、约束条件。

### messages.yml
消息配置文件，包含所有用户界面文本，支持颜色码和 PlaceholderAPI 占位符。

## 依赖关系

### 核心依赖
- **DrcomoCoreLib**: 核心库依赖，提供工具类和基础设施
- **Paper/Spigot API 1.18.2**: Minecraft 服务器 API

### 软依赖  
- **PlaceholderAPI**: 占位符集成
- **Vault**: 经济系统集成
- **PlayerPoints**: 点数系统集成

### 库依赖
- **HikariCP**: 数据库连接池
- **Caffeine**: 高性能缓存
- **cron-utils**: Cron 表达式解析

## 开发注意事项

### 指令系统
主指令处理器位于 `MainCommand.java`，支持：
- 玩家变量操作：`/vex player <operation> ...`
- 全局变量操作：`/vex global <operation> ...`
- 批量操作：使用 `*` 通配符和条件过滤
- 预演模式：使用 `-n` 或 `--dry-run` 参数

### 异步操作模式
- 所有变量操作都是异步的，返回 `CompletableFuture<VariableResult>`
- 使用 `AsyncTaskManager` 管理异步任务
- 主线程安全：重载等关键操作在主线程执行

### 性能优化策略
- 内存优先存储，减少数据库访问
- 多级缓存系统，缓存表达式结果
- 批量持久化，定期刷新脏数据
- 连接池管理，支持高并发

### 错误处理
- 统一使用 `VariableResult` 封装操作结果
- 异常处理和超时保护（默认5秒）
- 详细的日志记录和调试信息

### 扩展性设计
项目遵循 SOLID 原则，核心组件通过接口解耦。新功能可通过实现相应接口来扩展，如经济系统集成、新的存储后端等。

## 待补充组件

参考 `NEED.md` 文件，当前需要补充的组件包括多级缓存管理、批量持久化与脏数据管理、内存优先变量存储等的接口规范化。