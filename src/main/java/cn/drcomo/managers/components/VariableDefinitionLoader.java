package cn.drcomo.managers.components;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.model.structure.Limitations;
import cn.drcomo.model.structure.ValueType;
import cn.drcomo.model.structure.Variable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * 变量定义加载与解析组件
 * - 负责递归扫描 variables 目录，解析 .yml 中的变量定义
 * - 保持原有解析逻辑不变
 */
public class VariableDefinitionLoader {

    private final DrcomoVEX plugin;
    private final DebugUtil logger;

    public VariableDefinitionLoader(DrcomoVEX plugin, DebugUtil logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    /**
     * 加载所有变量定义（扫描 variables 目录）
     */
    public void loadAll(Consumer<Variable> register) {
        try {
            File variablesDir = new File(plugin.getDataFolder(), "variables");
            if (!variablesDir.exists() || !variablesDir.isDirectory()) {
                logger.warn("变量目录不存在: " + variablesDir.getAbsolutePath());
                return;
            }

            int[] loadedCount = new int[]{0};
            scanVariablesRecursively(variablesDir, "", loadedCount, register);
            logger.info("变量目录递归扫描完成，共加载 " + loadedCount[0] + " 个配置文件");
        } catch (Exception e) {
            logger.error("递归扫描变量目录失败", e);
        }
    }

    /** 递归扫描 variables 目录并加载所有 .yml */
    private void scanVariablesRecursively(File directory, String relativePath, int[] loadedCount, Consumer<Variable> register) {
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                String sub = relativePath.isEmpty() ? f.getName() : relativePath + "/" + f.getName();
                scanVariablesRecursively(f, sub, loadedCount, register);
            } else if (f.isFile() && f.getName().toLowerCase().endsWith(".yml")) {
                String base = f.getName().substring(0, f.getName().length() - 4);
                String configName = relativePath.isEmpty() ? base : relativePath + "/" + base;
                try {
                    loadVariableDefinitionsFromFile(f, configName, register);
                    loadedCount[0]++;
                    logger.debug("已加载变量文件: " + configName + " (" + f.getAbsolutePath() + ")");
                } catch (Exception e) {
                    logger.error("加载变量文件失败: " + configName + " (" + f.getAbsolutePath() + ")", e);
                }
            }
        }
    }

    /** 从磁盘文件直接加载并解析（支持子目录与中文路径） */
    private void loadVariableDefinitionsFromFile(File configFile, String configName, Consumer<Variable> register) {
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
        loadVariableDefinitionsFromConfig(configName, cfg, register);
    }

    /** 公共解析实现：从给定配置对象解析变量定义 */
    private void loadVariableDefinitionsFromConfig(String configName, FileConfiguration config, Consumer<Variable> register) {
        if (config == null) {
            logger.warn("配置文件无法解析: " + configName);
            return;
        }
        ConfigurationSection section = config.getConfigurationSection("variables");
        if (section == null) {
            logger.warn("未找到 variables 节: " + configName);
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                ConfigurationSection varSec = section.getConfigurationSection(key);
                if (varSec != null) {
                    Variable var = parseVariableDefinition(key, varSec);
                    register.accept(var);
                }
            } catch (Exception e) {
                logger.error("解析变量定义失败: " + key, e);
            }
        }
    }

    /** 解析单个变量定义（补充了空值安全与字段校验） */
    private Variable parseVariableDefinition(String key, ConfigurationSection section) {
        Variable.Builder builder = new Variable.Builder(key);
        Limitations.Builder lb = new Limitations.Builder();

        builder.name(section.getString("name"))
                .scope(section.getString("scope", "player"))
                .initial(section.getString("initial"))
                .cycle(section.getString("cycle"));

        String typeStr = section.getString("type");
        if (typeStr != null && !typeStr.trim().isEmpty()) {
            ValueType vt = ValueType.fromString(typeStr);
            if (vt != null) {
                builder.valueType(vt);
            } else {
                logger.warn("变量 " + key + " 定义了无效的类型: " + typeStr);
            }
        }

        if (section.contains("min")) lb.minValue(section.getString("min"));
        if (section.contains("max")) lb.maxValue(section.getString("max"));

        if (section.isConfigurationSection("limitations")) {
            ConfigurationSection limSec = section.getConfigurationSection("limitations");
            applyLimitationsFromSection(limSec, lb);
        }

        // 解析 conditions：支持字符串或列表
        if (section.contains("conditions")) {
            List<String> conds = new java.util.ArrayList<>();
            if (section.isList("conditions")) {
                conds = section.getStringList("conditions");
            } else if (section.isString("conditions")) {
                String c = section.getString("conditions");
                if (c != null && !c.trim().isEmpty()) conds.add(c);
            } else {
                logger.warn("变量 " + key + " 的 conditions 字段类型无效，已忽略");
            }
            if (!conds.isEmpty()) {
                builder.conditions(conds);
            }
        }

        // 解析周期动作：cycle-actions 可以是字符串或字符串列表
        if (section.contains("cycle-actions")) {
            List<String> actions = new java.util.ArrayList<>();
            if (section.isList("cycle-actions")) {
                actions = section.getStringList("cycle-actions");
            } else if (section.isString("cycle-actions")) {
                String a = section.getString("cycle-actions");
                if (a != null && !a.trim().isEmpty()) actions.add(a);
            } else {
                logger.warn("变量 " + key + " 的 cycle-actions 字段类型无效，已忽略");
            }
            if (!actions.isEmpty()) {
                builder.cycleActions(actions);
            }
        }

        builder.limitations(lb.build());
        return builder.build();
    }

    /** 从配置节填充 Limitations.Builder（减少重复 null 判断） */
    private void applyLimitationsFromSection(ConfigurationSection limSec, Limitations.Builder lb) {
        if (limSec == null) return;
        if (limSec.contains("read-only")) lb.readOnly(limSec.getBoolean("read-only"));
        if (limSec.contains("persistable")) lb.persistable(limSec.getBoolean("persistable"));
        if (limSec.contains("strict-initial-mode")) lb.strictInitialMode(limSec.getBoolean("strict-initial-mode"));
        if (limSec.contains("max-recursion-depth")) lb.maxRecursionDepth(limSec.getInt("max-recursion-depth"));
        if (limSec.contains("max-expression-length")) lb.maxExpressionLength(limSec.getInt("max-expression-length"));
        if (limSec.contains("allow-circular-references")) lb.allowCircularReferences(limSec.getBoolean("allow-circular-references"));
    }
}
