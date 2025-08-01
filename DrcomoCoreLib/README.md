---
title: DrcomoCoreLib 子插件开发者指南
---

# **DrcomoCoreLib 子插件开发者指南**

## **安装与依赖**

1.  将 `DrcomoCoreLib.jar` 放入服务器的 `plugins` 文件夹。
2.  在你的插件 `plugin.yml` 中添加依赖：
    ```yaml
    depend: [DrcomoCoreLib]
    ```

## **核心使用范例** 

`DrcomoCoreLib` 的所有工具类都不能直接使用，你必须在你的插件中通过 `new` 关键字创建它们的实例，并将依赖注入。

### **基础实例化模式**

根据 `DrcomoCoreLib` 主类的设计理念，该类由 Bukkit/Spigot 服务器自动管理，开发者**不应也无需**手动创建其实例。开发者需要关注的是如何在自己的插件中，正确地实例化和使用本库提供的其他工具类。

```java
// 在你的插件主类的 onEnable() 方法中
import cn.drcomo.corelib.*;

public class MyAwesomePlugin extends JavaPlugin {

    private DebugUtil myLogger;
    private YamlUtil myYamlUtil;
    private YamlUtil.ConfigWatchHandle configHandle;
    private SoundManager mySoundManager;
    private SkullUtil skullUtil;
    private GUISessionManager guiSessionManager;
    private GuiActionDispatcher guiActionDispatcher;
    private MyPaginatedGui myPaginatedGui;

    @Override
    public void onEnable() {
        // 1. 为你的插件创建独立的日志工具
        myLogger = new DebugUtil(this, DebugUtil.LogLevel.INFO);
        // 可选：自定义前缀和输出格式
        myLogger.setPrefix("&f[&bMyPlugin&r]&f ");
        myLogger.setFormatTemplate("%prefix%[%level%] %msg%");
        // 额外将日志写入文件
        myLogger.addFileHandler(new File(getDataFolder(), "debug.log"));

        // 2. 为你的插件创建独立的 Yaml 配置工具，并注入日志实例
        myYamlUtil = new YamlUtil(this, myLogger);
        myYamlUtil.loadConfig("config");
        ExecutorService watchPool = Executors.newSingleThreadExecutor();
        configHandle = myYamlUtil.watchConfig(
                "config",
                updated -> myLogger.info("配置文件已重新加载！"),
                watchPool,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE
        );

        // 3. 实例化 SoundManager，注入所有需要的依赖
        mySoundManager = new SoundManager(
            this,
            myYamlUtil,
            myLogger,
            "mySounds.yml", // 自定义配置文件名
            1.0f,           // 全局音量
            true            // 找不到音效时警告
        );
        mySoundManager.loadSounds(); // 手动加载音效
        // 可随时调整全局音量倍率
        mySoundManager.setVolumeMultiplier(1.2f);
        // 可在需要时自定义音量与音调
        // mySoundManager.play("level_up", player.getLocation(), 0.8f, 1.2f);

        // 4. 使用类型安全的方式读取配置
        boolean autoSave = myYamlUtil.getValue("settings.auto-save", Boolean.class, true);
        if (autoSave) {
            myLogger.info("自动保存已启用");
        }

        // 5. 备份数据并清理旧归档
        ArchiveUtil archiveUtil = new ArchiveUtil(myLogger);
        String zip = archiveUtil.archiveByDate("plugins/MyPlugin/data", "backups");
        archiveUtil.cleanupOldArchives("backups", 30);
        // 若需更细粒度控制，可指定压缩级别
        archiveUtil.compress("logs/latest.log", "logs.zip", 9);

        // 6. 创建自定义头像工具
        skullUtil = new SkullUtil(myLogger);
        // 可通过 URL 或 Base64 创建自定义头颅
        // String textureUrl = "http://textures.minecraft.net/texture/<texture-id>";
        // ItemStack customSkull = skullUtil.fromUrl(textureUrl);

        // 7. 创建 GUI 管理组件
        guiSessionManager = new GUISessionManager(this, myLogger);
        guiActionDispatcher = new GuiActionDispatcher(this, myLogger);
        
        // 8. 创建自定义分页 GUI（需要继承 PaginatedGui）
        myPaginatedGui = new MyPaginatedGui(guiSessionManager, guiActionDispatcher);
        // 使用示例：myPaginatedGui.open(player, "my-gui-session");

        myLogger.info("我的插件已成功加载，并配置好了核心库工具！");
    }

    @Override
    public void onDisable() {
        // 停止所有文件监听器，防止线程泄露
        if (myYamlUtil != null) {
            myYamlUtil.stopAllWatches();
        }
        
        // 如果使用了 AsyncTaskManager，记得关闭以释放线程资源
        // if (asyncTaskManager != null) {
        //     asyncTaskManager.close();
        // }
        
        myLogger.info("插件已安全卸载");
    }
}
}

// MyPaginatedGui 实现示例
class MyPaginatedGui extends PaginatedGui {
    public MyPaginatedGui(GUISessionManager sessions, GuiActionDispatcher dispatcher) {
        super(sessions, dispatcher, 45, 45, 53); // 每页45格，45和53为导航按钮槽位
    }

    @Override
    protected int getTotalItemCount(Player player) {
        // 返回要展示的物品总数
        return getItemsForPlayer(player).size();
    }

    @Override
    protected Inventory createInventory(Player player) {
        return Bukkit.createInventory(player, 54, "我的物品列表");
    }

    @Override
    protected void renderPage(Player player, Inventory inv, int page, int totalPages) {
        List<ItemStack> items = getItemsForPlayer(player);
        int from = page * getPageSize();
        for (int i = 0; i < getPageSize(); i++) {
            int index = from + i;
            inv.setItem(i, index < items.size() ? items.get(index) : null);
        }
        
        // 添加导航按钮
        if (page > 0) {
            inv.setItem(getPrevSlot(), createPrevButton());
        }
        if (page < totalPages - 1) {
            inv.setItem(getNextSlot(), createNextButton());
        }
    }
    
    private List<ItemStack> getItemsForPlayer(Player player) {
        // 实际实现中应该返回玩家的物品列表
        return Arrays.asList(new ItemStack(Material.DIAMOND), new ItemStack(Material.GOLD_INGOT));
    }
    
    private ItemStack createPrevButton() {
        return new ItemStack(Material.ARROW); // 上一页按钮
    }
    
    private ItemStack createNextButton() {
        return new ItemStack(Material.ARROW); // 下一页按钮
    }
}
```

