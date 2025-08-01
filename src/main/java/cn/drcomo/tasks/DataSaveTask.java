package cn.drcomo.tasks;

import org.bukkit.scheduler.BukkitRunnable;
import cn.drcomo.DrcomoVEX;

public class DataSaveTask {

	private DrcomoVEX plugin;
	private boolean end;
	public DataSaveTask(DrcomoVEX plugin) {
		this.plugin = plugin;
		this.end = false;
	}
	
	public void end() {
		end = true;
	}
	
	public void start(int minutes) {
		long ticks = minutes*60*20;
		
		new BukkitRunnable() {
			@Override
			public void run() {
				if(end) {
					this.cancel();
				}else {
					execute();
				}
			}
			
		}.runTaskTimerAsynchronously(plugin, 0L, ticks);
	}
	
	public void execute() {
		plugin.getConfigsManager().saveServerData();
		plugin.getConfigsManager().savePlayerData();
	}
}
