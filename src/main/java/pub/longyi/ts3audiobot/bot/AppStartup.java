package pub.longyi.ts3audiobot.bot;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 AppStartup 相关功能。
 */


/**
 * AppStartup 相关功能。
 *
 * <p>职责：负责 AppStartup 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Component
public final class AppStartup implements ApplicationRunner {
    private final BotManager botManager;

    /**
     * 创建 AppStartup 实例。
     * @param botManager 参数 botManager
     */
    public AppStartup(BotManager botManager) {
        this.botManager = botManager;
    }


    /**
     * 执行 run 操作。
     * @param args 参数 args
     */
    @Override
    public void run(ApplicationArguments args) {
        botManager.initFromConfig();
    }
}
