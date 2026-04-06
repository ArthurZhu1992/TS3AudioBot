package pub.longyi.ts3audiobot.web.internal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pub.longyi.ts3audiobot.bot.BotInstance;
import pub.longyi.ts3audiobot.bot.BotManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pub.longyi.ts3audiobot.media.TrackMediaService;
import pub.longyi.ts3audiobot.queue.QueueItem;
import pub.longyi.ts3audiobot.queue.QueueService;
import pub.longyi.ts3audiobot.queue.Track;
import pub.longyi.ts3audiobot.resolver.ResolverRegistry;
import pub.longyi.ts3audiobot.resolver.TrackResolver;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 InternalQueueController 相关功能。
 */


/**
 * InternalQueueController 相关功能。
 *
 * <p>职责：负责 InternalQueueController 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@RestController
@RequestMapping("/internal/queue")
public final class InternalQueueController {
    private static final Logger log = LoggerFactory.getLogger(InternalQueueController.class);
    private final QueueService queueService;
    private final ResolverRegistry resolverRegistry;
    private final TrackMediaService trackMediaService;
    private final BotManager botManager;

    /**
     * 创建 InternalQueueController 实例。
     * @param queueService 参数 queueService
     * @param resolverRegistry 参数 resolverRegistry
     */
    public InternalQueueController(
        QueueService queueService,
        ResolverRegistry resolverRegistry,
        TrackMediaService trackMediaService
    ) {
        this(queueService, resolverRegistry, trackMediaService, null);
    }

    @Autowired
    public InternalQueueController(
        QueueService queueService,
        ResolverRegistry resolverRegistry,
        TrackMediaService trackMediaService,
        BotManager botManager
    ) {
        this.queueService = queueService;
        this.resolverRegistry = resolverRegistry;
        this.trackMediaService = trackMediaService;
        this.botManager = botManager;
    }


    /**
     * 执行 list 操作。
     * @param botId 参数 botId
     * @return 返回值
     */
    @GetMapping("/{botId}")
    public List<QueueItem> list(@PathVariable String botId) {
        String playlistId = queueService.getActivePlaylist(botId);
        return prepareQueueForDisplay(botId, playlistId, queueService.rawList(botId, playlistId));
    }


    /**
     * 执行 listByPlaylist 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @return 返回值
     */
    @GetMapping("/{botId}/{playlistId}")
    public List<QueueItem> listByPlaylist(@PathVariable String botId, @PathVariable String playlistId) {
        return prepareQueueForDisplay(botId, playlistId, queueService.rawList(botId, playlistId));
    }

    @PostMapping("/{botId}/{playlistId}/items/{itemId}/refresh")
    public ResponseEntity<?> refreshItem(
        @PathVariable String botId,
        @PathVariable String playlistId,
        @PathVariable String itemId
    ) {
        QueueItem refreshed = queueService.refreshItem(botId, playlistId, itemId);
        if (refreshed == null) {
            return ResponseEntity.notFound().build();
        }
        QueueItem prepared = prepareQueueItemForDisplay(botId, playlistId, refreshed);
        return ResponseEntity.ok(prepared);
    }


    /**
     * 执行 add 操作。
     * @param botId 参数 botId
     * @param request 参数 request
     * @return 返回值
     */
    @PostMapping("/{botId}/add")
    public ResponseEntity<?> add(@PathVariable String botId, @RequestBody AddRequest request) {
        List<AddItemRequest> items = normalizeItems(request);
        if (items.isEmpty()) {
            return ResponseEntity.badRequest().body("Query is required");
        }
        if (items.size() == 1) {
            AddItemRequest item = items.get(0);
            Optional<Track> track = resolveTrack(item);
            if (track.isEmpty()) {
                return ResponseEntity.badRequest().body("No resolver could handle the query");
            }
            Track resolved = trackMediaService.prepareForQueue(applyTrackMetadata(item, applyWebsiteSourceType(item.query(), track.get())));
            return ResponseEntity.ok(queueService.add(botId, resolved, request.addedBy()));
        }
        BatchAddResponse response = addBatch(botId, null, items, request.addedBy());
        return ResponseEntity.ok(response);
    }


    /**
     * 执行 addByPlaylist 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @param request 参数 request
     * @return 返回值
     */
    @PostMapping("/{botId}/{playlistId}/add")
    public ResponseEntity<?> addByPlaylist(
        @PathVariable String botId,
        @PathVariable String playlistId,
        @RequestBody AddRequest request
    ) {
        List<AddItemRequest> items = normalizeItems(request);
        if (items.isEmpty()) {
            return ResponseEntity.badRequest().body("Query is required");
        }
        if (items.size() == 1) {
            AddItemRequest item = items.get(0);
            Optional<Track> track = resolveTrack(item);
            if (track.isEmpty()) {
                return ResponseEntity.badRequest().body("No resolver could handle the query");
            }
            Track resolved = trackMediaService.prepareForQueue(applyTrackMetadata(item, applyWebsiteSourceType(item.query(), track.get())));
            return ResponseEntity.ok(queueService.add(botId, playlistId, resolved, request.addedBy()));
        }
        BatchAddResponse response = addBatch(botId, playlistId, items, request.addedBy());
        return ResponseEntity.ok(response);
    }


    /**
     * 执行 clear 操作。
     * @param botId 参数 botId
     */
    @PostMapping("/{botId}/clear")
    public void clear(@PathVariable String botId) {
        List<QueueItem> items = queueService.rawList(botId);
        pausePlaybackForDeletionIfNeeded(botId, items);
        releasePlaybackForItems(botId, items);
        deleteQueueMedia(items);
        queueService.clear(botId);
    }


    /**
     * 执行 clearByPlaylist 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     */
    @PostMapping("/{botId}/{playlistId}/clear")
    public void clearByPlaylist(@PathVariable String botId, @PathVariable String playlistId) {
        List<QueueItem> items = queueService.rawList(botId, playlistId);
        pausePlaybackForDeletionIfNeeded(botId, items);
        releasePlaybackForItems(botId, items);
        deleteQueueMedia(items);
        queueService.clear(botId, playlistId);
    }


    /**
     * 执行 removeItem 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @param itemId 参数 itemId
     * @return 返回值
     */
    @DeleteMapping("/{botId}/{playlistId}/items/{itemId}")
    public ResponseEntity<?> removeItem(
        @PathVariable String botId,
        @PathVariable String playlistId,
        @PathVariable String itemId
    ) {
        Track targetTrack = findTrack(botId, playlistId, itemId);
        pausePlaybackForDeletionIfNeeded(botId, playlistId, itemId);
        boolean removed = queueService.removeItem(botId, playlistId, itemId);
        if (!removed) {
            return ResponseEntity.badRequest().body("Queue item delete failed");
        }
        releaseCurrentPlayback(botId, playlistId, itemId);
        trackMediaService.deleteTrackMedia(targetTrack);
        return ResponseEntity.ok().build();

    }


    /**
     * 执行 listPlaylists 操作。
     * @param botId 参数 botId
     * @return 返回值
     */
    @GetMapping("/{botId}/playlists")
    public PlaylistsResponse listPlaylists(@PathVariable String botId) {

        String active = queueService.getActivePlaylist(botId);
        List<PlaylistView> playlists = queueService.listPlaylists(botId).stream()
            .map(name -> new PlaylistView(name, name.equalsIgnoreCase(active)))
            .toList();
        return new PlaylistsResponse(active, playlists);
    }


    /**
     * 执行 createPlaylist 操作。
     * @param botId 参数 botId
     * @param request 参数 request
     * @return 返回值
     */
    @PostMapping("/{botId}/playlists")
    public ResponseEntity<?> createPlaylist(@PathVariable String botId, @RequestBody PlaylistRequest request) {
        String name = request == null ? "" : request.name();
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body("Playlist name is required");
        }
        boolean created = queueService.createPlaylist(botId, name);
        if (!created) {
            return ResponseEntity.badRequest().body("Playlist already exists");
        }
        return ResponseEntity.ok().build();
    }


    /**
     * 执行 renamePlaylist 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @param request 参数 request
     * @return 返回值
     */
    @PostMapping("/{botId}/playlists/{playlistId}/rename")
    public ResponseEntity<?> renamePlaylist(
        @PathVariable String botId,
        @PathVariable String playlistId,
        @RequestBody PlaylistRequest request
    ) {
        String name = request == null ? "" : request.name();
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body("Playlist name is required");
        }
        boolean renamed = queueService.renamePlaylist(botId, playlistId, name);
        if (!renamed) {
            return ResponseEntity.badRequest().body("Playlist rename failed");
        }
        return ResponseEntity.ok().build();

    }


    /**
     * 执行 deletePlaylist 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @return 返回值
     */
    @DeleteMapping("/{botId}/playlists/{playlistId}")
    public ResponseEntity<?> deletePlaylist(@PathVariable String botId, @PathVariable String playlistId) {
        List<QueueItem> items = queueService.rawList(botId, playlistId);
        pausePlaybackForDeletionIfNeeded(botId, items);
        releasePlaybackForItems(botId, items);
        deleteQueueMedia(items);
        boolean removed = queueService.removePlaylist(botId, playlistId);
        if (!removed) {
            return ResponseEntity.badRequest().body("Playlist delete failed");
        }
        return ResponseEntity.ok().build();
    }


    /**
     * 执行 activatePlaylist 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     */
    @PostMapping("/{botId}/playlists/{playlistId}/activate")
    public void activatePlaylist(@PathVariable String botId, @PathVariable String playlistId) {
        queueService.setActivePlaylist(botId, playlistId);
    }

    private Optional<Track> resolveTrack(String query) {
        return resolveTrack(new AddItemRequest(query, null, null, null, null, null, null));
    }

    private Optional<Track> resolveTrack(AddItemRequest item) {
        String query = item == null ? null : item.query();
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        String preferredSource = preferredResolverSource(item);
        if (!preferredSource.isBlank()) {
            log.info("Prefer resolver source={} query={}", preferredSource, query);
        }
        if (!preferredSource.isBlank()) {
            for (TrackResolver resolver : resolverRegistry.list()) {
                if (!equalsIgnoreCase(canonicalResolverSource(resolver == null ? null : resolver.sourceType()), preferredSource)) {
                    continue;
                }
                Optional<Track> resolved = resolver.resolve(query);
                if (resolved.isPresent()) {
                    log.info("Resolved query via preferred resolver source={} query={}", resolver.sourceType(), query);
                    return resolved;
                }
            }
        }
        for (TrackResolver resolver : resolverRegistry.list()) {
            if (!preferredSource.isBlank() && equalsIgnoreCase(canonicalResolverSource(resolver == null ? null : resolver.sourceType()), preferredSource)) {
                continue;
            }
            Optional<Track> resolved = resolver.resolve(query);
            if (resolved.isPresent()) {
                log.info("Resolved query via fallback resolver source={} query={}", resolver.sourceType(), query);
                return resolved;
            }
        }
        return Optional.empty();
    }

    private String preferredResolverSource(AddItemRequest item) {
        if (item == null) {
            return "";
        }
        String explicit = canonicalResolverSource(item.source());
        if (!explicit.isBlank()) {
            return explicit;
        }
        String query = item.query();
        if (query == null || query.isBlank()) {
            return "";
        }
        String host = extractHost(query);
        if (host == null || host.isBlank()) {
            return "";
        }
        String lower = host.toLowerCase();
        if (lower.startsWith("music.youtube.com")) {
            return "ytmusic";
        }
        if (lower.contains("youtube.com") || lower.equals("youtu.be") || lower.endsWith(".youtu.be")) {
            return "yt";
        }
        if (lower.startsWith("music.163.com") || lower.endsWith(".music.163.com")) {
            return "netease";
        }
        if (lower.startsWith("y.qq.com") || lower.endsWith(".y.qq.com")) {
            return "qq";
        }
        return "";
    }

    private String canonicalResolverSource(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String lower = value.trim().toLowerCase();
        return switch (lower) {
            case "yt", "youtube" -> "yt";
            case "ytmusic", "youtube music" -> "ytmusic";
            case "netease", "netease music", "网易云音乐" -> "netease";
            case "qq", "qqmusic", "qq music", "qq音乐" -> "qq";
            default -> lower;
        };
    }

    private Track applyWebsiteSourceType(String query, Track track) {
        String website = resolveWebsiteName(query);
        if (website == null || website.isBlank()) {
            return track;
        }
        String sourceType = track.sourceType();
        if (sourceType != null && !sourceType.isBlank()) {
            return track;
        }
        if (website.equalsIgnoreCase(track.sourceType())) {
            return track;
        }
        return new Track(
            track.id(),
            track.title(),
            website,
            track.sourceId(),
            track.streamUrl(),
            track.durationMs(),
            track.coverUrl(),
            track.artist(),
            track.playCount()
        );
    }

    private Track applyTrackMetadata(AddItemRequest item, Track track) {
        if (item == null || track == null) {
            return track;
        }
        String title = firstNonBlank(track.title(), item.title(), item.query());
        String sourceType = firstNonBlank(track.sourceType(), item.source());
        String sourceId = firstNonBlank(track.sourceId(), item.query());
        String streamUrl = firstNonBlank(track.streamUrl(), item.query());
        long durationMs = track.durationMs() > 0L ? track.durationMs() : safeDuration(item.durationMs());
        String coverUrl = firstNonBlank(track.coverUrl(), item.coverUrl());
        String artist = firstNonBlank(track.artist(), item.artist());
        Long playCount = track.playCount() != null && track.playCount() > 0L ? track.playCount() : safePlayCount(item.playCount());
        return new Track(
            track.id(),
            title,
            sourceType,
            sourceId,
            streamUrl,
            durationMs,
            coverUrl,
            artist,
            playCount
        );
    }

    private BatchAddResponse addBatch(String botId, String playlistId, List<AddItemRequest> items, String addedBy) {
        List<QueueItem> added = new java.util.ArrayList<>();
        List<AddFailure> failed = new java.util.ArrayList<>();
        for (AddItemRequest item : items) {
            String query = item == null ? null : item.query();
            if (query == null || query.isBlank()) {
                continue;
            }
            Optional<Track> track = resolveTrack(item);
            if (track.isEmpty()) {
                failed.add(new AddFailure(query, "No resolver could handle the query"));
                continue;
            }
            Track resolved = trackMediaService.prepareForQueue(applyTrackMetadata(item, applyWebsiteSourceType(query, track.get())));
            QueueItem queueItem = playlistId == null
                ? queueService.add(botId, resolved, addedBy)
                : queueService.add(botId, playlistId, resolved, addedBy);
            added.add(queueItem);
        }
        return new BatchAddResponse(added, failed);
    }

    private List<AddItemRequest> normalizeItems(AddRequest request) {
        if (request == null) {
            return List.of();
        }
        List<AddItemRequest> result = new java.util.ArrayList<>();
        if (request.items() != null) {
            for (AddItemRequest item : request.items()) {
                if (item == null || item.query() == null || item.query().isBlank()) {
                    continue;
                }
                result.add(new AddItemRequest(
                    item.query().trim(),
                    trimToNull(item.title()),
                    trimToNull(item.artist()),
                    trimToNull(item.coverUrl()),
                    item.durationMs(),
                    trimToNull(item.source()),
                    item.playCount()
                ));
            }
        }
        if (request.query() != null && !request.query().isBlank()) {
            result.add(new AddItemRequest(request.query().trim(), null, null, null, null, null, null));
        }
        if (request.queries() != null) {
            for (String query : request.queries()) {
                if (query != null && !query.isBlank()) {
                    result.add(new AddItemRequest(query.trim(), null, null, null, null, null, null));
                }
            }
        }
        return result;
    }

    private String firstNonBlank(String... values) {
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

    private boolean equalsIgnoreCase(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
    }

    private long safeDuration(Long durationMs) {
        if (durationMs == null) {
            return 0L;
        }
        return Math.max(0L, durationMs);
    }

    private Long safePlayCount(Long playCount) {
        if (playCount == null || playCount <= 0L) {
            return null;
        }
        return playCount;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String resolveWebsiteName(String query) {
        String host = extractHost(query);
        if (host == null || host.isBlank()) {
            return null;
        }
        String lower = host.toLowerCase();
        if (lower.startsWith("music.youtube.com")) {
            return "YouTube Music";
        }
        if (lower.contains("youtube.com") || lower.equals("youtu.be") || lower.endsWith(".youtu.be")) {
            return "YouTube";
        }
        if (lower.startsWith("music.163.com") || lower.endsWith(".music.163.com")) {
            return "网易云音乐";
        }
        if (lower.startsWith("y.qq.com") || lower.endsWith(".y.qq.com")) {
            return "QQ音乐";
        }
        return stripWww(host);
    }

    private String extractHost(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(query.trim());
            String host = uri.getHost();


            if (host != null && !host.isBlank()) {
                return host;
            }
        } catch (Exception ex) {
            log.debug("Invalid url query={}", query, ex);


        }
        try {
            URI uri = new URI("http://" + query.trim());
            String host = uri.getHost();
            return host == null || host.isBlank() ? null : host;
        } catch (Exception ex) {
            log.debug("Invalid url query={}", query, ex);
            return null;
        }
    }

    private String stripWww(String host) {
        if (host == null) {
            return null;
        }
        if (host.toLowerCase().startsWith("www.")) {
            return host.substring(4);
        }
        return host;
    }

    private QueueItem prepareQueueItemForDisplay(String botId, String playlistId, QueueItem item) {
        if (item == null || item.track() == null) {
            return item;
        }
        Track displayTrack = trackMediaService.prepareForDisplay(item.track());
        if (!displayTrack.equals(item.track())) {
            queueService.updateTrack(botId, playlistId, item.id(), displayTrack);
            return new QueueItem(
                item.id(),
                item.botId(),
                item.playlistId(),
                displayTrack,
                item.addedAt(),
                item.addedBy()
            );
        }
        return item;
    }

    private List<QueueItem> prepareQueueForDisplay(String botId, String playlistId, List<QueueItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<QueueItem> prepared = new java.util.ArrayList<>(items.size());
        for (QueueItem item : items) {
            prepared.add(prepareQueueItemForDisplay(botId, playlistId, item));
        }
        return prepared;
    }

    private Track findTrack(String botId, String playlistId, String itemId) {
        for (QueueItem item : queueService.rawList(botId, playlistId)) {
            if (item != null && item.id().equals(itemId)) {
                return item.track();
            }
        }
        return null;
    }

    private void deleteQueueMedia(List<QueueItem> items) {
        if (items == null) {
            return;
        }
        for (QueueItem item : items) {
            if (item != null) {
                trackMediaService.deleteTrackMedia(item.track());
            }
        }
    }

    private void releaseCurrentPlayback(String botId, String playlistId, String itemId) {
        if (botManager == null) {
            return;
        }
        BotInstance bot = botManager.get(botId);
        if (bot == null) {
            return;
        }
        bot.handleRemovedQueueItem(playlistId, itemId);
    }

    private void releasePlaybackForItems(String botId, List<QueueItem> items) {
        if (items == null) {
            return;
        }
        for (QueueItem item : items) {
            if (item != null) {
                releaseCurrentPlayback(botId, item.playlistId(), item.id());
            }
        }
    }

    private void pausePlaybackForDeletionIfNeeded(String botId, String playlistId, String itemId) {
        if (botManager == null) {
            return;
        }
        BotInstance bot = botManager.get(botId);
        if (bot == null) {
            return;
        }
        if (!itemId.equals(bot.currentItemId())) {
            return;
        }
        if (playlistId != null && !playlistId.equals(bot.currentPlaylistId())) {
            return;
        }
        bot.pausePlayback();
    }

    private void pausePlaybackForDeletionIfNeeded(String botId, List<QueueItem> items) {
        if (botManager == null || items == null || items.isEmpty()) {
            return;
        }
        BotInstance bot = botManager.get(botId);
        if (bot == null || bot.currentItemId() == null) {
            return;
        }
        String currentItemId = bot.currentItemId();
        String currentPlaylistId = bot.currentPlaylistId();
        for (QueueItem item : items) {
            if (item == null) {
                continue;
            }
            if (currentItemId.equals(item.id())
                && (currentPlaylistId == null || currentPlaylistId.equals(item.playlistId()))) {
                bot.pausePlayback();
                return;
            }
        }
    }


    /**
     * 执行 AddRequest 操作。
     * @param query 参数 query
     * @param addedBy 参数 addedBy
     * @return 返回值
     */
    public record AddRequest(String query, List<String> queries, List<AddItemRequest> items, String addedBy) {
    }


    /**
     * 鎵ц AddItemRequest 鎿嶄綔銆?     * @param query 鍙傛暟 query
     * @param title 鍙傛暟 title
     * @param artist 鍙傛暟 artist
     * @param coverUrl 鍙傛暟 coverUrl
     * @param durationMs 鍙傛暟 durationMs
     * @param source 鍙傛暟 source
     * @return 杩斿洖鍊?     */
    public record AddItemRequest(
        String query,
        String title,
        String artist,
        String coverUrl,
        Long durationMs,
        String source,
        Long playCount
    ) {
    }


    /**
     * 执行 PlaylistRequest 操作。
     * @param name 参数 name
     * @return 返回值
     */
    public record PlaylistRequest(String name) {
    }


    /**
     * 执行 PlaylistsResponse 操作。
     * @param active 参数 active
     * @param playlists 参数 playlists
     * @return 返回值
     */
    public record PlaylistsResponse(String active, List<PlaylistView> playlists) {
    }


    /**
     * 执行 PlaylistView 操作。
     * @param name 参数 name
     * @param active 参数 active
     * @return 返回值
     */
    public record PlaylistView(String name, boolean active) {
    }


    /**
     * 执行 BatchAddResponse 操作。
     * @param added 参数 added
     * @param failed 参数 failed
     * @return 返回值
     */
    public record BatchAddResponse(List<QueueItem> added, List<AddFailure> failed) {
    }


    /**
     * 执行 AddFailure 操作。
     * @param query 参数 query
     * @param reason 参数 reason
     * @return 返回值
     */
    public record AddFailure(String query, String reason) {
    }
}
