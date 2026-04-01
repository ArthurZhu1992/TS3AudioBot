package pub.longyi.ts3audiobot.search;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SearchAuthStore 相关功能。
 *
 * <p>职责：保存搜索登录态与登录会话数据。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Slf4j
public final class SearchAuthStore {
    private static final String JDBC_PREFIX = "jdbc:sqlite:";
    private static final String TABLE_AUTH = "search_auth";
    private static final String TABLE_LOGIN = "search_login_session";

    private static final String SQL_CREATE_AUTH = """
        CREATE TABLE IF NOT EXISTS search_auth (
            source TEXT NOT NULL,
            scope_type TEXT NOT NULL,
            bot_id TEXT NOT NULL,
            cookie TEXT NOT NULL,
            token TEXT,
            extra_json TEXT,
            expires_at TEXT,
            updated_at TEXT NOT NULL,
            PRIMARY KEY (source, scope_type, bot_id)
        )
        """;
    private static final String SQL_CREATE_LOGIN = """
        CREATE TABLE IF NOT EXISTS search_login_session (
            session_id TEXT PRIMARY KEY,
            source TEXT NOT NULL,
            scope_type TEXT NOT NULL,
            bot_id TEXT NOT NULL,
            status TEXT NOT NULL,
            payload TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        )
        """;

    private static final String SQL_UPSERT_AUTH = """
        INSERT INTO search_auth(source, scope_type, bot_id, cookie, token, extra_json, expires_at, updated_at)
        VALUES(?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(source, scope_type, bot_id) DO UPDATE SET
            cookie = excluded.cookie,
            token = excluded.token,
            extra_json = excluded.extra_json,
            expires_at = excluded.expires_at,
            updated_at = excluded.updated_at
        """;
    private static final String SQL_SELECT_AUTH = """
        SELECT source, scope_type, bot_id, cookie, token, extra_json, expires_at, updated_at
          FROM search_auth
         WHERE source = ? AND scope_type = ? AND bot_id = ?
        """;
    private static final String SQL_SELECT_AUTH_BY_SOURCE = """
        SELECT source, scope_type, bot_id, cookie, token, extra_json, expires_at, updated_at
          FROM search_auth
         WHERE source = ?
        """;
    private static final String SQL_DELETE_AUTH = """
        DELETE FROM search_auth WHERE source = ? AND scope_type = ? AND bot_id = ?
        """;

    private static final String SQL_UPSERT_LOGIN = """
        INSERT INTO search_login_session(session_id, source, scope_type, bot_id, status, payload, created_at, updated_at)
        VALUES(?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(session_id) DO UPDATE SET
            status = excluded.status,
            payload = excluded.payload,
            updated_at = excluded.updated_at
        """;
    private static final String SQL_SELECT_LOGIN = """
        SELECT session_id, source, scope_type, bot_id, status, payload, created_at, updated_at
          FROM search_login_session
         WHERE session_id = ?
        """;
    private static final String SQL_UPDATE_LOGIN_STATUS = """
        UPDATE search_login_session SET status = ?, updated_at = ? WHERE session_id = ?
        """;
    private static final String SQL_DELETE_LOGIN = "DELETE FROM search_login_session WHERE session_id = ?";

    private final Path dbPath;

    public SearchAuthStore(Path dbPath) {
        if (dbPath == null) {
            throw new IllegalArgumentException("dbPath must not be null");
        }
        this.dbPath = dbPath;
    }

