package pub.longyi.ts3audiobot.search;

import org.springframework.stereotype.Component;
import pub.longyi.ts3audiobot.config.ConfigService;

@Component
public final class YtSearchProvider extends YtDlpSearchProvider {
    public YtSearchProvider(ConfigService configService) {
        super("yt", configService.get().resolvers.external.yt);
    }

    @Override
    protected String searchPrefix() {
        return "ytsearch";
    }

    @Override
    protected String buildPageUrl(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return "https://www.youtube.com/watch?v=" + id;
    }
}
