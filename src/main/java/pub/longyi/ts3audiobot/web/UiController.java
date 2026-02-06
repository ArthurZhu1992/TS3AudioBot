package pub.longyi.ts3audiobot.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import pub.longyi.ts3audiobot.bot.BotManager;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.config.AppConfig;
import java.util.List;
import java.util.Map;
import pub.longyi.ts3audiobot.queue.QueueService;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 UiController 相关功能。
 */


/**
 * UiController 相关功能。
 *
 * <p>职责：负责 UiController 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Controller
public final class UiController {
    private final BotManager botManager;
    private final QueueService queueService;
    private final ConfigService configService;


    /**
     * 创建 UiController 实例。
     * @param botManager 参数 botManager
     * @param queueService 参数 queueService
     * @param configService 参数 configService
     */
    public UiController(BotManager botManager, QueueService queueService, ConfigService configService) {
        this.botManager = botManager;
        this.queueService = queueService;
        this.configService = configService;
    }


    /**
     * 执行 index 操作。
     * @param model 参数 model
     * @return 返回值
     */
    @GetMapping("/")
    public String index(Model model) {
        List<AppConfig.BotConfig> bots = configService.loadBots();
        Map<String, String> statuses = new java.util.HashMap<>();
        for (AppConfig.BotConfig bot : bots) {
            String name = bot.name;
            var instance = botManager.get(name);
            if (instance != null) {
                statuses.put(name, instance.status().toString());
            } else {
                statuses.put(name, "STOPPED");
            }
        }
        model.addAttribute("bots", bots);
        model.addAttribute("statuses", statuses);
        return "index";
    }


    /**
     * 执行 player 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @param model 参数 model
     * @return 返回值
     */
    @GetMapping("/player")
    public String player(
        @RequestParam(required = false) String botId,
        @RequestParam(required = false) String playlistId,
        Model model
    ) {
        model.addAttribute("bots", botManager.list());
        if (botId == null || botId.isBlank()) {
            model.addAttribute("botId", "");
            model.addAttribute("bot", null);
            model.addAttribute("activePlaylist", "default");
            model.addAttribute("queue", List.of());
            model.addAttribute("playlists", List.of("default"));
            model.addAttribute("playlistId", "default");
            model.addAttribute("currentIndex", -1);
            return "player";
        }
        model.addAttribute("botId", botId);
        model.addAttribute("bot", botManager.get(botId));
        String activePlaylist = queueService.getActivePlaylist(botId);
        model.addAttribute("activePlaylist", activePlaylist);
        String selected = playlistId == null || playlistId.isBlank() ? activePlaylist : playlistId;
        if (!queueService.hasPlaylist(botId, selected)) {
            selected = activePlaylist;
        }

        model.addAttribute("queue", queueService.list(botId, selected));
        model.addAttribute("playlists", queueService.listPlaylists(botId));
        model.addAttribute("playlistId", selected);
        int currentIndex = Math.max(-1, queueService.getPosition(botId, selected) - 1);
        model.addAttribute("currentIndex", currentIndex);
        return "player";
    }


    /**
     * 执行 queue 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @param model 参数 model
     * @return 返回值
     */
    @GetMapping("/queue")
    public String queue(
        @RequestParam(required = false) String botId,
        @RequestParam(required = false) String playlistId,
        Model model
    ) {
        model.addAttribute("bots", botManager.list());
        if (botId == null || botId.isBlank()) {
            model.addAttribute("botId", "");
            model.addAttribute("bot", null);
            model.addAttribute("activePlaylist", "default");
            model.addAttribute("queue", List.of());
            model.addAttribute("playlists", List.of("default"));
            model.addAttribute("playlistId", "default");
            model.addAttribute("currentIndex", -1);

            return "player";
        }
        model.addAttribute("botId", botId);
        model.addAttribute("bot", botManager.get(botId));
        String activePlaylist = queueService.getActivePlaylist(botId);
        String selected = playlistId == null || playlistId.isBlank() ? activePlaylist : playlistId;
        if (!queueService.hasPlaylist(botId, selected)) {
            selected = activePlaylist;
        }
        model.addAttribute("queue", queueService.list(botId, selected));
        int currentIndex = Math.max(-1, queueService.getPosition(botId, selected) - 1);
        model.addAttribute("currentIndex", currentIndex);
        model.addAttribute("playlists", queueService.listPlaylists(botId));
        model.addAttribute("activePlaylist", activePlaylist);
        model.addAttribute("playlistId", selected);
        return "player";
    }


    /**
     * 执行 settings 操作。
     * @param model 参数 model
     * @return 返回值
     */
    @GetMapping("/settings")
    public String settings(Model model) {
        model.addAttribute("bots", botManager.list());
        return "settings";
    }
}
