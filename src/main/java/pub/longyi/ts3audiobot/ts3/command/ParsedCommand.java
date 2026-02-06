package pub.longyi.ts3audiobot.ts3.command;

import java.util.Collections;
import java.util.Map;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 ParsedCommand 相关功能。
 */


/**
 * ParsedCommand 相关功能。
 *
 * <p>职责：负责 ParsedCommand 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public record ParsedCommand(String name, Map<String, String> params) {
    public ParsedCommand {
        if (params == null) {
            params = Collections.emptyMap();
        }
    }
}
