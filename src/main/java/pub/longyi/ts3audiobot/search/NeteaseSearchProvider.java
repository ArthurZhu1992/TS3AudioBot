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
    private static final int[] QR_LOGIN_TYPES = new int[] {3, 1, 2, 4};
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
        String unikey = "";
        int qrType = QR_LOGIN_TYPES[0];
        String apiCookie = "";
        for (int candidateType : QR_LOGIN_TYPES) {
            ApiResponse unikeyResponse = postApi("/api/login/qrcode/unikey?type=" + candidateType, null);
            JsonNode unikeyNode = unikeyResponse.body();
            unikey = unikeyNode.path("unikey").asText("");
            if (unikey.isBlank()) {
                unikey = unikeyNode.path("data").path("unikey").asText("");
            }
            if (!unikey.isBlank()) {
                qrType = candidateType;
                apiCookie = unikeyResponse.cookie() == null ? "" : unikeyResponse.cookie();
                break;
            }
        }
        if (unikey.isBlank()) {
            return new LoginStartResult("", "", "", "", 0, "获取二维码失败");
        }
        String qrurl = "https://music.163.com/login?codekey=" + urlEncode(unikey);
        ApiResponse pageResponse = getApi("/login?codekey=" + urlEncode(unikey), apiCookie);
        apiCookie = combineCookies(apiCookie, pageResponse.cookie());
        String payload = buildLoginPayload(unikey, qrType, qrurl, apiCookie);
        return new LoginStartResult(UUID.randomUUID().toString(), "", qrurl, payload, 300, "请扫码登录");
    }

    @Override
    public LoginPollResult pollLogin(LoginPollRequest request) {
        Map<String, String> payload = parsePayload(request.payload());
        String unikey = payload.getOrDefault("unikey", "");
        String qrUrl = payload.getOrDefault("qrurl", "");
        String typeRaw = payload.getOrDefault("type", Integer.toString(QR_LOGIN_TYPES[0]));
        int preferredType = parseQrType(typeRaw);
        String apiCookie = payload.getOrDefault("apiCookie", "");
        if (unikey.isBlank()) {
            return new LoginPollResult(LoginStatus.ERROR, "登录会话已失效", request.payload(), null);
        }

        ApiPoll successPoll = null;
        String pendingMessage = "";
        String scannedMessage = "";
        String expiredMessage = "";
        String errorMessage = "";
        boolean unsupported = false;
        int scannedType = preferredType;
        int pendingType = preferredType;
        int expiredType = preferredType;
        int errorType = preferredType;
        for (int type : buildPollTypeOrder(preferredType)) {
            ApiPoll poll = pollApi(unikey, type, apiCookie);
            apiCookie = poll.response().cookie() == null ? apiCookie : poll.response().cookie();
            if (poll.code() == 803) {
                successPoll = poll;
                break;
            }
            if (isUnsupportedQrMessage(poll.message())) {
                unsupported = true;
                log.info("[Search:netease] qr poll unsupported type={} message={}", type, poll.message());
                continue;
            }
            if (poll.code() == 802 && scannedMessage.isBlank()) {
                scannedMessage = poll.message();
                scannedType = type;
                continue;
            }
            if (poll.code() == 801 && pendingMessage.isBlank()) {
                pendingMessage = poll.message();
                pendingType = type;
                continue;
            }
            if (poll.code() == 800 && expiredMessage.isBlank()) {
                expiredMessage = poll.message();
                expiredType = type;
                continue;
            }
            if (!poll.message().isBlank()) {
                errorMessage = poll.message();
                errorType = type;
            }
        }

        String updatedPayload = buildLoginPayload(unikey, preferredType, qrUrl, apiCookie);
        if (successPoll == null) {
            if (!scannedMessage.isBlank()) {
                return new LoginPollResult(
                    LoginStatus.SCANNED,
                    scannedMessage,
                    buildLoginPayload(unikey, scannedType, qrUrl, apiCookie),
                    null
                );
            }
            if (!pendingMessage.isBlank()) {
                return new LoginPollResult(
                    LoginStatus.PENDING,
                    pendingMessage,
                    buildLoginPayload(unikey, pendingType, qrUrl, apiCookie),
                    null
                );
            }
            if (!expiredMessage.isBlank()) {
                return new LoginPollResult(
                    LoginStatus.EXPIRED,
                    expiredMessage,
                    buildLoginPayload(unikey, expiredType, qrUrl, apiCookie),
                    null
                );
            }
            if (unsupported) {
                return new LoginPollResult(
                    LoginStatus.ERROR,
                    "网易云二维码授权受限，请改用网页登录导入",
                    updatedPayload,
                    null
                );
            }
            return new LoginPollResult(
                LoginStatus.ERROR,
                errorMessage.isBlank() ? "登录失败" : errorMessage,
                buildLoginPayload(unikey, errorType, qrUrl, apiCookie),
                null
            );
        }

        JsonNode result = successPoll.body();
        String cookie = result.path("cookie").asText("");
        if (cookie.isBlank()) {
            cookie = result.path("data").path("cookie").asText("");
        }
        if (cookie.isBlank()) {
            cookie = successPoll.response().cookie() == null ? "" : successPoll.response().cookie();
        }
        if (cookie.isBlank()) {
            ApiPoll retry = pollApi(unikey, successPoll.type(), apiCookie);
            apiCookie = retry.response().cookie() == null ? apiCookie : retry.response().cookie();
            cookie = retry.response().cookie() == null ? "" : retry.response().cookie();
        }
        if (cookie.isBlank()) {
            cookie = apiCookie;
        }
        NeteaseResponse legacyCheck = postWeapi(
            "/weapi/login/qrcode/check",
            Map.of("key", unikey, "type", 1),
            cookie
        );
        ApiResponse accountCheck = getApi("/api/nuser/account/get", combineCookies(cookie, apiCookie, legacyCheck.cookie()));
        String mergedCookie = combineCookies(cookie, apiCookie, legacyCheck.cookie(), accountCheck.cookie());
        AccountProfile profile = resolveAccountProfile(mergedCookie);
        if (profile.userId().isBlank() && profile.nickname().isBlank()) {
            log.info("[Search:netease] qr confirmed but account profile unresolved");
            return new LoginPollResult(
                LoginStatus.ERROR,
                "二维码确认成功，但未获取到有效登录凭据，请改用网页登录导入",
                updatedPayload,
                null
            );
        }
        String accountJson = "";
        String userId = profile.userId();
        String nickname = profile.nickname();
        try {
            accountJson = toJson(Map.of(
                "userId", userId,
                "nickname", nickname,
                "vipType", Integer.toString(profile.vipType()),
                "redVipLevel", Integer.toString(profile.redVipLevel())
            ));
        } catch (Exception ex) {
            log.warn("[Search:netease] failed to resolve account info", ex);
        }
        SearchAuthStore.AuthRecord auth = new SearchAuthStore.AuthRecord(
            source(),
            request.scopeType(),
            request.botId(),
            mergedCookie,
            "",
            accountJson,
            null,
            Instant.now()
        );
        return new LoginPollResult(LoginStatus.CONFIRMED, "登录成功", updatedPayload, auth);
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
                VipTrackInfo vip = resolveTrackVipInfo(song);
                items.add(new SearchItem(
                    uid,
                    id,
                    title,
                    artist,
                    cover,
                    durationMs,
                    null,
                    pageUrl,
                    source(),
                    vip.vipRequired(),
                    vip.vipHint()
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

    AccountProfile resolveAccountProfile(String cookie) {
        if (cookie == null || cookie.isBlank()) {
            return new AccountProfile("", "", 0, "", 0, 0);
        }
        JsonNode account = postWeapi("/weapi/w/nuser/account/get", Map.of(), cookie).body();
        JsonNode profile = account.path("profile");
        String userId = profile.path("userId").asText("");
        if (userId.isBlank()) {
            userId = account.path("account").path("id").asText("");
        }
        String nickname = profile.path("nickname").asText("");
        int vipType = profile.path("vipType").asInt(account.path("account").path("vipType").asInt(0));
        int redVipLevel = profile.path("redVipLevel").asInt(0);
        int code = account.path("code").asInt(0);
        String message = account.path("message").asText("");
        if (message.isBlank()) {
            message = account.path("msg").asText("");
        }
        return new AccountProfile(userId, nickname, code, message, vipType, redVipLevel);
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

    private ApiResponse postApi(String path, String cookie) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://music.163.com/")
            .POST(HttpRequest.BodyPublishers.noBody());
        if (cookie != null && !cookie.isBlank()) {
            builder.header("Cookie", cookie);
        }
        try {
            HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String mergedCookie = mergeCookies(cookie, response.headers().allValues("Set-Cookie"));
            JsonNode body = parseJsonSafely(response.body());
            return new ApiResponse(body, mergedCookie, response.statusCode());
        } catch (Exception ex) {
            log.warn("[Search:netease] api request failed path={}", path, ex);
            return new ApiResponse(MAPPER.createObjectNode(), cookie == null ? "" : cookie, 500);
        }
    }

    private ApiPoll pollApi(String unikey, int type, String cookie) {
        String csrf = extractCookieValue(cookie, "__csrf");
        String path = "/api/login/qrcode/client/login?key=" + urlEncode(unikey) + "&type=" + type;
        if (!csrf.isBlank()) {
            path += "&csrf_token=" + urlEncode(csrf);
        }
        ApiResponse response = postApi(
            path,
            cookie
        );
        JsonNode body = response.body();
        int code = body.path("code").asInt(0);
        String message = body.path("message").asText("");
        if (message.isBlank()) {
            message = body.path("msg").asText("");
        }
        return new ApiPoll(type, code, message, response, body);
    }

    private List<Integer> buildPollTypeOrder(int preferredType) {
        List<Integer> order = new ArrayList<>();
        order.add(preferredType);
        for (int type : QR_LOGIN_TYPES) {
            if (!order.contains(type)) {
                order.add(type);
            }
        }
        return order;
    }

    private ApiResponse getApi(String path, String cookie) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://music.163.com/")
            .GET();
        if (cookie != null && !cookie.isBlank()) {
            builder.header("Cookie", cookie);
        }
        try {
            HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String mergedCookie = mergeCookies(cookie, response.headers().allValues("Set-Cookie"));
            JsonNode body = parseJsonSafely(response.body());
            return new ApiResponse(body, mergedCookie, response.statusCode());
        } catch (Exception ex) {
            log.warn("[Search:netease] api get failed path={}", path, ex);
            return new ApiResponse(MAPPER.createObjectNode(), cookie == null ? "" : cookie, 500);
        }
    }

    private JsonNode parseJsonSafely(String body) {
        if (body == null || body.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception ex) {
            log.warn("[Search:netease] non-json response body={}", shrink(body));
            return MAPPER.createObjectNode();
        }
    }

    private String shrink(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() <= 400) {
            return oneLine;
        }
        return oneLine.substring(0, 400) + "...";
    }

    private String buildLoginPayload(String unikey, int type, String qrurl, String apiCookie) {
        Map<String, String> map = new HashMap<>();
        map.put("unikey", unikey == null ? "" : unikey);
        map.put("type", Integer.toString(type));
        map.put("qrurl", qrurl == null ? "" : qrurl);
        map.put("apiCookie", apiCookie == null ? "" : apiCookie);
        return toJson(map);
    }

    private String combineCookies(String... candidates) {
        Map<String, String> merged = new HashMap<>();
        if (candidates == null) {
            return "";
        }
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            for (String part : candidate.split(";")) {
                String trimmed = part == null ? "" : part.trim();
                int idx = trimmed.indexOf('=');
                if (idx <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, idx).trim();
                String value = trimmed.substring(idx + 1).trim();
                if (!key.isBlank() && !value.isBlank()) {
                    merged.put(key, value);
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

    private String extractCookieValue(String cookieHeader, String name) {
        if (cookieHeader == null || cookieHeader.isBlank() || name == null || name.isBlank()) {
            return "";
        }
        String marker = name + "=";
        for (String part : cookieHeader.split(";")) {
            String trimmed = part == null ? "" : part.trim();
            if (trimmed.startsWith(marker)) {
                return trimmed.substring(marker.length()).trim();
            }
        }
        return "";
    }

    private VipTrackInfo resolveTrackVipInfo(JsonNode song) {
        if (song == null || song.isMissingNode()) {
            return new VipTrackInfo(null, "未识别到歌曲权限信息");
        }
        int fee = song.path("fee").asInt(Integer.MIN_VALUE);
        if (fee == Integer.MIN_VALUE) {
            fee = song.path("privilege").path("fee").asInt(0);
        }
        int st = song.path("st").asInt(0);
        if (st < 0) {
            return new VipTrackInfo(null, "该歌曲可能受版权限制，暂不保证可播");
        }
        if (fee == 1 || fee == 4) {
            return new VipTrackInfo(true, "网易云标记为会员或付费歌曲");
        }
        if (fee == 8) {
            return new VipTrackInfo(false, "该歌曲通常可播放，部分音质可能需要会员");
        }
        if (fee == 0 || fee == 16) {
            return new VipTrackInfo(false, "该歌曲通常可直接播放");
        }
        return new VipTrackInfo(null, "未能识别该歌曲是否需要会员");
    }

    private int parseQrType(String raw) {
        if (raw == null || raw.isBlank()) {
            return QR_LOGIN_TYPES[0];
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            for (int candidate : QR_LOGIN_TYPES) {
                if (candidate == parsed) {
                    return parsed;
                }
            }
        } catch (Exception ignored) {
        }
        return QR_LOGIN_TYPES[0];
    }

    private boolean isUnsupportedQrMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("请切换其他登录方式")
            || message.contains("升级新版本再试")
            || message.contains("暂不支持");
    }

    private record NeteaseResponse(JsonNode body, String cookie) {
    }

    private record ApiResponse(JsonNode body, String cookie, int statusCode) {
    }

    private record ApiPoll(int type, int code, String message, ApiResponse response, JsonNode body) {
    }

    record AccountProfile(String userId, String nickname, int code, String message, int vipType, int redVipLevel) {
    }

    private record VipTrackInfo(Boolean vipRequired, String vipHint) {
    }
}
