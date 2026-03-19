package pub.longyi.ts3audiobot.web.internal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pub.longyi.ts3audiobot.search.SearchModels.LoginPoll;
import pub.longyi.ts3audiobot.search.SearchModels.LoginStart;
import pub.longyi.ts3audiobot.search.SearchModels.PlaylistPage;
import pub.longyi.ts3audiobot.search.SearchModels.PlaylistTrackPage;
import pub.longyi.ts3audiobot.search.SearchModels.SearchDetailPage;
import pub.longyi.ts3audiobot.search.SearchModels.SearchPage;
import pub.longyi.ts3audiobot.search.SearchModels.SearchStatus;
import pub.longyi.ts3audiobot.search.SearchService;

import java.util.List;

@RestController
@RequestMapping("/internal/search")
public final class InternalSearchController {
    private final SearchService searchService;

    public InternalSearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/status")
    public List<SearchStatus> status(@RequestParam(required = false) String botId) {
        return searchService.getStatus(botId);
    }

    @PostMapping("/{source}/login")
    public ResponseEntity<?> startLogin(
        @PathVariable String source,
        @RequestParam(defaultValue = "global") String scope,
        @RequestParam(required = false) String botId
    ) {
        try {
            LoginStart start = searchService.startLogin(source, scope, botId);
            return ResponseEntity.ok(start);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body("登录失败");
        }
    }

    @GetMapping("/{source}/login/{sessionId}")
    public ResponseEntity<?> pollLogin(@PathVariable String source, @PathVariable String sessionId) {
        try {
            LoginPoll result = searchService.pollLogin(sessionId);
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body("登录状态获取失败");
        }
    }

    @GetMapping
    public ResponseEntity<?> search(
        @RequestParam String source,
        @RequestParam String q,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "30") int pageSize,
        @RequestParam(required = false) String botId
    ) {
        try {
            SearchPage result = searchService.search(source, botId, q, page, pageSize);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(401).body(ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body("搜索失败");
        }
    }

    @GetMapping("/detail")
    public ResponseEntity<?> searchDetail(
        @RequestParam String source,
        @RequestParam String q,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "30") int pageSize,
        @RequestParam(required = false) String botId
    ) {
        try {
            SearchDetailPage result = searchService.searchDetail(source, botId, q, page, pageSize);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(401).body(ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body("搜索失败");
        }
    }

    @GetMapping("/{source}/playlists")
    public ResponseEntity<?> playlists(
        @PathVariable String source,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "30") int pageSize,
        @RequestParam(required = false) String botId
    ) {
        try {
            PlaylistPage result = searchService.listPlaylists(source, botId, page, pageSize);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(401).body(ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body("获取歌单失败");
        }
    }

    @GetMapping("/{source}/playlists/{playlistId}/tracks")
    public ResponseEntity<?> playlistTracks(
        @PathVariable String source,
        @PathVariable String playlistId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "30") int pageSize,
        @RequestParam(required = false) String botId
    ) {
        try {
            PlaylistTrackPage result = searchService.listPlaylistTracks(source, botId, playlistId, page, pageSize);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(401).body(ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body("获取歌单歌曲失败");
        }
    }
}
