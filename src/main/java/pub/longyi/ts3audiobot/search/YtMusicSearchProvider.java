package pub.longyi.ts3audiobot.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.search.SearchModels.SearchItem;
import pub.longyi.ts3audiobot.search.SearchModels.SearchPage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public final class YtMusicSearchProvider extends YtDlpSearchProvider implements SearchDetailProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long RAW_TIMEOUT_MS = 15_000L;
    private static final int RAW_RETRY = 1;
    private static final String SEARCH_DUMP_GLOB = "*youtubei_v1_searchprettyPrint=false.dump";
    private static final long FLAT_TIMEOUT_MS = 12_000L;
    private static final long FULL_TIMEOUT_BASE_MS = 8_000L;
    private static final long FULL_TIMEOUT_PER_ITEM_MS = 1_500L;
    private static final long FULL_TIMEOUT_MAX_MS = 25_000L;
    private static final int FULL_DETAIL_THRESHOLD = 10;
    private static final int FLAT_RETRY = 3;
    private static final int FULL_RETRY = 1;
    private static final int DETAIL_CHUNK = 8;
    private static final int DETAIL_RETRY = 1;
    private static final long DETAIL_BUDGET_BASE_MS = 20_000L;
    private static final long DETAIL_BUDGET_PER_ITEM_MS = 1_500L;
    private static final long DETAIL_BUDGET_MAX_MS = 90_000L;

    public YtMusicSearchProvider(ConfigService configService) {
        super("ytmusic", configService.get().resolvers.external.ytmusic);
    }

    @Override
    public SearchPage search(SearchRequest request) {
        String query = request.query();
        String cmd = command();
        if (cmd == null || cmd.isBlank() || query == null || query.isBlank()) {
            return new SearchPage(List.of(), request.page(), request.pageSize(), 0);
        }
        SearchPage rawPage = searchFromRawDump(cmd, query, request.page(), request.pageSize());
        if (shouldUseRawItems(rawPage.items(), request.pageSize())) {
            return rawPage;
        }

        int start = (request.page() - 1) * request.pageSize() + 1;
        int end = Math.max(start, request.page() * request.pageSize());
        String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        String target = "https://music.youtube.com/search?q=" + encoded;

        List<SearchItem> baseItems = preferBetterItems(
            runAndParse(
                buildFlatArgs(cmd, target, start, end),
                FLAT_TIMEOUT_MS,
                query,
                FLAT_RETRY
            ),
            runSearchPrefix(cmd, query, request.page(), request.pageSize(), FLAT_TIMEOUT_MS, FLAT_RETRY, true)
        );
        if (baseItems.isEmpty()) {
            return new SearchPage(List.of(), request.page(), request.pageSize(), 0);
        }

        if (request.pageSize() > FULL_DETAIL_THRESHOLD || !shouldAttemptFullDetail(baseItems, request.pageSize())) {
            return new SearchPage(baseItems, request.page(), request.pageSize(), null);
        }

        List<SearchItem> fullItems = preferBetterItems(
            runAndParse(
                buildFullArgs(cmd, target, start, end),
                resolveFullTimeoutMs(request.pageSize()),
                query,
                FULL_RETRY
            ),
            runSearchPrefix(cmd, query, request.page(), request.pageSize(), resolveFullTimeoutMs(request.pageSize()), FULL_RETRY, false)
        );
        if (!fullItems.isEmpty()) {
            return new SearchPage(mergeById(baseItems, fullItems), request.page(), request.pageSize(), null);
        }

        return new SearchPage(baseItems, request.page(), request.pageSize(), null);
    }

    @Override
    public SearchPage searchDetail(SearchRequest request) {
        String query = request.query();
        String cmd = command();
        if (cmd == null || cmd.isBlank() || query == null || query.isBlank()) {
            return new SearchPage(List.of(), request.page(), request.pageSize(), 0);
        }
        SearchPage rawPage = searchFromRawDump(cmd, query, request.page(), request.pageSize());
        if (shouldUseRawItems(rawPage.items(), request.pageSize())) {
            return rawPage;
        }

        int start = (request.page() - 1) * request.pageSize() + 1;
        int end = Math.max(start, request.page() * request.pageSize());
        String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        String target = "https://music.youtube.com/search?q=" + encoded;

        List<SearchItem> baseItems = preferBetterItems(
            runAndParse(
                buildFlatArgs(cmd, target, start, end),
                FLAT_TIMEOUT_MS,
                query,
                FLAT_RETRY
            ),
            runSearchPrefix(cmd, query, request.page(), request.pageSize(), FLAT_TIMEOUT_MS, FLAT_RETRY, true)
        );
        if (baseItems.isEmpty()) {
            return new SearchPage(List.of(), request.page(), request.pageSize(), 0);
        }
        if (!shouldAttemptFullDetail(baseItems, request.pageSize())) {
            return new SearchPage(baseItems, request.page(), request.pageSize(), null);
        }

        List<SearchItem> enriched = preferBetterItems(
            enrichDetailSequential(cmd, target, start, end, query, request.pageSize()),
            runSearchPrefix(cmd, query, request.page(), request.pageSize(), resolveFullTimeoutMs(request.pageSize()), FULL_RETRY, false)
        );
        if (!enriched.isEmpty()) {
            return new SearchPage(mergeById(baseItems, enriched), request.page(), request.pageSize(), null);
        }

        return new SearchPage(baseItems, request.page(), request.pageSize(), null);
    }

    @Override
    protected String searchPrefix() {
        return "ytmsearch";
    }

    @Override
    protected String buildPageUrl(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return "https://music.youtube.com/watch?v=" + id;
    }

    private SearchPage searchFromRawDump(String cmd, String query, int page, int pageSize) {
        int start = (page - 1) * pageSize + 1;
        int end = Math.max(start, page * pageSize);
        String target = "https://music.youtube.com/search?q=" + urlEncode(query);
        for (int attempt = 0; attempt < RAW_RETRY; attempt++) {
            Path workDir = createSearchWorkDir();
            if (workDir == null) {
                break;
            }
            try {
                runCommandWithWorkingDir(buildRawArgs(cmd, target, start, end), RAW_TIMEOUT_MS, workDir);
                String rawJson = readSearchDump(workDir);
                List<SearchItem> items = parseSearchDump(rawJson, query);
                if (!items.isEmpty()) {
                    return new SearchPage(items, page, pageSize, null);
                }
            } finally {
                deleteQuietly(workDir);
            }
        }
        return new SearchPage(List.of(), page, pageSize, 0);
    }

    static boolean shouldUseRawItems(List<SearchItem> items, int pageSize) {
        if (items == null || items.isEmpty()) {
            return false;
        }
        if (pageSize > FULL_DETAIL_THRESHOLD || items.size() < Math.max(1, pageSize)) {
            return false;
        }
        int watchCount = 0;
        int durationCount = 0;
        for (SearchItem item : items) {
            if (item == null) {
                continue;
            }
            String pageUrl = item.pageUrl();
            if (pageUrl != null && pageUrl.contains("/watch?v=")) {
                watchCount++;
            }
            if (item.durationMs() > 0) {
                durationCount++;
            }
        }
        int targetPlayable = Math.max(1, Math.min(pageSize, 3));
        return watchCount >= targetPlayable && durationCount >= targetPlayable;
    }

    static boolean shouldAttemptFullDetail(List<SearchItem> items, int pageSize) {
        if (items == null || items.isEmpty()) {
            return false;
        }
        int candidateCount = 0;
        for (SearchItem item : items) {
            if (item == null) {
                continue;
            }
            String id = item.id();
            if (id != null && id.matches("[A-Za-z0-9_-]{11}")) {
                candidateCount++;
            }
        }
        int threshold = Math.max(2, Math.min(pageSize, 3));
        return candidateCount >= threshold;
    }

    static List<SearchItem> preferBetterItems(List<SearchItem> primary, List<SearchItem> candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return primary == null ? List.of() : primary;
        }
        if (primary == null || primary.isEmpty()) {
            return candidate;
        }
        if (candidate.size() > primary.size()) {
            return candidate;
        }
        if (candidate.size() < primary.size()) {
            return primary;
        }
        return scoreItems(candidate) > scoreItems(primary) ? candidate : primary;
    }
    static List<SearchItem> parseSearchDump(String rawJson, String fallbackQuery) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(rawJson);
            JsonNode tabs = root.path("contents")
                .path("tabbedSearchResultsRenderer")
                .path("tabs");
            if (!tabs.isArray()) {
                return List.of();
            }
            List<SearchItem> items = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode tab : tabs) {
                JsonNode contents = tab.path("tabRenderer")
                    .path("content")
                    .path("sectionListRenderer")
                    .path("contents");
                if (!contents.isArray()) {
                    continue;
                }
                for (JsonNode section : contents) {
                    JsonNode shelfItems = section.path("musicShelfRenderer").path("contents");
                    if (!shelfItems.isArray()) {
                        continue;
                    }
                    for (JsonNode itemNode : shelfItems) {
                        JsonNode renderer = itemNode.path("musicResponsiveListItemRenderer");
                        if (renderer.isMissingNode() || renderer.isNull()) {
                            continue;
                        }
                        SearchItem item = toSearchItem(renderer, fallbackQuery);
                        if (item == null) {
                            continue;
                        }
                        if (seen.add(item.uid())) {
                            items.add(item);
                        }
                    }
                }
            }
            return items;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private long resolveFullTimeoutMs(int pageSize) {
        int size = Math.max(1, pageSize);
        long timeout = FULL_TIMEOUT_BASE_MS + FULL_TIMEOUT_PER_ITEM_MS * size;
        return Math.min(FULL_TIMEOUT_MAX_MS, timeout);
    }

    private long resolveDetailBudgetMs(int pageSize) {
        int size = Math.max(1, pageSize);
        long budget = DETAIL_BUDGET_BASE_MS + DETAIL_BUDGET_PER_ITEM_MS * size;
        return Math.min(DETAIL_BUDGET_MAX_MS, budget);
    }

    private List<SearchItem> runSearchPrefix(
        String cmd,
        String query,
        int page,
        int pageSize,
        long timeoutMs,
        int attempts,
        boolean flat
    ) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, pageSize);
        int limit = safePage * safeSize;
        List<SearchItem> items = runAndParse(
            buildPrefixArgs(cmd, query, limit, flat),
            timeoutMs,
            query,
            attempts
        );
        return slicePage(items, safePage, safeSize);
    }

    private List<String> buildRawArgs(String cmd, String target, int start, int end) {
        List<String> args = new ArrayList<>();
        args.add(cmd);
        args.add("-J");
        args.add("--ignore-config");
        args.add("--flat-playlist");
        args.add("--skip-download");
        args.add("--no-warnings");
        args.add("--write-pages");
        args.add("--cache-dir");
        args.add(cacheDir().toString());
        args.add("--playlist-items");
        args.add(start + "-" + end);
        args.add("--encoding");
        args.add("UTF-8");
        args.add(target);
        return args;
    }

    private List<String> buildPrefixArgs(String cmd, String query, int limit, boolean flat) {
        List<String> args = new ArrayList<>();
        args.add(cmd);
        args.add("-J");
        args.add("--ignore-config");
        if (flat) {
            args.add("--flat-playlist");
        }
        args.add("--skip-download");
        args.add("--no-warnings");
        args.add("--cache-dir");
        args.add(cacheDir().toString());
        args.add("--encoding");
        args.add("UTF-8");
        args.add("ytsearch" + Math.max(1, limit) + ":" + query.trim());
        return args;
    }

    private List<String> buildFullArgs(String cmd, String target, int start, int end) {
        List<String> args = new ArrayList<>();
        args.add(cmd);
        args.add("-J");
        args.add("--ignore-config");
        args.add("--skip-download");
        args.add("--no-warnings");
        args.add("--cache-dir");
        args.add(cacheDir().toString());
        args.add("--playlist-items");
        args.add(start + "-" + end);
        args.add("--encoding");
        args.add("UTF-8");
        args.add(target);
        return args;
    }

    private List<String> buildFlatArgs(String cmd, String target, int start, int end) {
        List<String> args = new ArrayList<>();
        args.add(cmd);
        args.add("-J");
        args.add("--ignore-config");
        args.add("--flat-playlist");
        args.add("--skip-download");
        args.add("--no-warnings");
        args.add("--cache-dir");
        args.add(cacheDir().toString());
        args.add("--playlist-items");
        args.add(start + "-" + end);
        args.add("--encoding");
        args.add("UTF-8");
        args.add(target);
        return args;
    }

    private List<SearchItem> runAndParse(List<String> args, long timeoutMs, String query, int attempts) {
        int retry = Math.max(1, attempts);
        for (int index = 0; index < retry; index++) {
            List<String> output = runCommand(args, timeoutMs, false);
            List<SearchItem> items = parseItems(output, query);
            if (!items.isEmpty()) {
                return items;
            }
        }
        return List.of();
    }

    private List<SearchItem> enrichDetailSequential(
        String cmd,
        String target,
        int start,
        int end,
        String query,
        int pageSize
    ) {
        List<int[]> ranges = buildRanges(start, end, DETAIL_CHUNK);
        if (ranges.isEmpty()) {
            return List.of();
        }
        long budgetMs = resolveDetailBudgetMs(pageSize);
        long deadline = System.currentTimeMillis() + budgetMs;
        List<SearchItem> enriched = new ArrayList<>();
        for (int[] range : ranges) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            int rangeStart = range[0];
            int rangeEnd = range[1];
            int size = Math.max(1, rangeEnd - rangeStart + 1);
            long timeoutMs = resolveFullTimeoutMs(size);
            long effectiveTimeout = Math.min(timeoutMs, Math.max(5_000L, remaining));
            List<SearchItem> part = runAndParse(
                buildFullArgs(cmd, target, rangeStart, rangeEnd),
                effectiveTimeout,
                query,
                DETAIL_RETRY
            );
            if (!part.isEmpty()) {
                enriched.addAll(part);
            }
        }
        return enriched;
    }

    private List<int[]> buildRanges(int start, int end, int chunkSize) {
        List<int[]> ranges = new ArrayList<>();
        if (end < start) {
            return ranges;
        }
        int size = Math.max(1, chunkSize);
        int current = start;
        while (current <= end) {
            int chunkEnd = Math.min(end, current + size - 1);
            ranges.add(new int[] { current, chunkEnd });
            current = chunkEnd + 1;
        }
        return ranges;
    }

    private List<SearchItem> mergeById(List<SearchItem> baseItems, List<SearchItem> enrichedItems) {
        if (enrichedItems == null || enrichedItems.isEmpty()) {
            return baseItems;
        }
        Map<String, SearchItem> byId = new HashMap<>();
        for (SearchItem item : enrichedItems) {
            if (item != null && item.id() != null && !item.id().isBlank()) {
                byId.put(item.id(), item);
            }
        }
        if (byId.isEmpty()) {
            return baseItems;
        }
        List<SearchItem> merged = new ArrayList<>(baseItems.size());
        for (SearchItem base : baseItems) {
            SearchItem detail = byId.get(base.id());
            merged.add(detail == null ? base : mergeItem(base, detail));
        }
        return merged;
    }

    private List<SearchItem> slicePage(List<SearchItem> items, int page, int pageSize) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        int startIndex = Math.max(0, (page - 1) * pageSize);
        if (startIndex >= items.size()) {
            return List.of();
        }
        int endIndex = Math.min(items.size(), startIndex + Math.max(1, pageSize));
        return new ArrayList<>(items.subList(startIndex, endIndex));
    }

    private SearchItem mergeItem(SearchItem base, SearchItem detail) {
        String title = detail.title() != null && !detail.title().isBlank() ? detail.title() : base.title();
        String artist = detail.artist() != null && !detail.artist().isBlank() ? detail.artist() : base.artist();
        String cover = detail.coverUrl() != null && !detail.coverUrl().isBlank() ? detail.coverUrl() : base.coverUrl();
        long duration = detail.durationMs() > 0 ? detail.durationMs() : base.durationMs();
        Long playCount = detail.playCount() != null && detail.playCount() > 0 ? detail.playCount() : base.playCount();
        Boolean vipRequired = detail.vipRequired() != null ? detail.vipRequired() : base.vipRequired();
        String vipHint = detail.vipHint() != null && !detail.vipHint().isBlank() ? detail.vipHint() : base.vipHint();
        return new SearchItem(
            base.uid(),
            base.id(),
            title == null || title.isBlank() ? base.title() : title,
            artist,
            cover,
            duration,
            playCount,
            base.pageUrl(),
            base.source(),
            vipRequired,
            vipHint == null ? "" : vipHint
        );
    }

    private static int scoreItems(List<SearchItem> items) {
        int score = 0;
        for (SearchItem item : items) {
            if (item == null) {
                continue;
            }
            if (item.id() != null && !item.id().isBlank()) {
                score += 2;
            }
            if (item.pageUrl() != null && item.pageUrl().contains("/watch?v=")) {
                score += 2;
            }
            if (item.artist() != null && !item.artist().isBlank()) {
                score += 1;
            }
            if (item.durationMs() > 0) {
                score += 1;
            }
            if (item.playCount() != null && item.playCount() > 0) {
                score += 1;
            }
        }
        return score;
    }

    private Path createSearchWorkDir() {
        Path base = tempDir();
        if (base == null) {
            return null;
        }
        Path dir = base.resolve("ytmusic-search-" + UUID.randomUUID());
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (Exception ex) {
            log.warn("[Search:{}] failed to create search temp dir", source(), ex);
            return null;
        }
    }

    private void runCommandWithWorkingDir(List<String> args, long timeoutMs, Path workDir) {
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(true);
        if (workDir != null) {
            builder.directory(workDir.toFile());
        }
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
                    while (reader.readLine() != null) {
                        // Drain merged output so yt-dlp cannot block on a full pipe.
                    }
                } catch (Exception ignored) {
                }
            }, "ytmusic-search-reader");
            readerThread.setDaemon(true);
            readerThread.start();
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[Search:{}] yt-dlp raw search timeout", source());
            }
            readerThread.join(1000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.warn("[Search:{}] yt-dlp raw search failed", source(), ex);
        }
    }

    private String readSearchDump(Path workDir) {
        if (workDir == null || !Files.isDirectory(workDir)) {
            return "";
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(workDir, SEARCH_DUMP_GLOB)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    return Files.readString(file, StandardCharsets.UTF_8);
                }
            }
        } catch (Exception ex) {
            log.warn("[Search:{}] failed to read yt-dlp search dump", source(), ex);
        }
        return "";
    }

    private void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            List<Path> paths = Files.walk(root).sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {
        }
    }

    private List<SearchItem> parseItems(List<String> output, String query) {
        if (output == null || output.isEmpty()) {
            return List.of();
        }
        String json = extractJson(String.join("\n", output));
        if (json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode entries = root.path("entries");
            List<SearchItem> items = new ArrayList<>();
            if (entries.isArray()) {
                for (JsonNode entry : entries) {
                    addItem(items, entry, query);
                }
                return items;
            }
            if (root.isObject()) {
                addItem(items, root, query);
                return items;
            }
            return List.of();
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return "";
        }
        return raw.substring(start, end + 1);
    }

    private void addItem(List<SearchItem> items, JsonNode entry, String query) {
        if (entry == null || entry.isNull()) {
            return;
        }
        String id = text(entry, "id");
        String title = text(entry, "title");
        String track = text(entry, "track");
        if (!track.isBlank()) {
            title = track;
        }
        String artist = extractArtist(entry);
        String typeLabel = extractTypeLabel(entry);
        DescriptionInfo descInfo = parseDescription(entry);
        if (descInfo != null) {
            if (track.isBlank() && !descInfo.track.isBlank()) {
                track = descInfo.track;
                title = track;
            }
            if (artist.isBlank() && !descInfo.artist.isBlank()) {
                artist = descInfo.artist;
            }
            if (typeLabel.isBlank() && !descInfo.typeLabel.isBlank()) {
                typeLabel = descInfo.typeLabel;
            }
        }
        if (typeLabel.isBlank() && !track.isBlank()) {
            typeLabel = "\u6B4C\u66F2";
        }
        if (!typeLabel.isBlank()) {
            artist = artist.isBlank() ? typeLabel : typeLabel + " \u00B7 " + artist;
        }
        String thumbnail = extractThumbnail(entry);
        long durationMs = entry.path("duration").asLong(0) * 1000L;
        Long playCount = entry.has("view_count") ? entry.path("view_count").asLong() : null;
        String pageUrl = buildPageUrl(id);
        String uid = source().toLowerCase(Locale.ROOT) + ":" + id;
        items.add(new SearchItem(
            uid,
            id,
            title.isBlank() ? query : title,
            artist,
            thumbnail,
            durationMs,
            playCount,
            pageUrl,
            source(),
            null,
            ""
        ));
    }

    private String extractArtist(JsonNode entry) {
        String artist = text(entry, "artist");
        if (!artist.isBlank()) {
            return artist;
        }
        JsonNode artists = entry.path("artists");
        if (artists.isArray()) {
            List<String> names = new ArrayList<>();
            for (JsonNode node : artists) {
                String name = node.isTextual() ? node.asText("") : text(node, "name");
                if (name != null && !name.isBlank()) {
                    names.add(name.trim());
                }
            }
            if (!names.isEmpty()) {
                return String.join(", ", names);
            }
        }
        String uploader = text(entry, "uploader");
        if (!uploader.isBlank()) {
            return uploader;
        }
        return text(entry, "channel");
    }

    private String extractTypeLabel(JsonNode entry) {
        String type = text(entry, "result_type");
        if (type.isBlank()) {
            type = text(entry, "type");
        }
        if (type.isBlank()) {
            type = text(entry, "category");
        }
        if (type.isBlank()) {
            return "";
        }
        return mapTypeLabel(type);
    }

    private DescriptionInfo parseDescription(JsonNode entry) {
        String desc = text(entry, "description");
        if (desc.isBlank()) {
            desc = text(entry, "subtitle");
        }
        if (desc.isBlank()) {
            return null;
        }
        String normalized = desc.replace("\u2022", "\u00B7");
        String[] lines = normalized.split("\\r?\\n");
        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("provided to youtube")) {
                continue;
            }
            int dotIndex = line.indexOf('\u00B7');
            if (dotIndex > 0) {
                String left = line.substring(0, dotIndex).trim();
                String right = line.substring(dotIndex + 1).trim();
                String typeLabel = mapTypeLabel(left);
                if (!typeLabel.isBlank()) {
                    return new DescriptionInfo(typeLabel, right, "");
                }
                return new DescriptionInfo("\u6B4C\u66F2", right, left);
            }
        }
        return null;
    }

    private String mapTypeLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        switch (trimmed) {
            case "\u6B4C\u66F2":
                return "\u6B4C\u66F2";
            case "\u89C6\u9891":
                return "\u89C6\u9891";
            case "\u4E13\u8F91":
                return "\u4E13\u8F91";
            case "\u6B4C\u5355":
                return "\u6B4C\u5355";
            case "\u827A\u4EBA":
                return "\u827A\u4EBA";
            default:
                break;
        }
        String type = trimmed.toLowerCase(Locale.ROOT);
        return switch (type) {
            case "song", "music" -> "\u6B4C\u66F2";
            case "video", "mv" -> "\u89C6\u9891";
            case "album" -> "\u4E13\u8F91";
            case "playlist" -> "\u6B4C\u5355";
            case "artist", "profile", "channel" -> "\u827A\u4EBA";
            default -> "";
        };
    }

    private static SearchItem toSearchItem(JsonNode renderer, String fallbackQuery) {
        String videoId = firstNonBlank(
            findText(renderer, "navigationEndpoint", "watchEndpoint", "videoId"),
            findText(renderer, "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
                "playNavigationEndpoint", "watchEndpoint", "videoId"),
            findText(renderer, "playlistItemData", "videoId")
        );
        String playlistId = firstNonBlank(
            findText(renderer, "navigationEndpoint", "watchPlaylistEndpoint", "playlistId"),
            findText(renderer, "overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer",
                "playNavigationEndpoint", "watchPlaylistEndpoint", "playlistId")
        );
        String browseId = findText(renderer, "navigationEndpoint", "browseEndpoint", "browseId");
        String id = firstNonBlank(videoId, playlistId, browseId);
        if (id.isBlank()) {
            return null;
        }

        String title = extractRunsText(renderer.path("flexColumns").path(0)
            .path("musicResponsiveListItemFlexColumnRenderer")
            .path("text")
            .path("runs"));
        if (title.isBlank()) {
            title = fallbackQuery == null ? "" : fallbackQuery;
        }

        String meta = extractRunsText(renderer.path("flexColumns").path(1)
            .path("musicResponsiveListItemFlexColumnRenderer")
            .path("text")
            .path("runs"));
        meta = normalizeMeta(meta);

        String durationText = extractRunsText(renderer.path("fixedColumns").path(0)
            .path("musicResponsiveListItemFixedColumnRenderer")
            .path("text")
            .path("runs"));
        long durationMs = parseDurationMs(durationText);
        if (durationMs <= 0) {
            durationMs = parseDurationMsFromMeta(meta);
        }

        String coverUrl = findBestThumbnail(renderer.path("thumbnail")
            .path("musicThumbnailRenderer")
            .path("thumbnail")
            .path("thumbnails"));
        String pageUrl = buildItemUrl(videoId, playlistId, browseId);
        String uid = "ytmusic:" + id;
        return new SearchItem(
            uid,
            id,
            title,
            meta,
            coverUrl,
            durationMs,
            null,
            pageUrl,
            "ytmusic",
            null,
            ""
        );
    }

    private static String findText(JsonNode node, String... path) {
        JsonNode current = node;
        for (String field : path) {
            if (current == null) {
                return "";
            }
            current = current.path(field);
        }
        return current == null ? "" : current.asText("");
    }

    private static String extractRunsText(JsonNode runs) {
        if (!runs.isArray()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode run : runs) {
            String text = run.path("text").asText("");
            if (!text.isBlank()) {
                sb.append(text);
            }
        }
        return sb.toString();
    }

    private static String normalizeMeta(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.replace('\u2022', '\u00B7');
        normalized = normalized.replaceAll("\\s*\\u00B7\\s*", " \u00B7 ");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized;
    }

    private static long parseDurationMs(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        String text = raw.trim();
        if (!text.matches("\\d{1,2}:\\d{2}(?::\\d{2})?")) {
            return 0L;
        }
        String[] parts = text.split(":");
        long totalSeconds = 0L;
        for (String part : parts) {
            totalSeconds = totalSeconds * 60L + Long.parseLong(part);
        }
        return totalSeconds * 1000L;
    }

    private static long parseDurationMsFromMeta(String meta) {
        if (meta == null || meta.isBlank()) {
            return 0L;
        }
        String[] parts = meta.split("\\u00B7");
        for (int index = parts.length - 1; index >= 0; index--) {
            long durationMs = parseDurationMs(parts[index].trim());
            if (durationMs > 0) {
                return durationMs;
            }
        }
        return 0L;
    }

    private static String findBestThumbnail(JsonNode thumbnails) {
        if (!thumbnails.isArray()) {
            return "";
        }
        String best = "";
        for (JsonNode thumbnail : thumbnails) {
            String url = normalizeThumbnailUrlValue(thumbnail.path("url").asText(""));
            if (!url.isBlank()) {
                best = url;
            }
        }
        return best;
    }

    private static String normalizeThumbnailUrlValue(String url) {
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

    private static String buildItemUrl(String videoId, String playlistId, String browseId) {
        if (videoId != null && !videoId.isBlank()) {
            return "https://music.youtube.com/watch?v=" + videoId;
        }
        if (playlistId != null && !playlistId.isBlank()) {
            return "https://music.youtube.com/playlist?list=" + playlistId;
        }
        if (browseId != null && !browseId.isBlank()) {
            return "https://music.youtube.com/browse/" + browseId;
        }
        return "";
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value.trim(), StandardCharsets.UTF_8);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static final class DescriptionInfo {
        private final String typeLabel;
        private final String artist;
        private final String track;

        private DescriptionInfo(String typeLabel, String artist, String track) {
            this.typeLabel = typeLabel == null ? "" : typeLabel;
            this.artist = artist == null ? "" : artist;
            this.track = track == null ? "" : track;
        }
    }
}






