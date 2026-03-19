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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public final class QqMusicSearchProvider implements SearchProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Pattern LOGIN_SIG_PATTERN = Pattern.compile("login_sig=\\\"([^\\\"]+)\\\"");
    private static final Pattern LOGIN_SIG_PATTERN_SINGLE = Pattern.compile("login_sig='([^']+)'");
    private static final Pattern LOGIN_SIG_PATTERN_VAR = Pattern.compile("login_sig\\s*[:=]\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern LOGIN_SIG_PATTERN_G = Pattern.compile("g_login_sig\\s*=\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern LOGIN_SIG_PATTERN_G_SINGLE = Pattern.compile("g_login_sig\\s*=\\s*'([^']+)'");
    private static final Pattern LOGIN_SIG_PATTERN_PT = Pattern.compile("pt_login_sig=\\\"([^\\\"]+)\\\"");
    private static final Pattern PTUICB_PATTERN =
        Pattern.compile("ptuiCB\\('([^']*)','([^']*)','([^']*)','([^']*)','([^']*)'(?:,'([^']*)')?\\)");

    @Override
    public String source() {
        return "qq";
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
        LoginSigInfo loginSigInfo = fetchLoginSig();
        String loginSig = loginSigInfo.sig();
        HttpRequest qrRequest = HttpRequest.newBuilder()
            .uri(URI.create("https://ssl.ptlogin2.qq.com/ptqrshow?appid=716027609&e=2&l=M&s=3&d=72&v=4&t="
                + Math.random()))
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://y.qq.com/")
            .GET()
            .build();
        try {
            HttpResponse<byte[]> response = CLIENT.send(qrRequest, HttpResponse.BodyHandlers.ofByteArray());
            String qrsig = extractCookie(response.headers().allValues("Set-Cookie"), "qrsig");
            String cookie = mergeCookies(loginSigInfo.cookie(), response.headers().allValues("Set-Cookie"));
            if (loginSig.isBlank()) {
                loginSig = extractCookieValue(cookie, "pt_login_sig");
                if (loginSig.isBlank()) {
                    loginSig = extractCookieValue(cookie, "login_sig");
                }
            }
            if (loginSig.isBlank()) {
                return new LoginStartResult("", "", "", "", 0, "\u83b7\u53d6\u767b\u5f55\u7b7e\u540d\u5931\u8d25");
            }
            if (qrsig.isBlank()) {
                return new LoginStartResult("", "", "", "", 0, "\u83b7\u53d6\u4e8c\u7ef4\u7801\u5931\u8d25");
            }
            String qrImage = Base64.getEncoder().encodeToString(response.body());
            String payload = toJson(Map.of(
                "qrsig", qrsig,
                "loginSig", loginSig,
                "cookie", cookie
            ));
            return new LoginStartResult(UUID.randomUUID().toString(), qrImage, "", payload, 300, "\u8bf7\u626b\u7801\u767b\u5f55");
        } catch (Exception ex) {
            log.warn("[Search:qq] failed to start login", ex);
            return new LoginStartResult("", "", "", "", 0, "\u83b7\u53d6\u4e8c\u7ef4\u7801\u5931\u8d25");
        }
    }

    @Override
    public LoginPollResult pollLogin(LoginPollRequest request) {
        Map<String, String> payload = parsePayload(request.payload());
        String qrsig = payload.getOrDefault("qrsig", "");
        String loginSig = payload.getOrDefault("loginSig", "");
        String cookie = payload.getOrDefault("cookie", "");
        if (qrsig.isBlank()) {
            return new LoginPollResult(LoginStatus.ERROR, "\u767b\u5f55\u4f1a\u8bdd\u5df2\u5931\u6548", request.payload(), null);
        }
        if (loginSig.isBlank()) {
            loginSig = extractCookieValue(cookie, "pt_login_sig");
            if (loginSig.isBlank()) {
                loginSig = extractCookieValue(cookie, "login_sig");
            }
        }
        long ptqrtoken = calcPtqrToken(qrsig);
        String url = "https://ssl.ptlogin2.qq.com/ptqrlogin?u1="
            + urlEncode("https://y.qq.com/portal/profile.html")
            + "&ptqrtoken=" + ptqrtoken
            + "&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052"
            + "&action=0-0-" + System.currentTimeMillis()
            + "&js_ver=20032614&js_type=1&login_sig=" + urlEncode(loginSig)
            + "&pt_uistyle=40&aid=716027609&daid=383&pt_3rd_aid=0";
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "Mozilla/5.0")
            .header("Referer", "https://y.qq.com/")
            .GET();
        if (!cookie.isBlank()) {
            builder.header("Cookie", cookie);
        }
        try {
            HttpResponse<byte[]> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            String body = decodeBody(response);
            String[] args = parsePtuiArgs(body);
            if (args.length < 5) {
                log.warn("[Search:qq] ptqrlogin response not matched: status={} len={} body={}",
                    response.statusCode(),
                    response.body() == null ? 0 : response.body().length,
                    shrink(body));
                return new LoginPollResult(LoginStatus.ERROR, "\u767b\u5f55\u72b6\u6001\u89e3\u6790\u5931\u8d25", request.payload(), null);
            }
            String code = args[0];
            String redirect = args[2];
            String message = args[4];
            if (!"0".equals(code) && !"65".equals(code) && !"66".equals(code) && !"67".equals(code)) {
                log.warn("[Search:qq] ptqrlogin unexpected code={} msg={} body={}", code, message, shrink(body));
            }
            if ("66".equals(code)) {
                return new LoginPollResult(LoginStatus.PENDING, message.isBlank() ? "\u7b49\u5f85\u626b\u7801" : message, request.payload(), null);
            }
            if ("67".equals(code)) {
                return new LoginPollResult(LoginStatus.SCANNED, message.isBlank() ? "\u5df2\u626b\u7801\uff0c\u7b49\u5f85\u786e\u8ba4" : message, request.payload(), null);
            }
            if ("65".equals(code)) {
                return new LoginPollResult(LoginStatus.EXPIRED, message.isBlank() ? "\u4e8c\u7ef4\u7801\u5df2\u8fc7\u671f" : message, request.payload(), null);
            }
            if (!"0".equals(code) || redirect.isBlank()) {
                log.warn("[Search:qq] ptqrlogin login failed code={} redirect={} msg={}", code, redirect, message);
                return new LoginPollResult(LoginStatus.ERROR, "\u767b\u5f55\u5931\u8d25", request.payload(), null);
            }
            String merged = mergeCookies(cookie, response.headers().allValues("Set-Cookie"));
            String finalCookie = completeLogin(redirect, merged);
            String uin = extractUin(finalCookie);
            String extra = toJson(Map.of(
                "uin", uin,
                "g_tk", Long.toString(calcGtk(finalCookie))
            ));
            SearchAuthStore.AuthRecord auth = new SearchAuthStore.AuthRecord(
                source(),
                request.scopeType(),
                request.botId(),
                finalCookie,
                "",
                extra,
                null,
                Instant.now()
            );
            return new LoginPollResult(LoginStatus.CONFIRMED, "\u767b\u5f55\u6210\u529f", request.payload(), auth);
        } catch (Exception ex) {
            log.warn("[Search:qq] poll login failed", ex);
            return new LoginPollResult(LoginStatus.ERROR, "\u767b\u5f55\u5931\u8d25", request.payload(), null);
        }
    }

    @Override
    public SearchPage search(SearchRequest request) {
        String cookie = request.auth() == null ? "" : request.auth().cookie();
        long gtk = calcGtk(cookie);
        String uin = extractUin(cookie);
        Map<String, Object> body = Map.of(
            "comm", Map.of(
                "ct", 24,
                "cv", 0,
                "g_tk", gtk,
                "uin", uin.isBlank() ? "0" : uin,
                "format", "json",
                "platform", "yqq",
                "needNewCode", 1
            ),
            "req_0", Map.of(
                "module", "music.search.SearchCgiService",
                "method", "DoSearchForQQMusicDesktop",
                "param", Map.of(
                    "search_type", 0,
                    "query", request.query(),
                    "page_num", request.page(),
                    "num_per_page", request.pageSize()
                )
            )
        );
        JsonNode root = postJson("https://u.y.qq.com/cgi-bin/musicu.fcg", body, cookie);
        JsonNode song = root.path("req_0").path("data").path("body").path("song");
        JsonNode list = song.path("list");
        int total = song.path("totalnum").asInt(0);
        List<SearchItem> items = new ArrayList<>();
        if (list.isArray()) {
            for (JsonNode item : list) {
                String mid = item.path("songmid").asText("");
                String title = item.path("songname").asText("");
                String artist = joinArtists(item.path("singer"));
                String albummid = item.path("albummid").asText("");
                String cover = albummid.isBlank()
                    ? ""
                    : "https://y.qq.com/music/photo_new/T002R300x300M000" + albummid + ".jpg?max_age=2592000";
                long durationMs = item.path("interval").asLong(0) * 1000L;
                Long playCount = null;
                if (item.has("playcnt")) {
                    playCount = item.path("playcnt").asLong();
                }
                String pageUrl = mid.isBlank() ? "" : "https://y.qq.com/n/ryqq/songDetail/" + mid;
                String uid = "qq:" + mid;
                items.add(new SearchItem(
                    uid,
                    mid,
                    title,
                    artist,
                    cover,
                    durationMs,
                    playCount,
                    pageUrl,
                    source()
                ));
            }
        }
        return new SearchPage(items, request.page(), request.pageSize(), total);
    }

    @Override
    public PlaylistPage listPlaylists(PlaylistRequest request) {
        String cookie = request.auth() == null ? "" : request.auth().cookie();
        String uin = extractUin(cookie);
        if (uin.isBlank()) {
            return new PlaylistPage(List.of(), request.page(), request.pageSize(), 0);
        }
        long gtk = calcGtk(cookie);
        int offset = (request.page() - 1) * request.pageSize();
        String url = "https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss"
            + "?hostuin=" + urlEncode(uin)
            + "&sin=" + offset
            + "&size=" + request.pageSize()
            + "&g_tk=" + gtk
            + "&format=json";
        JsonNode root = getJson(url, cookie);
        JsonNode list = root.path("data").path("disslist");
        List<PlaylistItem> items = new ArrayList<>();
        if (list.isArray()) {
            for (JsonNode item : list) {
                String id = item.path("dissid").asText("");
                String name = item.path("dissname").asText("");
                String cover = item.path("imgurl").asText("");
                int trackCount = item.path("song_cnt").asInt(0);
                long playCount = item.path("visitnum").asLong(0);
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
        int total = root.path("data").path("total").asInt(items.size());
        return new PlaylistPage(items, request.page(), request.pageSize(), total);
    }

    @Override
    public PlaylistTrackPage listPlaylistTracks(PlaylistTracksRequest request) {
        String cookie = request.auth() == null ? "" : request.auth().cookie();
        long gtk = calcGtk(cookie);
        String url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp"
            + "?type=1&disstid=" + urlEncode(request.playlistId())
            + "&utf8=1&onlysong=0&new_format=1&g_tk=" + gtk
            + "&format=json";
        JsonNode root = getJson(url, cookie);
        JsonNode list = root.path("cdlist");
        List<PlaylistTrackItem> items = new ArrayList<>();
        if (list.isArray() && list.size() > 0) {
            JsonNode songlist = list.get(0).path("songlist");
            if (songlist.isArray()) {
                for (JsonNode item : songlist) {
                    String mid = item.path("songmid").asText("");
                    String title = item.path("songname").asText("");
                    String artist = joinArtists(item.path("singer"));
                    String albummid = item.path("albummid").asText("");
                    String cover = albummid.isBlank()
                        ? ""
                        : "https://y.qq.com/music/photo_new/T002R300x300M000" + albummid + ".jpg?max_age=2592000";
                    long durationMs = item.path("interval").asLong(0) * 1000L;
                    String pageUrl = mid.isBlank() ? "" : "https://y.qq.com/n/ryqq/songDetail/" + mid;
                    String uid = "qq:" + mid;
                    items.add(new PlaylistTrackItem(
                        uid,
                        mid,
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
        }
        return new PlaylistTrackPage(items, request.page(), request.pageSize(), items.size());
    }

    private LoginSigInfo fetchLoginSig() {
        String url = "https://xui.ptlogin2.qq.com/cgi-bin/xlogin"
            + "?appid=716027609&daid=383&style=33&login_text=%E7%99%BB%E5%BD%95"
            + "&hide_title_bar=1&hide_border=1&target=self"
            + "&s_url=https%3A%2F%2Fy.qq.com%2Fportal%2Fprofile.html"
            + "&pt_3rd_aid=0&pt_feedback_link=https%3A%2F%2Fy.qq.com%2F"
            + "&theme=2&platform=1&need_qr=1";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://y.qq.com/")
                .GET()
                .build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            String sig = extractLoginSig(body);
            String cookie = mergeCookies("", response.headers().allValues("Set-Cookie"));
            if (sig.isBlank()) {
                sig = extractCookieValue(cookie, "pt_login_sig");
            }
            if (sig.isBlank()) {
                sig = extractCookieValue(cookie, "login_sig");
            }
            if (!sig.isBlank()) {
                return new LoginSigInfo(sig, cookie);
            }
        } catch (Exception ex) {
            log.warn("[Search:qq] failed to fetch login_sig", ex);
        }
        return new LoginSigInfo("", "");
    }

    private String completeLogin(String redirectUrl, String cookie) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(redirectUrl))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://y.qq.com/")
                .GET();
            if (cookie != null && !cookie.isBlank()) {
                builder.header("Cookie", cookie);
            }
            HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return mergeCookies(cookie, response.headers().allValues("Set-Cookie"));
        } catch (Exception ex) {
            log.warn("[Search:qq] failed to complete login", ex);
            return cookie;
        }
    }

    private JsonNode postJson(String url, Object body, String cookie) {
        try {
            String json = MAPPER.writeValueAsString(body);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://y.qq.com/")
                .POST(HttpRequest.BodyPublishers.ofString(json));
            if (cookie != null && !cookie.isBlank()) {
                builder.header("Cookie", cookie);
            }
            HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String bodyText = stripJsonp(response.body());
            return MAPPER.readTree(bodyText);
        } catch (Exception ex) {
            log.warn("[Search:qq] post json failed {}", url, ex);
            return MAPPER.createObjectNode();
        }
    }

    private JsonNode getJson(String url, String cookie) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://y.qq.com/")
                .GET();
            if (cookie != null && !cookie.isBlank()) {
                builder.header("Cookie", cookie);
            }
            HttpResponse<String> response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String bodyText = stripJsonp(response.body());
            return MAPPER.readTree(bodyText);
        } catch (Exception ex) {
            log.warn("[Search:qq] get json failed {}", url, ex);
            return MAPPER.createObjectNode();
        }
    }

    private String stripJsonp(String body) {
        if (body == null) {
            return "{}";
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return "{}";
    }

    private long calcPtqrToken(String qrsig) {
        long hash = 0;
        for (char c : qrsig.toCharArray()) {
            hash += (hash << 5) + c;
        }
        return hash & 0x7fffffffL;
    }

    private long calcGtk(String cookie) {
        String key = extractCookieValue(cookie, "p_skey");
        if (key.isBlank()) {
            key = extractCookieValue(cookie, "skey");
        }
        long hash = 5381;
        for (char c : key.toCharArray()) {
            hash += (hash << 5) + c;
        }
        return hash & 0x7fffffffL;
    }

    private String extractCookie(List<String> cookies, String name) {
        if (cookies == null || name == null) {
            return "";
        }
        for (String cookie : cookies) {
            if (cookie == null) {
                continue;
            }
            String pair = cookie.split(";", 2)[0];
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        return "";
    }

    private String extractCookieValue(String cookieHeader, String name) {
        if (cookieHeader == null || name == null) {
            return "";
        }
        String[] parts = cookieHeader.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                return trimmed.substring(name.length() + 1);
            }
        }
        return "";
    }

    private String extractUin(String cookieHeader) {
        String uin = extractCookieValue(cookieHeader, "uin");
        if (uin.isBlank()) {
            uin = extractCookieValue(cookieHeader, "p_uin");
        }
        if (uin.startsWith("o")) {
            uin = uin.substring(1);
        }
        return uin.replaceAll("^0+", "");
    }

    private String joinArtists(JsonNode singerNode) {
        if (singerNode == null || !singerNode.isArray()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode item : singerNode) {
            String name = item.path("name").asText("");
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
    private String extractLoginSig(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        Matcher matcher = LOGIN_SIG_PATTERN.matcher(body);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = LOGIN_SIG_PATTERN_SINGLE.matcher(body);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = LOGIN_SIG_PATTERN_VAR.matcher(body);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = LOGIN_SIG_PATTERN_G.matcher(body);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = LOGIN_SIG_PATTERN_G_SINGLE.matcher(body);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = LOGIN_SIG_PATTERN_PT.matcher(body);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
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

    private String decodeBody(HttpResponse<byte[]> response) {
        if (response == null || response.body() == null || response.body().length == 0) {
            return "";
        }
        String charset = response.headers()
            .firstValue("Content-Type")
            .map(value -> {
                int idx = value.toLowerCase(Locale.ROOT).indexOf("charset=");
                if (idx < 0) {
                    return "";
                }
                return value.substring(idx + 8).trim();
            })
            .orElse("");
        try {
            if (!charset.isBlank()) {
                return new String(response.body(), java.nio.charset.Charset.forName(charset));
            }
        } catch (Exception ignored) {
        }
        String utf8 = new String(response.body(), StandardCharsets.UTF_8);
        if (utf8.contains("ptuiCB")) {
            return utf8;
        }
        try {
            String gbk = new String(response.body(), java.nio.charset.Charset.forName("GBK"));
            if (gbk.contains("ptuiCB")) {
                return gbk;
            }
        } catch (Exception ignored) {
        }
        return utf8;
    }

    private String shrink(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.replaceAll("\\s+", " ").trim();
        if (trimmed.length() <= 220) {
            return trimmed;
        }
        return trimmed.substring(0, 220) + "...";
    }

    private String[] parsePtuiArgs(String body) {
        if (body == null || body.isBlank()) {
            return new String[0];
        }
        Matcher matcher = PTUICB_PATTERN.matcher(body);
        if (matcher.find()) {
            List<String> args = new ArrayList<>();
            for (int i = 1; i <= matcher.groupCount(); i++) {
                String value = matcher.group(i);
                if (value == null) {
                    break;
                }
                args.add(value);
            }
            if (!args.isEmpty()) {
                return args.toArray(new String[0]);
            }
        }
        int start = body.indexOf("ptuiCB");
        if (start < 0) {
            return new String[0];
        }
        int open = body.indexOf('(', start);
        int close = body.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return new String[0];
        }
        String inner = body.substring(open + 1, close);
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escape = false;
        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);
            if (quote != 0) {
                if (escape) {
                    current.append(ch);
                    escape = false;
                    continue;
                }
                if (ch == '\\') {
                    escape = true;
                    continue;
                }
                if (ch == quote) {
                    quote = 0;
                    continue;
                }
                current.append(ch);
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                continue;
            }
            if (ch == ',') {
                args.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        String last = current.toString().trim();
        if (!last.isEmpty() || !args.isEmpty()) {
            args.add(last);
        }
        return args.toArray(new String[0]);
    }

    private record LoginSigInfo(String sig, String cookie) {
    }
}








