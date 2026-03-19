package pub.longyi.ts3audiobot.search;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pub.longyi.ts3audiobot.config.ConfigService;

import java.time.Instant;
import java.util.Optional;

/**
 * SearchAuthService 相关功能。
 *
 * <p>职责：封装登录态读取、作用域优先级与加解密。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Slf4j
@Component
public final class SearchAuthService {
    public static final String SCOPE_GLOBAL = "global";
    public static final String SCOPE_BOT = "bot";

    private final SearchAuthStore store;
    private final SearchCrypto crypto;

    public SearchAuthService(ConfigService configService) {
        this.store = new SearchAuthStore(configService.getConfigStore().getDbPath());
        this.store.initialize();
        String secret = configService.get().search == null ? "" : configService.get().search.authSecret;
        this.crypto = new SearchCrypto(secret);
    }

    public Optional<SearchAuthStore.AuthRecord> resolveAuth(String source, String botId) {
        SearchAuthStore.AuthRecord bot = getAuth(source, SCOPE_BOT, botId);
        if (bot != null && !isExpired(bot) && bot.cookie() != null && !bot.cookie().isBlank()) {
            return Optional.of(bot);
        }
        SearchAuthStore.AuthRecord global = getAuth(source, SCOPE_GLOBAL, "");
        if (global != null && !isExpired(global) && global.cookie() != null && !global.cookie().isBlank()) {
            return Optional.of(global);
        }
        return Optional.empty();
    }

    public SearchAuthStore.AuthRecord getAuth(String source, String scopeType, String botId) {
        SearchAuthStore.AuthRecord record = store.getAuth(source, scopeType, botId);
        if (record == null) {
            return null;
        }
        String cookie = crypto.decrypt(record.cookie());
        String token = crypto.decrypt(record.token());
        return new SearchAuthStore.AuthRecord(
            record.source(),
            record.scopeType(),
            record.botId(),
            cookie,
            token,
            record.extraJson(),
            record.expiresAt(),
            record.updatedAt()
        );
    }

    public void upsertAuth(SearchAuthStore.AuthRecord record) {
        if (record == null) {
            return;
        }
        String encryptedCookie = crypto.encrypt(record.cookie());
        String encryptedToken = crypto.encrypt(record.token());
        SearchAuthStore.AuthRecord payload = new SearchAuthStore.AuthRecord(
            record.source(),
            record.scopeType(),
            record.botId(),
            encryptedCookie,
            encryptedToken,
            record.extraJson(),
            record.expiresAt(),
            record.updatedAt()
        );
        store.upsertAuth(payload);
    }

    public void deleteAuth(String source, String scopeType, String botId) {
        store.deleteAuth(source, scopeType, botId);
    }

    public SearchAuthStore.LoginSessionRecord getLoginSession(String sessionId) {
        return store.getLoginSession(sessionId);
    }

    public void upsertLoginSession(SearchAuthStore.LoginSessionRecord record) {
        store.upsertLoginSession(record);
    }

    public void updateLoginStatus(String sessionId, String status) {
        store.updateLoginStatus(sessionId, status);
    }

    public void deleteLoginSession(String sessionId) {
        store.deleteLoginSession(sessionId);
    }

    public boolean isExpired(SearchAuthStore.AuthRecord record) {
        if (record == null) {
            return true;
        }
        Instant expiresAt = record.expiresAt();
        if (expiresAt == null) {
            return false;
        }
        return Instant.now().isAfter(expiresAt);
    }

    public void clearIfExpired(SearchAuthStore.AuthRecord record) {
        if (record == null) {
            return;
        }
        if (isExpired(record)) {
            deleteAuth(record.source(), record.scopeType(), record.botId());
            log.info("Cleared expired search auth source={} scope={} bot={}", record.source(), record.scopeType(), record.botId());
        }
    }
}
