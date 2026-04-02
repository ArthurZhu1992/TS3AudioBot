package pub.longyi.ts3audiobot.resolver;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.search.SearchAuthService;
import pub.longyi.ts3audiobot.search.SearchAuthStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 ResolverRegistry 相关功能。
 */


/**
 * ResolverRegistry 相关功能。
 *
 * <p>职责：负责 ResolverRegistry 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Component
public final class ResolverRegistry {
    private final List<TrackResolver> resolvers = new ArrayList<>();

    /**
     * 创建 ResolverRegistry 实例。
     * @param configService 参数 configService
     */
    public ResolverRegistry(ConfigService configService) {
        this(configService, null);
    }

    @Autowired
    public ResolverRegistry(ConfigService configService, SearchAuthService searchAuthService) {
        var external = configService.get().resolvers.external;
        resolvers.add(new ExternalCliResolver("yt", external.yt));
        resolvers.add(new ExternalCliResolver("ytmusic", external.ytmusic));
        String neteaseCommand = selectNeteaseCommand(external.netease, external.ytmusic, external.yt);
        resolvers.add(new ExternalCliResolver("netease", neteaseCommand, () ->
            resolveSourceCookie(searchAuthService, "netease")
        ));
        String qqCommand = selectQqCommand(external.qq, external.ytmusic, external.yt);
        resolvers.add(new ExternalCliResolver("qq", qqCommand, () ->
            resolveSourceCookie(searchAuthService, "qq")
        ));
    }

    private String selectQqCommand(String qq, String ytmusic, String yt) {
        if (qq != null && !qq.isBlank() && !"qqmusic".equalsIgnoreCase(qq.trim())) {
            return qq;
        }
        if (ytmusic != null && !ytmusic.isBlank()) {
            return ytmusic;
        }
        return yt == null ? "" : yt;
    }

    private String selectNeteaseCommand(String netease, String ytmusic, String yt) {
        if (netease != null && !netease.isBlank() && !"netease-cloud-music".equalsIgnoreCase(netease.trim())) {
            return netease;
        }
        if (ytmusic != null && !ytmusic.isBlank()) {
            return ytmusic;
        }
        return yt == null ? "" : yt;
    }

    private String resolveSourceCookie(SearchAuthService searchAuthService, String source) {
        if (searchAuthService == null) {
            return "";
        }
        List<SearchAuthStore.AuthRecord> records = searchAuthService.listAuthBySource(source);
        if (records == null || records.isEmpty()) {
            return searchAuthService.resolveAuth(source, "")
                .map(record -> record.cookie() == null ? "" : record.cookie())
                .orElse("");
        }
        SearchAuthStore.AuthRecord latest = null;
        for (SearchAuthStore.AuthRecord record : records) {
            if (record == null || record.cookie() == null || record.cookie().isBlank()) {
                continue;
            }
            if (searchAuthService.isExpired(record)) {
                continue;
            }
            if (latest == null) {
                latest = record;
                continue;
            }
            if (record.updatedAt() != null && (latest.updatedAt() == null || record.updatedAt().isAfter(latest.updatedAt()))) {
                latest = record;
            }
        }
        return latest == null ? "" : (latest.cookie() == null ? "" : latest.cookie());
    }


    /**
     * 执行 list 操作。
     * @return 返回值
     */
    public List<TrackResolver> list() {
        return List.copyOf(resolvers);
    }
}
