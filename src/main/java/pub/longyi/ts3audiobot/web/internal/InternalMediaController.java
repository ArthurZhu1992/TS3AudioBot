package pub.longyi.ts3audiobot.web.internal;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pub.longyi.ts3audiobot.media.TrackMediaService;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

@RestController
@RequestMapping("/internal/media")
public final class InternalMediaController {
    private final TrackMediaService trackMediaService;

    public InternalMediaController(TrackMediaService trackMediaService) {
        this.trackMediaService = trackMediaService;
    }

    @GetMapping("/cover/{trackId}")
    public ResponseEntity<Resource> cover(
        @PathVariable String trackId,
        @RequestParam(name = "size", required = false) String size
    ) {
        int maxEdge = trackMediaService.resolveImageSizeForAlias(size);
        Optional<Path> cover = maxEdge > 0
            ? trackMediaService.findCoverFile(trackId, maxEdge)
            : trackMediaService.findCoverFile(trackId);
        if (cover.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Path path = cover.get();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
            .contentType(resolveMediaType(path))
            .body(new FileSystemResource(path));
    }

    private MediaType resolveMediaType(Path path) {
        String name = path == null ? "" : path.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (name.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.IMAGE_JPEG;
    }
}
