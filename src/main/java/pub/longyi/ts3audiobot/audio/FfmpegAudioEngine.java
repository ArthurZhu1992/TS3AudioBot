package pub.longyi.ts3audiobot.audio;

import lombok.extern.slf4j.Slf4j;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.queue.Track;
import pub.longyi.ts3audiobot.ts3.Ts3VoiceClient;
import pub.longyi.ts3audiobot.ts3.full.TsFullClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 FfmpegAudioEngine 相关功能。
 */


/**
 * FfmpegAudioEngine 相关功能。
 *
 * <p>职责：负责 FfmpegAudioEngine 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Slf4j
public final class FfmpegAudioEngine implements AudioEngine {
    private static final int FRAME_MS = 20;
    private static final PcmFormat PCM_FORMAT = new PcmFormat(48000, 2, 16);
    private static final double OPUS_VOICE_MIN_KIB = 2.73;
    private static final double OPUS_VOICE_MAX_KIB = 7.71;
    private static final double OPUS_MUSIC_MIN_KIB = 3.08;
    private static final double OPUS_MUSIC_MAX_KIB = 11.87;
    private static final int OPUS_MIN_BITRATE = 6000;
    private static final int OPUS_MAX_BITRATE = 192000;
    private static final String SOURCE_YT = "yt";
    private static final String SOURCE_YTMUSIC = "ytmusic";
    private static final String YT_DLP_FORMAT = "bestaudio";
    private static final String YT_DLP_OUTPUT = "-";
    private static final String YT_DLP_ENCODING = "UTF-8";
    private static final String YT_DLP_ARG_QUIET = "-q";
    private static final String YT_DLP_ARG_NO_WARNINGS = "--no-warnings";
    private static final String YT_DLP_ARG_NO_PLAYLIST = "--no-playlist";
    private static final String YT_DLP_ARG_ENCODING = "--encoding";
    private static final String YT_DLP_ARG_FORMAT = "-f";
    private static final String YT_DLP_ARG_OUTPUT = "-o";

    private final String ffmpegPath;
    private final String ytDlpPath;
    private final String ytMusicPath;
    private final Ts3VoiceClient voiceClient;
    private final OpusEncoder opusEncoder;
    private final FfmpegPcmPump pump;

    private volatile boolean playing;
    private volatile int volumePercent = 100;
    private volatile String currentStreamUrl;
    private volatile String currentSourceId;
    private volatile String currentSourceType;
    private volatile long lastStatsAt;
    private volatile long pcmFrames;
    private volatile long pcmBytes;
    private volatile long opusBytes;
    private volatile long droppedFrames;
    private volatile long droppedNoConn;
    private volatile long droppedEncode;

    private static final long STATS_INTERVAL_MS = 5000L;

    /**
     * 创建 FfmpegAudioEngine 实例。
     * @param configService 参数 configService
     * @param voiceClient 参数 voiceClient
     */
    public FfmpegAudioEngine(ConfigService configService, Ts3VoiceClient voiceClient) {
        var config = configService.get();
        this.ffmpegPath = config.tools.ffmpegPath;
        this.ytDlpPath = config.resolvers.external.yt;
        this.ytMusicPath = config.resolvers.external.ytmusic;
        this.voiceClient = voiceClient;
        this.opusEncoder = new ConcentusOpusEncoder(PCM_FORMAT, FRAME_MS);
        this.pump = new FfmpegPcmPump(ffmpegPath, PCM_FORMAT, FRAME_MS, this::onPcmFrame, this::onPcmFinished);
    }


    /**
     * 执行 play 操作。
     * @param track 参数 track
     */
    @Override
    public void play(Track track) {
        log.info("[Audio] play track={} source={} ffmpeg={}", track.title(), track.sourceType(), ffmpegPath);
        currentStreamUrl = track.streamUrl();
        currentSourceId = track.sourceId();
        currentSourceType = track.sourceType();
        playing = true;
        resetStats();
        updateChannelBitrate();
        startPump(track, 0);
    }


    /**
     * 执行 pause 操作。
     */
    @Override
    public void pause() {
        log.info("[Audio] pause");
        playing = false;
        pump.stop();
        voiceClient.sendOpusFrame(new byte[0], 0);
    }


    /**
     * 执行 stop 操作。
     */
    @Override
    public void stop() {
        log.info("[Audio] stop");
        playing = false;
        currentStreamUrl = null;
        currentSourceId = null;
        currentSourceType = null;
        pump.stop();
        voiceClient.sendOpusFrame(new byte[0], 0);
    }


    /**
     * 执行 seek 操作。
     * @param positionMs 参数 positionMs
     */
    @Override
    public void seek(long positionMs) {
        log.info("[Audio] seek {}ms", positionMs);
        if (!playing) {
            return;
        }
        startPump(currentSourceType, currentSourceId, currentStreamUrl, positionMs);
    }


    /**
     * 执行 setVolume 操作。
     * @param percent 参数 percent
     */
    @Override
    public void setVolume(int percent) {
        volumePercent = Math.max(0, Math.min(200, percent));
        log.info("[Audio] volume {}%", volumePercent);
    }


    /**
     * 执行 isPlaying 操作。
     * @return 返回值
     */
    @Override
    public boolean isPlaying() {
        return playing;
    }

    private void onPcmFrame(byte[] data, int length, PcmFormat format) {
        pcmFrames++;
        pcmBytes += length;
        if (!voiceClient.isConnected()) {
            droppedFrames++;
            droppedNoConn++;
            logStatsIfNeeded();
            return;
        }
        byte[] pcm = applyVolume(data, length);
        byte[] opus = opusEncoder.encode(pcm, pcm.length, format);
        if (opus.length == 0) {
            droppedFrames++;
            droppedEncode++;
            logStatsIfNeeded();
            return;
        }
        opusBytes += opus.length;
        voiceClient.sendOpusFrame(opus, opus.length);
        logStatsIfNeeded();
    }

    private void onPcmFinished() {
        if (!playing) {
            return;
        }
        playing = false;
        voiceClient.sendOpusFrame(new byte[0], 0);
        log.info("[Audio] track finished");
    }

    private byte[] applyVolume(byte[] data, int length) {
        if (volumePercent == 100) {
            byte[] copy = new byte[length];
            System.arraycopy(data, 0, copy, 0, length);
            return copy;
        }
        double gain = volumePercent / 100.0;
        byte[] output = new byte[length];
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (data[i + 1] << 8) | (data[i] & 0xff);
            int scaled = (int) Math.round(sample * gain);
            if (scaled > Short.MAX_VALUE) {
                scaled = Short.MAX_VALUE;
            } else if (scaled < Short.MIN_VALUE) {
                scaled = Short.MIN_VALUE;
            }
            output[i] = (byte) (scaled & 0xff);
            output[i + 1] = (byte) ((scaled >> 8) & 0xff);
        }
        return output;
    }

    private void resetStats() {
        lastStatsAt = System.currentTimeMillis();
        pcmFrames = 0;
        pcmBytes = 0;
        opusBytes = 0;
        droppedFrames = 0;
        droppedNoConn = 0;
        droppedEncode = 0;
    }

    private void logStatsIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastStatsAt < STATS_INTERVAL_MS) {
            return;
        }
        lastStatsAt = now;
        log.info(
            "[Audio] stats frames={} pcmBytes={} opusBytes={} dropped={} noConn={} encodeFail={}",
            pcmFrames,
            pcmBytes,
            opusBytes,
            droppedFrames,
            droppedNoConn,
            droppedEncode
        );
    }

    private void updateChannelBitrate() {
        if (!voiceClient.isConnected()) {
            return;
        }
        if (!(voiceClient instanceof TsFullClient fullClient)) {
            return;
        }
        TsFullClient.ChannelCodecInfo codecInfo = fullClient.fetchChannelCodecInfo();
        if (codecInfo == null) {
            return;
        }
        int bitrate = resolveBitrate(codecInfo.codec(), codecInfo.quality());
        if (opusEncoder instanceof ConcentusOpusEncoder encoder) {
            encoder.setBitrate(bitrate);
            log.info(
                "[Audio] channel codec={} quality={} bitrate={}bps",
                codecInfo.codec(),
                codecInfo.quality(),
                bitrate
            );
        }
    }

    private void startPump(Track track, long positionMs) {
        if (track == null) {
            return;
        }
        startPump(track.sourceType(), track.sourceId(), track.streamUrl(), positionMs);
    }

    private void startPump(String sourceType, String sourceId, String streamUrl, long positionMs) {
        if (tryStartWithPipe(sourceType, sourceId, positionMs)) {
            return;
        }
        if (streamUrl == null || streamUrl.isBlank()) {
            log.warn("[Audio] stream url empty, skip play");
            return;
        }
        pump.start(streamUrl, positionMs);
    }

    private boolean tryStartWithPipe(String sourceType, String sourceId, long positionMs) {
        if (!isYtSource(sourceType)) {
            return false;
        }
        String command = resolveYtCommand(sourceType);
        if (command == null || command.isBlank()) {
            return false;
        }
        if (sourceId == null || sourceId.isBlank()) {
            return false;
        }
        List<String> args = buildYtDlpPipeArgs(command, sourceId);
        boolean started = pump.startWithPipe(args, positionMs);
        if (started) {
            log.info("[Audio] yt-dlp pipe enabled source={}", sourceType);
            return true;
        }
        log.warn("[Audio] yt-dlp pipe failed, fallback to direct url");
        return false;
    }

    private boolean isYtSource(String sourceType) {
        if (sourceType == null) {
            return false;
        }
        String lower = sourceType.trim().toLowerCase();
        return SOURCE_YT.equals(lower) || SOURCE_YTMUSIC.equals(lower);
    }

    private String resolveYtCommand(String sourceType) {
        String lower = sourceType == null ? "" : sourceType.trim().toLowerCase();
        if (SOURCE_YTMUSIC.equals(lower)) {
            return ytMusicPath;
        }
        return ytDlpPath;
    }

    private List<String> buildYtDlpPipeArgs(String command, String sourceId) {
        List<String> args = new ArrayList<>();
        args.add(command);
        args.add(YT_DLP_ARG_QUIET);
        args.add(YT_DLP_ARG_NO_WARNINGS);
        args.add(YT_DLP_ARG_NO_PLAYLIST);
        args.add(YT_DLP_ARG_ENCODING);
        args.add(YT_DLP_ENCODING);
        args.add(YT_DLP_ARG_FORMAT);
        args.add(YT_DLP_FORMAT);
        args.add(YT_DLP_ARG_OUTPUT);
        args.add(YT_DLP_OUTPUT);
        args.add(sourceId);
        return args;
    }

    private int resolveBitrate(int codec, int quality) {
        int clamped = Math.max(0, Math.min(10, quality));
        double ratio = clamped / 10.0;
        double minKib = OPUS_MUSIC_MIN_KIB;
        double maxKib = OPUS_MUSIC_MAX_KIB;
        if (codec == 4) {
            minKib = OPUS_VOICE_MIN_KIB;
            maxKib = OPUS_VOICE_MAX_KIB;
        } else if (codec == 5) {
            minKib = OPUS_MUSIC_MIN_KIB;
            maxKib = OPUS_MUSIC_MAX_KIB;
        }
        double kib = minKib + (maxKib - minKib) * ratio;
        int bitrate = (int) Math.round(kib * 1024 * 8);
        return Math.max(OPUS_MIN_BITRATE, Math.min(OPUS_MAX_BITRATE, bitrate));
    }
}
