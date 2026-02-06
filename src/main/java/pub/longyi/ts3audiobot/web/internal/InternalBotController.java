package pub.longyi.ts3audiobot.web.internal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pub.longyi.ts3audiobot.bot.BotInstance;
import pub.longyi.ts3audiobot.bot.BotManager;
import pub.longyi.ts3audiobot.bot.PlaybackMode;
import pub.longyi.ts3audiobot.bot.BotStatus;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.queue.Track;

import java.util.Collection;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 InternalBotController 相关功能。
 */


/**
 * InternalBotController 相关功能。
 *
 * <p>职责：负责 InternalBotController 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@RestController
@RequestMapping("/internal/bots")
public final class InternalBotController {
    private final BotManager botManager;
    private final pub.longyi.ts3audiobot.queue.QueueService queueService;
    private final ConfigService configService;


    /**
     * 创建 InternalBotController 实例。
     * @param botManager 参数 botManager
     * @param queueService 参数 queueService
     * @param configService 参数 configService
     */
    public InternalBotController(
        BotManager botManager,
        pub.longyi.ts3audiobot.queue.QueueService queueService,
        ConfigService configService
    ) {
        this.botManager = botManager;
        this.queueService = queueService;
        this.configService = configService;
    }


    /**
     * 执行 list 操作。
     * @return 返回值
     */
    @GetMapping
    public Collection<BotInstance> list() {
        return botManager.list();
    }


    /**
     * 执行 start 操作。
     * @param botId 参数 botId
     * @return 返回值
     */
    @PostMapping("/{botId}/start")
    public ResponseEntity<?> start(@PathVariable String botId) {
        BotInstance bot = botManager.get(botId);

        if (bot == null) {
            return ResponseEntity.notFound().build();
        }
        bot.startPlayback();
        return ResponseEntity.ok().build();
    }


    /**
     * 执行 stop 操作。
     * @param botId 参数 botId
     * @return 返回值
     */
    @PostMapping("/{botId}/stop")
    public ResponseEntity<?> stop(@PathVariable String botId) {
        BotInstance bot = botManager.get(botId);
        if (bot == null) {
            return ResponseEntity.notFound().build();
        }

        bot.pausePlayback();
        return ResponseEntity.ok().build();
    }


    /**
     * 执行 next 操作。
     * @param botId 参数 botId
     * @return 返回值
     */
    @PostMapping("/{botId}/next")
    public ResponseEntity<?> next(@PathVariable String botId) {
        BotInstance bot = botManager.get(botId);

        if (bot == null) {
            return ResponseEntity.notFound().build();
        }
        bot.playNextForced();
        return ResponseEntity.ok().build();
    }


    /**
     * 执行 prev 操作。
     * @param botId 参数 botId
     * @return 返回值
     */
    @PostMapping("/{botId}/prev")
    public ResponseEntity<?> prev(@PathVariable String botId) {
        BotInstance bot = botManager.get(botId);
        if (bot == null) {
            return ResponseEntity.notFound().build();
        }
        queueService.stepBack(botId);
        bot.playNextForced();

        return ResponseEntity.ok().build();
    }


    /**
     * 执行 playNow 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @param itemId 参数 itemId
     * @return 返回值
     */
    @PostMapping("/{botId}/play/{playlistId}/{itemId}")
    public ResponseEntity<?> playNow(
        @PathVariable String botId,
        @PathVariable String playlistId,
        @PathVariable String itemId
    ) {
        BotInstance bot = botManager.get(botId);
        if (bot == null) {
            return ResponseEntity.notFound().build();
        }
        boolean jumped = queueService.jumpTo(botId, playlistId, itemId);
        if (!jumped) {
            return ResponseEntity.badRequest().body("Queue item not found");
        }
        if (bot.status() == BotStatus.STOPPED || bot.status() == BotStatus.ERROR) {
            bot.startPlayback();
            return ResponseEntity.ok().build();
        }
        bot.playNextForced();
        return ResponseEntity.ok().build();
    }


    /**
     * 执行 seek 操作。
     * @param botId 参数 botId
     * @param request 参数 request
     * @return 返回值
     */
    @PostMapping("/{botId}/seek")
    public ResponseEntity<?> seek(@PathVariable String botId, @RequestBody SeekRequest request) {
        BotInstance bot = botManager.get(botId);
        if (bot == null) {
            return ResponseEntity.notFound().build();
        }
        if (request == null) {
            return ResponseEntity.badRequest().body("Position is required");
        }
        bot.seekPlayback(request.positionMs());
        return ResponseEntity.ok().build();
    }


    /**
     * 执行 playback 操作。
     * @param botId 参数 botId
     * @return 返回值
     */
    @GetMapping("/{botId}/playback")
    public ResponseEntity<?> playback(@PathVariable String botId) {
        BotInstance bot = botManager.get(botId);
        if (bot == null) {
            return ResponseEntity.notFound().build();

        }
        Track track = bot.currentTrack();
        return ResponseEntity.ok(new PlaybackState(
            bot.status().name(),
            bot.isPlaying(),
            bot.isPlaybackPaused(),

            bot.playbackPositionMs(),
            bot.playbackDurationMs(),
            bot.volumePercent(),
            track == null ? "" : track.title(),
            track == null ? "" : track.sourceType()
        ));
    }


    /**
     * 执行 setVolume 操作。
     * @param botId 参数 botId
     * @param request 参数 request
     * @return 返回值
     */
    @PostMapping("/{botId}/volume")
    public ResponseEntity<?> setVolume(@PathVariable String botId, @RequestBody VolumeRequest request) {
        BotInstance bot = botManager.get(botId);
        if (bot == null) {
            return ResponseEntity.notFound().build();
        }
        if (request == null) {
            return ResponseEntity.badRequest().body("Volume is required");
        }
        int percent = request.volumePercent();
        if (percent < 0 || percent > 100) {
            return ResponseEntity.badRequest().body("Volume must be between 0 and 100");
        }
        bot.setVolumePercent(percent);
        configService.updateBotVolume(bot.name(), percent);
        return ResponseEntity.ok().build();
    }


    /**
     * 执行 mode 操作。
     * @param botId 参数 botId
     * @return 返回值
     */
    @GetMapping("/{botId}/mode")
    public ResponseEntity<?> mode(@PathVariable String botId) {
        BotInstance bot = botManager.get(botId);
        if (bot == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(bot.playbackMode().name().toLowerCase());
    }


    /**
     * 执行 setMode 操作。
     * @param botId 参数 botId
     * @param mode 参数 mode
     * @return 返回值
     */
    @PostMapping("/{botId}/mode/{mode}")
    public ResponseEntity<?> setMode(@PathVariable String botId, @PathVariable String mode) {
        BotInstance bot = botManager.get(botId);
        if (bot == null) {
            return ResponseEntity.notFound().build();
        }
        bot.setPlaybackMode(PlaybackMode.from(mode));
        return ResponseEntity.ok().build();
    }


    /**
     * 执行 SeekRequest 操作。
     * @param positionMs 参数 positionMs
     * @return 返回值
     */
    public record SeekRequest(long positionMs) {
    }


    /**
     * 执行 PlaybackState 操作。
     * @param status 参数 status
     * @param playing 参数 playing
     * @param paused 参数 paused
     * @param positionMs 参数 positionMs
     * @param durationMs 参数 durationMs
     * @param volumePercent 参数 volumePercent
     * @param title 参数 title
     * @param sourceType 参数 sourceType
     * @return 返回值
     */
    public record PlaybackState(
        String status,
        boolean playing,
        boolean paused,
        long positionMs,
        long durationMs,
        int volumePercent,
        String title,
        String sourceType
    ) {
    }


    /**
     * 执行 VolumeRequest 操作。
     * @param volumePercent 参数 volumePercent
     * @return 返回值
     */
    public record VolumeRequest(int volumePercent) {
    }
}
