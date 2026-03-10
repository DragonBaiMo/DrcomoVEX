package cn.drcomo.redis;

import cn.drcomo.corelib.util.DebugUtil;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.Bukkit;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Redis 连接管理器
 *
 * 功能：
 * - 管理 Jedis 连接池
 * - 提供基础 KV 操作与发布消息能力
 * - 维护一个后台订阅线程（自动重连）
 */
public class RedisConnection {

    private static final String SERVER_ID_OWNER_PREFIX = "vex:server-id:owner:";

    private final DebugUtil logger;
    private final boolean enabled;
    private final String host;
    private final int port;
    private final String password;
    private final int database;
    private final int timeoutMillis;
    private final int maxTotal;
    private final int maxIdle;
    private final int minIdle;

    private volatile JedisPool pool;
    private volatile JedisPubSub activeSubscriber;
    private volatile ExecutorService subscriberExecutor;

    private final AtomicBoolean closing = new AtomicBoolean(false);

    public RedisConnection(DebugUtil logger, FileConfiguration config) {
        this.logger = logger;

        this.enabled = config.getBoolean("settings.redis-sync.enabled", false);
        this.host = config.getString("settings.redis-sync.host", "127.0.0.1");
        this.port = Math.max(1, config.getInt("settings.redis-sync.port", 6379));
        String pwd = config.getString("settings.redis-sync.password", "");
        this.password = (pwd == null || pwd.trim().isEmpty()) ? null : pwd;
        this.database = Math.max(0, config.getInt("settings.redis-sync.database", 0));
        this.timeoutMillis = Math.max(500, config.getInt("settings.redis-sync.pool.timeout-millis", 3000));

        this.maxTotal = Math.max(1, config.getInt("settings.redis-sync.pool.max-total", 8));
        this.maxIdle = Math.max(0, config.getInt("settings.redis-sync.pool.max-idle", 4));
        this.minIdle = Math.max(0, config.getInt("settings.redis-sync.pool.min-idle", 1));
    }

