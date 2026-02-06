package pub.longyi.ts3audiobot.ts3;

import org.springframework.stereotype.Component;
import pub.longyi.ts3audiobot.ts3.full.TsFullClient;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 Ts3ClientFactory 相关功能。
 */


/**
 * Ts3ClientFactory 相关功能。
 *
 * <p>职责：负责 Ts3ClientFactory 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Component
public final class Ts3ClientFactory {
    /**
     * 执行 create 操作。
     * @return 返回值
     */
    public Ts3VoiceClient create() {
        return new TsFullClient();
    }
}
