package pub.longyi.ts3audiobot.ts3.command;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 TsCommandBuilder 相关功能。
 */


/**
 * TsCommandBuilder 相关功能。
 *
 * <p>职责：负责 TsCommandBuilder 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public final class TsCommandBuilder {
    private TsCommandBuilder() {}

    /**
     * 执行 build 操作。
     * @param command 参数 command
     * @param params 参数 params
     * @return 返回值
     */
    public static String build(String command, Map<String, String> params) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command required");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(TsString.escape(command));
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                sb.append(' ').append(entry.getKey()).append('=')
                    .append(TsString.escape(entry.getValue() == null ? "" : entry.getValue()));
            }
        }
        return sb.toString();
    }


    /**
     * 执行 params 操作。
     * @param pairs 参数 pairs
     * @return 返回值
     */
    public static Map<String, String> params(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("params requires even number of arguments");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1] == null ? "" : String.valueOf(pairs[i + 1]));
        }
        return map;
    }
}
