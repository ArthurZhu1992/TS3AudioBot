package pub.longyi.ts3audiobot.ts3;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 Ts3Client 相关功能。
 */


/**
 * Ts3Client 接口相关功能。
 *
 * <p>职责：定义 Ts3Client 接口契约。</p>
 * <p>线程安全：由实现类保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public interface Ts3Client {
    /**
     * 执行 connect 操作。
     * @param address 参数 address
     * @param channel 参数 channel
     */
    void connect(String address, String channel);

    /**
     * 执行 disconnect 操作。
     */
    void disconnect();

    /**
     * 执行 isConnected 操作。
     * @return 返回值
     */
    boolean isConnected();
}
