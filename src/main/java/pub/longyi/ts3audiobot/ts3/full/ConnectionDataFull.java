package pub.longyi.ts3audiobot.ts3.full;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 ConnectionDataFull 相关功能。
 */


/**
 * ConnectionDataFull 相关功能。
 *
 * <p>职责：负责 ConnectionDataFull 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public final class ConnectionDataFull extends ConnectionData {
    public static final String DEFAULT_HWID = "+LyYqbDqOvEEpN5pdAbF8/v5kZ0=";

    private final IdentityData identity;
    private final TsVersionSigned versionSign;
    private final String username;
    private final Password serverPassword;
    private final String defaultChannel;
    private final Password defaultChannelPassword;
    private final String nicknamePhonetic;
    private final String defaultToken;
    private final String hwid;

    /**
     * 创建 ConnectionDataFull 实例。
     * @param address 参数 address
     * @param identity 参数 identity
     * @param versionSign 参数 versionSign
     * @param username 参数 username
     * @param serverPassword 参数 serverPassword
     * @param defaultChannel 参数 defaultChannel
     * @param defaultChannelPassword 参数 defaultChannelPassword
     * @param nicknamePhonetic 参数 nicknamePhonetic
     * @param defaultToken 参数 defaultToken
     * @param hwid 参数 hwid
     * @param logId 参数 logId
     */
    public ConnectionDataFull(
        String address,
        IdentityData identity,
        TsVersionSigned versionSign,
        String username,
        Password serverPassword,
        String defaultChannel,
        Password defaultChannelPassword,
        String nicknamePhonetic,
        String defaultToken,
        String hwid,
        String logId
    ) {
        super(address, logId);
        this.identity = identity;
        this.versionSign = versionSign;
        this.username = username == null ? "TS3Java" : username;
        this.serverPassword = serverPassword == null ? Password.EMPTY : serverPassword;
        this.defaultChannel = defaultChannel == null ? "" : defaultChannel;
        this.defaultChannelPassword = defaultChannelPassword == null ? Password.EMPTY : defaultChannelPassword;
        this.nicknamePhonetic = nicknamePhonetic == null ? "" : nicknamePhonetic;
        this.defaultToken = defaultToken == null ? "" : defaultToken;
        if (hwid == null || hwid.isBlank()) {
            this.hwid = DEFAULT_HWID;
        } else {
            this.hwid = hwid;
        }
    }


    /**
     * 执行 identity 操作。
     * @return 返回值
     */
    public IdentityData identity() {
        return identity;
    }


    /**
     * 执行 versionSign 操作。
     * @return 返回值
     */
    public TsVersionSigned versionSign() {
        return versionSign;
    }


    /**
     * 执行 username 操作。
     * @return 返回值
     */
    public String username() {
        return username;
    }


    /**
     * 执行 serverPassword 操作。
     * @return 返回值
     */
    public Password serverPassword() {
        return serverPassword;
    }


    /**
     * 执行 defaultChannel 操作。
     * @return 返回值
     */
    public String defaultChannel() {
        return defaultChannel;
    }


    /**
     * 执行 defaultChannelPassword 操作。
     * @return 返回值
     */
    public Password defaultChannelPassword() {
        return defaultChannelPassword;
    }


    /**
     * 执行 nicknamePhonetic 操作。
     * @return 返回值
     */
    public String nicknamePhonetic() {
        return nicknamePhonetic;
    }


    /**
     * 执行 defaultToken 操作。
     * @return 返回值
     */
    public String defaultToken() {
        return defaultToken;
    }


    /**
     * 执行 hwid 操作。
     * @return 返回值
     */
    public String hwid() {
        return hwid;
    }
}
