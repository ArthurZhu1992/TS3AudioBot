package pub.longyi.ts3audiobot.resolver;

import lombok.extern.slf4j.Slf4j;
import pub.longyi.ts3audiobot.queue.Track;
import pub.longyi.ts3audiobot.util.IdGenerator;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 ExternalCliResolver 相关功能。
 */


/**
 * ExternalCliResolver 相关功能。
 *
 * <p>职责：负责 ExternalCliResolver 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Slf4j
public final class ExternalCliResolver implements TrackResolver {
    private static final long PROCESS_TIMEOUT_MS = 20_000L;

    private final String sourceType;
    private final String command;

    /**
     * 创建 ExternalCliResolver 实例。
     * @param sourceType 参数 sourceType
     * @param command 参数 command
     */
    public ExternalCliResolver(String sourceType, String command) {
        this.sourceType = sourceType;
        this.command = command;
    }


    /**
     * 执行 resolve 操作。
     * @param query 参数 query
     * @return 返回值
     */
    @Override
    public Optional<Track> resolve(String query) {
        if (command == null || command.isBlank()) {
            return Optional.empty();
        }
        log.info("[Resolver:{}] using command={} query={}", sourceType, command, query);
        List<String> output = runCommand(buildArgs(query));
        if (output.isEmpty()) {
            log.warn("[Resolver:{}] command output empty query={}", sourceType, query);
            return Optional.empty();
        }
        ParsedOutput parsed = parseOutput(output, query);
        String streamUrl = parsed.streamUrl;
        String title = parsed.title;
        long duration = parsed.duration;
        if (streamUrl.isBlank()) {
            log.warn("[Resolver:{}] stream url empty output={}", sourceType, output);
            return Optional.empty();
        }
        log.info("[Resolver:{}] resolved streamUrl={} title={} durationMs={}",
            sourceType,
            streamUrl,
            title,
            duration
        );
        Track track = new Track(
            IdGenerator.newId(),
            title.isBlank() ? query : title,
            sourceType,
            query,
            streamUrl,
            duration
        );
        return Optional.of(track);
    }


    /**
     * 执行 sourceType 操作。
     * @return 返回值
     */
    @Override
    public String sourceType() {
        return sourceType;
    }

    private List<String> buildArgs(String query) {
        List<String> args = new ArrayList<>();
        args.add(command);
        String lower = command.toLowerCase();
        if (lower.contains("yt-dlp") || lower.contains("youtube-dl")) {
            args.add("-q");
            args.add("--no-warnings");
            args.add("--no-playlist");
            args.add("--encoding");
            args.add("UTF-8");
            args.add("--get-url");
            args.add("--get-title");
            args.add("--get-duration");
            args.add("-f");
            args.add("bestaudio");
            args.add(query);
        } else {
            args.add(query);
        }
        return args;
    }

    private ParsedOutput parseOutput(List<String> output, String fallbackTitle) {
        if (isYtDlpCommand()) {
            List<String> cleaned = new ArrayList<>();
            for (String line : output) {
                if (line == null) {
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("WARNING:") || trimmed.startsWith("ERROR:")) {
                    continue;
                }
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    continue;
                }
                if (trimmed.startsWith("[")) {
                    continue;
                }
                cleaned.add(trimmed);
            }
            if (cleaned.isEmpty()) {
                return new ParsedOutput("", fallbackTitle, 0);
            }
            UrlPick picked = pickStreamUrl(cleaned, fallbackTitle);
            return new ParsedOutput(picked.streamUrl, picked.title, picked.duration);
        }

        String streamUrl = output.get(0).trim();
        String title = output.size() > 1 ? output.get(1).trim() : fallbackTitle;
        long duration = output.size() > 2 ? parseDurationSeconds(output.get(2).trim()) : 0;
        return new ParsedOutput(streamUrl, title, duration);
    }

    private UrlPick pickStreamUrl(List<String> cleaned, String fallbackTitle) {
        List<String> urls = new ArrayList<>();
        for (String line : cleaned) {
            if (line.startsWith("http://") || line.startsWith("https://")) {
                urls.add(line);
            }
        }
        if (urls.isEmpty()) {
            String title = cleaned.get(0);
            long duration = cleaned.size() > 1 ? parseDurationSeconds(cleaned.get(1)) : 0;
            return new UrlPick("", title, duration);
        }
        String preferred = selectPreferredUrl(urls, fallbackTitle);
        if (preferred == null || preferred.isBlank()) {
            preferred = urls.get(0);
        }
        int idx = cleaned.indexOf(preferred);
        String title = fallbackTitle;
        long duration = 0;
        if (idx >= 0) {
            String before = idx - 1 >= 0 ? cleaned.get(idx - 1) : null;
            String after = idx + 1 < cleaned.size() ? cleaned.get(idx + 1) : null;
            String after2 = idx + 2 < cleaned.size() ? cleaned.get(idx + 2) : null;
            if (before != null && !isLikelyDuration(before) && !isUrl(before)) {
                title = before;
            }
            if (after != null && isLikelyDuration(after)) {
                duration = parseDurationSeconds(after);
            } else if (after2 != null && isLikelyDuration(after2)) {
                duration = parseDurationSeconds(after2);
            }
            if (title.equals(fallbackTitle) && after != null && !isLikelyDuration(after) && !isUrl(after)) {
                title = after;
            }
        }
        return new UrlPick(preferred, title, duration);
    }

    private String selectPreferredUrl(List<String> urls, String fallbackTitle) {
        String candidate = null;
        for (String url : urls) {
            if (isLikelyStreamUrl(url)) {
                return url;
            }
            if (candidate == null) {
                candidate = url;
            }
        }
        if (candidate != null && !candidate.equals(fallbackTitle)) {
            return candidate;
        }
        return candidate;
    }

    private boolean isLikelyStreamUrl(String url) {
        String lower = url.toLowerCase();
        if (lower.contains("googlevideo.com")) {
            return true;
        }
        if (lower.contains("youtube.com/watch") || lower.contains("music.youtube.com/watch")) {
            return false;
        }
        if (lower.contains("youtu.be/")) {
            return false;
        }
        return lower.contains("videoplayback") || lower.contains("m3u8") || lower.contains("manifest");
    }

    private boolean isUrl(String value) {
        if (value == null) {
            return false;
        }
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private boolean isLikelyDuration(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.matches("^\\d+$")) {
            return true;
        }
        return value.matches("^\\d{1,2}:\\d{2}(:\\d{2})?$");
    }

    private boolean isYtDlpCommand() {
        String lower = command == null ? "" : command.toLowerCase();
        return lower.contains("yt-dlp") || lower.contains("youtube-dl");
    }

    private List<String> runCommand(List<String> args) {
        List<String> lines = new ArrayList<>();
        ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        lines.add(line);
                    }
                }
            }
            boolean finished = process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("[Resolver:{}] command timeout", sourceType);
                return List.of();
            }
            if (process.exitValue() != 0) {
                log.warn("[Resolver:{}] command exit {}", sourceType, process.exitValue());
            }
        } catch (Exception ex) {
            log.warn("[Resolver:{}] command failed", sourceType, ex);
            return List.of();
        }
        return lines;
    }

    private long parseDurationSeconds(String value) {
        if (value == null || value.isBlank() || "NA".equalsIgnoreCase(value) || "None".equalsIgnoreCase(value)) {
            return 0;
        }
        try {
            if (value.matches("^\\d+$")) {
                return Long.parseLong(value) * 1000;
            }
            String[] parts = value.split(":");
            long seconds = 0;
            for (String part : parts) {
                seconds = seconds * 60 + Long.parseLong(part.trim());
            }
            return seconds * 1000;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private record ParsedOutput(String streamUrl, String title, long duration) {
    }

    private record UrlPick(String streamUrl, String title, long duration) {
    }
}
