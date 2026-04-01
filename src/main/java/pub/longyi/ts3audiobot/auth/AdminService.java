package pub.longyi.ts3audiobot.auth;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 AdminService 相关功能。
 */

/**
 * AdminService 相关功能。
 *
 * <p>职责：负责 AdminService 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Component
public final class AdminService {
    private static final Duration REMEMBER_TOKEN_TTL = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminStore adminStore;

    public AdminService(pub.longyi.ts3audiobot.config.ConfigService configService) {
        this.adminStore = new AdminStore(configService.getConfigStore().getDbPath());
        this.adminStore.initialize();
    }

    public boolean hasAdmin() {
        return adminStore.hasAdmin();
    }

    public void createAdmin(String username, String password) {
        PasswordHasher.PasswordHash hash = PasswordHasher.hash(password);
        adminStore.createAdmin(username, hash.saltBase64(), hash.hashBase64());
    }

    public boolean verify(String username, String password) {
        AdminStore.AdminRecord record = adminStore.getAdmin(username);
        if (record == null) {
            return false;
        }
        return PasswordHasher.verify(password, record.salt(), record.hash());
    }

    public String issueRememberToken(String username) {
        String safeUsername = username == null ? "" : username.trim();
        if (safeUsername.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        AdminStore.AdminRecord record = adminStore.getAdmin(safeUsername);
        if (record == null) {
            throw new IllegalArgumentException("admin not found");
        }
        long expiresEpoch = Instant.now().plus(REMEMBER_TOKEN_TTL).getEpochSecond();
        String nonce = newNonce();
        String userPart = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(safeUsername.getBytes(StandardCharsets.UTF_8));
        String signature = signRememberToken(record, userPart, expiresEpoch, nonce);
        return "v1." + userPart + "." + expiresEpoch + "." + nonce + "." + signature;
    }

    public String verifyRememberToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length != 5 || !"v1".equals(parts[0])) {
            return "";
        }
        String userPart = parts[1];
        String expiresRaw = parts[2];
        String nonce = parts[3];
        String signature = parts[4];
        long expiresEpoch;
        try {
            expiresEpoch = Long.parseLong(expiresRaw);
        } catch (NumberFormatException ignored) {
            return "";
        }
        if (Instant.now().getEpochSecond() >= expiresEpoch) {
            return "";
        }
        String username;
        try {
            byte[] raw = Base64.getUrlDecoder().decode(userPart);
            username = new String(raw, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return "";
        }
        if (username.isBlank()) {
            return "";
        }
        AdminStore.AdminRecord record = adminStore.getAdmin(username);
        if (record == null) {
            return "";
        }
        String expected = signRememberToken(record, userPart, expiresEpoch, nonce);
        if (!constantTimeEquals(expected, signature)) {
            return "";
        }
        return username;
    }

    public int rememberTokenMaxAgeSeconds() {
        return Math.toIntExact(REMEMBER_TOKEN_TTL.getSeconds());
    }

    private String newNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String signRememberToken(AdminStore.AdminRecord record, String userPart, long expiresEpoch, String nonce) {
        String payload = "v1|" + userPart + "|" + expiresEpoch + "|" + nonce + "|" + record.salt() + "|" + record.hash();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign remember token", ex);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8),
            b.getBytes(StandardCharsets.UTF_8)
        );
    }
}
