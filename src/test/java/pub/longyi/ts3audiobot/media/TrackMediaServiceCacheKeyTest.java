package pub.longyi.ts3audiobot.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;
import pub.longyi.ts3audiobot.config.AppConfig;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.queue.Track;
import pub.longyi.ts3audiobot.search.SearchAuthService;
import pub.longyi.ts3audiobot.search.SearchAuthStore;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void neteaseCacheArgsIncludeCookieHeaderToAvoidPreviewFallback() throws Exception {
        SearchAuthService authService = mock(SearchAuthService.class);
        SearchAuthStore.AuthRecord record = new SearchAuthStore.AuthRecord(
            "netease",
            SearchAuthService.SCOPE_GLOBAL,
            "",
            "MUSIC_U=abc123; __csrf=token",
            "",
            "{}",
            null,
            Instant.now()
        );
        when(authService.listAuthBySource("netease")).thenReturn(List.of(record));
        when(authService.isExpired(record)).thenReturn(false);

        TrackMediaService service = newService(authService);
        Track track = new Track(
            "n1",
            "Netease Song",
            "netease",
            "https://music.163.com/song?id=725692",
            "",
            0L,
            "",
            "",
            null
        );
        List<String> args = buildYtDlpCacheArgs(service, track, tempDir.resolve("audio"));
        String joined = String.join("\n", args);
        assertTrue(joined.contains("--referer"));
        assertTrue(joined.contains("https://music.163.com/"));
        assertTrue(joined.contains("--add-headers"));
        assertTrue(joined.contains("Cookie: MUSIC_U=abc123; __csrf=token"));
    }

    @Test
    void playbackAndCoverUseSameTrackDirectoryAndDeleteCleansIt() throws Exception {
        TrackMediaService service = newService();
        Track track = new Track(
            "track-1",
            "A",
            "yt",
            "https://music.youtube.com/watch?v=AAA111",
            "https://example.com/stream.mp3",
            0L,
            "https://example.com/cover.jpg",
            "",
            null
        );
        Path trackDir = tempDir.resolve("track").resolve("track-1");
        Path audio = trackDir.resolve("audio.mp3");
        Path cover = trackDir.resolve("cover.jpg");
        Files.createDirectories(trackDir);
        Files.writeString(audio, "a");
        Files.writeString(cover, "c");

        Track prepared = service.prepareForPlayback(track);
        assertEquals(audio.toAbsolutePath().normalize().toString(), prepared.streamUrl());
        assertEquals("/internal/media/cover/track-1", prepared.coverUrl());
        assertTrue(service.findCoverFile(track.id()).isPresent());

        service.deleteTrackMedia(track);
        assertFalse(Files.exists(trackDir));
    }

    private TrackMediaService newService() {
        return newService(null);
    }

    private TrackMediaService newService(SearchAuthService authService) {
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
        return new TrackMediaService(configService, environment, authService);
    }

    private String resolveAudioCacheKey(TrackMediaService service, Track track) throws Exception {
        Method method = TrackMediaService.class.getDeclaredMethod("resolveAudioCacheKey", Track.class);
        method.setAccessible(true);
        return (String) method.invoke(service, track);
    }

    @SuppressWarnings("unchecked")
    private List<String> buildYtDlpCacheArgs(TrackMediaService service, Track track, Path dir) throws Exception {
        Method method = TrackMediaService.class.getDeclaredMethod("buildYtDlpCacheArgs", Track.class, Path.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(service, track, dir);
    }
}
