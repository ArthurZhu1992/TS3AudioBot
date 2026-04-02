package pub.longyi.ts3audiobot.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class RuntimeToolPathResolver {
    private RuntimeToolPathResolver() {
    }

    public static String resolveYtDlpCommand(String configuredCommand) {
        String raw = safe(configuredCommand);
        if (raw.isBlank()) {
            raw = "yt-dlp";
        }
        Path explicit = explicitPath(raw);
        if (explicit != null) {
            if (Files.isRegularFile(explicit)) {
                return explicit.toString();
            }
            Path fallback = findBundledYtDlp();
            if (fallback != null) {
                return fallback.toString();
            }
            return explicit.toString();
        }
        if (isAutoYtDlp(raw)) {
            Path fallback = findBundledYtDlp();
            if (fallback != null) {
                return fallback.toString();
            }
        }
        return raw;
    }

    public static String resolveFfmpegCommand(String configuredCommand) {
        String raw = safe(configuredCommand);
        if (raw.isBlank()) {
            raw = "ffmpeg";
        }
        Path explicit = explicitPath(raw);
        if (explicit != null) {
            if (Files.isRegularFile(explicit)) {
                return explicit.toString();
            }
            Path fallback = findBundledFfmpeg();
            if (fallback != null) {
                return fallback.toString();
            }
            return explicit.toString();
        }
        if (isAutoFfmpeg(raw)) {
            Path fallback = findBundledFfmpeg();
            if (fallback != null) {
                return fallback.toString();
            }
        }
        return raw;
    }

    public static Path defaultYtDlpTarget() {
        String exe = isWindows() ? "yt-dlp.exe" : "yt-dlp";
        return baseDir().resolve("yt-dlp").resolve(exe);
    }

    public static Path findBundledYtDlp() {
        Path base = baseDir();
        String exe = isWindows() ? "yt-dlp.exe" : "yt-dlp";
        List<Path> candidates = new ArrayList<>();
        candidates.add(base.resolve(exe));
        candidates.add(base.resolve("yt-dlp").resolve(exe));
        if (!isWindows()) {
            candidates.add(base.resolve("yt-dlp_linux"));
            candidates.add(base.resolve("yt-dlp").resolve("yt-dlp_linux"));
            candidates.add(base.resolve("yt-dlp_linux_aarch64"));
            candidates.add(base.resolve("yt-dlp").resolve("yt-dlp_linux_aarch64"));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public static Path findBundledFfmpeg() {
        Path root = baseDir().resolve("ffmpeg");
        if (!Files.isDirectory(root)) {
            return null;
        }
        String exe = isWindows() ? "ffmpeg.exe" : "ffmpeg";
        List<Path> candidates = List.of(
            root.resolve(exe),
            root.resolve("bin").resolve(exe)
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        try (Stream<Path> stream = Files.walk(root, 6)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equalsIgnoreCase(exe))
                .findFirst()
                .orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }

    public static Path baseDir() {
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    public static Path explicitPath(String raw) {
        String value = safe(raw);
        if (value.isBlank()) {
            return null;
        }
        if (!looksLikePath(value)) {
            return null;
        }
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) {
                path = baseDir().resolve(path).normalize();
            }
            return path;
        } catch (Exception ex) {
            return null;
        }
    }

    public static boolean looksLikePath(String raw) {
        String value = safe(raw);
        if (value.contains("\\") || value.contains("/")) {
            return true;
        }
        if (value.matches("^[A-Za-z]:.*")) {
            return true;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.endsWith(".exe") || lower.endsWith(".bin");
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private static boolean isAutoYtDlp(String value) {
        String lower = safe(value).toLowerCase(Locale.ROOT);
        return "yt-dlp".equals(lower) || "auto".equals(lower);
    }

    private static boolean isAutoFfmpeg(String value) {
        String lower = safe(value).toLowerCase(Locale.ROOT);
        return "ffmpeg".equals(lower) || "auto".equals(lower);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
