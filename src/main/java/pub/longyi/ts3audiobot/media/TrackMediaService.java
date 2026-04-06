package pub.longyi.ts3audiobot.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import pub.longyi.ts3audiobot.config.AppConfig;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.queue.Track;
import pub.longyi.ts3audiobot.search.SearchAuthService;
import pub.longyi.ts3audiobot.search.SearchAuthStore;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

@Slf4j
@Service
public final class TrackMediaService {
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(60);
    private static final long PROCESS_TIMEOUT_MS = 180_000L;
    private static final String MEDIA_DIR_PROPERTY = "ts3audiobot.media.dir";
    private static final String DEFAULT_MEDIA_DIR = "media";
    private static final String CACHE_AUDIO_DIR = "audio";
    private static final String CACHE_COVER_DIR = "cover";
    private static final String CACHE_TRACK_DIR = "track";
    private static final String CACHE_TMP_DIR = "tmp";
    private static final String CACHE_TOUCH_FILE = ".last_access";
    private static final String TRACK_BINDING_FILE = "binding.properties";
    private static final String BINDING_AUDIO_KEY = "audioKey";
    private static final String BINDING_COVER_KEY = "coverKey";
    private static final int DEFAULT_CACHE_TTL_HOURS = 24 * 30;
    private static final int DEFAULT_THUMB_SIZE = 120;
    private static final int DEFAULT_COVER_SIZE = 360;
    private static final Duration TMP_ENTRY_TTL = Duration.ofHours(6);
    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;

    private final ConfigService configService;
    private final SearchAuthService searchAuthService;
    private final Path mediaBaseDir;
    private final Path audioCacheDir;
    private final Path coverCacheDir;
    private final Path trackCacheDir;
    private final Path tmpCacheDir;
    private final boolean mediaCacheEnabled;
    private final boolean audioCacheEnabled;
    private final boolean imageEnabled;
    private final AppConfig.ImageMode imageMode;
    private final int imageThumbSize;
    private final int imageCoverSize;
    private final Duration cacheEntryTtl;
    private final long maxCacheBytes;

    public TrackMediaService(ConfigService configService, Environment environment) {
        this(configService, environment, null);
    }

    @Autowired
    public TrackMediaService(ConfigService configService, Environment environment, SearchAuthService searchAuthService) {
        this.configService = configService;
        this.searchAuthService = searchAuthService;
        var mediaConfig = configService.get().media;
        this.mediaCacheEnabled = mediaConfig != null && mediaConfig.cacheEnabled;
        this.audioCacheEnabled = mediaConfig != null && mediaConfig.audioCacheEnabled;
        AppConfig.Image imageConfig = mediaConfig == null ? null : mediaConfig.image;
        this.imageEnabled = imageConfig == null || imageConfig.enabled;
        this.imageMode = imageConfig == null ? AppConfig.ImageMode.HYBRID : imageConfig.mode;
        this.imageThumbSize = imageConfig == null ? DEFAULT_THUMB_SIZE : Math.max(1, imageConfig.thumbSize);
        this.imageCoverSize = imageConfig == null ? DEFAULT_COVER_SIZE : Math.max(1, imageConfig.coverSize);
        int ttlHours = mediaConfig == null ? DEFAULT_CACHE_TTL_HOURS : Math.max(1, mediaConfig.cacheTtlHours);
        int maxSizeGb = mediaConfig == null ? 20 : Math.max(1, mediaConfig.maxSizeGb);
        this.cacheEntryTtl = Duration.ofHours(ttlHours);
        this.maxCacheBytes = maxSizeGb * BYTES_PER_GB;
        String configured = environment == null ? "" : environment.getProperty(MEDIA_DIR_PROPERTY, "");
        this.mediaBaseDir = resolveMediaBaseDir(configured).toAbsolutePath().normalize();
        this.audioCacheDir = mediaBaseDir.resolve(CACHE_AUDIO_DIR);
        this.coverCacheDir = mediaBaseDir.resolve(CACHE_COVER_DIR);
        this.trackCacheDir = mediaBaseDir.resolve(CACHE_TRACK_DIR);
        this.tmpCacheDir = mediaBaseDir.resolve(CACHE_TMP_DIR);
        try {
            Files.createDirectories(tmpCacheDir);
            if (mediaCacheEnabled) {
                Files.createDirectories(trackCacheDir);
                cleanStaleCacheEntries(trackCacheDir, cacheEntryTtl);
                // Keep legacy shared caches manageable during migration.
                cleanStaleCacheEntries(audioCacheDir, cacheEntryTtl);
                cleanStaleCacheEntries(coverCacheDir, cacheEntryTtl);
                enforceCacheSizeLimitIfNeeded();
            }
            cleanStaleTmpFiles(tmpCacheDir, TMP_ENTRY_TTL);
        } catch (IOException ex) {
            log.warn("Failed to create media cache directory {}", mediaBaseDir, ex);
        }
    }

