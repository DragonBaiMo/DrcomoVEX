package cn.drcomo.managers;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.corelib.async.AsyncTaskManager;
import cn.drcomo.corelib.net.HttpUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 更新检查管理器
 * 
 * 负责检查插件更新并通知管理员。
 * 
 * @author BaiMo
 */
public class UpdateCheckerManager {
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final AsyncTaskManager asyncTaskManager;
    
    // GitHub API 地址
    private static final String GITHUB_API_URL = "https://api.github.com/repos/DragonBaiMo/DrcomoVEX/releases/latest";
    
    // 当前版本
    private final String currentVersion;
    
    // 更新状态
    private boolean updateAvailable = false;
    private String latestVersion = "";
    private String downloadUrl = "";
    private String releaseNotes = "";
    
    public UpdateCheckerManager(
            DrcomoVEX plugin,
            DebugUtil logger,
            AsyncTaskManager asyncTaskManager
    ) {
        this.plugin = plugin;
        this.logger = logger;
        this.asyncTaskManager = asyncTaskManager;
        this.currentVersion = plugin.getDescription().getVersion();
    }
    
    /**
     * 初始化更新检查管理器
     */
    public void initialize() {
        logger.info("正在初始化更新检查管理器...");
        logger.info("更新检查管理器初始化完成！");
    }
    
    /**
     * 检查更新
     */
    public CompletableFuture<Boolean> checkForUpdates() {
        logger.info("正在检查插件更新...");
        
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        asyncTaskManager.submitAsync(() -> {
            try {
                HttpUtil httpUtil = HttpUtil.newBuilder()
                        .logger(logger)
                        .defaultHeader("User-Agent", "DrcomoVEX-UpdateChecker")
                        .build();

                String response = httpUtil.get(GITHUB_API_URL, Map.of()).join();

                if (response != null && !response.trim().isEmpty()) {
                    future.complete(parseUpdateResponse(response));
                } else {
                    logger.warn("获取更新信息失败：响应为空");
                    future.complete(false);
                }
            } catch (Exception e) {
                logger.error("检查更新时发生异常", e);
                future.complete(false);
            }
        });

        return future.thenApply(hasUpdate -> {
            if (hasUpdate) {
                notifyUpdateAvailable();
            } else {
                logger.info("当前版本已是最新版本: " + currentVersion);
            }
            return hasUpdate;
        });
    }
    
    /**
     * 解析更新响应
     */
    private boolean parseUpdateResponse(String response) {
        try {
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            
            // 获取最新版本号
            var tagNameElement = json.get("tag_name");
            if (tagNameElement == null || tagNameElement.isJsonNull()) {
                logger.warn("响应中缺少tag_name字段或为null");
                return false;
            }
            String tagName = tagNameElement.getAsString();
            latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
            
            // 获取下载链接
            if (json.has("assets") && json.get("assets").isJsonArray()) {
                var assets = json.getAsJsonArray("assets");
                if (assets.size() > 0) {
                    var firstAsset = assets.get(0).getAsJsonObject();
                    downloadUrl = firstAsset.get("browser_download_url").getAsString();
                }
            }
            
            // 获取更新说明
            if (json.has("body")) {
                releaseNotes = json.get("body").getAsString();
            }
            
            // 比较版本
            updateAvailable = isNewerVersion(latestVersion, currentVersion);
            
            return updateAvailable;
            
        } catch (Exception e) {
            logger.error("解析更新响应失败", e);
            return false;
        }
    }
    
    /**
     * 比较版本号
     */
    private boolean isNewerVersion(String latest, String current) {
        try {
            String[] latestParts = latest.split("\\.");
            String[] currentParts = current.split("\\.");
            
            int maxLength = Math.max(latestParts.length, currentParts.length);
            
            for (int i = 0; i < maxLength; i++) {
                int latestPart = i < latestParts.length ? 
                    Integer.parseInt(latestParts[i].replaceAll("[^0-9]", "")) : 0;
                int currentPart = i < currentParts.length ? 
                    Integer.parseInt(currentParts[i].replaceAll("[^0-9]", "")) : 0;
                
                if (latestPart > currentPart) {
                    return true;
                } else if (latestPart < currentPart) {
                    return false;
                }
            }
            
            return false; // 版本相同
            
        } catch (Exception e) {
            logger.error("比较版本号失败", e);
            return false;
        }
    }
    
    /**
     * 通知更新可用
     */
    private void notifyUpdateAvailable() {
        logger.info("发现新版本！当前版本: " + currentVersion + ", 最新版本: " + latestVersion);
        
        // 通知在线的OP
        Bukkit.getScheduler().runTask(plugin, () -> {
            String message = String.format(
                "§6[§eDrcomoVEX§6] §a发现新版本！当前: §c%s§a, 最新: §b%s",
                currentVersion, latestVersion
            );
            
            Bukkit.getOnlinePlayers().stream()
                    .filter(player -> player.isOp())
                    .forEach(player -> player.sendMessage(message));
                    
            // 发送给控制台
            Bukkit.getConsoleSender().sendMessage(message);
            
            if (!downloadUrl.isEmpty()) {
                String downloadMessage = "§6[§eDrcomoVEX§6] §a下载地址: §f" + downloadUrl;
                Bukkit.getOnlinePlayers().stream()
                        .filter(player -> player.isOp())
                        .forEach(player -> player.sendMessage(downloadMessage));
                Bukkit.getConsoleSender().sendMessage(downloadMessage);
            }
        });
    }
    
    /**
     * 定时检查更新
     */
    public void startPeriodicCheck() {
        // 每24小时检查一次
        asyncTaskManager.scheduleAtFixedRate(
                () -> { checkForUpdates(); },
                3600000,
                86400000,
                java.util.concurrent.TimeUnit.MILLISECONDS
        );
        
        logger.info("已启动定时更新检查（每24小时）");
    }
    
    // Getter 方法
    public boolean isUpdateAvailable() {
        return updateAvailable;
    }
    
    public String getLatestVersion() {
        return latestVersion;
    }
    
    public String getCurrentVersion() {
        return currentVersion;
    }
    
    public String getDownloadUrl() {
        return downloadUrl;
    }
    
    public String getReleaseNotes() {
        return releaseNotes;
    }
}