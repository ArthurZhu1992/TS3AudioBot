package pub.longyi.ts3audiobot.ts3.full;

import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 IdentityData 相关功能。
 */


/**
 * IdentityData 相关功能。
 *
 * <p>职责：负责 IdentityData 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public final class IdentityData {
    private final ECPoint publicKey;
    private final BigInteger privateKey;
    private long validKeyOffset;
    private long lastCheckedKeyOffset;

    private String publicKeyString;
    private String privateKeyString;
    private String publicAndPrivateKeyString;
    private String clientUid;

    /**
     * 创建 IdentityData 实例。
     * @param privateKey 参数 privateKey
     * @param publicKey 参数 publicKey
     */
    public IdentityData(BigInteger privateKey, ECPoint publicKey) {
        if (privateKey == null) {
            throw new IllegalArgumentException("privateKey required");
        }
        this.privateKey = privateKey;
        this.publicKey = publicKey == null ? TsCrypt.restorePublicFromPrivateKey(privateKey) : publicKey;
    }


    /**
     * 执行 publicKey 操作。
     * @return 返回值
     */
    public ECPoint publicKey() {
        return publicKey;
    }


    /**
     * 执行 privateKey 操作。
     * @return 返回值
     */
    public BigInteger privateKey() {
        return privateKey;
    }


    /**
     * 执行 validKeyOffset 操作。
     * @return 返回值
     */
    public long validKeyOffset() {
        return validKeyOffset;
    }


    /**
     * 执行 setValidKeyOffset 操作。
     * @param validKeyOffset 参数 validKeyOffset
     */
    public void setValidKeyOffset(long validKeyOffset) {
        this.validKeyOffset = validKeyOffset;
    }


    /**
     * 执行 lastCheckedKeyOffset 操作。
     * @return 返回值
     */
    public long lastCheckedKeyOffset() {
        return lastCheckedKeyOffset;
    }


    /**
     * 执行 setLastCheckedKeyOffset 操作。
     * @param lastCheckedKeyOffset 参数 lastCheckedKeyOffset
     */
    public void setLastCheckedKeyOffset(long lastCheckedKeyOffset) {
        this.lastCheckedKeyOffset = lastCheckedKeyOffset;
    }


    /**
     * 执行 publicKeyString 操作。
     * @return 返回值
     */
    public String publicKeyString() {
        if (publicKeyString == null) {
            publicKeyString = TsCrypt.exportPublicKey(publicKey);
        }
        return publicKeyString;
    }


    /**
     * 执行 privateKeyString 操作。
     * @return 返回值
     */
    public String privateKeyString() {
        if (privateKeyString == null) {
            privateKeyString = TsCrypt.exportPrivateKey(privateKey);
        }
        return privateKeyString;
    }


    /**
     * 执行 publicAndPrivateKeyString 操作。
     * @return 返回值
     */
    public String publicAndPrivateKeyString() {
        if (publicAndPrivateKeyString == null) {
            publicAndPrivateKeyString = TsCrypt.exportPublicAndPrivateKey(publicKey, privateKey);
        }
        return publicAndPrivateKeyString;
    }


    /**
     * 执行 clientUid 操作。
     * @return 返回值
     */
    public String clientUid() {
        if (clientUid == null) {
            clientUid = TsCrypt.getUidFromPublicKey(publicKeyString());
        }
        return clientUid;
    }
}
