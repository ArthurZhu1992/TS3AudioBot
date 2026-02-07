package pub.longyi.ts3audiobot.config;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 SqliteConfigStore 相关功能。
 */


/**
 * SqliteConfigStore 相关功能。
 *
 * <p>职责：负责 SqliteConfigStore 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Slf4j
public final class SqliteConfigStore {
    private static final String TABLE_SETTINGS = "settings";
    private static final String TABLE_BOTS = "bots";
    private static final String JDBC_PREFIX = "jdbc:sqlite:";

    private static final String SQL_CREATE_SETTINGS = """
        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )
        """;
    private static final String SQL_CREATE_BOTS = """
        CREATE TABLE IF NOT EXISTS bots (
            name TEXT PRIMARY KEY,
            run INTEGER NOT NULL,
            connect_address TEXT NOT NULL,
            channel TEXT NOT NULL,
            nickname TEXT NOT NULL,
            server_password TEXT NOT NULL,
            channel_password TEXT NOT NULL,
            identity TEXT NOT NULL,
            identity_offset INTEGER NOT NULL DEFAULT 0,
            identity_key_offset INTEGER NOT NULL,
            volume_percent INTEGER NOT NULL DEFAULT 100,
            client_version TEXT NOT NULL,
            client_platform TEXT NOT NULL,
            client_version_sign TEXT NOT NULL,
            client_hwid TEXT NOT NULL,
            client_nickname_phonetic TEXT NOT NULL,
            client_default_token TEXT NOT NULL
        )
        """;

    private static final String SQL_COUNT_SETTINGS = "SELECT COUNT(*) FROM " + TABLE_SETTINGS;
    private static final String SQL_COUNT_BOTS = "SELECT COUNT(*) FROM " + TABLE_BOTS;
    private static final String SQL_SELECT_SETTINGS = "SELECT key, value FROM " + TABLE_SETTINGS;
    private static final String SQL_SELECT_BOTS = """
        SELECT name, run, connect_address, channel, nickname, server_password, channel_password,
               identity, identity_offset, identity_key_offset, volume_percent, client_version, client_platform, client_version_sign,
               client_hwid, client_nickname_phonetic, client_default_token
          FROM bots
          ORDER BY name
        """;
    private static final String SQL_DELETE_SETTINGS = "DELETE FROM " + TABLE_SETTINGS;
    private static final String SQL_DELETE_BOTS = "DELETE FROM " + TABLE_BOTS;
    private static final String SQL_INSERT_SETTING = "INSERT INTO settings(key, value) VALUES(?, ?)";
    private static final String SQL_INSERT_BOT = """
        INSERT INTO bots(name, run, connect_address, channel, nickname, server_password, channel_password,
                         identity, identity_offset, identity_key_offset, volume_percent, client_version, client_platform,
                         client_version_sign, client_hwid, client_nickname_phonetic, client_default_token)
        VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    private static final String SQL_UPSERT_BOT = """
        INSERT INTO bots(name, run, connect_address, channel, nickname, server_password, channel_password,
                         identity, identity_offset, identity_key_offset, volume_percent, client_version, client_platform,
                         client_version_sign, client_hwid, client_nickname_phonetic, client_default_token)
        VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(name) DO UPDATE SET
            run = excluded.run,
            connect_address = excluded.connect_address,
            channel = excluded.channel,
            nickname = excluded.nickname,
            server_password = excluded.server_password,
            channel_password = excluded.channel_password,
            identity = excluded.identity,
            identity_offset = excluded.identity_offset,
            identity_key_offset = excluded.identity_key_offset,
            volume_percent = excluded.volume_percent,
            client_version = excluded.client_version,
            client_platform = excluded.client_platform,
            client_version_sign = excluded.client_version_sign,
            client_hwid = excluded.client_hwid,
            client_nickname_phonetic = excluded.client_nickname_phonetic,
            client_default_token = excluded.client_default_token
        """;
    private static final String SQL_DELETE_BOT = "DELETE FROM bots WHERE name = ?";
    private static final String SQL_SELECT_BOT = """
        SELECT name, run, connect_address, channel, nickname, server_password, channel_password,
               identity, identity_offset, identity_key_offset, volume_percent, client_version, client_platform, client_version_sign,
               client_hwid, client_nickname_phonetic, client_default_token
          FROM bots
         WHERE name = ?
        """;
    private static final String SQL_UPDATE_IDENTITY = "UPDATE bots SET identity = ? WHERE name = ?";
    private static final String SQL_UPDATE_IDENTITY_OFFSET =
        "UPDATE bots SET identity = ?, identity_offset = ?, identity_key_offset = ? WHERE name = ?";
    private static final String SQL_UPDATE_VOLUME = "UPDATE bots SET volume_percent = ? WHERE name = ?";

    private final Path dbPath;

    /**
     * 创建 SqliteConfigStore 实例。
     * @param dbPath 参数 dbPath
     */
    public SqliteConfigStore(Path dbPath) {
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
            statement.execute(SQL_CREATE_SETTINGS);
            statement.execute(SQL_CREATE_BOTS);
            ensureBotVolumeColumn(connection);
            ensureBotIdentityOffsetColumn(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to initialize sqlite schema at " + dbPath, ex);
        }
    }


    /**
     * 执行 hasConfig 操作。
     * @return 返回值
     */
    public boolean hasConfig() {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            return hasRows(statement, SQL_COUNT_SETTINGS) || hasRows(statement, SQL_COUNT_BOTS);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to query sqlite config state at " + dbPath, ex);
        }
    }


    /**
     * 执行 loadConfig 操作。
     * @return 返回值
     */
    public ConfigSnapshot loadConfig() {
        try (Connection connection = openConnection()) {
            Map<String, String> settings = loadSettings(connection);
            List<AppConfig.BotConfig> bots = loadBots(connection);
            return new ConfigSnapshot(settings, bots);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load sqlite config at " + dbPath, ex);
        }
    }


    /**
     * 执行 saveConfig 操作。
     * @param snapshot 参数 snapshot
     */
    public void saveConfig(ConfigSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            saveSettings(connection, snapshot.settings);
            saveBots(connection, snapshot.bots);
            connection.commit();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to save sqlite config at " + dbPath, ex);
        }
    }


    /**
     * 执行 updateBotIdentity 操作。
     * @param botName 参数 botName
     * @param identity 参数 identity
     */
    public void updateBotIdentity(String botName, String identity) {
        if (botName == null || botName.isBlank()) {
            throw new IllegalArgumentException("botName must not be blank");
        }
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_UPDATE_IDENTITY)) {
            statement.setString(1, identity);
            statement.setString(2, botName);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                log.warn("No bot row updated for identity name={}", botName);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to update bot identity for " + botName, ex);
        }
    }


    /**
     * 执行 updateBotIdentityAndOffset 操作。
     * @param botName 参数 botName
     * @param identity 参数 identity
     * @param identityKeyOffset 参数 identityKeyOffset
     */
    public void updateBotIdentityAndOffset(String botName, String identity, long identityOffset, long identityKeyOffset) {
        if (botName == null || botName.isBlank()) {
            throw new IllegalArgumentException("botName must not be blank");
        }
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_UPDATE_IDENTITY_OFFSET)) {
            statement.setString(1, identity);
            statement.setLong(2, identityOffset);
            statement.setLong(3, identityKeyOffset);
            statement.setString(4, botName);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                log.warn("No bot row updated for identity+offset name={}", botName);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to update bot identity+offset for " + botName, ex);
        }
    }


    /**
     * 执行 updateBotVolume 操作。
     * @param botName 参数 botName
     * @param volumePercent 参数 volumePercent
     */
    public void updateBotVolume(String botName, int volumePercent) {
        if (botName == null || botName.isBlank()) {
            throw new IllegalArgumentException("botName must not be blank");
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_UPDATE_VOLUME)) {
            statement.setInt(1, volumePercent);
            statement.setString(2, botName);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                log.warn("No bot row updated for volume name={}", botName);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to update bot volume for " + botName, ex);
        }
    }


    /**
     * 执行 listBots 操作。
     * @return 返回值
     */
    public List<AppConfig.BotConfig> listBots() {
        try (Connection connection = openConnection()) {
            return loadBots(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to list bots at " + dbPath, ex);
        }
    }


    /**
     * 执行 getBot 操作。
     * @param name 参数 name
     * @return 返回值
     */
    public AppConfig.BotConfig getBot(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_SELECT_BOT)) {
            statement.setString(1, name);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new AppConfig.BotConfig(
                    rs.getString("name"),
                    rs.getInt("run") != 0,
                    rs.getString("connect_address"),
                    rs.getString("channel"),
                    rs.getString("nickname"),
                    rs.getString("server_password"),
                    rs.getString("channel_password"),
                    rs.getString("identity"),
                    rs.getLong("identity_offset"),
                    rs.getLong("identity_key_offset"),
                    rs.getInt("volume_percent"),
                    rs.getString("client_version"),
                    rs.getString("client_platform"),
                    rs.getString("client_version_sign"),
                    rs.getString("client_hwid"),
                    rs.getString("client_nickname_phonetic"),
                    rs.getString("client_default_token")
                );
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load bot " + name, ex);
        }
    }


    /**
     * 执行 upsertBot 操作。
     * @param bot 参数 bot
     */
    public void upsertBot(AppConfig.BotConfig bot) {
        if (bot == null || bot.name == null || bot.name.isBlank()) {
            throw new IllegalArgumentException("bot name must not be blank");
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_UPSERT_BOT)) {
            bindBot(statement, bot);
            statement.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to upsert bot " + bot.name, ex);
        }
    }


    /**
     * 执行 deleteBot 操作。
     * @param name 参数 name
     * @return 返回值
     */
    public boolean deleteBot(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(SQL_DELETE_BOT)) {
            statement.setString(1, name);
            return statement.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to delete bot " + name, ex);
        }
    }


    /**
     * 执行 getDbPath 操作。
     * @return 返回值
     */
    public Path getDbPath() {
        return dbPath;
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

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(JDBC_PREFIX + dbPath.toAbsolutePath());
    }

    private void ensureBotVolumeColumn(Connection connection) throws SQLException {
        if (connection == null) {
            return;
        }
        boolean hasColumn = false;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(bots)")) {
            while (rs.next()) {
                String name = rs.getString("name");
                if ("volume_percent".equalsIgnoreCase(name)) {
                    hasColumn = true;
                    break;
                }
            }
        }
        if (hasColumn) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE bots ADD COLUMN volume_percent INTEGER NOT NULL DEFAULT 100");
        }
    }

    private void ensureBotIdentityOffsetColumn(Connection connection) throws SQLException {
        if (connection == null) {
            return;
        }
        boolean hasColumn = false;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(bots)")) {
            while (rs.next()) {
                String name = rs.getString("name");
                if ("identity_offset".equalsIgnoreCase(name)) {
                    hasColumn = true;
                    break;
                }
            }
        }
        if (hasColumn) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE bots ADD COLUMN identity_offset INTEGER NOT NULL DEFAULT 0");
        }
    }

    private boolean hasRows(Statement statement, String sql) throws SQLException {
        try (ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                return false;
            }
            return rs.getInt(1) > 0;
        }
    }

    private Map<String, String> loadSettings(Connection connection) throws SQLException {
        Map<String, String> settings = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(SQL_SELECT_SETTINGS);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                settings.put(rs.getString(1), rs.getString(2));
            }
        }
        return settings;
    }

    private List<AppConfig.BotConfig> loadBots(Connection connection) throws SQLException {
        List<AppConfig.BotConfig> bots = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SQL_SELECT_BOTS);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                bots.add(new AppConfig.BotConfig(
                    rs.getString("name"),
                    rs.getInt("run") != 0,
                    rs.getString("connect_address"),
                    rs.getString("channel"),
                    rs.getString("nickname"),
                    rs.getString("server_password"),
                    rs.getString("channel_password"),
                    rs.getString("identity"),
                    rs.getLong("identity_offset"),
                    rs.getLong("identity_key_offset"),
                    rs.getInt("volume_percent"),
                    rs.getString("client_version"),
                    rs.getString("client_platform"),
                    rs.getString("client_version_sign"),
                    rs.getString("client_hwid"),
                    rs.getString("client_nickname_phonetic"),
                    rs.getString("client_default_token")
                ));
            }
        }
        return bots;
    }

    private void saveSettings(Connection connection, Map<String, String> settings) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(SQL_DELETE_SETTINGS);
        }
        if (settings == null || settings.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(SQL_INSERT_SETTING)) {
            for (Map.Entry<String, String> entry : settings.entrySet()) {
                statement.setString(1, entry.getKey());
                statement.setString(2, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void saveBots(Connection connection, List<AppConfig.BotConfig> bots) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(SQL_DELETE_BOTS);
        }
        if (bots == null || bots.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(SQL_INSERT_BOT)) {
            for (AppConfig.BotConfig bot : bots) {
                bindBot(statement, bot);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void bindBot(PreparedStatement statement, AppConfig.BotConfig bot) throws SQLException {
        statement.setString(1, bot.name);
        statement.setInt(2, bot.run ? 1 : 0);
        statement.setString(3, safe(bot.connectAddress));
        statement.setString(4, safe(bot.channel));
        statement.setString(5, safe(bot.nickname));
        statement.setString(6, safe(bot.serverPassword));
        statement.setString(7, safe(bot.channelPassword));
        statement.setString(8, safe(bot.identity));
        statement.setLong(9, bot.identityOffset);
        statement.setLong(10, bot.identityKeyOffset);
        statement.setInt(11, bot.volumePercent);
        statement.setString(12, safe(bot.clientVersion));
        statement.setString(13, safe(bot.clientPlatform));
        statement.setString(14, safe(bot.clientVersionSign));
        statement.setString(15, safe(bot.clientHwid));
        statement.setString(16, safe(bot.clientNicknamePhonetic));
        statement.setString(17, safe(bot.clientDefaultToken));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }


    @Value
    public static class ConfigSnapshot {
        Map<String, String> settings;
        List<AppConfig.BotConfig> bots;
    }
}
