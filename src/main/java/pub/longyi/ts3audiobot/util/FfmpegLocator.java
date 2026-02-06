package pub.longyi.ts3audiobot.util;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 FfmpegLocator 相关功能。
 */


/**
 * FfmpegLocator 相关功能。
 *
 * <p>职责：负责 FfmpegLocator 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Slf4j
public final class FfmpegLocator {
    private static final int TAR_BLOCK = 512;

    private FfmpegLocator() {
    }


    /**
     * 执行 resolve 操作。
     * @param configuredPath 参数 configuredPath
     * @return 返回值
     */
    public static String resolve(String configuredPath) {
        String trimmed = configuredPath == null ? "" : configuredPath.trim();
        if (!trimmed.isEmpty() && !isAuto(trimmed)) {
            return trimmed;
        }

        Path baseDir = resolveBaseDir();
        Path ffmpegDir = baseDir.resolve("ffmpeg");
        Path found = findExistingBinary(ffmpegDir);
        if (found != null) {
            return found.toString();
        }

        String os = detectOs();
        String arch = detectArch();
        if (os == null || arch == null) {
            throw new IllegalStateException("Unsupported platform for bundled ffmpeg: os="
                + System.getProperty("os.name") + " arch=" + System.getProperty("os.arch"));
        }

        Path archive = findArchive(ffmpegDir, os, arch);
        if (archive == null) {
            throw new IllegalStateException("Bundled ffmpeg archive not found in " + ffmpegDir);
        }

        Path extractedDir = ffmpegDir.resolve("extracted").resolve(os + "-" + arch);
        if (!Files.exists(extractedDir)) {
            try {
                Files.createDirectories(extractedDir);
            } catch (IOException ex) {
                log.warn("[FFmpeg] failed to create {}", extractedDir, ex);
                return "ffmpeg";
            }
        }

        try {
            extractArchive(archive, extractedDir);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to extract bundled ffmpeg " + archive, ex);
        }

        found = findExistingBinary(extractedDir);
        if (found != null) {
            return found.toString();
        }

        throw new IllegalStateException("Bundled ffmpeg extracted but binary not found under " + extractedDir);
    }

    private static boolean isAuto(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        return "ffmpeg".equals(v) || "auto".equals(v);
    }

    private static Path resolveBaseDir() {
        Path workDir = Paths.get(System.getProperty("user.dir"));
        if (Files.isDirectory(workDir.resolve("ffmpeg"))) {
            return workDir;
        }
        try {
            Path codeSource = Paths.get(FfmpegLocator.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            Path base = Files.isRegularFile(codeSource) ? codeSource.getParent() : codeSource;
            if (base != null && Files.isDirectory(base.resolve("ffmpeg"))) {
                return base;
            }
        } catch (URISyntaxException ex) {
            return workDir;
        }
        return workDir;
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

    private static Path findExistingBinary(Path root) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        String exeName = isWindows() ? "ffmpeg.exe" : "ffmpeg";
        try (Stream<Path> stream = Files.walk(root, 8)) {
            Optional<Path> found = stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equalsIgnoreCase(exeName))
                .findFirst();
            return found.orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private static Path findArchive(Path ffmpegDir, String os, String arch) {
        if (!Files.isDirectory(ffmpegDir)) {
            return null;
        }
        String targetExt = os.equals("windows") ? ".zip" : ".tar.xz";
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(ffmpegDir)) {
            Path best = null;
            for (Path path : stream) {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(targetExt)) {
                    continue;
                }
                if (!name.contains(os == null ? "" : os.substring(0, 3))) {
                    if (os.equals("windows") && !name.contains("win")) {
                        continue;
                    }
                    if (os.equals("linux") && !name.contains("linux")) {
                        continue;
                    }
                }
                if ("arm64".equals(arch)) {
                    if (!name.contains("arm64") && !name.contains("aarch64")) {
                        continue;
                    }
                } else if ("amd64".equals(arch)) {
                    if (!name.contains("64") || name.contains("arm")) {
                        continue;
                    }
                }
                best = path;
                break;
            }
            return best;
        } catch (IOException ex) {
            return null;
        }
    }

    private static void extractArchive(Path archive, Path outputDir) throws IOException {
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        log.info("[FFmpeg] extracting {} -> {}", archive, outputDir);
        if (name.endsWith(".zip")) {
            extractZip(archive, outputDir);
        } else if (name.endsWith(".tar.xz")) {
            extractTarXz(archive, outputDir);
        } else {
            throw new IOException("Unsupported archive: " + archive);
        }
    }

    private static void extractZip(Path archive, Path outputDir) throws IOException {
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
            new BufferedInputStream(Files.newInputStream(archive)))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zin.getNextEntry()) != null) {
                Path out = outputDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(outputDir)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                try (var outStream = Files.newOutputStream(out)) {
                    int read;
                    while ((read = zin.read(buffer)) > 0) {
                        outStream.write(buffer, 0, read);
                    }
                }
            }
        }
        setExecutableIfNeeded(outputDir);
    }

    private static void extractTarXz(Path archive, Path outputDir) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(archive));
             org.tukaani.xz.XZInputStream xzIn = new org.tukaani.xz.XZInputStream(in)) {
            byte[] header = new byte[TAR_BLOCK];
            String longName = null;
            while (true) {
                int read = readFully(xzIn, header, 0, TAR_BLOCK);
                if (read <= 0 || isAllZero(header)) {
                    break;
                }
                String name = parseName(header);
                String prefix = parsePrefix(header);
                char typeFlag = (char) header[156];
                long size = parseOctal(header, 124, 12);
                if (typeFlag == 'L') {
                    byte[] nameBytes = readBytes(xzIn, size);
                    longName = new String(nameBytes, 0, nameBytes.length, StandardCharsets.UTF_8).trim();
                    skipPadding(xzIn, size);
                    continue;
                }
                String entryName = longName != null ? longName : name;
                longName = null;
                if (!prefix.isEmpty()) {
                    entryName = prefix + "/" + entryName;
                }
                Path out = outputDir.resolve(entryName).normalize();
                if (!out.startsWith(outputDir)) {
                    skipBytes(xzIn, size);
                    skipPadding(xzIn, size);
                    continue;
                }
                if (typeFlag == '5') {
                    Files.createDirectories(out);
                    continue;
                }
                if (typeFlag == 0 || typeFlag == '0') {
                    Files.createDirectories(out.getParent());
                    try (var outStream = Files.newOutputStream(out,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        copyExact(xzIn, outStream, size);
                    }
                } else {
                    skipBytes(xzIn, size);
                }
                skipPadding(xzIn, size);
            }
        }
        setExecutableIfNeeded(outputDir);
    }

    private static void setExecutableIfNeeded(Path root) {
        if (isWindows()) {
            return;
        }
        Path bin = findExistingBinary(root);
        if (bin != null) {
            try {
                if (Files.getFileAttributeView(bin, PosixFileAttributeView.class) != null) {
                    Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-xr-x");
                    Files.setPosixFilePermissions(bin, perms);
                } else {
                    bin.toFile().setExecutable(true, false);
                }
            } catch (IOException ex) {
                log.warn("[FFmpeg] failed to set executable on {}", bin, ex);
            }
            if (!Files.isExecutable(bin)) {
                throw new IllegalStateException("Bundled ffmpeg is not executable: " + bin);
            }
        }
    }

    private static boolean isAllZero(byte[] buffer) {
        for (byte b : buffer) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String parseName(byte[] header) {
        return readString(header, 0, 100);
    }

    private static String parsePrefix(byte[] header) {
        return readString(header, 345, 155);
    }

    private static String readString(byte[] data, int offset, int length) {
        int end = offset;
        int max = offset + length;
        while (end < max && data[end] != 0) {
            end++;
        }
        return new String(data, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long parseOctal(byte[] data, int offset, int length) {
        long result = 0;
        int end = offset + length;
        int i = offset;
        while (i < end && (data[i] == 0 || data[i] == ' ')) {
            i++;
        }
        for (; i < end; i++) {
            byte b = data[i];
            if (b < '0' || b > '7') {
                break;
            }
            result = (result << 3) + (b - '0');
        }
        return result;
    }

    private static int readFully(InputStream in, byte[] buffer, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int read = in.read(buffer, offset + total, length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static byte[] readBytes(InputStream in, long size) throws IOException {
        if (size <= 0) {
            return new byte[0];
        }
        byte[] data = new byte[(int) size];
        readFully(in, data, 0, data.length);
        return data;
    }

    private static void copyExact(InputStream in, java.io.OutputStream out, long size) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = size;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("Unexpected end of stream");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skipBytes(InputStream in, long size) throws IOException {
        long remaining = size;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    break;
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long padding = (TAR_BLOCK - (size % TAR_BLOCK)) % TAR_BLOCK;
        if (padding > 0) {
            skipBytes(in, padding);
        }
    }
}