    public synchronized void initialize() {
        if (!enabled) {
            logger.info("Redis 同步已禁用");
            return;
        }
        closing.set(false);

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(maxTotal);
        poolConfig.setMaxIdle(maxIdle);
        poolConfig.setMinIdle(minIdle);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setMinEvictableIdleDuration(Duration.ofSeconds(30));

        try {
            pool = new JedisPool(poolConfig, host, port, timeoutMillis, password, database);
            try (Jedis jedis = pool.getResource()) {
                String pong = jedis.ping();
                if (!"PONG".equalsIgnoreCase(pong)) {
                    throw new IllegalStateException("PING 返回异常: " + pong);
                }
            }
            logger.info("Redis 连接成功: " + host + ":" + port + " db=" + database);
        } catch (Exception e) {
            logger.error("Redis 初始化失败，已降级为本地模式", e);
            safeClosePool();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isReady() {
        return enabled && pool != null && !closing.get();
    }

    public <T> T execute(Function<Jedis, T> action) {
        if (!isReady()) {
            return null;
        }
        try (Jedis jedis = pool.getResource()) {
            return action.apply(jedis);
        } catch (Exception e) {
            logger.debug("Redis 执行失败: " + e.getMessage());
            return null;
        }
    }

    public void executeVoid(Consumer<Jedis> action) {
        execute(jedis -> {
            action.accept(jedis);
            return null;
        });
    }

    public String get(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        if (Bukkit.isPrimaryThread()) {
            logger.debug("警告: 在主线程调用 Redis get，可能引发卡顿。key=" + key);
        }
        return execute(jedis -> jedis.get(key));
    }

    public void setEx(String key, int ttlSeconds, String value) {
        if (key == null || key.isEmpty() || value == null) {
            return;
        }
        int ttl = Math.max(1, ttlSeconds);
        executeVoid(jedis -> jedis.setex(key, ttl, value));
    }

    public void del(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        executeVoid(jedis -> jedis.del(key));
    }

    public void publish(String channel, String message) {
        if (channel == null || channel.isEmpty() || message == null) {
            return;
        }
        executeVoid(jedis -> jedis.publish(channel, message));
    }

    /**
     * 尝试占用 server-id。
     *
     * @return true=占用成功；false=已被其他实例占用或 Redis 不可用
     */
    public boolean tryClaimServerId(String serverId, String owner, int ttlSeconds) {
        if (serverId == null || serverId.isEmpty() || owner == null || owner.isEmpty()) {
            return false;
        }
        int ttl = Math.max(30, ttlSeconds);
        String key = buildServerIdOwnerKey(serverId);
        String result = execute(jedis -> jedis.set(key, owner, SetParams.setParams().nx().ex(ttl)));
        return "OK".equalsIgnoreCase(result);
    }

    /**
     * 刷新 server-id 占用 TTL。
     *
     * 仅当当前 owner 匹配时刷新；若 key 已过期则尝试重新占用。
     */
    public boolean refreshServerIdClaim(String serverId, String owner, int ttlSeconds) {
        if (serverId == null || serverId.isEmpty() || owner == null || owner.isEmpty()) {
            return false;
        }
        int ttl = Math.max(30, ttlSeconds);
        String key = buildServerIdOwnerKey(serverId);

        Boolean ok = execute(jedis -> {
            String current = jedis.get(key);
            if (current != null && !owner.equals(current)) {
                return Boolean.FALSE;
            }

            String refreshed = jedis.set(key, owner, SetParams.setParams().xx().ex(ttl));
            if ("OK".equalsIgnoreCase(refreshed)) {
                return Boolean.TRUE;
            }

            // key 不存在（可能刚过期），尝试重新占用
            String reclaimed = jedis.set(key, owner, SetParams.setParams().nx().ex(ttl));
            return "OK".equalsIgnoreCase(reclaimed);
        });

        return Boolean.TRUE.equals(ok);
    }

    public String getServerIdOwner(String serverId) {
        if (serverId == null || serverId.isEmpty()) {
            return null;
        }
        return get(buildServerIdOwnerKey(serverId));
    }

    /**
     * 仅在 owner 匹配时释放 server-id 占用。
     */
    public void releaseServerIdClaim(String serverId, String owner) {
        if (serverId == null || serverId.isEmpty() || owner == null || owner.isEmpty()) {
            return;
        }
        String key = buildServerIdOwnerKey(serverId);
        executeVoid(jedis -> {
            String current = jedis.get(key);
            if (owner.equals(current)) {
                jedis.del(key);
            }
        });
    }

    private String buildServerIdOwnerKey(String serverId) {
        return SERVER_ID_OWNER_PREFIX + serverId;
    }

    /**
     * 启动订阅线程。
     *
     * 注意：
     * - Jedis subscribe 为阻塞调用，因此必须独立线程运行。
     * - 发生异常时会自动重连，直到 close()/stopSubscriber()。
     */
    public synchronized void subscribe(String[] channels, BiConsumer<String, String> handler) {
        if (!isReady() || channels == null || channels.length == 0 || handler == null) {
            return;
        }

        stopSubscriber();

        subscriberExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DrcomoVEX-RedisSubscriber");
            t.setDaemon(true);
            return t;
        });

        subscriberExecutor.submit(() -> {
            while (!closing.get() && !Thread.currentThread().isInterrupted()) {
                JedisPubSub subscriber = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        try {
                            handler.accept(channel, message);
                        } catch (Exception e) {
                            logger.warn("处理 Redis 消息失败: channel=" + channel + ", err=" + e.getMessage());
                        }
                    }
                };

                activeSubscriber = subscriber;
                try (Jedis jedis = pool.getResource()) {
                    jedis.subscribe(subscriber, channels);
                } catch (Exception e) {
                    if (closing.get()) {
                        break;
                    }
                    logger.warn("Redis 订阅异常，1秒后重试: " + e.getMessage());
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } finally {
                    activeSubscriber = null;
                }
            }
        });
    }

    public synchronized void stopSubscriber() {
        JedisPubSub sub = activeSubscriber;
        if (sub != null) {
            try {
                sub.unsubscribe();
            } catch (Exception ignored) {
                // ignore
            }
        }

        ExecutorService executor = subscriberExecutor;
        subscriberExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public synchronized void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        stopSubscriber();
        safeClosePool();
    }

    private void safeClosePool() {
        if (pool != null) {
            try {
                pool.close();
            } catch (Exception ignored) {
                // ignore
            } finally {
                pool = null;
            }
        }
    }
}
