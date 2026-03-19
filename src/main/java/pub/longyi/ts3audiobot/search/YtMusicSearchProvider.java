package pub.longyi.ts3audiobot.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.search.SearchModels.SearchItem;
import pub.longyi.ts3audiobot.search.SearchModels.SearchPage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public final class YtMusicSearchProvider extends YtDlpSearchProvider implements SearchDetailProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
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
        int start = (request.page() - 1) * request.pageSize() + 1;
        int end = Math.max(start, request.page() * request.pageSize());
        String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        String target = "https://music.youtube.com/search?q=" + encoded;

        List<SearchItem> baseItems = runAndParse(
            buildFlatArgs(cmd, target, start, end),
            FLAT_TIMEOUT_MS,
            query,
            FLAT_RETRY
        );
        if (baseItems.isEmpty()) {
            return new SearchPage(List.of(), request.page(), request.pageSize(), 0);
        }

        if (request.pageSize() > FULL_DETAIL_THRESHOLD) {
            return new SearchPage(baseItems, request.page(), request.pageSize(), null);
        }

        List<SearchItem> fullItems = runAndParse(
            buildFullArgs(cmd, target, start, end),
            resolveFullTimeoutMs(request.pageSize()),
            query,
            FULL_RETRY
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
        int start = (request.page() - 1) * request.pageSize() + 1;
        int end = Math.max(start, request.page() * request.pageSize());
        String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        String target = "https://music.youtube.com/search?q=" + encoded;

        List<SearchItem> baseItems = runAndParse(
            buildFlatArgs(cmd, target, start, end),
            FLAT_TIMEOUT_MS,
            query,
            FLAT_RETRY
        );
        if (baseItems.isEmpty()) {
            return new SearchPage(List.of(), request.page(), request.pageSize(), 0);
        }

        List<SearchItem> enriched = enrichDetailSequential(cmd, target, start, end, query, request.pageSize());
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

    private SearchItem mergeItem(SearchItem base, SearchItem detail) {
        String title = detail.title() != null && !detail.title().isBlank() ? detail.title() : base.title();
        String artist = detail.artist() != null && !detail.artist().isBlank() ? detail.artist() : base.artist();
        String cover = detail.coverUrl() != null && !detail.coverUrl().isBlank() ? detail.coverUrl() : base.coverUrl();
        long duration = detail.durationMs() > 0 ? detail.durationMs() : base.durationMs();
        Long playCount = detail.playCount() != null && detail.playCount() > 0 ? detail.playCount() : base.playCount();
        return new SearchItem(
            base.uid(),
            base.id(),
            title == null || title.isBlank() ? base.title() : title,
            artist,
            cover,
            duration,
            playCount,
            base.pageUrl(),
            base.source()
        );
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
        String thumbnail = text(entry, "thumbnail");
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
            source()
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
