### `PerformanceUtil.java`

**1. 概述 (Overview)**

* **完整路径:** `cn.drcomo.corelib.performance.PerformanceUtil`
* **核心职责:** 提供方便的接口获取服务器运行时的性能指标，包括 TPS、CPU 使用率、内存占用以及 GC 统计信息。
* **兼容性:** 支持 Paper 和 Spigot 服务器，自动检测并适配 TPS 获取功能。

**2. 初始化 (Initialization)**

```java
Plugin plugin = ...;
DebugUtil logger = new DebugUtil(plugin, DebugUtil.LogLevel.INFO);
PerformanceUtil perf = new PerformanceUtil(plugin, logger);

// 检查 TPS 功能是否可用
if (perf.isTpsSupported()) {
    logger.info("当前服务器支持 TPS 监控");
} else {
    logger.info("当前服务器不支持 TPS 监控（可能是 Spigot）");
}
```

**3. 兼容性检测 (Compatibility Detection)**

* `isTpsSupported()` - 检查当前服务器是否支持 TPS 获取功能
  * 返回 `true`：检测到 Paper 服务器，支持 `getTPS()` 方法
  * 返回 `false`：检测到 Spigot 服务器，不支持 TPS 功能

**4. 获取性能数据 (Collect metrics)**

* 调用 `snapshot()` 将即刻从服务器与 JVM 收集数据，返回 `PerformanceSnapshot` 记录：
  * `tps` - 服务器当前 TPS（Paper 服务器返回实际值，Spigot 返回 -1.0）
  * `cpuUsage` - 当前进程 CPU 使用率 (0-1)
  * `usedMemory` - 已使用内存字节数
  * `maxMemory` - 最大可用内存字节数
  * `gcCount` - 垃圾回收总次数
  * `gcTime` - 垃圾回收总耗时（毫秒）

```java
PerformanceSnapshot snap = perf.snapshot();
if (perf.isTpsSupported()) {
    logger.info("TPS: " + snap.tps());
} else {
    logger.info("TPS 功能不可用，当前服务器类型: Spigot");
}
```

**5. 使用场景示例 (Example usage)**

在定时任务中周期性调用 `snapshot()`，将结果写入日志或发送给管理员，实现轻量级性能监控。支持跨服务器类型部署。
