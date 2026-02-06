package pub.longyi.ts3audiobot.ts3.util;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 QuickLZ 相关功能。
 */

// QuickLZ 数据压缩库
// 版权 (C) 2006-2011 Lasse Mikkel Reinhold
// 联系方式 lar@quicklz.com
//
// QuickLZ 可在 GPL 1/2/3 许可下免费使用（公开发布需开源），或在购买商业许可后使用。
// 商业许可购买地址：http://www.quicklz.com/order.html
// 商业许可不覆盖在 GPL 下由第三方创建的派生或移植版本。
//
// 仅移植了 C 库的部分：level 1 和 level 3 的非流式模式。
//
// 版本：1.5.0 final

import java.io.IOException;
import java.util.Base64;


/**
 * QuickLZ 相关功能。
 *
 * <p>职责：负责 QuickLZ 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public final class QuickLZ {
    private QuickLZ() {
    }

    public static final int QLZ_STREAMING_BUFFER = 0;
    public static final int QLZ_MEMORY_SAFE = 0;

    public static final int QLZ_VERSION_MAJOR = 1;
    public static final int QLZ_VERSION_MINOR = 5;
    public static final int QLZ_VERSION_REVISION = 0;

    private static final int HASH_VALUES = 4096;
    private static final int MINOFFSET = 2;
    private static final int UNCONDITIONAL_MATCHLEN = 6;
    private static final int UNCOMPRESSED_END = 4;
    private static final int CWORD_LEN = 4;
    private static final int DEFAULT_HEADERLEN = 9;
    private static final int QLZ_POINTERS_1 = 1;
    private static final int QLZ_POINTERS_3 = 16;

    /**
     * 执行 headerLen 操作。
     * @param source 参数 source
     * @return 返回值
     */
    public static int headerLen(byte[] source) {
        return ((source[0] & 2) == 2) ? 9 : 3;
    }


    /**
     * 执行 sizeDecompressed 操作。
     * @param source 参数 source
     * @return 返回值
     */
    public static long sizeDecompressed(byte[] source) {
        if (headerLen(source) == 9) {
            return fastRead(source, 5, 4);
        }
        return fastRead(source, 2, 1);
    }


    /**
     * 执行 sizeCompressed 操作。
     * @param source 参数 source
     * @return 返回值
     */
    public static long sizeCompressed(byte[] source) {
        if (headerLen(source) == 9) {
            return fastRead(source, 1, 4);
        }
        return fastRead(source, 1, 1);
    }

    private static void writeHeader(
        byte[] dst,
        int level,
        boolean compressible,
        int sizeCompressed,
        int sizeDecompressed
    ) {
        dst[0] = (byte) (2 | (compressible ? 1 : 0));
        dst[0] |= (byte) (level << 2);
        dst[0] |= (1 << 6);
        dst[0] |= (0 << 4);
        fastWrite(dst, 1, sizeDecompressed, 4);
        fastWrite(dst, 5, sizeCompressed, 4);
    }


    /**
     * 执行 compress 操作。
     * @param source 参数 source
     * @param level 参数 level
     * @return 返回值
     */
    public static byte[] compress(byte[] source, int level) {
        int src = 0;
        int dst = DEFAULT_HEADERLEN + CWORD_LEN;
        long cwordVal = 0x80000000L;
        int cwordPtr = DEFAULT_HEADERLEN;
        byte[] destination = new byte[source.length + 400];
        int[][] hashtable;
        int[] cachetable = new int[HASH_VALUES];
        byte[] hashCounter = new byte[HASH_VALUES];
        byte[] d2;
        int fetch = 0;
        int lastMatchstart = (source.length - UNCONDITIONAL_MATCHLEN - UNCOMPRESSED_END - 1);
        int lits = 0;

        if (level != 1 && level != 3) {
            throw new RuntimeException("Java version only supports level 1 and 3");
        }

        if (level == 1) {
            hashtable = new int[HASH_VALUES][QLZ_POINTERS_1];
        } else {
            hashtable = new int[HASH_VALUES][QLZ_POINTERS_3];
        }

        if (source.length == 0) {
            return new byte[0];
        }

        if (src <= lastMatchstart) {
            fetch = (int) fastRead(source, src, 3);
        }

        while (src <= lastMatchstart) {
            if ((cwordVal & 1) == 1) {
                if (src > 3 * (source.length >> 2) && dst > src - (src >> 5)) {
                    d2 = new byte[source.length + DEFAULT_HEADERLEN];
                    writeHeader(d2, level, false, source.length, source.length + DEFAULT_HEADERLEN);
                    System.arraycopy(source, 0, d2, DEFAULT_HEADERLEN, source.length);
                    return d2;
                }

                fastWrite(destination, cwordPtr, (cwordVal >>> 1) | 0x80000000L, 4);
                cwordPtr = dst;
                dst += CWORD_LEN;
                cwordVal = 0x80000000L;
            }

            if (level == 1) {
                int hash = ((fetch >>> 12) ^ fetch) & (HASH_VALUES - 1);
                int o = hashtable[hash][0];
                int cache = cachetable[hash] ^ fetch;

                cachetable[hash] = fetch;
                hashtable[hash][0] = src;

                if (cache == 0 && hashCounter[hash] != 0
                    && (src - o > MINOFFSET
                        || (src == o + 1 && lits >= 3 && src > 3 && source[src] == source[src - 3]
                            && source[src] == source[src - 2] && source[src] == source[src - 1]
                            && source[src] == source[src + 1] && source[src] == source[src + 2]))) {
                    cwordVal = ((cwordVal >>> 1) | 0x80000000L);
                    if (source[o + 3] != source[src + 3]) {
                        int f = 3 - 2 | (hash << 4);
                        destination[dst] = (byte) (f >>> 0 * 8);
                        destination[dst + 1] = (byte) (f >>> 1 * 8);
                        src += 3;
                        dst += 2;
                    } else {
                        int oldSrc = src;
                        int remaining =
                            ((source.length - UNCOMPRESSED_END - src + 1 - 1) > 255
                                ? 255
                                : (source.length - UNCOMPRESSED_END - src + 1 - 1));

                        src += 4;
                        if (source[o + src - oldSrc] == source[src]) {
                            src++;
                            if (source[o + src - oldSrc] == source[src]) {
                                src++;
                                while (source[o + (src - oldSrc)] == source[src] && (src - oldSrc) < remaining) {
                                    src++;
                                }
                            }
                        }

                        int matchlen = src - oldSrc;

                        hash <<= 4;
                        if (matchlen < 18) {
                            int f = hash | (matchlen - 2);
                            destination[dst] = (byte) (f >>> 0 * 8);
                            destination[dst + 1] = (byte) (f >>> 1 * 8);
                            dst += 2;
                        } else {
                            int f = hash | (matchlen << 16);
                            fastWrite(destination, dst, f, 3);
                            dst += 3;
                        }
                    }
                    lits = 0;
                    fetch = (int) fastRead(source, src, 3);
                } else {
                    lits++;
                    hashCounter[hash] = 1;
                    destination[dst] = source[src];
                    cwordVal = (cwordVal >>> 1);
                    src++;
                    dst++;
                    fetch = ((fetch >>> 8) & 0xffff) | ((((int) source[src + 2]) & 0xff) << 16);
                }
            } else {
                fetch = (int) fastRead(source, src, 3);

                int o;
                int offset2;
                int matchlen;
                int k;
                int m;
                int bestK = 0;
                byte c;
                int remaining =
                    ((source.length - UNCOMPRESSED_END - src + 1 - 1) > 255
                        ? 255
                        : (source.length - UNCOMPRESSED_END - src + 1 - 1));
                int hash = ((fetch >>> 12) ^ fetch) & (HASH_VALUES - 1);

                c = hashCounter[hash];
                matchlen = 0;
                offset2 = 0;
                for (k = 0; k < QLZ_POINTERS_3 && (c > k || c < 0); k++) {
                    o = hashtable[hash][k];
                    if ((byte) fetch == source[o] && (byte) (fetch >>> 8) == source[o + 1]
                        && (byte) (fetch >>> 16) == source[o + 2] && o < src - MINOFFSET) {
                        m = 3;
                        while (source[o + m] == source[src + m] && m < remaining) {
                            m++;
                        }
                        if ((m > matchlen) || (m == matchlen && o > offset2)) {
                            offset2 = o;
                            matchlen = m;
                            bestK = k;
                        }
                    }
                }
                o = offset2;
                hashtable[hash][c & (QLZ_POINTERS_3 - 1)] = src;
                c++;
                hashCounter[hash] = c;

                if (matchlen >= 3 && src - o < 131071) {
                    int offset = src - o;
                    for (int u = 1; u < matchlen; u++) {
                        fetch = (int) fastRead(source, src + u, 3);
                        hash = ((fetch >>> 12) ^ fetch) & (HASH_VALUES - 1);
                        c = hashCounter[hash]++;
                        hashtable[hash][c & (QLZ_POINTERS_3 - 1)] = src + u;
                    }

                    src += matchlen;
                    cwordVal = ((cwordVal >>> 1) | 0x80000000L);

                    if (matchlen == 3 && offset <= 63) {
                        fastWrite(destination, dst, offset << 2, 1);
                        dst++;
                    } else if (matchlen == 3 && offset <= 16383) {
                        fastWrite(destination, dst, (offset << 2) | 1, 2);
                        dst += 2;
                    } else if (matchlen <= 18 && offset <= 1023) {
                        fastWrite(destination, dst, ((matchlen - 3) << 2) | (offset << 6) | 2, 2);
                        dst += 2;
                    } else if (matchlen <= 33) {
                        fastWrite(destination, dst, ((matchlen - 2) << 2) | (offset << 7) | 3, 3);
                        dst += 3;
                    } else {
                        fastWrite(destination, dst, ((matchlen - 3) << 7) | (offset << 15) | 3, 4);
                        dst += 4;
                    }
                } else {
                    destination[dst] = source[src];
                    cwordVal = (cwordVal >>> 1);
                    src++;
                    dst++;
                }
            }
        }

        while (src <= source.length - 1) {
            if ((cwordVal & 1) == 1) {
                fastWrite(destination, cwordPtr, (long) ((cwordVal >>> 1) | 0x80000000L), 4);
                cwordPtr = dst;
                dst += CWORD_LEN;
                cwordVal = 0x80000000L;
            }

            destination[dst] = source[src];
            src++;
            dst++;
            cwordVal = (cwordVal >>> 1);
        }
        while ((cwordVal & 1) != 1) {
            cwordVal = (cwordVal >>> 1);
        }
        fastWrite(destination, cwordPtr, (long) ((cwordVal >>> 1) | 0x80000000L), CWORD_LEN);
        writeHeader(destination, level, true, source.length, dst);

        d2 = new byte[dst];
        System.arraycopy(destination, 0, d2, 0, dst);
        return d2;
    }

    private static long fastRead(byte[] a, int i, int numbytes) {
        long l = 0;
        for (int j = 0; j < numbytes; j++) {
            l |= ((((int) a[i + j]) & 0xffL) << j * 8);
        }
        return l;
    }

    private static void fastWrite(byte[] a, int i, long value, int numbytes) {
        for (int j = 0; j < numbytes; j++) {
            a[i + j] = (byte) (value >>> (j * 8));
        }
    }


    /**
     * 执行 decompress 操作。
     * @param source 参数 source
     * @param maximum 参数 maximum
     * @return 返回值
     * @throws IOException 异常说明
     */
    public static byte[] decompress(byte[] source, int maximum) throws IOException {
        try {
            int level = (source[0] >>> 2) & 0x3;

            if (level != 1 && level != 3) {
                throw new IllegalArgumentException("unsupported QuickLZ level: " + level);
            }

            int size = (int) sizeDecompressed(source);
            if (size > maximum) {
                throw new IllegalArgumentException("decompression too large: " + size);
            }

            int src = headerLen(source);
            int dst = 0;
            long cwordVal = 1;
            byte[] destination = new byte[size];
            int[] hashtable = new int[HASH_VALUES];
            byte[] hashCounter = new byte[HASH_VALUES];
            int lastMatchstart = size - UNCONDITIONAL_MATCHLEN - UNCOMPRESSED_END - 1;
            int lastHashed = -1;
            int hash;
            int fetch = 0;

            if ((source[0] & 1) != 1) {
                byte[] d2 = new byte[size];
                System.arraycopy(source, headerLen(source), d2, 0, size);
                return d2;
            }

            for (; ; ) {
                if (cwordVal == 1) {
                    cwordVal = fastRead(source, src, 4);
                    src += 4;
                    if (dst <= lastMatchstart) {
                        if (level == 1) {
                            fetch = (int) fastRead(source, src, 3);
                        } else {
                            fetch = (int) fastRead(source, src, 4);
                        }
                    }
                }

                if ((cwordVal & 1) == 1) {
                    int matchlen;
                    int offset2;

                    cwordVal = cwordVal >>> 1;

                    if (level == 1) {
                        hash = (fetch >>> 4) & 0xfff;
                        offset2 = hashtable[hash];

                        if ((fetch & 0xf) != 0) {
                            matchlen = (fetch & 0xf) + 2;
                            src += 2;
                        } else {
                            matchlen = ((int) source[src + 2]) & 0xff;
                            src += 3;
                        }
                    } else {
                        int offset;

                        if ((fetch & 3) == 0) {
                            offset = (fetch & 0xff) >>> 2;
                            matchlen = 3;
                            src++;
                        } else if ((fetch & 2) == 0) {
                            offset = (fetch & 0xffff) >>> 2;
                            matchlen = 3;
                            src += 2;
                        } else if ((fetch & 1) == 0) {
                            offset = (fetch & 0xffff) >>> 6;
                            matchlen = ((fetch >>> 2) & 15) + 3;
                            src += 2;
                        } else if ((fetch & 127) != 3) {
                            offset = (fetch >>> 7) & 0x1ffff;
                            matchlen = ((fetch >>> 2) & 0x1f) + 2;
                            src += 3;
                        } else {
                            offset = (fetch >>> 15);
                            matchlen = ((fetch >>> 7) & 255) + 3;
                            src += 4;
                        }
                        offset2 = (int) (dst - offset);
                    }

                    destination[dst] = destination[offset2];
                    destination[dst + 1] = destination[offset2 + 1];
                    destination[dst + 2] = destination[offset2 + 2];

                    for (int i = 3; i < matchlen; i += 1) {
                        destination[dst + i] = destination[offset2 + i];
                    }
                    dst += matchlen;

                    if (level == 1) {
                        fetch = (int) fastRead(destination, lastHashed + 1, 3);
                        while (lastHashed < dst - matchlen) {
                            lastHashed++;
                            hash = ((fetch >>> 12) ^ fetch) & (HASH_VALUES - 1);
                            hashtable[hash] = lastHashed;
                            hashCounter[hash] = 1;
                            fetch = fetch >>> 8 & 0xffff | (((int) destination[lastHashed + 3]) & 0xff) << 16;
                        }
                        fetch = (int) fastRead(source, src, 3);
                    } else {
                        fetch = (int) fastRead(source, src, 4);
                    }
                    lastHashed = dst - 1;
                } else {
                    if (dst <= lastMatchstart) {
                        destination[dst] = source[src];
                        dst += 1;
                        src += 1;
                        cwordVal = cwordVal >>> 1;

                        if (level == 1) {
                            while (lastHashed < dst - 3) {
                                lastHashed++;
                                int fetch2 = (int) fastRead(destination, lastHashed, 3);
                                hash = ((fetch2 >>> 12) ^ fetch2) & (HASH_VALUES - 1);
                                hashtable[hash] = lastHashed;
                                hashCounter[hash] = 1;
                            }
                            fetch = fetch >> 8 & 0xffff | (((int) source[src + 2]) & 0xff) << 16;
                        } else {
                            fetch = fetch >> 8 & 0xffff | (((int) source[src + 2]) & 0xff) << 16
                                | (((int) source[src + 3]) & 0xff) << 24;
                        }
                    } else {
                        while (dst <= size - 1) {
                            if (cwordVal == 1) {
                                src += CWORD_LEN;
                                cwordVal = 0x80000000L;
                            }

                            destination[dst] = source[src];
                            dst++;
                            src++;
                            cwordVal = cwordVal >>> 1;
                        }
                        return destination;
                    }
                }
            }
        } catch (Throwable ex) {
            throw new IOException(ex.getMessage() + " (" + Base64.getEncoder().encodeToString(source) + ")", ex);
        }
    }
}
