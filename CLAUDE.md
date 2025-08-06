# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目简介

DrcomoVEX 是一个基于直觉设计的 Minecraft 服务器变量管理系统，使用 Java 21 开发。它提供智能类型推断、动态表达式计算、周期性重置和全能指令操作功能。

## 构建和开发命令

### Maven 构建
- **构建项目**: `mvn clean compile`
- **打包插件**: `mvn clean package`
- **安装依赖**: `mvn dependency:resolve`
- **生成依赖报告**: `mvn dependency:tree`

### 测试和验证
- **编译检查**: `mvn compile`
- **验证项目**: `mvn validate`

## 架构概述

### 核心模块架构
项目采用三层架构设计：

1. **配置解析与变量定义核心** (`config` 包)
   - `ConfigsManager`: 统一配置管理
   - `DataConfigManager`, `MainConfigManager`, `PlayerConfigsManager`: 具体配置实现
   - 基于 `variables.yml` 进行智能类型推断和可选类型校验

2. **核心逻辑与数据服务** (`managers` 包)
   - `VariablesManager`: 变量操作核心逻辑
   - `ServerVariablesManager`: 服务器全局变量管理
   - `PlayerVariablesManager`: 玩家变量管理
   - 全异步设计，返回 `CompletableFuture` 对象

3. **指令与外部接口** (`MainCommand` 和 `api` 包)
   - `/vex` 主指令系统，支持别名 `/dvex`, `/drcomo`
   - `ServerVariablesAPI`: 对外 API 接口
   - PlaceholderAPI 集成支持

### 依赖关系
- **DrcomoCoreLib**: 核心依赖库，提供工具类和通用功能
- **Spigot API 1.21.8**: Minecraft 服务器 API
- **HikariCP**: 数据库连接池
- **PlaceholderAPI**: 占位符支持（软依赖）

### 数据存储
- 使用 SQLite 作为主要数据存储
- HikariCP 连接池管理数据库连接
- 支持异步数据操作，避免阻塞主线程

## 开发规范

### 代码约定
- 使用中文注释和日志消息
- 所有业务逻辑必须异步执行
- 严格的类型校验和约束检查
- 基于 DrcomoCoreLib 工具类进行开发

### 配置文件结构
- `config.yml`: 主配置文件
- `variables.yml`: 变量定义文件
- `messages.yml`: 消息模板文件
- `data.yml`: 数据配置文件

### 智能变量系统特性
- **智能类型推断**: 根据 `initial` 值自动推断变量类型
- **动态表达式**: 支持 PlaceholderAPI 占位符和内部变量引用
- **周期性重置**: 支持 DAILY、WEEKLY、MONTHLY、YEARLY 和 Cron 表达式
- **全能指令**: `/vex add` 支持数值运算、列表追加、文本拼接

### 异步编程模式
- 所有 Manager 类的核心方法返回 `CompletableFuture`
- 使用 `AsyncTaskManager` 进行任务调度
- 数据库操作完全异步化
- 缓存策略：值缓存 + 解析缓存双重优化

## 重要文件路径

### 核心类文件
- 主类: `src/main/java/cn/drcomo/DrcomoVEX.java`
- 指令处理: `src/main/java/cn/drcomo/MainCommand.java`
- 变量管理: `src/main/java/cn/drcomo/managers/VariablesManager.java`

### 配置文件
- 插件配置: `src/main/resources/plugin.yml`
- 主配置模板: `src/main/resources/config.yml`
- 变量配置模板: `src/main/resources/variables.yml`
- 消息模板: `src/main/resources/messages.yml`

### 数据库
- SQL 建表语句: `src/main/resources/schema.sql`
- 连接管理: `src/main/java/cn/drcomo/database/HikariConnection.java`

## 开发注意事项

### DrcomoCoreLib 使用
- 必须通过 `new` 实例化工具类，不能直接静态调用
- 依赖注入模式：将核心工具实例注入到业务类中
- 详细 API 文档位于 `DrcomoCoreLib/JavaDocs/` 目录

### 线程安全
- 所有核心业务逻辑必须异步执行
- 严禁在主线程调用 `.join()` 或 `.get()`
- 使用 Caffeine 缓存实现高性能内存缓存

### 配置热重载
- 支持 `/vex reload` 指令热重载配置
- 配置变更后自动清空相关缓存
- 支持分布式配置文件（`variables/` 目录下的 `.yml` 文件）

### 版本兼容性
- Java 21 最低要求
- Spigot/Paper 1.16+ API 兼容
- Maven 3.8.1+ 构建工具

## 开发流程建议

1. **理解业务逻辑**: 阅读开发总文档了解系统设计理念
2. **配置开发环境**: 确保 Java 21 和 Maven 正确安装
3. **依赖管理**: 确保 DrcomoCoreLib 在本地可用
4. **模块化开发**: 按照三层架构进行功能开发
5. **异步优先**: 所有耗时操作必须异步执行
6. **严格测试**: 重点测试动态表达式解析和周期性重置功能