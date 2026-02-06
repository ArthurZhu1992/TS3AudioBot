package pub.longyi.ts3audiobot.audio;

import lombok.extern.slf4j.Slf4j;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 OpusEncoderStub 相关功能。
 */


/**
 * OpusEncoderStub 相关功能。
 *
 * <p>职责：负责 OpusEncoderStub 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Slf4j
public final class OpusEncoderStub implements OpusEncoder {
    private volatile boolean warned;

    /**
     * 执行 encode 操作。
     * @param pcm 参数 pcm
     * @param length 参数 length
     * @param format 参数 format
     * @return 返回值
     */
    @Override
    public byte[] encode(byte[] pcm, int length, PcmFormat format) {
        if (!warned) {
            log.warn("Opus encoder stub in use. Replace with native libopus binding.");
            warned = true;
        }
        byte[] copy = new byte[length];
        System.arraycopy(pcm, 0, copy, 0, length);
        return copy;
    }
}
