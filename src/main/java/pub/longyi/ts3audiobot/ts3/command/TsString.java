package pub.longyi.ts3audiobot.ts3.command;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 TsString 相关功能。
 */


/**
 * TsString 相关功能。
 *
 * <p>职责：负责 TsString 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public final class TsString {
    private TsString() {}

    /**
     * 执行 escape 操作。
     * @param input 参数 input
     * @return 返回值
     */
    public static String escape(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '/': sb.append("\\/"); break;
                case ' ': sb.append("\\s"); break;
                case '|': sb.append("\\p"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\u000b': sb.append("\\v"); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }


    /**
     * 执行 unescape 操作。
     * @param input 参数 input
     * @return 返回值
     */
    public static String unescape(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\') {
                if (++i >= input.length()) {
                    throw new IllegalArgumentException("Invalid escape sequence");
                }
                char next = input.charAt(i);
                switch (next) {
                    case 'v': sb.append('\u000b'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'n': sb.append('\n'); break;
                    case 'f': sb.append('\f'); break;
                    case 'p': sb.append('|'); break;
                    case 's': sb.append(' '); break;
                    case '/': sb.append('/'); break;
                    case '\\': sb.append('\\'); break;
                    default: throw new IllegalArgumentException("Invalid escape sequence");
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
