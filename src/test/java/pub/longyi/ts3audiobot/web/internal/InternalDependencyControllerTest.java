package pub.longyi.ts3audiobot.web.internal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import pub.longyi.ts3audiobot.system.DependencyToolService;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalDependencyControllerTest {

    @Test
    void statusReturnsSnapshot() {
        DependencyToolService service = mock(DependencyToolService.class);
        InternalDependencyController controller = new InternalDependencyController(service);
        DependencyToolService.DependencySnapshot snapshot = new DependencyToolService.DependencySnapshot(
            List.of(),
            "2026-04-02T00:00:00Z"
        );
        when(service.snapshot()).thenReturn(snapshot);

        DependencyToolService.DependencySnapshot response = controller.status();

        assertEquals("2026-04-02T00:00:00Z", response.updatedAt());
        verify(service).snapshot();
    }

    @Test
    void downloadReturnsOkWhenAccepted() {
        DependencyToolService service = mock(DependencyToolService.class);
        InternalDependencyController controller = new InternalDependencyController(service);
        when(service.startDownload("yt-dlp")).thenReturn(new DependencyToolService.DownloadRequestResult(true, "ok"));

        ResponseEntity<?> response = controller.download("yt-dlp");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody() instanceof Map);
        assertEquals("ok", ((Map<?, ?>) response.getBody()).get("message"));
        verify(service).startDownload("yt-dlp");
    }

    @Test
    void downloadReturnsConflictWhenAlreadyRunning() {
        DependencyToolService service = mock(DependencyToolService.class);
        InternalDependencyController controller = new InternalDependencyController(service);
        when(service.startDownload("ffmpeg")).thenReturn(new DependencyToolService.DownloadRequestResult(false, "running"));

        ResponseEntity<?> response = controller.download("ffmpeg");

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertTrue(response.getBody() instanceof Map);
        assertEquals("running", ((Map<?, ?>) response.getBody()).get("message"));
        verify(service).startDownload("ffmpeg");
    }
}
