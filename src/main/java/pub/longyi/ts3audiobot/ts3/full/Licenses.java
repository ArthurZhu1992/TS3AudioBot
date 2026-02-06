package pub.longyi.ts3audiobot.ts3.full;

import Punisher.NaCl.Internal.Ed25519Ref10.GroupElementCached;
import Punisher.NaCl.Internal.Ed25519Ref10.GroupElementP1P1;
import Punisher.NaCl.Internal.Ed25519Ref10.GroupElementP3;
import Punisher.NaCl.Internal.Ed25519Ref10.GroupOperations;
import Punisher.NaCl.Internal.Ed25519Ref10.ScalarOperations;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 Licenses 相关功能。
 */


/**
 * Licenses 相关功能。
 *
 * <p>职责：负责 Licenses 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public final class Licenses {
    public static final byte[] LICENSE_ROOT_KEY = new byte[] {
        (byte) 0xcd, 0x0d, (byte) 0xe2, (byte) 0xae, (byte) 0xd4, 0x63, 0x45, 0x50,
        (byte) 0x9a, 0x7e, 0x3c, (byte) 0xfd, (byte) 0x8f, 0x68, (byte) 0xb3, (byte) 0xdc,
        0x75, 0x55, (byte) 0xb2, (byte) 0x9d, (byte) 0xcc, (byte) 0xec, 0x73, (byte) 0xcd,
        0x18, 0x75, 0x0f, (byte) 0x99, 0x38, 0x12, 0x40, (byte) 0x8a
    };

    private final List<LicenseBlock> blocks = new ArrayList<>();

    /**
     * 执行 parse 操作。
     * @param data 参数 data
     * @return 返回值
     */
    public static Licenses parse(byte[] data) {
        if (data == null || data.length < 1) {
            throw new IllegalArgumentException("License too short");
        }
        int version = data[0] & 0xFF;
        if (version != 1) {
            throw new IllegalArgumentException("Unsupported version");
        }
        Licenses licenses = new Licenses();
        int offset = 1;
        while (offset < data.length) {
            ParseResult result = LicenseBlock.parse(data, offset);
            licenses.blocks.add(result.block);
            offset += result.read;
        }
        return licenses;
    }


    /**
     * 执行 deriveKey 操作。
     * @return 返回值
     */
    public byte[] deriveKey() {
        byte[] round = Arrays.copyOf(LICENSE_ROOT_KEY, LICENSE_ROOT_KEY.length);
        for (LicenseBlock block : blocks) {
            round = block.deriveKey(round);
        }
        return round;
    }


    /**
     * 执行 blocks 操作。
     * @return 返回值
     */
    public List<LicenseBlock> blocks() {
        return blocks;
    }

    private record ParseResult(LicenseBlock block, int read) {
    }


    /**
     * LicenseBlock 相关功能。
     *
     * <p>职责：负责 LicenseBlock 相关功能。</p>
     * <p>线程安全：无显式保证。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    public abstract static class LicenseBlock {
        private static final int MIN_BLOCK_LEN = 42;

        /**
         * 执行 type 操作。
         * @return 返回值
         */
        public abstract ChainBlockType type();

        public Instant notValidBefore;
        public Instant notValidAfter;
        public byte[] key;
        public byte[] hash;

        /**
         * 执行 parse 操作。
         * @param data 参数 data
         * @param offset 参数 offset
         * @return 返回值
         */
        public static ParseResult parse(byte[] data, int offset) {
            if (data.length - offset < MIN_BLOCK_LEN) {
                throw new IllegalArgumentException("License too short");
            }
            if (data[offset] != 0) {
                throw new IllegalArgumentException("Wrong key kind " + data[offset] + " in license");
            }

            int type = data[offset + 33] & 0xFF;
            LicenseBlock block;
            int read;
            if (type == 0) {
                StringResult str = readNullString(data, offset + 46);
                block = new IntermediateLicenseBlock(str.value);
                read = 5 + str.read;
            } else if (type == 2) {
                int licenseType = data[offset + 42] & 0xFF;
                StringResult str = readNullString(data, offset + 47);
                block = new ServerLicenseBlock(str.value, ServerLicenseType.fromValue(licenseType));
                read = 6 + str.read;
            } else if (type == 32) {
                block = new EphemeralLicenseBlock();
                read = 0;
            } else {
                throw new IllegalArgumentException("Invalid license block type " + type);
            }

            long notBefore = readUInt32BE(data, offset + 34) + 0x50e22700L;
            long notAfter = readUInt32BE(data, offset + 38) + 0x50e22700L;
            if (notAfter < notBefore) {
                throw new IllegalArgumentException("License times are invalid");
            }
            block.notValidBefore = Instant.ofEpochSecond(notBefore);
            block.notValidAfter = Instant.ofEpochSecond(notAfter);

            block.key = Arrays.copyOfRange(data, offset + 1, offset + 33);

            int allLen = MIN_BLOCK_LEN + read;
            byte[] hashInput = Arrays.copyOfRange(data, offset + 1, offset + allLen);
            byte[] hash = TsCrypt.hash512(hashInput);
            block.hash = Arrays.copyOf(hash, 32);

            return new ParseResult(block, allLen);
        }


        /**
         * 执行 deriveKey 操作。
         * @param parent 参数 parent
         * @return 返回值
         */
        public byte[] deriveKey(byte[] parent) {
            byte[] scalar = Arrays.copyOf(hash, hash.length);
            ScalarOperations.sc_clamp(scalar, 0);

            GroupElementP3 pubkey = new GroupElementP3();
            GroupOperations.ge_frombytes_negate_vartime(pubkey, key, 0);

            GroupElementP3 parkey = new GroupElementP3();
            GroupOperations.ge_frombytes_negate_vartime(parkey, parent, 0);

            GroupElementP1P1 res = new GroupElementP1P1();
            GroupOperations.ge_scalarmult_vartime(res, scalar, pubkey);

            GroupElementCached pargrp = new GroupElementCached();
            GroupOperations.ge_p3_to_cached(pargrp, parkey);

            GroupElementP3 r = new GroupElementP3();
            GroupOperations.ge_p1p1_to_p3(r, res);

            GroupElementP1P1 sum = new GroupElementP1P1();
            GroupOperations.ge_add(sum, r, pargrp);

            GroupElementP3 r2 = new GroupElementP3();
            GroupOperations.ge_p1p1_to_p3(r2, sum);

            byte[] finalKey = new byte[32];
            GroupOperations.ge_p3_tobytes(finalKey, 0, r2);
            finalKey[31] ^= (byte) 0x80;
            return finalKey;
        }

        private static StringResult readNullString(byte[] data, int offset) {
            int idx = offset;
            while (idx < data.length && data[idx] != 0) {
                idx++;
            }
            if (idx >= data.length) {
                throw new IllegalArgumentException("Non-null-terminated issuer string");
            }
            int len = idx - offset;
            String value = new String(data, offset, len, StandardCharsets.UTF_8);
            return new StringResult(value, len);
        }

        private static long readUInt32BE(byte[] data, int offset) {
            int value = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            return Integer.toUnsignedLong(value);
        }
    }


    /**
     * IntermediateLicenseBlock 相关功能。
     *
     * <p>职责：负责 IntermediateLicenseBlock 相关功能。</p>
     * <p>线程安全：无显式保证。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    public static final class IntermediateLicenseBlock extends LicenseBlock {
        private final String issuer;

        /**
         * 创建 IntermediateLicenseBlock 实例。
         * @param issuer 参数 issuer
         */
        public IntermediateLicenseBlock(String issuer) {
            this.issuer = issuer;
        }


        /**
         * 执行 issuer 操作。
         * @return 返回值
         */
        public String issuer() {
            return issuer;
        }


        /**
         * 执行 type 操作。
         * @return 返回值
         */
        @Override
        public ChainBlockType type() {
            return ChainBlockType.INTERMEDIATE;
        }
    }


    /**
     * ServerLicenseBlock 相关功能。
     *
     * <p>职责：负责 ServerLicenseBlock 相关功能。</p>
     * <p>线程安全：无显式保证。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    public static final class ServerLicenseBlock extends LicenseBlock {
        private final String issuer;
        private final ServerLicenseType licenseType;

        /**
         * 创建 ServerLicenseBlock 实例。
         * @param issuer 参数 issuer
         * @param licenseType 参数 licenseType
         */
        public ServerLicenseBlock(String issuer, ServerLicenseType licenseType) {
            this.issuer = issuer;
            this.licenseType = licenseType;
        }


        /**
         * 执行 issuer 操作。
         * @return 返回值
         */
        public String issuer() {
            return issuer;
        }


        /**
         * 执行 licenseType 操作。
         * @return 返回值
         */
        public ServerLicenseType licenseType() {
            return licenseType;
        }


        /**
         * 执行 type 操作。
         * @return 返回值
         */
        @Override
        public ChainBlockType type() {
            return ChainBlockType.SERVER;
        }
    }


    /**
     * EphemeralLicenseBlock 相关功能。
     *
     * <p>职责：负责 EphemeralLicenseBlock 相关功能。</p>
     * <p>线程安全：无显式保证。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    public static final class EphemeralLicenseBlock extends LicenseBlock {
        /**
         * 执行 type 操作。
         * @return 返回值
         */
        @Override
        public ChainBlockType type() {
            return ChainBlockType.EPHEMERAL;
        }
    }


    /**
     * ChainBlockType 枚举相关功能。
     *
     * <p>职责：定义 ChainBlockType 枚举值。</p>
     * <p>线程安全：枚举常量天然线程安全。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    public enum ChainBlockType {
        INTERMEDIATE(0),
        WEBSITE(1),
        SERVER(2),
        CODE(3),
        EPHEMERAL(32);

        private final int value;

        ChainBlockType(int value) {
            this.value = value;
        }


        /**
         * 执行 value 操作。
         * @return 返回值
         */
        public int value() {
            return value;
        }
    }


    /**
     * ServerLicenseType 枚举相关功能。
     *
     * <p>职责：定义 ServerLicenseType 枚举值。</p>
     * <p>线程安全：枚举常量天然线程安全。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    public enum ServerLicenseType {
        NONE(0),
        OFFLINE(1),
        SDK(2),
        SDK_OFFLINE(3),
        NPL(4),
        ATHP(5),
        AAL(6),
        DEFAULT(7),
        GAMER(8),
        SPONSORSHIP(9),
        COMMERCIAL(10),
        UNKNOWN(255);

        private final int value;

        ServerLicenseType(int value) {
            this.value = value;
        }


        /**
         * 执行 value 操作。
         * @return 返回值
         */
        public int value() {
            return value;
        }


        /**
         * 执行 fromValue 操作。
         * @param value 参数 value
         * @return 返回值
         */
        public static ServerLicenseType fromValue(int value) {
            for (ServerLicenseType type : values()) {
                if (type.value == value) {
                    return type;
                }
            }
            return UNKNOWN;
        }
    }

    private record StringResult(String value, int read) {
    }
}