    public void initialize() {
        ensureParentDir();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute(SQL_CREATE_AUTH);
            statement.execute(SQL_CREATE_LOGIN);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to initialize search auth store at " + dbPath, ex);
        }
    }

    public AuthRecord getAuth(String source, String scopeType, String botId) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_SELECT_AUTH)) {
            statement.setString(1, source);
            statement.setString(2, scopeType);
            statement.setString(3, normalizeBotId(botId));
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new AuthRecord(
                    rs.getString("source"),
                    rs.getString("scope_type"),
                    rs.getString("bot_id"),
                    rs.getString("cookie"),
                    rs.getString("token"),
                    rs.getString("extra_json"),
                    parseInstant(rs.getString("expires_at")),
                    parseInstant(rs.getString("updated_at"))
                );
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load search auth", ex);
        }
    }

    public void upsertAuth(AuthRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_UPSERT_AUTH)) {
            statement.setString(1, record.source());
            statement.setString(2, record.scopeType());
            statement.setString(3, normalizeBotId(record.botId()));
            statement.setString(4, safe(record.cookie()));
            statement.setString(5, safeNullable(record.token()));
            statement.setString(6, safeNullable(record.extraJson()));
            statement.setString(7, toText(record.expiresAt()));
            statement.setString(8, toText(record.updatedAt()));
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to upsert search auth", ex);
        }
    }

    public void deleteAuth(String source, String scopeType, String botId) {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_DELETE_AUTH)) {
            statement.setString(1, source);
            statement.setString(2, scopeType);
            statement.setString(3, normalizeBotId(botId));
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to delete search auth", ex);
        }
    }

    public List<AuthRecord> listAuthBySource(String source) {
        if (source == null || source.isBlank()) {
            return List.of();
        }
        List<AuthRecord> records = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_SELECT_AUTH_BY_SOURCE)) {
            statement.setString(1, source.trim());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    records.add(new AuthRecord(
                        rs.getString("source"),
                        rs.getString("scope_type"),
                        rs.getString("bot_id"),
                        rs.getString("cookie"),
                        rs.getString("token"),
                        rs.getString("extra_json"),
                        parseInstant(rs.getString("expires_at")),
                        parseInstant(rs.getString("updated_at"))
                    ));
                }
            }
            return records;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list search auth by source", ex);
        }
    }

    public LoginSessionRecord getLoginSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_SELECT_LOGIN)) {
            statement.setString(1, sessionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new LoginSessionRecord(
                    rs.getString("session_id"),
                    rs.getString("source"),
                    rs.getString("scope_type"),
                    rs.getString("bot_id"),
                    rs.getString("status"),
                    rs.getString("payload"),
                    parseInstant(rs.getString("created_at")),
                    parseInstant(rs.getString("updated_at"))
                );
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load login session", ex);
        }
    }

    public void upsertLoginSession(LoginSessionRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_UPSERT_LOGIN)) {
            statement.setString(1, record.sessionId());
            statement.setString(2, record.source());
            statement.setString(3, record.scopeType());
            statement.setString(4, normalizeBotId(record.botId()));
            statement.setString(5, record.status());
            statement.setString(6, safeNullable(record.payload()));
            statement.setString(7, toText(record.createdAt()));
            statement.setString(8, toText(record.updatedAt()));
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to upsert login session", ex);
        }
    }

    public void updateLoginStatus(String sessionId, String status) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_UPDATE_LOGIN_STATUS)) {
            statement.setString(1, status == null ? "" : status);
            statement.setString(2, Instant.now().toString());
            statement.setString(3, sessionId);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to update login session", ex);
        }
    }

    public void deleteLoginSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_DELETE_LOGIN)) {
            statement.setString(1, sessionId);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to delete login session", ex);
        }
    }

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

    private String normalizeBotId(String botId) {
        return botId == null ? "" : botId.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safeNullable(String value) {
        return value == null ? null : value;
    }

    private String toText(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (Exception ex) {
            log.warn("Failed to parse instant {}", raw, ex);
            return null;
        }
    }

    public record AuthRecord(
        String source,
        String scopeType,
        String botId,
        String cookie,
        String token,
        String extraJson,
        Instant expiresAt,
        Instant updatedAt
    ) {
    }

    public record LoginSessionRecord(
        String sessionId,
        String source,
        String scopeType,
        String botId,
        String status,
        String payload,
        Instant createdAt,
        Instant updatedAt
    ) {
    }
}
