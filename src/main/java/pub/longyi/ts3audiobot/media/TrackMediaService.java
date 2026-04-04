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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

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
    private static final Duration TMP_ENTRY_TTL = Duration.ofHours(6);
    private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;
    private static final long IMAGE_PROBE_TIMEOUT_MS = 1_500L;
    private static final Duration IMAGE_PROBE_SUCCESS_TTL = Duration.ofHours(24);
    private static final Duration IMAGE_PROBE_FAIL_TTL = Duration.ofMinutes(30);
    private static final long IMAGE_TRANSCODE_TIMEOUT_MS = 30_000L;
    private static final String COVER_VARIANT_COVER = "cover";
    private static final String COVER_VARIANT_THUMB = "thumb";
    private static final String COVER_MAIN_JPG_FILE = "cover-main.jpg";
    private static final String COVER_MAIN_AVIF_FILE = "cover-main.avif";
    private static final String COVER_THUMB_JPG_FILE = "cover-thumb.jpg";
    private static final String COVER_THUMB_AVIF_FILE = "cover-thumb.avif";

    private final ConfigService configService;
    private final SearchAuthService searchAuthService;
    private final Path mediaBaseDir;
    private final Path audioCacheDir;
    private final Path coverCacheDir;
    private final Path trackCacheDir;
    private final Path tmpCacheDir;
    private final boolean mediaCacheEnabled;
    private final boolean audioCacheEnabled;
    private final Duration cacheEntryTtl;
    private final long maxCacheBytes;
    private final String ffmpegPath;
    private final boolean imagePolicyEnabled;
    private final String imageMode;
    private final int coverThumbSize;
    private final int coverMainSize;
    private final Map<String, ProbeDecision> directCoverProbeCache = new ConcurrentHashMap<>();

    public TrackMediaService(ConfigService configService, Environment environment) {
        this(configService, environment, null);
    }

    @Autowired
    public TrackMediaService(ConfigService configService, Environment environment, SearchAuthService searchAuthService) {
        this.configService = configService;
        this.searchAuthService = searchAuthService;
        var mediaConfig = configService.get().media;
        AppConfig.MediaImage imageConfig = mediaConfig == null || mediaConfig.image == null
            ? AppConfig.MediaImage.defaults()
            : mediaConfig.image;
        this.mediaCacheEnabled = mediaConfig != null && mediaConfig.cacheEnabled;
        this.audioCacheEnabled = mediaConfig != null && mediaConfig.audioCacheEnabled;
        this.imagePolicyEnabled = imageConfig.enabled;
        this.imageMode = imageConfig.mode;
        this.coverThumbSize = imageConfig.thumbSize;
        this.coverMainSize = imageConfig.coverSize;
        this.ffmpegPath = firstNonBlank(configService.get().tools.ffmpegPath, "ffmpeg");
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
        return findCoverFile(trackId, COVER_VARIANT_COVER, "", true);
    }

    public Optional<Path> findCoverFile(String trackId, String variant, String acceptHeader) {
        return findCoverFile(trackId, normalizeCoverVariant(variant), acceptHeader, false);
    }

    private Optional<Path> findCoverFile(
        String trackId,
        String variant,
        String acceptHeader,
        boolean preferPortableFormat
    ) {
        if (isBlank(trackId)) {
            return Optional.empty();
        }
        Optional<Path> inTrackDir = findCoverFromDirectory(
            resolveTrackCacheEntryDir(trackId),
            variant,
            acceptHeader,
            preferPortableFormat
        );
        if (inTrackDir.isPresent()) {
            return inTrackDir;
        }
        if (!mediaCacheEnabled) {
            return findCoverFromDirectory(resolveLegacyTrackDir(trackId), variant, acceptHeader, preferPortableFormat);
        }
        Optional<Path> legacySharedByBinding = findCoverFromDirectory(
            resolveCoverCacheEntryDir(readLegacyBindingKey(trackId, BINDING_COVER_KEY).orElse("")),
            variant,
            acceptHeader,
            preferPortableFormat
        );
        if (legacySharedByBinding.isPresent()) {
            return legacySharedByBinding;
        }
        // Backward compatibility: older builds used the cover cache key in URL directly.
        Optional<Path> cached = findCoverFromDirectory(
            resolveCoverCacheEntryDir(trackId),
            variant,
            acceptHeader,
            preferPortableFormat
        );
        return cached.isPresent()
            ? cached
            : findCoverFromDirectory(resolveLegacyTrackDir(trackId), variant, acceptHeader, preferPortableFormat);
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
        String coverUrl = track == null || track.coverUrl() == null ? "" : track.coverUrl().trim();
        if (isBlank(coverUrl)) {
            return "";
        }
        if (!imagePolicyEnabled) {
            return ensureCoverWithProxy(track, coverUrl);
        }
        // hybrid 模式始终优先尝试直链，只有探测失败后才回退代理。
        if (isHttpUrl(coverUrl) && shouldUseDirectCover(coverUrl)) {
            return coverUrl;
        }
        return ensureCoverWithProxy(track, coverUrl);
    }

    /**
     * 代理模式下统一走本地缓存：下载一次源图并生成多版本产物。
     */
    private String ensureCoverWithProxy(Track track, String coverUrl) {
        if (!mediaCacheEnabled) {
            return coverUrl;
        }
        if (track == null || isBlank(track.id())) {
            return coverUrl;
        }
        Path trackDir = ensureCacheEntryDir(trackCacheDir, track.id());
        if (trackDir == null) {
            return coverUrl;
        }
        if (hasCoverVariants(trackDir)) {
            markCacheEntryTouched(trackDir);
            return buildCoverUrl(track.id());
        }
        Optional<Path> source = findCoverSourceFile(trackDir);
        if (source.isEmpty()) {
            source = importLegacyCache(resolveLegacyTrackDir(track), "cover.", trackDir, "cover-legacy");
        }
        if (source.isEmpty()) {
            String legacyCoverKey = firstNonBlank(
                readLegacyBindingKey(track.id(), BINDING_COVER_KEY).orElse(""),
                resolveCoverCacheKey(track)
            );
            source = importLegacyCache(
                resolveCoverCacheEntryDir(legacyCoverKey),
                "cover.",
                trackDir,
                "cover-shared-legacy"
            );
        }
        if (source.isPresent()) {
            if (ensureCoverVariants(trackDir, source.get()) || Files.isRegularFile(source.get())) {
                markCacheEntryTouched(trackDir);
                enforceCacheSizeLimitIfNeeded();
                return buildCoverUrl(track.id());
            }
        }
        if (!isHttpUrl(coverUrl)) {
            return coverUrl;
        }
        try {
            DownloadedFile downloaded = downloadHttpFile(coverUrl, trackDir, "cover-downloaded", true);
            if (downloaded != null) {
                if (ensureCoverVariants(trackDir, downloaded.path()) || Files.isRegularFile(downloaded.path())) {
                    markCacheEntryTouched(trackDir);
                    enforceCacheSizeLimitIfNeeded();
                    return buildCoverUrl(track.id());
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to cache cover for track {}", track.id(), ex);
        }
        return coverUrl;
    }

    private boolean shouldUseDirectCover(String coverUrl) {
        if (!isHttpUrl(coverUrl)) {
            return false;
        }
        if (AppConfig.MediaImage.MODE_DIRECT.equals(imageMode)) {
            return true;
        }
        if (AppConfig.MediaImage.MODE_PROXY.equals(imageMode)) {
            return false;
        }
        return probeDirectCoverAvailability(coverUrl);
    }

    private boolean probeDirectCoverAvailability(String coverUrl) {
        String key = normalizeUrlKey(coverUrl);
        long now = System.currentTimeMillis();
        ProbeDecision cached = directCoverProbeCache.get(key);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.available();
        }
        boolean available = probeImageUrl(coverUrl);
        Duration ttl = available ? IMAGE_PROBE_SUCCESS_TTL : IMAGE_PROBE_FAIL_TTL;
        directCoverProbeCache.put(key, new ProbeDecision(available, now + ttl.toMillis()));
        return available;
    }

    private boolean probeImageUrl(String coverUrl) {
        try {
            HttpRequest head = HttpRequest.newBuilder(URI.create(coverUrl))
                .timeout(Duration.ofMillis(IMAGE_PROBE_TIMEOUT_MS))
                .header("User-Agent", "Mozilla/5.0 TS3AudioBot")
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
            HttpResponse<Void> headResponse = HTTP.send(head, HttpResponse.BodyHandlers.discarding());
            int status = headResponse.statusCode();
            if (status >= 200 && status < 300) {
                String type = headResponse.headers().firstValue("Content-Type").orElse("");
                return type.isBlank() || type.toLowerCase(Locale.ROOT).startsWith("image/");
            }
            if (status == 405 || status == 501) {
                return probeImageUrlWithRangeGet(coverUrl);
            }
            return false;
        } catch (Exception ex) {
            return probeImageUrlWithRangeGet(coverUrl);
        }
    }

    private boolean probeImageUrlWithRangeGet(String coverUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(coverUrl))
                .timeout(Duration.ofMillis(IMAGE_PROBE_TIMEOUT_MS))
                .header("User-Agent", "Mozilla/5.0 TS3AudioBot")
                .header("Range", "bytes=0-0")
                .GET()
                .build();
            HttpResponse<Void> response = HTTP.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status < 200 || status >= 400) {
                return false;
            }
            String type = response.headers().firstValue("Content-Type").orElse("");
            return type.isBlank() || type.toLowerCase(Locale.ROOT).startsWith("image/");
        } catch (Exception ex) {
            return false;
        }
    }

    private Optional<Path> findCoverFromDirectory(
        Path directory,
        String variant,
        String acceptHeader,
        boolean preferPortableFormat
    ) {
        if (directory == null || !Files.isDirectory(directory)) {
            return Optional.empty();
        }
        Optional<Path> variantCover = findCoverVariantFile(directory, variant, acceptHeader, preferPortableFormat);
        if (variantCover.isPresent()) {
            return variantCover;
        }
        return findCachedFile(directory, "cover.");
    }

    private Optional<Path> findCoverVariantFile(
        Path directory,
        String variant,
        String acceptHeader,
        boolean preferPortableFormat
    ) {
        List<Path> candidates = new ArrayList<>();
        String normalizedVariant = normalizeCoverVariant(variant);
        String mainBase = COVER_VARIANT_THUMB.equals(normalizedVariant) ? "cover-thumb" : "cover-main";
        Path avif = directory.resolve(mainBase + ".avif");
        Path jpg = directory.resolve(mainBase + ".jpg");
        if (preferPortableFormat) {
            candidates.add(jpg);
            candidates.add(avif);
        } else if (acceptsAvif(acceptHeader)) {
            candidates.add(avif);
            candidates.add(jpg);
        } else {
            candidates.add(jpg);
            candidates.add(avif);
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private boolean hasCoverVariants(Path trackDir) {
        if (trackDir == null) {
            return false;
        }
        return Files.isRegularFile(trackDir.resolve(COVER_MAIN_JPG_FILE))
            && Files.isRegularFile(trackDir.resolve(COVER_THUMB_JPG_FILE));
    }

    private Optional<Path> findCoverSourceFile(Path trackDir) {
        if (trackDir == null || !Files.isDirectory(trackDir)) {
            return Optional.empty();
        }
        List<String> preferred = List.of(
            COVER_MAIN_JPG_FILE,
            COVER_MAIN_AVIF_FILE,
            "cover.jpg",
            "cover.jpeg",
            "cover.png",
            "cover.webp"
        );
        for (String name : preferred) {
            Path path = trackDir.resolve(name);
            if (Files.isRegularFile(path)) {
                return Optional.of(path);
            }
        }
        return findCachedFile(trackDir, "cover.");
    }

    /**
     * 生成主封面和缩略图两个版本，并同时尝试写出 avif 与 jpg。
     */
    private boolean ensureCoverVariants(Path trackDir, Path sourceFile) {
        if (trackDir == null || sourceFile == null || !Files.isRegularFile(sourceFile)) {
            return false;
        }
        try {
            Files.createDirectories(trackDir);
            Path mainJpg = trackDir.resolve(COVER_MAIN_JPG_FILE);
            Path thumbJpg = trackDir.resolve(COVER_THUMB_JPG_FILE);
            boolean mainOk = transcodeCoverVariant(sourceFile, mainJpg, coverMainSize, false);
            boolean thumbOk = transcodeCoverVariant(sourceFile, thumbJpg, coverThumbSize, true);
            if (!mainOk || !thumbOk) {
                return false;
            }
            // avif 作为优先格式，失败时仍保留 jpg 兜底，避免页面空图。
            transcodeCoverVariant(sourceFile, trackDir.resolve(COVER_MAIN_AVIF_FILE), coverMainSize, false, true);
            transcodeCoverVariant(sourceFile, trackDir.resolve(COVER_THUMB_AVIF_FILE), coverThumbSize, true, true);
            cleanupLegacyCoverFiles(trackDir);
            return true;
        } catch (Exception ex) {
            log.warn("Failed to generate cover variants from {}", sourceFile, ex);
            return false;
        }
    }

    private void cleanupLegacyCoverFiles(Path trackDir) {
        if (trackDir == null || !Files.isDirectory(trackDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(trackDir)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString();
                if (!name.startsWith("cover")) {
                    continue;
                }
                if (name.equals(COVER_MAIN_JPG_FILE)
                    || name.equals(COVER_MAIN_AVIF_FILE)
                    || name.equals(COVER_THUMB_JPG_FILE)
                    || name.equals(COVER_THUMB_AVIF_FILE)) {
                    continue;
                }
                Files.deleteIfExists(path);
            }
        } catch (IOException ex) {
            log.debug("Failed to clean legacy cover files in {}", trackDir, ex);
        }
    }

    private boolean transcodeCoverVariant(Path sourceFile, Path targetFile, int size, boolean thumb) {
        return transcodeCoverVariant(sourceFile, targetFile, size, thumb, false);
    }

    private boolean transcodeCoverVariant(Path sourceFile, Path targetFile, int size, boolean thumb, boolean bestEffort) {
        if (sourceFile != null
            && targetFile != null
            && sourceFile.toAbsolutePath().normalize().equals(targetFile.toAbsolutePath().normalize())
            && Files.isRegularFile(targetFile)) {
            return true;
        }
        String name = targetFile == null ? "" : targetFile.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean avif = name.endsWith(".avif");
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-y");
        command.add("-loglevel");
        command.add("error");
        command.add("-i");
        command.add(sourceFile.toAbsolutePath().normalize().toString());
        command.add("-map_metadata");
        command.add("-1");
        command.add("-vf");
        command.add("scale=" + size + ":" + size + ":force_original_aspect_ratio=increase,crop=" + size + ":" + size);
        command.add("-frames:v");
        command.add("1");
        if (avif) {
            command.add("-c:v");
            command.add("libaom-av1");
            command.add("-still-picture");
            command.add("1");
            command.add("-cpu-used");
            command.add("6");
            command.add("-crf");
            command.add(thumb ? "36" : "33");
            command.add("-b:v");
            command.add("0");
        } else {
            command.add("-c:v");
            command.add("mjpeg");
            command.add("-q:v");
            command.add(thumb ? "5" : "4");
        }
        command.add(targetFile.toAbsolutePath().normalize().toString());
        if (runProcess(command, IMAGE_TRANSCODE_TIMEOUT_MS)) {
            return Files.isRegularFile(targetFile);
        }
        if (!bestEffort) {
            log.warn("Failed to transcode cover variant {}", targetFile);
        }
        return false;
    }

    private boolean runProcess(List<String> command, long timeoutMs) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            boolean finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean acceptsAvif(String acceptHeader) {
        if (isBlank(acceptHeader)) {
            return false;
        }
        String lower = acceptHeader.toLowerCase(Locale.ROOT);
        return lower.contains("image/avif");
    }

    private String normalizeCoverVariant(String variant) {
        if (variant == null || variant.isBlank()) {
            return COVER_VARIANT_COVER;
        }
        String normalized = variant.trim().toLowerCase(Locale.ROOT);
        if (COVER_VARIANT_THUMB.equals(normalized)) {
            return COVER_VARIANT_THUMB;
        }
        return COVER_VARIANT_COVER;
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
        if (lowerType.contains("avif")) {
            return ".avif";
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
        for (String ext : List.of(".jpg", ".jpeg", ".png", ".webp", ".avif", ".mp3", ".ogg", ".webm", ".m4a", ".mp4")) {
            if (lower.endsWith(ext)) {
                return ext;
            }
        }
        return "";
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
        return "/internal/media/cover/" + sanitizeSegment(trackId);
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

    private record ProbeDecision(boolean available, long expiresAtMillis) {
    }
}
