-- 数据库迁移脚本：添加严格初始值模式支持
-- 版本：001
-- 作者：BaiMo
-- 说明：为 player_variables 和 server_variables 表添加 strict-initial-mode 相关字段

-- 为 player_variables 表添加严格模式字段
ALTER TABLE player_variables ADD COLUMN strict_initial_value TEXT DEFAULT NULL;
ALTER TABLE player_variables ADD COLUMN is_strict_computed TINYINT DEFAULT 0;
ALTER TABLE player_variables ADD COLUMN strict_computed_at BIGINT DEFAULT NULL;

-- 为 server_variables 表添加严格模式字段  
ALTER TABLE server_variables ADD COLUMN strict_initial_value TEXT DEFAULT NULL;
ALTER TABLE server_variables ADD COLUMN is_strict_computed TINYINT DEFAULT 0;
ALTER TABLE server_variables ADD COLUMN strict_computed_at BIGINT DEFAULT NULL;

-- 创建索引以提高查询性能
CREATE INDEX IF NOT EXISTS idx_player_variables_strict ON player_variables(player_uuid, variable_key, is_strict_computed);
CREATE INDEX IF NOT EXISTS idx_server_variables_strict ON server_variables(variable_key, is_strict_computed);

-- 字段说明注释：
-- strict_initial_value: 存储严格模式下首次计算的初始值
-- is_strict_computed: 标记是否已完成严格模式的首次计算 (0=未计算, 1=已计算)
-- strict_computed_at: 记录严格模式计算的时间戳(用于调试和统计)