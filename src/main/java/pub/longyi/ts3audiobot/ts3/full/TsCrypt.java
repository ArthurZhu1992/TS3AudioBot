package pub.longyi.ts3audiobot.ts3.full;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.modes.EAXBlockCipher;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.signers.DSADigestSigner;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.math.ec.ECPoint;
import Punisher.NaCl.Internal.Ed25519Ref10.GroupElementP2;
import Punisher.NaCl.Internal.Ed25519Ref10.GroupElementP3;
import Punisher.NaCl.Internal.Ed25519Ref10.GroupOperations;
import Punisher.NaCl.Internal.Ed25519Ref10.ScalarOperations;
import Punisher.NaCl.Internal.Sha512;
import pub.longyi.ts3audiobot.ts3.protocol.Packet;
import pub.longyi.ts3audiobot.ts3.protocol.PacketDirection;
import pub.longyi.ts3audiobot.ts3.protocol.PacketFlags;
import pub.longyi.ts3audiobot.ts3.protocol.PacketType;
import pub.longyi.ts3audiobot.ts3.protocol.ProtocolConst;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 TsCrypt 相关功能。
 */


/**
 * TsCrypt 相关功能。
 *
 * <p>职责：负责 TsCrypt 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public final class TsCrypt {
    public static final int MAC_LEN = 8;
    private static final int PACKET_TYPE_KINDS = 9;
    private static final int INIT_VERSION = 1566914096;
    private static final byte[] TS3_INIT_MAC = "TS3INIT1".getBytes(StandardCharsets.US_ASCII);
    private static final String DUMMY_KEY_NONCE_STRING = "c:\\windows\\system\\firewall32.cpl";
    private static final byte[] DUMMY_KEY = DUMMY_KEY_NONCE_STRING.substring(0, 16)
        .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DUMMY_NONCE = DUMMY_KEY_NONCE_STRING.substring(16, 32)
        .getBytes(StandardCharsets.US_ASCII);

    private static final X9ECParameters CURVE = ECNamedCurveTable.getByName("prime256v1");
    private static final ECDomainParameters DOMAIN = new ECDomainParameters(
        CURVE.getCurve(),
        CURVE.getG(),
        CURVE.getN(),
        CURVE.getH()
    );

    private static final Pattern IDENTITY_REGEX = Pattern.compile("^(\\d+)V([\\w/+]+={0,2})$");
    private static final byte[] TS_IDENTITY_OBFUSCATION_KEY =
        ("b9dfaa7bee6ac57ac7b65f1094a1c155e747327bc2fe5d51c512023fe54a2802"
            + "01004e90ad1daaae1075d53b7d571c30e063b5a62a4a017bb394833aa0983e6e")
            .getBytes(StandardCharsets.US_ASCII);

    private final IdentityData identity;
    private final EAXBlockCipher eaxCipher = new EAXBlockCipher(new AESEngine());
    private final SecureRandom random = new SecureRandom();

    private boolean cryptoInitComplete;
    private int initVersion = INIT_VERSION;
    private String clientIp = "";
    private byte[] alphaTmp;
    private byte[] ivStruct;
    private final byte[] fakeSignature = new byte[MAC_LEN];
    private final KeyNonce[] cachedKeyNonces = new KeyNonce[PACKET_TYPE_KINDS * 2];

    /**
     * 创建 TsCrypt 实例。
     * @param identity 参数 identity
     */
    public TsCrypt(IdentityData identity) {
        if (identity == null) {
            throw new IllegalArgumentException("identity required");
        }
        this.identity = identity;
        reset();
    }


    /**
     * 执行 reset 操作。
     */
    public void reset() {
        cryptoInitComplete = false;
        ivStruct = null;
        alphaTmp = null;
        initVersion = INIT_VERSION;
        for (int i = 0; i < cachedKeyNonces.length; i++) {
            cachedKeyNonces[i] = null;
        }
    }


    /**
     * 执行 setClientIp 操作。
     * @param clientIp 参数 clientIp
     */
    public void setClientIp(String clientIp) {
        this.clientIp = clientIp == null ? "" : clientIp.trim();
    }


    /**
     * 执行 isCryptoInitComplete 操作。
     * @return 返回值
     */
    public boolean isCryptoInitComplete() {
        return cryptoInitComplete;
    }


    /**
     * 执行 cryptoInit 操作。
     * @param alpha 参数 alpha
     * @param beta 参数 beta
     * @param omega 参数 omega
     * @return 返回值
     */
    public String cryptoInit(String alpha, String beta, String omega) {
        byte[] alphaBytes = base64Decode(alpha);
        if (alphaBytes == null) {
            return "alphaBytes parameter is invalid";
        }
        byte[] betaBytes = base64Decode(beta);
        if (betaBytes == null) {
            return "betaBytes parameter is invalid";
        }
        byte[] omegaBytes = base64Decode(omega);
        if (omegaBytes == null) {
            return "omegaBytes parameter is invalid";
        }
        ECPoint serverPublicKey = importPublicKey(omegaBytes);
        byte[] sharedKey = getSharedSecret(serverPublicKey);
        return setSharedSecret(alphaBytes, betaBytes, sharedKey);
    }


    /**
     * 执行 cryptoInit2 操作。
     * @param license 参数 license
     * @param omega 参数 omega
     * @param proof 参数 proof
     * @param beta 参数 beta
     * @param privateKey 参数 privateKey
     * @return 返回值
     */
    public String cryptoInit2(String license, String omega, String proof, String beta, byte[] privateKey) {
        if (alphaTmp == null) {
            return "alpha not initialized";
        }
        byte[] licenseBytes = base64Decode(license);
        if (licenseBytes == null) {
            return "license parameter is invalid";
        }
        byte[] omegaBytes = base64Decode(omega);
        if (omegaBytes == null) {
            return "omega parameter is invalid";
        }
        byte[] proofBytes = base64Decode(proof);
        if (proofBytes == null) {
            return "proof parameter is invalid";
        }
        byte[] betaBytes = base64Decode(beta);
        if (betaBytes == null) {
            return "beta parameter is invalid";
        }
        ECPoint serverPublicKey = importPublicKey(omegaBytes);

        if (!verifySign(serverPublicKey, licenseBytes, proofBytes)) {
            return "init proof not valid";
        }

        Licenses licenses;
        try {
            licenses = Licenses.parse(licenseBytes);
        } catch (RuntimeException ex) {
            return ex.getMessage();
        }
        byte[] key = licenses.deriveKey();
        byte[] sharedKey = getSharedSecret2(key, privateKey);
        return setSharedSecret(alphaTmp, betaBytes, sharedKey);
    }


    /**
     * 执行 processInit1 操作。
     * @param direction 参数 direction
     * @param data 参数 data
     * @return 返回值
     */
    public byte[] processInit1(PacketDirection direction, byte[] data) {
        final int versionLen = 4;
        final int initTypeLen = 1;

        int type;
        if (data != null) {
            if (direction == PacketDirection.S2C) {
                if (data.length < initTypeLen) {
                    throw new IllegalArgumentException("Invalid Init1 packet (too short)");
                }
                type = data[0] & 0xFF;
                if (type != 1 && type != 3 && type != 0x7F) {
                    throw new IllegalArgumentException("Invalid Init1 packet (invalid step)");
                }
            } else {
                if (data.length < versionLen + initTypeLen) {
                    throw new IllegalArgumentException("Invalid Init1 packet (too short)");
                }
                type = data[4] & 0xFF;
                if (type != 0 && type != 2 && type != 4) {
                    throw new IllegalArgumentException("Invalid Init1 packet (invalid step)");
                }
            }
        } else {
            type = -1;
        }

        if (data == null || type == 0x7F) {
            updateInitVersion();
            byte[] sendData = new byte[versionLen + initTypeLen + 4 + 4 + 8];
            writeUInt32BE(sendData, 0, initVersion);
            sendData[versionLen] = 0x00;
            writeUInt32BE(sendData, versionLen + initTypeLen, (int) Instant.now().getEpochSecond());
            writeInt32BE(sendData, versionLen + initTypeLen + 4, random.nextInt());
            return sendData;
        }

        switch (type) {
            case 0: {
                if (data.length != 21) {
                    throw new IllegalArgumentException("Invalid Init1 packet (invalid length)");
                }
                byte[] sendData = new byte[initTypeLen + 16 + 4];
                sendData[0] = 0x01;
                int little = readUInt32LE(data, versionLen + initTypeLen + 4);
                writeUInt32BE(sendData, initTypeLen + 16, little);
                return sendData;
            }
            case 1: {
                if (data.length == 21) {
                    byte[] sendData = new byte[versionLen + initTypeLen + 16 + 4];
                    writeUInt32BE(sendData, 0, initVersion);
                    sendData[versionLen] = 0x02;
                    System.arraycopy(data, initTypeLen, sendData, versionLen + initTypeLen, 20);
                    return sendData;
                }
                if (data.length == 5) {
                    int errorNum = readUInt32BE(data, initTypeLen);
                    throw new IllegalArgumentException("Init1(1) error: " + errorNum);
                }
                throw new IllegalArgumentException("Invalid Init1 packet (invalid length)");
            }
            case 2: {
                if (data.length != versionLen + initTypeLen + 16 + 4) {
                    throw new IllegalArgumentException("Invalid Init1 packet (invalid length)");
                }
                byte[] sendData = new byte[initTypeLen + 64 + 64 + 4 + 100];
                sendData[0] = 0x03;
                sendData[initTypeLen + 64 - 1] = 1;
                sendData[initTypeLen + 64 + 64 - 1] = 1;
                writeInt32BE(sendData, initTypeLen + 64 + 64, 1);
                return sendData;
            }
            case 3: {
                if (data.length != initTypeLen + 64 + 64 + 4 + 100) {
                    throw new IllegalArgumentException("Invalid Init1 packet (invalid length)");
                }
                alphaTmp = new byte[10];
                random.nextBytes(alphaTmp);
                String alpha = Base64.getEncoder().encodeToString(alphaTmp);
                String command = buildClientInitIv(alpha, identity.publicKeyString());
                byte[] textBytes = command.getBytes(StandardCharsets.UTF_8);

                int level = readInt32BE(data, initTypeLen + 128);
                byte[] y = solveRsaChallenge(data, initTypeLen, level);

                byte[] sendData = new byte[versionLen + initTypeLen + 64 + 64 + 4 + 100 + 64 + textBytes.length];
                writeUInt32BE(sendData, 0, initVersion);
                sendData[versionLen] = 0x04;
                System.arraycopy(data, initTypeLen, sendData, versionLen + initTypeLen, 232);
                System.arraycopy(y, 0, sendData, versionLen + initTypeLen + 232 + (64 - y.length), y.length);
                System.arraycopy(textBytes, 0, sendData, versionLen + initTypeLen + 232 + 64, textBytes.length);
                return sendData;
            }
            case 4: {
                if (data.length < versionLen + initTypeLen + 64 + 64 + 4 + 100 + 64) {
                    throw new IllegalArgumentException("Invalid Init1 packet (too short)");
                }
                return new byte[0];
            }
            default:
                throw new IllegalArgumentException("Invalid Init1 step: " + type);
        }
    }


    /**
     * 执行 encrypt 操作。
     * @param packet 参数 packet
     */
    public void encrypt(Packet packet) {
        if (packet.getPacketType() == PacketType.INIT1) {
            fakeEncrypt(packet, TS3_INIT_MAC);
            return;
        }
        if (packet.hasFlag(PacketFlags.UNENCRYPTED)) {
            fakeEncrypt(packet, fakeSignature);
            return;
        }
        if (ivStruct == null && cryptoInitComplete) {
            throw new IllegalStateException("Crypto not initialized");
        }

        byte[] header = packet.buildHeader();
        KeyNonce keyNonce = getKeyNonce(
            packet.getDirection() == PacketDirection.S2C,
            packet.getPacketId(),
            packet.getGenerationId(),
            packet.getPacketType(),
            !cryptoInitComplete
        );
        AEADParameters params = new AEADParameters(
            new KeyParameter(keyNonce.key),
            MAC_LEN * 8,
            keyNonce.nonce,
            header
        );

        byte[] result;
        int len;
        try {
            synchronized (eaxCipher) {
                eaxCipher.init(true, params);
                result = new byte[eaxCipher.getOutputSize(packet.getData().length)];
                len = eaxCipher.processBytes(packet.getData(), 0, packet.getData().length, result, 0);
                len += eaxCipher.doFinal(result, len);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Encryption failed", ex);
        }

        byte[] raw = new byte[MAC_LEN + header.length + (len - MAC_LEN)];
        System.arraycopy(result, len - MAC_LEN, raw, 0, MAC_LEN);
        System.arraycopy(header, 0, raw, MAC_LEN, header.length);
        System.arraycopy(result, 0, raw, MAC_LEN + header.length, len - MAC_LEN);
        packet.setRaw(raw);
    }


    /**
     * 执行 decrypt 操作。
     * @param packet 参数 packet
     * @return 返回值
     */
    public boolean decrypt(Packet packet) {
        if (packet.getPacketType() == PacketType.INIT1) {
            return fakeDecrypt(packet, TS3_INIT_MAC);
        }
        if (packet.hasFlag(PacketFlags.UNENCRYPTED)) {
            return fakeDecrypt(packet, fakeSignature);
        }

        boolean ok = decryptData(packet, !cryptoInitComplete);
        if (ok) {
            return true;
        }
        if (packet.getPacketType() == PacketType.ACK && packet.getPacketId() <= 2) {
            return decryptData(packet, true);
        }
        return false;
    }

    private boolean decryptData(Packet packet, boolean dummyEncryption) {
        byte[] header = packet.buildHeader();
        KeyNonce keyNonce = getKeyNonce(
            packet.getDirection() == PacketDirection.S2C,
            packet.getPacketId(),
            packet.getGenerationId(),
            packet.getPacketType(),
            dummyEncryption
        );
        int dataLen = packet.getRaw().length - (MAC_LEN + header.length);
        AEADParameters params = new AEADParameters(
            new KeyParameter(keyNonce.key),
            MAC_LEN * 8,
            keyNonce.nonce,
            header
        );

        try {
            byte[] result;
            int len;
            synchronized (eaxCipher) {
                eaxCipher.init(false, params);
                result = new byte[eaxCipher.getOutputSize(dataLen + MAC_LEN)];
                len = eaxCipher.processBytes(packet.getRaw(), MAC_LEN + header.length, dataLen, result, 0);
                len += eaxCipher.processBytes(packet.getRaw(), 0, MAC_LEN, result, len);
                len += eaxCipher.doFinal(result, len);
            }
            if (len != dataLen) {
                return false;
            }
            packet.setData(result);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private void fakeEncrypt(Packet packet, byte[] mac) {
        byte[] header = packet.buildHeader();
        byte[] raw = new byte[MAC_LEN + header.length + packet.getData().length];
        System.arraycopy(mac, 0, raw, 0, MAC_LEN);
        System.arraycopy(header, 0, raw, MAC_LEN, header.length);
        System.arraycopy(packet.getData(), 0, raw, MAC_LEN + header.length, packet.getData().length);
        packet.setRaw(raw);
    }

    private boolean fakeDecrypt(Packet packet, byte[] mac) {
        byte[] raw = packet.getRaw();
        if (raw == null || raw.length < MAC_LEN) {
            return false;
        }
        for (int i = 0; i < MAC_LEN; i++) {
            if (raw[i] != mac[i]) {
                return false;
            }
        }
        byte[] header = packet.buildHeader();
        int dataLen = raw.length - (MAC_LEN + header.length);
        byte[] data = new byte[dataLen];
        System.arraycopy(raw, MAC_LEN + header.length, data, 0, dataLen);
        packet.setData(data);
        return true;
    }

    private String setSharedSecret(byte[] alpha, byte[] beta, byte[] sharedKey) {
        if (beta.length != 10 && beta.length != 54) {
            return "Invalid beta size (" + beta.length + ")";
        }
        if (sharedKey.length < 10 + beta.length) {
            return "Shared key length too short";
        }
        ivStruct = new byte[10 + beta.length];
        xorBinary(sharedKey, alpha, alpha.length, ivStruct);
        xorBinary(slice(sharedKey, 10, beta.length), beta, beta.length, ivStruct, 10);

        byte[] buffer = hash1(ivStruct);
        System.arraycopy(buffer, 0, fakeSignature, 0, MAC_LEN);

        alphaTmp = null;
        cryptoInitComplete = true;
        return null;
    }

    private byte[] getSharedSecret(ECPoint serverPublicKey) {
        ECPoint p = serverPublicKey.multiply(identity.privateKey()).normalize();
        byte[] keyArr = p.getAffineXCoord().toBigInteger().toByteArray();
        if (keyArr.length == 32) {
            return hash1(keyArr);
        }
        if (keyArr.length > 32) {
            return hash1(keyArr, keyArr.length - 32, 32);
        }
        byte[] extended = new byte[32];
        System.arraycopy(keyArr, 0, extended, 32 - keyArr.length, keyArr.length);
        return hash1(extended);
    }

    private static byte[] getSharedSecret2(byte[] publicKey, byte[] privateKey) {
        if (publicKey == null || publicKey.length != 32 || privateKey == null || privateKey.length != 32) {
            throw new IllegalArgumentException("invalid key size");
        }
        byte[] privateKeyCopy = new byte[32];
        System.arraycopy(privateKey, 0, privateKeyCopy, 0, 32);
        privateKeyCopy[31] &= 0x7F;

        GroupElementP3 pub = new GroupElementP3();
        GroupOperations.ge_frombytes_negate_vartime(pub, publicKey, 0);

        GroupElementP2 mul = new GroupElementP2();
        GroupOperations.ge_scalarmult_vartime(mul, privateKeyCopy, pub);

        byte[] sharedTmp = new byte[32];
        GroupOperations.ge_tobytes(sharedTmp, 0, mul);
        sharedTmp[31] ^= (byte) 0x80;
        try {
            return Sha512.Hash(sharedTmp);
        } catch (Exception ex) {
            throw new IllegalStateException("sha512 failed", ex);
        }
    }

    private KeyNonce getKeyNonce(
        boolean fromServer,
        int packetId,
        int generationId,
        PacketType packetType,
        boolean dummyEncryption
    ) {
        if (dummyEncryption) {
            return new KeyNonce(DUMMY_KEY, DUMMY_NONCE, generationId);
        }
        if (ivStruct == null) {
            throw new IllegalStateException("Crypto not initialized");
        }
        byte packetTypeRaw = (byte) (packetType.value() & 0x0F);
        int cacheIndex = (packetTypeRaw * 2) + (fromServer ? 0 : 1);
        KeyNonce cache = cachedKeyNonces[cacheIndex];
        if (cache == null || cache.generationId != generationId) {
            byte[] tmpToHash = new byte[ivStruct.length == 20 ? 26 : 70];
            tmpToHash[0] = fromServer ? (byte) 0x30 : (byte) 0x31;
            tmpToHash[1] = packetTypeRaw;
            writeUInt32BE(tmpToHash, 2, generationId);
            System.arraycopy(ivStruct, 0, tmpToHash, 6, ivStruct.length);
            byte[] result = hash256(tmpToHash);
            cache = new KeyNonce(slice(result, 0, 16), slice(result, 16, 16), generationId);
            cachedKeyNonces[cacheIndex] = cache;
        }
        byte[] key = slice(cache.key, 0, 16);
        byte[] nonce = slice(cache.nonce, 0, 16);
        key[0] ^= (byte) (packetId >> 8);
        key[1] ^= (byte) (packetId);
        return new KeyNonce(key, nonce, generationId);
    }

    private static byte[] solveRsaChallenge(byte[] data, int offset, int level) {
        if (level < 0 || level > 1_000_000) {
            throw new IllegalArgumentException("RSA challenge level out of range");
        }
        byte[] xBytes = new byte[64];
        byte[] nBytes = new byte[64];
        System.arraycopy(data, offset, xBytes, 0, 64);
        System.arraycopy(data, offset + 64, nBytes, 0, 64);
        BigInteger x = new BigInteger(1, xBytes);
        BigInteger n = new BigInteger(1, nBytes);
        BigInteger exponent = BigInteger.TWO.pow(level);
        BigInteger y = x.modPow(exponent, n);
        return stripLeadingZeros(y.toByteArray());
    }

    private String buildClientInitIv(String alpha, String omega) {
        String ipValue = clientIp == null ? "" : clientIp;
        return "clientinitiv alpha=" + alpha
            + " omega=" + omega
            + " ot=1 ip=" + ipValue;
    }

    private void updateInitVersion() {
        long now = Instant.now().getEpochSecond();
        long version = now - 1356998400L;
        if (version > 0 && version <= 0xFFFFFFFFL) {
            initVersion = (int) version;
        } else {
            initVersion = INIT_VERSION;
        }
    }

    private static void xorBinary(byte[] a, byte[] b, int len, byte[] out) {
        xorBinary(a, b, len, out, 0);
    }

    private static void xorBinary(byte[] a, byte[] b, int len, byte[] out, int outOffset) {
        if (a.length < len || b.length < len || out.length < outOffset + len) {
            throw new IllegalArgumentException("xor length invalid");
        }
        for (int i = 0; i < len; i++) {
            out[outOffset + i] = (byte) (a[i] ^ b[i]);
        }
    }

    private static byte[] hash1(byte[] data) {
        return hash1(data, 0, data.length);
    }

    private static byte[] hash1(byte[] data, int offset, int len) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(data, offset, len);
            return sha1.digest();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 not available", ex);
        }
    }

    private static byte[] hash256(byte[] data) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return sha256.digest(data);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    static byte[] hash512(byte[] data) {
        try {
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            return sha512.digest(data);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-512 not available", ex);
        }
    }

    private static byte[] base64Decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static byte[] encodeAsn1(ASN1Primitive primitive) {
        try {
            return primitive.getEncoded();
        } catch (Exception ex) {
            throw new IllegalStateException("ASN.1 encoding failed", ex);
        }
    }

    private static ImportKeyResult importKeyDynamic(byte[] asnBytes) {
        try {
            ASN1Sequence seq = (ASN1Sequence) ASN1Primitive.fromByteArray(asnBytes);
            DERBitString bitString = (DERBitString) seq.getObjectAt(0);
            int bitInfo = bitString.intValue();
            ECPoint publicKey = null;
            BigInteger privateKey = null;

            if (bitInfo == 0x00 || bitInfo == 0x80) {
                BigInteger x = ((ASN1Integer) seq.getObjectAt(2)).getValue();
                BigInteger y = ((ASN1Integer) seq.getObjectAt(3)).getValue();
                publicKey = CURVE.getCurve().createPoint(x, y);
                if (bitInfo == 0x80) {
                    privateKey = ((ASN1Integer) seq.getObjectAt(4)).getValue();
                }
            } else if (bitInfo == 0xC0) {
                privateKey = ((ASN1Integer) seq.getObjectAt(2)).getValue();
            }
            return new ImportKeyResult(publicKey, privateKey);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not import identity: " + ex.getMessage(), ex);
        }
    }

    private static ECPoint importPublicKey(byte[] asnBytes) {
        ImportKeyResult result = importKeyDynamic(asnBytes);
        if (result.publicKey == null) {
            throw new IllegalArgumentException("Could not import public key");
        }
        return result.publicKey;
    }

    private static IdentityData deobfuscateAndImportTsIdentity(String identity) {
        Matcher matcher = IDENTITY_REGEX.matcher(identity);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("identity could not be matched as teamspeak identity");
        }
        long level = Long.parseLong(matcher.group(1));
        byte[] ident = base64Decode(matcher.group(2));
        if (ident == null || ident.length < 20) {
            throw new IllegalArgumentException("invalid identity base64 string");
        }
        int nullIndex = -1;
        for (int i = 20; i < ident.length; i++) {
            if (ident[i] == 0) {
                nullIndex = i;
                break;
            }
        }
        int hashLen = nullIndex < 0 ? ident.length - 20 : nullIndex - 20;
        byte[] hash = hash1(ident, 20, hashLen);
        xorBinary(ident, hash, 20, ident);
        xorBinary(ident, TS_IDENTITY_OBFUSCATION_KEY, Math.min(100, ident.length), ident);

        byte[] decoded = base64Decode(new String(ident, StandardCharsets.US_ASCII));
        if (decoded == null) {
            throw new IllegalArgumentException("invalid deobfuscated base64 string");
        }
        ImportKeyResult result = importKeyDynamic(decoded);
        if (result.privateKey == null) {
            throw new IllegalArgumentException("key string did not contain a private key");
        }
        IdentityData identityData = new IdentityData(result.privateKey, result.publicKey);
        identityData.setValidKeyOffset(level);
        identityData.setLastCheckedKeyOffset(level);
        return identityData;
    }


    /**
     * 执行 loadIdentityDynamic 操作。
     * @param key 参数 key
     * @param keyOffset 参数 keyOffset
     * @return 返回值
     */
    public static IdentityData loadIdentityDynamic(String key, long keyOffset) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("identity key is empty");
        }
        try {
            return deobfuscateAndImportTsIdentity(key);
        } catch (RuntimeException ex) {
            return loadIdentity(key, keyOffset);
        }
    }


    /**
     * 执行 loadIdentity 操作。
     * @param key 参数 key
     * @param keyOffset 参数 keyOffset
     * @return 返回值
     */
    public static IdentityData loadIdentity(String key, long keyOffset) {
        byte[] data = base64Decode(key);
        if (data == null) {
            throw new IllegalArgumentException("invalid identity base64 string");
        }
        ImportKeyResult result = importKeyDynamic(data);
        if (result.privateKey == null) {
            throw new IllegalArgumentException("key string did not contain a private key");
        }
        IdentityData identity = new IdentityData(result.privateKey, result.publicKey);
        identity.setValidKeyOffset(keyOffset);
        identity.setLastCheckedKeyOffset(keyOffset);
        return identity;
    }


    /**
     * 执行 generateNewIdentity 操作。
     * @param securityLevel 参数 securityLevel
     * @return 返回值
     */
    public static IdentityData generateNewIdentity(int securityLevel) {
        ECKeyPairGenerator generator = new ECKeyPairGenerator();
        generator.init(new ECKeyGenerationParameters(DOMAIN, new SecureRandom()));
        AsymmetricCipherKeyPair pair = generator.generateKeyPair();
        ECPrivateKeyParameters privateKey = (ECPrivateKeyParameters) pair.getPrivate();
        ECPublicKeyParameters publicKey = (ECPublicKeyParameters) pair.getPublic();
        IdentityData identity = new IdentityData(privateKey.getD(), publicKey.getQ());
        identity.setValidKeyOffset(Math.max(0, securityLevel));
        identity.setLastCheckedKeyOffset(Math.max(0, securityLevel));
        return identity;
    }


    /**
     * 执行 generateTsIdentity 操作。
     * @param securityLevel 参数 securityLevel
     * @return 返回值
     */
    public static String generateTsIdentity(int securityLevel) {
        IdentityData identity = generateNewIdentity(securityLevel);
        return exportTsIdentity(identity);
    }


    /**
     * 执行 findKeyOffset 操作。
     * @param identity 参数 identity
     * @param targetLevel 参数 targetLevel
     * @param startOffset 参数 startOffset
     * @return 返回值
     */
    public static KeyOffsetResult findKeyOffset(IdentityData identity, int targetLevel, long startOffset) {
        if (identity == null) {
            throw new IllegalArgumentException("identity must not be null");
        }
        int desired = Math.max(0, targetLevel);
        long offset = Math.max(0L, startOffset);
        int level = computeSecurityLevel(identity, offset);
        if (level >= desired) {
            return new KeyOffsetResult(offset, level, 0L);
        }
        MessageDigest sha1 = sha1();
        byte[] publicKey = identity.publicKeyString().getBytes(StandardCharsets.US_ASCII);
        long iterations = 0L;
        while (true) {
            offset++;
            iterations++;
            level = computeSecurityLevel(publicKey, offset, sha1);
            if (level >= desired) {
                return new KeyOffsetResult(offset, level, iterations);
            }
        }
    }

    public static int computeSecurityLevel(IdentityData identity, long offset) {
        MessageDigest sha1 = sha1();
        byte[] publicKey = identity.publicKeyString().getBytes(StandardCharsets.US_ASCII);
        return computeSecurityLevel(publicKey, offset, sha1);
    }

    private static int computeSecurityLevel(byte[] publicKey, long offset, MessageDigest sha1) {
        sha1.reset();
        sha1.update(publicKey);
        sha1.update(Long.toString(offset).getBytes(StandardCharsets.US_ASCII));
        byte[] hash = sha1.digest();
        return countLeadingZeroBits(hash);
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 digest not available", ex);
        }
    }

    private static int countLeadingZeroBits(byte[] hash) {
        int count = 0;
        for (byte b : hash) {
            int value = b & 0xFF;
            if (value == 0) {
                count += 8;
                continue;
            }
            count += Integer.numberOfLeadingZeros(value) - 24;
            break;
        }
        return count;
    }


    /**
     * 执行 isTsIdentityFormat 操作。
     * @param identity 参数 identity
     * @return 返回值
     */
    public static boolean isTsIdentityFormat(String identity) {
        if (identity == null || identity.isBlank()) {
            return false;
        }
        return IDENTITY_REGEX.matcher(identity.trim()).matches();

    }


    /**
     * 执行 exportPublicKey 操作。
     * @param publicKey 参数 publicKey
     * @return 返回值
     */
    public static String exportPublicKey(ECPoint publicKey) {
        DERSequence seq = new DERSequence(new ASN1Encodable[] {

            new DERBitString(new byte[] { 0 }, 7),
            new ASN1Integer(BigInteger.valueOf(32)),
            new ASN1Integer(publicKey.getAffineXCoord().toBigInteger()),
            new ASN1Integer(publicKey.getAffineYCoord().toBigInteger())
        });
        return Base64.getEncoder().encodeToString(encodeAsn1(seq));
    }


    /**
     * 执行 exportPrivateKey 操作。
     * @param privateKey 参数 privateKey
     * @return 返回值
     */
    public static String exportPrivateKey(BigInteger privateKey) {
        DERSequence seq = new DERSequence(new ASN1Encodable[] {
            new DERBitString(new byte[] { (byte) 0xC0 }, 6),
            new ASN1Integer(BigInteger.valueOf(32)),
            new ASN1Integer(privateKey)
        });
        return Base64.getEncoder().encodeToString(encodeAsn1(seq));
    }


    /**
     * 执行 exportPublicAndPrivateKey 操作。
     * @param publicKey 参数 publicKey
     * @param privateKey 参数 privateKey
     * @return 返回值
     */
    public static String exportPublicAndPrivateKey(ECPoint publicKey, BigInteger privateKey) {
        DERSequence seq = new DERSequence(new ASN1Encodable[] {
            new DERBitString(new byte[] { (byte) 0x80 }, 7),
            new ASN1Integer(BigInteger.valueOf(32)),
            new ASN1Integer(publicKey.getAffineXCoord().toBigInteger()),
            new ASN1Integer(publicKey.getAffineYCoord().toBigInteger()),
            new ASN1Integer(privateKey)
        });
        return Base64.getEncoder().encodeToString(encodeAsn1(seq));
    }


    /**
     * 执行 exportTsIdentity 操作。
     * @param identity 参数 identity
     * @return 返回值
     */
    public static String exportTsIdentity(IdentityData identity) {
        if (identity == null) {

            throw new IllegalArgumentException("identity must not be null");
        }
        String keyBase64 = identity.publicAndPrivateKeyString();
        byte[] plain = keyBase64.getBytes(StandardCharsets.US_ASCII);

        if (plain.length < 20) {
            return keyBase64;
        }

        byte[] obfuscated = Arrays.copyOf(plain, plain.length);
        byte[] hash = hash1(obfuscated, 20, obfuscated.length - 20);
        xorBinary(obfuscated, hash, 20, obfuscated);
        xorBinary(obfuscated, TS_IDENTITY_OBFUSCATION_KEY, Math.min(100, obfuscated.length), obfuscated);
        String encoded = Base64.getEncoder().encodeToString(obfuscated);
        long level = Math.max(0L, identity.validKeyOffset());

        return level + "V" + encoded;
    }


    /**
     * 执行 getUidFromPublicKey 操作。
     * @param publicKey 参数 publicKey
     * @return 返回值
     */
    public static String getUidFromPublicKey(String publicKey) {
        byte[] bytes = publicKey.getBytes(StandardCharsets.US_ASCII);
        return Base64.getEncoder().encodeToString(hash1(bytes));
    }


    /**
     * 执行 restorePublicFromPrivateKey 操作。
     * @param privateKey 参数 privateKey
     * @return 返回值
     */
    public static ECPoint restorePublicFromPrivateKey(BigInteger privateKey) {
        return CURVE.getG().multiply(privateKey).normalize();

    }


    /**
     * 执行 hashPassword 操作。
     * @param password 参数 password
     * @return 返回值
     */
    public static String hashPassword(String password) {
        if (password == null || password.isEmpty()) {
            return "";
        }
        byte[] bytes = password.getBytes(StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(hash1(bytes));
    }


    /**
     * 执行 generateTemporaryKey 操作。
     * @return 返回值
     */
    public static TempKey generateTemporaryKey() {
        byte[] privateKey = new byte[32];
        new SecureRandom().nextBytes(privateKey);
        clampScalar(privateKey);
        GroupElementP3 base = new GroupElementP3();
        GroupOperations.ge_scalarmult_base(base, privateKey, 0);
        byte[] publicKey = new byte[32];
        GroupOperations.ge_p3_tobytes(publicKey, 0, base);
        return new TempKey(publicKey, privateKey);
    }


    /**
     * 执行 sign 操作。
     * @param privateKey 参数 privateKey
     * @param data 参数 data
     * @return 返回值
     */
    public static byte[] sign(BigInteger privateKey, byte[] data) {
        DSADigestSigner signer = new DSADigestSigner(new ECDSASigner(), new SHA256Digest());
        ECPrivateKeyParameters keyParams = new ECPrivateKeyParameters(privateKey, DOMAIN);
        signer.init(true, keyParams);
        signer.update(data, 0, data.length);
        return signer.generateSignature();
    }


    /**
     * 执行 verifySign 操作。
     * @param publicKey 参数 publicKey
     * @param data 参数 data
     * @param proof 参数 proof
     * @return 返回值
     */
    public static boolean verifySign(ECPoint publicKey, byte[] data, byte[] proof) {
        DSADigestSigner signer = new DSADigestSigner(new ECDSASigner(), new SHA256Digest());
        ECPublicKeyParameters keyParams = new ECPublicKeyParameters(publicKey, DOMAIN);
        signer.init(false, keyParams);
        signer.update(data, 0, data.length);
        return signer.verifySignature(proof);
    }


    static void clampScalar(byte[] scalar) {
        if (scalar == null || scalar.length < 32) {
            throw new IllegalArgumentException("scalar too short");
        }
        ScalarOperations.sc_clamp(scalar, 0);
    }

    private static byte[] slice(byte[] input, int offset, int len) {
        byte[] out = new byte[len];
        System.arraycopy(input, offset, out, 0, len);
        return out;
    }

    private static byte[] stripLeadingZeros(byte[] input) {
        int idx = 0;
        while (idx < input.length - 1 && input[idx] == 0) {
            idx++;
        }
        byte[] out = new byte[input.length - idx];
        System.arraycopy(input, idx, out, 0, out.length);
        return out;
    }

    private static void writeUInt32BE(byte[] buffer, int offset, int value) {
        ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt(value);
    }

    private static void writeInt32BE(byte[] buffer, int offset, int value) {
        ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt(value);
    }

    private static int readUInt32LE(byte[] buffer, int offset) {
        long value = ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
        return (int) value;
    }

    private static int readUInt32BE(byte[] buffer, int offset) {
        long value = ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt() & 0xFFFFFFFFL;
        return (int) value;
    }

    private static int readInt32BE(byte[] buffer, int offset) {
        return ByteBuffer.wrap(buffer, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            int idx = i * 2;
            out[i] = (byte) Integer.parseInt(hex.substring(idx, idx + 2), 16);
        }
        return out;
    }


    /**
     * 执行 TempKey 操作。
     * @param publicKey 参数 publicKey
     * @param privateKey 参数 privateKey
     * @return 返回值
     */
    public record TempKey(byte[] publicKey, byte[] privateKey) {
    }

    public record KeyOffsetResult(long offset, int level, long iterations) {
    }

    private record ImportKeyResult(ECPoint publicKey, BigInteger privateKey) {
    }

    private record KeyNonce(byte[] key, byte[] nonce, int generationId) {
    }

}
