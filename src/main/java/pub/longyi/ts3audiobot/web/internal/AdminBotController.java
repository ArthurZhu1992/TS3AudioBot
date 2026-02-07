package pub.longyi.ts3audiobot.web.internal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pub.longyi.ts3audiobot.bot.BotManager;
import pub.longyi.ts3audiobot.config.AppConfig;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.config.SqliteConfigStore;
import pub.longyi.ts3audiobot.queue.QueueService;
import pub.longyi.ts3audiobot.ts3.full.ConnectionDataFull;
import pub.longyi.ts3audiobot.ts3.full.IdentityData;
import pub.longyi.ts3audiobot.ts3.full.Password;
import pub.longyi.ts3audiobot.ts3.full.TsCrypt;
import pub.longyi.ts3audiobot.ts3.full.TsFullClient;
import pub.longyi.ts3audiobot.ts3.full.TsVersionSigned;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private static final int DEFAULT_IDENTITY_MIN_LEVEL = 8;
    private static final int MAX_IDENTITY_LEVEL = 20;
    private static final int DEFAULT_PROBE_TIMEOUT_MS = 8000;
    private static final String IDENTITY_INVALID_MESSAGE = "could not validate client identity";
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
     * 执行 probeIdentityLevel 操作。
     * @param request 参数 request
     * @return 返回值
     */
    @PostMapping("/probe-identity-level")
    public ResponseEntity<?> probeIdentityLevel(@RequestBody IdentityLevelProbeRequest request) {
        if (request == null || request.connectAddress == null || request.connectAddress.isBlank()) {
            return ResponseEntity.badRequest().body("Server address is required");
        }
        int minLevel = clampIdentityLevel(request.minLevel, DEFAULT_IDENTITY_MIN_LEVEL);
        int maxLevel = clampIdentityLevel(request.maxLevel, MAX_IDENTITY_LEVEL);
        if (maxLevel < minLevel) {
            maxLevel = minLevel;
        }
        int timeoutMs = request.timeoutMs != null && request.timeoutMs > 0
            ? request.timeoutMs
            : DEFAULT_PROBE_TIMEOUT_MS;
        String identityRaw = safe(request.identity);
        if (identityRaw.isBlank()) {
            identityRaw = TsCrypt.generateNewIdentity(0).publicAndPrivateKeyString();
        }
        IdentityData identity;
        try {
            identity = TsCrypt.loadIdentityDynamic(identityRaw, 0);
        } catch (RuntimeException ex) {
            return ResponseEntity.badRequest().body("Identity is invalid");
        }
        String nickname = safeOrDefault(request.nickname, "TS3AudioBot");
        Password serverPassword = Password.fromPlain(request.serverPassword);
        Password channelPassword = Password.fromPlain(request.channelPassword);
        TsVersionSigned version = TsVersionSigned.defaultForOs();

        long offset = 0L;
        for (int level = minLevel; level <= maxLevel; level++) {
            TsCrypt.KeyOffsetResult result = TsCrypt.findKeyOffset(identity, level, Math.max(0L, offset));
            offset = result.offset();
            identity.setValidKeyOffset(offset);
            identity.setLastCheckedKeyOffset(offset);
            ProbeOutcome outcome = probeServerOnce(
                request.connectAddress,
                safe(request.channel),
                nickname,
                serverPassword,
                channelPassword,
                version,
                identity,
                timeoutMs
            );
            if (outcome.success) {
                return ResponseEntity.ok(new IdentityLevelProbeResponse(
                    level,
                    result.level(),
                    offset,
                    outcome.message
                ));
            }
            if (outcome.identityInvalid) {
                continue;
            }
            String message = outcome.message == null || outcome.message.isBlank()
                ? "Probe failed"
                : outcome.message;
            return ResponseEntity.badRequest().body(message);
        }
        return ResponseEntity.badRequest().body("Server requires a higher security level");
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
        long identityOffset = 0L;
        if (identity.isBlank()) {
            identity = generateIdentityString(level);
        } else {
            try {
                var loaded = TsCrypt.loadIdentityDynamic(identity, level);
                identity = loaded.publicAndPrivateKeyString();
                level = (int) Math.max(DEFAULT_IDENTITY_MIN_LEVEL, loaded.validKeyOffset());
                if (loaded.validKeyOffset() < DEFAULT_IDENTITY_MIN_LEVEL) {
                    identity = generateIdentityString(level);
                    log.warn("Bot {} identity level {} is too low, regenerated", name, loaded.validKeyOffset());
                }
            } catch (RuntimeException ex) {
                identity = generateIdentityString(level);
                log.warn("Bot {} identity invalid, regenerated", name, ex);
            }
        }
        try {
            var loaded = TsCrypt.loadIdentityDynamic(identity, 0);
            TsCrypt.KeyOffsetResult result = TsCrypt.findKeyOffset(loaded, level, 0);
            identityOffset = result.offset();
            if (result.iterations() > 0) {
                log.info("Bot {} identity offset={} level={} iterations={}", name, result.offset(), result.level(), result.iterations());
            }
        } catch (RuntimeException ex) {
            log.warn("Bot {} failed to compute identity offset, using 0", name, ex);
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
            identityOffset,
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

    private String generateIdentityString(int level) {
        return TsCrypt.generateNewIdentity(level).publicAndPrivateKeyString();
    }

    private ProbeOutcome probeServerOnce(
        String address,
        String channel,
        String nickname,
        Password serverPassword,
        Password channelPassword,
        TsVersionSigned version,
        IdentityData identity,
        int timeoutMs
    ) {
        TsFullClient client = new TsFullClient();
        CompletableFuture<ProbeOutcome> future = new CompletableFuture<>();
        client.setLoginListener(() -> completeProbe(future, new ProbeOutcome(true, false, "ok")));
        client.setStopListener(reason -> completeProbe(
            future,
            new ProbeOutcome(false, false, "connection closed: " + reason)
        ));
        client.setErrorListener(cmd -> {
            String msg = cmd == null || cmd.params() == null ? "" : cmd.params().get("msg");
            String lower = msg == null ? "" : msg.toLowerCase();
            if (lower.contains(IDENTITY_INVALID_MESSAGE)) {
                completeProbe(future, new ProbeOutcome(false, true, msg));
            } else if (!msg.isBlank()) {
                completeProbe(future, new ProbeOutcome(false, false, msg));
            }
        });
        ConnectionDataFull data = new ConnectionDataFull(
            address,
            identity,
            version,
            nickname,
            serverPassword,
            channel,
            channelPassword,
            "",
            "",
            "",
            "probe"
        );
        client.configure(data);
        client.connect(address, channel);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            return new ProbeOutcome(false, false, "timeout");
        } catch (Exception ex) {
            return new ProbeOutcome(false, false, "probe failed");
        } finally {
            client.disconnect();
        }
    }

    private void completeProbe(CompletableFuture<ProbeOutcome> future, ProbeOutcome outcome) {
        if (future == null || outcome == null || future.isDone()) {
            return;
        }
        future.complete(outcome);
    }

    private int clampIdentityLevel(Integer level, int fallback) {
        int value = level == null ? fallback : level;
        if (value < DEFAULT_IDENTITY_MIN_LEVEL) {
            return DEFAULT_IDENTITY_MIN_LEVEL;
        }
        if (value > MAX_IDENTITY_LEVEL) {
            return MAX_IDENTITY_LEVEL;
        }
        return value;
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

    private static final class ProbeOutcome {
        private final boolean success;
        private final boolean identityInvalid;
        private final String message;

        private ProbeOutcome(boolean success, boolean identityInvalid, String message) {
            this.success = success;
            this.identityInvalid = identityInvalid;
            this.message = message;
        }
    }

    public static final class IdentityLevelProbeRequest {
        public String connectAddress;
        public String serverPassword;
        public String channel;
        public String channelPassword;
        public String nickname;
        public String identity;
        public Integer minLevel;
        public Integer maxLevel;
        public Integer timeoutMs;
    }

    public static final class IdentityLevelProbeResponse {
        public final int level;
        public final int actualLevel;
        public final long identityOffset;
        public final String message;

        public IdentityLevelProbeResponse(int level, int actualLevel, long identityOffset, String message) {
            this.level = level;
            this.actualLevel = actualLevel;
            this.identityOffset = identityOffset;
            this.message = message;
        }
    }
}
