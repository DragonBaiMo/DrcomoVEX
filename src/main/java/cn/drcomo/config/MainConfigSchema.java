package cn.drcomo.config;

import cn.drcomo.corelib.config.ConfigSchema;
import cn.drcomo.corelib.config.ConfigValidator;
import java.util.Set;

/**
 * 主配置结构校验规则
 * 按照 config.yml 的约定对关键路径进行类型与取值校验。
 */
public class MainConfigSchema implements ConfigSchema {

	@Override
	public void configure(ConfigValidator validator) {
		// 数据库类型: sqlite 或 mysql
		validator.validateString("database.type")
				.required()
				.custom(v -> {
					String s = String.valueOf(v).trim().toLowerCase();
					return s.equals("sqlite") || s.equals("mysql");
				}, "database.type 只能为 sqlite 或 mysql");

		// SQLite 文件
		validator.validateString("database.file");

		// MySQL 连接信息
		validator.validateString("database.mysql.host");
		validator.validateNumber("database.mysql.port")
				.custom(n -> {
					int p = ((Number) n).intValue();
					return p > 0 && p <= 65535;
				}, "database.mysql.port 必须在 1-65535 范围内");
		validator.validateString("database.mysql.database");
		validator.validateString("database.mysql.username");
		validator.validateString("database.mysql.password");

		// 连接池参数
		validator.validateNumber("database.pool.minimum-idle");
		validator.validateNumber("database.pool.maximum-pool-size");
		validator.validateNumber("database.pool.connection-timeout");
		validator.validateNumber("database.pool.idle-timeout");
		validator.validateNumber("database.pool.max-lifetime");

		// 数据保存配置
		validator.validateNumber("data.save-interval-minutes")
				.custom(n -> ((Number) n).intValue() > 0, "data.save-interval-minutes 必须大于 0");

		// 周期配置
		validator.validateNumber("cycle.check-interval-seconds")
				.custom(n -> ((Number) n).intValue() >= 1, "cycle.check-interval-seconds 不能小于 1");
		validator.validateString("cycle.timezone");

		// 全局变量数据库拉取同步配置
		validator.validateNumber("settings.global-db-sync.poll-interval-millis")
				.custom(n -> ((Number) n).intValue() >= 250, "settings.global-db-sync.poll-interval-millis 不能小于 250");
		validator.validateNumber("settings.global-db-sync.query-timeout-millis")
				.custom(n -> ((Number) n).longValue() >= 1000L, "settings.global-db-sync.query-timeout-millis 不能小于 1000");

		// MySQL 事件表跨服同步配置
		validator.validateString("settings.cross-server-sync.server-id");
		validator.validateNumber("settings.cross-server-sync.poll-interval-ms")
				.custom(n -> ((Number) n).intValue() >= 100, "settings.cross-server-sync.poll-interval-ms 不能小于 100");
		validator.validateNumber("settings.cross-server-sync.batch-size")
				.custom(n -> ((Number) n).intValue() >= 50, "settings.cross-server-sync.batch-size 不能小于 50");
		validator.validateNumber("settings.cross-server-sync.retention-days")
				.custom(n -> ((Number) n).intValue() >= 1, "settings.cross-server-sync.retention-days 不能小于 1");
		validator.validateNumber("settings.cross-server-sync.cleanup-interval-seconds")
				.custom(n -> ((Number) n).intValue() >= 60, "settings.cross-server-sync.cleanup-interval-seconds 不能小于 60");
		validator.validateNumber("settings.cross-server-sync.cleanup-safety-margin")
				.custom(n -> ((Number) n).intValue() >= 100, "settings.cross-server-sync.cleanup-safety-margin 不能小于 100");
		validator.validateNumber("settings.cross-server-sync.consumer-stale-days")
				.custom(n -> ((Number) n).intValue() >= 1, "settings.cross-server-sync.consumer-stale-days 不能小于 1");

		// 日志级别
		validator.validateString("debug.level")
				.custom(v -> {
					String s = String.valueOf(v).trim().toUpperCase();
					return Set.of("DEBUG","INFO","WARN","ERROR").contains(s);
				}, "debug.level 必须为 DEBUG/INFO/WARN/ERROR 之一");

		// Redis 同步配置
		validator.validateString("settings.redis-sync.host");
		validator.validateNumber("settings.redis-sync.port")
				.custom(n -> {
					int p = ((Number) n).intValue();
					return p > 0 && p <= 65535;
				}, "settings.redis-sync.port 必须在 1-65535 范围内");
		validator.validateString("settings.redis-sync.server-id");
		validator.validateNumber("settings.redis-sync.heartbeat-interval-seconds")
				.custom(n -> ((Number) n).intValue() >= 5, "settings.redis-sync.heartbeat-interval-seconds 不能小于 5");
		validator.validateNumber("settings.redis-sync.online-ttl-seconds")
				.custom(n -> ((Number) n).intValue() >= 10, "settings.redis-sync.online-ttl-seconds 不能小于 10");
		validator.validateNumber("settings.redis-sync.request-timeout-millis")
				.custom(n -> ((Number) n).intValue() >= 500, "settings.redis-sync.request-timeout-millis 不能小于 500");
		validator.validateNumber("settings.redis-sync.server-id-claim-ttl-seconds")
				.custom(n -> ((Number) n).intValue() >= 30, "settings.redis-sync.server-id-claim-ttl-seconds 不能小于 30");
	}
}


