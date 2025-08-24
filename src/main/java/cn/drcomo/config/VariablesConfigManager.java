package cn.drcomo.config;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.corelib.config.YamlUtil;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 变量配置管理器
 * 
 * 负责递归扫描和加载 variables 目录下的所有 yml 配置文件。
 * 支持任意层级的目录结构，提供统一访问接口。
 * 
 * @author BaiMo
 */
public class VariablesConfigManager {
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final YamlUtil yamlUtil;
    
    // 存储所有变量配置文件的映射关系
    // key: 基于相对路径的配置名称
    // value: YamlConfiguration 实例
    private final Map<String, YamlConfiguration> variableConfigs = new HashMap<>();
    
    // 存储配置文件路径到配置名称的映射
    private final Map<String, String> pathToConfigName = new HashMap<>();
    
    private static final String VARIABLES_DIR = "variables";
    
    public VariablesConfigManager(DrcomoVEX plugin, DebugUtil logger, YamlUtil yamlUtil) {
        this.plugin = plugin;
        this.logger = logger;
        this.yamlUtil = yamlUtil;
    }
    
    /**
     * 初始化变量配置管理器
     */
    public void initialize() {
        logger.info("正在初始化变量配置管理器...");
        
        // 确保 variables 目录存在
        ensureVariablesDirectory();
        
        // 递归加载所有配置文件
        loadAllVariableConfigs();
        
        logger.info("变量配置管理器初始化完成，已加载 " + variableConfigs.size() + " 个配置文件");
    }
    
    /**
     * 重载所有变量配置
     */
    public void reload() {
        logger.info("正在重载所有变量配置...");
        
        // 清空现有配置
        variableConfigs.clear();
        pathToConfigName.clear();
        
        // 重新加载所有配置
        loadAllVariableConfigs();
        
        logger.info("所有变量配置已重载完成，共 " + variableConfigs.size() + " 个配置文件");
    }
    
    /**
     * 确保 variables 目录存在，并拷贝默认文件
     */
    private void ensureVariablesDirectory() {
        File variablesFolder = new File(plugin.getDataFolder(), VARIABLES_DIR);
        if (!variablesFolder.exists()) {
            logger.info("首次运行，正在初始化变量配置目录...");
            yamlUtil.ensureFolderAndCopyDefaults("variables", "variables", new String[0]);
            logger.info("默认变量配置文件已拷贝完成！");
        }
    }
    
    /**
     * 递归加载 variables 目录下的所有 yml 配置文件
     */
    private void loadAllVariableConfigs() {
        File variablesDir = new File(plugin.getDataFolder(), VARIABLES_DIR);
        if (!variablesDir.exists() || !variablesDir.isDirectory()) {
            logger.warn("变量配置目录不存在: " + variablesDir.getAbsolutePath());
            return;
        }
        
        // 递归扫描并加载所有 yml 文件
        loadConfigsRecursively(variablesDir, "");
        
        logger.debug("变量配置文件加载详情:");
        for (Map.Entry<String, YamlConfiguration> entry : variableConfigs.entrySet()) {
            logger.debug("  - " + entry.getKey() + " (节点数: " + entry.getValue().getKeys(false).size() + ")");
        }
    }
    
