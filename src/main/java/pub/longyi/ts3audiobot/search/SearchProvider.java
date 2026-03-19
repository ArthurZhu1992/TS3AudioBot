package pub.longyi.ts3audiobot.search;

import pub.longyi.ts3audiobot.search.SearchModels.PlaylistPage;
import pub.longyi.ts3audiobot.search.SearchModels.PlaylistTrackPage;
import pub.longyi.ts3audiobot.search.SearchModels.SearchPage;

public interface SearchProvider {
    String source();

    boolean requiresLogin();

    boolean supportsPlaylists();

    LoginStartResult startLogin(LoginRequest request);

    LoginPollResult pollLogin(LoginPollRequest request);

    SearchPage search(SearchRequest request);

    PlaylistPage listPlaylists(PlaylistRequest request);

    PlaylistTrackPage listPlaylistTracks(PlaylistTracksRequest request);

    record LoginRequest(String scopeType, String botId) {
    }

    record LoginPollRequest(String sessionId, String payload, String scopeType, String botId) {
    }

    record SearchRequest(
        String botId,
        String query,
        int page,
        int pageSize,
        SearchAuthStore.AuthRecord auth
    ) {
    }

    record PlaylistRequest(
        String botId,
        int page,
        int pageSize,
        SearchAuthStore.AuthRecord auth
    ) {
    }

    record PlaylistTracksRequest(
        String botId,
        String playlistId,
        int page,
        int pageSize,
        SearchAuthStore.AuthRecord auth
    ) {
    }

    record LoginStartResult(
        String sessionId,
        String qrImage,
        String qrUrl,
        String payload,
        long expiresInSeconds,
        String message
    ) {
    }

    record LoginPollResult(
        LoginStatus status,
        String message,
        String payload,
        SearchAuthStore.AuthRecord authRecord
    ) {
    }
}
