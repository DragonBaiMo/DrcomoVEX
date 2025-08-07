package cn.drcomo.database;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.config.ConfigsManager;
import cn.drcomo.corelib.util.DebugUtil;
import cn.drcomo.corelib.database.SQLiteDB;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.Enumeration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Hikari数据库连接管理器
 * 
 * 统一管理数据库连接，支持 SQLite 和 MySQL。
 * 使用 HikariCP 连接池提供高性能的数据库访问。
 * 
 * @author BaiMo
 */
public class HikariConnection {
    
    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final ConfigsManager configsManager;
    
    private HikariDataSource dataSource;
    private SQLiteDB sqliteDB;
    private String databaseType;

    // SQL 语句
    // 初始化脚本列表，仅包含 schema.sql
    private static final List<String> INIT_SCRIPTS = Collections.singletonList("schema.sql");
    
    public HikariConnection(DrcomoVEX plugin, DebugUtil logger, ConfigsManager configsManager) {
        this.plugin = plugin;
        this.logger = logger;
        this.configsManager = configsManager;
    }
    
    /**
     * 初始化数据库连接
     */
    public void initialize() {
        logger.info("正在初始化数据库连接...");
        
        try {
            FileConfiguration config = configsManager.getMainConfig();
            databaseType = config.getString("database.type", "sqlite").toLowerCase();
            
            if ("sqlite".equals(databaseType)) {
                initializeSQLite();
            } else if ("mysql".equals(databaseType)) {
                initializeMySQL();
            } else {
                logger.error("不支持的数据库类型: " + databaseType);
                throw new IllegalArgumentException("不支持的数据库类型: " + databaseType);
            }
            
            // 初始化表结构
            initializeTables();
            
            logger.info("数据库连接初始化完成！类型: " + databaseType.toUpperCase());
        } catch (Exception e) {
            logger.error("数据库初始化失败！", e);
            throw new RuntimeException("数据库初始化失败", e);
        }
    }
    
    /**
     * 初始化 SQLite 数据库
     */
    private void initializeSQLite() {
        String dbFile = configsManager.getMainConfig().getString("database.file", "drcomovex.db");
        
        // 使用 DrcomoCoreLib 的 SQLiteDB
        sqliteDB = new SQLiteDB(plugin, dbFile, INIT_SCRIPTS);

        try {
            sqliteDB.connect();
            // 手动执行初始化脚本，确保表结构完整
            sqliteDB.initializeSchema();
            logger.info("SQLite 数据库连接成功: " + dbFile);
        } catch (Exception e) {
            logger.error("SQLite 数据库连接失败", e);
            throw new RuntimeException("SQLite 数据库连接失败", e);
        }
    }
    
    /**
     * 初始化 MySQL 数据库
     */
    private void initializeMySQL() {
        FileConfiguration config = configsManager.getMainConfig();
        
        HikariConfig hikariConfig = new HikariConfig();
        
        // 基本连接信息
        String host = config.getString("database.mysql.host", "localhost");
        int port = config.getInt("database.mysql.port", 3306);
        String database = config.getString("database.mysql.database", "drcomovex");
        String username = config.getString("database.mysql.username", "root");
        String password = config.getString("database.mysql.password", "password");
        boolean useSSL = config.getBoolean("database.mysql.useSSL", false);
        
        String jdbcUrl = String.format("jdbc:mysql://%s:%d/%s?useSSL=%s&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=utf8mb4",
                host, port, database, useSSL);
        
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
        
        // 连接池配置
        hikariConfig.setMinimumIdle(config.getInt("database.pool.minimum-idle", 2));
        hikariConfig.setMaximumPoolSize(config.getInt("database.pool.maximum-pool-size", 10));
        hikariConfig.setConnectionTimeout(config.getLong("database.pool.connection-timeout", 30000));
        hikariConfig.setIdleTimeout(config.getLong("database.pool.idle-timeout", 600000));
        hikariConfig.setMaxLifetime(config.getLong("database.pool.max-lifetime", 1800000));
        
        // 连接池名称
        hikariConfig.setPoolName("DrcomoVEX-Pool");
        
        // 连接检查
        hikariConfig.setConnectionTestQuery("SELECT 1");
        
        try {
            dataSource = new HikariDataSource(hikariConfig);
            logger.info("MySQL 数据库连接成功: " + host + ":" + port + "/" + database);
        } catch (Exception e) {
            logger.error("MySQL 数据库连接失败", e);
            throw new RuntimeException("MySQL 数据库连接失败", e);
        }
    }
    
