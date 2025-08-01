package cn.drcomo.config;

import java.util.ArrayList;

import cn.drcomo.DrcomoVEX;
import org.bukkit.configuration.file.FileConfiguration;
import cn.drcomo.managers.ServerVariablesManager;
import cn.drcomo.model.ServerVariablesVariable;

public class DataConfigManager {

	private DrcomoVEX plugin;
	private CustomConfig configFile;
	
	public DataConfigManager(DrcomoVEX plugin) {
		this.plugin = plugin;
		this.configFile = new CustomConfig("data.yml",plugin,null);
		configFile.registerConfig();
	}
	
	public void configure() {
		ServerVariablesManager serverVariablesManager = plugin.getServerVariablesManager();
		serverVariablesManager.clearVariables();
		FileConfiguration dataFile = configFile.getConfig();

		if(dataFile.contains("variables")) {
			for(String key : dataFile.getConfigurationSection("variables").getKeys(false)){
				serverVariablesManager.addVariable(key,dataFile.getString("variables."+key));
			}
		}
	}

	public void saveData(){
		ServerVariablesManager serverVariablesManager = plugin.getServerVariablesManager();
		ArrayList<ServerVariablesVariable> variables = serverVariablesManager.getVariables();
		FileConfiguration dataFile = configFile.getConfig();
		dataFile.set("variables", null);
		for(ServerVariablesVariable v : variables){
			String variableName = v.getVariableName();
			dataFile.set("variables."+variableName,v.getCurrentValue());
		}
		configFile.saveConfig();
	}
}
