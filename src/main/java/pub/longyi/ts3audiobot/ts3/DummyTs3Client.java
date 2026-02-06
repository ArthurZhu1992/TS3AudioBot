package pub.longyi.ts3audiobot.ts3;

import lombok.extern.slf4j.Slf4j;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 DummyTs3Client 相关功能。
 */


/**
 * DummyTs3Client 相关功能。
 *
 * <p>职责：负责 DummyTs3Client 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Slf4j
public final class DummyTs3Client implements Ts3Client {
    private volatile boolean connected;

    /**
     * 执行 connect 操作。
     * @param address 参数 address
     * @param channel 参数 channel
     */
    @Override
    public void connect(String address, String channel) {
        log.info("[TS3] connect requested address={} channel={}", address, channel);
        connected = true;
    }


    /**
     * 执行 disconnect 操作。
     */
    @Override
    public void disconnect() {
        log.info("[TS3] disconnect requested");
        connected = false;
    }


    /**
     * 执行 isConnected 操作。
     * @return 返回值
     */
    @Override
    public boolean isConnected() {
        return connected;
    }
}
