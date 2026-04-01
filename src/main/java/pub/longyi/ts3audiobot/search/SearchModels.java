package pub.longyi.ts3audiobot.search;

import java.time.Instant;
import java.util.List;

public final class SearchModels {
    private SearchModels() {
    }

    public record SearchItem(
        String uid,
        String id,
        String title,
        String artist,
        String coverUrl,
        long durationMs,
        Long playCount,
        String pageUrl,
        String source,
        Boolean vipRequired,
        String vipHint
    ) {
    }

    public record SearchPage(
        List<SearchItem> items,
        int page,
        int pageSize,
        Integer total
    ) {
    }

    public record SearchDetailPage(
        String status,
        List<SearchItem> items,
        int page,
        int pageSize,
        Integer total
    ) {
    }

    public record PlaylistItem(
        String id,
        String name,
        String coverUrl,
        Integer trackCount,
        Long playCount,
        String source
    ) {
    }

    public record PlaylistPage(
        List<PlaylistItem> items,
        int page,
        int pageSize,
        Integer total
    ) {
    }

    public record PlaylistTrackItem(
        String uid,
        String id,
        String title,
        String artist,
        String coverUrl,
        long durationMs,
        Long playCount,
        String pageUrl,
        String source
    ) {
    }

    public record PlaylistTrackPage(
        List<PlaylistTrackItem> items,
        int page,
        int pageSize,
        Integer total
    ) {
    }

    public record SearchStatus(
        String source,
        boolean requiresLogin,
        boolean loggedIn,
        String scopeUsed,
        boolean supportsPlaylists,
        boolean enabled,
        String vipState,
        String vipHint,
        String accountInfo
    ) {
    }

    public record LoginStart(
        String sessionId,
        String source,
        String scope,
        String qrImage,
        String qrUrl,
        Instant expiresAt,
        String message
    ) {
    }

    public record LoginPoll(
        LoginStatus status,
        String message
    ) {
    }
}
