package pub.longyi.ts3audiobot.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public final class QqHeadlessLoginSupport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final String LOGIN_URL = "https://xui.ptlogin2.qq.com/cgi-bin/xlogin"
        + "?appid=716027609&daid=383&style=33&login_text=%E7%99%BB%E5%BD%95"
        + "&hide_title_bar=1&hide_border=1&target=self"
        + "&s_url=https%3A%2F%2Fy.qq.com%2Fportal%2Fprofile.html"
        + "&pt_3rd_aid=0&pt_feedback_link=https%3A%2F%2Fy.qq.com%2F"
        + "&theme=2&platform=1&need_qr=1";
    private static final List<String> COOKIE_URLS = List.of("https://y.qq.com/", "https://u.y.qq.com/", "https://c.y.qq.com/", "https://ssl.ptlogin2.qq.com/", "https://qq.com/");

    public SearchProvider.LoginStartResult startLogin(SearchProvider.LoginRequest request) {
        BrowserBinary browser = findBrowserBinary();
        if (browser == null) {
            throw new IllegalStateException("Browser not found. Install Chrome/Edge/Chromium or set QQ_BROWSER_BIN.");
        }
        List<Boolean> modes = resolveModes(firstNonBlank(System.getenv("QQ_BROWSER_HEADLESS"), ""));
        Exception last = null;
        for (boolean headless : modes) {
            try {
                return startLoginInMode(headless, browser);
            } catch (Exception ex) {
                last = ex;
                log.warn("[Search:qq] failed to start {} browser login", headless ? "headless" : "headed", ex);
            }
        }
        throw new IllegalStateException("QQ QR login failed. Check browser environment and risk control.", last);
    }

    private List<Boolean> resolveModes(String requested) {
        if (requested == null || requested.isBlank() || "auto".equalsIgnoreCase(requested)) {
            return isLinux() ? List.of(true) : List.of(true, false);
        }
        if ("false".equalsIgnoreCase(requested) || "0".equalsIgnoreCase(requested) || "no".equalsIgnoreCase(requested)) {
            return List.of(false);
        }
        return List.of(true);
    }

    private SearchProvider.LoginStartResult startLoginInMode(boolean headless, BrowserBinary browser) {
        String sessionId = UUID.randomUUID().toString();
        Path userDataDir = Paths.get("data", "browser-login", "qq", sessionId);
        ensureDir(userDataDir);
        int debugPort = findFreePort();
        Process process = startBrowser(browser, debugPort, userDataDir, headless);
        try {
            waitForDebugger(debugPort);
            JsonNode target = findOrCreateLoginTarget(debugPort);
            String targetId = target.path("id").asText("");
            String ws = target.path("webSocketDebuggerUrl").asText("");
            if (targetId.isBlank() || ws.isBlank()) {
                throw new IllegalStateException("Failed to resolve login target from browser debugger.");
            }
            String qrImage = captureQrImage(ws);
            if (qrImage.isBlank()) {
                throw new IllegalStateException("Failed to capture QQ QR code from browser page.");
            }
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("mode", "headless");
            payload.put("renderMode", headless ? "headless" : "headed");
            payload.put("debugPort", Integer.toString(debugPort));
            payload.put("targetId", targetId);
            payload.put("pid", Long.toString(process.pid()));
            payload.put("userDataDir", userDataDir.toAbsolutePath().toString());
            return new SearchProvider.LoginStartResult(sessionId, qrImage, "", toJson(payload), 300, "Scan this QR code with mobile QQ.");
        } catch (Exception ex) {
            cleanupProcess(process.pid(), userDataDir);
            throw ex;
        }
    }

    public SearchProvider.LoginPollResult pollLogin(SearchProvider.LoginPollRequest request, Map<String, String> payload) {
        int debugPort = parseInt(payload.get("debugPort"), -1);
        String targetId = payload.getOrDefault("targetId", "");
        long pid = parseLong(payload.get("pid"), -1L);
        Path userDataDir = safePath(payload.get("userDataDir"));
        if (debugPort <= 0 || targetId.isBlank()) {
            return new SearchProvider.LoginPollResult(LoginStatus.ERROR, "Invalid login session payload.", request.payload(), null);
        }
        String ws = resolveWs(debugPort, targetId);
        if (ws.isBlank()) {
            cleanupProcess(pid, userDataDir);
            return new SearchProvider.LoginPollResult(LoginStatus.EXPIRED, "Login session expired. Please scan again.", request.payload(), null);
        }
        try (DevToolsClient client = DevToolsClient.connect(ws)) {
            client.send("Network.enable", MAPPER.createObjectNode());
            JsonNode cookieResult = client.send("Network.getCookies", MAPPER.readTree("{\"urls\":" + toJson(COOKIE_URLS) + "}"));
            String cookieHeader = toCookieHeader(collectCookies(cookieResult.path("cookies")));
            if (isAuthorized(cookieHeader)) {
                String uin = extractUin(cookieHeader);
                SearchAuthStore.AuthRecord auth = new SearchAuthStore.AuthRecord("qq", request.scopeType(), request.botId(), cookieHeader, "", toJson(Map.of("uin", uin, "g_tk", Long.toString(calcGtk(cookieHeader)))), null, Instant.now());
                cleanupProcess(pid, userDataDir);
                return new SearchProvider.LoginPollResult(LoginStatus.CONFIRMED, "Login confirmed.", request.payload(), auth);
            }
            JsonNode state = evaluateValue(client, """
                (() => {
                    const isVisible = (el) => {
                        if (!el) return false;
                        const s = window.getComputedStyle(el);
                        return s.display !== 'none' && s.visibility !== 'hidden' && el.offsetWidth > 0 && el.offsetHeight > 0;
                    };
                    return {
                        expired: isVisible(document.getElementById('qr_invalid')),
                        scanned: isVisible(document.getElementById('qrlogin_step2')) || isVisible(document.getElementById('qrlogin_step3'))
                    };
                })()
                """);
            if (state.path("expired").asBoolean(false)) {
                cleanupProcess(pid, userDataDir);
                return new SearchProvider.LoginPollResult(LoginStatus.EXPIRED, "QR code expired. Please scan again.", request.payload(), null);
            }
            if (state.path("scanned").asBoolean(false)) {
                return new SearchProvider.LoginPollResult(LoginStatus.SCANNED, "QR scanned. Confirm on mobile QQ.", request.payload(), null);
            }
            return new SearchProvider.LoginPollResult(LoginStatus.PENDING, "Waiting for QR scan.", request.payload(), null);
        } catch (Exception ex) {
            log.warn("[Search:qq] headless poll failed", ex);
            return new SearchProvider.LoginPollResult(LoginStatus.ERROR, "Failed to poll login status.", request.payload(), null);
        }
    }

    private Process startBrowser(BrowserBinary browser, int debugPort, Path userDataDir, boolean headless) {
        List<String> cmd = new ArrayList<>();
        cmd.add(browser.path().toString());
        if (headless) {
            cmd.add("--headless=new");
        }
        if (isLinux()) {
            cmd.add("--disable-dev-shm-usage");
            cmd.add("--no-sandbox");
        }
        cmd.add("--disable-gpu");
        cmd.add("--no-first-run");
        cmd.add("--no-default-browser-check");
        cmd.add("--disable-blink-features=AutomationControlled");
        cmd.add("--disable-background-timer-throttling");
        cmd.add("--disable-renderer-backgrounding");
        cmd.add("--window-size=1280,900");
        cmd.add("--remote-debugging-port=" + debugPort);
        cmd.add("--user-data-dir=" + userDataDir.toAbsolutePath());
        cmd.add(LOGIN_URL);
        try {
            return new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to start browser.", ex);
        }
    }

    private void waitForDebugger(int port) {
        long deadline = System.currentTimeMillis() + 20000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                JsonNode version = httpGetJson("http://127.0.0.1:" + port + "/json/version");
                if (version.hasNonNull("webSocketDebuggerUrl")) {
                    return;
                }
            } catch (Exception ignored) {
            }
            sleep(300L);
        }
        throw new IllegalStateException("Browser debugger port not ready in time.");
    }

    private JsonNode findOrCreateLoginTarget(int port) {
        JsonNode list = httpGetJson("http://127.0.0.1:" + port + "/json/list");
        JsonNode found = findLoginTarget(list);
        if (found != null) {
            return found;
        }
        JsonNode created = httpPutJson("http://127.0.0.1:" + port + "/json/new?" + URLEncoder.encode(LOGIN_URL, StandardCharsets.UTF_8));
        if (created != null && created.hasNonNull("id")) {
            return created;
        }
        found = findLoginTarget(httpGetJson("http://127.0.0.1:" + port + "/json/list"));
        if (found == null) {
            throw new IllegalStateException("QQ login page target not found.");
        }
        return found;
    }

    private String resolveWs(int port, String targetId) {
        try {
            JsonNode list = httpGetJson("http://127.0.0.1:" + port + "/json/list");
            if (list != null && list.isArray()) {
                for (JsonNode item : list) {
                    if (targetId.equals(item.path("id").asText(""))) {
                        return item.path("webSocketDebuggerUrl").asText("");
                    }
                }
                JsonNode fallback = findLoginTarget(list);
                if (fallback != null) {
                    return fallback.path("webSocketDebuggerUrl").asText("");
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private JsonNode findLoginTarget(JsonNode list) {
        if (list == null || !list.isArray()) {
            return null;
        }
        for (JsonNode item : list) {
            if (!"page".equalsIgnoreCase(item.path("type").asText(""))) {
                continue;
            }
            String url = item.path("url").asText("");
            if (url.contains("ptlogin2.qq.com") || url.contains("y.qq.com")) {
                return item;
            }
        }
        return null;
    }

    private String captureQrImage(String ws) {
        try (DevToolsClient client = DevToolsClient.connect(ws)) {
            client.send("Page.enable", MAPPER.createObjectNode());
            client.send("Runtime.enable", MAPPER.createObjectNode());
            client.send("Network.enable", MAPPER.createObjectNode());
            JsonNode headers = MAPPER.createObjectNode().set("headers", MAPPER.createObjectNode()
                .put("Referer", LOGIN_URL)
                .put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8"));
            client.send("Network.setExtraHTTPHeaders", headers);
            client.send("Page.setDeviceMetricsOverride", MAPPER.createObjectNode().put("width", 1280).put("height", 900).put("deviceScaleFactor", 1).put("mobile", false));
            triggerQrRefresh(client);
            for (int i = 0; i < 120; i++) {
                JsonNode qr = evaluateValue(client, """
                    (() => {
                        const img = document.getElementById('qrlogin_img')
                            || document.querySelector("img[src*='ptqrshow']")
                            || document.querySelector("img[src*='ptlogin2.qq.com/ptqrshow']")
                            || document.querySelector("canvas#qrlogin_img");
                        const invalid = document.getElementById('qr_invalid') || document.querySelector('.qr-invalid') || document.querySelector('.qrcode-invalid');
                        const visible = (el) => !!el && window.getComputedStyle(el).display !== 'none' && window.getComputedStyle(el).visibility !== 'hidden' && el.offsetWidth > 0 && el.offsetHeight > 0;
                        const frame = document.getElementById('ptlogin_iframe') || document.querySelector("iframe[src*='ptlogin2.qq.com']");
                        const frameRect = frame ? frame.getBoundingClientRect() : null;
                        if (!img) {
                            const fallbackSrc = (window.pt && pt.ptui && pt.ptui.imgURL) || (window.g_qr_url || '');
                            return {
                                ready:false,
                                expired:visible(invalid),
                                src:fallbackSrc,
                                frameX: frameRect ? frameRect.left : -1,
                                frameY: frameRect ? frameRect.top : -1,
                                frameW: frameRect ? frameRect.width : -1,
                                frameH: frameRect ? frameRect.height : -1
                            };
                        }
                        const tag = (img.tagName || '').toUpperCase();
                        if (tag === 'CANVAS') {
                            const ready = img.width > 20 && img.height > 20;
                            return {
                                ready,
                                expired:visible(invalid),
                                src:ready ? img.toDataURL('image/png') : '',
                                frameX: frameRect ? frameRect.left : -1,
                                frameY: frameRect ? frameRect.top : -1,
                                frameW: frameRect ? frameRect.width : -1,
                                frameH: frameRect ? frameRect.height : -1
                            };
                        }
                        const src = img.currentSrc || img.src || ((window.pt && pt.ptui && pt.ptui.imgURL) || '');
                        return {
                            ready: img.complete && img.naturalWidth > 20 && img.naturalHeight > 20,
                            expired: visible(invalid),
                            src,
                            frameX: frameRect ? frameRect.left : -1,
                            frameY: frameRect ? frameRect.top : -1,
                            frameW: frameRect ? frameRect.width : -1,
                            frameH: frameRect ? frameRect.height : -1
                        };
                    })()
                    """);
                String src = qr.path("src").asText("").trim();
                if (src.startsWith("data:image/")) {
                    int comma = src.indexOf(',');
                    if (comma > 0 && comma + 1 < src.length()) {
                        return src.substring(comma + 1);
                    }
                }
                if (!src.isBlank() && src.contains("ptqrshow")) {
                    String direct = fetchQrImageViaHttp(client, src);
                    if (!direct.isBlank()) {
                        return direct;
                    }
                }
                if (qr.path("expired").asBoolean(false)) {
                    triggerQrRefresh(client);
                    sleep(600L);
                    continue;
                }
                if (qr.path("ready").asBoolean(false)) {
                    String clipped = captureQrByScreenshot(client);
                    if (!clipped.isBlank()) {
                        return clipped;
                    }
                }
                double frameX = qr.path("frameX").asDouble(-1);
                double frameY = qr.path("frameY").asDouble(-1);
                double frameW = qr.path("frameW").asDouble(-1);
                double frameH = qr.path("frameH").asDouble(-1);
                if (frameX >= 0 && frameY >= 0 && frameW > 80 && frameH > 80 && i > 6) {
                    String fromFrame = captureByClip(client, frameX, frameY, frameW, frameH);
                    if (!fromFrame.isBlank()) {
                        return fromFrame;
                    }
                }
                if (i > 20 && i % 15 == 0) {
                    String full = capturePageScreenshot(client);
                    if (!full.isBlank()) {
                        return full;
                    }
                }
                if (i % 12 == 0) {
                    triggerQrRefresh(client);
                }
                sleep(400L);
            }
        }
        return "";
    }
    private void triggerQrRefresh(DevToolsClient client) {
        evaluateValue(client, """
            (() => {
                try {
                    if (window.pt && pt.plogin && typeof pt.plogin.begin_qrlogin === 'function') {
                        pt.plogin.begin_qrlogin();
                        return true;
                    }
                    const invalid = document.getElementById('qr_invalid') || document.querySelector('.qr-invalid') || document.querySelector('.qrcode-invalid');
                    if (invalid && typeof invalid.click === 'function') {
                        invalid.click();
                        return true;
                    }
                } catch (e) {
                }
                return false;
            })()
            """);
    }

    private String captureQrByScreenshot(DevToolsClient client) {
        try {
            JsonNode box = evaluateValue(client, """
                (() => {
                    const img = document.getElementById('qrlogin_img')
                        || document.querySelector("img[src*='ptqrshow']")
                        || document.querySelector("img[src*='ptlogin2.qq.com/ptqrshow']")
                        || document.querySelector("canvas#qrlogin_img");
                    if (!img) return null;
                    const r = img.getBoundingClientRect();
                    if (!r || r.width < 20 || r.height < 20) return null;
                    return {x:r.left, y:r.top, width:r.width, height:r.height};
                })()
                """);
            if (box == null || box.isMissingNode() || box.isNull()) {
                return "";
            }
            double x = box.path("x").asDouble(-1);
            double y = box.path("y").asDouble(-1);
            double width = box.path("width").asDouble(-1);
            double height = box.path("height").asDouble(-1);
            if (x < 0 || y < 0 || width < 20 || height < 20) {
                return "";
            }
            JsonNode params = MAPPER.createObjectNode().put("format", "png").set("clip", MAPPER.createObjectNode().put("x", x).put("y", y).put("width", width).put("height", height).put("scale", 1));
            JsonNode result = client.send("Page.captureScreenshot", params);
            String data = result.path("data").asText("");
            return data == null ? "" : data.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String captureByClip(DevToolsClient client, double x, double y, double width, double height) {
        if (x < 0 || y < 0 || width < 20 || height < 20) {
            return "";
        }
        try {
            JsonNode params = MAPPER.createObjectNode()
                .put("format", "png")
                .set("clip", MAPPER.createObjectNode()
                    .put("x", x)
                    .put("y", y)
                    .put("width", width)
                    .put("height", height)
                    .put("scale", 1));
            JsonNode result = client.send("Page.captureScreenshot", params);
            String data = result.path("data").asText("");
            return data == null ? "" : data.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String capturePageScreenshot(DevToolsClient client) {
        try {
            JsonNode result = client.send("Page.captureScreenshot", MAPPER.createObjectNode().put("format", "png"));
            String data = result.path("data").asText("");
            return data == null ? "" : data.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String fetchQrImageViaHttp(DevToolsClient client, String src) {
        if (src == null || src.isBlank()) {
            return "";
        }
        if (src.startsWith("data:image/")) {
            int idx = src.indexOf(',');
            if (idx > 0 && idx + 1 < src.length()) {
                return src.substring(idx + 1);
            }
            return "";
        }
        if (!src.startsWith("http://") && !src.startsWith("https://")) {
            return "";
        }
        try {
            JsonNode cookieResult = client.send("Network.getCookies", MAPPER.readTree("{\"urls\":[\"" + src + "\",\"https://ssl.ptlogin2.qq.com/\"]}"));
            String cookieHeader = toCookieHeader(collectCookies(cookieResult.path("cookies")));
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(src)).timeout(Duration.ofSeconds(8)).header("User-Agent", "Mozilla/5.0").header("Referer", LOGIN_URL).GET();
            if (!cookieHeader.isBlank()) {
                builder.header("Cookie", cookieHeader);
            }
            HttpResponse<byte[]> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            int code = response.statusCode();
            int len = response.body() == null ? 0 : response.body().length;
            if (code >= 200 && code < 300 && len > 128) {
                return Base64.getEncoder().encodeToString(response.body());
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private JsonNode evaluateValue(DevToolsClient client, String expression) {
        JsonNode params = MAPPER.createObjectNode().put("expression", expression).put("returnByValue", true).put("awaitPromise", true);
        return client.send("Runtime.evaluate", params).path("result").path("value");
    }

    private Map<String, String> collectCookies(JsonNode cookies) {
        Map<String, String> map = new LinkedHashMap<>();
        if (cookies == null || !cookies.isArray()) {
            return map;
        }
        for (JsonNode cookie : cookies) {
            String name = cookie.path("name").asText("");
            String value = cookie.path("value").asText("");
            if (!name.isBlank() && !value.isBlank()) {
                map.put(name, value);
            }
        }
        return map;
    }

    private String toCookieHeader(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    private boolean isAuthorized(String cookie) {
        return (cookie.contains("uin=") || cookie.contains("p_uin=")) && (cookie.contains("skey=") || cookie.contains("p_skey="));
    }

    private String extractUin(String cookieHeader) {
        String uin = extractCookieValue(cookieHeader, "uin");
        if (uin.isBlank()) {
            uin = extractCookieValue(cookieHeader, "p_uin");
        }
        if (uin.startsWith("o")) {
            uin = uin.substring(1);
        }
        return uin.replaceFirst("^0+", "");
    }

    private long calcGtk(String cookie) {
        String key = extractCookieValue(cookie, "p_skey");
        if (key.isBlank()) {
            key = extractCookieValue(cookie, "skey");
        }
        long hash = 5381L;
        for (char c : key.toCharArray()) {
            hash += (hash << 5) + c;
        }
        return hash & 0x7fffffffL;
    }

    private String extractCookieValue(String cookieHeader, String name) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
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

    private BrowserBinary findBrowserBinary() {
        String fromEnv = firstNonBlank(System.getenv("QQ_BROWSER_BIN"), System.getenv("CHROME_BIN"), System.getenv("CHROMIUM_BIN"), System.getenv("EDGE_BIN"));
        if (!fromEnv.isBlank() && Files.isRegularFile(Paths.get(fromEnv))) {
            return new BrowserBinary(Paths.get(fromEnv));
        }
        if (isWindows()) {
            for (Path path : List.of(Paths.get("C:/Program Files/Google/Chrome/Application/chrome.exe"), Paths.get("C:/Program Files (x86)/Google/Chrome/Application/chrome.exe"), Paths.get("C:/Program Files/Microsoft/Edge/Application/msedge.exe"), Paths.get("C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"))) {
                if (Files.isRegularFile(path)) {
                    return new BrowserBinary(path);
                }
            }
        }
        for (String name : List.of("google-chrome", "chromium", "chromium-browser", "chrome", "msedge", "microsoft-edge")) {
            Path path = resolveOnPath(name);
            if (path != null) {
                return new BrowserBinary(path);
            }
        }
        return null;
    }

    private Path resolveOnPath(String name) {
        String envPath = System.getenv("PATH");
        if (envPath == null || envPath.isBlank()) {
            return null;
        }
        for (String dir : envPath.split(PatternHolder.PATH_SPLIT)) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            Path candidate = Paths.get(dir, name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            if (isWindows()) {
                Path withExe = Paths.get(dir, name + ".exe");
                if (Files.isRegularFile(withExe)) {
                    return withExe;
                }
            }
        }
        return null;
    }

    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to allocate free port.", ex);
        }
    }

    private JsonNode httpGetJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return MAPPER.readTree(response.body());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to request browser debugger endpoint: " + url, ex);
        }
    }

    private JsonNode httpPutJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(5)).PUT(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return MAPPER.readTree(response.body());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to call browser debugger endpoint: " + url, ex);
        }
    }

    private void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create browser profile directory.", ex);
        }
    }

    private void cleanupProcess(long pid, Path userDataDir) {
        if (pid > 0) {
            ProcessHandle.of(pid).ifPresent(handle -> {
                try {
                    handle.destroy();
                    handle.onExit().get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    try {
                        handle.destroyForcibly();
                    } catch (Exception ignoredAgain) {
                    }
                }
            });
        }
        if (userDataDir != null && Files.exists(userDataDir)) {
            try (var stream = Files.walk(userDataDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
        }
    }

    private Path safePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Paths.get(raw);
        } catch (Exception ex) {
            return null;
        }
    }

    private int parseInt(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private long parseLong(String value, long fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private record BrowserBinary(Path path) {
    }

    private static final class PatternHolder {
        private static final String PATH_SPLIT = java.util.regex.Pattern.quote(File.pathSeparator);
    }

    private static final class DevToolsClient implements AutoCloseable, WebSocket.Listener {
        private static final HttpClient WS_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final AtomicInteger ids = new AtomicInteger(1);
        private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final StringBuilder textBuffer = new StringBuilder();
        private final String wsUrl;
        private WebSocket socket;

        private DevToolsClient(String wsUrl) {
            this.wsUrl = wsUrl;
        }

        public static DevToolsClient connect(String wsUrl) {
            DevToolsClient client = new DevToolsClient(wsUrl);
            client.socket = WS_CLIENT.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(URI.create(wsUrl), client).join();
            return client;
        }

        public JsonNode send(String method, JsonNode params) {
            int id = ids.getAndIncrement();
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pending.put(id, future);
            try {
                JsonNode payload = MAPPER.createObjectNode().put("id", id).put("method", method).set("params", params == null ? MAPPER.createObjectNode() : params);
                socket.sendText(payload.toString(), true).join();
                return future.get(10, TimeUnit.SECONDS);
            } catch (Exception ex) {
                pending.remove(id);
                throw new IllegalStateException("Failed to invoke DevTools method: " + method, ex);
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String text = textBuffer.toString();
                textBuffer.setLength(0);
                try {
                    JsonNode node = MAPPER.readTree(text);
                    JsonNode idNode = node.get("id");
                    if (idNode != null && idNode.isInt()) {
                        int id = idNode.asInt();
                        CompletableFuture<JsonNode> future = pending.remove(id);
                        if (future != null) {
                            if (node.has("error")) {
                                future.completeExceptionally(new IllegalStateException(node.get("error").toString()));
                            } else {
                                future.complete(node.path("result"));
                            }
                        }
                    }
                } catch (Exception ex) {
                    for (CompletableFuture<JsonNode> future : pending.values()) {
                        future.completeExceptionally(ex);
                    }
                    pending.clear();
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            for (CompletableFuture<JsonNode> future : pending.values()) {
                future.completeExceptionally(error);
            }
            pending.clear();
        }

        @Override
        public void close() {
            if (socket != null) {
                try {
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
