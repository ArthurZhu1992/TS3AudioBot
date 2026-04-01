package pub.longyi.ts3audiobot.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public final class QqAuthHeartbeatJob {
    private final SearchAuthService authService;
    private final QqMusicSearchProvider qqProvider;

    public QqAuthHeartbeatJob(SearchAuthService authService, QqMusicSearchProvider qqProvider) {
        this.authService = authService;
        this.qqProvider = qqProvider;
    }

    @Scheduled(
        initialDelayString = "${ts3audiobot.search.qq.heartbeat-initial-ms:45000}",
        fixedDelayString = "${ts3audiobot.search.qq.heartbeat-ms:300000}"
    )
    public void run() {
        List<SearchAuthStore.AuthRecord> records = authService.listAuthBySource("qq");
        if (records.isEmpty()) {
            return;
        }
        int refreshed = 0;
        int invalid = 0;
        int skipped = 0;
        for (SearchAuthStore.AuthRecord record : records) {
            if (record == null) {
                continue;
            }
            if (authService.isExpired(record)) {
                authService.clearIfExpired(record);
                continue;
            }
            try {
                QqMusicSearchProvider.HeartbeatResult result = qqProvider.heartbeatAuth(record);
                if (result == null) {
                    skipped++;
                    continue;
                }
                if (result.invalid()) {
                    authService.deleteAuth(record.source(), record.scopeType(), record.botId());
                    invalid++;
                    continue;
                }
                if (result.refreshed() && result.authRecord() != null) {
                    authService.upsertAuth(result.authRecord());
                    refreshed++;
                } else {
                    skipped++;
                }
            } catch (Exception ex) {
                skipped++;
                log.debug("[Search:qq] heartbeat task failed scope={} bot={}", record.scopeType(), record.botId(), ex);
            }
        }
        if (refreshed > 0 || invalid > 0) {
            log.info("[Search:qq] heartbeat done refreshed={} invalid={} skipped={}", refreshed, invalid, skipped);
        } else {
            log.debug("[Search:qq] heartbeat done refreshed={} invalid={} skipped={}", refreshed, invalid, skipped);
        }
    }
}
