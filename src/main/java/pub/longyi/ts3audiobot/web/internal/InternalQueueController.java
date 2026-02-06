package pub.longyi.ts3audiobot.web.internal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /**
     * 创建 InternalQueueController 实例。
     * @param queueService 参数 queueService
     * @param resolverRegistry 参数 resolverRegistry
     */
    public InternalQueueController(QueueService queueService, ResolverRegistry resolverRegistry) {
        this.queueService = queueService;
        this.resolverRegistry = resolverRegistry;
    }


    /**
     * 执行 list 操作。
     * @param botId 参数 botId
     * @return 返回值
     */
    @GetMapping("/{botId}")
    public List<QueueItem> list(@PathVariable String botId) {
        return queueService.list(botId);
    }


    /**
     * 执行 listByPlaylist 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @return 返回值
     */
    @GetMapping("/{botId}/{playlistId}")
    public List<QueueItem> listByPlaylist(@PathVariable String botId, @PathVariable String playlistId) {
        return queueService.list(botId, playlistId);
    }


    /**
     * 执行 add 操作。
     * @param botId 参数 botId
     * @param request 参数 request
     * @return 返回值
     */
    @PostMapping("/{botId}/add")
    public ResponseEntity<?> add(@PathVariable String botId, @RequestBody AddRequest request) {
        Optional<Track> track = resolveTrack(request.query());
        if (track.isEmpty()) {
            return ResponseEntity.badRequest().body("No resolver could handle the query");
        }
        Track resolved = applyWebsiteSourceType(request.query(), track.get());
        return ResponseEntity.ok(queueService.add(botId, resolved, request.addedBy()));
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
        Optional<Track> track = resolveTrack(request.query());
        if (track.isEmpty()) {
            return ResponseEntity.badRequest().body("No resolver could handle the query");
        }
        Track resolved = applyWebsiteSourceType(request.query(), track.get());
        return ResponseEntity.ok(queueService.add(botId, playlistId, resolved, request.addedBy()));
    }


    /**
     * 执行 clear 操作。
     * @param botId 参数 botId
     */
    @PostMapping("/{botId}/clear")
    public void clear(@PathVariable String botId) {
        queueService.clear(botId);
    }


    /**
     * 执行 clearByPlaylist 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     */
    @PostMapping("/{botId}/{playlistId}/clear")
    public void clearByPlaylist(@PathVariable String botId, @PathVariable String playlistId) {
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
        boolean removed = queueService.removeItem(botId, playlistId, itemId);
        if (!removed) {
            return ResponseEntity.badRequest().body("Queue item delete failed");
        }
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
        for (TrackResolver resolver : resolverRegistry.list()) {
            Optional<Track> resolved = resolver.resolve(query);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.empty();
    }

    private Track applyWebsiteSourceType(String query, Track track) {
        String website = resolveWebsiteName(query);
        if (website == null || website.isBlank()) {
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
            track.durationMs()
        );
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


    /**
     * 执行 AddRequest 操作。
     * @param query 参数 query
     * @param addedBy 参数 addedBy
     * @return 返回值
     */
    public record AddRequest(String query, String addedBy) {
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
}
