package pub.longyi.ts3audiobot.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 AppConfig 相关功能。
 */


/**
 * AppConfig 相关功能。
 *
 * <p>职责：负责 AppConfig 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
public final class AppConfig {
    public final Configs configs;
    public final Web web;
    public final Tools tools;
    public final Resolvers resolvers;
    public final List<BotConfig> bots;


    /**
     * 创建 AppConfig 实例。
     * @param configs 参数 configs
     * @param web 参数 web
     * @param tools 参数 tools
     * @param resolvers 参数 resolvers
     * @param bots 参数 bots
     */
    public AppConfig(Configs configs, Web web, Tools tools, Resolvers resolvers, List<BotConfig> bots) {
        this.configs = configs;
        this.web = web;
        this.tools = tools;
        this.resolvers = resolvers;
        this.bots = bots == null ? new ArrayList<>() : new ArrayList<>(bots);
    }


    /**
     * 执行 bots 操作。
     * @return 返回值
     */
    public List<BotConfig> bots() {
        return Collections.unmodifiableList(bots);
    }


    /**
     * Configs 相关功能。
     *
     * <p>职责：负责 Configs 相关功能。</p>
     * <p>线程安全：无显式保证。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    public static final class Configs {

        public final String botsPath;

        /**
         * 创建 Configs 实例。
         * @param botsPath 参数 botsPath
         */
        public Configs(String botsPath) {
            this.botsPath = botsPath;
        }
    }


    /**
     * Web 相关功能。
     *
     * <p>职责：负责 Web 相关功能。</p>
     * <p>线程安全：无显式保证。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    public static final class Web {
        public final int port;

        public final List<String> hosts;
        public final WebApi api;
        public final WebInterface webInterface;


        /**
         * 创建 Web 实例。
         * @param port 参数 port
         * @param hosts 参数 hosts
         * @param api 参数 api
         * @param webInterface 参数 webInterface
         */
        public Web(int port, List<String> hosts, WebApi api, WebInterface webInterface) {
            this.port = port;
            this.hosts = hosts == null ? List.of("*") : List.copyOf(hosts);
            this.api = api;
            this.webInterface = webInterface;
        }
    }


    /**
     * WebApi 相关功能。
     *
     * <p>职责：负责 WebApi 相关功能。</p>
     * <p>线程安全：无显式保证。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    public static final class WebApi {
        public final boolean enabled;

        /**
         * 创建 WebApi 实例。
         * @param enabled 参数 enabled
         */
        public WebApi(boolean enabled) {

            this.enabled = enabled;
        }
    }


    /**
     * WebInterface 相关功能。
     *
     * <p>职责：负责 WebInterface 相关功能。</p>
     * <p>线程安全：无显式保证。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    public static final class WebInterface {
        public final boolean enabled;


        /**
         * 创建 WebInterface 实例。
         * @param enabled 参数 enabled
         */
        public WebInterface(boolean enabled) {
            this.enabled = enabled;
        }
    }


    /**
     * Tools 相关功能。
     *
     * <p>职责：负责 Tools 相关功能。</p>
     * <p>线程安全：无显式保证。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    public static final class Tools {
        public final String ffmpegPath;


        /**
         * 创建 Tools 实例。
         * @param ffmpegPath 参数 ffmpegPath
         */
        public Tools(String ffmpegPath) {
            this.ffmpegPath = ffmpegPath;
        }
    }


    /**
     * Resolvers 相关功能。
     *
     * <p>职责：负责 Resolvers 相关功能。</p>
     * <p>线程安全：无显式保证。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    public static final class Resolvers {
        public final ExternalResolvers external;

        /**
         * 创建 Resolvers 实例。
         * @param external 参数 external
         */
        public Resolvers(ExternalResolvers external) {
            this.external = external;
        }
    }


    /**
     * ExternalResolvers 相关功能。
     *
     * <p>职责：负责 ExternalResolvers 相关功能。</p>
     * <p>线程安全：无显式保证。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    public static final class ExternalResolvers {
        public final String yt;
        public final String ytmusic;
        public final String netease;
        public final String qq;

        /**
         * 创建 ExternalResolvers 实例。
         * @param yt 参数 yt
         * @param ytmusic 参数 ytmusic
         * @param netease 参数 netease
         * @param qq 参数 qq
         */
        public ExternalResolvers(String yt, String ytmusic, String netease, String qq) {

            this.yt = yt;
            this.ytmusic = ytmusic;
            this.netease = netease;
            this.qq = qq;
        }
    }


    /**
     * BotConfig 相关功能。
     *
     * <p>职责：负责 BotConfig 相关功能。</p>
     * <p>线程安全：无显式保证。</p>
     * <p>约束：调用方需遵守方法契约。</p>
     */
    public static final class BotConfig {
        public final String name;
        public final boolean run;
        public final String connectAddress;
        public final String channel;
        public final String nickname;
        public final String serverPassword;
        public final String channelPassword;
        public final String identity;
        public final long identityOffset;
        public final long identityKeyOffset;
        public final int volumePercent;
        public final String clientVersion;
        public final String clientPlatform;
        public final String clientVersionSign;
        public final String clientHwid;
        public final String clientNicknamePhonetic;
        public final String clientDefaultToken;

        /**
         * 创建 BotConfig 实例。
         * @param name 参数 name
         * @param run 参数 run
         * @param connectAddress 参数 connectAddress
         * @param channel 参数 channel
         * @param nickname 参数 nickname
         * @param serverPassword 参数 serverPassword
         * @param channelPassword 参数 channelPassword
         * @param identity 参数 identity
         * @param identityKeyOffset 参数 identityKeyOffset
         * @param volumePercent 参数 volumePercent
         * @param clientVersion 参数 clientVersion
         * @param clientPlatform 参数 clientPlatform
         * @param clientVersionSign 参数 clientVersionSign
         * @param clientHwid 参数 clientHwid
         * @param clientNicknamePhonetic 参数 clientNicknamePhonetic
         * @param clientDefaultToken 参数 clientDefaultToken
         */
        public BotConfig(
            String name,
            boolean run,
            String connectAddress,
            String channel,
            String nickname,
            String serverPassword,
            String channelPassword,
            String identity,
            long identityOffset,
            long identityKeyOffset,
            int volumePercent,
            String clientVersion,
            String clientPlatform,
            String clientVersionSign,
            String clientHwid,
            String clientNicknamePhonetic,
            String clientDefaultToken
        ) {
            this.name = name;
            this.run = run;
            this.connectAddress = connectAddress;
            this.channel = channel;
            this.nickname = nickname;
            this.serverPassword = serverPassword;
            this.channelPassword = channelPassword;
            this.identity = identity;
            this.identityOffset = identityOffset;
            this.identityKeyOffset = identityKeyOffset;
            this.volumePercent = volumePercent;
            this.clientVersion = clientVersion;
            this.clientPlatform = clientPlatform;
            this.clientVersionSign = clientVersionSign;
            this.clientHwid = clientHwid;
            this.clientNicknamePhonetic = clientNicknamePhonetic;
            this.clientDefaultToken = clientDefaultToken;
        }
    }
}
