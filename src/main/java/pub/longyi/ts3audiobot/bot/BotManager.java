package pub.longyi.ts3audiobot.bot;

import org.springframework.stereotype.Service;
import pub.longyi.ts3audiobot.audio.FfmpegAudioEngine;
import pub.longyi.ts3audiobot.config.AppConfig;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.queue.QueueService;
import pub.longyi.ts3audiobot.ts3.Ts3ClientFactory;
import pub.longyi.ts3audiobot.util.IdGenerator;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import jakarta.annotation.PreDestroy;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 BotManager 相关功能。
 */


/**
 * BotManager 相关功能。
 *
 * <p>职责：负责 BotManager 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Service
public final class BotManager {
    private final Map<String, BotInstance> bots = new ConcurrentHashMap<>();
    private final ConfigService configService;
    private final Ts3ClientFactory ts3ClientFactory;
    private final QueueService queueService;

    /**
     * 创建 BotManager 实例。
     * @param configService 参数 configService
     * @param ts3ClientFactory 参数 ts3ClientFactory
     * @param queueService 参数 queueService
     */
    public BotManager(ConfigService configService, Ts3ClientFactory ts3ClientFactory, QueueService queueService) {
        this.configService = configService;
        this.ts3ClientFactory = ts3ClientFactory;
        this.queueService = queueService;
    }


    /**
     * 执行 initFromConfig 操作。
     */
    public void initFromConfig() {
        for (AppConfig.BotConfig botConfig : configService.loadBots()) {
            BotInstance instance = createBot(botConfig);
            if (botConfig.run) {
                instance.start();
            }
        }
        if (bots.size() == 1) {
            BotInstance onlyBot = bots.values().iterator().next();
            List<String> storedIds = queueService.listStoredBotIds();
            if (storedIds.size() == 1 && !storedIds.contains(onlyBot.id())) {
                queueService.renameBotId(storedIds.get(0), onlyBot.id());
            }
        }
    }


    /**
     * 执行 createBot 操作。
     * @param botConfig 参数 botConfig
     * @return 返回值
     */
    public BotInstance createBot(AppConfig.BotConfig botConfig) {
        String id = resolveBotId(botConfig);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        var voiceClient = ts3ClientFactory.create();
        BotInstance instance = new BotInstance(
            id,
            botConfig,
            voiceClient,
            new FfmpegAudioEngine(configService, voiceClient),
            queueService,
            scheduler
        );
        bots.put(id, instance);
        return instance;
    }


    /**
     * 执行 upsertBot 操作。
     * @param botConfig 参数 botConfig
     * @return 返回值
     */
    public synchronized BotInstance upsertBot(AppConfig.BotConfig botConfig) {
        if (botConfig == null) {
            return null;
        }
        String id = resolveBotId(botConfig);
        BotInstance existing = bots.get(id);
        boolean shouldStart = botConfig.run;
        if (existing != null) {
            existing.stop();
            bots.remove(id);
        }
        BotInstance instance = createBot(botConfig);

        if (shouldStart) {
            instance.start();
        }
        return instance;
    }


    /**
     * 执行 removeBot 操作。
     * @param id 参数 id
     * @return 返回值
     */
    public synchronized boolean removeBot(String id) {
        BotInstance bot = bots.remove(id);
        if (bot == null) {
            return false;
        }
        bot.stop();
        return true;
    }

    private String resolveBotId(AppConfig.BotConfig botConfig) {
        String candidate = botConfig == null ? "" : botConfig.name;
        if (candidate != null) {
            candidate = candidate.trim();
        }
        if (candidate == null || candidate.isBlank()) {
            return IdGenerator.newId();
        }
        String base = candidate;
        String resolved = base;

        int suffix = 2;
        while (bots.containsKey(resolved)) {
            resolved = base + "-" + suffix;
            suffix++;

        }
        return resolved;


    }


    /**
     * 执行 list 操作。
     * @return 返回值
     */
    public Collection<BotInstance> list() {
        return bots.values();

    }


    /**
     * 执行 get 操作。
     * @param id 参数 id
     * @return 返回值
     */
    public BotInstance get(String id) {

        return bots.get(id);
    }


    /**
     * 执行 stop 操作。
     * @param id 参数 id
     */
    public void stop(String id) {
        BotInstance bot = bots.get(id);
        if (bot != null) {
            bot.stop();
        }
    }


    /**
     * 执行 shutdown 操作。
     */
    @PreDestroy
    public void shutdown() {
        for (BotInstance bot : bots.values()) {
            bot.shutdown();
        }
    }
}
