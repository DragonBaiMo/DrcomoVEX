package cn.drcomo.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.configuration.file.YamlConfiguration;
import cn.drcomo.DrcomoVEX;
import cn.drcomo.model.ServerVariablesPlayer;
import cn.drcomo.model.ServerVariablesVariable;
import cn.drcomo.corelib.config.YamlUtil;

/**
 * 负责管理玩家数据配置文件的读写。
 */
public class PlayerConfigsManager {

        private DrcomoVEX plugin;
        private YamlUtil yamlUtil;

        /**
         * 创建玩家配置管理器。
         *
         * @param plugin   主插件实例
         * @param yamlUtil 核心库提供的 YAML 工具
         */
        public PlayerConfigsManager(DrcomoVEX plugin, YamlUtil yamlUtil) {
                this.plugin = plugin;
                this.yamlUtil = yamlUtil;
        }

        /**
         * 初始化玩家数据目录并加载玩家变量。
         */
        public void configure() {
                yamlUtil.ensureDirectory("players");
                loadPlayers();
        }

        /**
         * 从玩家配置文件加载所有玩家变量信息。
         */
        public void loadPlayers() {
                Map<UUID,ServerVariablesPlayer> players = new HashMap<>();
                Map<String, YamlConfiguration> configs = yamlUtil.loadAllConfigsInFolder("players");
                for(Map.Entry<String, YamlConfiguration> entry : configs.entrySet()){
                        String fileName = entry.getKey();
                        String uuidString = fileName.endsWith(".yml") ? fileName.substring(0, fileName.length()-4) : fileName;
                        YamlConfiguration playerFile = entry.getValue();
                        String name = playerFile.getString("name");
                        ArrayList<ServerVariablesVariable> variables = new ArrayList<>();
                        if(playerFile.contains("variables")){
                                for(String key : playerFile.getConfigurationSection("variables").getKeys(false)){
                                        variables.add(new ServerVariablesVariable(key, playerFile.getString("variables." + key)));
                                }
                        }
                        UUID uuid = UUID.fromString(uuidString);
                        ServerVariablesPlayer player = new ServerVariablesPlayer(uuid, name, variables);
                        players.put(uuid, player);
                }
                plugin.getPlayerVariablesManager().setPlayerVariables(players);
        }

        /**
         * 将单个玩家的数据保存到对应文件。
         *
         * @param player 玩家变量数据对象
         */
        public void savePlayer(ServerVariablesPlayer player){
                YamlConfiguration playerFile = yamlUtil.getConfig("players/" + player.getUuid());
                playerFile.set("name", player.getName());
                playerFile.set("variables", null);
                ArrayList<ServerVariablesVariable> variables = player.getVariables();
                for(ServerVariablesVariable v : variables){
                        playerFile.set("variables."+v.getVariableName(), v.getCurrentValue());
                }
                yamlUtil.saveConfig("players/" + player.getUuid());
        }

        /**
         * 保存所有已修改的玩家数据。
         */
        public void savePlayers() {
                Map<UUID, ServerVariablesPlayer> players = plugin.getPlayerVariablesManager().getPlayerVariables();
                Map<UUID, ServerVariablesPlayer> playersCopy = new HashMap<>(players);
                for(Map.Entry<UUID, ServerVariablesPlayer> entry : playersCopy.entrySet()){
                        ServerVariablesPlayer player = entry.getValue();
                        if(player.isModified()){
                                savePlayer(player);
                        }
                        player.setModified(false);
                }
        }
}
