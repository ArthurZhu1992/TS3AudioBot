package pub.longyi.ts3audiobot.ts3.protocol;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 PacketFlags 相关功能。
 */


/**
 * PacketFlags 枚举相关功能。
 *
 * <p>职责：定义 PacketFlags 枚举值。</p>
 * <p>线程安全：枚举常量天然线程安全。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public enum PacketFlags {
    NONE(0x0),
    FRAGMENTED(0x10),
    NEW_PROTOCOL(0x20),
    COMPRESSED(0x40),
    UNENCRYPTED(0x80);

    private final int mask;

    PacketFlags(int mask) {
        this.mask = mask;
    }


    /**
     * 执行 mask 操作。
     * @return 返回值
     */
    public int mask() {
        return mask;
    }
}