    /**
     * 初始化表结构
     */
    private void initializeTables() {
        if ("sqlite".equals(databaseType)) {
            // SQLiteDB 已经在连接时初始化了表
            return;
        }
        
        // MySQL 需要手动初始化表
        try (Connection connection = getConnection()) {
            for (String script : INIT_SCRIPTS) {
                List<String> sqlList = loadSqlStatements(script);
                for (String sql : sqlList) {
                    // 将 SQLite 的 SQL 转换为 MySQL 兼容的格式
                    String mysqlSql = convertToMySQLSyntax(sql);
                    try (PreparedStatement stmt = connection.prepareStatement(mysqlSql)) {
                        stmt.executeUpdate();
                    }
                }
            }
            logger.debug("MySQL 表结构初始化完成");
        } catch (SQLException e) {
            logger.error("初始化 MySQL 表结构失败", e);
            throw new RuntimeException("初始化表结构失败", e);
        }
    }

    /**
     * 从脚本文件读取 SQL 语句
     */
    private List<String> loadSqlStatements(String script) {
        if (!script.endsWith(".sql")) {
            return Collections.singletonList(script);
        }

        try (InputStream in = plugin.getResource(script)) {
            if (in == null) {
                logger.error("找不到初始化脚本: " + script);
                return Collections.emptyList();
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Arrays.stream(content.split(";"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("读取初始化脚本失败: " + script, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 将 SQLite SQL 转换为 MySQL 兼容的格式
     */
    private String convertToMySQLSyntax(String sqliteSQL) {
        return sqliteSQL
                .replace("INTEGER PRIMARY KEY AUTOINCREMENT", "INT AUTO_INCREMENT PRIMARY KEY")
                .replace("BIGINT", "BIGINT")
                .replace("VARCHAR(36)", "VARCHAR(36)")
                .replace("VARCHAR(255)", "VARCHAR(255)")
                .replace("TEXT", "TEXT")
                .replace("IF NOT EXISTS", "IF NOT EXISTS");
    }
    
    /**
     * 获取数据库连接
     */
    public Connection getConnection() throws SQLException {
        if ("mysql".equals(databaseType)) {
            return dataSource.getConnection();
        }
        throw new UnsupportedOperationException("SQLite 不提供直接连接获取，请使用异步API");
    }
    
    /**
     * 异步执行查询（单条结果）
     */
    public CompletableFuture<String> queryValueAsync(String sql, Object... params) {
        if ("sqlite".equals(databaseType)) {
            return sqliteDB.queryOneAsync(sql, rs -> rs.getString(1), params);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                
                // 设置参数
                for (int i = 0; i < params.length; i++) {
                    stmt.setObject(i + 1, params[i]);
                }
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                }
                
                return null;
            } catch (SQLException e) {
                logger.error("查询数据库失败: " + sql, e);
                throw new RuntimeException("查询数据库失败", e);
            }
        });
    }
    
    /**
     * 异步执行更新操作
     */
    public CompletableFuture<Integer> executeUpdateAsync(String sql, Object... params) {
        if ("sqlite".equals(databaseType)) {
            return sqliteDB.executeUpdateAsync(sql, params);
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                
                // 设置参数
                for (int i = 0; i < params.length; i++) {
                    stmt.setObject(i + 1, params[i]);
                }
                
                return stmt.executeUpdate();
                
            } catch (SQLException e) {
                logger.error("执行数据库更新失败: " + sql, e);
                throw new RuntimeException("执行数据库更新失败", e);
            }
        });
    }
    
    /**
     * 异步执行 UPSERT 操作
     */
    public CompletableFuture<Void> upsertAsync(String table, String keyColumn, String valueColumn, 
                                               Object keyValue, Object valueValue) {
        return CompletableFuture.runAsync(() -> {
            if ("sqlite".equals(databaseType)) {
                String sql = String.format(
                    "INSERT OR REPLACE INTO %s (%s, %s, updated_at) VALUES (?, ?, ?)",
                    table, keyColumn, valueColumn
                );
                try {
                    sqliteDB.executeUpdate(sql, keyValue, valueValue, System.currentTimeMillis());
                } catch (Exception e) {
                    logger.error("SQLite UPSERT 失败", e);
                    throw new RuntimeException("UPSERT 操作失败", e);
                }
            } else {
                String sql = String.format(
                    "INSERT INTO %s (%s, %s, created_at, updated_at) VALUES (?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE %s = VALUES(%s), updated_at = VALUES(updated_at)",
                    table, keyColumn, valueColumn, valueColumn, valueColumn
                );
                
                long now = System.currentTimeMillis();
                try (Connection conn = getConnection();
                     PreparedStatement stmt = conn.prepareStatement(sql)) {
                    
                    stmt.setObject(1, keyValue);
                    stmt.setObject(2, valueValue);
                    stmt.setLong(3, now);
                    stmt.setLong(4, now);
                    
                    stmt.executeUpdate();
                } catch (SQLException e) {
                    logger.error("MySQL UPSERT 失败", e);
                    throw new RuntimeException("UPSERT 操作失败", e);
                }
            }
        });
    }
    
    /**
     * 异步删除操作
     */
    public CompletableFuture<Integer> deleteAsync(String table, String whereClause, Object... params) {
        String sql = "DELETE FROM " + table + " WHERE " + whereClause;
        return executeUpdateAsync(sql, params);
    }
    
    /**
     * 检查连接是否有效
     */
    public boolean isConnectionValid() {
        if ("sqlite".equals(databaseType)) {
            return true;
        }

        try (Connection conn = getConnection()) {
            return conn != null && !conn.isClosed() && conn.isValid(5);
        } catch (SQLException e) {
            logger.error("数据库连接检查失败", e);
            return false;
        }
    }
    
    /**
     * 获取数据库类型
     */
    public String getDatabaseType() {
        return databaseType;
    }
    
    /**
     * 获取连接池统计信息
     */
    public String getPoolStats() {
        if ("mysql".equals(databaseType) && dataSource != null) {
            return String.format("MySQL连接池 - 活跃: %d, 空闲: %d, 等待: %d, 总数: %d",
                    dataSource.getHikariPoolMXBean().getActiveConnections(),
                    dataSource.getHikariPoolMXBean().getIdleConnections(),
                    dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection(),
                    dataSource.getHikariPoolMXBean().getTotalConnections());
        } else if ("sqlite".equals(databaseType)) {
            return "SQLite - 单文件数据库";
        }
        return "未知数据库类型";
    }
    
    /**
     * 关闭数据库连接
     */
    public void close() {
        logger.info("正在关闭数据库连接...");
        
        // 异步关闭避免阻塞主线程
        CompletableFuture.runAsync(() -> {
            try {
                if (sqliteDB != null) {
                    sqliteDB.disconnect();
                    logger.info("SQLite 数据库连接已关闭");
                }

                if (dataSource != null && !dataSource.isClosed()) {
                    dataSource.close();
                    logger.info("MySQL 连接池已关闭");
                }

                // 注销当前插件 ClassLoader 注册的 JDBC 驱动，防止热重载导致的 ZipFile closed
                ClassLoader cl = this.getClass().getClassLoader();
                Enumeration<Driver> drivers = DriverManager.getDrivers();
                while (drivers.hasMoreElements()) {
                    Driver driver = drivers.nextElement();
                    if (driver.getClass().getClassLoader() == cl) {
                        try {
                            DriverManager.deregisterDriver(driver);
                            logger.debug("已注销 JDBC 驱动: " + driver);
                        } catch (SQLException e) {
                            logger.error("注销 JDBC 驱动失败: " + driver, e);
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("关闭数据库连接时发生异常", e);
            }
        }).exceptionally(throwable -> {
            logger.error("异步关闭数据库连接失败", throwable);
            return null;
        });
    }
}