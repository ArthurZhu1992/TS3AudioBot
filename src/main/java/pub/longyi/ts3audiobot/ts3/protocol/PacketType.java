package pub.longyi.ts3audiobot.ts3.protocol;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 PacketType 相关功能。
 */


/**
 * PacketType 枚举相关功能。
 *
 * <p>职责：定义 PacketType 枚举值。</p>
 * <p>线程安全：枚举常量天然线程安全。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public enum PacketType {
    VOICE(0x0),
    VOICE_WHISPER(0x1),
    COMMAND(0x2),
    COMMAND_LOW(0x3),
    PING(0x4),
    PONG(0x5),
    ACK(0x6),
    ACK_LOW(0x7),
    INIT1(0x8);

    private final int value;

    PacketType(int value) {
        this.value = value;
    }


    /**
     * 执行 value 操作。
     * @return 返回值
     */
    public int value() {
        return value;
    }


    /**
     * 执行 fromValue 操作。
     * @param value 参数 value
     * @return 返回值
     */
    public static PacketType fromValue(int value) {
        for (PacketType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        return null;
    }
}
