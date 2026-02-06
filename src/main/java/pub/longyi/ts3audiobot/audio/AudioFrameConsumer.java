package pub.longyi.ts3audiobot.audio;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 AudioFrameConsumer 相关功能。
 */


/**
 * AudioFrameConsumer 接口相关功能。
 *
 * <p>职责：定义 AudioFrameConsumer 接口契约。</p>
 * <p>线程安全：由实现类保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@FunctionalInterface
public interface AudioFrameConsumer {
    /**
     * 执行 onPcmFrame 操作。
     * @param data 参数 data
     * @param length 参数 length
     * @param format 参数 format
     */
    void onPcmFrame(byte[] data, int length, PcmFormat format);
}