> **注意**：自定义模板必须包含 `%msg%` 占位符，否则日志内容将丢失。将日志写入文件时请确认插件目录可写。

### **高级配置示例**

#### **更换线程池示例**

通过 `AsyncTaskManager.newBuilder()` 可接入自定义线程池或调度器。

```java
ExecutorService exec = Executors.newFixedThreadPool(4);
ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
AsyncTaskManager manager = AsyncTaskManager
        .newBuilder(this, myLogger)
        .executor(exec)
        .scheduler(sched) // 可替换为封装 BukkitScheduler 的实现
        .build();

// 快速调整内部线程池
AsyncTaskManager tuned = AsyncTaskManager
        .newBuilder(this, myLogger)
        .poolSize(4)
        .threadFactory(r -> new Thread(r, "Worker-" + r.hashCode()))
        .build();
// 在插件 onDisable() 方法中调用以释放线程资源
// manager.close();
```

#### **自定义 HttpClient 示例**

可通过 `HttpUtil.newBuilder()` 注入自定义的 `HttpClient` 或执行器，实现完全控制的网络请求。

```java
ExecutorService pool = Executors.newFixedThreadPool(2);
HttpClient client = HttpClient.newBuilder()
        .executor(pool)
        .build();

HttpUtil http = HttpUtil.newBuilder()
        .logger(myLogger)
        .client(client)      // 直接使用自定义 HttpClient
        .baseUri(URI.create("https://api.example.com/"))
        .defaultHeader("User-Agent", "MyPlugin")
        .build();
```

