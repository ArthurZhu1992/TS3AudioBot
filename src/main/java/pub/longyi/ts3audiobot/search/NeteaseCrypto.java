package pub.longyi.ts3audiobot.search;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

public final class NeteaseCrypto {
    private static final String PRESET_KEY = "0CoJUm6Qyw8W8jud";
    private static final String IV = "0102030405060708";
    private static final String PUB_KEY = "010001";
    private static final String MODULUS =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record WeapiPayload(String params, String encSecKey) {
    }

    public WeapiPayload encrypt(String json) {
        String secKey = randomKey(16);
        String params = aesEncrypt(aesEncrypt(json, PRESET_KEY, IV), secKey, IV);
        String encSecKey = rsaEncrypt(secKey);
        return new WeapiPayload(params, encSecKey);
    }

    private String aesEncrypt(String text, String key, String iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8))
            );
            byte[] encrypted = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encrypt weapi payload", ex);
        }
    }

    private String rsaEncrypt(String secKey) {
        try {
            String reversed = new StringBuilder(secKey).reverse().toString();
            BigInteger text = new BigInteger(1, reversed.getBytes(StandardCharsets.UTF_8));
            BigInteger pubKey = new BigInteger(PUB_KEY, 16);
            BigInteger modulus = new BigInteger(MODULUS, 16);
            BigInteger result = text.modPow(pubKey, modulus);
            String hex = result.toString(16);
            return padLeft(hex, 256, '0');
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encrypt weapi secKey", ex);
        }
    }

    private String randomKey(int len) {
        String chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String padLeft(String value, int size, char pad) {
        if (value.length() >= size) {
            return value;
        }
        StringBuilder sb = new StringBuilder(size);
        for (int i = value.length(); i < size; i++) {
            sb.append(pad);
        }
        sb.append(value);
        return sb.toString();
    }
}
