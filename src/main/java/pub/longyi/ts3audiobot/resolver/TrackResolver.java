package pub.longyi.ts3audiobot.resolver;

import pub.longyi.ts3audiobot.queue.Track;

import java.util.Optional;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 TrackResolver 相关功能。
 */


/**
 * TrackResolver 接口相关功能。
 *
 * <p>职责：定义 TrackResolver 接口契约。</p>
 * <p>线程安全：由实现类保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public interface TrackResolver {
    /**
     * 执行 resolve 操作。
     * @param query 参数 query
     * @return 返回值
     */
    Optional<Track> resolve(String query);

    /**
     * 执行 sourceType 操作。
     * @return 返回值
     */
    String sourceType();
}
