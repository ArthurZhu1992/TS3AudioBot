package pub.longyi.ts3audiobot.ts3.protocol;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 PacketDirection 相关功能。
 */


/**
 * PacketDirection 枚举相关功能。
 *
 * <p>职责：定义 PacketDirection 枚举值。</p>
 * <p>线程安全：枚举常量天然线程安全。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public enum PacketDirection {
    C2S(5),
    S2C(3);

    private final int headerLength;

    PacketDirection(int headerLength) {
        this.headerLength = headerLength;
    }


    /**
     * 执行 headerLength 操作。
     * @return 返回值
     */
    public int headerLength() {
        return headerLength;
    }
}
