package pub.longyi.ts3audiobot.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pub.longyi.ts3audiobot.search.SearchModels.PlaylistItem;
import pub.longyi.ts3audiobot.search.SearchModels.PlaylistPage;
import pub.longyi.ts3audiobot.search.SearchModels.PlaylistTrackItem;
import pub.longyi.ts3audiobot.search.SearchModels.PlaylistTrackPage;
import pub.longyi.ts3audiobot.search.SearchModels.SearchItem;
import pub.longyi.ts3audiobot.search.SearchModels.SearchPage;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public final class NeteaseSearchProvider implements SearchProvider {
    private static final String BASE_URL = "https://music.163.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private final NeteaseCrypto crypto = new NeteaseCrypto();

    @Override
    public String source() {
        return "netease";
    }

    @Override
    public boolean requiresLogin() {
        return true;
    }

    @Override
    public boolean supportsPlaylists() {
        return true;
    }

    @Override
    public LoginStartResult startLogin(LoginRequest request) {
        JsonNode unikeyNode = postWeapi("/weapi/login/qrcode/unikey", Map.of("type", 1), null).body();
        String unikey = unikeyNode.path("data").path("unikey").asText("");
        if (unikey.isBlank()) {
            return new LoginStartResult("", "", "", "", 0, "获取二维码失败");
        }
        Map<String, Object> createPayload = new HashMap<>();
        createPayload.put("key", unikey);
        createPayload.put("qrimg", true);
        JsonNode qrNode = postWeapi("/weapi/login/qrcode/create", createPayload, null).body();
        String qrimg = qrNode.path("data").path("qrimg").asText("");
        String qrurl = qrNode.path("data").path("qrurl").asText("");
        String payload = toJson(Map.of(
            "unikey", unikey,
            "qrimg", qrimg,
            "qrurl", qrurl
        ));
        return new LoginStartResult(UUID.randomUUID().toString(), qrimg, qrurl, payload, 300, "请扫码登录");
    }

    @Override
    public LoginPollResult pollLogin(LoginPollRequest request) {
        Map<String, String> payload = parsePayload(request.payload());
        String unikey = payload.getOrDefault("unikey", "");
        if (unikey.isBlank()) {
            return new LoginPollResult(LoginStatus.ERROR, "登录会话已失效", request.payload(), null);
        }
        NeteaseResponse checkResponse = postWeapi(
            "/weapi/login/qrcode/check",
            Map.of("key", unikey, "type", 1),
            null
        );
        JsonNode result = checkResponse.body();
        int code = result.path("code").asInt(0);
        String message = result.path("message").asText("");
        if (code == 801) {
            return new LoginPollResult(LoginStatus.PENDING, message.isBlank() ? "等待扫码" : message, request.payload(), null);
        }
        if (code == 802) {
            return new LoginPollResult(LoginStatus.SCANNED, message.isBlank() ? "已扫码，等待确认" : message, request.payload(), null);
        }
        if (code == 800) {
            return new LoginPollResult(LoginStatus.EXPIRED, message.isBlank() ? "二维码已过期" : message, request.payload(), null);
        }
        if (code != 803) {
            return new LoginPollResult(LoginStatus.ERROR, message.isBlank() ? "登录失败" : message, request.payload(), null);
        }
        String cookie = result.path("cookie").asText("");
        if (cookie.isBlank()) {
            cookie = result.path("data").path("cookie").asText("");
        }
        if (cookie.isBlank()) {
            cookie = checkResponse.cookie() == null ? "" : checkResponse.cookie();
        }
        String accountJson = "";
        String userId = "";
        String nickname = "";
        try {
            JsonNode account = postWeapi("/weapi/w/nuser/account/get", Map.of(), cookie).body();
            JsonNode profile = account.path("profile");
            userId = profile.path("userId").asText("");
            nickname = profile.path("nickname").asText("");
            accountJson = toJson(Map.of(
                "userId", userId,
                "nickname", nickname
            ));
        } catch (Exception ex) {
            log.warn("[Search:netease] failed to resolve account info", ex);
        }
        SearchAuthStore.AuthRecord auth = new SearchAuthStore.AuthRecord(
            source(),
            request.scopeType(),
            request.botId(),
            cookie,
            "",
            accountJson,
            null,
            Instant.now()
        );
        return new LoginPollResult(LoginStatus.CONFIRMED, "登录成功", request.payload(), auth);
    }

    @Override
    public SearchPage search(SearchRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("s", request.query());
        payload.put("type", 1);
        payload.put("limit", request.pageSize());
        payload.put("offset", (request.page() - 1) * request.pageSize());
        JsonNode result = postWeapi("/weapi/cloudsearch/get/web", payload, request.auth().cookie()).body();
        JsonNode songs = result.path("result").path("songs");
        int total = result.path("result").path("songCount").asInt(0);
        List<SearchItem> items = new ArrayList<>();
        if (songs.isArray()) {
            for (JsonNode song : songs) {
                String id = song.path("id").asText("");
                String title = song.path("name").asText("");
                String artist = joinArtists(song.path("ar"));
                String cover = song.path("al").path("picUrl").asText("");
                long durationMs = song.path("dt").asLong(0);
                String pageUrl = id.isBlank() ? "" : "https://music.163.com/song?id=" + id;
                String uid = "netease:" + id;
                items.add(new SearchItem(
                    uid,
                    id,
                    title,
                    artist,
                    cover,
                    durationMs,
                    null,
                    pageUrl,
                    source()
                ));
            }
        }
        return new SearchPage(items, request.page(), request.pageSize(), total);
    }

    @Override
    public PlaylistPage listPlaylists(PlaylistRequest request) {
        String userId = resolveUserId(request.auth());
        if (userId.isBlank()) {
            return new PlaylistPage(List.of(), request.page(), request.pageSize(), 0);
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("uid", userId);
        payload.put("limit", request.pageSize());
        payload.put("offset", (request.page() - 1) * request.pageSize());
        JsonNode result = postWeapi("/weapi/user/playlist", payload, request.auth().cookie()).body();
        JsonNode lists = result.path("playlist");
        List<PlaylistItem> items = new ArrayList<>();
        if (lists.isArray()) {
            for (JsonNode item : lists) {
                String id = item.path("id").asText("");
                String name = item.path("name").asText("");
                String cover = item.path("coverImgUrl").asText("");
                int trackCount = item.path("trackCount").asInt(0);
                long playCount = item.path("playCount").asLong(0);
                items.add(new PlaylistItem(
                    id,
                    name,
                    cover,
                    trackCount,
                    playCount > 0 ? playCount : null,
                    source()
                ));
            }
        }
        int total = result.path("count").asInt(items.size());
        return new PlaylistPage(items, request.page(), request.pageSize(), total);
    }

    @Override
    public PlaylistTrackPage listPlaylistTracks(PlaylistTracksRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", request.playlistId());
        payload.put("limit", request.pageSize());
        payload.put("offset", (request.page() - 1) * request.pageSize());
        JsonNode result = postWeapi("/weapi/playlist/track/all", payload, request.auth().cookie()).body();
        JsonNode songs = result.path("songs");
        if (!songs.isArray()) {
            songs = result.path("playlist").path("tracks");
        }
        List<PlaylistTrackItem> items = new ArrayList<>();
        if (songs.isArray()) {
            for (JsonNode song : songs) {
                String id = song.path("id").asText("");
                String title = song.path("name").asText("");
                String artist = joinArtists(song.path("ar"));
                String cover = song.path("al").path("picUrl").asText("");
                long durationMs = song.path("dt").asLong(0);
                String pageUrl = id.isBlank() ? "" : "https://music.163.com/song?id=" + id;
                String uid = "netease:" + id;
                items.add(new PlaylistTrackItem(
                    uid,
                    id,
                    title,
                    artist,
                    cover,
                    durationMs,
                    null,
                    pageUrl,
                    source()
                ));
            }
        }
        int total = result.path("count").asInt(items.size());
        return new PlaylistTrackPage(items, request.page(), request.pageSize(), total);
    }

    private String resolveUserId(SearchAuthStore.AuthRecord auth) {
        if (auth == null) {
            return "";
        }
        Map<String, String> extra = parsePayload(auth.extraJson());
        String userId = extra.getOrDefault("userId", "");
        if (!userId.isBlank()) {
            return userId;
        }
        try {
            JsonNode account = postWeapi("/weapi/w/nuser/account/get", Map.of(), auth.cookie()).body();
            JsonNode profile = account.path("profile");
            return profile.path("userId").asText("");
        } catch (Exception ex) {
            log.warn("[Search:netease] failed to resolve user id", ex);
            return "";
        }
    }

    private NeteaseResponse postWeapi(String path, Map<String, Object> payload, String cookie) {
        String json = toJson(payload);
        NeteaseCrypto.WeapiPayload weapi = crypto.encrypt(json);
        String form = "params=" + urlEncode(weapi.params()) + "&encSecKey=" + urlEncode(weapi.encSecKey());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://music.163.com/")
            .POST(HttpRequest.BodyPublishers.ofString(form));
        if (cookie != null && !cookie.isBlank()) {
            builder.header("Cookie", cookie);
        }
        try {
            HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String mergedCookie = mergeCookies(cookie, response.headers().allValues("Set-Cookie"));
            JsonNode body = MAPPER.readTree(response.body());
            return new NeteaseResponse(body, mergedCookie);
        } catch (Exception ex) {
            log.warn("[Search:netease] request failed path={}", path, ex);
            return new NeteaseResponse(MAPPER.createObjectNode(), cookie);
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private Map<String, String> parsePayload(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null || json.isBlank()) {
            return map;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue().asText("")));
        } catch (Exception ex) {
            return map;
        }
        return map;
    }

    private String joinArtists(JsonNode arNode) {
        if (arNode == null || !arNode.isArray()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode artist : arNode) {
            String name = artist.path("name").asText("");
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return String.join(" / ", names);
    }

    private String mergeCookies(String base, List<String> setCookies) {
        Map<String, String> merged = new HashMap<>();
        if (base != null && !base.isBlank()) {
            for (String part : base.split(";")) {
                String trimmed = part.trim();
                int idx = trimmed.indexOf('=');
                if (idx > 0) {
                    merged.put(trimmed.substring(0, idx), trimmed.substring(idx + 1));
                }
            }
        }
        if (setCookies != null) {
            for (String cookie : setCookies) {
                if (cookie == null || cookie.isBlank()) {
                    continue;
                }
                String pair = cookie.split(";", 2)[0];
                int idx = pair.indexOf('=');
                if (idx > 0) {
                    merged.put(pair.substring(0, idx), pair.substring(idx + 1));
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    private record NeteaseResponse(JsonNode body, String cookie) {
    }
}
