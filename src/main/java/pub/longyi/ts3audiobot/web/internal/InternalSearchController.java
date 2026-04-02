package pub.longyi.ts3audiobot.web.internal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pub.longyi.ts3audiobot.search.SearchModels.LoginPoll;
import pub.longyi.ts3audiobot.search.SearchModels.LoginStart;
import pub.longyi.ts3audiobot.search.SearchModels.PlaylistPage;
import pub.longyi.ts3audiobot.search.SearchModels.PlaylistTrackPage;
import pub.longyi.ts3audiobot.search.SearchModels.SearchDetailPage;
import pub.longyi.ts3audiobot.search.SearchModels.SearchPage;
import pub.longyi.ts3audiobot.search.SearchModels.SearchStatus;
import pub.longyi.ts3audiobot.search.SearchService;

import java.util.List;
import java.util.Map;

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
            return ResponseEntity.internalServerError().body("\u767b\u5f55\u5931\u8d25");
        }
    }

    @GetMapping("/{source}/login/{sessionId}")
    public ResponseEntity<?> pollLogin(@PathVariable String source, @PathVariable String sessionId) {
        try {
            LoginPoll result = searchService.pollLogin(sessionId);
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body("\u83b7\u53d6\u767b\u5f55\u72b6\u6001\u5931\u8d25");
        }
    }

    @PostMapping("/{source}/login/manual")
    public ResponseEntity<?> importManualLogin(
        @PathVariable String source,
        @RequestParam(defaultValue = "global") String scope,
        @RequestParam(required = false) String botId,
        @RequestParam String payload
    ) {
        if (!"qq".equalsIgnoreCase(source) && !"netease".equalsIgnoreCase(source)) {
            return ResponseEntity.badRequest().body("\u5f53\u524d\u5e73\u53f0\u4e0d\u652f\u6301\u624b\u52a8\u5bfc\u5165");
        }
        try {
            String message = "qq".equalsIgnoreCase(source)
                ? searchService.importQqManualAuth(scope, botId, payload)
                : searchService.importNeteaseManualAuth(scope, botId, payload);
            return ResponseEntity.ok(Map.of("message", message));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body("\u624b\u52a8\u767b\u5f55\u5bfc\u5165\u5931\u8d25");
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
            return ResponseEntity.internalServerError().body("\u641c\u7d22\u5931\u8d25");
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
            return ResponseEntity.internalServerError().body("\u641c\u7d22\u5931\u8d25");
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
            return ResponseEntity.internalServerError().body("\u83b7\u53d6\u6b4c\u5355\u5931\u8d25");
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
            return ResponseEntity.internalServerError().body("\u83b7\u53d6\u6b4c\u5355\u6b4c\u66f2\u5931\u8d25");
        }
    }
}