若需使用 Bukkit 原生调度器，可将 `BukkitScheduler` 封装为 `ScheduledExecutorService` 后传入 `scheduler()`。

#### **获取 DrcomoCoreLib 实例（可选）**

虽然通常不需要直接与 `DrcomoCoreLib` 主类交互，但如果需要获取其实例：

```java
DrcomoCoreLib coreLib = (DrcomoCoreLib) Bukkit.getPluginManager().getPlugin("DrcomoCoreLib");

if (coreLib != null) {
    // 你可以获取到实例，但通常不会直接与它交互。
    // 它的价值在于作为一个加载入口和提供用法示例。
    getLogger().info("成功获取到 DrcomoCoreLib 实例。");
}
```

---

## **API文档查询规则**

当接收到与 DrcomoCoreLib 开发相关的用户请求时，严格遵循以下规则，将所需功能映射到对应的API文档，并基于该文档提供解决方案。

---

### 日志记录
- **功能描述**：实现或管理控制台日志输出，如 `info`, `warn`, `error`，或动态设置日志级别。  
- **包类路径**：`cn.drcomo.corelib.util.DebugUtil`
- **查询文档**：[查看](./JavaDocs/util/DebugUtil-JavaDoc.md)


### 配置文件读写 (YAML)
- **功能描述**：对 `.yml` 文件进行加载、重载、保存、读写键值、复制默认配置、获取配置节等操作。
- **包类路径**：`cn.drcomo.corelib.config.YamlUtil`
- **查询文档**：[查看](./JavaDocs/config/YamlUtil-JavaDoc.md)

### 配置校验
- **功能描述**：在读取或重载配置后，验证必填项是否存在且类型正确，支持字符串、数值、枚举类型验证以及自定义校验规则。
- **包类路径**：
  - `cn.drcomo.corelib.config.ConfigValidator`（配置校验器主入口）
  - `cn.drcomo.corelib.config.ValidatorBuilder`（链式校验规则构建器）
  - `cn.drcomo.corelib.config.ValidationResult`（校验结果处理）
- **核心查询**：[查看](./JavaDocs/config/ConfigValidator-JavaDoc.md)（配置校验器主入口）
- **关联查询**：
  - [查看](./JavaDocs/config/ValidatorBuilder-JavaDoc.md)（链式校验规则构建器）
  - [查看](./JavaDocs/config/ValidationResult-JavaDoc.md)（校验结果处理）


### 文本颜色处理
- **功能描述**：翻译（`&` → `§`）、转换（`&#RRGGBB`）或剥离字符串中的 Minecraft 颜色代码。  
- **包类路径**：`cn.drcomo.corelib.color.ColorUtil`
- **查询文档**：[查看](./JavaDocs/color/ColorUtil-JavaDoc.md)


### 发送消息与文本本地化
- **功能描述**：发送游戏内消息（聊天、ActionBar、Title），解析多层级占位符（自定义、PAPI、内部），管理多语言文件。  
- **包类路径**：`cn.drcomo.corelib.message.MessageService`
- **查询文档**：[查看](./JavaDocs/message/MessageService-JavaDoc.md)


### 物品NBT数据操作
- **功能描述**：在 `ItemStack` 上附加、读取、修改、删除或批量保留自定义数据标签。  
- **包类路径**：
  - `cn.drcomo.corelib.nbt.NbtKeyHandler`（NBT键名安全策略）
  - `cn.drcomo.corelib.nbt.NBTUtil`（具体NBT操作API）
- **前置查询**：[查看](./JavaDocs/nbt/NbtKeyHandler-JavaDoc.md)（NBT键名安全策略）  
- **核心查询**：[查看](./JavaDocs/nbt/NBTUtil-JavaDoc.md)（具体NBT操作API）


### PlaceholderAPI (PAPI) 集成
- **功能描述**：注册自定义PAPI占位符（如 `%myplugin_level%`）或解析含PAPI占位符的字符串。  
- **包类路径**：`cn.drcomo.corelib.hook.placeholder.PlaceholderAPIUtil`
- **查询文档**：[查看](./JavaDocs/hook/placeholder/PlaceholderAPIUtil-JavaDoc.md)


