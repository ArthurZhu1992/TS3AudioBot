package pub.longyi.ts3audiobot.bot;

import lombok.extern.slf4j.Slf4j;
import pub.longyi.ts3audiobot.audio.AudioEngine;
import pub.longyi.ts3audiobot.config.AppConfig;
import pub.longyi.ts3audiobot.queue.QueueService;
import pub.longyi.ts3audiobot.queue.QueueItem;
import pub.longyi.ts3audiobot.queue.Track;
import pub.longyi.ts3audiobot.ts3.Ts3VoiceClient;
import pub.longyi.ts3audiobot.ts3.full.ConnectionDataFull;
import pub.longyi.ts3audiobot.ts3.full.Password;
import pub.longyi.ts3audiobot.ts3.full.TsCrypt;
import pub.longyi.ts3audiobot.ts3.full.TsVersionSigned;
import pub.longyi.ts3audiobot.ts3.full.IdentityData;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 BotInstance 相关功能。
 */


/**
 * BotInstance 相关功能。
 *
 * <p>职责：负责 BotInstance 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Slf4j
public final class BotInstance {
    private final String id;
    private final AppConfig.BotConfig config;
    private final Ts3VoiceClient client;
    private final AudioEngine audioEngine;
    private final QueueService queueService;
    private final ScheduledExecutorService scheduler;
    private final java.util.Random random = new java.util.Random();

    private volatile BotStatus status = BotStatus.STOPPED;
    private volatile ConnectionDataFull connectionData;
    private volatile int volumePercent = DEFAULT_VOLUME_PERCENT;
    private ScheduledFuture<?> playbackTask;
    private ScheduledFuture<?> connectionTask;
    private volatile boolean connectInProgress;
    private volatile long connectAttemptAt;
    private volatile long nextReconnectAt;
    private volatile long reconnectDelayMs = RECONNECT_BASE_MS;
    private volatile boolean playbackPaused;
    private volatile PlaybackMode playbackMode = PlaybackMode.ORDER;
    private volatile Track currentTrack;
    private volatile long playbackStartedAt;
    private volatile long playbackPositionMs;

    private static final long RECONNECT_BASE_MS = 2000L;
    private static final long RECONNECT_MAX_MS = 30_000L;
    private static final long CONNECT_TIMEOUT_MS = 35_000L;
    private static final int DEFAULT_VOLUME_PERCENT = 100;
    private static final int MIN_VOLUME_PERCENT = 0;
    private static final int MAX_VOLUME_PERCENT = 100;
    private static final int DEFAULT_IDENTITY_LEVEL = 8;
    private static final int MAX_IDENTITY_LEVEL = 20;

    /**
     * 创建 BotInstance 实例。
     * @param id 参数 id
     * @param config 参数 config
     * @param client 参数 client
     * @param audioEngine 参数 audioEngine
     * @param queueService 参数 queueService
     * @param scheduler 参数 scheduler
     */
    public BotInstance(
        String id,
        AppConfig.BotConfig config,
        Ts3VoiceClient client,
        AudioEngine audioEngine,
        QueueService queueService,
        ScheduledExecutorService scheduler
    ) {
        this.id = id;
        this.config = config;
        this.client = client;
        this.audioEngine = audioEngine;
        this.queueService = queueService;
        this.scheduler = scheduler;
        if (config != null) {
            this.volumePercent = clampVolume(config.volumePercent);
            this.audioEngine.setVolume(this.volumePercent);
        }
    }


    /**
     * 执行 id 操作。
     * @return 返回值
     */
    public String id() {
        return id;
    }


    /**
     * 执行 name 操作。
     * @return 返回值
     */
    public String name() {
        return config.name;
    }


    /**
     * 执行 playbackMode 操作。
     * @return 返回值
     */
    public PlaybackMode playbackMode() {
        return playbackMode;
    }


    /**
     * 执行 setPlaybackMode 操作。
     * @param playbackMode 参数 playbackMode
     */
    public void setPlaybackMode(PlaybackMode playbackMode) {
        if (playbackMode == null) {
            this.playbackMode = PlaybackMode.ORDER;
        } else {
            this.playbackMode = playbackMode;
        }
    }


    /**
     * 执行 status 操作。
     * @return 返回值
     */
    public BotStatus status() {
        return status;
    }


    /**
     * 执行 isPlaying 操作。
     * @return 返回值
     */
    public boolean isPlaying() {
        return audioEngine.isPlaying();
    }


    /**
     * 执行 isPlaybackPaused 操作。
     * @return 返回值
     */
    public boolean isPlaybackPaused() {
        return playbackPaused;
    }


    /**
     * 执行 currentTrack 操作。
     * @return 返回值
     */
    public Track currentTrack() {
        return currentTrack;
    }


    /**
     * 执行 volumePercent 操作。
     * @return 返回值
     */
    public int volumePercent() {
        return volumePercent;
    }


    /**
     * 执行 setVolumePercent 操作。
     * @param percent 参数 percent
     */
    public synchronized void setVolumePercent(int percent) {
        int clamped = clampVolume(percent);
        volumePercent = clamped;
        audioEngine.setVolume(clamped);
    }


    /**
     * 执行 playbackPositionMs 操作。
     * @return 返回值
     */
    public long playbackPositionMs() {
        return resolvePlaybackPosition();
    }


    /**
     * 执行 playbackDurationMs 操作。
     * @return 返回值
     */
    public long playbackDurationMs() {
        Track track = currentTrack;
        if (track == null) {
            return 0L;
        }
        return Math.max(0L, track.durationMs());
    }


    /**
     * 执行 start 操作。
     */
    public synchronized void start() {
        if (status == BotStatus.RUNNING || status == BotStatus.STARTING) {
            return;
        }
        status = BotStatus.STARTING;
        playbackPaused = false;
        try {
            connectionData = buildConnectionData();
            client.configure(connectionData);
            attemptConnect();
            if (playbackTask == null || playbackTask.isCancelled()) {
                playbackTask = scheduler.scheduleAtFixedRate(this::tickPlayback, 0, 1, TimeUnit.SECONDS);
            }
            if (connectionTask == null || connectionTask.isCancelled()) {
                connectionTask = scheduler.scheduleAtFixedRate(this::ensureConnected, 1, 2, TimeUnit.SECONDS);
            }
            status = BotStatus.RUNNING;
        } catch (Exception ex) {
            log.error("Bot {} failed to start", id, ex);
            status = BotStatus.ERROR;
        }
    }


    /**
     * 执行 stop 操作。
     */
    public synchronized void stop() {
        if (status == BotStatus.STOPPED) {
            return;
        }
        playbackPaused = true;
        playbackPositionMs = 0L;
        currentTrack = null;
        audioEngine.stop();
        cancelTask(playbackTask);
        cancelTask(connectionTask);
        connectInProgress = false;
        client.disconnect();
        status = BotStatus.STOPPED;
    }


    /**
     * 执行 pausePlayback 操作。
     */
    public synchronized void pausePlayback() {
        playbackPositionMs = resolvePlaybackPosition();
        playbackPaused = true;
        audioEngine.stop();
    }


    /**
     * 执行 startPlayback 操作。
     */
    public synchronized void startPlayback() {
        if (status == BotStatus.STOPPED || status == BotStatus.ERROR) {
            start();
            return;
        }
        boolean wasPaused = playbackPaused;
        playbackPaused = false;
        if (wasPaused && currentTrack != null) {
            long resumeAt = Math.max(0L, playbackPositionMs);
            playbackStartedAt = System.currentTimeMillis() - resumeAt;
            audioEngine.play(currentTrack);
            if (resumeAt > 0L) {
                audioEngine.seek(resumeAt);
            }
            return;
        }
        if (!audioEngine.isPlaying()) {
            playNext();
        }
    }


    /**
     * 执行 shutdown 操作。
     */
    public synchronized void shutdown() {
        stop();
        scheduler.shutdownNow();
    }


    /**
     * 执行 playNext 操作。
     */
    public void playNext() {
        playNext(false);
    }


    /**
     * 执行 playNextForced 操作。
     */
    public void playNextForced() {
        playNext(true);
    }

    private void playNext(boolean forceOrder) {
        String playlistId = queueService.getActivePlaylist(id);
        PlaybackMode mode = forceOrder ? PlaybackMode.ORDER : playbackMode;
        QueueItem resolved = switch (mode) {
            case RANDOM -> queueService.nextRandom(id, playlistId, random);
            case LOOP -> queueService.nextLoop(id, playlistId);
            default -> queueService.next(id, playlistId);
        };
        if (resolved == null) {
            return;
        }
        playbackPaused = false;
        currentTrack = resolved.track();
        playbackPositionMs = 0L;
        playbackStartedAt = System.currentTimeMillis();
        audioEngine.play(resolved.track());
    }


    /**
     * 执行 seekPlayback 操作。
     * @param positionMs 参数 positionMs
     */
    public synchronized void seekPlayback(long positionMs) {
        Track track = currentTrack;
        if (track == null) {
            return;
        }
        long duration = Math.max(0L, track.durationMs());
        long clamped = Math.max(0L, positionMs);
        if (duration > 0L) {
            clamped = Math.min(clamped, duration);
        }
        playbackPositionMs = clamped;
        playbackStartedAt = System.currentTimeMillis() - clamped;
        if (playbackPaused) {
            return;
        }
        if (!audioEngine.isPlaying()) {
            audioEngine.play(track);
        }
        audioEngine.seek(clamped);
    }

    private void tickPlayback() {
        if (playbackPaused) {
            return;
        }
        if (!audioEngine.isPlaying()) {
            playNext();
        }
    }

    private void ensureConnected() {
        if (status != BotStatus.RUNNING) {
            return;
        }
        if (client.isConnected()) {
            connectInProgress = false;
            reconnectDelayMs = RECONNECT_BASE_MS;
            return;
        }
        long now = System.currentTimeMillis();
        if (connectInProgress) {
            if (now - connectAttemptAt >= CONNECT_TIMEOUT_MS) {
                client.disconnect();
                connectInProgress = false;
            } else {
                return;
            }
        }
        if (now < nextReconnectAt) {
            return;
        }
        attemptConnect();
    }

    private void attemptConnect() {
        long now = System.currentTimeMillis();
        connectAttemptAt = now;
        nextReconnectAt = now + reconnectDelayMs;
        reconnectDelayMs = Math.min(RECONNECT_MAX_MS, reconnectDelayMs * 2);
        connectInProgress = true;
        if (connectionData == null) {
            connectionData = buildConnectionData();
        }
        client.configure(connectionData);
        client.connect(config.connectAddress, config.channel);
    }

    private int clampVolume(int percent) {
        if (percent < MIN_VOLUME_PERCENT) {
            return MIN_VOLUME_PERCENT;
        }
        if (percent > MAX_VOLUME_PERCENT) {
            return MAX_VOLUME_PERCENT;
        }
        return percent;
    }

    private ConnectionDataFull buildConnectionData() {
        int desiredLevel = (int) Math.max(DEFAULT_IDENTITY_LEVEL, config.identityKeyOffset);
        if (desiredLevel > MAX_IDENTITY_LEVEL) {
            log.warn("Bot {} identity level {} too high, capping to {}", id, desiredLevel, MAX_IDENTITY_LEVEL);
            desiredLevel = MAX_IDENTITY_LEVEL;
        }
        IdentityData identity;
        if (config.identity != null && !config.identity.isBlank()) {
            identity = TsCrypt.loadIdentityDynamic(config.identity, 0);
        } else {
            identity = TsCrypt.generateNewIdentity(0);
        }
        long offset = Math.max(0L, config.identityOffset);
        int currentLevel = offset > 0 ? TsCrypt.computeSecurityLevel(identity, offset) : 0;
        TsCrypt.KeyOffsetResult result = null;
        if (offset <= 0 || currentLevel < desiredLevel) {
            result = TsCrypt.findKeyOffset(identity, desiredLevel, Math.max(0L, offset));
            offset = result.offset();
        }
        identity.setValidKeyOffset(offset);
        identity.setLastCheckedKeyOffset(offset);
        if (result != null && result.iterations() > 0) {
            log.info("Bot {} identity offset={} level={} iterations={}", id, result.offset(), result.level(), result.iterations());
        }
        TsVersionSigned version = TsVersionSigned.defaultForOs();
        if (config.clientVersionSign != null && !config.clientVersionSign.isBlank()) {
            version = TsVersionSigned.fromConfig(
                config.clientVersion,
                config.clientPlatform,
                config.clientVersionSign
            );
        }
        Password serverPassword = Password.fromPlain(config.serverPassword);
        Password channelPassword = Password.fromPlain(config.channelPassword);
        return new ConnectionDataFull(
            config.connectAddress,
            identity,
            version,
            config.nickname,
            serverPassword,
            config.channel,
            channelPassword,
            config.clientNicknamePhonetic,
            config.clientDefaultToken,
            config.clientHwid,
            id
        );
    }

    private void cancelTask(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(true);
        }
    }

    private long resolvePlaybackPosition() {
        Track track = currentTrack;
        if (track == null) {
            return 0L;
        }
        if (playbackPaused) {
            return Math.max(0L, playbackPositionMs);
        }
        long position = System.currentTimeMillis() - playbackStartedAt;
        if (!audioEngine.isPlaying()) {
            playbackPositionMs = Math.max(0L, position);
            return playbackPositionMs;
        }
        return Math.max(0L, position);
    }
}
