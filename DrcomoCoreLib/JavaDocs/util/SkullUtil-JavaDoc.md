### `SkullUtil.java`

**1. 概述 (Overview)**

  * **完整路径:** `cn.drcomo.corelib.util.SkullUtil`
  * **核心职责:** 根据 URL 或 base64 字符串生成带自定义纹理的玩家頭顱 `ItemStack`。内部封装 `GameProfile`、`SkullMeta` 等实现细节，并通过 `DebugUtil` 输出异常日志。

**2. 如何实例化 (Initialization)**

  * **核心思想:** 该类无状态，构造时只需注入 `DebugUtil` 以便记录日志，可在多个模块中复用。
  * **代码示例:**
    ```java
    DebugUtil logger = new DebugUtil(this, DebugUtil.LogLevel.INFO);
    SkullUtil skullUtil = new SkullUtil(logger);
    ```

**3. 公共API方法 (Public API Methods)**

  * #### `ItemStack fromUrl(String url)`
      * **功能描述:** 传入纹理 URL，返回带有该纹理的玩家头颅物品。
      * **参数说明:** `url` (`String`): 形如 `http://textures.minecraft.net/texture/...` 的地址。
      * **返回类型:** `ItemStack` - 带自定义纹理的玩家头颅物品，若 URL 为空或异常则返回普通玩家头颅。

  * #### `ItemStack fromBase64(String base64)`
      * **功能描述:** 传入已编码的纹理 Base64 字符串，返回玩家头颅物品。
      * **参数说明:** `base64` (`String`): 已编码的纹理 Base64 字符串。
      * **返回类型:** `ItemStack` - 带自定义纹理的玩家头颅物品，若处理失败则返回普通玩家头颅。

**4. 创建自定义头像示例 (Usage Example)**

```java
String url = "http://textures.minecraft.net/texture/<texture-id>";
ItemStack skull = skullUtil.fromUrl(url);
player.getInventory().addItem(skull);
```

**5. 性能优化建议 (Performance Recommendations)**

  * #### 异步使用 (Asynchronous Usage)
      * **重要性:** 头像纹理加载涉及网络请求，会阻塞主线程导致TPS下降。**强烈建议在异步线程中调用**。
      * **代码示例:**
        ```java
        // 错误示例 - 阻塞主线程
        ItemStack skull = skullUtil.fromUrl(url); // 可能导致TPS下降
        
        // 正确示例 - 异步加载
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ItemStack skull = skullUtil.fromUrl(url);
            
            // 回到主线程更新GUI
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (inventory != null) {
                    inventory.setItem(slot, skull);
                }
            });
        });
        ```

  * #### 缓存策略 (Caching Strategy)
      * **必要性:** 相同玩家的头像纹理不会频繁变化，应实现缓存机制避免重复网络请求。
      * **缓存实现建议:**
        ```java
        // 使用ConcurrentHashMap实现线程安全缓存
        private static final Map<String, ItemStack> skullCache = new ConcurrentHashMap<>();
        private static final int MAX_CACHE_SIZE = 1000;
        
        public ItemStack getCachedSkull(String textureUrl) {
            return skullCache.computeIfAbsent(textureUrl, url -> {
                if (skullCache.size() >= MAX_CACHE_SIZE) {
                    clearOldCache(); // 清理旧缓存
                }
                return skullUtil.fromUrl(url);
            });
        }
        ```

  * #### 懒加载模式 (Lazy Loading Pattern)
      * **应用场景:** GUI中显示多个玩家头像时，建议先显示默认头像，再异步加载真实头像。
      * **实现方式:**
        ```java
        // 1. 先设置默认头像，立即显示
        ItemStack defaultSkull = new ItemStack(Material.PLAYER_HEAD);
        inventory.setItem(slot, defaultSkull);
        
        // 2. 异步加载真实头像
        loadSkullAsync(textureUrl, inventory, slot);
        ```

  * #### 超时处理 (Timeout Handling)
      * **必要性:** 网络请求可能失败或超时，需要设置超时机制和降级方案。
      * **建议实现:**
        ```java
        CompletableFuture.supplyAsync(() -> skullUtil.fromUrl(url))
            .orTimeout(5, TimeUnit.SECONDS)
            .exceptionally(throwable -> {
                logger.warn("头像加载超时: " + url);
                return fallbackSkull; // 返回备用头像
            });
        ```

**6. 内存管理建议 (Memory Management)**

  * #### 缓存大小控制
      * 建议设置缓存最大条目数（如1000个），防止内存溢出
      * 实现LRU或定期清理机制清理长时间未使用的缓存

  * #### 避免内存泄漏
      * GUI关闭时及时清理相关的头像引用
      * 使用WeakReference包装缓存值，允许GC自动回收

**7. 最佳实践总结 (Best Practices)**

  * ✅ **总是在异步线程中调用头像加载方法**
  * ✅ **实现缓存机制避免重复网络请求**
  * ✅ **使用懒加载提升用户体验**
  * ✅ **设置合理的超时时间和降级方案**
  * ✅ **控制缓存大小防止内存问题**
  * ❌ **避免在主线程中同步加载多个头像**
  * ❌ **避免无限制的缓存增长**
  * ❌ **避免忽略网络异常处理**
