package pub.longyi.ts3audiobot.shuffle;

import java.util.Objects;

/**
 * 随机播放状态作用域键，按 bot + playlist 隔离状态。
 */
public record ShuffleSessionKey(String botId, String playlistId) {
    private static final String DEFAULT_PLAYLIST_ID = "default";

    public ShuffleSessionKey {
        botId = normalizeBotId(botId);
        playlistId = normalizePlaylistId(playlistId);
    }

    public static ShuffleSessionKey of(String botId, String playlistId) {
        return new ShuffleSessionKey(botId, playlistId);
    }

    private static String normalizeBotId(String value) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("botId must not be blank");
        }
        return normalized;
    }

    private static String normalizePlaylistId(String value) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        return normalized.isEmpty() ? DEFAULT_PLAYLIST_ID : normalized;
    }
}
