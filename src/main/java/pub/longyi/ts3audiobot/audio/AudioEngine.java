package pub.longyi.ts3audiobot.audio;

import pub.longyi.ts3audiobot.queue.Track;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 AudioEngine 相关功能。
 */


/**
 * AudioEngine 接口相关功能。
 *
 * <p>职责：定义 AudioEngine 接口契约。</p>
 * <p>线程安全：由实现类保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public interface AudioEngine {
    /**
     * 执行 play 操作。
     * @param track 参数 track
     */
    void play(Track track);

    /**
     * 执行 pause 操作。
     */
    void pause();

    /**
     * 执行 stop 操作。
     */
    void stop();

    /**
     * 执行 seek 操作。
     * @param positionMs 参数 positionMs
     */
    void seek(long positionMs);

    /**
     * 执行 setVolume 操作。
     * @param percent 参数 percent
     */
    void setVolume(int percent);

    /**
     * 执行 isPlaying 操作。
     * @return 返回值
     */
    boolean isPlaying();
}
