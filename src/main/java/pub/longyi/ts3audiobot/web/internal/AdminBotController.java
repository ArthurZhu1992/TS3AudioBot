package pub.longyi.ts3audiobot.web.internal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pub.longyi.ts3audiobot.bot.BotManager;
import pub.longyi.ts3audiobot.config.AppConfig;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.config.SqliteConfigStore;
import pub.longyi.ts3audiobot.queue.QueueService;
import pub.longyi.ts3audiobot.ts3.full.TsCrypt;

import java.util.List;
import java.util.Objects;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 AdminBotController 相关功能。
 */


/**
 * AdminBotController 相关功能。
 *
 * <p>职责：负责 AdminBotController 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@RestController
@RequestMapping("/internal/admin/bots")
@Slf4j
public final class AdminBotController {
    private static final int DEFAULT_IDENTITY_MIN_LEVEL = 100;
    private final SqliteConfigStore store;
    private final BotManager botManager;
    private final QueueService queueService;
    private final ConfigService configService;


    /**
     * 创建 AdminBotController 实例。
     * @param configService 参数 configService
     * @param botManager 参数 botManager
     * @param queueService 参数 queueService
     */
    public AdminBotController(ConfigService configService, BotManager botManager, QueueService queueService) {


        this.configService = configService;
        this.store = configService.getConfigStore();
        this.botManager = botManager;
        this.queueService = queueService;
    }


    /**
     * 执行 list 操作。
     * @return 返回值
     */
    @GetMapping
    public List<AppConfig.BotConfig> list() {
        return configService.loadBots();
    }


    /**
     * 执行 create 操作。
     * @param request 参数 request
     * @return 返回值
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody BotRequest request) {

        if (request == null || request.name == null || request.name.isBlank()) {
            return ResponseEntity.badRequest().body("Bot name is required");
        }
        String name = request.name.trim();
        if (store.getBot(name) != null) {
            return ResponseEntity.badRequest().body("Bot already exists");
        }
        AppConfig.BotConfig config = toConfig(request, name);
        store.upsertBot(config);
        botManager.upsertBot(config);
        return ResponseEntity.ok(config);
    }


    /**
     * 执行 update 操作。
     * @param name 参数 name
     * @param request 参数 request
     * @return 返回值
     */
    @PutMapping("/{name}")
    public ResponseEntity<?> update(@PathVariable String name, @RequestBody BotRequest request) {
        AppConfig.BotConfig existing = store.getBot(name);
        if (existing == null) {
            return ResponseEntity.notFound().build();

        }
        String newName = request == null || request.name == null || request.name.isBlank()
            ? name
            : request.name.trim();
        if (!Objects.equals(newName, name) && store.getBot(newName) != null) {
            return ResponseEntity.badRequest().body("Target bot name already exists");
        }
        AppConfig.BotConfig config = toConfig(request, newName);
        if (!Objects.equals(newName, name)) {
            store.deleteBot(name);
            store.upsertBot(config);
            queueService.renameBotId(name, newName);
            botManager.removeBot(name);
        } else {
            store.upsertBot(config);
        }
        botManager.upsertBot(config);
        return ResponseEntity.ok(config);
    }


    /**
     * 执行 delete 操作。
     * @param name 参数 name
     * @return 返回值
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<?> delete(@PathVariable String name) {
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body("Bot name is required");
        }
        boolean removed = store.deleteBot(name);
        botManager.removeBot(name);
        return removed ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    private AppConfig.BotConfig toConfig(BotRequest request, String name) {
        long requestedOffset = request.identityKeyOffset;
        int level = (int) Math.max(DEFAULT_IDENTITY_MIN_LEVEL, requestedOffset);
        String identity = request.identity == null ? "" : request.identity.trim();
        if (identity.isBlank()) {
            identity = TsCrypt.generateTsIdentity(level);
        } else {
            try {
                var loaded = TsCrypt.loadIdentityDynamic(identity, level);
                if (!TsCrypt.isTsIdentityFormat(identity)) {
                    identity = TsCrypt.exportTsIdentity(loaded);
                    log.info("Bot {} identity converted to standard format", name);
                }
                level = (int) Math.max(DEFAULT_IDENTITY_MIN_LEVEL, loaded.validKeyOffset());
                if (loaded.validKeyOffset() < DEFAULT_IDENTITY_MIN_LEVEL) {
                    identity = TsCrypt.generateTsIdentity(level);
                    log.warn("Bot {} identity level {} is too low, regenerated", name, loaded.validKeyOffset());
                }
            } catch (RuntimeException ex) {
                identity = TsCrypt.generateTsIdentity(level);
                log.warn("Bot {} identity invalid, regenerated", name, ex);
            }
        }
        String clientVersion = safeOrDefault(request.clientVersion, "3.6.2 [Build: 1695203293]");
        String clientPlatform = safeOrDefault(request.clientPlatform, "Windows");
        String clientVersionSign = safeOrDefault(
            request.clientVersionSign,
            "JEhuVodiWr2/F9mixBcaAZTtjx4Rs9cJDLbpEG8i7hPKswcFdsn6MWwINP+Nwmw4AEPpVJevUEvRQbqVMVoLlw=="
        );
        return new AppConfig.BotConfig(
            name,
            request.run,
            safe(request.connectAddress),
            safe(request.channel),
            safeOrDefault(request.nickname, "TS3AudioBot"),
            safe(request.serverPassword),
            safe(request.channelPassword),
            identity,
            level,
            clampVolumePercent(request.volumePercent),
            clientVersion,
            clientPlatform,
            clientVersionSign,
            safe(request.clientHwid),
            safe(request.clientNicknamePhonetic),
            safe(request.clientDefaultToken)
        );
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeOrDefault(String value, String fallback) {
        String trimmed = safe(value);
        return trimmed.isBlank() ? fallback : trimmed;
    }

    private int clampVolumePercent(int percent) {
        if (percent < 0) {
            return 100;
        }
        if (percent > 100) {
            return 100;
        }
        return percent;
    }


    /**
     * BotRequest 相关功能。
     *
     * <p>职责：负责 BotRequest 相关功能。</p>
     * <p>线程安全：无显式保证。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    public static final class BotRequest {
        public String name;
        public boolean run;
        public String connectAddress;
        public String channel;
        public String nickname;
        public String serverPassword;
        public String channelPassword;
        public String identity;
        public long identityKeyOffset = 8L;
        public int volumePercent = 100;
        public String clientVersion;
        public String clientPlatform;
        public String clientVersionSign;
        public String clientHwid;
        public String clientNicknamePhonetic;
        public String clientDefaultToken;
    }
}
