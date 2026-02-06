package pub.longyi.ts3audiobot.ts3.full;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 Password 相关功能。
 */


/**
 * Password 相关功能。
 *
 * <p>职责：负责 Password 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public record Password(String hashedPassword) {
    /**
     * 创建 Password 实例。
     */
    public static final Password EMPTY = new Password("");

    /**
     * 执行 fromHash 操作。
     * @param hash 参数 hash
     * @return 返回值
     */
    public static Password fromHash(String hash) {
        return new Password(hash == null ? "" : hash);
    }


    /**
     * 执行 fromPlain 操作。
     * @param plain 参数 plain
     * @return 返回值
     */
    public static Password fromPlain(String plain) {
        return new Password(TsCrypt.hashPassword(plain));
    }
}
