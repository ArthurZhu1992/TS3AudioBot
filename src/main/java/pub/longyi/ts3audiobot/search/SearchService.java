package pub.longyi.ts3audiobot.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.search.SearchModels.LoginPoll;
import pub.longyi.ts3audiobot.search.SearchModels.LoginStart;
import pub.longyi.ts3audiobot.search.SearchModels.PlaylistPage;
import pub.longyi.ts3audiobot.search.SearchModels.PlaylistTrackPage;
import pub.longyi.ts3audiobot.search.SearchModels.SearchDetailPage;
import pub.longyi.ts3audiobot.search.SearchModels.SearchPage;
import pub.longyi.ts3audiobot.search.SearchModels.SearchStatus;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public final class SearchService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern RAW_COOKIE_LINE_PATTERN = Pattern.compile("(?im)^\\s*cookie\\s*:\\s*(.+)$");
    private static final Pattern CURL_COOKIE_HEADER_PATTERN = Pattern.compile(
        "(?is)(?:-H|--header)\\s+(?:'|\")\\s*cookie\\s*:\\s*([^'\"]+)(?:'|\")"
    );
    private static final int DEFAULT_PAGE = 1;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Duration DETAIL_JOB_TIMEOUT = Duration.ofMinutes(2);
    
    private static final String DETAIL_STATUS_RUNNING = "running";
    private static final String DETAIL_STATUS_READY = "ready";
    private static final String DETAIL_STATUS_EMPTY = "empty";
    private static final String DETAIL_STATUS_UNSUPPORTED = "unsupported";
    private static final String DETAIL_STATUS_ERROR = "error";

    private final Map<String, SearchProvider> providers = new ConcurrentHashMap<>();
    private final SearchAuthService authService;
    private final SearchCache<SearchPage> searchCache = new SearchCache<>();
    private final SearchCache<SearchPage> searchDetailCache = new SearchCache<>();
    private final SearchCache<PlaylistPage> playlistCache = new SearchCache<>();
    private final SearchCache<PlaylistTrackPage> playlistTrackCache = new SearchCache<>();
    private final Map<String, DetailJob> detailJobs = new ConcurrentHashMap<>();
    private final ExecutorService detailPool = Executors.newFixedThreadPool(2);
    private final Duration cacheTtl;
    private final Duration detailCacheTtl;

    public SearchService(
        List<SearchProvider> providerList,
        SearchAuthService authService,
        ConfigService configService
    ) {
        if (providerList != null) {
            for (SearchProvider provider : providerList) {
                providers.put(provider.source().toLowerCase(Locale.ROOT), provider);
            }
        }
        this.authService = authService;
        int cacheSeconds = 0;
        if (configService.get().search != null) {
            cacheSeconds = Math.max(0, configService.get().search.cacheSeconds);
        }
        this.cacheTtl = cacheSeconds <= 0 ? Duration.ZERO : Duration.ofSeconds(cacheSeconds);
        this.detailCacheTtl = this.cacheTtl.isZero() ? Duration.ofSeconds(30) : this.cacheTtl;
    }

    public List<SearchStatus> getStatus(String botId) {
        List<SearchStatus> statuses = new ArrayList<>();
        for (SearchProvider provider : providers.values()) {
            boolean requiresLogin = provider.requiresLogin();
            Optional<SearchAuthStore.AuthRecord> auth = authService.resolveAuth(provider.source(), botId);
            auth.ifPresent(authService::clearIfExpired);
            boolean loggedIn = auth.isPresent() && !authService.isExpired(auth.get());
            String scopeUsed = auth.map(SearchAuthStore.AuthRecord::scopeType).orElse("none");
            boolean enabled = !requiresLogin || loggedIn;
            String vipState = "na";
            String vipHint = "";
            String accountInfo = "";
            if ("qq".equalsIgnoreCase(provider.source())) {
                if (!loggedIn) {
                    vipState = "unknown";
                    vipHint = "未登录，无法识别VIP状态";
                } else if (provider instanceof QqMusicSearchProvider qqProvider) {
                    QqMusicSearchProvider.VipProbeResult vipProbe = qqProvider.probeVipStatus(auth.orElse(null));
                    vipState = vipProbe.state();
                    vipHint = vipProbe.message();
                    accountInfo = resolveQqAccountInfo(auth.orElse(null));
                }
            }
            statuses.add(new SearchStatus(
                provider.source(),
                requiresLogin,
                loggedIn,
                scopeUsed,
                provider.supportsPlaylists(),
                enabled,
                vipState,
                vipHint,
                accountInfo
            ));
        }
        return statuses;
    }

    public LoginStart startLogin(String source, String scope, String botId) {
        SearchProvider provider = requireProvider(source);
        String safeScope = "bot".equalsIgnoreCase(scope) ? SearchAuthService.SCOPE_BOT : SearchAuthService.SCOPE_GLOBAL;
        String safeBotId = normalizeScopeBotId(safeScope, botId);
        SearchProvider.LoginStartResult result = provider.startLogin(new SearchProvider.LoginRequest(safeScope, safeBotId));
        String sessionId = result.sessionId() == null || result.sessionId().isBlank()
            ? UUID.randomUUID().toString()
            : result.sessionId();
        Instant now = Instant.now();
        SearchAuthStore.LoginSessionRecord record = new SearchAuthStore.LoginSessionRecord(
            sessionId,
            provider.source(),
            safeScope,
            safeBotId,
            LoginStatus.PENDING.name().toLowerCase(Locale.ROOT),
            result.payload(),
            now,
            now
        );
        authService.upsertLoginSession(record);
        Instant expiresAt = result.expiresInSeconds() > 0 ? now.plusSeconds(result.expiresInSeconds()) : null;
        return new LoginStart(
            sessionId,
            provider.source(),
            scope,
            result.qrImage(),
            result.qrUrl(),
            expiresAt,
            result.message()
        );
    }

    public LoginPoll pollLogin(String sessionId) {
        SearchAuthStore.LoginSessionRecord session = authService.getLoginSession(sessionId);
        if (session == null) {
            return new LoginPoll(LoginStatus.ERROR, "登录会话不存在");
        }

        SearchProvider provider = requireProvider(session.source());
        SearchProvider.LoginPollResult result = provider.pollLogin(new SearchProvider.LoginPollRequest(
            session.sessionId(),
            session.payload(),
            session.scopeType(),
            session.botId()
        ));
        if (result.payload() != null && !result.payload().equals(session.payload())) {
            SearchAuthStore.LoginSessionRecord updated = new SearchAuthStore.LoginSessionRecord(
                session.sessionId(),
                session.source(),
                session.scopeType(),
                session.botId(),
                result.status().name().toLowerCase(Locale.ROOT),
                result.payload(),
                session.createdAt(),
                Instant.now()
            );
            authService.upsertLoginSession(updated);
        } else {
            authService.updateLoginStatus(session.sessionId(), result.status().name().toLowerCase(Locale.ROOT));
        }
        if (result.status() == LoginStatus.CONFIRMED && result.authRecord() != null) {
            authService.upsertAuth(normalizeAuthRecordForStorage(result.authRecord()));
            authService.deleteLoginSession(session.sessionId());
        } else if (result.status() == LoginStatus.EXPIRED) {
            authService.deleteLoginSession(session.sessionId());
        } else if (result.status() == LoginStatus.ERROR) {
            authService.updateLoginStatus(session.sessionId(), result.status().name().toLowerCase(Locale.ROOT));
        }
        return new LoginPoll(result.status(), result.message());
    }

    public String importQqManualAuth(String scope, String botId, String payloadText) {
        String safeScope = "bot".equalsIgnoreCase(scope) ? SearchAuthService.SCOPE_BOT : SearchAuthService.SCOPE_GLOBAL;
        String safeBotId = normalizeScopeBotId(safeScope, botId);
        String cookie = extractCookieFromPayload(payloadText);
        if (cookie.isBlank()) {
            throw new IllegalArgumentException("鏈粠杈撳叆鍐呭涓彁鍙栧埌 cookie");
        }
        cookie = normalizeCookieHeader(cookie);
        String uin = extractFirstCookie(cookie, "uin", "p_uin", "qqmusic_uin", "qm_uid");
        if (uin.isBlank()) {
            throw new IllegalArgumentException("cookie 缺少 uin/p_uin，无法导入");
        }
        String key = extractFirstCookie(cookie, "p_skey", "skey", "qm_keyst", "qqmusic_key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("cookie 缺少 p_skey/skey/qm_keyst/qqmusic_key，无法导入");
        }
        long gtk = calcGtk(cookie);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("uin", uin);
        extra.put("g_tk", Long.toString(gtk));
        extra.put("mode", "manual_console");
        extra.put("importedAt", Instant.now().toString());
        SearchAuthStore.AuthRecord auth = new SearchAuthStore.AuthRecord(
            "qq",
            safeScope,
            safeBotId,
            cookie,
            "",
            toJson(extra),
            null,
            Instant.now()
        );
        authService.upsertAuth(auth);
        return "QQ 鐧诲綍淇℃伅宸插鍏ワ紙宸蹭繚瀛橈級";
    }

    public SearchPage search(String source, String botId, String query, int page, int pageSize) {
        SearchProvider provider = requireProvider(source);
        String safeQuery = query == null ? "" : query.trim();
        SearchAuthStore.AuthRecord auth = null;
        if (provider.requiresLogin()) {
            auth = authService.resolveAuth(provider.source(), botId).orElse(null);
            if (auth == null || authService.isExpired(auth)) {
                if (auth != null) {
                    authService.clearIfExpired(auth);
                }
                throw new IllegalStateException("闇€瑕佸厛鐧诲綍");
            }
        }
        int safePage = normalizePage(page);
        int safeSize = normalizePageSize(pageSize);
        String cacheKey = buildCacheKey("search", provider.source(), botId, safePage, safeSize, safeQuery, auth);
        Optional<SearchPage> cached = cacheTtl.isZero() ? Optional.empty() : searchCache.get(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }
        SearchPage result = provider.search(new SearchProvider.SearchRequest(
            botId,
            safeQuery,
            safePage,
            safeSize,
            auth
        ));
        if (!cacheTtl.isZero()) {
            searchCache.put(cacheKey, result, cacheTtl);
        }
        return result;
    }

    public SearchDetailPage searchDetail(String source, String botId, String query, int page, int pageSize) {
        SearchProvider provider = requireProvider(source);
        String safeQuery = query == null ? "" : query.trim();
        SearchAuthStore.AuthRecord auth = null;
        if (provider.requiresLogin()) {
            auth = authService.resolveAuth(provider.source(), botId).orElse(null);
            if (auth == null || authService.isExpired(auth)) {
                if (auth != null) {
                    authService.clearIfExpired(auth);
                }
                throw new IllegalStateException("闂団偓鐟曚礁鍘涢惂璇茬秿");
            }
        }
        int safePage = normalizePage(page);
        int safeSize = normalizePageSize(pageSize);
        String cacheKey = buildCacheKey("search_detail", provider.source(), botId, safePage, safeSize, safeQuery, auth);
        Optional<SearchPage> cached = detailCacheTtl.isZero() ? Optional.empty() : searchDetailCache.get(cacheKey);
        if (cached.isPresent()) {
            SearchPage result = cached.get();
            return new SearchDetailPage(DETAIL_STATUS_READY, result.items(), result.page(), result.pageSize(), result.total());
        }
        DetailJob existing = detailJobs.get(cacheKey);
        if (existing != null) {
            if (existing.isExpired()) {
                existing.cancel();
                detailJobs.remove(cacheKey);
            } else if (existing.future().isDone()) {
                return finalizeDetailJob(cacheKey, existing, safePage, safeSize);
            } else {
                return new SearchDetailPage(DETAIL_STATUS_RUNNING, List.of(), safePage, safeSize, null);
            }
        }
        if (!(provider instanceof SearchDetailProvider detailProvider)) {
            return new SearchDetailPage(DETAIL_STATUS_UNSUPPORTED, List.of(), safePage, safeSize, null);
        }
        final SearchAuthStore.AuthRecord authFinal = auth;
        DetailJob created = detailJobs.computeIfAbsent(cacheKey, key -> new DetailJob(
            detailPool.submit(() -> detailProvider.searchDetail(new SearchProvider.SearchRequest(
                botId,
                safeQuery,
                safePage,
                safeSize,
                authFinal
            ))),
            Instant.now()
        ));
        if (created.future().isDone()) {
            return finalizeDetailJob(cacheKey, created, safePage, safeSize);
        }
        return new SearchDetailPage(DETAIL_STATUS_RUNNING, List.of(), safePage, safeSize, null);
    }

    public PlaylistPage listPlaylists(String source, String botId, int page, int pageSize) {
        SearchProvider provider = requireProvider(source);
        if (!provider.supportsPlaylists()) {
            return new PlaylistPage(List.of(), page, pageSize, 0);
        }
        SearchAuthStore.AuthRecord auth = authService.resolveAuth(provider.source(), botId).orElse(null);
        if (auth == null || authService.isExpired(auth)) {
            if (auth != null) {
                authService.clearIfExpired(auth);
            }
            throw new IllegalStateException("闇€瑕佸厛鐧诲綍");
        }
        int safePage = normalizePage(page);
        int safeSize = normalizePageSize(pageSize);
        String cacheKey = buildCacheKey("playlist", provider.source(), botId, safePage, safeSize, "", auth);
        Optional<PlaylistPage> cached = cacheTtl.isZero() ? Optional.empty() : playlistCache.get(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }
        PlaylistPage result = provider.listPlaylists(new SearchProvider.PlaylistRequest(
            botId,
            safePage,
            safeSize,
            auth
        ));
        if (!cacheTtl.isZero()) {
            playlistCache.put(cacheKey, result, cacheTtl);
        }
        return result;
    }

    public PlaylistTrackPage listPlaylistTracks(String source, String botId, String playlistId, int page, int pageSize) {
        SearchProvider provider = requireProvider(source);
        if (!provider.supportsPlaylists()) {
            return new PlaylistTrackPage(List.of(), page, pageSize, 0);
        }
        SearchAuthStore.AuthRecord auth = authService.resolveAuth(provider.source(), botId).orElse(null);
        if (auth == null || authService.isExpired(auth)) {
            if (auth != null) {
                authService.clearIfExpired(auth);
            }
            throw new IllegalStateException("闇€瑕佸厛鐧诲綍");
        }
        int safePage = normalizePage(page);
        int safeSize = normalizePageSize(pageSize);
        String cacheKey = buildCacheKey("playlist_tracks", provider.source(), botId, safePage, safeSize, playlistId, auth);
        Optional<PlaylistTrackPage> cached = cacheTtl.isZero() ? Optional.empty() : playlistTrackCache.get(cacheKey);
        if (cached.isPresent()) {
            return cached.get();
        }
        PlaylistTrackPage result = provider.listPlaylistTracks(new SearchProvider.PlaylistTracksRequest(
            botId,
            playlistId,
            safePage,
            safeSize,
            auth
        ));
        if (!cacheTtl.isZero()) {
            playlistTrackCache.put(cacheKey, result, cacheTtl);
        }
        return result;
    }

    @PreDestroy
    public void shutdownDetailPool() {
        detailPool.shutdownNow();
    }

    private SearchProvider requireProvider(String source) {
        if (source == null) {
            throw new IllegalArgumentException("source is required");
        }
        SearchProvider provider = providers.get(source.toLowerCase(Locale.ROOT));
        if (provider == null) {
            throw new IllegalArgumentException("Unsupported source " + source);
        }
        return provider;
    }

    private int normalizePage(int page) {
        return Math.max(DEFAULT_PAGE, page);
    }

    private int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return 30;
        }
        return Math.min(MAX_PAGE_SIZE, pageSize);
    }

    private String buildCacheKey(
        String prefix,
        String source,
        String botId,
        int page,
        int pageSize,
        String extra,
        SearchAuthStore.AuthRecord auth
    ) {
        String scope = auth == null ? "none" : auth.scopeType() + ":" + auth.botId();
        return String.join("|",
            prefix,
            source,
            normalizeBotId(botId),
            Integer.toString(page),
            Integer.toString(pageSize),
            scope,
            extra == null ? "" : extra.trim(),
            auth == null || auth.cookie() == null ? "" : Integer.toHexString(auth.cookie().hashCode())
        );
    }

    private String normalizeBotId(String botId) {
        return botId == null ? "" : botId.trim();
    }

    private SearchAuthStore.AuthRecord normalizeAuthRecordForStorage(SearchAuthStore.AuthRecord auth) {
        if (auth == null) {
            return null;
        }
        Instant expiresAt = auth.expiresAt();
        if ("qq".equalsIgnoreCase(auth.source())) {
            expiresAt = null;
        }
        return new SearchAuthStore.AuthRecord(
            auth.source(),
            auth.scopeType(),
            auth.botId(),
            auth.cookie(),
            auth.token(),
            auth.extraJson(),
            expiresAt,
            Instant.now()
        );
    }

    private String resolveQqAccountInfo(SearchAuthStore.AuthRecord auth) {
        if (auth == null) {
            return "";
        }
        String uin = "";
        try {
            if (auth.extraJson() != null && !auth.extraJson().isBlank()) {
                JsonNode extra = MAPPER.readTree(auth.extraJson());
                uin = extra.path("uin").asText("");
            }
        } catch (Exception ignored) {
        }
        if (uin.isBlank()) {
            uin = extractFirstCookie(auth.cookie(), "uin", "p_uin", "qqmusic_uin", "qm_uid");
        }
        if (uin.startsWith("o")) {
            uin = uin.substring(1);
        }
        uin = uin.replaceAll("^0+", "");
        if (uin.isBlank()) {
            return "QQ璐﹀彿鏈煡";
        }
        if (uin.length() <= 4) {
            return "QQ " + uin;
        }
        String masked = uin.substring(0, Math.min(3, uin.length())) + "****" + uin.substring(uin.length() - 2);
        return "QQ " + masked;
    }

    private String normalizeScopeBotId(String scopeType, String botId) {
        String normalized = normalizeBotId(botId);
        if (SearchAuthService.SCOPE_BOT.equals(scopeType)) {
            if (normalized.isBlank()) {
                throw new IllegalArgumentException("褰撳墠鑼冨洿鏄満鍣ㄤ汉鐧诲綍锛屼絾 botId 涓虹┖");
            }
            return normalized;
        }
        return "";
    }

    private String extractCookieFromPayload(String payloadText) {
        if (payloadText == null) {
            return "";
        }
        String raw = payloadText.trim();
        if (raw.isBlank()) {
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(raw);
            String cookie = root.path("cookie").asText("");
            if (cookie.isBlank()) {
                cookie = root.path("cookies").asText("");
            }
            if (cookie.isBlank() && root.has("headers")) {
                JsonNode headers = root.path("headers");
                cookie = headers.path("cookie").asText("");
            }
            if (!cookie.isBlank()) {
                return cookie;
            }
        } catch (Exception ignored) {
        }
        Matcher lineMatcher = RAW_COOKIE_LINE_PATTERN.matcher(raw);
        if (lineMatcher.find()) {
            String matched = cleanupCookieCandidate(lineMatcher.group(1));
            if (!matched.isBlank()) {
                return matched;
            }
        }
        Matcher curlMatcher = CURL_COOKIE_HEADER_PATTERN.matcher(raw);
        if (curlMatcher.find()) {
            String matched = cleanupCookieCandidate(curlMatcher.group(1));
            if (!matched.isBlank()) {
                return matched;
            }
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("cookie:");
        if (idx >= 0) {
            String tail = raw.substring(idx + "cookie:".length()).trim();
            int lineBreak = tail.indexOf('\n');
            String candidate = lineBreak >= 0 ? tail.substring(0, lineBreak).trim() : tail;
            candidate = cleanupCookieCandidate(candidate);
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return cleanupCookieCandidate(raw);
    }

    private String normalizeCookieHeader(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return "";
        }
        Map<String, String> map = new LinkedHashMap<>();
        String[] parts = cookieHeader.split(";");
        for (String part : parts) {
            String trimmed = part == null ? "" : part.trim();
            int idx = trimmed.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = trimmed.substring(0, idx).trim();
            String value = trimmed.substring(idx + 1).trim();
            if (!key.isBlank() && !value.isBlank()) {
                map.put(key, value);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    private String extractFirstCookie(String cookieHeader, String... names) {
        if (cookieHeader == null || cookieHeader.isBlank() || names == null) {
            return "";
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String marker = name + "=";
            for (String part : cookieHeader.split(";")) {
                String trimmed = part == null ? "" : part.trim();
                if (trimmed.startsWith(marker)) {
                    return trimmed.substring(marker.length()).trim();
                }
            }
        }
        return "";
    }

    private String cleanupCookieCandidate(String value) {
        if (value == null) {
            return "";
        }
        String candidate = value.trim();
        if (candidate.startsWith("'") && candidate.endsWith("'") && candidate.length() > 1) {
            candidate = candidate.substring(1, candidate.length() - 1).trim();
        }
        if (candidate.startsWith("\"") && candidate.endsWith("\"") && candidate.length() > 1) {
            candidate = candidate.substring(1, candidate.length() - 1).trim();
        }
        int headerIdx = indexOfHeaderSwitch(candidate);
        if (headerIdx > 0) {
            candidate = candidate.substring(0, headerIdx).trim();
        }
        return candidate;
    }

    private int indexOfHeaderSwitch(String text) {
        int idxH = text.indexOf(" -H ");
        int idxHeader = text.indexOf(" --header ");
        if (idxH < 0) {
            return idxHeader;
        }
        if (idxHeader < 0) {
            return idxH;
        }
        return Math.min(idxH, idxHeader);
    }

    private long calcGtk(String cookieHeader) {
        String key = extractFirstCookie(cookieHeader, "p_skey", "skey", "qm_keyst", "qqmusic_key");
        long hash = 5381L;
        for (char c : key.toCharArray()) {
            hash += (hash << 5) + c;
        }
        return hash & 0x7fffffffL;
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private SearchDetailPage finalizeDetailJob(String cacheKey, DetailJob job, int page, int pageSize) {
        detailJobs.remove(cacheKey);
        try {
            SearchPage result = job.future().get();
            if (result == null) {
                return new SearchDetailPage(DETAIL_STATUS_EMPTY, List.of(), page, pageSize, null);
            }
            if (!detailCacheTtl.isZero()) {
                searchDetailCache.put(cacheKey, result, detailCacheTtl);
            }
            if (result.items() == null || result.items().isEmpty()) {
                return new SearchDetailPage(DETAIL_STATUS_EMPTY, List.of(), result.page(), result.pageSize(), result.total());
            }
            return new SearchDetailPage(DETAIL_STATUS_READY, result.items(), result.page(), result.pageSize(), result.total());
        } catch (Exception ex) {
            log.warn("Search detail job failed", ex);
            return new SearchDetailPage(DETAIL_STATUS_ERROR, List.of(), page, pageSize, null);
        }
    }

    private static final class DetailJob {
        private final Future<SearchPage> future;
        private final Instant startedAt;

        private DetailJob(Future<SearchPage> future, Instant startedAt) {
            this.future = future;
            this.startedAt = startedAt;
        }

        private boolean isExpired() {
            return startedAt != null && Instant.now().isAfter(startedAt.plus(DETAIL_JOB_TIMEOUT));
        }

        private void cancel() {
            future.cancel(true);
        }

        private Future<SearchPage> future() {
            return future;
        }
    }


}

