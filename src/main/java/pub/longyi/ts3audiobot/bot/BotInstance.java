package pub.longyi.ts3audiobot.bot;

import lombok.extern.slf4j.Slf4j;
import pub.longyi.ts3audiobot.audio.AudioEngine;
import pub.longyi.ts3audiobot.config.AppConfig;
import pub.longyi.ts3audiobot.media.TrackMediaService;
import pub.longyi.ts3audiobot.queue.QueueService;
import pub.longyi.ts3audiobot.queue.QueueItem;
import pub.longyi.ts3audiobot.queue.Track;
import pub.longyi.ts3audiobot.shuffle.ShufflePlaybackService;
import pub.longyi.ts3audiobot.ts3.Ts3VoiceClient;
import pub.longyi.ts3audiobot.ts3.full.ConnectionDataFull;
import pub.longyi.ts3audiobot.ts3.full.Password;
import pub.longyi.ts3audiobot.ts3.full.TsCrypt;
import pub.longyi.ts3audiobot.ts3.full.TsVersionSigned;
import pub.longyi.ts3audiobot.ts3.full.IdentityData;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    private final boolean avatarSyncEnabled;
    private final Ts3VoiceClient client;
    private final AudioEngine audioEngine;
    private final TrackMediaService trackMediaService;
    private final QueueService queueService;
    private final ShufflePlaybackService shufflePlaybackService;
    private final ScheduledExecutorService scheduler;
    private final java.util.Random random = new java.util.Random();
    private final ExecutorService profileExecutor;
    private final AtomicLong profileRevision = new AtomicLong();
    private final String baseBotName;

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
    private volatile String currentItemId;
    private volatile String currentPlaylistId;
    private volatile long playbackStartedAt;
    private volatile long playbackPositionMs;
    private volatile boolean trackDisplayActive;
    private volatile String appliedNickname = "";
    private volatile String appliedAvatarTrackId = "";
    private volatile boolean wasConnected;

    private static final long RECONNECT_BASE_MS = 2000L;
    private static final long RECONNECT_MAX_MS = 30_000L;
    private static final long CONNECT_TIMEOUT_MS = 35_000L;
    private static final int DEFAULT_VOLUME_PERCENT = 100;
    private static final int MIN_VOLUME_PERCENT = 0;
    private static final int MAX_VOLUME_PERCENT = 100;
    private static final int DEFAULT_IDENTITY_LEVEL = 8;
    private static final int MAX_IDENTITY_LEVEL = 20;
    private static final int MAX_NICKNAME_LENGTH = 30;
    private static final String NOW_PLAYING_PREFIX = "♪ ";
    private static final String NOW_PLAYING_SEPARATOR = " | ";

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
        boolean avatarSyncEnabled,
        Ts3VoiceClient client,
        AudioEngine audioEngine,
        TrackMediaService trackMediaService,
        QueueService queueService,
        ShufflePlaybackService shufflePlaybackService,
        ScheduledExecutorService scheduler
    ) {
        this.id = id;
        this.config = config;
        this.avatarSyncEnabled = avatarSyncEnabled;
        this.client = client;
        this.audioEngine = audioEngine;
        this.trackMediaService = trackMediaService;
        this.queueService = queueService;
        this.shufflePlaybackService = shufflePlaybackService;
        this.scheduler = scheduler;
        this.baseBotName = resolveBaseBotName(config, id);
        this.profileExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bot-profile-" + id);
            thread.setDaemon(true);
            return thread;
        });
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
    public synchronized void setPlaybackMode(PlaybackMode playbackMode) {
        PlaybackMode resolved = playbackMode == null ? PlaybackMode.ORDER : playbackMode;
        PlaybackMode previous = this.playbackMode;
        this.playbackMode = resolved;
        if (resolved == PlaybackMode.RANDOM && previous != PlaybackMode.RANDOM) {
            String playlistId = queueService.getActivePlaylist(id);
            List<QueueItem> queue = queueService.rawList(id, playlistId);
            shufflePlaybackService.rebuild(id, playlistId, queue, random, currentItemId);
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
     * 返回当前是否已和 TS3 服务端建立连接。
     */
    public boolean isConnected() {
        return client.isConnected();
    }

    /**
     * 返回当前是否处于连接尝试流程中。
     */
    public boolean isConnectInProgress() {
        return connectInProgress;
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

    public String currentItemId() {
        return currentItemId;
    }

    public String currentPlaylistId() {
        return currentPlaylistId;
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
        // 手动重连时重置退避状态，避免沿用上一次失败后的长等待窗口。
        connectInProgress = false;
        connectAttemptAt = 0L;
        nextReconnectAt = 0L;
        reconnectDelayMs = RECONNECT_BASE_MS;
        playbackPaused = false;
        trackDisplayActive = false;
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
            scheduleClientProfileSync();
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
        trackDisplayActive = false;
        playbackPositionMs = 0L;
        currentTrack = null;
        currentItemId = null;
        currentPlaylistId = null;
        appliedNickname = "";
        appliedAvatarTrackId = "";
        wasConnected = false;
        audioEngine.stop();
        cancelTask(playbackTask);
        cancelTask(connectionTask);
        connectInProgress = false;
        connectAttemptAt = 0L;
        nextReconnectAt = 0L;
        reconnectDelayMs = RECONNECT_BASE_MS;
        client.disconnect();
        status = BotStatus.STOPPED;
    }


    /**
     * 执行 pausePlayback 操作。
     */
    public synchronized void pausePlayback() {
        playbackPositionMs = resolvePlaybackPosition();
        playbackPaused = true;
        trackDisplayActive = false;
        audioEngine.stop();
        scheduleClientProfileSync();
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
            currentTrack = prepareTrackForPlayback(currentPlaylistId, currentItemId, currentTrack);
            long resumeAt = Math.max(0L, playbackPositionMs);
            playbackStartedAt = System.currentTimeMillis() - resumeAt;
            trackDisplayActive = true;
            audioEngine.play(currentTrack);
            if (resumeAt > 0L) {
                audioEngine.seek(resumeAt);
            }
            scheduleClientProfileSync();
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
        profileExecutor.shutdownNow();
        scheduler.shutdownNow();
    }


    /**
     * 执行 playNext 操作。
     */
    public void playNext() {
        playNext(queueService.getActivePlaylist(id), false);
    }


    /**
     * 执行 playNextForced 操作。
     */
    public void playNextForced() {
        playNext(queueService.getActivePlaylist(id), true);
    }

    /**
     * 随机模式下按随机序列回退；非随机模式维持原有上一首语义。
     */
    public synchronized void playPrevious() {
        String playlistId = queueService.getActivePlaylist(id);
        if (playbackMode != PlaybackMode.RANDOM) {
            queueService.stepBack(id, playlistId);
            playNext(playlistId, true);
            return;
        }
        List<QueueItem> queue = queueService.rawList(id, playlistId);
        QueueItem resolved = shufflePlaybackService.previous(id, playlistId, queue, random, currentItemId);
        if (resolved == null) {
            if (currentTrack != null) {
                seekPlayback(0L);
            }
            return;
        }
        startPlaybackFromQueueItem(playlistId, resolved, true);
    }

    public synchronized boolean handleRemovedQueueItem(String playlistId, String itemId) {
        if (!Objects.equals(currentItemId, itemId) || !Objects.equals(currentPlaylistId, playlistId)) {
            return false;
        }
        boolean shouldContinue = status == BotStatus.RUNNING && !playbackPaused;
        audioEngine.stop();
        playbackPositionMs = 0L;
        playbackStartedAt = 0L;
        currentTrack = null;
        currentItemId = null;
        currentPlaylistId = null;
        trackDisplayActive = false;
        if (shouldContinue) {
            playNext(playlistId, playbackMode != PlaybackMode.RANDOM);
        } else {
            scheduleClientProfileSync();
        }
        return true;
    }

    private void playNext(String playlistId, boolean forceOrder) {
        String resolvedPlaylistId = playlistId;
        if (resolvedPlaylistId == null || resolvedPlaylistId.isBlank()) {
            resolvedPlaylistId = queueService.getActivePlaylist(id);
        }
        PlaybackMode mode = forceOrder ? PlaybackMode.ORDER : playbackMode;
        QueueItem resolved = switch (mode) {
            case RANDOM -> {
                List<QueueItem> queue = queueService.rawList(id, resolvedPlaylistId);
                yield shufflePlaybackService.next(id, resolvedPlaylistId, queue, random, currentItemId);
            }
            case LOOP -> queueService.nextLoop(id, resolvedPlaylistId);
            case LIST_LOOP -> queueService.nextListLoop(id, resolvedPlaylistId);
            default -> queueService.next(id, resolvedPlaylistId);
        };
        if (resolved == null) {
            boolean changed = trackDisplayActive;
            trackDisplayActive = false;
            if (changed) {
                scheduleClientProfileSync();
            }
            return;
        }
        startPlaybackFromQueueItem(resolvedPlaylistId, resolved, mode == PlaybackMode.RANDOM);
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
            if (!wasConnected) {
                wasConnected = true;
                appliedNickname = "";
                scheduleClientProfileSync();
            }
            connectInProgress = false;
            reconnectDelayMs = RECONNECT_BASE_MS;
            return;
        }
        if (wasConnected) {
            wasConnected = false;
            appliedNickname = "";
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

    private Track prepareTrackForPlayback(String playlistId, String itemId, Track track) {
        Track baseTrack = track;
        if (itemId != null && !itemId.isBlank()) {
            QueueItem refreshed = queueService.refreshItem(id, playlistId, itemId);
            if (refreshed != null && refreshed.track() != null) {
                baseTrack = refreshed.track();
            }
        }
        if (baseTrack == null) {
            return null;
        }
        Track prepared = trackMediaService.prepareForPlayback(id, baseTrack);
        if (itemId != null
            && !itemId.isBlank()
            && !prepared.equals(baseTrack)
            && trackMediaService.shouldPersistPreparedTrack(baseTrack, prepared)) {
            queueService.updateTrack(id, playlistId, itemId, prepared);
        }
        return prepared;
    }

    private void startPlaybackFromQueueItem(String playlistId, QueueItem item, boolean syncQueueCursor) {
        if (item == null) {
            return;
        }
        playbackPaused = false;
        trackDisplayActive = true;
        currentItemId = item.id();
        currentPlaylistId = playlistId;
        currentTrack = prepareTrackForPlayback(playlistId, item.id(), item.track());
        playbackPositionMs = 0L;
        playbackStartedAt = System.currentTimeMillis();
        audioEngine.play(currentTrack);
        if (syncQueueCursor) {
            // 随机模式由独立状态机选歌，需要额外同步队列游标，保证前端 currentIndex 一致。
            queueService.jumpTo(id, playlistId, item.id());
            queueService.next(id, playlistId);
        }
        scheduleClientProfileSync();
    }

    private void scheduleClientProfileSync() {
        long revision = profileRevision.incrementAndGet();
        log.info("Bot {} schedule profile sync revision={} connected={} paused={} trackActive={}",
            id,
            revision,
            client.isConnected(),
            playbackPaused,
            trackDisplayActive
        );
        profileExecutor.execute(() -> applyClientProfile(revision));
    }

    private void applyClientProfile(long revision) {
        if (revision != profileRevision.get()) {
            log.info("Bot {} skip profile sync revision={} reason=stale latest={}", id, revision, profileRevision.get());
            return;
        }
        if (!client.isConnected()) {
            log.info("Bot {} skip profile sync revision={} reason=client_not_connected", id, revision);
            return;
        }
        Track track = currentTrack;
        boolean nowPlaying = shouldShowNowPlaying(track);
        String targetNickname = nowPlaying ? buildNowPlayingNickname(track) : baseBotName;
        if (targetNickname.isBlank()) {
            log.info("Bot {} skip profile sync revision={} reason=blank_nickname", id, revision);
            return;
        }
        if (revision != profileRevision.get()) {
            log.info("Bot {} skip profile sync revision={} reason=stale_before_apply latest={}",
                id,
                revision,
                profileRevision.get()
            );
            return;
        }
        if (!Objects.equals(targetNickname, appliedNickname) && client.updateClientNickname(targetNickname)) {
            appliedNickname = targetNickname;
            log.info("Bot {} profile nickname updated revision={} nickname={}", id, revision, targetNickname);
        } else if (!Objects.equals(targetNickname, appliedNickname)) {
            log.warn("Bot {} profile nickname update failed revision={} nickname={}", id, revision, targetNickname);
        } else {
            log.info("Bot {} profile nickname unchanged revision={} nickname={}", id, revision, targetNickname);
        }
        // 头像同步允许单独关闭；关闭后仅保留昵称同步，避免触发文件传输链路问题。
        if (!avatarSyncEnabled) {
            log.info("Bot {} skip avatar update revision={} reason=avatar_sync_disabled", id, revision);
            return;
        }
        if (!nowPlaying || track == null || track.id() == null || track.id().isBlank()) {
            log.info("Bot {} skip avatar update revision={} reason=no_playing_track", id, revision);
            return;
        }
        String trackId = track.id().trim();
        if (Objects.equals(trackId, appliedAvatarTrackId)) {
            log.info("Bot {} skip avatar update revision={} reason=same_track trackId={}", id, revision, trackId);
            return;
        }
        Optional<Path> coverFile = trackMediaService.findCoverFile(trackId);
        appliedAvatarTrackId = trackId;
        if (coverFile.isPresent() && revision == profileRevision.get()) {
            boolean updated = client.updateClientAvatar(coverFile.get());
            if (updated) {
                log.info("Bot {} profile avatar updated revision={} trackId={} file={}",
                    id,
                    revision,
                    trackId,
                    coverFile.get()
                );
            } else {
                log.warn("Bot {} profile avatar update failed revision={} trackId={} file={}",
                    id,
                    revision,
                    trackId,
                    coverFile.get()
                );
            }
        } else if (coverFile.isEmpty()) {
            log.info("Bot {} skip avatar update revision={} reason=cover_missing trackId={}", id, revision, trackId);
        } else {
            log.info("Bot {} skip avatar update revision={} reason=stale_after_cover latest={}",
                id,
                revision,
                profileRevision.get()
            );
        }
    }

    private boolean shouldShowNowPlaying(Track track) {
        return status == BotStatus.RUNNING && !playbackPaused && trackDisplayActive && track != null;
    }

    private String buildNowPlayingNickname(Track track) {
        if (track == null) {
            return baseBotName;
        }
        String title = firstNonBlank(track.title(), "Unknown");
        String artist = firstNonBlank(track.artist(), "");
        String song = artist.isBlank() ? title : title + " - " + artist;
        String suffix = NOW_PLAYING_SEPARATOR + baseBotName;
        int available = MAX_NICKNAME_LENGTH - NOW_PLAYING_PREFIX.length() - suffix.length();
        if (available <= 0) {
            return trimToLength(baseBotName, MAX_NICKNAME_LENGTH);
        }
        String clippedSong = trimToLength(song, available);
        return NOW_PLAYING_PREFIX + clippedSong + suffix;
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private static String resolveBaseBotName(AppConfig.BotConfig config, String fallbackId) {
        if (config != null && config.name != null && !config.name.isBlank()) {
            return config.name.trim();
        }
        if (config != null && config.nickname != null && !config.nickname.isBlank()) {
            return config.nickname.trim();
        }
        return fallbackId == null ? "TS3AudioBot" : fallbackId;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
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
