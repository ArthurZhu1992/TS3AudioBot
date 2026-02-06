package pub.longyi.ts3audiobot.ts3.full;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 ClientPlatform 相关功能。
 */


/**
 * ClientPlatform 枚举相关功能。
 *
 * <p>职责：定义 ClientPlatform 枚举值。</p>
 * <p>线程安全：枚举常量天然线程安全。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public enum ClientPlatform {
    WINDOWS,
    LINUX,
    MACOS,
    ANDROID,
    IOS
}