### 动态条件判断
- **功能描述**：解析并计算包含PAPI占位符、逻辑运算符（`&&`, `||`）和比较运算符（`>=`, `==`, `STR_CONTAINS`）的条件表达式。
- **包类路径**：
  - `cn.drcomo.corelib.hook.placeholder.parse.PlaceholderConditionEvaluator`
  - `cn.drcomo.corelib.hook.placeholder.parse.ParseException`（表达式解析异常处理）
- **查询文档**：[查看](./JavaDocs/hook/placeholder/parse/PlaceholderConditionEvaluator-JavaDoc.md)
- **关联查询**：[查看](./JavaDocs/hook/placeholder/parse/ParseException-JavaDoc.md)（表达式解析异常处理）


### 经济系统交互 (Vault / PlayerPoints)
- **功能描述**：查询玩家余额、扣款、存款、格式化货币等操作。  
- **包类路径**：
  - `cn.drcomo.corelib.hook.economy.EconomyProvider`（通用经济接口）
  - `cn.drcomo.corelib.hook.economy.provider.VaultEconomyProvider`（Vault对接实现）
  - `cn.drcomo.corelib.hook.economy.provider.PlayerPointsEconomyProvider`（PlayerPoints对接实现）
  - `cn.drcomo.corelib.hook.economy.EconomyResponse`（经济操作返回对象）
- **查询文档 1 (接口)**：[查看](./JavaDocs/hook/economy/EconomyProvider-JavaDoc.md)（通用经济接口）  
- **查询文档 2 (实现)**：  
  - Vault 对接：[查看](./JavaDocs/hook/economy/provider/VaultEconomyProvider-JavaDoc.md)  
  - PlayerPoints 对接：[查看](./JavaDocs/hook/economy/provider/PlayerPointsEconomyProvider-JavaDoc.md)  
- **查询文档 3 (结果)**：[查看](./JavaDocs/hook/economy/EconomyResponse-JavaDoc.md)（经济操作返回对象）


### 数学公式计算
- **功能描述**：计算字符串形式的数学表达式（支持变量）。  
- **包类路径**：`cn.drcomo.corelib.math.FormulaCalculator`
- **查询文档**：[查看](./JavaDocs/math/FormulaCalculator-JavaDoc.md)


### 异步任务管理
- **功能描述**：管理异步任务执行，支持任务提交、延迟执行、定时调度、批量处理等，内置异常捕获和日志记录。通过 Builder 可调整线程池大小及线程工厂。
- **包类路径**：`cn.drcomo.corelib.async.AsyncTaskManager`
- **查询文档**：[查看](./JavaDocs/async/AsyncTaskManager-JavaDoc.md)


### 性能监控
- **功能描述**：实时获取服务器TPS、CPU使用率、内存使用情况和GC统计信息，支持Paper和Spigot服务器。
- **包类路径**：
  - `cn.drcomo.corelib.performance.PerformanceUtil`（性能采集工具）
  - `cn.drcomo.corelib.performance.PerformanceSnapshot`（性能快照数据）
- **查询文档**：[查看](./JavaDocs/performance/PerformanceUtil-JavaDoc.md)（性能采集工具）
- **关联查询**：[查看](./JavaDocs/performance/PerformanceSnapshot-JavaDoc.md)（性能快照数据）


### JSON序列化工具
- **功能描述**：基于Gson的JSON序列化与反序列化工具，支持对象转JSON、JSON转对象、文件读写和复杂泛型类型解析。
- **包类路径**：`cn.drcomo.corelib.json.JsonUtil`
- **查询文档**：[查看](./JavaDocs/json/JsonUtil-JavaDoc.md)


### HTTP网络请求
- **功能描述**：基于Java 11 HttpClient的异步HTTP工具，支持GET/POST请求、文件上传、代理配置、超时设置和重试机制。
- **包类路径**：`cn.drcomo.corelib.net.HttpUtil`
- **查询文档**：[查看](./JavaDocs/net/HttpUtil-JavaDoc.md)


