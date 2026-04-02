package pub.longyi.ts3audiobot.search;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NeteaseLoginProbeTest {

    @Test
    void startLoginAndPollWithoutScan() {
        Assumptions.assumeTrue(
            "true".equalsIgnoreCase(System.getenv("NETEASE_PROBE")),
            "set NETEASE_PROBE=true to run manual probe"
        );
        NeteaseSearchProvider provider = new NeteaseSearchProvider();
        SearchProvider.LoginStartResult start = provider.startLogin(new SearchProvider.LoginRequest("global", ""));

        assertNotNull(start);
        assertFalse((start.payload() == null ? "" : start.payload()).isBlank(), "payload should include unikey");

        SearchProvider.LoginPollResult poll = provider.pollLogin(new SearchProvider.LoginPollRequest(
            start.sessionId(),
            start.payload(),
            "global",
            ""
        ));

        assertNotNull(poll);
        assertNotNull(poll.status());
    }
}

