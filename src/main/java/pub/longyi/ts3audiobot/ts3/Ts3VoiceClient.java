package pub.longyi.ts3audiobot.ts3;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 Ts3VoiceClient 相关功能。
 */


/**
 * Ts3VoiceClient 接口相关功能。
 *
 * <p>职责：定义 Ts3VoiceClient 接口契约。</p>
 * <p>线程安全：由实现类保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public interface Ts3VoiceClient extends Ts3Client {
    /**
     * 执行 configure 操作。
     * @param config 参数 config
     */
    default void configure(pub.longyi.ts3audiobot.ts3.full.ConnectionDataFull config) {
    }


    /**
     * 执行 sendOpusFrame 操作。
     * @param data 参数 data
     * @param length 参数 length
     */
    void sendOpusFrame(byte[] data, int length);
}
