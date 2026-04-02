package pub.longyi.ts3audiobot.system;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.util.RuntimeToolPathResolver;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.net.ssl.SSLHandshakeException;

@Slf4j
@Component
public final class DependencyToolService {
    private static final String TOOL_FFMPEG = "ffmpeg";
    private static final String TOOL_YTDLP = "yt-dlp";
    private static final int TAR_BLOCK = 512;
    private static final Duration PROBE_CACHE_TTL = Duration.ofSeconds(4);

    private final ConfigService configService;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private final ExecutorService downloadPool = Executors.newFixedThreadPool(2);
    private final Map<String, DownloadState> states = new ConcurrentHashMap<>();
    private final Map<String, ProbeCache> probeCache = new ConcurrentHashMap<>();

    public DependencyToolService(ConfigService configService) {
        this.configService = configService;
    }

    public DependencySnapshot snapshot() {
        List<DependencyStatusItem> items = new ArrayList<>();
        items.add(buildYtDlpStatus());
        items.add(buildFfmpegStatus());
        return new DependencySnapshot(items, Instant.now().toString());
    }

    public DownloadRequestResult startDownload(String toolId) {
        String safeId = normalizeToolId(toolId);
        if (!TOOL_FFMPEG.equals(safeId) && !TOOL_YTDLP.equals(safeId)) {
            throw new IllegalArgumentException("不支持的依赖: " + toolId);
        }
        synchronized (states) {
            DownloadState running = states.get(safeId);
            if (running != null && running.running) {
                return new DownloadRequestResult(false, "正在下载中");
            }
            DownloadState state = new DownloadState(safeId);
            states.put(safeId, state);
            downloadPool.submit(() -> runDownload(state));
        }
        return new DownloadRequestResult(true, "下载任务已开始");
    }

    @PreDestroy
    public void shutdown() {
        downloadPool.shutdownNow();
    }

    private DependencyStatusItem buildYtDlpStatus() {
        String ytCommand = safe(configService.get().resolvers.external.yt);
        String ytmCommand = safe(configService.get().resolvers.external.ytmusic);
        String ytResolved = RuntimeToolPathResolver.resolveYtDlpCommand(ytCommand);
        String ytmResolved = RuntimeToolPathResolver.resolveYtDlpCommand(ytmCommand);
        Path targetPath = resolveYtDlpTargetPath();
        DownloadState state = states.get(TOOL_YTDLP);
        boolean downloading = state != null && state.running;
        boolean downloadSupported = ytdlpDownloadUrl() != null;
        String downloadUrl = ytdlpDownloadUrl();
        boolean ytAvailable = downloading ? false : isCommandAvailableCached("yt|" + ytResolved, ytResolved, "--version");
        boolean ytmAvailable = downloading ? false : isCommandAvailableCached("ytm|" + ytmResolved, ytmResolved, "--version");
        boolean filePresent = isFilePresent(targetPath);
        boolean installed = filePresent || (ytAvailable && ytmAvailable);
        if (installed && state != null && !state.running) {
            states.remove(TOOL_YTDLP);
        }
        String message = buildToolMessage(installed, state, "已检测到 yt-dlp");
        return new DependencyStatusItem(
            TOOL_YTDLP,
            "yt-dlp",
            installed,
            downloading,
            percent(state),
            state == null ? 0 : state.downloadedBytes,
            state == null ? -1 : state.totalBytes,
            speed(state),
            etaSeconds(state),
            message,
            downloadSupported,
            "yt=" + ytCommand + ", ytmusic=" + ytmCommand,
            targetPath == null ? "" : targetPath.toString(),
            downloadUrl == null ? "" : downloadUrl
        );
    }

    private DependencyStatusItem buildFfmpegStatus() {
        String command = safe(configService.get().tools.ffmpegPath);
        String resolved = RuntimeToolPathResolver.resolveFfmpegCommand(command);
        Path targetPath = resolveFfmpegTargetPath();
        DownloadState state = states.get(TOOL_FFMPEG);
        boolean downloading = state != null && state.running;
        boolean runnable = downloading ? false : isCommandAvailableCached("ffmpeg|" + resolved, resolved, "-version");
        boolean filePresent = isFilePresent(targetPath);
        boolean installed = filePresent || runnable;
        String downloadUrl = ffmpegDownloadUrl();
        if (installed && state != null && !state.running) {
            states.remove(TOOL_FFMPEG);
        }
        String message = buildToolMessage(installed, state, "已检测到 ffmpeg");
        return new DependencyStatusItem(
            TOOL_FFMPEG,
            "ffmpeg",
            installed,
            downloading,
            percent(state),
            state == null ? 0 : state.downloadedBytes,
            state == null ? -1 : state.totalBytes,
            speed(state),
            etaSeconds(state),
            message,
            ffmpegDownloadUrl() != null,
            command,
            targetPath == null ? "" : targetPath.toString(),
            downloadUrl == null ? "" : downloadUrl
        );
    }

