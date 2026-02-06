package pub.longyi.ts3audiobot.ts3.protocol;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 PacketKind 相关功能。
 */


/**
 * PacketKind 枚举相关功能。
 *
 * <p>职责：定义 PacketKind 枚举值。</p>
 * <p>线程安全：枚举常量天然线程安全。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public enum PacketKind {
    KEEPALIVE,
    SPEECH,
    CONTROL;

    /**
     * 执行 label 操作。
     * @return 返回值
     */
    public String label() {
        return name().toLowerCase();
    }
}
