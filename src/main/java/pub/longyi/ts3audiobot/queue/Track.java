package pub.longyi.ts3audiobot.queue;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 Track 相关功能。
 */


/**
 * Track 相关功能。
 *
 * <p>职责：负责 Track 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public record Track(
    String id,
    String title,
    String sourceType,
    String sourceId,
    String streamUrl,
    long durationMs
) {
}
