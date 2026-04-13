package pub.longyi.ts3audiobot.shuffle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.queue.QueueItem;
import pub.longyi.ts3audiobot.queue.Track;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShufflePlaybackServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void nextAndPreviousShouldFollowSameRandomSequence() throws Exception {
        ShufflePlaybackService service = newService(tempDir.resolve("case-1"));
        List<QueueItem> queue = buildQueue("bot-a", "default", "a", "b", "c", "d");

        QueueItem first = service.next("bot-a", "default", queue, new Random(7), null);
        QueueItem second = service.next("bot-a", "default", queue, new Random(7), first == null ? null : first.id());
        QueueItem back = service.previous("bot-a", "default", queue, new Random(7), second == null ? null : second.id());

        assertNotNull(first);
        assertNotNull(second);
        assertNotNull(back);
        assertEquals(first.id(), back.id(), "random prev 应该回到刚刚的上一首");
    }

    @Test
    void playlistChangeShouldRebuildRandomQueueAndIncludeNewItem() throws Exception {
        ShufflePlaybackService service = newService(tempDir.resolve("case-2"));
        List<QueueItem> before = buildQueue("bot-a", "default", "a", "b", "c");
        QueueItem current = service.next("bot-a", "default", before, new Random(11), null);
        assertNotNull(current);

        List<QueueItem> after = buildQueue("bot-a", "default", "a", "b", "c", "d");
        boolean touchedNewItem = false;
        String currentId = current.id();
        for (int i = 0; i < 8; i++) {
            QueueItem next = service.next("bot-a", "default", after, new Random(11), currentId);
            assertNotNull(next);
            if ("d".equals(next.id())) {
                touchedNewItem = true;
                break;
            }
            currentId = next.id();
        }
        assertTrue(touchedNewItem, "歌单变化后重建的随机序列应包含新增歌曲");
    }

    @Test
    void persistedStateShouldSupportSequenceRollbackAfterReload() throws Exception {
        Path caseDir = tempDir.resolve("case-3");
        List<QueueItem> queue = buildQueue("bot-a", "default", "a", "b", "c");

        ShufflePlaybackService firstRun = newService(caseDir);
        QueueItem first = firstRun.next("bot-a", "default", queue, new Random(3), null);
        QueueItem second = firstRun.next("bot-a", "default", queue, new Random(3), first == null ? null : first.id());
        assertNotNull(first);
        assertNotNull(second);

        ShufflePlaybackService secondRun = newService(caseDir);
        QueueItem back = secondRun.previous("bot-a", "default", queue, new Random(3), second.id());
        assertNotNull(back);
        assertEquals(first.id(), back.id(), "恢复持久化状态后，prev 应回到上一首");
    }

    private ShufflePlaybackService newService(Path caseDir) throws Exception {
        Files.createDirectories(caseDir);
        Path configPath = caseDir.resolve("ts3Audio-config.toml");
        Files.writeString(configPath, "", java.nio.charset.StandardCharsets.UTF_8);
        MockEnvironment env = new MockEnvironment()
            .withProperty("ts3audiobot.config.external-file", configPath.toString())
            .withProperty("ts3audiobot.storage.data-dir", caseDir.resolve("data").toString())
            .withProperty("ts3audiobot.configs.db-path", caseDir.resolve("data").resolve("test.db").toString());
        ConfigService configService = new ConfigService(env);
        return new ShufflePlaybackService(configService);
    }

    private List<QueueItem> buildQueue(String botId, String playlistId, String... ids) {
        if (ids == null || ids.length == 0) {
            return List.of();
        }
        List<QueueItem> result = new java.util.ArrayList<>(ids.length);
        for (String id : ids) {
            Track track = new Track(
                "track-" + id,
                "title-" + id,
                "yt",
                "https://example.com/" + id,
                "https://stream.example.com/" + id,
                10_000L,
                "",
                "artist",
                null
            );
            result.add(new QueueItem(id, botId, playlistId, track, Instant.now(), "test"));
        }
        return result;
    }
}
