package pub.longyi.ts3audiobot.web.internal;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import pub.longyi.ts3audiobot.search.SearchService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class InternalSearchControllerTest {

    @Test
    void importManualLoginRoutesToQqService() {
        SearchService searchService = mock(SearchService.class);
        InternalSearchController controller = new InternalSearchController(searchService);
        when(searchService.importQqManualAuth("global", "", "payload")).thenReturn("QQ OK");

        ResponseEntity<?> response = controller.importManualLogin("qq", "global", "", "payload");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody() instanceof Map);
        assertEquals("QQ OK", ((Map<?, ?>) response.getBody()).get("message"));
        verify(searchService).importQqManualAuth("global", "", "payload");
    }

    @Test
    void importManualLoginRoutesToNeteaseService() {
        SearchService searchService = mock(SearchService.class);
        InternalSearchController controller = new InternalSearchController(searchService);
        when(searchService.importNeteaseManualAuth("bot", "bot-1", "payload")).thenReturn("NE OK");

        ResponseEntity<?> response = controller.importManualLogin("netease", "bot", "bot-1", "payload");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody() instanceof Map);
        assertEquals("NE OK", ((Map<?, ?>) response.getBody()).get("message"));
        verify(searchService).importNeteaseManualAuth("bot", "bot-1", "payload");
    }

    @Test
    void importManualLoginRejectsUnsupportedSource() {
        SearchService searchService = mock(SearchService.class);
        InternalSearchController controller = new InternalSearchController(searchService);

        ResponseEntity<?> response = controller.importManualLogin("ytmusic", "global", "", "payload");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(searchService);
    }
}

