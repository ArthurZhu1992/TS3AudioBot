package pub.longyi.ts3audiobot.ts3.full;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 ConnectionData 相关功能。
 */


/**
 * ConnectionData 相关功能。
 *
 * <p>职责：负责 ConnectionData 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public class ConnectionData {
    private final String address;
    private final String logId;

    /**
     * 创建 ConnectionData 实例。
     * @param address 参数 address
     * @param logId 参数 logId
     */
    public ConnectionData(String address, String logId) {
        this.address = address;
        this.logId = logId;
    }


    /**
     * 执行 address 操作。
     * @return 返回值
     */
    public String address() {
        return address;
    }


    /**
     * 执行 logId 操作。
     * @return 返回值
     */
    public String logId() {
        return logId;
    }
}
