package pub.longyi.ts3audiobot.queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.resolver.TrackResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueueServiceRepairTest {

    @TempDir
    Path tempDir;

    @Test
    void refreshItemRepairsMissingLocalStreamUrl() {
        QueueService service = newService(List.of(new FixedResolver("yt", resolvedTrack("https://example.com/live.webm"))));
        Track original = new Track(
            "track-1",
            "Song",
            "yt",
            "https://music.youtube.com/watch?v=AAA111",
            tempDir.resolve("missing.mp3").toString(),
            120_000L,
            "https://example.com/cover.jpg",
            "Artist",
            null
        );

        QueueItem created = service.add("bot", "default", original, "test");
        QueueItem refreshed = service.refreshItem("bot", "default", created.id());
        assertNotNull(refreshed);
        assertEquals("https://example.com/live.webm", refreshed.track().streamUrl());
    }

    @Test
    void refreshItemRepairsExpiredHttpStreamUrl() {
        QueueService service = newService(List.of(new FixedResolver("yt", resolvedTrack("https://example.com/new.webm"))));
        Track original = new Track(
            "track-2",
            "Song",
            "yt",
            "https://music.youtube.com/watch?v=BBB222",
            "https://cdn.example.com/audio.webm?expire=1&token=old",
            120_000L,
            "https://example.com/cover.jpg",
            "Artist",
            null
        );

        QueueItem created = service.add("bot", "default", original, "test");
        QueueItem refreshed = service.refreshItem("bot", "default", created.id());
        assertNotNull(refreshed);
        assertEquals("https://example.com/new.webm", refreshed.track().streamUrl());
    }

    @Test
    void refreshItemRepairsSignedHttpStreamUrlWithoutExpireParam() {
        QueueService service = newService(List.of(new FixedResolver("netease", resolvedTrack("https://example.com/new.mp3"))));
        Track original = new Track(
            "track-2b",
            "Song",
            "netease",
            "https://music.163.com/song?id=123456",
            "https://m702.music.126.net/audio.mp3?vuutv=token",
            120_000L,
            "https://example.com/cover.jpg",
            "Artist",
            null
        );

        QueueItem created = service.add("bot", "default", original, "test");
        QueueItem refreshed = service.refreshItem("bot", "default", created.id());
        assertNotNull(refreshed);
        assertEquals("https://example.com/new.mp3", refreshed.track().streamUrl());
    }

    @Test
    void refreshItemKeepsExistingValidLocalStreamUrl() throws Exception {
        Track resolved = resolvedTrack("https://example.com/should-not-be-used.webm");
        QueueService service = newService(List.of(new FixedResolver("yt", resolved)));
        Path localAudio = tempDir.resolve("audio.mp3");
        Files.writeString(localAudio, "ok");
        Track original = new Track(
            "track-3",
            "Song",
            "yt",
            "https://music.youtube.com/watch?v=CCC333",
            localAudio.toString(),
            120_000L,
            "https://example.com/cover.jpg",
            "Artist",
            null
        );

        QueueItem created = service.add("bot", "default", original, "test");
        QueueItem refreshed = service.refreshItem("bot", "default", created.id());
        assertNotNull(refreshed);
        assertEquals(localAudio.toString(), refreshed.track().streamUrl());
    }

    private Track resolvedTrack(String streamUrl) {
        return new Track(
            "resolved",
            "Resolved Song",
            "yt",
            "https://music.youtube.com/watch?v=resolved",
            streamUrl,
            180_000L,
            "https://example.com/new-cover.jpg",
            "Resolved Artist",
            123L
        );
    }

    private QueueService newService(List<TrackResolver> resolvers) {
        ConfigService configService = mock(ConfigService.class);
        when(configService.getQueueStorePath()).thenReturn(tempDir.resolve("queues.json"));
        return new QueueService(configService, resolvers);
    }

    private record FixedResolver(String sourceType, Track resolved) implements TrackResolver {
        @Override
        public Optional<Track> resolve(String query) {
            return Optional.ofNullable(resolved);
        }
    }
}