    public Track prepareForQueue(Track track) {
        return prepare(track, false);
    }

    public Track prepareForDisplay(Track track) {
        return prepare(track, false);
    }

    public Track prepareForPlayback(Track track) {
        return prepare(track, true);
    }

    public boolean shouldPersistPreparedTrack(Track original, Track prepared) {
        if (original == null || prepared == null) {
            return false;
        }
        return !Objects.equals(original.streamUrl(), prepared.streamUrl())
            || !Objects.equals(original.coverUrl(), prepared.coverUrl());
    }

    public void deleteTrackMedia(Track track) {
        deleteDirectoryQuietly(resolveTrackCacheEntryDir(track == null ? null : track.id()));
        deleteDirectoryQuietly(resolveLegacyTrackDir(track));
    }

    public Optional<Path> findCoverFile(String trackId) {
        return findCoverFile(trackId, 0);
    }

    public Optional<Path> findCoverFile(String trackId, int maxEdge) {
        Optional<Path> base = findCoverBaseFile(trackId);
        if (base.isEmpty() || maxEdge <= 0) {
            return base;
        }
        Path resized = ensureSizedCover(base.get(), maxEdge);
        return Optional.ofNullable(resized == null ? base.get() : resized);
    }

    public int resolveImageSizeForAlias(String alias) {
        String normalized = alias == null ? "" : alias.trim().toLowerCase(Locale.ROOT);
        if ("thumb".equals(normalized)) {
            return imageThumbSize;
        }
        if ("cover".equals(normalized)) {
            return imageCoverSize;
        }
        if (!normalized.isBlank()) {
            try {
                return Math.max(0, Integer.parseInt(normalized));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private Optional<Path> findCoverBaseFile(String trackId) {
        if (isBlank(trackId)) {
            return Optional.empty();
        }
        Optional<Path> inTrackDir = findCachedFile(resolveTrackCacheEntryDir(trackId), "cover.");
        if (inTrackDir.isPresent()) {
            return inTrackDir;
        }
        if (!mediaCacheEnabled) {
            return findCachedFile(resolveLegacyTrackDir(trackId), "cover.");
        }
        Optional<Path> legacySharedByBinding = findCachedFile(
            resolveCoverCacheEntryDir(readLegacyBindingKey(trackId, BINDING_COVER_KEY).orElse("")),
            "cover."
        );
        if (legacySharedByBinding.isPresent()) {
            return legacySharedByBinding;
        }
        // Backward compatibility: older builds used the cover cache key in URL directly.
        Optional<Path> cached = findCachedFile(resolveCoverCacheEntryDir(trackId), "cover.");
        return cached.isPresent() ? cached : findCachedFile(resolveLegacyTrackDir(trackId), "cover.");
    }

    public Path mediaBaseDir() {
        return mediaBaseDir;
    }

    private Track prepare(Track track, boolean includeAudio) {
        if (track == null || isBlank(track.id())) {
            return track;
        }
        String coverUrl = ensureCover(track);
        String streamUrl = track.streamUrl();
        if (includeAudio) {
            String cachedAudio = ensureAudio(track);
            if (!cachedAudio.isBlank()) {
                streamUrl = cachedAudio;
            }
        }
        if (Objects.equals(coverUrl, track.coverUrl()) && Objects.equals(streamUrl, track.streamUrl())) {
            return track;
        }
        return new Track(
            track.id(),
            track.title(),
            track.sourceType(),
            track.sourceId(),
            streamUrl,
            track.durationMs(),
            coverUrl,
            track.artist(),
            track.playCount()
        );
    }

    private String ensureCover(Track track) {
        String coverUrl = track == null ? "" : track.coverUrl();
        String safeCoverUrl = coverUrl == null ? "" : coverUrl;
        if (!imageEnabled || imageMode == AppConfig.ImageMode.DIRECT) {
            return safeCoverUrl;
        }
        if (!mediaCacheEnabled) {
            return safeCoverUrl;
        }
        boolean hybridMode = imageMode == AppConfig.ImageMode.HYBRID;
        boolean preferDirect = hybridMode && isHttpUrl(safeCoverUrl);
        if (track == null || isBlank(track.id())) {
            return safeCoverUrl;
        }
        Path trackDir = ensureCacheEntryDir(trackCacheDir, track.id());
        if (trackDir == null) {
            return safeCoverUrl;
        }
        Optional<Path> cached = findCachedFile(trackDir, "cover.");
        if (cached.isPresent()) {
            markCacheEntryTouched(trackDir);
            return preferDirect ? safeCoverUrl : buildCoverUrl(track.id());
        }
        Optional<Path> migrated = importLegacyCache(resolveLegacyTrackDir(track), "cover.", trackDir, "cover");
        if (migrated.isPresent()) {
            markCacheEntryTouched(trackDir);
            enforceCacheSizeLimitIfNeeded();
            return preferDirect ? safeCoverUrl : buildCoverUrl(track.id());
        }
        String legacyCoverKey = firstNonBlank(
            readLegacyBindingKey(track.id(), BINDING_COVER_KEY).orElse(""),
            resolveCoverCacheKey(track)
        );
        Optional<Path> migratedLegacyShared = importLegacyCache(
            resolveCoverCacheEntryDir(legacyCoverKey),
            "cover.",
            trackDir,
            "cover"
        );
        if (migratedLegacyShared.isPresent()) {
            markCacheEntryTouched(trackDir);
            enforceCacheSizeLimitIfNeeded();
            return preferDirect ? safeCoverUrl : buildCoverUrl(track.id());
        }
        if (!isHttpUrl(safeCoverUrl)) {
            return safeCoverUrl;
        }
        try {
            DownloadedFile downloaded = downloadHttpFile(safeCoverUrl, trackDir, "cover", true);
            if (downloaded != null) {
                markCacheEntryTouched(trackDir);
                enforceCacheSizeLimitIfNeeded();
                return preferDirect ? safeCoverUrl : buildCoverUrl(track.id());
            }
        } catch (Exception ex) {
            log.warn("Failed to cache cover for track {}", track.id(), ex);
        }
        return safeCoverUrl;
    }

    private String ensureAudio(Track track) {
        if (track == null) {
            return "";
        }
        if (!mediaCacheEnabled || !audioCacheEnabled) {
            return track.streamUrl();
        }
        if (isExistingLocalPath(track.streamUrl())) {
            return Path.of(track.streamUrl()).toAbsolutePath().normalize().toString();
        }
        Path trackDir = ensureCacheEntryDir(trackCacheDir, track.id());
        if (trackDir == null) {
            return track.streamUrl();
        }
        Optional<Path> cached = findCachedFile(trackDir, "audio.");
        if (cached.isPresent()) {
            markCacheEntryTouched(trackDir);
            return cached.get().toAbsolutePath().normalize().toString();
        }
        Optional<Path> migrated = importLegacyCache(resolveLegacyTrackDir(track), "audio.", trackDir, "audio");
        if (migrated.isPresent()) {
            markCacheEntryTouched(trackDir);
            enforceCacheSizeLimitIfNeeded();
            return migrated.get().toAbsolutePath().normalize().toString();
        }
        String legacyAudioKey = firstNonBlank(
            readLegacyBindingKey(track.id(), BINDING_AUDIO_KEY).orElse(""),
            resolveAudioCacheKey(track)
        );
        Optional<Path> migratedLegacyShared = importLegacyCache(
            resolveAudioCacheEntryDir(legacyAudioKey),
            "audio.",
            trackDir,
            "audio"
        );
        if (migratedLegacyShared.isPresent()) {
            markCacheEntryTouched(trackDir);
            enforceCacheSizeLimitIfNeeded();
            return migratedLegacyShared.get().toAbsolutePath().normalize().toString();
        }
        if (cacheAudioWithYtDlp(track, trackDir)) {
            markCacheEntryTouched(trackDir);
            enforceCacheSizeLimitIfNeeded();
            return findCachedFile(trackDir, "audio.").map(path -> path.toAbsolutePath().normalize().toString()).orElse(track.streamUrl());
        }
        if (isHttpUrl(track.streamUrl())) {
            try {
                DownloadedFile downloaded = downloadHttpFile(track.streamUrl(), trackDir, "audio", false);
                if (downloaded != null) {
                    markCacheEntryTouched(trackDir);
                    enforceCacheSizeLimitIfNeeded();
                    return downloaded.path().toAbsolutePath().normalize().toString();
                }
            } catch (Exception ex) {
                log.warn("Failed to cache direct audio for track {}", track.id(), ex);
            }
        }
        return track.streamUrl();
    }

    private boolean cacheAudioWithYtDlp(Track track, Path trackDir) {
        String sourceId = track.sourceId();
        if (!isHttpUrl(sourceId)) {
            return false;
        }
        String command = resolveYtCommand(track.sourceType(), sourceId);
        if (isBlank(command)) {
            return false;
        }
        List<String> args = buildYtDlpCacheArgs(track, trackDir);
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            boolean finished = process.waitFor(PROCESS_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Audio cache timeout for track {}", track.id());
                return false;
            }
            if (process.exitValue() != 0) {
                log.warn("Audio cache command exit {} for track {}", process.exitValue(), track.id());
                return false;
            }
            return findCachedFile(trackDir, "audio.").isPresent();
        } catch (Exception ex) {
            log.warn("Failed to cache audio with yt-dlp for track {}", track.id(), ex);
            return false;
        }
    }

    private List<String> buildYtDlpCacheArgs(Track track, Path trackDir) {
        String sourceId = track == null ? "" : track.sourceId();
        String sourceType = track == null ? "" : track.sourceType();
        String command = resolveYtCommand(sourceType, sourceId);
        List<String> args = new ArrayList<>();
        args.add(command);
        args.add("-q");
        args.add("--no-warnings");
        args.add("--no-playlist");
        args.add("--encoding");
        args.add("UTF-8");
        args.add("-f");
        args.add("bestaudio");
        String referer = resolveAuthReferer(sourceType, sourceId);
        if (!isBlank(referer)) {
            args.add("--referer");
            args.add(referer);
            // IMPORTANT: do not remove this cookie forwarding.
            // Resolver and cache are two independent yt-dlp invocations; without
            // cookie in cache stage, VIP tracks can silently fall back to ~30s previews.
            String cookie = resolveSourceCookie(sourceType, sourceId);
            if (!isBlank(cookie)) {
                args.add("--add-headers");
                args.add("Cookie: " + cookie);
            }
        }
        args.add("-o");
        args.add(trackDir.resolve("audio.%(ext)s").toString());
        args.add(sourceId);
        return args;
    }

    private String resolveAuthReferer(String sourceType, String sourceId) {
        String normalized = normalizeSourceType(sourceType);
        String lowerSourceId = sourceId == null ? "" : sourceId.toLowerCase(Locale.ROOT);
        if ("qq".equals(normalized)
            || lowerSourceId.contains("y.qq.com")
            || lowerSourceId.contains("qqmusic.qq.com")
            || lowerSourceId.contains("c.y.qq.com")
            || lowerSourceId.contains("u.y.qq.com")) {
            return "https://y.qq.com/";
        }
        if ("netease".equals(normalized)
            || lowerSourceId.contains("music.163.com")
            || lowerSourceId.contains("musicapi.163.com")
            || lowerSourceId.contains("interface3.music.163.com")) {
            return "https://music.163.com/";
        }
        return "";
    }

    private String resolveSourceCookie(String sourceType, String sourceId) {
        if (searchAuthService == null) {
            return "";
        }
        String normalized = normalizeSourceType(sourceType);
        String lowerSourceId = sourceId == null ? "" : sourceId.toLowerCase(Locale.ROOT);
        String source = "";
        if ("qq".equals(normalized)
            || lowerSourceId.contains("y.qq.com")
            || lowerSourceId.contains("qqmusic.qq.com")
            || lowerSourceId.contains("c.y.qq.com")
            || lowerSourceId.contains("u.y.qq.com")) {
            source = "qq";
        } else if ("netease".equals(normalized)
            || lowerSourceId.contains("music.163.com")
            || lowerSourceId.contains("musicapi.163.com")
            || lowerSourceId.contains("interface3.music.163.com")) {
            source = "netease";
        }
        if (isBlank(source)) {
            return "";
        }
        return latestAuthCookie(source);
    }

    private String latestAuthCookie(String source) {
        List<SearchAuthStore.AuthRecord> records = searchAuthService.listAuthBySource(source);
        SearchAuthStore.AuthRecord latest = null;
        if (records != null) {
            for (SearchAuthStore.AuthRecord record : records) {
                if (record == null || searchAuthService.isExpired(record)) {
                    continue;
                }
                if (isBlank(record.cookie())) {
                    continue;
                }
                if (latest == null) {
                    latest = record;
                    continue;
                }
                if (record.updatedAt() != null
                    && (latest.updatedAt() == null || record.updatedAt().isAfter(latest.updatedAt()))) {
                    latest = record;
                }
            }
        }
        if (latest != null) {
            return latest.cookie().trim();
        }
        return searchAuthService.resolveAuth(source, "")
            .map(SearchAuthStore.AuthRecord::cookie)
            .map(String::trim)
            .orElse("");
    }

    private String resolveYtCommand(String sourceType, String sourceId) {
        var external = configService.get().resolvers.external;
        String normalized = normalizeSourceType(sourceType);
        String lowerSourceId = sourceId == null ? "" : sourceId.toLowerCase(Locale.ROOT);

        if ("ytmusic".equals(normalized) || lowerSourceId.contains("music.youtube.com")) {
            return firstAvailableResolver(external.ytmusic, external.yt);
        }
        if ("yt".equals(normalized) || lowerSourceId.contains("youtube.com") || lowerSourceId.contains("youtu.be")) {
            return firstAvailableResolver(external.yt, external.ytmusic);
        }
        if ("qq".equals(normalized)
            || lowerSourceId.contains("y.qq.com")
            || lowerSourceId.contains("qqmusic.qq.com")
            || lowerSourceId.contains("c.y.qq.com")
            || lowerSourceId.contains("u.y.qq.com")) {
            return firstAvailableResolver(external.qq, external.ytmusic, external.yt);
        }
        if ("netease".equals(normalized)
            || lowerSourceId.contains("music.163.com")
            || lowerSourceId.contains("musicapi.163.com")
            || lowerSourceId.contains("interface3.music.163.com")) {
            return firstAvailableResolver(external.netease, external.ytmusic, external.yt);
        }
        return firstAvailableResolver(external.ytmusic, external.yt);
    }

    private String normalizeSourceType(String sourceType) {
        if (isBlank(sourceType)) {
            return "";
        }
        String lower = sourceType.trim().toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "yt", "youtube" -> "yt";
            case "ytmusic", "youtube music" -> "ytmusic";
            case "qq", "qqmusic", "qq music" -> "qq";
            case "netease", "netease music", "netease-cloud-music" -> "netease";
            default -> lower;
        };
    }

    private String firstAvailableResolver(String... candidates) {
        if (candidates == null) {
            return "";
        }
        for (String candidate : candidates) {
            if (isResolverCommand(candidate)) {
                return candidate.trim();
            }
        }
        return "";
    }

    private boolean isResolverCommand(String value) {
        if (isBlank(value)) {
            return false;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return !lower.equals("qqmusic") && !lower.equals("netease-cloud-music");
    }

    private DownloadedFile downloadHttpFile(String url, Path trackDir, String baseName, boolean image) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(DOWNLOAD_TIMEOUT)
            .header("User-Agent", "Mozilla/5.0 TS3AudioBot")
            .GET()
            .build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return null;
        }
        String extension = guessExtension(url, response.headers().firstValue("Content-Type").orElse(""), image);
        Path target = trackDir.resolve(baseName + extension);
        Path temp = createTempFile(baseName, extension);
        try (InputStream body = response.body()) {
            Files.copy(body, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new DownloadedFile(target, response.headers().firstValue("Content-Type").orElse(""));
    }

    private String guessExtension(String url, String contentType, boolean image) {
        String fromUrl = extensionFromUrl(url);
        if (!fromUrl.isBlank()) {
            return fromUrl;
        }
        String lowerType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (lowerType.contains("jpeg")) {
            return ".jpg";
        }
        if (lowerType.contains("png")) {
            return ".png";
        }
        if (lowerType.contains("webp")) {
            return ".webp";
        }
        if (lowerType.contains("mpeg")) {
            return ".mp3";
        }
        if (lowerType.contains("ogg")) {
            return ".ogg";
        }
        if (lowerType.contains("webm")) {
            return ".webm";
        }
        if (lowerType.contains("mp4")) {
            return ".m4a";
        }
        return image ? ".jpg" : ".bin";
    }

    private String extensionFromUrl(String url) {
        if (isBlank(url)) {
            return "";
        }
        String lower = url.toLowerCase(Locale.ROOT);
        int queryIndex = lower.indexOf('?');
        if (queryIndex >= 0) {
            lower = lower.substring(0, queryIndex);
        }
        for (String ext : List.of(".jpg", ".jpeg", ".png", ".webp", ".mp3", ".ogg", ".webm", ".m4a", ".mp4")) {
            if (lower.endsWith(ext)) {
                return ext;
            }
        }
        return "";
    }

    private Path ensureSizedCover(Path source, int maxEdge) {
        if (source == null || maxEdge <= 0 || Files.notExists(source)) {
            return source;
        }
        try {
            BufferedImage original = ImageIO.read(source.toFile());
            if (original == null) {
                return source;
            }
            int sourceWidth = Math.max(1, original.getWidth());
            int sourceHeight = Math.max(1, original.getHeight());
            int longest = Math.max(sourceWidth, sourceHeight);
            if (longest <= maxEdge) {
                return source;
            }
            int targetWidth;
            int targetHeight;
            if (sourceWidth >= sourceHeight) {
                targetWidth = maxEdge;
                targetHeight = Math.max(1, (int) Math.round((double) sourceHeight * maxEdge / sourceWidth));
            } else {
                targetHeight = maxEdge;
                targetWidth = Math.max(1, (int) Math.round((double) sourceWidth * maxEdge / sourceHeight));
            }
            Path parent = source.getParent();
            if (parent == null) {
                return source;
            }
            Path target = parent.resolve("cover-" + maxEdge + ".jpg");
            long sourceModified = Files.getLastModifiedTime(source).toMillis();
            if (Files.exists(target) && Files.getLastModifiedTime(target).toMillis() >= sourceModified) {
                return target;
            }
            BufferedImage resized = resizeImage(original, targetWidth, targetHeight);
            Path temp = createTempFile("cover-" + maxEdge, ".jpg");
            try {
                if (!ImageIO.write(resized, "jpg", temp.toFile())) {
                    return source;
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return target;
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Exception ex) {
            log.debug("Failed to generate sized cover {} edge={}", source, maxEdge, ex);
            return source;
        }
    }

    private BufferedImage resizeImage(BufferedImage source, int width, int height) {
        BufferedImage rgb = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rgb.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, rgb.getWidth(), rgb.getHeight(), null);
        } finally {
            graphics.dispose();
        }
        return rgb;
    }

    private Optional<Path> findCachedFile(Path trackDir, String prefix) {
        if (trackDir == null || !Files.isDirectory(trackDir)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(trackDir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().startsWith(prefix))
                .findFirst();
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    private Path resolveLegacyTrackDir(Track track) {
        if (track == null) {
            return null;
        }
        return resolveLegacyTrackDir(track.id());
    }

    private Path resolveLegacyTrackDir(String trackId) {
        if (isBlank(trackId)) {
            return null;
        }
        return mediaBaseDir.resolve(sanitizeSegment(trackId));
    }

    private Path resolveCoverCacheEntryDir(String token) {
        if (isBlank(token)) {
            return null;
        }
        return coverCacheDir.resolve(sanitizeSegment(token));
    }

    private Path resolveAudioCacheEntryDir(String token) {
        if (isBlank(token)) {
            return null;
        }
        return audioCacheDir.resolve(sanitizeSegment(token));
    }

    private Path resolveTrackCacheEntryDir(String trackId) {
        if (isBlank(trackId)) {
            return null;
        }
        return trackCacheDir.resolve(sanitizeSegment(trackId));
    }

    private Optional<String> readLegacyBindingKey(String trackId, String keyName) {
        Path bindingFile = resolveTrackCacheEntryDir(trackId);
        if (bindingFile != null) {
            bindingFile = bindingFile.resolve(TRACK_BINDING_FILE);
        }
        if (bindingFile == null || Files.notExists(bindingFile)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(bindingFile)) {
            properties.load(input);
        } catch (IOException ex) {
            return Optional.empty();
        }
        String value = properties.getProperty(keyName, "").trim();
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private Path ensureCacheEntryDir(Path baseDir, String key) {
        if (baseDir == null || isBlank(key)) {
            return null;
        }
        Path dir = baseDir.resolve(sanitizeSegment(key));
        try {
            Files.createDirectories(dir);
            markCacheEntryTouched(dir);
            return dir;
        } catch (IOException ex) {
            log.warn("Failed to create cache entry directory {}", dir, ex);
            return null;
        }
    }

    private Optional<Path> importLegacyCache(Path legacyDir, String prefix, Path targetDir, String targetBaseName) {
        if (legacyDir == null || targetDir == null) {
            return Optional.empty();
        }
        Optional<Path> legacy = findCachedFile(legacyDir, prefix);
        if (legacy.isEmpty()) {
            return Optional.empty();
        }
        Path source = legacy.get();
        String fileName = source.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot) : "";
        Path target = targetDir.resolve(targetBaseName + ext);
        try {
            if (!Files.exists(target)) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return Optional.of(target);
        } catch (IOException ex) {
            log.warn("Failed to import legacy cache {} -> {}", source, target, ex);
            return Optional.empty();
        }
    }

    private Path resolveMediaBaseDir(String configured) {
        if (!isBlank(configured)) {
            Path configuredPath = Path.of(configured.trim());
            if (!configuredPath.isAbsolute()) {
                configuredPath = resolveJarDir().resolve(configuredPath).normalize();
            }
            return configuredPath;
        }
        return resolveJarDir().resolve(DEFAULT_MEDIA_DIR).normalize();
    }

    private Path resolveJarDir() {
        try {
            Path codeSource = Path.of(TrackMediaService.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(codeSource)) {
                return codeSource.getParent();
            }
        } catch (Exception ignored) {
        }
        Path workDir = Path.of(System.getProperty("user.dir", "."));
        return workDir.toAbsolutePath().normalize();
    }

    private String buildCoverUrl(String trackId) {
        return "/internal/media/cover/" + sanitizeSegment(trackId) + "?size=cover";
    }

    private void cleanStaleCacheEntries(Path baseDir, Duration ttl) throws IOException {
        if (baseDir == null || ttl == null || Files.notExists(baseDir)) {
            return;
        }
        long expireBefore = System.currentTimeMillis() - ttl.toMillis();
        try (Stream<Path> stream = Files.list(baseDir)) {
            for (Path entry : stream.filter(Files::isDirectory).toList()) {
                long lastAccess = resolveCacheEntryTime(entry);
                if (lastAccess > 0 && lastAccess < expireBefore) {
                    deleteDirectoryQuietly(entry);
                }
            }
        }
    }

    private void cleanStaleTmpFiles(Path baseDir, Duration ttl) throws IOException {
        if (baseDir == null || ttl == null || Files.notExists(baseDir)) {
            return;
        }
        long expireBefore = System.currentTimeMillis() - ttl.toMillis();
        try (Stream<Path> stream = Files.list(baseDir)) {
            for (Path path : stream.toList()) {
                if (Files.isDirectory(path)) {
                    continue;
                }
                long modified = Files.getLastModifiedTime(path).toMillis();
                if (modified < expireBefore) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private long resolveCacheEntryTime(Path entryDir) {
        if (entryDir == null || Files.notExists(entryDir)) {
            return 0L;
        }
        Path touchFile = entryDir.resolve(CACHE_TOUCH_FILE);
        try {
            if (Files.exists(touchFile)) {
                return Files.getLastModifiedTime(touchFile).toMillis();
            }
            return Files.getLastModifiedTime(entryDir).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private void markCacheEntryTouched(Path entryDir) {
        if (entryDir == null) {
            return;
        }
        Path touchFile = entryDir.resolve(CACHE_TOUCH_FILE);
        try {
            Files.createDirectories(entryDir);
            if (!Files.exists(touchFile)) {
                Files.writeString(touchFile, "");
            }
            FileTime now = FileTime.fromMillis(System.currentTimeMillis());
            Files.setLastModifiedTime(touchFile, now);
            Files.setLastModifiedTime(entryDir, now);
        } catch (IOException ex) {
            log.debug("Failed to update cache entry timestamp {}", entryDir, ex);
        }
    }

    private Path createTempFile(String baseName, String extension) throws IOException {
        Files.createDirectories(tmpCacheDir);
        String ext = extension == null ? "" : extension;
        String suffix = ext.isBlank() ? ".tmp" : ext + ".tmp";
        return Files.createTempFile(tmpCacheDir, sanitizeSegment(baseName) + "-", suffix);
    }

    private void deleteDirectoryQuietly(Path dir) {
        if (dir == null || Files.notExists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    log.warn("Failed to delete cached media {}", path, ex);
                }
            });
        } catch (IOException ex) {
            log.warn("Failed to delete cache directory {}", dir, ex);
        }
    }

    private void enforceCacheSizeLimitIfNeeded() {
        if (!mediaCacheEnabled || maxCacheBytes <= 0) {
            return;
        }
        try {
            long totalSize = directorySize(trackCacheDir) + directorySize(audioCacheDir) + directorySize(coverCacheDir);
            if (totalSize <= maxCacheBytes) {
                return;
            }
            List<CacheEntry> entries = listCacheEntries();
            entries.sort(Comparator.comparingLong(CacheEntry::lastAccessMillis));
            for (CacheEntry entry : entries) {
                if (totalSize <= maxCacheBytes) {
                    break;
                }
                deleteDirectoryQuietly(entry.path());
                totalSize -= entry.sizeBytes();
            }
        } catch (Exception ex) {
            log.warn("Failed to enforce media cache size limit", ex);
        }
    }

    private List<CacheEntry> listCacheEntries() {
        List<CacheEntry> entries = new ArrayList<>();
        entries.addAll(listCacheEntries(trackCacheDir));
        entries.addAll(listCacheEntries(audioCacheDir));
        entries.addAll(listCacheEntries(coverCacheDir));
        return entries;
    }

    private List<CacheEntry> listCacheEntries(Path baseDir) {
        if (baseDir == null || Files.notExists(baseDir)) {
            return List.of();
        }
        List<CacheEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(baseDir)) {
            for (Path path : stream.filter(Files::isDirectory).toList()) {
                long size = directorySize(path);
                if (size <= 0) {
                    continue;
                }
                entries.add(new CacheEntry(path, resolveCacheEntryTime(path), size));
            }
        } catch (IOException ex) {
            log.warn("Failed to list cache entries in {}", baseDir, ex);
        }
        return entries;
    }

    private long directorySize(Path dir) {
        if (dir == null || Files.notExists(dir)) {
            return 0L;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream
                .filter(Files::isRegularFile)
                .mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException ex) {
                        return 0L;
                    }
                })
                .sum();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private String resolveAudioCacheKey(Track track) {
        if (track == null) {
            return "";
        }
        String source = normalizeSourceType(track.sourceType());
        String sourceId = normalizeUrlKey(track.sourceId());
        String streamUrl = normalizeUrlKey(track.streamUrl());
        String sourceIdentity = composeSourceIdentity(source, sourceId);
        String identity = firstNonBlank(sourceIdentity, streamUrl, track.id());
        return hashKey("audio|" + identity);
    }

    private String resolveCoverCacheKey(Track track) {
        if (track == null) {
            return "";
        }
        String coverUrl = normalizeUrlKey(track.coverUrl());
        String sourceId = normalizeUrlKey(track.sourceId());
        String identity = firstNonBlank(coverUrl, sourceId, track.id());
        return hashKey("cover|" + identity);
    }

    private String normalizeUrlKey(String value) {
        if (isBlank(value)) {
            return "";
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        int fragmentIndex = trimmed.indexOf('#');
        if (fragmentIndex >= 0) {
            trimmed = trimmed.substring(0, fragmentIndex);
        }
        return trimmed;
    }

    private String composeSourceIdentity(String source, String sourceId) {
        if (isBlank(sourceId)) {
            return "";
        }
        if (isBlank(source)) {
            return sourceId;
        }
        return source + "|" + sourceId;
    }

    private String hashKey(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 32);
        } catch (Exception ex) {
            return sanitizeSegment(UUID.randomUUID().toString());
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private boolean isExistingLocalPath(String value) {
        if (isBlank(value) || isHttpUrl(value)) {
            return false;
        }
        try {
            return Files.isRegularFile(Path.of(value));
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isHttpUrl(String value) {
        if (isBlank(value)) {
            return false;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String sanitizeSegment(String value) {
        StringBuilder builder = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        return builder.isEmpty() ? "track" : builder.toString();
    }

    private record DownloadedFile(Path path, String contentType) {
    }

    private record CacheEntry(Path path, long lastAccessMillis, long sizeBytes) {
    }
}