### 文件归档与压缩
- **功能描述**：压缩或解压文件/目录，并可按日期归档和清理旧文件。
- **包类路径**：`cn.drcomo.corelib.archive.ArchiveUtil`
- **查询文档**：[查看](./JavaDocs/archive/ArchiveUtil-JavaDoc.md)


### 音效管理
- **功能描述**：从配置文件加载音效并通过键名（key）播放。  
- **包类路径**：`cn.drcomo.corelib.sound.SoundManager`
- **查询文档**：[查看](./JavaDocs/sound/SoundManager-JavaDoc.md)


### 自定义头像生成
- **功能描述**：根据纹理 URL 或 Base64 字符串生成带自定义纹理的玩家头颅物品，支持异常处理和日志记录。
- **包类路径**：`cn.drcomo.corelib.util.SkullUtil`
- **查询文档**：[查看](./JavaDocs/util/SkullUtil-JavaDoc.md)


### GUI 创建与交互
- **功能描述**：构建交互式菜单、定义特定槽位的点击行为、管理GUI的打开与关闭、获取点击事件的详细信息或执行安全的GUI辅助操作。
- **包类路径**：
  - `cn.drcomo.corelib.gui.interfaces.ClickAction`（定义"做什么"的回调）
  - `cn.drcomo.corelib.gui.interfaces.SlotPredicate`（定义"在哪里生效"的条件）
  - `cn.drcomo.corelib.gui.GuiActionDispatcher`（事件分发器）
  - `cn.drcomo.corelib.gui.GUISessionManager`（会话管理）
  - `cn.drcomo.corelib.gui.ClickContext`（点击上下文数据载体）
  - `cn.drcomo.corelib.gui.GuiManager`（GUI辅助工具）
  - `cn.drcomo.corelib.gui.PaginatedGui`（分页界面基类）
  - `cn.drcomo.corelib.gui.session.PlayerSessionManager`（通用玩家会话）
  - `cn.drcomo.corelib.gui.ClickTypeUtil`（点击类型工具）
- **前置概念查询**：
    * [查看](./JavaDocs/gui/interfaces/ClickAction-JavaDoc.md) (理解定义 **"做什么"** 的回调)
    * [查看](./JavaDocs/gui/interfaces/SlotPredicate-JavaDoc.md) (理解定义 **"在哪里生效"** 的条件)
- **核心逻辑查询 (事件分发)**：[查看](./JavaDocs/gui/GuiActionDispatcher-JavaDoc.md) (用于注册 `ClickAction` 与 `SlotPredicate` 的组合)
- **关联查询 (会话管理)**：[查看](./JavaDocs/gui/GUISessionManager-JavaDoc.md) (用于打开、关闭、验证玩家的GUI会话)
- **会话超时设置**：构造 `GUISessionManager` 时可传入自定义过期毫秒数，或稍后调用 `setSessionTimeout(long)` 动态调整。
- **关联查询 (数据载体)**：[查看](./JavaDocs/gui/ClickContext-JavaDoc.md) (用于在回调中获取点击类型、玩家等上下文信息)
- **关联查询 (辅助工具)**：[查看](./JavaDocs/gui/GuiManager-JavaDoc.md) (用于安全播放音效、清理光标、检查危险点击等)
- **分页界面基类**：[查看](./JavaDocs/gui/PaginatedGui-JavaDoc.md) (用于快速构建带翻页的GUI)
- **通用玩家会话**：[查看](./JavaDocs/gui/session/PlayerSessionManager-JavaDoc.md) (创建、获取或销毁与玩家相关的临时数据，退出或超时后自动清理)

### 数据库操作 (SQLite)
- **功能描述**：连接管理 SQLite 数据库，初始化表结构，执行增删改查（CRUD）、事务处理。内置 HikariCP 连接池并提供异步接口，适合并发环境。
- **包类路径**：`cn.drcomo.corelib.database.SQLiteDB`
- **查询文档**：[查看](./JavaDocs/database)