    private String buildToolMessage(boolean installed, DownloadState state, String okMessage) {
        if (installed) {
            return okMessage;
        }
        if (state == null) {
            return "未检测到可用依赖";
        }
        if (state.running) {
            if ("extracting".equals(state.phase)) {
                return "下载完成，正在解压...";
            }
            if ("downloading_fallback".equals(state.phase)) {
                return "JVM 证书校验失败，已切换系统下载器...";
            }
            return "正在下载...";
        }
        if (state.errorMessage != null && !state.errorMessage.isBlank()) {
            return state.errorMessage;
        }
        if (state.success) {
            return "已下载完成，正在等待可执行检测";
        }
        return "未检测到可用依赖";
    }

    private Integer percent(DownloadState state) {
        if (state == null || state.totalBytes <= 0 || state.downloadedBytes <= 0) {
            return null;
        }
        long value = (state.downloadedBytes * 100L) / state.totalBytes;
        return (int) Math.max(0, Math.min(100, value));
    }

    private long speed(DownloadState state) {
        if (state == null || state.startedAt <= 0 || state.downloadedBytes <= 0) {
            return 0;
        }
        long elapsedMs = Math.max(1L, System.currentTimeMillis() - state.startedAt);
        return Math.max(0L, state.downloadedBytes * 1000L / elapsedMs);
    }

    private Long etaSeconds(DownloadState state) {
        if (state == null || state.totalBytes <= 0 || state.downloadedBytes <= 0) {
            return null;
        }
        long speed = speed(state);
        if (speed <= 0) {
            return null;
        }
        long remain = Math.max(0L, state.totalBytes - state.downloadedBytes);
        return remain / speed;
    }

    private void runDownload(DownloadState state) {
        try {
            state.running = true;
            state.errorMessage = "";
            state.success = false;
            state.phase = "downloading";
            state.startedAt = System.currentTimeMillis();
            state.downloadedBytes = 0;
            state.totalBytes = -1;
            if (TOOL_YTDLP.equals(state.toolId)) {
                downloadYtDlp(state);
            } else if (TOOL_FFMPEG.equals(state.toolId)) {
                downloadFfmpeg(state);
            } else {
                throw new IllegalArgumentException("unsupported tool " + state.toolId);
            }
            state.success = true;
            state.phase = "done";
        } catch (Exception ex) {
            log.warn("[Deps] download {} failed", state.toolId, ex);
            state.errorMessage = ex.getMessage() == null ? "下载失败" : ex.getMessage();
            state.success = false;
            state.phase = "error";
        } finally {
            state.running = false;
            state.finishedAt = System.currentTimeMillis();
            probeCache.clear();
        }
    }

