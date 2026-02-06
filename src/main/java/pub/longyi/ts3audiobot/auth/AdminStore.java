package pub.longyi.ts3audiobot.auth;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 AdminStore 相关功能。
 */


/**
 * AdminStore 相关功能。
 *
 * <p>职责：负责 AdminStore 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Slf4j
public final class AdminStore {
    private static final String JDBC_PREFIX = "jdbc:sqlite:";
    private static final String TABLE_ADMINS = "admins";
    private static final String SQL_CREATE_ADMINS = """
        CREATE TABLE IF NOT EXISTS admins (
            username TEXT PRIMARY KEY,
            salt TEXT NOT NULL,
            password_hash TEXT NOT NULL,
            created_at TEXT NOT NULL
        )
        """;
    private static final String SQL_COUNT_ADMINS = "SELECT COUNT(*) FROM admins";
    private static final String SQL_INSERT_ADMIN = """
        INSERT INTO admins(username, salt, password_hash, created_at)
        VALUES(?, ?, ?, ?)
        """;
    private static final String SQL_SELECT_ADMIN = """
        SELECT username, salt, password_hash
          FROM admins
         WHERE username = ?
        """;

    private final Path dbPath;

    /**
     * 创建 AdminStore 实例。
     * @param dbPath 参数 dbPath
     */
    public AdminStore(Path dbPath) {
        if (dbPath == null) {
            throw new IllegalArgumentException("dbPath must not be null");
        }
        this.dbPath = dbPath;
    }


    /**
     * 执行 initialize 操作。
     */
    public void initialize() {
        ensureParentDir();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute(SQL_CREATE_ADMINS);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to initialize admin store at " + dbPath, ex);
        }
    }


    /**
     * 执行 hasAdmin 操作。
     * @return 返回值
     */
    public boolean hasAdmin() {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(SQL_COUNT_ADMINS)) {
            if (!rs.next()) {
                return false;
            }
            return rs.getInt(1) > 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to query admin state", ex);
        }
    }


    /**
     * 执行 getAdmin 操作。
     * @param username 参数 username
     * @return 返回值
     */
    public AdminRecord getAdmin(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_SELECT_ADMIN)) {
            statement.setString(1, username);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new AdminRecord(
                    rs.getString("username"),
                    rs.getString("salt"),
                    rs.getString("password_hash")
                );
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load admin", ex);
        }
    }


    /**
     * 执行 createAdmin 操作。
     * @param username 参数 username
     * @param salt 参数 salt
     * @param hash 参数 hash
     */
    public void createAdmin(String username, String salt, String hash) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_INSERT_ADMIN)) {
            statement.setString(1, username);
            statement.setString(2, salt);
            statement.setString(3, hash);
            statement.setString(4, java.time.Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create admin", ex);
        }
    }


    /**
     * 执行 getDbPath 操作。
     * @return 返回值
     */
    public Path getDbPath() {
        return dbPath;
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(JDBC_PREFIX + dbPath.toAbsolutePath());
    }

    private void ensureParentDir() {
        Path parent = dbPath.getParent();
        if (parent == null || Files.isDirectory(parent)) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to create sqlite directory " + parent, ex);
        }
    }

    public record AdminRecord(String username, String salt, String hash) {
    }
}
