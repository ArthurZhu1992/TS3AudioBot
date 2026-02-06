package pub.longyi.ts3audiobot.config;

import com.moandjiezana.toml.Toml;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pub.longyi.ts3audiobot.ts3.full.IdentityData;
import pub.longyi.ts3audiobot.ts3.full.TsCrypt;
import pub.longyi.ts3audiobot.util.CliToolLocator;
import pub.longyi.ts3audiobot.util.FfmpegLocator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 ConfigService 相关功能。
 */


/**
 * ConfigService 相关功能。
 *
 * <p>职责：负责 ConfigService 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Slf4j
@Component
public final class ConfigService {
    private static final String DEFAULT_CONFIG_FILE = "ts3Audio-config.toml";
    private static final String DEFAULT_DB_FILE = "ts3audiobot.db";
    private static final String DEFAULT_BOTS_PATH = "bots";
    private static final String DEFAULT_FFMPEG_PATH = "ffmpeg";
    private static final String DEFAULT_YT = "yt-dlp";
    private static final String DEFAULT_NETEASE = "netease-cloud-music";
    private static final String DEFAULT_QQ = "qqmusic";
    private static final int DEFAULT_WEB_PORT = 58913;
    private static final List<String> DEFAULT_HOSTS = List.of("*");
    private static final String DEFAULT_CLIENT_VERSION = "3.6.2 [Build: 1695203293]";
    private static final String DEFAULT_CLIENT_PLATFORM = "Windows";
    private static final String DEFAULT_CLIENT_VERSION_SIGN =
        "JEhuVodiWr2/F9mixBcaAZTtjx4Rs9cJDLbpEG8i7hPKswcFdsn6MWwINP+Nwmw4AEPpVJevUEvRQbqVMVoLlw==";
    private static final int DEFAULT_IDENTITY_MIN_LEVEL = 100;
    private static final int DEFAULT_VOLUME_PERCENT = 100;

    private static final String KEY_BOTS_PATH = "configs.bots_path";
    private static final String KEY_DB_PATH = "configs.db_path";
    private static final String KEY_WEB_PORT = "web.port";
    private static final String KEY_WEB_HOSTS = "web.hosts";
    private static final String KEY_WEB_API = "web.api.enabled";
    private static final String KEY_WEB_UI = "web.interface.enabled";
    private static final String KEY_FFMPEG = "tools.ffmpeg_path";
    private static final String KEY_RESOLVER_YT = "resolvers.external.yt";
    private static final String KEY_RESOLVER_YTMUSIC = "resolvers.external.ytmusic";
    private static final String KEY_RESOLVER_NETEASE = "resolvers.external.netease";
    private static final String KEY_RESOLVER_QQ = "resolvers.external.qq";

    private static final Pattern INI_KEY_VALUE = Pattern.compile("^([^=]+)=(.*)$");

    private final AppConfig config;
    private final Path configPath;
    private final SqliteConfigStore configStore;

    /**
     * 创建 ConfigService 实例。
     */
    public ConfigService() {
        this.configPath = resolveConfigPath();
        Map<String, String> externalSettings = loadExternalSettings(configPath);
        ensureDataDirExists(configPath);
        Path dbPath = resolveDbPath(configPath, externalSettings);
        this.configStore = new SqliteConfigStore(dbPath);
        this.configStore.initialize();
        log.info("Using sqlite config at {}", dbPath.toAbsolutePath());
        this.config = loadFromStore(configPath, externalSettings);
    }


    /**
     * 执行 get 操作。
     * @return 返回值
     */
    public AppConfig get() {
        return config;
    }


    /**
     * 执行 loadBots 操作。
     * @return 返回值
     */
    public List<AppConfig.BotConfig> loadBots() {
        SqliteConfigStore.ConfigSnapshot snapshot = configStore.loadConfig();
        List<IdentityUpdate> identityUpdates = new ArrayList<>();
        List<AppConfig.BotConfig> bots = resolveBots(configPath, snapshot, identityUpdates);
        persistIdentityUpdates(identityUpdates);
        List<AppConfig.BotConfig> normalized = new ArrayList<>(bots.size());
        for (AppConfig.BotConfig bot : bots) {
            AppConfig.BotConfig fixed = applyClientDefaults(bot);
            normalized.add(fixed);
            if (fixed != bot) {
                configStore.upsertBot(fixed);
            }
        }
        return normalized;
    }


    /**
     * 执行 updateBotVolume 操作。
     * @param botName 参数 botName
     * @param volumePercent 参数 volumePercent
     */
    public void updateBotVolume(String botName, int volumePercent) {
        configStore.updateBotVolume(botName, clampVolumePercent(volumePercent));
    }


    /**
     * 执行 getConfigStore 操作。
     * @return 返回值
     */
    public SqliteConfigStore getConfigStore() {
        return configStore;
    }


    /**
     * 执行 getDataDir 操作。
     * @return 返回值
     */
    public Path getDataDir() {
        Path baseDir = configPath.getParent();
        if (baseDir == null) {
            baseDir = Path.of(".");
        }
        return baseDir.resolve("data");
    }

    private AppConfig loadFromStore(Path configPath, Map<String, String> externalSettings) {
        SqliteConfigStore.ConfigSnapshot snapshot = configStore.loadConfig();
        Map<String, String> settings = snapshot == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(snapshot.getSettings());
        if (externalSettings != null && !externalSettings.isEmpty()) {
            settings.putAll(externalSettings);
        }
        ResolvedSettings resolved = resolveSettings(settings);
        List<IdentityUpdate> identityUpdates = new ArrayList<>();
        List<AppConfig.BotConfig> bots = resolveBots(configPath, snapshot, identityUpdates);
        persistIdentityUpdates(identityUpdates);

        return new AppConfig(
            new AppConfig.Configs(resolved.botsPath),
            new AppConfig.Web(
                resolved.port,
                resolved.hosts,
                new AppConfig.WebApi(resolved.apiEnabled),
                new AppConfig.WebInterface(resolved.uiEnabled)
            ),
            new AppConfig.Tools(resolved.ffmpegPath),
            new AppConfig.Resolvers(new AppConfig.ExternalResolvers(
                resolved.yt,
                resolved.ytmusic,
                resolved.netease,
                resolved.qq
            )),
            bots
        );
    }

    private ResolvedSettings resolveSettings(Map<String, String> settings) {
        String botsPath = getSetting(settings, KEY_BOTS_PATH, DEFAULT_BOTS_PATH);
        int port = parseIntSetting(settings, KEY_WEB_PORT, DEFAULT_WEB_PORT);
        List<String> hosts = parseHostsSetting(settings.get(KEY_WEB_HOSTS));
        boolean apiEnabled = parseBooleanSetting(settings, KEY_WEB_API, false);
        boolean uiEnabled = parseBooleanSetting(settings, KEY_WEB_UI, true);

        String ffmpegPathRaw = getSetting(settings, KEY_FFMPEG, DEFAULT_FFMPEG_PATH);
        String ffmpegPath = FfmpegLocator.resolve(ffmpegPathRaw);
        if (!ffmpegPath.equals(ffmpegPathRaw)) {
            log.info("Resolved ffmpeg path {} -> {}", ffmpegPathRaw, ffmpegPath);
        }
        String ytRaw = getSetting(settings, KEY_RESOLVER_YT, DEFAULT_YT);
        String ytmusicRaw = getSetting(settings, KEY_RESOLVER_YTMUSIC, DEFAULT_YT);
        String yt = CliToolLocator.resolveYtDlp(ytRaw);
        String ytmusic = CliToolLocator.resolveYtDlp(ytmusicRaw);
        String netease = getSetting(settings, KEY_RESOLVER_NETEASE, DEFAULT_NETEASE);
        String qq = getSetting(settings, KEY_RESOLVER_QQ, DEFAULT_QQ);
        return new ResolvedSettings(botsPath, port, hosts, apiEnabled, uiEnabled, ffmpegPath, yt, ytmusic, netease, qq);
    }

    private List<AppConfig.BotConfig> resolveBots(
        Path configPath,
        SqliteConfigStore.ConfigSnapshot snapshot,
        List<IdentityUpdate> identityUpdates
    ) {
        List<AppConfig.BotConfig> bots = new ArrayList<>();
        List<AppConfig.BotConfig> storedBots = snapshot == null ? List.of() : snapshot.getBots();
        for (AppConfig.BotConfig bot : storedBots) {
            if (bot == null) {
                continue;
            }
            int identityLen = bot.identity == null ? 0 : bot.identity.length();
            log.info(
                "Loaded bot {} identity_len={} identity_key_offset={}",
                bot.name,
                identityLen,
                bot.identityKeyOffset
            );
            IdentityResolution resolved = resolveIdentity(configPath, bot.name, bot.identity, bot.identityKeyOffset);
            if (resolved.persistIdentity || resolved.identityKeyOffset != bot.identityKeyOffset) {
                identityUpdates.add(new IdentityUpdate(
                    bot.name,
                    resolved.identity,
                    resolved.identityKeyOffset
                ));
            }
            bots.add(new AppConfig.BotConfig(
                bot.name,
                bot.run,
                bot.connectAddress,
                bot.channel,
                bot.nickname,
                bot.serverPassword,
                bot.channelPassword,
                resolved.identity,
                resolved.identityKeyOffset,
                clampVolumePercent(bot.volumePercent),
                bot.clientVersion,
                bot.clientPlatform,
                bot.clientVersionSign,
                bot.clientHwid,
                bot.clientNicknamePhonetic,
                bot.clientDefaultToken
            ));
        }
        return bots;
    }

    private static Path resolveConfigPath() {
        String envPath = System.getenv("TS3AB_CONFIG");
        if (envPath != null && !envPath.isBlank()) {
            return Path.of(envPath);
        }
        String sysProp = System.getProperty("ts3ab.config");
        if (sysProp != null && !sysProp.isBlank()) {
            return Path.of(sysProp);
        }
        Path jarDir = resolveJarDir();
        if (jarDir != null) {
            return jarDir.resolve(DEFAULT_CONFIG_FILE);
        }
        return Path.of(DEFAULT_CONFIG_FILE);
    }

    private static Path resolveDbPath(Path configPath, Map<String, String> externalSettings) {
        String raw = System.getenv("TS3AB_DB");
        if (raw == null || raw.isBlank()) {
            raw = System.getProperty("ts3ab.db", "");
        }
        if (raw == null || raw.isBlank()) {
            raw = getSetting(externalSettings, KEY_DB_PATH, "");
        }
        Path baseDir = configPath.getParent();
        if (baseDir == null) {
            baseDir = Path.of(".");
        }
        if (raw.isBlank()) {
            return baseDir.resolve("data").resolve(DEFAULT_DB_FILE);
        }
        Path candidate = Path.of(raw);
        if (!candidate.isAbsolute()) {
            candidate = baseDir.resolve(candidate).normalize();
        }
        return candidate;
    }

    private static Map<String, String> loadExternalSettings(Path configPath) {
        if (configPath == null || !Files.isRegularFile(configPath)) {
            return Map.of();
        }
        try {
            Toml toml = new Toml().read(configPath.toFile());
            Map<String, String> settings = new LinkedHashMap<>();
            putIfNotBlank(settings, KEY_BOTS_PATH, toml.getString("configs.bots_path"));
            putIfNotBlank(settings, KEY_DB_PATH, toml.getString("configs.db_path"));

            Long port = toml.getLong("web.port");
            if (port != null) {
                settings.put(KEY_WEB_PORT, Long.toString(port));
            }
            Object hostsValue = toml.get("web.hosts");
            if (hostsValue instanceof List<?>) {
                List<?> list = (List<?>) hostsValue;
                List<String> hosts = new ArrayList<>();
                for (Object item : list) {
                    if (item != null) {
                        String text = item.toString().trim();
                        if (!text.isEmpty()) {
                            hosts.add(text);
                        }
                    }
                }
                if (!hosts.isEmpty()) {
                    settings.put(KEY_WEB_HOSTS, String.join(",", hosts));
                }
            } else if (hostsValue instanceof String) {
                putIfNotBlank(settings, KEY_WEB_HOSTS, (String) hostsValue);
            }
            putIfNotBlank(settings, KEY_WEB_API, toBooleanString(toml.getBoolean("web.api.enabled")));
            putIfNotBlank(settings, KEY_WEB_UI, toBooleanString(toml.getBoolean("web.interface.enabled")));

            putIfNotBlank(settings, KEY_FFMPEG, toml.getString("tools.ffmpeg_path"));
            putIfNotBlank(settings, KEY_RESOLVER_YT, toml.getString("resolvers.external.yt"));
            putIfNotBlank(settings, KEY_RESOLVER_YTMUSIC, toml.getString("resolvers.external.ytmusic"));
            putIfNotBlank(settings, KEY_RESOLVER_NETEASE, toml.getString("resolvers.external.netease"));
            putIfNotBlank(settings, KEY_RESOLVER_QQ, toml.getString("resolvers.external.qq"));

            if (!settings.isEmpty()) {
                log.info("Loaded external config from {}", configPath.toAbsolutePath());
            }
            return settings;
        } catch (Exception ex) {
            log.warn("Failed to read external config {}, using defaults", configPath, ex);
            return Map.of();
        }
    }

    private static Path resolveJarDir() {
        try {
            Path codeSource = Path.of(ConfigService.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(codeSource)) {
                return codeSource.getParent();
            }
            if (Files.isDirectory(codeSource)) {
                return codeSource;
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static void putIfNotBlank(Map<String, String> settings, String key, String value) {
        if (settings == null || key == null) {
            return;
        }
        if (value == null || value.isBlank()) {
            return;
        }
        settings.put(key, value.trim());
    }

    private static String toBooleanString(Boolean value) {
        if (value == null) {
            return null;
        }
        return Boolean.toString(value);
    }

    private static void ensureDataDirExists(Path configPath) {
        Path baseDir = configPath.getParent();
        if (baseDir == null) {
            baseDir = Path.of(".");
        }
        Path dataDir = baseDir.resolve("data");
        if (Files.isDirectory(dataDir)) {
            return;
        }
        try {
            Files.createDirectories(dataDir);
            log.info("Created data directory {}", dataDir);
        } catch (IOException ex) {
            log.warn("Failed to create data directory {}", dataDir, ex);
        }
    }

    private void persistIdentityUpdates(List<IdentityUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        for (IdentityUpdate update : updates) {
            configStore.updateBotIdentityAndOffset(update.botName, update.identity, update.identityKeyOffset);
        }
        log.info("Persisted TS3 identity updates to {}", configStore.getDbPath());
    }

    private static String getSetting(Map<String, String> settings, String key, String defaultValue) {
        if (settings == null || settings.isEmpty()) {
            return defaultValue;
        }
        String value = settings.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    private static int parseIntSetting(Map<String, String> settings, String key, int defaultValue) {
        String value = getSetting(settings, key, Integer.toString(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static boolean parseBooleanSetting(Map<String, String> settings, String key, boolean defaultValue) {
        String value = getSetting(settings, key, Boolean.toString(defaultValue));
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        return defaultValue;
    }

    private static List<String> parseHostsSetting(String hostsRaw) {
        if (hostsRaw == null || hostsRaw.isBlank()) {
            return DEFAULT_HOSTS;
        }
        String[] parts = hostsRaw.split(",");
        List<String> hosts = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                hosts.add(trimmed);
            }
        }
        return hosts.isEmpty() ? DEFAULT_HOSTS : hosts;
    }

    private static long toLong(Long value) {
        if (value == null) {
            return 0L;
        }
        return value;
    }

    private static IdentityResolution resolveIdentity(
        Path configPath,
        String botName,
        String identity,
        long identityKeyOffset
    ) {
        String trimmed = identity == null ? "" : identity.trim();
        int level = (int) Math.max(DEFAULT_IDENTITY_MIN_LEVEL, identityKeyOffset);
        IdentityFileData fileData = tryLoadIdentityFromFile(configPath, trimmed);
        if (fileData != null && fileData.identity != null && !fileData.identity.isBlank()) {
            return normalizeIdentity(fileData.identity, botName, "file", level, true);
        }
        if (trimmed.isBlank()) {
            String export = TsCrypt.generateTsIdentity(level);
            log.info("Generated new TS3 identity for bot {} (security level {})", botName, level);
            return new IdentityResolution(export, level, true);
        }
        return normalizeIdentity(trimmed, botName, "database", level, false);
    }

    private static IdentityResolution normalizeIdentity(
        String rawIdentity,
        String botName,
        String source,
        int level,
        boolean persistAlways
    ) {
        try {
            IdentityData loaded = TsCrypt.loadIdentityDynamic(rawIdentity, level);
            if (!TsCrypt.isTsIdentityFormat(rawIdentity)) {
                String converted = TsCrypt.exportTsIdentity(loaded);
                log.info("Converted TS3 identity for bot {} from {} to standard format", botName, source);
                return new IdentityResolution(converted, loaded.validKeyOffset(), true);
            }
            if (loaded.validKeyOffset() < DEFAULT_IDENTITY_MIN_LEVEL) {
                String export = TsCrypt.generateTsIdentity(level);
                log.warn(
                    "TS3 identity for bot {} from {} has low security level {}, generated a new one",
                    botName,
                    source,
                    loaded.validKeyOffset()
                );
                return new IdentityResolution(export, level, true);
            }
            log.info("Using TS3 identity for bot {} from {}", botName, source);
            return new IdentityResolution(rawIdentity, loaded.validKeyOffset(), persistAlways);
        } catch (RuntimeException ex) {
            String export = TsCrypt.generateTsIdentity(level);
            log.warn("Invalid TS3 identity for bot {} from {}, generated a new one", botName, source, ex);
            return new IdentityResolution(export, level, true);
        }
    }

    private static AppConfig.BotConfig applyClientDefaults(AppConfig.BotConfig bot) {
        if (bot == null) {
            return null;
        }
        boolean changed = false;
        String clientVersion = bot.clientVersion;
        if (clientVersion == null || clientVersion.isBlank()) {
            clientVersion = DEFAULT_CLIENT_VERSION;
            changed = true;
        }
        String clientPlatform = bot.clientPlatform;
        if (clientPlatform == null || clientPlatform.isBlank()) {
            clientPlatform = DEFAULT_CLIENT_PLATFORM;
            changed = true;
        }
        String clientSign = bot.clientVersionSign;
        if (clientSign == null || clientSign.isBlank()) {
            clientSign = DEFAULT_CLIENT_VERSION_SIGN;
            changed = true;
        }
        if (!changed) {
            return bot;
        }
        return new AppConfig.BotConfig(
            bot.name,
            bot.run,
            bot.connectAddress,
            bot.channel,
            bot.nickname,
            bot.serverPassword,
            bot.channelPassword,
            bot.identity,
            bot.identityKeyOffset,
            clampVolumePercent(bot.volumePercent),
            clientVersion,
            clientPlatform,
            clientSign,
            bot.clientHwid,
            bot.clientNicknamePhonetic,
            bot.clientDefaultToken
        );
    }

    private static int clampVolumePercent(int percent) {
        if (percent < 0) {
            return DEFAULT_VOLUME_PERCENT;
        }
        if (percent > 100) {
            return 100;
        }
        return percent;
    }

    private static IdentityFileData tryLoadIdentityFromFile(Path configPath, String identityValue) {
        if (identityValue == null || identityValue.isBlank()) {
            return null;
        }
        String raw = identityValue.trim();
        boolean explicit = false;
        if (raw.startsWith("@")) {
            raw = raw.substring(1).trim();
            explicit = true;
        } else if (raw.regionMatches(true, 0, "file:", 0, 5)) {
            raw = raw.substring(5).trim();
            explicit = true;
        }
        Path baseDir = configPath.getParent();
        if (baseDir == null) {
            baseDir = Path.of(".");
        }
        Path candidate = Path.of(raw);
        if (!candidate.isAbsolute()) {
            candidate = baseDir.resolve(candidate).normalize();
        }
        if (!Files.exists(candidate)) {
            if (explicit) {
                log.warn("Identity file not found at {}", candidate);
            }
            return null;
        }
        try {
            IdentityFileData data = readIdentityIni(candidate);
            if (data.identity == null || data.identity.isBlank()) {
                log.warn("Identity file {} does not contain an identity entry", candidate);
                return null;
            }
            return data;
        } catch (IOException ex) {
            log.warn("Failed to read identity file {}", candidate, ex);
            return null;
        }
    }

    private static IdentityFileData readIdentityIni(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        String identity = null;
        String nickname = null;
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";") || trimmed.startsWith("[")) {
                continue;
            }
            Matcher matcher = INI_KEY_VALUE.matcher(trimmed);
            if (!matcher.matches()) {
                continue;
            }
            String key = matcher.group(1).trim();
            String value = matcher.group(2).trim();
            value = unquote(value);
            if ("identity".equalsIgnoreCase(key)) {
                identity = value;
            } else if ("nickname".equalsIgnoreCase(key)) {
                nickname = value;
            }
        }
        return new IdentityFileData(path, identity, nickname);
    }

    private static String unquote(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private record ResolvedSettings(
        String botsPath,
        int port,
        List<String> hosts,
        boolean apiEnabled,
        boolean uiEnabled,
        String ffmpegPath,
        String yt,
        String ytmusic,
        String netease,
        String qq
    ) {
    }

    private record IdentityResolution(String identity, long identityKeyOffset, boolean persistIdentity) {
    }

    private record IdentityFileData(Path path, String identity, String nickname) {
    }

    private record IdentityUpdate(String botName, String identity, long identityKeyOffset) {
    }
}