    private void downloadYtDlp(DownloadState state) throws IOException {
        String url = ytdlpDownloadUrl();
        if (url == null) {
            throw new IOException("当前系统不支持自动下载 yt-dlp");
        }
        Path target = resolveYtDlpTargetPath();
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".download");
        downloadFile(url, tmp, state);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        setExecutableIfNeeded(target);
        state.outputPath = target.toString();
    }

    private void downloadFfmpeg(DownloadState state) throws IOException {
        String url = ffmpegDownloadUrl();
        if (url == null) {
            throw new IOException("当前系统不支持自动下载 ffmpeg");
        }
        Path installDir = resolveFfmpegInstallDir();
        Files.createDirectories(installDir);
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        Path tmpArchive = installDir.resolve(fileName + ".download");
        Path archive = installDir.resolve(fileName);
        downloadFile(url, tmpArchive, state);
        Files.move(tmpArchive, archive, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        state.phase = "extracting";
        extractArchive(archive, installDir);
        Files.deleteIfExists(archive);
        Path ffmpeg = findBinary(installDir, "ffmpeg");
        if (ffmpeg == null) {
            throw new IOException("解压后未找到 ffmpeg 可执行文件");
        }
        setExecutableIfNeeded(ffmpeg);
        state.outputPath = ffmpeg.toString();
    }

    private void downloadFile(String url, Path output, DownloadState state) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException ex) {
            if (isTlsTrustFailure(ex) && trySystemDownloader(url, output, state)) {
                return;
            }
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断", ex);
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status);
        }
        state.totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        try (InputStream in = new BufferedInputStream(response.body());
             var out = Files.newOutputStream(output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                state.downloadedBytes += read;
            }
        } finally {
            state.lastUpdatedAt = System.currentTimeMillis();
        }
    }

    private boolean trySystemDownloader(String url, Path output, DownloadState state) {
        state.phase = "downloading_fallback";
        String downloader = isWindows() ? "curl.exe" : "curl";
        if (!isCommandRunnable(downloader, "--version")) {
            return false;
        }
        List<String> command = List.of(
            downloader,
            "-L",
            "--fail",
            "--retry",
            "3",
            "--retry-delay",
            "2",
            "--connect-timeout",
            "15",
            "-o",
            output.toString(),
            url
        );
        Process process = null;
        Thread drainer = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process procRef = process;
            drainer = new Thread(() -> drainProcessOutput(procRef), "dep-curl-drain-" + state.toolId);
            drainer.setDaemon(true);
            drainer.start();
            while (process.isAlive()) {
                updateDownloadedFromFile(output, state);
                Thread.sleep(250L);
            }
            if (drainer != null) {
                drainer.join(1000L);
            }
            updateDownloadedFromFile(output, state);
            return process.exitValue() == 0;
        } catch (Exception ex) {
            log.warn("[Deps] system downloader failed tool={} command={}", state.toolId, command, ex);
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private void drainProcessOutput(Process process) {
        if (process == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
                // Drain combined output to avoid process blocking.
            }
        } catch (Exception ignored) {
        }
    }

    private void updateDownloadedFromFile(Path file, DownloadState state) {
        if (file == null || state == null) {
            return;
        }
        try {
            if (Files.isRegularFile(file)) {
                state.downloadedBytes = Files.size(file);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isTlsTrustFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SSLHandshakeException) {
                return true;
            }
            String text = current.getMessage();
            if (text != null && (text.contains("PKIX") || text.contains("certification path"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isCommandRunnable(String command, String arg) {
        Process process = null;
        try {
            process = new ProcessBuilder(command, arg).redirectErrorStream(true).start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private boolean isCommandAvailableCached(String key, String command, String versionArg) {
        if (command == null || command.isBlank()) {
            return false;
        }
        ProbeCache cache = probeCache.get(key);
        Instant now = Instant.now();
        if (cache != null && Duration.between(cache.timestamp, now).compareTo(PROBE_CACHE_TTL) <= 0) {
            return cache.available;
        }
        boolean available = isCommandAvailable(command, versionArg);
        probeCache.put(key, new ProbeCache(available, now));
        return available;
    }

    private boolean isCommandAvailable(String command, String versionArg) {
        String normalized = safe(command);
        if (normalized.isBlank()) {
            return false;
        }
        Path explicit = resolveConfiguredPath(normalized);
        if (explicit != null) {
            if (!Files.isRegularFile(explicit)) {
                return false;
            }
            normalized = explicit.toString();
        }
        ProcessBuilder pb = new ProcessBuilder(normalized, versionArg);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            boolean done = process.waitFor(3, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    private Path resolveYtDlpTargetPath() {
        String yt = safe(configService.get().resolvers.external.yt);
        String ytm = safe(configService.get().resolvers.external.ytmusic);
        Path configured = chooseWritableConfiguredPath(List.of(yt, ytm));
        if (configured != null) {
            return configured;
        }
        return RuntimeToolPathResolver.defaultYtDlpTarget();
    }

    private Path resolveFfmpegInstallDir() {
        String configured = safe(configService.get().tools.ffmpegPath);
        Path explicit = resolveConfiguredPath(configured);
        if (explicit != null) {
            Path parent = explicit.getParent();
            if (parent != null && isParentWritable(parent)) {
                return parent;
            }
        }
        if (isLikelyDirectory(configured)) {
            Path candidate = baseDir().resolve(configured).normalize();
            if (isParentWritable(candidate)) {
                return candidate;
            }
        }
        return baseDir().resolve("ffmpeg");
    }

    private Path resolveFfmpegTargetPath() {
        Path bundled = RuntimeToolPathResolver.findBundledFfmpeg();
        if (bundled != null) {
            return bundled;
        }
        Path installDir = resolveFfmpegInstallDir();
        return installDir.resolve(isWindows() ? "ffmpeg.exe" : "ffmpeg");
    }

    private Path chooseWritableConfiguredPath(List<String> rawCommands) {
        for (String raw : rawCommands) {
            Path path = resolveConfiguredPath(raw);
            if (path == null) {
                continue;
            }
            Path parent = path.getParent();
            if (parent != null && isParentWritable(parent)) {
                return path;
            }
        }
        return null;
    }

    private boolean isParentWritable(Path parent) {
        try {
            if (Files.exists(parent)) {
                return Files.isDirectory(parent) && Files.isWritable(parent);
            }
            Path root = parent.getRoot();
            if (root != null && !Files.exists(root)) {
                return false;
            }
            Files.createDirectories(parent);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private Path resolveConfiguredPath(String raw) {
        String value = safe(raw);
        if (value.isBlank()) {
            return null;
        }
        if ("ffmpeg".equalsIgnoreCase(value) || "auto".equalsIgnoreCase(value) || "yt-dlp".equalsIgnoreCase(value)) {
            return null;
        }
        return RuntimeToolPathResolver.explicitPath(value);
    }

    private boolean isLikelyDirectory(String value) {
        String text = safe(value);
        return text.endsWith("/") || text.endsWith("\\");
    }

    private Path findBinary(Path root, String name) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        String expected = isWindows() ? name + ".exe" : name;
        try (Stream<Path> stream = Files.walk(root, 10)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equalsIgnoreCase(expected))
                .findFirst()
                .orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }

    private void extractArchive(Path archive, Path outputDir) throws IOException {
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            extractZip(archive, outputDir);
            return;
        }
        if (name.endsWith(".tar.xz")) {
            extractTarXz(archive, outputDir);
            return;
        }
        throw new IOException("不支持的压缩包格式: " + archive);
    }

    private void extractZip(Path archive, Path outputDir) throws IOException {
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
    }

    private void extractTarXz(Path archive, Path outputDir) throws IOException {
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
    }

    private String ytdlpDownloadUrl() {
        String os = detectOs();
        if ("windows".equals(os)) {
            return "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
        }
        if ("linux".equals(os)) {
            return "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp";
        }
        return null;
    }

    private String ffmpegDownloadUrl() {
        String os = detectOs();
        String arch = detectArch();
        if ("windows".equals(os)) {
            return "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";
        }
        if ("linux".equals(os)) {
            if ("arm64".equals(arch)) {
                return "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-arm64-static.tar.xz";
            }
            if ("amd64".equals(arch)) {
                return "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz";
            }
        }
        return null;
    }

    private String detectOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        return null;
    }

    private String detectArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "arm64";
        }
        if (arch.contains("x86_64") || arch.contains("amd64")) {
            return "amd64";
        }
        return null;
    }

    private boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    private Path baseDir() {
        return RuntimeToolPathResolver.baseDir();
    }

    private String normalizeToolId(String toolId) {
        return safe(toolId).toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isFilePresent(Path path) {
        return path != null && Files.isRegularFile(path);
    }

    private void setExecutableIfNeeded(Path path) {
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
            log.warn("[Deps] failed to set executable {}", path, ex);
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

    private record ProbeCache(boolean available, Instant timestamp) {
    }

    private static final class DownloadState {
        private final String toolId;
        private volatile boolean running;
        private volatile boolean success;
        private volatile String phase;
        private volatile String errorMessage;
        private volatile String outputPath;
        private volatile long startedAt;
        private volatile long finishedAt;
        private volatile long lastUpdatedAt;
        private volatile long downloadedBytes;
        private volatile long totalBytes;

        private DownloadState(String toolId) {
            this.toolId = toolId;
            this.running = false;
            this.success = false;
            this.phase = "";
            this.errorMessage = "";
            this.outputPath = "";
            this.startedAt = 0;
            this.finishedAt = 0;
            this.lastUpdatedAt = 0;
            this.downloadedBytes = 0;
            this.totalBytes = -1;
        }
    }

    public record DependencySnapshot(List<DependencyStatusItem> items, String updatedAt) {
    }

    public record DependencyStatusItem(
        String id,
        String name,
        boolean installed,
        boolean downloading,
        Integer progressPercent,
        long downloadedBytes,
        long totalBytes,
        long speedBytesPerSecond,
        Long etaSeconds,
        String message,
        boolean canDownload,
        String configured,
        String targetPath,
        String downloadUrl
    ) {
    }

    public record DownloadRequestResult(boolean accepted, String message) {
    }
}
