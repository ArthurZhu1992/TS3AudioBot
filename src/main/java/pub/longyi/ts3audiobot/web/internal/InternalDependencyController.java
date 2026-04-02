package pub.longyi.ts3audiobot.web.internal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pub.longyi.ts3audiobot.system.DependencyToolService;

import java.util.Map;

@RestController
@RequestMapping("/internal/dependencies")
public final class InternalDependencyController {
    private final DependencyToolService dependencyToolService;

    public InternalDependencyController(DependencyToolService dependencyToolService) {
        this.dependencyToolService = dependencyToolService;
    }

    @GetMapping
    public DependencyToolService.DependencySnapshot status() {
        return dependencyToolService.snapshot();
    }

    @PostMapping("/{toolId}/download")
    public ResponseEntity<?> download(@PathVariable String toolId) {
        try {
            DependencyToolService.DownloadRequestResult result = dependencyToolService.startDownload(toolId);
            if (!result.accepted()) {
                return ResponseEntity.status(409).body(Map.of("message", result.message()));
            }
            return ResponseEntity.ok(Map.of("message", result.message()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().body("依赖下载任务启动失败");
        }
    }
}
