package pub.longyi.ts3audiobot.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class QqHeadlessLoginSupportProbeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void probeHeadlessQrGeneration() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("QQ_PROBE")), "set QQ_PROBE=true to run manual probe");

        QqHeadlessLoginSupport support = new QqHeadlessLoginSupport();
        SearchProvider.LoginStartResult start = support.startLogin(new SearchProvider.LoginRequest("global", ""));
        assertFalse(start.qrImage() == null || start.qrImage().isBlank(), "QR image should not be empty");

        JsonNode payload = MAPPER.readTree(start.payload());
        long pid = payload.path("pid").asLong(-1L);
        Path userDataDir = payload.hasNonNull("userDataDir") ? Path.of(payload.path("userDataDir").asText()) : null;

        Method cleanup = QqHeadlessLoginSupport.class.getDeclaredMethod("cleanupProcess", long.class, Path.class);
        cleanup.setAccessible(true);
        cleanup.invoke(support, pid, userDataDir);
    }

    @Test
    void probeProviderQrGenerationFallback() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("QQ_PROBE")), "set QQ_PROBE=true to run manual probe");
        QqMusicSearchProvider provider = new QqMusicSearchProvider(new QqHeadlessLoginSupport());
        SearchProvider.LoginStartResult start = provider.startLogin(new SearchProvider.LoginRequest("global", ""));
        assertFalse(start.qrImage() == null || start.qrImage().isBlank(), "Provider QR image should not be empty");
    }
}
