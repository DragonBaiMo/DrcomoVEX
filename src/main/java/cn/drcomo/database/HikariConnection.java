package cn.drcomo.database;

import cn.drcomo.DrcomoVEX;
import cn.drcomo.config.ConfigsManager;
import cn.drcomo.corelib.async.AsyncTaskManager;
import cn.drcomo.corelib.database.SQLiteDB;
import cn.drcomo.corelib.util.DebugUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Hikari 数据库连接管理器
 *
 * 统一管理数据库连接，支持 SQLite 与 MySQL。
 * - SQLite 通过 CoreLib 的 SQLiteDB 提供高性能访问
 * - MySQL 使用 HikariCP 连接池
 *
 * 作者: BaiMo
 */
public class HikariConnection {

    private final DrcomoVEX plugin;
    private final DebugUtil logger;
    private final ConfigsManager configsManager;
    private final AsyncTaskManager asyncTaskManager;

    private HikariDataSource dataSource;
    private SQLiteDB sqliteDB;
    private String databaseType;
    private ExecutorService dbExecutor;

    // 初始化脚本列表，仅包含 schema.sql
    private static final List<String> INIT_SCRIPTS = Collections.singletonList("schema.sql");

    public HikariConnection(DrcomoVEX plugin, DebugUtil logger, ConfigsManager configsManager,
                            AsyncTaskManager asyncTaskManager) {
        this.plugin = plugin;
        this.logger = logger;
        this.configsManager = configsManager;
        this.asyncTaskManager = asyncTaskManager;
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

            // 初始化表结构（MySQL 需要）
            initializeTables();

            // 独立数据库执行线程池，避免与业务线程互相阻塞
            int dbThreads = config.getInt("database.pool.db-executor-threads", 8);
            dbExecutor = Executors.newFixedThreadPool(Math.max(2, dbThreads), r -> {
                Thread t = new Thread(r, "DrcomoVEX-DBExecutor");
                t.setDaemon(true);
                return t;
            });

            logger.info("数据库连接初始化完成！类型: " + databaseType.toUpperCase());
        } catch (Exception e) {
            logger.error("数据库初始化失败", e);
            throw new RuntimeException("数据库初始化失败", e);
        }
    }

    /**
     * 初始化 SQLite 数据库
     */
    private void initializeSQLite() {
        String dbFile = configsManager.getMainConfig().getString("database.file", "drcomovex.db");

        // 重载时等待文件锁释放
        if (!waitForSQLiteFileUnlock(dbFile)) {
            logger.warn("SQLite 文件仍被锁定，但继续尝试连接: " + dbFile);
        }

        // 使用 CoreLib 提供的 SQLiteDB
        sqliteDB = new SQLiteDB(plugin, dbFile, INIT_SCRIPTS);
        try {
            sqliteDB.connect();
            // 手动执行初始化脚本，确保表结构完整
            sqliteDB.initializeSchema();

            // 连接已建立，立即记录成功日志
            logger.info("SQLite 数据库连接成功: " + dbFile);

            // 在独立连接中后台设置 PRAGMA，避免占用 SQLiteDB 的执行线程
            applySQLitePragmasInBackground(dbFile);
        } catch (Exception e) {
            logger.error("SQLite 数据库连接失败", e);
            throw new RuntimeException("SQLite 数据库连接失败", e);
        }
    }

    /**
     * 等待 SQLite 文件锁释放（重载时使用）
     */
    private boolean waitForSQLiteFileUnlock(String dbFile) {
        java.io.File f = new java.io.File(dbFile);
        if (!f.isAbsolute()) {
            f = new java.io.File(plugin.getDataFolder(), dbFile);
        }
        
        // 如果文件不存在，直接返回 true
        if (!f.exists()) {
            return true;
        }
        
        final String jdbcUrl = "jdbc:sqlite:" + f.getAbsolutePath();
        int maxAttempts = 10;
        long waitTimeMs = 200;
        
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (Connection testConn = DriverManager.getConnection(jdbcUrl)) {
                // 尝试执行一个简单查询来测试文件是否可用
                try (PreparedStatement stmt = testConn.prepareStatement("SELECT 1")) {
                    stmt.setQueryTimeout(1);
                    stmt.executeQuery();
                }
                logger.debug("SQLite 文件锁检查通过，尝试次数: " + attempt);
                return true;
            } catch (SQLException e) {
                if (attempt < maxAttempts) {
                    logger.debug("SQLite 文件仍被锁定，等待释放... (尝试 " + attempt + "/" + maxAttempts + ")");
                    try {
                        Thread.sleep(waitTimeMs);
                        waitTimeMs = Math.min(waitTimeMs * 2, 2000); // 指数退避，最大2秒
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.warn("等待 SQLite 文件解锁被中断");
                        return false;
                    }
                } else {
                    logger.warn("等待 SQLite 文件解锁超时: " + e.getMessage());
                    return false;
                }
            }
        }
        
        return false;
    }

    /**
     * 在独立 JDBC 连接里后台设置关键 PRAGMA，避免阻塞 SQLiteDB 的执行队列。
     */
    private void applySQLitePragmasInBackground(String dbFile) {
        try {
            // 计算绝对路径
            java.io.File f = new java.io.File(dbFile);
            if (!f.isAbsolute()) {
                f = new java.io.File(plugin.getDataFolder(), dbFile);
            }
            final String jdbcUrl = "jdbc:sqlite:" + f.getAbsolutePath();

            asyncTaskManager.runAsync(() -> {
                try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                    // journal_mode=WAL（读取返回值）
                    try (PreparedStatement ps = conn.prepareStatement("PRAGMA journal_mode=WAL")) {
                        try { ps.setQueryTimeout(2); } catch (Throwable ignore) {}
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                logger.debug("SQLite journal_mode 设置为: " + rs.getString(1));
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("设置 PRAGMA journal_mode=WAL 失败: " + (e.getMessage() != null ? e.getMessage() : String.valueOf(e)));
                    }

                    // busy_timeout
                    try (PreparedStatement ps = conn.prepareStatement("PRAGMA busy_timeout=10000")) {
                        try { ps.setQueryTimeout(2); } catch (Throwable ignore) {}
                        ps.execute();
                    } catch (Exception e) {
                        logger.warn("设置 PRAGMA busy_timeout 失败: " + (e.getMessage() != null ? e.getMessage() : String.valueOf(e)));
                    }

                    // synchronous=NORMAL
                    try (PreparedStatement ps = conn.prepareStatement("PRAGMA synchronous=NORMAL")) {
                        try { ps.setQueryTimeout(2); } catch (Throwable ignore) {}
                        ps.execute();
                    } catch (Exception e) {
                        logger.warn("设置 PRAGMA synchronous 失败: " + (e.getMessage() != null ? e.getMessage() : String.valueOf(e)));
                    }
                } catch (Exception e) {
                    logger.warn("后台设置 SQLite PRAGMA 失败: " + (e.getMessage() != null ? e.getMessage() : String.valueOf(e)));
                }
            });
        } catch (Exception e) {
            logger.debug("跳过后台 PRAGMA 设置: " + e.getMessage());
        }
    }

    /**
     * 初始化 MySQL 数据库
     */
    private void initializeMySQL() {
        FileConfiguration config = configsManager.getMainConfig();

        HikariConfig hikariConfig = new HikariConfig();

        // 基本连接信息
        String host = config.getString("database.mysql.host", "127.0.0.1");
        int port = config.getInt("database.mysql.port", 3306);
        String database = config.getString("database.mysql.database", "drcomovex");
        String username = config.getString("database.mysql.username", "root");
        String password = config.getString("database.mysql.password", "password");
        boolean useSSL = config.getBoolean("database.mysql.useSSL", false);

        String jdbcUrl = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=%s&allowPublicKeyRetrieval=true&connectTimeout=15000&socketTimeout=15000&tcpKeepAlive=true&rewriteBatchedStatements=true",
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
        hikariConfig.setPoolName("DrcomoVEX-Pool");
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
     * 初始化表结构（仅对 MySQL 有效；SQLite 已在 connect 时处理）
     */
    private void initializeTables() {
        if ("sqlite".equals(databaseType)) {
            return; // SQLiteDB 已自处理
        }

        try (Connection connection = getConnection()) {
            for (String script : INIT_SCRIPTS) {
                List<String> sqlList = loadSqlStatements(script);
                for (String sql : sqlList) {
                    String mysqlSql = convertToMySQLSyntax(sql);
                    try (PreparedStatement stmt = connection.prepareStatement(mysqlSql)) {
                        stmt.executeUpdate();
                    } catch (SQLException e) {
                        // 忽略重复索引错误（1061），便于多次初始化
                        String upper = mysqlSql.toUpperCase();
                        boolean isIndexStatement = upper.startsWith("CREATE INDEX") || upper.contains(" ADD INDEX ") || (upper.startsWith("ALTER TABLE") && upper.contains(" ADD INDEX "));
                        if (isIndexStatement && (e.getErrorCode() == 1061 || (e.getMessage() != null && e.getMessage().toLowerCase().contains("duplicate key name")))) {
                            logger.debug("索引已存在，跳过: " + mysqlSql);
                            continue;
                        }
                        throw e;
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
     * 将 SQLite SQL 转换为 MySQL 兼容语法（最小化转换）
     */
    private String convertToMySQLSyntax(String sqliteSQL) {
        String sql = sqliteSQL.trim();
        // 兼容 CREATE INDEX IF NOT EXISTS
        sql = sql.replaceAll("(?i)CREATE\\s+INDEX\\s+IF\\s+NOT\\s+EXISTS", "CREATE INDEX");
        // 类型转换
        sql = sql.replace("INTEGER PRIMARY KEY AUTOINCREMENT", "INT AUTO_INCREMENT PRIMARY KEY");
        return sql;
    }

    /**
     * 获取数据库连接（仅 MySQL 有效）
     */
    public Connection getConnection() throws SQLException {
        if ("mysql".equals(databaseType)) {
            if (dataSource == null) {
                throw new SQLException("MySQL 连接池未初始化或已关闭，无法获取连接");
            }
            return dataSource.getConnection();
        }
        throw new UnsupportedOperationException("SQLite 不提供直接连接获取，请使用异步 API");
    }

    /**
     * 异步查询单值
     */
    public CompletableFuture<String> queryValueAsync(String sql, Object... params) {
        if ("sqlite".equals(databaseType)) {
            return sqliteDB.queryOneAsync(sql, rs -> rs.getString(1), params);
        }

        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                try { conn.setNetworkTimeout(dbExecutor, 15000); } catch (Throwable ignore) {}
                try { stmt.setQueryTimeout(15); } catch (Throwable ignore) {}
                for (int i = 0; i < params.length; i++) stmt.setObject(i + 1, params[i]);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
                return null;
            } catch (SQLException e) {
                logger.error("查询数据库失败: " + sql, e);
                throw new RuntimeException("查询数据库失败", e);
            }
        }, dbExecutor);
    }

    /**
     * 异步按变量键查询玩家记录，返回 [player_uuid, variable_key, value]
     */
    public CompletableFuture<List<String[]>> queryPlayerVariablesByKeyAsync(String variableKeyOrLike, boolean likePattern) {
        String sql;
        Object[] params;
        if (likePattern) {
            // 统一使用 ESCAPE '\\'，确保 '_' 与 '%' 可被转义
            sql = "SELECT player_uuid, variable_key, value FROM player_variables WHERE variable_key LIKE ? ESCAPE '\\'";
            params = new Object[]{ variableKeyOrLike };
        } else {
            sql = "SELECT player_uuid, variable_key, value FROM player_variables WHERE variable_key = ?";
            params = new Object[]{ variableKeyOrLike };
        }

        if ("sqlite".equals(databaseType)) {
            return sqliteDB.queryListAsync(sql, rs -> new String[]{ rs.getString(1), rs.getString(2), rs.getString(3) }, params);
        }

        return asyncTaskManager.supplyAsync(() -> {
            List<String[]> results = new ArrayList<>();
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                try { stmt.setQueryTimeout(15); } catch (Throwable ignore) {}
                for (int i = 0; i < params.length; i++) stmt.setObject(i + 1, params[i]);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        results.add(new String[]{ rs.getString(1), rs.getString(2), rs.getString(3) });
                    }
                }
            } catch (SQLException e) {
                logger.error("查询玩家变量列表失败: " + sql, e);
                throw new RuntimeException("查询玩家变量列表失败", e);
            }
            return results;
        });
    }

    /**
     * 异步执行更新
     */
    public CompletableFuture<Integer> executeUpdateAsync(String sql, Object... params) {
        if ("sqlite".equals(databaseType)) {
            return sqliteDB.executeUpdateAsync(sql, params);
        }

        return asyncTaskManager.supplyAsync(() -> {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) stmt.setObject(i + 1, params[i]);
                return stmt.executeUpdate();
            } catch (SQLException e) {
                logger.error("执行数据库更新失败: " + sql, e);
                throw new RuntimeException("执行数据库更新失败", e);
            }
        });
    }

    /**
     * 异步 UPSERT 操作
     */
    public CompletableFuture<Void> upsertAsync(String table, String keyColumn, String valueColumn,
                                               Object keyValue, Object valueValue) {
        if ("sqlite".equals(databaseType)) {
            String sql = "INSERT OR REPLACE INTO " + table + " (" + keyColumn + ", " + valueColumn + ", created_at, updated_at) VALUES (?, ?, ?, ?)";
            long now = System.currentTimeMillis();
            return executeUpdateAsync(sql, keyValue, valueValue, now, now).thenApply(v -> null);
        }

        return asyncTaskManager.runAsync(() -> {
            String sql = String.format(
                    "INSERT INTO %s (%s, %s, created_at, updated_at) VALUES (?, ?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE %s = VALUES(%s), updated_at = VALUES(updated_at)",
                    table, keyColumn, valueColumn, valueColumn, valueColumn, valueColumn
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
        });
    }

    /**
     * 异步删除
     */
    public CompletableFuture<Integer> deleteAsync(String table, String whereClause, Object... params) {
        String sql = "DELETE FROM " + table + " WHERE " + whereClause;
        return executeUpdateAsync(sql, params);
    }

    /**
     * 检查连接是否有效
     */
    public boolean isConnectionValid() {
        if ("sqlite".equals(databaseType)) return true;
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
     * 强制刷新 SQLite 数据到磁盘（轻量检查点）
     */
    public CompletableFuture<Void> flushDatabase() {
        return flushDatabase(false);
    }

    /**
     * 强制刷新 SQLite 数据到磁盘
     * @param isShutdown 是否为关闭时调用（关闭时使用更简化的操作）
     */
    public CompletableFuture<Void> flushDatabase(boolean isShutdown) {
        if ("sqlite".equals(databaseType) && sqliteDB != null) {
            if (isShutdown) {
                // 关闭时只执行简单的检查点，跳过耗时的优化
                logger.debug("触发 SQLite 关闭前简化检查点...");
                return queryValueAsync("PRAGMA wal_checkpoint(TRUNCATE)")
                        .orTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                        .thenRun(() -> logger.debug("SQLite 关闭前检查点完成"))
                        .exceptionally(throwable -> {
                            logger.debug("SQLite 关闭前检查点失败(继续): " + (throwable.getMessage() != null ? throwable.getMessage() : "未知错误"));
                            return null;
                        });
            } else {
                // 正常运行时执行完整的检查点和优化
                logger.debug("触发 SQLite PASSIVE 检查点...");
                return queryValueAsync("PRAGMA wal_checkpoint(PASSIVE)")
                        .thenCompose(v -> queryValueAsync("PRAGMA optimize"))
                        .thenRun(() -> logger.debug("SQLite 检查点与优化完成"))
                        .exceptionally(throwable -> {
                            logger.warn("SQLite 检查点/优化失败(忽略继续): " + (throwable.getMessage() != null ? throwable.getMessage() : "未知错误"));
                            return null;
                        });
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 关闭数据库连接（同步）
     */
    public void close() {
        logger.info("正在关闭数据库连接...");
        try {
            if (sqliteDB != null) {
                // 同步断开，确保 SQLite 句柄及时释放，避免热重载竞争
                sqliteDB.disconnect();
                logger.info("SQLite 数据库连接已关闭");
            }

            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
                logger.info("MySQL 连接池已关闭");
            }

            // 注销当前插件 ClassLoader 注册的 JDBC 驱动，防止热重载导致 zip file closed
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
    }
}
