package pub.longyi.ts3audiobot.search;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Slf4j
@Component
public final class SearchService {
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
            statuses.add(new SearchStatus(
                provider.source(),
                requiresLogin,
                loggedIn,
                scopeUsed,
                provider.supportsPlaylists(),
                enabled
            ));
        }
        return statuses;
    }

    public LoginStart startLogin(String source, String scope, String botId) {
        SearchProvider provider = requireProvider(source);
        String safeScope = "bot".equalsIgnoreCase(scope) ? SearchAuthService.SCOPE_BOT : SearchAuthService.SCOPE_GLOBAL;
        SearchProvider.LoginStartResult result = provider.startLogin(new SearchProvider.LoginRequest(safeScope, botId));
        String sessionId = result.sessionId() == null || result.sessionId().isBlank()
            ? UUID.randomUUID().toString()
            : result.sessionId();
        Instant now = Instant.now();
        SearchAuthStore.LoginSessionRecord record = new SearchAuthStore.LoginSessionRecord(
            sessionId,
            provider.source(),
            safeScope,
            normalizeBotId(botId),
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
            authService.upsertAuth(result.authRecord());
            authService.deleteLoginSession(session.sessionId());
        } else if (result.status() == LoginStatus.EXPIRED) {
            authService.deleteLoginSession(session.sessionId());
        } else if (result.status() == LoginStatus.ERROR) {
            authService.updateLoginStatus(session.sessionId(), result.status().name().toLowerCase(Locale.ROOT));
        }
        return new LoginPoll(result.status(), result.message());
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
                throw new IllegalStateException("需要先登录");
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
                throw new IllegalStateException("闇€瑕佸厛鐧诲綍");
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
            throw new IllegalStateException("需要先登录");
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
            throw new IllegalStateException("需要先登录");
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
