package pub.longyi.ts3audiobot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 Ts3AudioBotApplication 相关功能。
 */


/**
 * Ts3AudioBotApplication 相关功能。
 *
 * <p>职责：负责 Ts3AudioBotApplication 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@SpringBootApplication
public class Ts3AudioBotApplication {

    /**
     * 执行 main 操作。
     * @param args 参数 args
     */
    public static void main(String[] args) {
        SpringApplication.run(Ts3AudioBotApplication.class, args);
    }

}
