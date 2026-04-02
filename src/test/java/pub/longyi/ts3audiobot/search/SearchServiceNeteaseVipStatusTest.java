package pub.longyi.ts3audiobot.search;

import org.junit.jupiter.api.Test;
import pub.longyi.ts3audiobot.config.AppConfig;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.search.SearchModels.SearchStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchServiceNeteaseVipStatusTest {

    @Test
    void neteaseStatusIncludesVipAndAccountInfo() {
        SearchProvider provider = mock(SearchProvider.class);
        when(provider.source()).thenReturn("netease");
        when(provider.requiresLogin()).thenReturn(true);
        when(provider.supportsPlaylists()).thenReturn(true);

        SearchAuthService authService = mock(SearchAuthService.class);
        SearchAuthStore.AuthRecord auth = new SearchAuthStore.AuthRecord(
            "netease",
            SearchAuthService.SCOPE_GLOBAL,
            "",
            "MUSIC_U=abc",
            "",
            "{\"userId\":\"12345\",\"nickname\":\"tester\",\"vipType\":\"11\",\"redVipLevel\":\"7\"}",
            null,
            Instant.now()
        );
        when(authService.resolveAuth("netease", "bot-1")).thenReturn(Optional.of(auth));
        when(authService.isExpired(auth)).thenReturn(false);

        SearchService service = new SearchService(List.of(provider), authService, mockConfigService());
        List<SearchStatus> statuses = service.getStatus("bot-1");
        assertEquals(1, statuses.size());

        SearchStatus status = statuses.get(0);
        assertEquals("netease", status.source());
        assertEquals("vip", status.vipState());
        assertEquals("黑胶VIP Lv.7", status.vipHint());
        assertEquals("tester (12345)", status.accountInfo());
    }

    @Test
    void neteaseStatusFallsBackToUnknownWhenVipFieldsMissing() {
        SearchProvider provider = mock(SearchProvider.class);
        when(provider.source()).thenReturn("netease");
        when(provider.requiresLogin()).thenReturn(true);
        when(provider.supportsPlaylists()).thenReturn(true);

        SearchAuthService authService = mock(SearchAuthService.class);
        SearchAuthStore.AuthRecord auth = new SearchAuthStore.AuthRecord(
            "netease",
            SearchAuthService.SCOPE_GLOBAL,
            "",
            "MUSIC_U=abc",
            "",
            "{\"userId\":\"12345\",\"nickname\":\"legacy-user\"}",
            null,
            Instant.now()
        );
        when(authService.resolveAuth("netease", "bot-1")).thenReturn(Optional.of(auth));
        when(authService.isExpired(auth)).thenReturn(false);

        SearchService service = new SearchService(List.of(provider), authService, mockConfigService());
        List<SearchStatus> statuses = service.getStatus("bot-1");
        SearchStatus status = statuses.get(0);

        assertEquals("unknown", status.vipState());
        assertEquals("未识别到会员信息", status.vipHint());
        assertEquals("legacy-user (12345)", status.accountInfo());
    }

    private ConfigService mockConfigService() {
        ConfigService configService = mock(ConfigService.class);
        AppConfig config = new AppConfig(
            new AppConfig.Configs("bots"),
            new AppConfig.Web(58913, List.of("*"), new AppConfig.WebApi(false), new AppConfig.WebInterface(true)),
            new AppConfig.Tools("ffmpeg"),
            new AppConfig.Search("test", 0),
            new AppConfig.Resolvers(new AppConfig.ExternalResolvers("yt-dlp", "yt-dlp", "netease-cloud-music", "qqmusic")),
            List.of()
        );
        when(configService.get()).thenReturn(config);
        return configService;
    }
}
