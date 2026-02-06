package pub.longyi.ts3audiobot.auth;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 PasswordHasher 相关功能。
 */


/**
 * PasswordHasher 相关功能。
 *
 * <p>职责：负责 PasswordHasher 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public final class PasswordHasher {
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH = 256;

    private PasswordHasher() {
    }


    /**
     * 执行 hash 操作。
     * @param password 参数 password
     * @return 返回值
     */
    public static PasswordHash hash(String password) {

        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(password, salt);
        return new PasswordHash(encode(salt), encode(hash));
    }


    /**
     * 执行 verify 操作。
     * @param password 参数 password
     * @param saltBase64 参数 saltBase64
     * @param hashBase64 参数 hashBase64
     * @return 返回值
     */
    public static boolean verify(String password, String saltBase64, String hashBase64) {
        if (password == null || saltBase64 == null || hashBase64 == null) {
            return false;
        }
        byte[] salt = decode(saltBase64);
        byte[] expected = decode(hashBase64);
        byte[] actual = pbkdf2(password, salt);
        if (expected.length != actual.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < expected.length; i++) {
            diff |= expected[i] ^ actual[i];
        }
        return diff == 0;
    }

    private static byte[] pbkdf2(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");

            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("Failed to hash password", ex);
        }
    }


    private static String encode(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private static byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
    }


    /**
     * 执行 PasswordHash 操作。
     * @param saltBase64 参数 saltBase64
     * @param hashBase64 参数 hashBase64
     * @return 返回值
     */
    public record PasswordHash(String saltBase64, String hashBase64) {
    }
}
