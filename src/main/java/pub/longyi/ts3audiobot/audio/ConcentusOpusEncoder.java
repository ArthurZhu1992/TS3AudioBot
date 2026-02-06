package pub.longyi.ts3audiobot.audio;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusSignal;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 ConcentusOpusEncoder 相关功能。
 */


/**
 * ConcentusOpusEncoder 相关功能。
 *
 * <p>职责：负责 ConcentusOpusEncoder 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public final class ConcentusOpusEncoder implements pub.longyi.ts3audiobot.audio.OpusEncoder {
    private static final int MAX_PACKET = 4096;
    private static final int MIN_BITRATE = 6000;
    private static final int MAX_BITRATE = 192000;
    private static final int DEFAULT_BITRATE = 128000;

    private final OpusEncoder encoder;
    private final int frameSize;
    private volatile int bitrate = DEFAULT_BITRATE;

    /**
     * 创建 ConcentusOpusEncoder 实例。
     * @param format 参数 format
     * @param frameMs 参数 frameMs
     */
    public ConcentusOpusEncoder(PcmFormat format, int frameMs) {
        if (format.sampleRate() != 48000) {
            throw new IllegalArgumentException("Opus requires 48kHz sample rate");
        }
        int samplesPerChannel = format.sampleRate() * frameMs / 1000;
        this.frameSize = samplesPerChannel;
        try {
            encoder = new OpusEncoder(format.sampleRate(), format.channels(), OpusApplication.OPUS_APPLICATION_AUDIO);
            encoder.setSignalType(OpusSignal.OPUS_SIGNAL_MUSIC);
            encoder.setBitrate(DEFAULT_BITRATE);
            encoder.setComplexity(10);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize Opus encoder", ex);
        }
    }


    /**
     * 执行 encode 操作。
     * @param pcm 参数 pcm
     * @param length 参数 length
     * @param format 参数 format
     * @return 返回值
     */
    @Override
    public byte[] encode(byte[] pcm, int length, PcmFormat format) {
        if (pcm == null || length <= 0) {
            return new byte[0];
        }
        short[] pcmSamples = toShorts(pcm, length);
        int expectedSamples = frameSize * format.channels();
        if (pcmSamples.length != expectedSamples) {
            short[] adjusted = new short[expectedSamples];
            System.arraycopy(pcmSamples, 0, adjusted, 0, Math.min(pcmSamples.length, expectedSamples));
            pcmSamples = adjusted;
        }
        byte[] out = new byte[MAX_PACKET];
        int encoded;
        try {
            encoded = encoder.encode(pcmSamples, 0, frameSize, out, 0, out.length);
        } catch (Exception ex) {
            throw new IllegalStateException("Opus encode failed", ex);
        }
        byte[] result = new byte[encoded];
        System.arraycopy(out, 0, result, 0, encoded);
        return result;
    }


    /**
     * 执行 setBitrate 操作。
     * @param bitrate 参数 bitrate
     */
    public void setBitrate(int bitrate) {
        int target = Math.max(MIN_BITRATE, Math.min(MAX_BITRATE, bitrate));
        try {
            encoder.setBitrate(target);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to update Opus bitrate", ex);
        }
        this.bitrate = target;
    }


    /**
     * 执行 getBitrate 操作。
     * @return 返回值
     */
    public int getBitrate() {
        return bitrate;
    }

    private static short[] toShorts(byte[] pcm, int length) {
        int samples = length / 2;
        short[] out = new short[samples];
        for (int i = 0; i < samples; i++) {
            int lo = pcm[i * 2] & 0xFF;
            int hi = pcm[i * 2 + 1] << 8;
            out[i] = (short) (hi | lo);
        }
        return out;
    }
}
