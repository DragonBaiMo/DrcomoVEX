package cn.drcomo.util;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rank 保护日志记录器
 * 
 * 将被拦截的 rank 减少操作记录到单独的日志文件中。
 * 使用异步写入避免阻塞主线程。
 * 
 * @author BaiMo
 */
public class RankProtectionLogger {
    
    private static final String LOG_FILE_NAME = "rank-protection.log";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    private final JavaPlugin plugin;
    private final File logFile;
    private final BlockingQueue<String> logQueue;
    private final AtomicBoolean running;
    private Thread writerThread;
    
    public RankProtectionLogger(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), LOG_FILE_NAME);
        this.logQueue = new LinkedBlockingQueue<>(10000);
        this.running = new AtomicBoolean(true);
        
        // 确保日志目录存在
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        
        // 启动异步写入线程
        startWriterThread();
    }
    
    /**
     * 记录被拦截的操作
     * 
     * @param operation    操作类型（ADD/REMOVE）
     * @param player       玩家
     * @param currentValue 当前值
     * @param attemptedChange 尝试的变化量
     * @param reason       拦截原因
     * @param caller       调用来源（可选）
     */
    public void logBlockedOperation(
            String operation,
            OfflinePlayer player,
            String currentValue,
            String attemptedChange,
            String reason,
            String caller
    ) {
        String timestamp = LocalDateTime.now().format(DATE_FORMAT);
        String playerName = player != null ? player.getName() : "UNKNOWN";
        String playerUuid = player != null ? player.getUniqueId().toString() : "-";
        String threadName = Thread.currentThread().getName();
        
        String logLine = String.format(
                "[%s] BLOCKED %s | player=%s | uuid=%s | current=%s | attempted=%s | reason=%s | thread=%s | caller=%s",
                timestamp,
                operation,
                playerName,
                playerUuid,
                currentValue,
                attemptedChange,
                reason,
                threadName,
                caller != null ? caller : "(unknown)"
        );
        
        // 非阻塞入队
        if (!logQueue.offer(logLine)) {
            // 队列满时打印到控制台
            plugin.getLogger().warning("[RankProtection] 日志队列已满，丢弃日志: " + logLine);
        }
    }
    
    /**
     * 启动异步写入线程
     */
    private void startWriterThread() {
        writerThread = new Thread(() -> {
            try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile, true)))) {
                while (running.get() || !logQueue.isEmpty()) {
                    try {
                        String line = logQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (line != null) {
                            writer.println(line);
                            writer.flush();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                // 清空剩余日志
                String remaining;
                while ((remaining = logQueue.poll()) != null) {
                    writer.println(remaining);
                }
                writer.flush();
            } catch (IOException e) {
                plugin.getLogger().severe("[RankProtection] 无法写入日志文件: " + e.getMessage());
            }
        }, "DrcomoVEX-RankProtection-Logger");
        
        writerThread.setDaemon(true);
        writerThread.start();
    }
    
    /**
     * 关闭日志记录器
     */
    public void shutdown() {
        running.set(false);
        if (writerThread != null) {
            try {
                writerThread.interrupt();
                writerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * 获取调用来源摘要
     */
    public static String getCallerSummary() {
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            // 跳过 getStackTrace, getCallerSummary, logBlockedOperation, 拦截方法
            int start = 4;
            StringBuilder sb = new StringBuilder();
            int appended = 0;
            for (int i = start; i < st.length && appended < 4; i++) {
                StackTraceElement e = st[i];
                if (e == null) continue;
                String cls = e.getClassName();
                // 跳过 JDK/反射框架栈
                if (cls != null && (cls.startsWith("java.") || cls.startsWith("jdk."))) {
                    continue;
                }
                if (sb.length() > 0) sb.append(" <- ");
                // 简化类名
                String simpleName = cls.substring(cls.lastIndexOf('.') + 1);
                sb.append(simpleName).append("#").append(e.getMethodName()).append(":").append(e.getLineNumber());
                appended++;
            }
            return sb.length() == 0 ? "(unknown)" : sb.toString();
        } catch (Exception ignored) {
            return "(unknown)";
        }
    }
}
