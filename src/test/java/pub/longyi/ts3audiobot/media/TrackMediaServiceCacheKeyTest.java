package pub.longyi.ts3audiobot.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;
import pub.longyi.ts3audiobot.config.AppConfig;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.queue.Track;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrackMediaServiceCacheKeyTest {

    @TempDir
    Path tempDir;

    @Test
    void audioCacheKeyDiffersForDifferentYoutubeVideos() throws Exception {
        TrackMediaService service = newService();

        Track first = new Track(
            "t1",
            "Song A",
            "yt",
            "https://music.youtube.com/watch?v=AAA111&list=RDAMVMAAA111",
            "https://example.com/stream-a",
            1000L,
            "",
            "",
            null
        );
        Track second = new Track(
            "t2",
            "Song B",
            "yt",
            "https://music.youtube.com/watch?v=BBB222&list=RDAMVMBBB222",
            "https://example.com/stream-b",
            1000L,
            "",
            "",
            null
        );

        String firstKey = resolveAudioCacheKey(service, first);
        String secondKey = resolveAudioCacheKey(service, second);
        assertNotEquals(firstKey, secondKey);
    }

    @Test
    void audioCacheKeyFallsBackToStreamUrlWhenSourceIdMissing() throws Exception {
        TrackMediaService service = newService();

        Track first = new Track(
            "t1",
            "Song A",
            "yt",
            "",
            "https://audio.example.com/file-a.mp3?token=123",
            1000L,
            "",
            "",
            null
        );
        Track second = new Track(
            "t2",
            "Song B",
            "yt",
            "",
            "https://audio.example.com/file-b.mp3?token=456",
            1000L,
            "",
            "",
            null
        );

        String firstKey = resolveAudioCacheKey(service, first);
        String secondKey = resolveAudioCacheKey(service, second);
        assertNotEquals(firstKey, secondKey);
    }

    private TrackMediaService newService() {
        ConfigService configService = mock(ConfigService.class);
        when(configService.get()).thenReturn(new AppConfig(
            new AppConfig.Configs("bots"),
            new AppConfig.Web(58913, List.of("*"), new AppConfig.WebApi(false), new AppConfig.WebInterface(true)),
            new AppConfig.Tools("ffmpeg"),
            new AppConfig.Search("test", 0),
            new AppConfig.Media(true, true, 20, 720),
            new AppConfig.Resolvers(new AppConfig.ExternalResolvers("yt-dlp", "yt-dlp", "netease-cloud-music", "qqmusic")),
            List.of()
        ));
        Environment environment = mock(Environment.class);
        when(environment.getProperty("ts3audiobot.media.dir", "")).thenReturn(tempDir.toString());
        return new TrackMediaService(configService, environment);
    }

    private String resolveAudioCacheKey(TrackMediaService service, Track track) throws Exception {
        Method method = TrackMediaService.class.getDeclaredMethod("resolveAudioCacheKey", Track.class);
        method.setAccessible(true);
        return (String) method.invoke(service, track);
    }
}

