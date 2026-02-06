package pub.longyi.ts3audiobot.bot;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 PlaybackMode 相关功能。
 */


/**
 * PlaybackMode 枚举相关功能。
 *
 * <p>职责：定义 PlaybackMode 枚举值。</p>
 * <p>线程安全：枚举常量天然线程安全。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public enum PlaybackMode {
    ORDER,
    RANDOM,
    LOOP;

    /**
     * 执行 from 操作。
     * @param raw 参数 raw
     * @return 返回值
     */
    public static PlaybackMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return ORDER;
        }
        return switch (raw.trim().toLowerCase()) {
            case "random", "shuffle" -> RANDOM;
            case "loop", "repeat" -> LOOP;
            default -> ORDER;
        };
    }
}
