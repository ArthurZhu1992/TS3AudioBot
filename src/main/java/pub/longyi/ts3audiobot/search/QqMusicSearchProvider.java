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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public final class QqMusicSearchProvider implements SearchProvider {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final String QQ_LOGIN_URL = "https://xui.ptlogin2.qq.com/cgi-bin/xlogin"
        + "?appid=716027609&daid=383&style=33&login_text=%E7%99%BB%E5%BD%95"
        + "&hide_title_bar=1&hide_border=1&target=self"
        + "&s_url=https%3A%2F%2Fy.qq.com%2Fportal%2Fprofile.html"
        + "&pt_3rd_aid=0&pt_feedback_link=https%3A%2F%2Fy.qq.com%2F"
        + "&theme=2&platform=1&need_qr=1";
    private static final Pattern LOGIN_SIG_PATTERN = Pattern.compile("login_sig=\\\"([^\\\"]+)\\\"");
    private static final Pattern LOGIN_SIG_PATTERN_SINGLE = Pattern.compile("login_sig='([^']+)'");
    private static final Pattern LOGIN_SIG_PATTERN_VAR = Pattern.compile("login_sig\\s*[:=]\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern LOGIN_SIG_PATTERN_G = Pattern.compile("g_login_sig\\s*=\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern LOGIN_SIG_PATTERN_G_SINGLE = Pattern.compile("g_login_sig\\s*=\\s*'([^']+)'");
    private static final Pattern LOGIN_SIG_PATTERN_PT = Pattern.compile("pt_login_sig=\\\"([^\\\"]+)\\\"");
    private static final Pattern PTUICB_PATTERN =
        Pattern.compile("ptuiCB\\('([^']*)','([^']*)','([^']*)','([^']*)','([^']*)'(?:,'([^']*)')?\\)");
    private static final Duration VIP_PROBE_TTL = Duration.ofMinutes(3);

    private final QqHeadlessLoginSupport headlessLoginSupport;
    private final Map<String, VipProbeCache> vipProbeCache = new ConcurrentHashMap<>();

    public QqMusicSearchProvider(QqHeadlessLoginSupport headlessLoginSupport) {
        this.headlessLoginSupport = headlessLoginSupport;
    }


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
        try {
            return headlessLoginSupport.startLogin(request);
        } catch (Exception ex) {
            log.warn("[Search:qq] headless login unavailable, fallback to legacy flow", ex);
        }
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
        if ("headless".equalsIgnoreCase(payload.getOrDefault("mode", ""))) {
            return headlessLoginSupport.pollLogin(request, payload);
        }
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
        String baseQuery = "u1=" + urlEncode("https://y.qq.com/portal/profile.html")
            + "&ptqrtoken=" + ptqrtoken
            + "&h=1&t=1&g=1&from_ui=1&ptlang=2052"
            + "&action=0-0-" + System.currentTimeMillis()
            + "&js_type=1&login_sig=" + urlEncode(loginSig)
            + "&pt_uistyle=40&aid=716027609&daid=383&pt_3rd_aid=0";
        String o1vId = UUID.randomUUID().toString().replace("-", "");
        List<String> pollUrls = List.of(
            "https://ssl.ptlogin2.qq.com/ptqrlogin?" + baseQuery + "&ptredirect=self&js_ver=25032513&o1vId=" + o1vId + "&pt_js_version=25032513",
            "https://ssl.ptlogin2.qq.com/ptqrlogin?" + baseQuery + "&ptredirect=0&js_ver=25032513&o1vId=" + o1vId + "&pt_js_version=25032513",
            "https://ssl.ptlogin2.qq.com/ptqrlogin?" + baseQuery + "&ptredirect=0&js_ver=20032614",
            "https://xui.ptlogin2.qq.com/ssl/ptqrlogin?" + baseQuery + "&ptredirect=self&js_ver=25032513&o1vId=" + o1vId + "&pt_js_version=25032513"
        );
        try {
            HttpResponse<byte[]> response = null;
            String body = "";
            String[] args = new String[0];
            for (String url : pollUrls) {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", QQ_LOGIN_URL)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .GET();
                if (!cookie.isBlank()) {
                    builder.header("Cookie", cookie);
                }
                response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
                body = decodeBody(response);
                args = parsePtuiArgs(body);
                if (args.length >= 5) {
                    break;
                }
                log.warn("[Search:qq] ptqrlogin response not matched: url={} status={} len={} body={}",
                    shrink(url),
                    response.statusCode(),
                    response.body() == null ? 0 : response.body().length,
                    shrink(body));
            }
            if (args.length < 5) {
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
                String mid = firstText(
                    item,
                    "songmid",
                    "mid",
                    "song_mid",
                    "songInfo.songmid",
                    "songInfo.mid",
                    "songinfo.songmid",
                    "songinfo.mid"
                );
                String title = extractSongTitle(item, request.query());
                String artist = extractSongArtist(item);
                String albummid = firstText(
                    item,
                    "albummid",
                    "album.mid",
                    "songInfo.albummid",
                    "songInfo.album.mid",
                    "songinfo.albummid",
                    "songinfo.album.mid"
                );
                String cover = albummid.isBlank()
                    ? ""
                    : "https://y.qq.com/music/photo_new/T002R300x300M000" + albummid + ".jpg?max_age=2592000";
                long durationMs = extractSongDurationMs(item);
                Long playCount = null;
                if (item.has("playcnt")) {
                    playCount = item.path("playcnt").asLong();
                }
                Boolean vipRequired = detectVipRequired(item);
                String vipHint = buildVipHint(vipRequired);
                String pageUrl = mid.isBlank() ? "" : "https://y.qq.com/n/ryqq/songDetail/" + mid;
                String uid = "qq:" + mid;
                items.add(new SearchItem(
                    uid,
                    mid,
                    title.isBlank() ? request.query() : title,
                    artist,
                    cover,
                    durationMs,
                    playCount,
                    pageUrl,
                    source(),
                    vipRequired,
                    vipHint
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
                    String mid = firstText(
                        item,
                        "songmid",
                        "mid",
                        "song_mid",
                        "songInfo.songmid",
                        "songInfo.mid",
                        "songinfo.songmid",
                        "songinfo.mid"
                    );
                    String title = extractSongTitle(item, "");
                    String artist = extractSongArtist(item);
                    String albummid = firstText(
                        item,
                        "albummid",
                        "album.mid",
                        "songInfo.albummid",
                        "songInfo.album.mid",
                        "songinfo.albummid",
                        "songinfo.album.mid"
                    );
                    String cover = albummid.isBlank()
                        ? ""
                        : "https://y.qq.com/music/photo_new/T002R300x300M000" + albummid + ".jpg?max_age=2592000";
                    long durationMs = extractSongDurationMs(item);
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

    public HeartbeatResult heartbeatAuth(SearchAuthStore.AuthRecord auth) {
        if (auth == null || auth.cookie() == null || auth.cookie().isBlank()) {
            return HeartbeatResult.invalid("missing cookie");
        }
        String cookie = auth.cookie();
        String uin = extractUin(cookie);
        if (uin.isBlank()) {
            return HeartbeatResult.invalid("missing uin");
        }
        try {
            long gtk = calcGtk(cookie);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("comm", Map.of(
                "ct", 24,
                "cv", 0,
                "g_tk", gtk,
                "uin", uin,
                "format", "json",
                "platform", "yqq",
                "needNewCode", 1
            ));
            body.put("req_0", Map.of(
                "module", "userInfo.BaseUserInfoServer",
                "method", "get_user_baseinfo_v2",
                "param", Map.of("vec_uin", List.of(uin))
            ));
            JsonResponse response = postJsonResponse("https://u.y.qq.com/cgi-bin/musicu.fcg", body, cookie);
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return HeartbeatResult.invalid("http_" + response.statusCode());
            }
            JsonNode req = response.body().path("req_0");
            int reqCode = req.path("code").asInt(0);
            if (reqCode != 0) {
                return HeartbeatResult.skipped("req_code_" + reqCode);
            }
            String mergedCookie = response.cookie();
            String finalCookie = mergedCookie == null || mergedCookie.isBlank() ? cookie : mergedCookie;
            String finalUin = extractUin(finalCookie);
            Map<String, String> extra = new LinkedHashMap<>();
            if (auth.extraJson() != null && !auth.extraJson().isBlank()) {
                try {
                    JsonNode root = MAPPER.readTree(auth.extraJson());
                    if (root != null && root.isObject()) {
                        root.fields().forEachRemaining(e -> extra.put(e.getKey(), e.getValue().asText("")));
                    }
                } catch (Exception ignored) {
                }
            }
            extra.put("uin", finalUin.isBlank() ? uin : finalUin);
            extra.put("g_tk", Long.toString(calcGtk(finalCookie)));
            extra.put("heartbeatAt", Instant.now().toString());
            SearchAuthStore.AuthRecord refreshed = new SearchAuthStore.AuthRecord(
                auth.source(),
                auth.scopeType(),
                auth.botId(),
                finalCookie,
                auth.token(),
                toJson(extra),
                null,
                Instant.now()
            );
            return HeartbeatResult.refreshed(refreshed);
        } catch (Exception ex) {
            log.debug("[Search:qq] heartbeat failed scope={} bot={}", auth.scopeType(), auth.botId(), ex);
            return HeartbeatResult.skipped("exception");
        }
    }

    public VipProbeResult probeVipStatus(SearchAuthStore.AuthRecord auth) {
        if (auth == null || auth.cookie() == null || auth.cookie().isBlank()) {
            return VipProbeResult.unknown("未登录，无法识别VIP状态");
        }
        String cookie = auth.cookie();
        String cacheKey = Integer.toHexString(cookie.hashCode());
        VipProbeCache cached = vipProbeCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.result();
        }
        String uin = extractUin(cookie);
        if (uin.isBlank()) {
            VipProbeResult result = VipProbeResult.unknown("缺少 uin，无法识别VIP状态");
            vipProbeCache.put(cacheKey, new VipProbeCache(result, Instant.now()));
            return result;
        }
        try {
            long gtk = calcGtk(cookie);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("comm", Map.of(
                "ct", 24,
                "cv", 0,
                "g_tk", gtk,
                "uin", uin,
                "format", "json",
                "platform", "yqq",
                "needNewCode", 1
            ));
            body.put("req_0", Map.of(
                "module", "userInfo.VipQueryServer",
                "method", "SRFVipQuery_V2",
                "param", Map.of("uin_list", List.of(uin))
            ));
            body.put("req_1", Map.of(
                "module", "userInfo.BaseUserInfoServer",
                "method", "get_user_baseinfo_v2",
                "param", Map.of("vec_uin", List.of(uin))
            ));
            JsonNode root = postJson("https://u.y.qq.com/cgi-bin/musicu.fcg", body, cookie);
            VipProbeResult result = parseVipProbeResult(root);
            vipProbeCache.put(cacheKey, new VipProbeCache(result, Instant.now()));
            return result;
        } catch (Exception ex) {
            log.debug("[Search:qq] failed to probe vip status", ex);
            VipProbeResult result = VipProbeResult.unknown("VIP状态检测失败");
            vipProbeCache.put(cacheKey, new VipProbeCache(result, Instant.now()));
            return result;
        }
    }

    private VipProbeResult parseVipProbeResult(JsonNode root) {
        if (root == null || root.isMissingNode()) {
            return VipProbeResult.unknown("VIP状态接口无响应");
        }
        JsonNode vipNode = firstNonMissingNode(
            root.path("req_0").path("data"),
            root.path("vip_req").path("data"),
            root.path("vip").path("data"),
            root.path("req_1").path("data"),
            root.path("base_req").path("data")
        );
        Boolean vip = resolveVipFlag(vipNode);
        if (vip != null) {
            if (vip) {
                return VipProbeResult.vip("已识别为QQ音乐VIP账号");
            }
            return VipProbeResult.nonVip("当前账号非QQ音乐VIP，部分歌曲可能无法播放");
        }
        return VipProbeResult.unknown("无法确认VIP状态");
    }

    private JsonNode firstNonMissingNode(JsonNode... candidates) {
        if (candidates == null) {
            return MAPPER.createObjectNode();
        }
        for (JsonNode node : candidates) {
            if (node != null && !node.isMissingNode() && !node.isNull() && !node.isEmpty()) {
                return node;
            }
        }
        return MAPPER.createObjectNode();
    }

    private Boolean resolveVipFlag(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        Boolean direct = readVipFlag(node);
        if (direct != null) {
            return direct;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                Boolean parsed = resolveVipFlag(child);
                if (parsed != null) {
                    return parsed;
                }
            }
            return null;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode child = entry.getValue();
                Boolean parsed = resolveVipFlag(child);
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
    }

    private Boolean readVipFlag(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String[] keys = {
            "is_vip",
            "isVip",
            "vip",
            "vip_flag",
            "vipFlag",
            "year_vip",
            "yearVip",
            "music_vip",
            "musicVip",
            "music_vip_level",
            "musicVipLevel",
            "vip_type",
            "vipType",
            "svip",
            "is_svip",
            "isSvip"
        };
        for (String key : keys) {
            if (!node.has(key)) {
                continue;
            }
            Boolean parsed = parseVipValue(node.path(key));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private Boolean parseVipValue(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isNumber()) {
            return value.asInt(0) > 0;
        }
        String text = value.asText("").trim().toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return null;
        }
        if ("1".equals(text) || "true".equals(text) || "yes".equals(text) || "vip".equals(text)) {
            return true;
        }
        if ("0".equals(text) || "false".equals(text) || "no".equals(text) || "normal".equals(text)) {
            return false;
        }
        return null;
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
        if (key.isBlank()) {
            key = extractCookieValue(cookie, "qm_keyst");
        }
        if (key.isBlank()) {
            key = extractCookieValue(cookie, "qqmusic_key");
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
        if (uin.isBlank()) {
            uin = extractCookieValue(cookieHeader, "qqmusic_uin");
        }
        if (uin.isBlank()) {
            uin = extractCookieValue(cookieHeader, "qm_uid");
        }
        if (uin.startsWith("o")) {
            uin = uin.substring(1);
        }
        return uin.replaceAll("^0+", "");
    }

    private JsonResponse postJsonResponse(String url, Object body, String cookie) {
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
            JsonNode root = MAPPER.readTree(bodyText);
            String mergedCookie = mergeCookies(cookie, response.headers().allValues("Set-Cookie"));
            return new JsonResponse(root, mergedCookie, response.statusCode());
        } catch (Exception ex) {
            log.warn("[Search:qq] heartbeat post json failed {}", url, ex);
            return new JsonResponse(MAPPER.createObjectNode(), cookie == null ? "" : cookie, 500);
        }
    }

    private String extractSongTitle(JsonNode item, String fallback) {
        String title = firstText(
            item,
            "songname",
            "title",
            "name",
            "song_name",
            "songInfo.songname",
            "songInfo.title",
            "songInfo.name",
            "songinfo.songname",
            "songinfo.title",
            "songinfo.name"
        );
        if (title.isBlank()) {
            title = fallback == null ? "" : fallback.trim();
        }
        return title;
    }

    private String extractSongArtist(JsonNode item) {
        JsonNode singerNode = item.path("singer");
        String artist = joinArtists(singerNode);
        if (!artist.isBlank()) {
            return artist;
        }
        artist = joinArtists(item.path("songInfo").path("singer"));
        if (!artist.isBlank()) {
            return artist;
        }
        artist = joinArtists(item.path("songinfo").path("singer"));
        if (!artist.isBlank()) {
            return artist;
        }
        return firstText(item, "subtitle", "songInfo.subtitle", "songinfo.subtitle");
    }

    private long extractSongDurationMs(JsonNode item) {
        long seconds = firstLong(
            item,
            "interval",
            "duration",
            "songInfo.interval",
            "songInfo.duration",
            "songinfo.interval",
            "songinfo.duration"
        );
        if (seconds <= 0) {
            return 0L;
        }
        // QQ fields are usually seconds; guard against already-ms values.
        return seconds >= 1000 ? seconds : seconds * 1000L;
    }

    private Boolean detectVipRequired(JsonNode item) {
        Integer payPlay = firstInt(item,
            "pay.pay_play",
            "pay.payPlay",
            "pay_info.pay_play",
            "pay_info.payPlay",
            "songInfo.pay.pay_play",
            "songInfo.pay_info.pay_play",
            "songinfo.pay.pay_play",
            "songinfo.pay_info.pay_play"
        );
        if (payPlay != null) {
            return payPlay > 0;
        }
        Integer payDown = firstInt(item,
            "pay.pay_down",
            "pay.payDown",
            "pay_info.pay_down",
            "pay_info.payDown",
            "songInfo.pay.pay_down",
            "songInfo.pay_info.pay_down",
            "songinfo.pay.pay_down",
            "songinfo.pay_info.pay_down"
        );
        if (payDown != null) {
            return payDown > 0;
        }
        Integer payStatus = firstInt(item,
            "pay.pay_status",
            "pay.payStatus",
            "pay_info.pay_status",
            "pay_info.payStatus",
            "songInfo.pay.pay_status",
            "songInfo.pay_info.pay_status",
            "songinfo.pay.pay_status",
            "songinfo.pay_info.pay_status"
        );
        if (payStatus != null) {
            return payStatus > 0;
        }
        Integer payMonth = firstInt(item,
            "pay.pay_month",
            "pay.payMonth",
            "pay_info.pay_month",
            "pay_info.payMonth",
            "songInfo.pay.pay_month",
            "songInfo.pay_info.pay_month",
            "songinfo.pay.pay_month",
            "songinfo.pay_info.pay_month"
        );
        if (payMonth != null) {
            return payMonth > 0;
        }
        return null;
    }

    private String buildVipHint(Boolean vipRequired) {
        if (vipRequired == null) {
            return "未能识别该歌曲是否需要VIP";
        }
        if (vipRequired) {
            return "该歌曲可能需要VIP或单曲购买";
        }
        return "该歌曲通常可直接播放";
    }

    private String firstText(JsonNode root, String... paths) {
        if (root == null || paths == null) {
            return "";
        }
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            JsonNode node = root;
            for (String part : path.split("\\.")) {
                if (node == null || node.isMissingNode()) {
                    break;
                }
                node = node.path(part);
            }
            if (node != null && !node.isMissingNode()) {
                String text = node.asText("").trim();
                if (!text.isBlank() && !"null".equalsIgnoreCase(text)) {
                    return text;
                }
            }
        }
        return "";
    }

    private long firstLong(JsonNode root, String... paths) {
        if (root == null || paths == null) {
            return 0L;
        }
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            JsonNode node = root;
            for (String part : path.split("\\.")) {
                if (node == null || node.isMissingNode()) {
                    break;
                }
                node = node.path(part);
            }
            if (node == null || node.isMissingNode()) {
                continue;
            }
            if (node.isNumber()) {
                long value = node.asLong(0L);
                if (value > 0) {
                    return value;
                }
                continue;
            }
            String text = node.asText("").trim();
            if (text.isBlank()) {
                continue;
            }
            try {
                long value = Long.parseLong(text);
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    private Integer firstInt(JsonNode root, String... paths) {
        if (root == null || paths == null) {
            return null;
        }
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            JsonNode node = root;
            for (String part : path.split("\\.")) {
                if (node == null || node.isMissingNode()) {
                    break;
                }
                node = node.path(part);
            }
            if (node == null || node.isMissingNode() || node.isNull()) {
                continue;
            }
            if (node.isNumber()) {
                return node.asInt();
            }
            String text = node.asText("").trim();
            if (text.isBlank()) {
                continue;
            }
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
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

    public record VipProbeResult(String state, String message) {
        private static VipProbeResult vip(String message) {
            return new VipProbeResult("vip", message);
        }

        private static VipProbeResult nonVip(String message) {
            return new VipProbeResult("non_vip", message);
        }

        private static VipProbeResult unknown(String message) {
            return new VipProbeResult("unknown", message);
        }
    }

    public record HeartbeatResult(
        boolean refreshed,
        boolean invalid,
        SearchAuthStore.AuthRecord authRecord,
        String message
    ) {
        public static HeartbeatResult refreshed(SearchAuthStore.AuthRecord authRecord) {
            return new HeartbeatResult(true, false, authRecord, "ok");
        }

        public static HeartbeatResult skipped(String message) {
            return new HeartbeatResult(false, false, null, message == null ? "" : message);
        }

        public static HeartbeatResult invalid(String message) {
            return new HeartbeatResult(false, true, null, message == null ? "" : message);
        }
    }

    private record JsonResponse(JsonNode body, String cookie, int statusCode) {
    }

    private record VipProbeCache(VipProbeResult result, Instant createdAt) {
        private boolean isExpired() {
            if (createdAt == null) {
                return true;
            }
            return Instant.now().isAfter(createdAt.plus(VIP_PROBE_TTL));
        }
    }

    private record LoginSigInfo(String sig, String cookie) {
    }
}









