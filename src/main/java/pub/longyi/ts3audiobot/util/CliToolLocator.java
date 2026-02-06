package pub.longyi.ts3audiobot.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Set;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 CliToolLocator 相关功能。
 */


/**
 * CliToolLocator 相关功能。
 *
 * <p>职责：负责 CliToolLocator 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Slf4j
public final class CliToolLocator {
    private CliToolLocator() {
    }


    /**
     * 执行 resolveYtDlp 操作。
     * @param configured 参数 configured
     * @return 返回值
     */
    public static String resolveYtDlp(String configured) {
        String raw = configured == null ? "" : configured.trim();
        if (raw.isEmpty()) {
            raw = "yt-dlp";
        }
        if (!isYtDlpCommand(raw)) {
            return raw;
        }

        Path baseDir = resolveBaseDir();
        if (isAuto(raw)) {
            Path bundled = findBundled(baseDir);
            if (bundled != null) {
                ensureExecutable(bundled);
                return bundled.toString();
            }
            log.warn("[yt-dlp] bundled binary not found under {}", baseDir);
            return raw;
        }

        Path candidate = Path.of(raw);
        if (!candidate.isAbsolute()) {
            candidate = baseDir.resolve(candidate).normalize();
        }
        if (Files.isRegularFile(candidate)) {
            ensureExecutable(candidate);
            return candidate.toString();
        }
        log.warn("[yt-dlp] configured binary not found at {}", candidate);
        return raw;
    }

    private static boolean isYtDlpCommand(String value) {
        if (value == null) {
            return false;
        }
        String v = value.toLowerCase(Locale.ROOT);
        return v.contains("yt-dlp") || v.contains("youtube-dl") || isAuto(v);
    }

    private static boolean isAuto(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        return "yt-dlp".equals(v) || "auto".equals(v);
    }

    private static Path resolveBaseDir() {
        Path workDir = Paths.get(System.getProperty("user.dir"));
        if (hasBundled(workDir)) {
            return workDir;
        }
        try {
            Path codeSource = Paths.get(CliToolLocator.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            Path base = Files.isRegularFile(codeSource) ? codeSource.getParent() : codeSource;
            if (base != null && hasBundled(base)) {
                return base;
            }
        } catch (URISyntaxException ex) {
            return workDir;
        }
        return workDir;
    }

    private static boolean hasBundled(Path baseDir) {
        return findBundled(baseDir) != null;
    }

    private static Path findBundled(Path baseDir) {
        if (baseDir == null) {
            return null;
        }
        String os = detectOs();
        String arch = detectArch();
        if ("windows".equals(os)) {
            Path[] candidates = new Path[] {
                baseDir.resolve("yt-dlp.exe"),
                baseDir.resolve("yt-dlp"),
                baseDir.resolve("yt-dlp").resolve("yt-dlp.exe"),
                baseDir.resolve("yt-dlp").resolve("yt-dlp")
            };
            for (Path candidate : candidates) {
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            return null;
        }
        if ("linux".equals(os)) {
            boolean arm64 = "arm64".equals(arch);
            Path[] candidates = arm64
                ? new Path[] {
                    baseDir.resolve("yt-dlp_linux_aarch64"),
                    baseDir.resolve("yt-dlp").resolve("yt-dlp_linux_aarch64"),
                    baseDir.resolve("yt-dlp_linux"),
                    baseDir.resolve("yt-dlp").resolve("yt-dlp_linux"),
                    baseDir.resolve("yt-dlp"),
                    baseDir.resolve("yt-dlp").resolve("yt-dlp")
                }
                : new Path[] {
                    baseDir.resolve("yt-dlp_linux"),
                    baseDir.resolve("yt-dlp").resolve("yt-dlp_linux"),
                    baseDir.resolve("yt-dlp"),
                    baseDir.resolve("yt-dlp").resolve("yt-dlp")
                };
            for (Path candidate : candidates) {
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            return null;
        }
        Path fallback = baseDir.resolve("yt-dlp");
        if (Files.isRegularFile(fallback)) {
            return fallback;
        }
        return null;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private static String detectOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        return null;
    }

    private static String detectArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        if (arch.contains("x86_64") || arch.contains("amd64")) {
            return "amd64";
        }
        return null;
    }

    private static void ensureExecutable(Path path) {
        if (path == null || isWindows()) {
            return;
        }
        try {
            if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
                Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
                Files.setPosixFilePermissions(path, perms);
            } else {
                path.toFile().setExecutable(true, false);
            }
        } catch (IOException ex) {
            log.warn("[yt-dlp] failed to set executable on {}", path, ex);
        }
        if (!Files.isExecutable(path)) {
            log.warn("[yt-dlp] binary is not executable: {}", path);
        }
    }
}
