package cn.drcomo.listeners;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.managers.PlayerVariablesManager;
import cn.drcomo.managers.ServerVariablesManager;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.model.structure.Variable;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家事件监听器
 * 
 * 处理玩家登入和退出事件，管理玩家数据的加载和保存。
 * 
 * @author BaiMo
 */
public class PlayerListener implements Listener {
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final PlayerVariablesManager playerVariablesManager;
    private final ServerVariablesManager serverVariablesManager;
    // 入服兜底刷新冷却：防止玩家频繁重登触发
    private final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long> lastJoinRefreshAt = new java.util.concurrent.ConcurrentHashMap<>();
    
    public PlayerListener(
            DrcomoVEX plugin,
            DebugUtil logger,
            PlayerVariablesManager playerVariablesManager,
            ServerVariablesManager serverVariablesManager
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.playerVariablesManager = playerVariablesManager;
        this.serverVariablesManager = serverVariablesManager;
    }
    
    /**
     * 玩家加入事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        try {
            // 预加载玩家变量数据到内存（高性能优化）
            plugin.getVariablesManager().handlePlayerJoin(event.getPlayer())
                    .thenRun(() -> logger.debug("玩家 " + event.getPlayer().getName() + " 变量数据预加载完成"))
                    .exceptionally(throwable -> {
                        logger.error("玩家变量数据预加载失败: " + event.getPlayer().getName(), throwable);
                        return null;
                    });
            
            logger.debug("玩家 " + event.getPlayer().getName() + " 登入，变量数据预加载中");
            serverVariablesManager.handlePlayerJoin(event.getPlayer());

            // 入服兜底刷新：失效关键变量缓存并为该玩家预热（不删除内存变量，不写库），解决多子服共库下的跨服缓存一致性问题
            scheduleJoinConsistencyRefresh(event.getPlayer());
            
        } catch (Exception e) {
            logger.error("处理玩家登入事件失败: " + event.getPlayer().getName(), e);
        }
    }
    
    /**
     * 玩家退出事件
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        try {
            // 立即持久化玩家变量数据（高性能优化）
            plugin.getVariablesManager().handlePlayerQuit(event.getPlayer())
                    .thenRun(() -> logger.debug("玩家 " + event.getPlayer().getName() + " 变量数据持久化完成"))
                    .exceptionally(throwable -> {
                        logger.error("玩家变量数据持久化失败: " + event.getPlayer().getName(), throwable);
                        return null;
                    });
            
            logger.debug("玩家 " + event.getPlayer().getName() + " 退出，变量数据持久化中");
            serverVariablesManager.handlePlayerQuit(event.getPlayer());
            // 冷却清理
            lastJoinRefreshAt.remove(event.getPlayer().getUniqueId());
            
        } catch (Exception e) {
            logger.error("处理玩家退出事件失败: " + event.getPlayer().getName(), e);
        }
    }

    /**
     * 入服兜底刷新：
     * - 异步延迟执行，避免阻塞主线程
     * - 优先使用配置的键清单；若未配置，则自动筛选带周期的玩家变量键
     * - 对目标键先清理内存与缓存，再为当前玩家预热，确保读取到一致的新值
     */
    private void scheduleJoinConsistencyRefresh(Player player) {
        FileConfiguration cfg = plugin.getConfigsManager().getMainConfig();
        boolean enabled = cfg.getBoolean("settings.join-refresh.enabled", true);
        if (!enabled) {
            return;
        }

        int delayMs = cfg.getInt("settings.join-refresh.delay-millis", 800);
        int limit = Math.max(1, cfg.getInt("settings.join-refresh.max-keys", 32));
        int cooldownSeconds = Math.max(0, cfg.getInt("settings.join-refresh.cooldown-seconds", 60));
        var configuredKeys = cfg.getStringList("settings.join-refresh.keys");

        // 冷却检查（按玩家）
        long now = System.currentTimeMillis();
        Long last = lastJoinRefreshAt.get(player.getUniqueId());
        if (last != null && (now - last) < cooldownSeconds * 1000L) {
            return;
        }
        lastJoinRefreshAt.put(player.getUniqueId(), now);

        Runnable task = () -> {
            try {
                // 延迟执行，等待其他入服链路预加载完成，降低并发写的概率
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                // 玩家可能已离线
                if (!player.isOnline()) {
                    return;
                }

                // 组装候选键
                java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
                if (configuredKeys != null && !configuredKeys.isEmpty()) {
                    for (String k : configuredKeys) {
                        if (k != null && !k.isEmpty()) keys.add(k);
                        if (keys.size() >= limit) break;
                    }
                } else {
                    // 自动筛选：仅玩家作用域且配置了周期（避免全量遍历）
                    for (String k : plugin.getVariablesManager().getPlayerVariableKeys()) {
                        Variable v = plugin.getVariablesManager().getVariableDefinition(k);
                        if (v != null && v.isPlayerScoped()) {
                            String cycle = v.getCycle();
                            if (cycle != null && !cycle.isEmpty()) {
                                keys.add(k);
                            }
                        }
                        if (keys.size() >= limit) break;
                    }
                }

                if (keys.isEmpty()) {
                    return;
                }

                // 仅失效缓存：
                // 1) 先对该玩家做一次性 L2 批量清理；
                // 2) 再按作用域仅清 L3（避免重复清 L2），不改内存存储，不产生删除标记
                long startNs = System.nanoTime();
                long l2Before = plugin.getVariablesManager().getL2InvalidationsTotal();
                long l3Before = plugin.getVariablesManager().getL3InvalidationsTotal();
                int l2BatchCleared = plugin.getVariablesManager().invalidateAllL2ForPlayer(player);

                int playerScopedCount = 0;
                int globalScopedCount = 0;
                for (String key : keys) {
                    try {
                        Variable vdef = plugin.getVariablesManager().getVariableDefinition(key);
                        if (vdef != null) {
                            if (vdef.isPlayerScoped()) {
                                plugin.getVariablesManager().invalidateL3Only(player, key);
                                playerScopedCount++;
                            } else if (vdef.isGlobal()) {
                                plugin.getVariablesManager().invalidateL3Only(null, key);
                                globalScopedCount++;
                            } else {
                                // 未知作用域，保守处理：仅清玩家上下文 L3，避免全局抖动
                                plugin.getVariablesManager().invalidateL3Only(player, key);
                                playerScopedCount++;
                            }
                        } else {
                            // 未找到定义，保守处理：仅清玩家上下文 L3
                            plugin.getVariablesManager().invalidateL3Only(player, key);
                            playerScopedCount++;
                        }
                    } catch (Exception ex) {
                        logger.debug("入服兜底刷新: 失效缓存失败 key=" + key + ", err=" + ex.getMessage());
                    }
                }

                long l2After = plugin.getVariablesManager().getL2InvalidationsTotal();
                long l3After = plugin.getVariablesManager().getL3InvalidationsTotal();
                long l2Delta = Math.max(0, l2After - l2Before);
                long l3Delta = Math.max(0, l3After - l3Before);
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

                // 可选预热：仅在开启且线程池可用时执行
                boolean preheat = cfg.getBoolean("settings.join-refresh.preheat", false);
                if (preheat) {
                    java.util.List<java.util.concurrent.CompletableFuture<?>> futures = new java.util.ArrayList<>();
                    for (String key : keys) {
                        try {
                            futures.add(playerVariablesManager.getPlayerVariable(player, key)
                                    .exceptionally(t -> {
                                        logger.debug("入服兜底刷新: 预热失败 key=" + key + ", err=" + t.getMessage());
                                        return null;
                                    }));
                        } catch (java.util.concurrent.RejectedExecutionException rex) {
                            // 线程池已关闭/未就绪，跳过预热
                            logger.debug("入服兜底刷新: 线程池不可用，跳过预热 key=" + key);
                        } catch (Exception ex) {
                            logger.debug("入服兜底刷新: 预热异常 key=" + key + ", err=" + ex.getMessage());
                        }
                    }
                    if (!futures.isEmpty()) {
                        try {
                            java.util.concurrent.CompletableFuture.allOf(
                                    futures.toArray(new java.util.concurrent.CompletableFuture[0])
                            ).orTimeout(3, java.util.concurrent.TimeUnit.SECONDS).exceptionally(t -> null).join();
                        } catch (Exception ignore) {}
                    }
                }

                logger.debug("玩家 " + player.getName() + " 入服兜底刷新完成，处理键数: " + keys.size()
                        + ", 玩家作用域: " + playerScopedCount
                        + ", 全局作用域: " + globalScopedCount
                        + ", L2批量清理: " + l2BatchCleared
                        + ", L2增量: " + l2Delta
                        + ", L3增量: " + l3Delta
                        + ", 耗时: " + elapsedMs + " ms");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.error("入服兜底刷新执行异常: " + player.getName(), e);
            }
        };

        try {
            plugin.getAsyncTaskManager().submitAsync(task);
        } catch (java.util.concurrent.RejectedExecutionException rex) {
            // 异步线程池未就绪或已关闭，退回 Bukkit 异步
            try {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            } catch (Exception e) {
                logger.debug("入服兜底刷新: 无法调度异步任务，已跳过");
            }
        }
    }
}