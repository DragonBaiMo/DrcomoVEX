package cn.drcomo.config;

import org.bukkit.configuration.file.YamlConfiguration;
import cn.drcomo.DrcomoVEX;
import cn.drcomo.managers.MessagesManager;
import cn.drcomo.tasks.DataSaveTask;
import cn.drcomo.corelib.config.YamlUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 负责读取与维护主配置文件 {@code config.yml} 的管理器。
 */
public class MainConfigManager {

        private DrcomoVEX plugin;
        private YamlUtil yamlUtil;

	private boolean updateNotify;
	private boolean isMySQL;
	private boolean silentCommandsHideErrors;

        /**
         * 使用指定插件与 YAML 工具初始化主配置管理器。
         *
         * @param plugin   主插件实例
         * @param yamlUtil 核心库提供的 YAML 工具
         */
        public MainConfigManager(DrcomoVEX plugin, YamlUtil yamlUtil) {
                this.plugin = plugin;
                this.yamlUtil = yamlUtil;
                this.yamlUtil.loadConfig("config");
                checkMessagesUpdate();
        }

        /**
         * 重新加载配置文件并应用设置。
         */
        public void reload(){
                yamlUtil.reloadConfig("config");
                configure();
        }

        /**
         * 根据配置内容初始化插件运行参数。
         */
        public void configure() {
                YamlConfiguration config = yamlUtil.getConfig("config");

                plugin.setMessagesManager(new MessagesManager(
                                config.getString("messages.prefix"),
                                plugin.getPlaceholderUtil()));

                DataSaveTask dataSaveTask = plugin.getDataSaveTask();
                if(dataSaveTask != null) {
                        dataSaveTask.end();
                }
                dataSaveTask = new DataSaveTask(plugin, plugin.getAsyncTaskManager());
                dataSaveTask.start(config.getInt("config.data_save_time"));
                plugin.setDataSaveTask(dataSaveTask);

		updateNotify = config.getBoolean("update_notify");
		isMySQL = config.getBoolean("config.mysql_database.enabled");
		silentCommandsHideErrors = config.getBoolean("config.silent_commands_hide_errors");
	}

        /**
         * 判断是否启用了 MySQL 存储。
         *
         * @return {@code true} 表示启用 MySQL
         */
        public boolean isMySQL(){
                return isMySQL;
        }

        /**
         * 是否提醒更新。
         *
         * @return {@code true} 表示启用版本更新提示
         */
        public boolean isUpdateNotify() {
                return updateNotify;
        }

        /**
         * 获取配置对象。
         *
         * @return {@link YamlConfiguration} 实例
         */
        public YamlConfiguration getConfig(){
                return yamlUtil.getConfig("config");
        }

        /**
         * 将内存中的配置保存到磁盘。
         */
        public void saveConfig(){
                yamlUtil.saveConfig("config");
        }

        /**
         * 检查并补全缺失的配置项。
         */
        public void checkMessagesUpdate(){
                Path pathConfig = Paths.get(plugin.getDataFolder().getPath(), "config.yml");
                try{
                        String text = new String(Files.readAllBytes(pathConfig));
                        if(!text.contains("update_notify:")){
                                getConfig().set("config.update_notify",true);
                                saveConfig();
                        }
                        if(!text.contains("verifyServerCertificate:")){
                                getConfig().set("config.mysql_database.pool.connectionTimeout",5000);
                                getConfig().set("config.mysql_database.advanced.verifyServerCertificate",false);
                                getConfig().set("config.mysql_database.advanced.useSSL",true);
                                getConfig().set("config.mysql_database.advanced.allowPublicKeyRetrieval",true);
                                saveConfig();
                        }
                        if(!text.contains("silent_commands_hide_errors:")){
                                getConfig().set("config.silent_commands_hide_errors",false);
                                saveConfig();
                        }
                        if(!text.contains("commandResetCorrectAll:")){
                                getConfig().set("messages.commandResetCorrectAll","&aVariable &7%variable% &areset for &eall players&a.");
                                saveConfig();
                        }
                        if(!text.contains("variableLimitationMaxCharactersError:")){
                                getConfig().set("messages.variableLimitationMaxCharactersError","&cVariable supports a maximum of &7%value% &ccharacters.");
                                saveConfig();
                        }
                        if(!text.contains("variableLimitationOutOfRangeMax:")){
                                getConfig().set("messages.variableLimitationOutOfRangeMax","&cVariable out of range. Max value is &7%value%");
                                getConfig().set("messages.variableLimitationOutOfRangeMin","&cVariable out of range. Min value is &7%value%");
                                saveConfig();
                        }
                        if(!text.contains("mysql_database:")){
                                getConfig().set("config.mysql_database.enabled", false);
                                getConfig().set("config.mysql_database.host", "localhost");
                                getConfig().set("config.mysql_database.port", 3306);
                                getConfig().set("config.mysql_database.username", "root");
                                getConfig().set("config.mysql_database.password", "root");
                                getConfig().set("config.mysql_database.database", "servervariables");
                                saveConfig();
                        }
                }catch(IOException e){
                        e.printStackTrace();
                }
        }

        /**
         * 是否隐藏静默指令的错误信息。
         *
         * @return {@code true} 表示隐藏错误
         */
        public boolean isSilentCommandsHideErrors() {
                return silentCommandsHideErrors;
        }
}
