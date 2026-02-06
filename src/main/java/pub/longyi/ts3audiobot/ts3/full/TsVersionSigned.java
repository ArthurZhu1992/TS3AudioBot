package pub.longyi.ts3audiobot.ts3.full;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 TsVersionSigned 相关功能。
 */


/**
 * TsVersionSigned 相关功能。
 *
 * <p>职责：负责 TsVersionSigned 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public class TsVersionSigned {
    private static final Pattern BUILD_PATTERN = Pattern.compile("Build:\\s*(\\d+)");
    private final String rawVersion;
    private final String platform;
    private final ClientPlatform platformType;
    private final long build;
    private final String sign;

    /**
     * 创建 TsVersionSigned 实例。
     * @param rawVersion 参数 rawVersion
     * @param platform 参数 platform
     * @param platformType 参数 platformType
     * @param build 参数 build
     * @param sign 参数 sign
     */
    public TsVersionSigned(String rawVersion, String platform, ClientPlatform platformType, long build, String sign) {
        this.rawVersion = rawVersion;
        this.platform = platform;
        this.platformType = platformType;
        this.build = build;
        this.sign = sign;
    }


    /**
     * 执行 rawVersion 操作。
     * @return 返回值
     */
    public String rawVersion() {
        return rawVersion;
    }


    /**
     * 执行 platform 操作。
     * @return 返回值
     */
    public String platform() {
        return platform;
    }


    /**
     * 执行 platformType 操作。
     * @return 返回值
     */
    public ClientPlatform platformType() {
        return platformType;
    }


    /**
     * 执行 build 操作。
     * @return 返回值
     */
    public long build() {
        return build;
    }


    /**
     * 执行 sign 操作。
     * @return 返回值
     */
    public String sign() {
        return sign;
    }


    /**
     * 执行 defaultForOs 操作。
     * @return 返回值
     */
    public static TsVersionSigned defaultForOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return VER_WIN_3_5_3;
        }
        if (os.contains("mac")) {
            return VER_MAC_3_5_3;
        }
        return VER_LIN_3_5_3;
    }


    /**
     * 执行 fromConfig 操作。
     * @param rawVersion 参数 rawVersion
     * @param platform 参数 platform
     * @param sign 参数 sign
     * @return 返回值
     */
    public static TsVersionSigned fromConfig(String rawVersion, String platform, String sign) {
        TsVersionSigned base = defaultForOs();
        String resolvedVersion = (rawVersion == null || rawVersion.isBlank()) ? base.rawVersion : rawVersion;
        String resolvedPlatform = (platform == null || platform.isBlank()) ? base.platform : platform;
        ClientPlatform platformType = platformTypeFromString(resolvedPlatform);
        long build = parseBuild(resolvedVersion, base.build);
        return new TsVersionSigned(resolvedVersion, resolvedPlatform, platformType, build, sign);
    }

    private static ClientPlatform platformTypeFromString(String platform) {
        if (platform == null) {
            return ClientPlatform.WINDOWS;
        }
        String value = platform.toLowerCase(Locale.ROOT);
        if (value.contains("win")) {
            return ClientPlatform.WINDOWS;
        }
        if (value.contains("mac") || value.contains("os x") || value.contains("osx")) {
            return ClientPlatform.MACOS;
        }
        if (value.contains("linux")) {
            return ClientPlatform.LINUX;
        }
        if (value.contains("android")) {
            return ClientPlatform.ANDROID;
        }
        if (value.contains("ios")) {
            return ClientPlatform.IOS;
        }
        return ClientPlatform.WINDOWS;
    }

    private static long parseBuild(String rawVersion, long fallback) {
        if (rawVersion == null) {
            return fallback;
        }
        Matcher matcher = BUILD_PATTERN.matcher(rawVersion);
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static final TsVersionSigned VER_WIN_3_5_3 = new TsVersionSigned(
        "3.5.3 [Build: 1587971024]",
        "Windows",
        ClientPlatform.WINDOWS,
        1587971024L,
        "Kvmj7qX6wJCPI5GVT71samfmhz/bvs7M+OTXWB/JWxdQbxDe17xda7dzUWLX7pjvdJTqZmbse1HBmTxThPKvAg=="
    );

    public static final TsVersionSigned VER_LIN_3_5_3 = new TsVersionSigned(
        "3.5.3 [Build: 1587971024]",
        "Linux",
        ClientPlatform.LINUX,
        1587971024L,
        "59chu1YQ1W4DdVj+yJOVmwhJK7s9p9FatqIjQRxEDmIb0CcDDO/K8CrbVnBHbD67/cExJbC3PjC/o/n0pDbiCg=="
    );

    public static final TsVersionSigned VER_MAC_3_5_3 = new TsVersionSigned(
        "3.5.3 [Build: 1586955962]",
        "OS X",
        ClientPlatform.MACOS,
        1586955962L,
        "/9gsbyyJoyHW1okwykGgXrn2j29wvnEfnw6/Hvft2WS12CURxTv5L9uLuu36I5u3TzWOxyB9dEbD1FeZ201uCQ=="
    );
}