    /**
     * 递归加载指定目录下的所有 yml 配置文件
     * 
     * @param directory 要扫描的目录
     * @param relativePath 相对于 variables 目录的路径
     */
    private void loadConfigsRecursively(File directory, String relativePath) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        
        for (File file : files) {
            if (file.isDirectory()) {
                // 递归处理子目录
                String subPath = relativePath.isEmpty() ? file.getName() : relativePath + "/" + file.getName();
                loadConfigsRecursively(file, subPath);
            } else if (file.isFile() && file.getName().toLowerCase().endsWith(".yml")) {
                // 处理 yml 文件
                loadSingleConfigFile(file, relativePath);
            }
        }
    }
    
    /**
     * 加载单个配置文件
     * 
     * @param configFile 配置文件
     * @param relativePath 相对路径
     */
    private void loadSingleConfigFile(File configFile, String relativePath) {
        try {
            String fileName = configFile.getName().replace(".yml", "");
            String configName = relativePath.isEmpty() ? fileName : relativePath + "/" + fileName;
            
            // 构造相对于插件数据目录的路径
            String relativeFilePath = VARIABLES_DIR + "/" + (relativePath.isEmpty() ? "" : relativePath + "/") + configFile.getName();
            
            // 使用 YamlUtil 加载配置
            yamlUtil.loadConfig(configName);
            YamlConfiguration config = yamlUtil.getConfig(configName);
            
            if (config != null) {
                variableConfigs.put(configName, config);
                pathToConfigName.put(relativeFilePath, configName);
                logger.debug("已加载变量配置: " + configName + " (" + relativeFilePath + ")");
            } else {
                logger.warn("无法加载变量配置文件: " + relativeFilePath);
            }
        } catch (Exception e) {
            logger.error("加载变量配置文件时出错: " + configFile.getAbsolutePath(), e);
        }
    }
    
    /**
     * 获取指定名称的变量配置
     * 
     * @param configName 配置名称
     * @return YamlConfiguration 实例，如果不存在则返回 null
     */
    public YamlConfiguration getVariableConfig(String configName) {
        return variableConfigs.get(configName);
    }
    
    /**
     * 获取所有已加载的变量配置名称
     * 
     * @return 配置名称列表
     */
    public Set<String> getAllConfigNames() {
        return new HashSet<>(variableConfigs.keySet());
    }
    
    /**
     * 获取指定路径下的所有配置名称
     * 
     * @param pathPrefix 路径前缀
     * @return 匹配的配置名称列表
     */
    public Set<String> getConfigNamesByPath(String pathPrefix) {
        return variableConfigs.keySet().stream()
                .filter(name -> name.startsWith(pathPrefix + "/") || name.equals(pathPrefix))
                .collect(Collectors.toSet());
    }
    
    /**
     * 检查指定配置是否存在
     * 
     * @param configName 配置名称
     * @return true 如果配置存在
     */
    public boolean hasConfig(String configName) {
        return variableConfigs.containsKey(configName);
    }
    
    /**
     * 从指定配置中获取字符串值
     * 
     * @param configName 配置名称
     * @param path 配置路径
     * @param def 默认值
     * @return 配置值
     */
    public String getString(String configName, String path, String def) {
        YamlConfiguration config = variableConfigs.get(configName);
        if (config != null) {
            return config.getString(path, def);
        }
        return def;
    }
    
    /**
     * 从指定配置中获取整数值
     * 
     * @param configName 配置名称
     * @param path 配置路径
     * @param def 默认值
     * @return 配置值
     */
    public int getInt(String configName, String path, int def) {
        YamlConfiguration config = variableConfigs.get(configName);
        if (config != null) {
            return config.getInt(path, def);
        }
        return def;
    }
    
    /**
     * 从指定配置中获取布尔值
     * 
     * @param configName 配置名称
     * @param path 配置路径
     * @param def 默认值
     * @return 配置值
     */
    public boolean getBoolean(String configName, String path, boolean def) {
        YamlConfiguration config = variableConfigs.get(configName);
        if (config != null) {
            return config.getBoolean(path, def);
        }
        return def;
    }
    
    /**
     * 从指定配置中获取双精度浮点值
     * 
     * @param configName 配置名称
     * @param path 配置路径
     * @param def 默认值
     * @return 配置值
     */
    public double getDouble(String configName, String path, double def) {
        YamlConfiguration config = variableConfigs.get(configName);
        if (config != null) {
            return config.getDouble(path, def);
        }
        return def;
    }
    
    /**
     * 从指定配置中获取字符串列表
     * 
     * @param configName 配置名称
     * @param path 配置路径
     * @param def 默认值
     * @return 配置值
     */
    public List<String> getStringList(String configName, String path, List<String> def) {
        YamlConfiguration config = variableConfigs.get(configName);
        if (config != null) {
            List<String> list = config.getStringList(path);
            return list.isEmpty() ? def : list;
        }
        return def;
    }
    
    /**
     * 获取配置统计信息
     * 
     * @return 配置统计信息字符串
     */
    public String getConfigStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("变量配置统计信息:\n");
        stats.append("  总配置文件数: ").append(variableConfigs.size()).append("\n");
        
        // 按顶级目录分组统计
        Map<String, Integer> pathStats = new HashMap<>();
        for (String configName : variableConfigs.keySet()) {
            String rootPath = configName.contains("/") ? configName.split("/")[0] : "根目录";
            pathStats.put(rootPath, pathStats.getOrDefault(rootPath, 0) + 1);
        }
        
        stats.append("  目录分布:\n");
        for (Map.Entry<String, Integer> entry : pathStats.entrySet()) {
            stats.append("    ").append(entry.getKey()).append(": ").append(entry.getValue()).append(" 个文件\n");
        }
        
        return stats.toString();
    }
}