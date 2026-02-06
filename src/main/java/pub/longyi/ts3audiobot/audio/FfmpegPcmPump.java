package pub.longyi.ts3audiobot.audio;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 FfmpegPcmPump 相关功能。
 */


/**
 * FfmpegPcmPump 相关功能。
 *
 * <p>职责：负责 FfmpegPcmPump 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Slf4j
public final class FfmpegPcmPump {
    private static final int PIPE_BUFFER_SIZE = 16 * 1024;
    private static final String PIPE_INPUT = "pipe:0";
    private static final String PIPE_OUTPUT = "pipe:1";
    private static final String LABEL_FFMPEG = "FFmpeg";
    private static final String LABEL_INPUT = "Input";
    private static final String THREAD_FFMPEG_READER = "ffmpeg-pcm-reader";
    private static final String THREAD_FFMPEG_STDERR = "ffmpeg-pcm-stderr";
    private static final String THREAD_INPUT_STDERR = "input-pipe-stderr";
    private static final String THREAD_INPUT_PIPE = "input-pipe";
    private static final String FFMPEG_ARG_HIDE_BANNER = "-hide_banner";
    private static final String FFMPEG_ARG_NO_STATS = "-nostats";
    private static final String FFMPEG_ARG_THREADS = "-threads";
    private static final String FFMPEG_THREADS = "1";
    private static final String FFMPEG_ARG_SEEK = "-ss";
    private static final String FFMPEG_ARG_INPUT = "-i";
    private static final String FFMPEG_ARG_CHANNELS = "-ac";
    private static final String FFMPEG_ARG_RATE = "-ar";
    private static final String FFMPEG_ARG_FORMAT = "-f";
    private static final String FFMPEG_FORMAT_S16LE = "s16le";
    private static final String FFMPEG_ARG_CODEC = "-acodec";
    private static final String FFMPEG_CODEC_PCM = "pcm_s16le";
    private static final long UNKNOWN_PID = -1;

    private final String ffmpegPath;
    private final PcmFormat format;
    private final int frameMs;
    private final AudioFrameConsumer consumer;
    private final Runnable onFinish;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Process process;
    private Process inputProcess;
    private Thread readerThread;
    private Thread stderrThread;
    private Thread inputStderrThread;
    private Thread pipeThread;

    /**
     * 创建 FfmpegPcmPump 实例。
     * @param ffmpegPath 参数 ffmpegPath
     * @param format 参数 format
     * @param frameMs 参数 frameMs
     * @param consumer 参数 consumer
     */
    public FfmpegPcmPump(String ffmpegPath, PcmFormat format, int frameMs, AudioFrameConsumer consumer) {
        this(ffmpegPath, format, frameMs, consumer, null);
    }


    /**
     * 创建 FfmpegPcmPump 实例。
     * @param ffmpegPath 参数 ffmpegPath
     * @param format 参数 format
     * @param frameMs 参数 frameMs
     * @param consumer 参数 consumer
     * @param onFinish 参数 onFinish
     */
    public FfmpegPcmPump(
        String ffmpegPath,
        PcmFormat format,
        int frameMs,
        AudioFrameConsumer consumer,
        Runnable onFinish
    ) {
        this.ffmpegPath = ffmpegPath;
        this.format = format;
        this.frameMs = frameMs;
        this.consumer = consumer;
        this.onFinish = onFinish;
    }


    /**
     * 执行 start 操作。
     * @param input 参数 input
     * @param startMs 参数 startMs
     */
    public synchronized void start(String input, long startMs) {
        Objects.requireNonNull(input, "input");
        if (input.isBlank()) {
            throw new IllegalArgumentException("input required");
        }
        stop();
        List<String> command = buildCommand(input, startMs);
        Process ffmpeg = startProcess(command, LABEL_FFMPEG);
        if (ffmpeg == null) {
            throw new IllegalStateException("Failed to start ffmpeg");
        }
        process = ffmpeg;
        running.set(true);
        startReaderThreads(ffmpeg, THREAD_FFMPEG_READER, THREAD_FFMPEG_STDERR);
    }


    /**
     * 执行 startWithPipe 操作。
     * @param inputCommand 参数 inputCommand
     * @param startMs 参数 startMs
     * @return 返回值
     */
    public synchronized boolean startWithPipe(List<String> inputCommand, long startMs) {
        Objects.requireNonNull(inputCommand, "inputCommand");
        if (inputCommand.isEmpty()) {
            throw new IllegalArgumentException("inputCommand required");
        }
        stop();
        Process source = startProcess(inputCommand, LABEL_INPUT);
        if (source == null) {
            return false;
        }
        List<String> ffmpegCommand = buildCommand(PIPE_INPUT, startMs);
        Process ffmpeg = startProcess(ffmpegCommand, LABEL_FFMPEG);
        if (ffmpeg == null) {
            safeDestroy(source);
            return false;
        }
        inputProcess = source;
        process = ffmpeg;
        running.set(true);
        startPipeThread(source, ffmpeg);
        startReaderThreads(ffmpeg, THREAD_FFMPEG_READER, THREAD_FFMPEG_STDERR);
        startInputStderrThread(source);
        return true;
    }


    /**
     * 执行 stop 操作。
     */
    public synchronized void stop() {
        running.set(false);
        if (inputProcess != null) {
            inputProcess.destroy();
            inputProcess = null;
        }
        if (process != null) {
            process.destroy();
            process = null;
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        if (stderrThread != null) {
            stderrThread.interrupt();
            stderrThread = null;
        }
        if (inputStderrThread != null) {
            inputStderrThread.interrupt();
            inputStderrThread = null;
        }
        if (pipeThread != null) {
            pipeThread.interrupt();
            pipeThread = null;
        }
    }


    /**
     * 执行 isRunning 操作。
     * @return 返回值
     */
    public boolean isRunning() {
        return running.get();
    }

    private void readLoop(Process proc) {
        int frameBytes = format.frameBytes(frameMs);
        byte[] buffer = new byte[frameBytes];
        long startAt = System.nanoTime();
        long frameIndex = 0;
        try (InputStream inputStream = new BufferedInputStream(proc.getInputStream())) {
            while (running.get()) {
                int offset = 0;
                while (offset < buffer.length) {
                    int read = inputStream.read(buffer, offset, buffer.length - offset);
                    if (read < 0) {
                        if (running.get()) {
                            running.set(false);
                            log.info("[{}] PCM output ended", LABEL_FFMPEG);
                            if (onFinish != null) {
                                onFinish.run();
                            }
                        }
                        return;
                    }
                    offset += read;
                }
                consumer.onPcmFrame(buffer, buffer.length, format);
                frameIndex++;
                paceFrame(startAt, frameIndex);
            }
        } catch (IOException ex) {
            if (running.get()) {
                log.error("ffmpeg read loop failed", ex);
            }
        } finally {
            running.set(false);
        }
    }

    private void readStderr(Process proc, String label) {
        try (InputStream stream = proc.getErrorStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    log.info("[{}] {}", label, line);
                }
            }
        } catch (IOException ex) {
            if (running.get()) {
                log.warn("[{}] stderr read failed", label, ex);
            }
        } finally {
            try {
                int code = proc.waitFor();
                log.info("[{}] process exited code={}", label, code);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Process startProcess(List<String> command, String label) {
        ProcessBuilder builder = new ProcessBuilder(command);
        try {
            Process proc = builder.start();
            log.info("[{}] started pid={} command={}", label, safePid(proc), String.join(" ", command));
            return proc;
        } catch (IOException ex) {
            log.warn("[{}] failed to start", label, ex);
            return null;
        }
    }

    private void startReaderThreads(Process ffmpeg, String readerName, String stderrName) {
        readerThread = new Thread(() -> readLoop(ffmpeg), readerName);
        readerThread.setDaemon(true);
        readerThread.start();
        stderrThread = new Thread(() -> readStderr(ffmpeg, LABEL_FFMPEG), stderrName);
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    private void startInputStderrThread(Process source) {
        inputStderrThread = new Thread(() -> readStderr(source, LABEL_INPUT), THREAD_INPUT_STDERR);
        inputStderrThread.setDaemon(true);
        inputStderrThread.start();
    }

    private void startPipeThread(Process source, Process target) {
        pipeThread = new Thread(() -> pipeLoop(source, target), THREAD_INPUT_PIPE);
        pipeThread.setDaemon(true);
        pipeThread.start();
    }

    private void pipeLoop(Process source, Process target) {
        try (InputStream inputStream = new BufferedInputStream(source.getInputStream());
             OutputStream outputStream = new BufferedOutputStream(target.getOutputStream())) {
            byte[] buffer = new byte[PIPE_BUFFER_SIZE];
            while (running.get()) {
                int read = inputStream.read(buffer);
                if (read < 0) {
                    return;
                }
                outputStream.write(buffer, 0, read);
                outputStream.flush();
            }
        } catch (IOException ex) {
            if (running.get()) {
                log.warn("[{}] pipe failed", LABEL_INPUT, ex);
            }
        }
    }

    private void safeDestroy(Process proc) {
        if (proc != null) {
            proc.destroy();
        }
    }

    private long safePid(Process proc) {
        try {
            return proc.pid();
        } catch (UnsupportedOperationException ex) {
            return UNKNOWN_PID;
        }
    }

    private List<String> buildCommand(String input, long startMs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add(FFMPEG_ARG_HIDE_BANNER);
        cmd.add(FFMPEG_ARG_NO_STATS);
        cmd.add(FFMPEG_ARG_THREADS);
        cmd.add(FFMPEG_THREADS);
        if (startMs > 0) {
            cmd.add(FFMPEG_ARG_SEEK);
            cmd.add(String.format("%.3f", startMs / 1000.0));
        }
        cmd.add(FFMPEG_ARG_INPUT);
        cmd.add(input);
        cmd.add(FFMPEG_ARG_CHANNELS);
        cmd.add(Integer.toString(format.channels()));
        cmd.add(FFMPEG_ARG_RATE);
        cmd.add(Integer.toString(format.sampleRate()));
        cmd.add(FFMPEG_ARG_FORMAT);
        cmd.add(FFMPEG_FORMAT_S16LE);
        cmd.add(FFMPEG_ARG_CODEC);
        cmd.add(FFMPEG_CODEC_PCM);
        cmd.add(PIPE_OUTPUT);
        return cmd;
    }

    private void paceFrame(long startAtNs, long frameIndex) {
        if (frameMs <= 0) {
            return;
        }
        long targetNs = startAtNs + frameIndex * frameMs * 1_000_000L;
        long now = System.nanoTime();
        long remaining = targetNs - now;
        if (remaining <= 0) {
            return;
        }
        long sleepMs = remaining / 1_000_000L;
        int sleepNs = (int) (remaining % 1_000_000L);
        try {
            Thread.sleep(sleepMs, sleepNs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
