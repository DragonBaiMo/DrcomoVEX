package cn.drcomo.config;

import cn.drcomo.DrcomoVEX;
import org.bukkit.configuration.file.YamlConfiguration;
import cn.drcomo.model.structure.Limitations;
import cn.drcomo.model.structure.ValueType;
import cn.drcomo.model.structure.Variable;
import cn.drcomo.model.structure.VariableType;
import cn.drcomo.corelib.config.YamlUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 统一管理插件运行过程中涉及的所有配置文件。
 */
public class ConfigsManager {

        private PlayerConfigsManager playerConfigsManager;
        private DataConfigManager dataConfigManager;
        private MainConfigManager mainConfigManager;
        private DrcomoVEX plugin;
        private YamlUtil yamlUtil;

        /**
         * 使用指定插件实例和 YAML 工具创建配置管理器。
         *
         * @param plugin   主插件实例
         * @param yamlUtil 核心库提供的 YAML 工具
         */
        public ConfigsManager(DrcomoVEX plugin, YamlUtil yamlUtil) {
                this.plugin = plugin;
                this.yamlUtil = yamlUtil;
                this.mainConfigManager = new MainConfigManager(plugin, yamlUtil);
                this.playerConfigsManager = new PlayerConfigsManager(plugin, yamlUtil);
                this.dataConfigManager = new DataConfigManager(plugin, yamlUtil);
        }

        /**
         * 初始化所有配置并加载变量信息。
         */
        public void configure() {
                yamlUtil.ensureDirectory("variables");
                yamlUtil.copyDefaults("variables", "variables");

                this.mainConfigManager.configure();
                this.playerConfigsManager.configure();
                configureVariables();
                this.dataConfigManager.configure();
        }

        /**
         * 解析所有变量配置并写入变量管理器。
         */
        public void configureVariables(){
                ArrayList<Variable> variables = new ArrayList<>();
                List<YamlConfiguration> variablesConfigs = getVariablesConfigs();

                for(YamlConfiguration config : variablesConfigs){
                        if(config.contains("variables")){
                                for(String key : config.getConfigurationSection("variables").getKeys(false)){
                                        String path = "variables."+key;
                                        VariableType variableType = VariableType.valueOf(config.getString(path+".variable_type"));
                                        ValueType valueType = ValueType.valueOf(config.getString(path+".value_type"));
                                        String initialValue = config.getString(path+".initial_value");

                                        List<String> possibleValues = new ArrayList<String>();
                                        if(config.contains(path+".possible_values")){
                                                possibleValues = config.getStringList(path+".possible_values");
                                        }
                                        Limitations limitations = new Limitations();
                                        if(config.contains(path+".limitations.min_value")){
                                                limitations.setMinValue(config.getDouble(path+".limitations.min_value"));
                                        }
                                        if(config.contains(path+".limitations.max_value")){
                                                limitations.setMaxValue(config.getDouble(path+".limitations.max_value"));
                                        }
                                        if(config.contains(path+".limitations.max_characters")){
                                                limitations.setMaxCharacters(config.getInt(path+".limitations.max_characters"));
                                        }
                                        if(config.contains(path+".limitations.max_decimals")){
                                                limitations.setMaxDecimals(config.getInt(path+".limitations.max_decimals"));
                                        }

                                        Variable variable = new Variable(key, variableType, valueType, initialValue, possibleValues, limitations);
                                        variables.add(variable);
                                }
                        }
                }
                plugin.getVariablesManager().setVariables(variables);
        }

        /**
         * 获取玩家配置管理器。
         *
         * @return 玩家配置管理器实例
         */
        public PlayerConfigsManager getPlayerConfigsManager() {
                return playerConfigsManager;
        }

        /**
         * 获取数据配置管理器。
         *
         * @return 数据配置管理器实例
         */
        public DataConfigManager getDataConfigManager() {
                return dataConfigManager;
        }

        /**
         * 获取主配置管理器。
         *
         * @return 主配置管理器实例
         */
        public MainConfigManager getMainConfigManager() {
                return mainConfigManager;
        }

        /**
         * 获取所有变量配置文件。
         *
         * @return 包含所有变量配置的列表
         */
        private List<YamlConfiguration> getVariablesConfigs() {
                List<YamlConfiguration> configs = new ArrayList<>();
                configs.add(mainConfigManager.getConfig());
                Map<String, YamlConfiguration> folderConfigs = yamlUtil.loadAllConfigsInFolder("variables");
                configs.addAll(folderConfigs.values());
                return configs;
        }

        /**
         * 重载配置并保存当前数据。
         */
        public void reloadConfigs(){
                mainConfigManager.reload();
                configureVariables();

                saveServerData();
                savePlayerData();
        }

        /**
         * 保存所有玩家数据到磁盘。
         */
        public void savePlayerData(){
                if(plugin.getMySQLConnection() == null){
                        playerConfigsManager.savePlayers();
                }
        }

        /**
         * 保存服务器变量数据到磁盘。
         */
        public void saveServerData(){
                dataConfigManager.saveData();
        }
}
