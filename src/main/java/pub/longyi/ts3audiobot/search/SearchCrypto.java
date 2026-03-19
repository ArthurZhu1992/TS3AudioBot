package pub.longyi.ts3audiobot.search;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * SearchCrypto 相关功能。
 *
 * <p>职责：为搜索登录态提供简单加解密。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：密钥为空时会退化为明文存储。</p>
 */
@Slf4j
public final class SearchCrypto {
    private static final String PREFIX = "v1:";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey secretKey;
    private final boolean enabled;

    public SearchCrypto(String secret) {
        if (secret == null || secret.isBlank()) {
            log.warn("Search auth secret is empty; cookies will be stored in plain text.");
            this.secretKey = null;
            this.enabled = false;
            return;
        }
        this.secretKey = deriveKey(secret);
        this.enabled = true;
    }

    public String encrypt(String raw) {
        if (!enabled || raw == null || raw.isBlank()) {
            return raw;
        }
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(raw.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception ex) {
            log.warn("Failed to encrypt search auth, falling back to plain text", ex);
            return raw;
        }
    }

    public String decrypt(String value) {
        if (!enabled || value == null || value.isBlank()) {
            return value;
        }
        if (!value.startsWith(PREFIX)) {
            return value;
        }
        try {
            String payload = value.substring(PREFIX.length());
            byte[] data = Base64.getDecoder().decode(payload);
            if (data.length <= GCM_IV_BYTES) {
                return "";
            }
            byte[] iv = new byte[GCM_IV_BYTES];
            byte[] cipherText = new byte[data.length - GCM_IV_BYTES];
            System.arraycopy(data, 0, iv, 0, iv.length);
            System.arraycopy(data, iv.length, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            log.warn("Failed to decrypt search auth; returning empty value", ex);
            return "";
        }
    }

    private SecretKey deriveKey(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, "AES");
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to derive search auth key", ex);
        }
    }
}
