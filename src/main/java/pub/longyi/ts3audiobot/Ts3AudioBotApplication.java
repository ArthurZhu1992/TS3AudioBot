package pub.longyi.ts3audiobot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 程序启动入口。
 *
 * <p>这里集中说明本次随机播放改造的关键设计，便于后续维护与迁移：</p>
 * <ul>
 *     <li>随机状态作用域：按 {@code botId + playlistId} 隔离，避免多机器人/多歌单互相污染。</li>
 *     <li>随机序列语义：随机模式下 {@code next/prev} 均沿同一随机序列前后移动，确保可回退。</li>
 *     <li>重建策略：歌单结构变化（增删清空重命名）或切换到随机模式时，触发随机序列重算。</li>
 *     <li>线程安全：随机状态由服务层统一保护，避免定时播放线程与 Web 控制并发改写状态。</li>
 *     <li>持久化解耦：随机状态独立落盘到 {@code data/shuffle-state.json}，不与队列快照耦合。</li>
 *     <li>迁移友好：随机核心使用通用数据结构，业务层只做适配，后续可平移到其他项目。</li>
 * </ul>
 *
 * <p>实现细节建议优先查看 {@code shuffle} 包以及 {@code BotInstance} 播放控制流程。</p>
 */
@SpringBootApplication
@EnableScheduling
public class Ts3AudioBotApplication {

    /**
     * 应用主入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(Ts3AudioBotApplication.class, args);
    }
}
