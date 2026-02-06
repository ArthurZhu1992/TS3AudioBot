package pub.longyi.ts3audiobot.audio;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 PcmFormat 相关功能。
 */


/**
 * PcmFormat 相关功能。
 *
 * <p>职责：负责 PcmFormat 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public record PcmFormat(int sampleRate, int channels, int bitsPerSample) {
    /**
     * 执行 frameBytes 操作。
     * @param frameMs 参数 frameMs
     * @return 返回值
     */
    public int frameBytes(int frameMs) {
        int samplesPerChannel = sampleRate * frameMs / 1000;
        int bytesPerSample = bitsPerSample / 8;
        return samplesPerChannel * channels * bytesPerSample;
    }
}
