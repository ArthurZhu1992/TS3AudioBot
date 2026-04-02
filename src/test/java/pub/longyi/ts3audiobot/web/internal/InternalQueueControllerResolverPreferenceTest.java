package pub.longyi.ts3audiobot.web.internal;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import pub.longyi.ts3audiobot.media.TrackMediaService;
import pub.longyi.ts3audiobot.queue.QueueItem;
import pub.longyi.ts3audiobot.queue.QueueService;
import pub.longyi.ts3audiobot.queue.Track;
import pub.longyi.ts3audiobot.resolver.ResolverRegistry;
import pub.longyi.ts3audiobot.resolver.TrackResolver;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalQueueControllerResolverPreferenceTest {

    @Test
    void addByPlaylistPrefersExplicitSourceResolver() {
        QueueService queueService = mock(QueueService.class);
        ResolverRegistry resolverRegistry = mock(ResolverRegistry.class);
        TrackMediaService trackMediaService = mock(TrackMediaService.class);
        TrackResolver ytResolver = mock(TrackResolver.class);
        TrackResolver neteaseResolver = mock(TrackResolver.class);
        InternalQueueController controller = new InternalQueueController(queueService, resolverRegistry, trackMediaService);

        String query = "https://music.163.com/song?id=123456";
        Track ytTrack = track("yt", query, "https://trial.example/30s");
        Track neteaseTrack = track("netease", query, "https://full.example/320k");

        when(ytResolver.sourceType()).thenReturn("yt");
        when(neteaseResolver.sourceType()).thenReturn("netease");
        when(ytResolver.resolve(query)).thenReturn(Optional.of(ytTrack));
        when(neteaseResolver.resolve(query)).thenReturn(Optional.of(neteaseTrack));
        when(resolverRegistry.list()).thenReturn(List.of(ytResolver, neteaseResolver));
        when(trackMediaService.prepareForQueue(any(Track.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(queueService.add(eq("bot-1"), eq("default"), any(Track.class), eq("web")))
            .thenAnswer(invocation -> new QueueItem("item-1", "bot-1", "default", invocation.getArgument(2), Instant.now(), "web"));

        InternalQueueController.AddItemRequest item = new InternalQueueController.AddItemRequest(
            query,
            "Song",
            "Singer",
            "",
            180_000L,
            "netease",
            null
        );
        InternalQueueController.AddRequest request = new InternalQueueController.AddRequest(null, null, List.of(item), "web");

        ResponseEntity<?> response = controller.addByPlaylist("bot-1", "default", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<Track> captor = ArgumentCaptor.forClass(Track.class);
        verify(queueService).add(eq("bot-1"), eq("default"), captor.capture(), eq("web"));
        assertEquals("https://full.example/320k", captor.getValue().streamUrl());
        verify(neteaseResolver).resolve(query);
        verify(ytResolver, never()).resolve(query);
    }

    @Test
    void addByPlaylistPrefersResolverByQueryHostWhenSourceMissing() {
        QueueService queueService = mock(QueueService.class);
        ResolverRegistry resolverRegistry = mock(ResolverRegistry.class);
        TrackMediaService trackMediaService = mock(TrackMediaService.class);
        TrackResolver ytResolver = mock(TrackResolver.class);
        TrackResolver neteaseResolver = mock(TrackResolver.class);
        InternalQueueController controller = new InternalQueueController(queueService, resolverRegistry, trackMediaService);

        String query = "https://music.163.com/song?id=223344";
        Track neteaseTrack = track("netease", query, "https://full.example/netease");

        when(ytResolver.sourceType()).thenReturn("yt");
        when(neteaseResolver.sourceType()).thenReturn("netease");
        when(neteaseResolver.resolve(query)).thenReturn(Optional.of(neteaseTrack));
        when(resolverRegistry.list()).thenReturn(List.of(ytResolver, neteaseResolver));
        when(trackMediaService.prepareForQueue(any(Track.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(queueService.add(eq("bot-2"), eq("default"), any(Track.class), eq("web")))
            .thenAnswer(invocation -> new QueueItem("item-2", "bot-2", "default", invocation.getArgument(2), Instant.now(), "web"));

        InternalQueueController.AddItemRequest item = new InternalQueueController.AddItemRequest(
            query,
            "Song",
            "Singer",
            "",
            180_000L,
            "",
            null
        );
        InternalQueueController.AddRequest request = new InternalQueueController.AddRequest(null, null, List.of(item), "web");

        ResponseEntity<?> response = controller.addByPlaylist("bot-2", "default", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(neteaseResolver).resolve(query);
        verify(ytResolver, never()).resolve(query);
    }

    @Test
    void addByPlaylistFallsBackWhenPreferredResolverFails() {
        QueueService queueService = mock(QueueService.class);
        ResolverRegistry resolverRegistry = mock(ResolverRegistry.class);
        TrackMediaService trackMediaService = mock(TrackMediaService.class);
        TrackResolver ytResolver = mock(TrackResolver.class);
        TrackResolver neteaseResolver = mock(TrackResolver.class);
        InternalQueueController controller = new InternalQueueController(queueService, resolverRegistry, trackMediaService);

        String query = "https://music.163.com/song?id=998877";
        Track ytTrack = track("yt", query, "https://fallback.example/yt");

        when(ytResolver.sourceType()).thenReturn("yt");
        when(neteaseResolver.sourceType()).thenReturn("netease");
        when(neteaseResolver.resolve(query)).thenReturn(Optional.empty());
        when(ytResolver.resolve(query)).thenReturn(Optional.of(ytTrack));
        when(resolverRegistry.list()).thenReturn(List.of(ytResolver, neteaseResolver));
        when(trackMediaService.prepareForQueue(any(Track.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(queueService.add(eq("bot-3"), eq("default"), any(Track.class), eq("web")))
            .thenAnswer(invocation -> new QueueItem("item-3", "bot-3", "default", invocation.getArgument(2), Instant.now(), "web"));

        InternalQueueController.AddItemRequest item = new InternalQueueController.AddItemRequest(
            query,
            "Song",
            "Singer",
            "",
            180_000L,
            "netease",
            null
        );
        InternalQueueController.AddRequest request = new InternalQueueController.AddRequest(null, null, List.of(item), "web");

        ResponseEntity<?> response = controller.addByPlaylist("bot-3", "default", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody() instanceof QueueItem);
        verify(neteaseResolver).resolve(query);
        verify(ytResolver).resolve(query);
    }

    private Track track(String source, String sourceId, String streamUrl) {
        return new Track(
            "track-id",
            "title",
            source,
            sourceId,
            streamUrl,
            180_000L,
            "",
            "",
            null
        );
    }
}

