package pub.longyi.ts3audiobot.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import pub.longyi.ts3audiobot.search.SearchModels.SearchItem;
import pub.longyi.ts3audiobot.search.SearchModels.SearchPage;
import pub.longyi.ts3audiobot.util.RuntimeToolPathResolver;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
public abstract class YtDlpSearchProvider implements SearchProvider {
    private static final long PROCESS_TIMEOUT_MS = 25_000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String source;
    private final String configuredCommand;
    private final Path tempDirPath;
    private final Path cacheDirPath;

    protected YtDlpSearchProvider(String source, String command, Path tempDirPath, Path cacheDirPath) {
        this.source = source;
        this.configuredCommand = command;
        this.tempDirPath = tempDirPath;
        this.cacheDirPath = cacheDirPath;
    }

    @Override
    public String source() {
        return source;
    }

    protected String command() {
        return RuntimeToolPathResolver.resolveYtDlpCommand(configuredCommand);
    }

    @Override
    public boolean requiresLogin() {
        return false;
    }

    @Override
    public boolean supportsPlaylists() {
        return false;
    }

    @Override
    public LoginStartResult startLogin(LoginRequest request) {
        return new LoginStartResult("", "", "", "", 0, "无需登录");
    }

    @Override
    public LoginPollResult pollLogin(LoginPollRequest request) {
        return new LoginPollResult(LoginStatus.ERROR, "无需登录", request.payload(), null);
    }

