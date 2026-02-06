package pub.longyi.ts3audiobot.ts3.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 TsCommandParser 相关功能。
 */


/**
 * TsCommandParser 相关功能。
 *
 * <p>职责：负责 TsCommandParser 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public final class TsCommandParser {
    private TsCommandParser() {}

    /**
     * 执行 parseLines 操作。
     * @param payload 参数 payload
     * @return 返回值
     */
    public static List<ParsedCommand> parseLines(String payload) {
        List<ParsedCommand> commands = new ArrayList<>();
        if (payload == null || payload.isBlank()) {
            return commands;
        }
        String[] lines = payload.split("\\r?\\n");
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            List<String> parts = splitCommands(line);
            for (String part : parts) {
                ParsedCommand cmd = parseLine(part.trim());
                if (cmd != null) {
                    commands.add(cmd);
                }
            }
        }
        return commands;
    }


    /**
     * 执行 parseLine 操作。
     * @param line 参数 line
     * @return 返回值
     */
    public static ParsedCommand parseLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        List<String> tokens = tokenize(line);
        if (tokens.isEmpty()) {
            return null;
        }
        String name = tokens.get(0);
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            int eq = token.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = token.substring(0, eq);
            String value = token.substring(eq + 1);
            params.put(key, TsString.unescape(value));
        }
        return new ParsedCommand(name, params);
    }

    private static List<String> splitCommands(String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                sb.append('\\').append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '|') {
                if (sb.length() > 0) {
                    parts.add(sb.toString());
                    sb.setLength(0);
                }
                continue;
            }
            sb.append(c);
        }
        if (sb.length() > 0) {
            parts.add(sb.toString());
        }
        return parts;
    }

    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                sb.append('\\').append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == ' ') {
                if (sb.length() > 0) {
                    tokens.add(sb.toString());
                    sb.setLength(0);
                }
                continue;
            }
            sb.append(c);
        }
        if (sb.length() > 0) {
            tokens.add(sb.toString());
        }
        return tokens;
    }
}
