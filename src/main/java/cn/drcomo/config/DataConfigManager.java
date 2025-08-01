package cn.drcomo.config;

import java.util.ArrayList;

import cn.drcomo.DrcomoVEX;
import org.bukkit.configuration.file.YamlConfiguration;
import cn.drcomo.managers.ServerVariablesManager;
import cn.drcomo.model.ServerVariablesVariable;
import cn.drcomo.corelib.config.YamlUtil;

/**
 * 管理服务器变量数据文件 {@code data.yml} 的加载与保存。
 */
public class DataConfigManager {

        private DrcomoVEX plugin;
        private YamlUtil yamlUtil;

        /**
         * 使用指定插件与 YAML 工具创建数据配置管理器。
         *
         * @param plugin   主插件实例
         * @param yamlUtil 核心库提供的 YAML 工具
         */
        public DataConfigManager(DrcomoVEX plugin, YamlUtil yamlUtil) {
                this.plugin = plugin;
                this.yamlUtil = yamlUtil;
                this.yamlUtil.loadConfig("data");
        }
	
        /**
         * 从数据文件加载变量到内存。
         */
        public void configure() {
                ServerVariablesManager serverVariablesManager = plugin.getServerVariablesManager();
                serverVariablesManager.clearVariables();
                YamlConfiguration dataFile = yamlUtil.getConfig("data");

                if(dataFile.contains("variables")) {
                        for(String key : dataFile.getConfigurationSection("variables").getKeys(false)){
                                serverVariablesManager.addVariable(key,dataFile.getString("variables."+key));
                        }
                }
        }

        /**
         * 将内存中的变量保存到数据文件。
         */
        public void saveData(){
                ServerVariablesManager serverVariablesManager = plugin.getServerVariablesManager();
                ArrayList<ServerVariablesVariable> variables = serverVariablesManager.getVariables();
                YamlConfiguration dataFile = yamlUtil.getConfig("data");
                dataFile.set("variables", null);
                for(ServerVariablesVariable v : variables){
                        String variableName = v.getVariableName();
                        dataFile.set("variables."+variableName,v.getCurrentValue());
                }
                yamlUtil.saveConfig("data");
        }
}