    @Override
    public SearchPage search(SearchRequest request) {
        String query = request.query();
        String command = command();
        if (command == null || command.isBlank() || query == null || query.isBlank()) {
            return new SearchPage(List.of(), request.page(), request.pageSize(), 0);
        }
        int limit = Math.max(1, request.page() * request.pageSize());
        String target = searchPrefix() + limit + ":" + query.trim();
        List<String> output = runCommand(List.of(
            command,
            "-J",
            "--flat-playlist",
            "--skip-download",
            "--no-warnings",
            "--cache-dir",
            cacheDir().toString(),
            "--encoding",
            "UTF-8",
            target
        ));
        if (output.isEmpty()) {
            return new SearchPage(List.of(), request.page(), request.pageSize(), 0);
        }
        String json = String.join("\n", output);
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode entries = root.path("entries");
            if (!entries.isArray()) {
                return new SearchPage(List.of(), request.page(), request.pageSize(), 0);
            }
            List<SearchItem> items = new ArrayList<>();
            int start = (request.page() - 1) * request.pageSize();
            int end = start + request.pageSize();
            int index = 0;
            for (JsonNode entry : entries) {
                if (entry == null || entry.isNull()) {
                    index++;
                    continue;
                }
                if (index >= end) {
                    break;
                }
                if (index >= start) {
                    String id = text(entry, "id");
                    String title = text(entry, "title");
                    String uploader = text(entry, "uploader");
                    String channel = text(entry, "channel");
                    String artist = !uploader.isBlank() ? uploader : channel;
                    String thumbnail = extractThumbnail(entry);
                    long durationMs = entry.path("duration").asLong(0) * 1000L;
                    Long playCount = entry.has("view_count") ? entry.path("view_count").asLong() : null;
                    String pageUrl = buildPageUrl(id);
                    String uid = source.toLowerCase(Locale.ROOT) + ":" + id;
                    items.add(new SearchItem(
                        uid,
                        id,
                        title.isBlank() ? query : title,
                        artist,
                        thumbnail,
                        durationMs,
                        playCount,
                        pageUrl,
                        source,
                        null,
                        ""
                    ));
                }
                index++;
            }
            return new SearchPage(items, request.page(), request.pageSize(), null);
        } catch (Exception ex) {
            log.warn("[Search:{}] failed to parse yt-dlp result", source, ex);
            return new SearchPage(List.of(), request.page(), request.pageSize(), 0);
        }
    }

    @Override
    public SearchModels.PlaylistPage listPlaylists(PlaylistRequest request) {
        return new SearchModels.PlaylistPage(List.of(), request.page(), request.pageSize(), 0);
    }

    @Override
    public SearchModels.PlaylistTrackPage listPlaylistTracks(PlaylistTracksRequest request) {
        return new SearchModels.PlaylistTrackPage(List.of(), request.page(), request.pageSize(), 0);
    }

    protected abstract String searchPrefix();

    protected abstract String buildPageUrl(String id);

    protected List<String> runCommand(List<String> args) {
        return runCommand(args, PROCESS_TIMEOUT_MS, true);
    }

    protected List<String> runCommand(List<String> args, long timeoutMs) {
        return runCommand(args, timeoutMs, true);
    }

    protected List<String> runCommand(List<String> args, long timeoutMs, boolean logFailure) {
        List<String> lines = java.util.Collections.synchronizedList(new ArrayList<>());
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(true);
        Path tmpDir = tempDir();
        if (tmpDir != null) {
            builder.environment().put("TMP", tmpDir.toString());
            builder.environment().put("TEMP", tmpDir.toString());
        }
        try {
            Process process = builder.start();
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                } catch (Exception ex) {
                    if (logFailure) {
                        log.warn("[Search:{}] yt-dlp output read failed", source, ex);
                    }
                }
            }, "yt-dlp-reader-" + source);
            readerThread.setDaemon(true);
            readerThread.start();

            boolean finished;
            try {
                finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return List.of();
            }
            if (!finished) {
                process.destroyForcibly();
                if (logFailure) {
                    log.warn("[Search:{}] yt-dlp command timeout", source);
                }
            }
            try {
                readerThread.join(1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            if (process.exitValue() != 0 && logFailure) {
                log.warn("[Search:{}] yt-dlp exit {}", source, process.exitValue());
            }
        } catch (Exception ex) {
            if (logFailure && !(ex instanceof InterruptedException)) {
                log.warn("[Search:{}] yt-dlp command failed", source, ex);
            }
            return List.of();
        }
        return lines;
    }

    protected Path tempDir() {
        Path path = tempDirPath == null ? Paths.get("data", "yt-dlp-tmp") : tempDirPath;
        try {
            Files.createDirectories(path);
            return path.toAbsolutePath();
        } catch (Exception ex) {
            log.warn("[Search:{}] failed to create yt-dlp temp dir", source, ex);
            return null;
        }
    }

    protected Path cacheDir() {
        Path path = cacheDirPath == null ? Paths.get("data", "yt-dlp-cache") : cacheDirPath;
        try {
            Files.createDirectories(path);
            return path.toAbsolutePath();
        } catch (Exception ex) {
            log.warn("[Search:{}] failed to create yt-dlp cache dir", source, ex);
            return Paths.get("").toAbsolutePath();
        }
    }

    protected String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return "";
        }
        JsonNode value = node.get(field);
        return value == null ? "" : value.asText("");
    }

    protected String extractThumbnail(JsonNode entry) {
        if (entry == null || entry.isNull()) {
            return "";
        }
        String direct = normalizeThumbnailUrl(text(entry, "thumbnail"));
        if (!direct.isBlank()) {
            return direct;
        }
        JsonNode thumbnailNode = entry.get("thumbnail");
        if (thumbnailNode != null && thumbnailNode.isObject()) {
            String objectUrl = normalizeThumbnailUrl(thumbnailNode.path("url").asText(""));
            if (!objectUrl.isBlank()) {
                return objectUrl;
            }
        }
        JsonNode thumbnails = entry.get("thumbnails");
        if (thumbnails == null || thumbnails.isMissingNode()) {
            thumbnails = thumbnailNode;
        }
        if (thumbnails != null && thumbnails.isArray()) {
            String best = "";
            for (JsonNode thumbnail : thumbnails) {
                String url = normalizeThumbnailUrl(thumbnail.path("url").asText(""));
                if (!url.isBlank()) {
                    best = url;
                }
            }
            return best;
        }
        return "";
    }

    protected String normalizeThumbnailUrl(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("//")) {
            return "https:" + trimmed;
        }
        return trimmed;
    }
}

