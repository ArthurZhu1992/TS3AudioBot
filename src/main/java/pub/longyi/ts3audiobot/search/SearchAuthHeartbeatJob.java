package pub.longyi.ts3audiobot.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public final class SearchAuthHeartbeatJob {
    private final SearchAuthService authService;
    private final QqMusicSearchProvider qqProvider;
    private final NeteaseSearchProvider neteaseProvider;

    public SearchAuthHeartbeatJob(
        SearchAuthService authService,
        QqMusicSearchProvider qqProvider,
        NeteaseSearchProvider neteaseProvider
    ) {
        this.authService = authService;
        this.qqProvider = qqProvider;
        this.neteaseProvider = neteaseProvider;
    }

    @Scheduled(
        initialDelayString = "${ts3audiobot.search.auth.heartbeat-initial-ms:${ts3audiobot.search.qq.heartbeat-initial-ms:45000}}",
        fixedDelayString = "${ts3audiobot.search.auth.heartbeat-ms:${ts3audiobot.search.qq.heartbeat-ms:300000}}"
    )
    public void run() {
        HeartbeatStats qqStats = runQqHeartbeat();
        HeartbeatStats neteaseStats = runNeteaseHeartbeat();
        if ((qqStats.refreshed + qqStats.invalid + neteaseStats.refreshed + neteaseStats.invalid) > 0) {
            log.info(
                "[Search] heartbeat done qq(refreshed={} invalid={} skipped={}) netease(refreshed={} invalid={} skipped={})",
                qqStats.refreshed,
                qqStats.invalid,
                qqStats.skipped,
                neteaseStats.refreshed,
                neteaseStats.invalid,
                neteaseStats.skipped
            );
        } else {
            log.debug(
                "[Search] heartbeat done qq(refreshed={} invalid={} skipped={}) netease(refreshed={} invalid={} skipped={})",
                qqStats.refreshed,
                qqStats.invalid,
                qqStats.skipped,
                neteaseStats.refreshed,
                neteaseStats.invalid,
                neteaseStats.skipped
            );
        }
    }

    private HeartbeatStats runQqHeartbeat() {
        List<SearchAuthStore.AuthRecord> records = authService.listAuthBySource("qq");
        HeartbeatStats stats = new HeartbeatStats();
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
                    stats.skipped++;
                    continue;
                }
                if (result.invalid()) {
                    authService.deleteAuth(record.source(), record.scopeType(), record.botId());
                    stats.invalid++;
                    continue;
                }
                if (result.refreshed() && result.authRecord() != null) {
                    authService.upsertAuth(result.authRecord());
                    stats.refreshed++;
                } else {
                    stats.skipped++;
                }
            } catch (Exception ex) {
                stats.skipped++;
                log.debug("[Search:qq] heartbeat task failed scope={} bot={}", record.scopeType(), record.botId(), ex);
            }
        }
        return stats;
    }

    private HeartbeatStats runNeteaseHeartbeat() {
        List<SearchAuthStore.AuthRecord> records = authService.listAuthBySource("netease");
        HeartbeatStats stats = new HeartbeatStats();
        for (SearchAuthStore.AuthRecord record : records) {
            if (record == null) {
                continue;
            }
            if (authService.isExpired(record)) {
                authService.clearIfExpired(record);
                continue;
            }
            try {
                NeteaseSearchProvider.HeartbeatResult result = neteaseProvider.heartbeatAuth(record);
                if (result == null) {
                    stats.skipped++;
                    continue;
                }
                if (result.invalid()) {
                    authService.deleteAuth(record.source(), record.scopeType(), record.botId());
                    stats.invalid++;
                    continue;
                }
                if (result.refreshed() && result.authRecord() != null) {
                    authService.upsertAuth(result.authRecord());
                    stats.refreshed++;
                } else {
                    stats.skipped++;
                }
            } catch (Exception ex) {
                stats.skipped++;
                log.debug("[Search:netease] heartbeat task failed scope={} bot={}", record.scopeType(), record.botId(), ex);
            }
        }
        return stats;
    }

    private static final class HeartbeatStats {
        private int refreshed;
        private int invalid;
        private int skipped;
    }
}
