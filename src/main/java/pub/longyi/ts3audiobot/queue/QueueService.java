package pub.longyi.ts3audiobot.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.util.IdGenerator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 负责 QueueService 相关功能。
 */


/**
 * QueueService 相关功能。
 *
 * <p>职责：负责 QueueService 相关功能。</p>
 * <p>线程安全：无显式保证。</p>
 * <p>约束：调用方需遵守方法契约。</p>
 */
@Slf4j
@Service
public final class QueueService {
    private static final String DEFAULT_PLAYLIST_ID = "default";

    private final Map<String, Map<String, List<QueueItem>>> queues = new ConcurrentHashMap<>();
    private final Map<String, String> activePlaylists = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> playlistPositions = new ConcurrentHashMap<>();
    private final Object stateLock = new Object();
    private final ObjectMapper objectMapper;
    private final Path storePath;


    /**
     * 创建 QueueService 实例。
     * @param configService 参数 configService
     */
    public QueueService(ConfigService configService) {
        this.storePath = configService.getDataDir().resolve("queues.json");
        this.objectMapper = new ObjectMapper()


            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        loadSnapshot();


    }


    /**
     * 执行 list 操作。
     * @param botId 参数 botId
     * @return 返回值
     */
    public List<QueueItem> list(String botId) {
        return list(botId, getActivePlaylist(botId));
    }


    /**
     * 执行 add 操作。
     * @param botId 参数 botId
     * @param track 参数 track
     * @param addedBy 参数 addedBy
     * @return 返回值
     */
    public QueueItem add(String botId, Track track, String addedBy) {

        return add(botId, getActivePlaylist(botId), track, addedBy);
    }


    /**
     * 执行 next 操作。
     * @param botId 参数 botId
     * @return 返回值
     */
    public QueueItem next(String botId) {
        return next(botId, getActivePlaylist(botId));
    }


    /**
     * 执行 getPosition 操作。
     * @param botId 参数 botId
     * @return 返回值
     */
    public int getPosition(String botId) {
        return getPosition(botId, getActivePlaylist(botId));
    }


    /**
     * 执行 getPosition 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @return 返回值
     */
    public int getPosition(String botId, String playlistId) {
        synchronized (stateLock) {
            return resolvePosition(botId, playlistId);
        }
    }


    /**
     * 执行 stepBack 操作。
     * @param botId 参数 botId
     */
    public void stepBack(String botId) {
        stepBack(botId, getActivePlaylist(botId));

    }


    /**
     * 执行 stepBack 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     */
    public void stepBack(String botId, String playlistId) {
        synchronized (stateLock) {
            int pos = resolvePosition(botId, playlistId);
            if (pos > 0) {
                setPosition(botId, playlistId, pos - 1);
                persistSnapshot();
            }
        }

    }


    /**
     * 执行 clear 操作。
     * @param botId 参数 botId
     */
    public void clear(String botId) {

        clear(botId, getActivePlaylist(botId));
    }


    /**
     * 执行 list 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @return 返回值
     */
    public List<QueueItem> list(String botId, String playlistId) {
        return Collections.unmodifiableList(resolveQueue(botId, playlistId));

    }


    /**
     * 执行 add 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @param track 参数 track
     * @param addedBy 参数 addedBy
     * @return 返回值
     */
    public QueueItem add(String botId, String playlistId, Track track, String addedBy) {

        String resolvedPlaylist = normalizePlaylistId(playlistId);
        synchronized (stateLock) {
            QueueItem item = new QueueItem(IdGenerator.newId(), botId, resolvedPlaylist, track, Instant.now(), addedBy);
            resolveQueue(botId, resolvedPlaylist).add(item);
            persistSnapshot();
            return item;
        }

    }


    /**
     * 执行 next 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @return 返回值
     */
    public QueueItem next(String botId, String playlistId) {
        synchronized (stateLock) {
            List<QueueItem> queue = resolveQueue(botId, playlistId);
            if (queue.isEmpty()) {
                return null;
            }
            int index = resolvePosition(botId, playlistId);
            if (index < 0 || index >= queue.size()) {
                return null;
            }
            QueueItem item = queue.get(index);
            setPosition(botId, playlistId, index + 1);
            persistSnapshot();
            return item;

        }
    }


    /**
     * 执行 nextRandom 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @param random 参数 random
     * @return 返回值
     */
    public QueueItem nextRandom(String botId, String playlistId, Random random) {
        if (random == null) {
            random = new Random();
        }
        synchronized (stateLock) {
            List<QueueItem> queue = resolveQueue(botId, playlistId);
            if (queue.isEmpty()) {
                return null;
            }
            int index = random.nextInt(queue.size());
            QueueItem item = queue.get(index);
            setPosition(botId, playlistId, index + 1);
            persistSnapshot();
            return item;
        }
    }


    /**
     * 执行 nextLoop 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @return 返回值
     */
    public QueueItem nextLoop(String botId, String playlistId) {
        synchronized (stateLock) {
            List<QueueItem> queue = resolveQueue(botId, playlistId);
            if (queue.isEmpty()) {
                return null;
            }
            int pos = resolvePosition(botId, playlistId);
            int maxIndex = queue.size() - 1;
            int index = Math.max(0, Math.min(maxIndex, pos - 1));
            if (pos == 0) {
                setPosition(botId, playlistId, index + 1);
                persistSnapshot();
            }
            return queue.get(index);
        }
    }


    /**
     * 执行 clear 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     */
    public void clear(String botId, String playlistId) {
        synchronized (stateLock) {
            resolveQueue(botId, playlistId).clear();
            setPosition(botId, playlistId, 0);
            persistSnapshot();
        }
    }


    /**
     * 执行 listPlaylists 操作。
     * @param botId 参数 botId
     * @return 返回值
     */
    public List<String> listPlaylists(String botId) {

        Map<String, List<QueueItem>> playlists = resolvePlaylists(botId);
        List<String> result = new ArrayList<>(playlists.keySet());
        result.sort(String::compareToIgnoreCase);
        return result;
    }


    /**
     * 执行 getActivePlaylist 操作。
     * @param botId 参数 botId
     * @return 返回值
     */
    public String getActivePlaylist(String botId) {
        String current = activePlaylists.get(botId);
        if (current == null || current.isBlank()) {
            current = DEFAULT_PLAYLIST_ID;
            activePlaylists.put(botId, current);
            persistSnapshot();
        }
        resolveQueue(botId, current);
        return current;


    }


    /**
     * 执行 setActivePlaylist 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     */
    public void setActivePlaylist(String botId, String playlistId) {
        String resolved = normalizePlaylistId(playlistId);
        synchronized (stateLock) {
            resolveQueue(botId, resolved);
            activePlaylists.put(botId, resolved);
            persistSnapshot();
        }
    }


    /**
     * 执行 createPlaylist 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @return 返回值
     */
    public boolean createPlaylist(String botId, String playlistId) {
        String resolved = normalizePlaylistId(playlistId);
        synchronized (stateLock) {
            Map<String, List<QueueItem>> playlists = resolvePlaylists(botId);
            if (playlists.containsKey(resolved)) {
                return false;
            }
            playlists.put(resolved, Collections.synchronizedList(new ArrayList<>()));
            setPosition(botId, resolved, 0);
            persistSnapshot();
            return true;
        }
    }


    /**
     * 执行 removePlaylist 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @return 返回值
     */
    public boolean removePlaylist(String botId, String playlistId) {
        String resolved = normalizePlaylistId(playlistId);
        if (DEFAULT_PLAYLIST_ID.equalsIgnoreCase(resolved)) {
            return false;
        }
        synchronized (stateLock) {
            Map<String, List<QueueItem>> playlists = resolvePlaylists(botId);
            if (playlists.remove(resolved) == null) {
                return false;
            }
            Map<String, Integer> positions = playlistPositions.get(botId);
            if (positions != null) {
                positions.remove(resolved);
            }
            String active = getActivePlaylist(botId);
            if (resolved.equalsIgnoreCase(active)) {
                activePlaylists.put(botId, DEFAULT_PLAYLIST_ID);
            }
            persistSnapshot();
            return true;
        }
    }


    /**
     * 执行 renamePlaylist 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @param newPlaylistId 参数 newPlaylistId
     * @return 返回值
     */
    public boolean renamePlaylist(String botId, String playlistId, String newPlaylistId) {
        String from = normalizePlaylistId(playlistId);
        String to = normalizePlaylistId(newPlaylistId);
        if (DEFAULT_PLAYLIST_ID.equalsIgnoreCase(from) || DEFAULT_PLAYLIST_ID.equalsIgnoreCase(to)) {
            return false;
        }
        if (from.equalsIgnoreCase(to)) {
            return false;
        }
        synchronized (stateLock) {
            Map<String, List<QueueItem>> playlists = resolvePlaylists(botId);
            if (!playlists.containsKey(from) || playlists.containsKey(to)) {
                return false;
            }
            List<QueueItem> items = playlists.remove(from);
            if (items == null) {
                return false;
            }
            List<QueueItem> renamed = new ArrayList<>(items.size());
            for (QueueItem item : items) {
                renamed.add(new QueueItem(
                    item.id(),
                    item.botId(),
                    to,
                    item.track(),
                    item.addedAt(),
                    item.addedBy()
                ));
            }
            playlists.put(to, Collections.synchronizedList(renamed));
            Map<String, Integer> positions = playlistPositions.computeIfAbsent(botId, key -> new ConcurrentHashMap<>());
            Integer currentPos = positions.remove(from);
            if (currentPos != null) {
                positions.put(to, currentPos);
            }
            String active = getActivePlaylist(botId);
            if (from.equalsIgnoreCase(active)) {
                activePlaylists.put(botId, to);
            }
            persistSnapshot();
            return true;
        }
    }


    /**
     * 执行 jumpTo 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @param itemId 参数 itemId
     * @return 返回值
     */
    public boolean jumpTo(String botId, String playlistId, String itemId) {
        if (botId == null || itemId == null || itemId.isBlank()) {
            return false;
        }
        String resolved = normalizePlaylistId(playlistId);
        synchronized (stateLock) {
            List<QueueItem> queue = resolveQueue(botId, resolved);
            int index = findIndex(queue, itemId);
            if (index < 0) {
                return false;
            }
            activePlaylists.put(botId, resolved);
            setPosition(botId, resolved, index);
            persistSnapshot();
            return true;
        }
    }


    /**
     * 执行 removeItem 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @param itemId 参数 itemId
     * @return 返回值
     */
    public boolean removeItem(String botId, String playlistId, String itemId) {
        if (botId == null || itemId == null || itemId.isBlank()) {
            return false;
        }
        String resolved = normalizePlaylistId(playlistId);
        synchronized (stateLock) {
            List<QueueItem> queue = resolveQueue(botId, resolved);
            int index = findIndex(queue, itemId);
            if (index < 0) {
                return false;
            }
            queue.remove(index);
            int pos = resolvePosition(botId, resolved);
            if (index < pos) {
                pos -= 1;
            }
            int max = queue.size();
            setPosition(botId, resolved, Math.max(0, Math.min(pos, max)));
            persistSnapshot();
            return true;
        }
    }


    /**
     * 执行 hasPlaylist 操作。
     * @param botId 参数 botId
     * @param playlistId 参数 playlistId
     * @return 返回值
     */
    public boolean hasPlaylist(String botId, String playlistId) {
        String resolved = normalizePlaylistId(playlistId);
        return resolvePlaylists(botId).containsKey(resolved);
    }


    /**
     * 执行 listStoredBotIds 操作。
     * @return 返回值
     */
    public List<String> listStoredBotIds() {
        return new ArrayList<>(queues.keySet());
    }


    /**
     * 执行 renameBotId 操作。
     * @param fromBotId 参数 fromBotId
     * @param toBotId 参数 toBotId
     */
    public void renameBotId(String fromBotId, String toBotId) {
        if (fromBotId == null || toBotId == null) {
            return;
        }
        if (fromBotId.equals(toBotId)) {
            return;
        }
        synchronized (stateLock) {
            Map<String, List<QueueItem>> fromQueues = queues.remove(fromBotId);
            if (fromQueues != null) {
                queues.put(toBotId, fromQueues);
            }
            String active = activePlaylists.remove(fromBotId);
            if (active != null) {
                activePlaylists.put(toBotId, active);
            }
            Map<String, Integer> positions = playlistPositions.remove(fromBotId);
            if (positions != null) {
                playlistPositions.put(toBotId, positions);
            }
            persistSnapshot();
        }
    }

    private Map<String, List<QueueItem>> resolvePlaylists(String botId) {
        Map<String, List<QueueItem>> playlists = queues.computeIfAbsent(botId, key -> new ConcurrentHashMap<>());
        playlists.computeIfAbsent(DEFAULT_PLAYLIST_ID, key -> Collections.synchronizedList(new ArrayList<>()));
        return playlists;
    }

    private List<QueueItem> resolveQueue(String botId, String playlistId) {
        String resolved = normalizePlaylistId(playlistId);
        Map<String, List<QueueItem>> playlists = resolvePlaylists(botId);
        return playlists.computeIfAbsent(resolved, key -> Collections.synchronizedList(new ArrayList<>()));
    }

    private String normalizePlaylistId(String playlistId) {
        if (playlistId == null || playlistId.isBlank()) {
            return DEFAULT_PLAYLIST_ID;
        }
        return playlistId.trim();
    }

    private int resolvePosition(String botId, String playlistId) {
        Map<String, Integer> positions = playlistPositions.computeIfAbsent(botId, key -> new ConcurrentHashMap<>());
        String resolved = normalizePlaylistId(playlistId);
        return positions.getOrDefault(resolved, 0);
    }

    private void setPosition(String botId, String playlistId, int position) {
        Map<String, Integer> positions = playlistPositions.computeIfAbsent(botId, key -> new ConcurrentHashMap<>());
        String resolved = normalizePlaylistId(playlistId);
        positions.put(resolved, Math.max(0, position));
    }

    private int findIndex(List<QueueItem> queue, String itemId) {
        if (queue == null || itemId == null) {
            return -1;
        }
        for (int i = 0; i < queue.size(); i++) {
            QueueItem item = queue.get(i);
            if (item != null && itemId.equals(item.id())) {
                return i;
            }
        }
        return -1;
    }

    private void loadSnapshot() {
        if (!Files.exists(storePath)) {
            return;
        }
        try {
            QueueSnapshot snapshot = objectMapper.readValue(storePath.toFile(), QueueSnapshot.class);
            if (snapshot == null) {
                return;
            }
            if (snapshot.queues != null) {
                for (Map.Entry<String, Map<String, List<QueueItem>>> botEntry : snapshot.queues.entrySet()) {
                    if (botEntry == null || botEntry.getKey() == null || botEntry.getValue() == null) {
                        continue;
                    }
                    Map<String, List<QueueItem>> playlists = new ConcurrentHashMap<>();
                    for (Map.Entry<String, List<QueueItem>> playlistEntry : botEntry.getValue().entrySet()) {
                        if (playlistEntry == null || playlistEntry.getKey() == null) {
                            continue;
                        }
                        List<QueueItem> items = playlistEntry.getValue() == null
                            ? new ArrayList<>()
                            : new ArrayList<>(playlistEntry.getValue());
                        playlists.put(playlistEntry.getKey(), Collections.synchronizedList(items));
                    }
                    queues.put(botEntry.getKey(), playlists);
                }
            }
            if (snapshot.activePlaylists != null) {
                activePlaylists.putAll(snapshot.activePlaylists);
            }
            if (snapshot.playlistPositions != null) {
                for (Map.Entry<String, Map<String, Integer>> entry : snapshot.playlistPositions.entrySet()) {
                    if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                        continue;
                    }
                    Map<String, Integer> positions = new ConcurrentHashMap<>();
                    for (Map.Entry<String, Integer> posEntry : entry.getValue().entrySet()) {
                        if (posEntry == null || posEntry.getKey() == null || posEntry.getValue() == null) {
                            continue;
                        }
                        positions.put(posEntry.getKey(), Math.max(0, posEntry.getValue()));
                    }
                    playlistPositions.put(entry.getKey(), positions);
                }
            }
            clampPositions();
        } catch (IOException ex) {
            log.warn("Failed to load queue snapshot from {}", storePath, ex);
        }
    }

    private void persistSnapshot() {
        try {
            Files.createDirectories(storePath.getParent());
            QueueSnapshot snapshot = new QueueSnapshot(
                deepCopyQueues(),
                new ConcurrentHashMap<>(activePlaylists),
                new ConcurrentHashMap<>(playlistPositions)
            );
            Path tmp = storePath.resolveSibling(storePath.getFileName().toString() + ".tmp");
            objectMapper.writeValue(tmp.toFile(), snapshot);
            Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            log.warn("Failed to persist queue snapshot to {}", storePath, ex);
        }
    }

    private Map<String, Map<String, List<QueueItem>>> deepCopyQueues() {
        Map<String, Map<String, List<QueueItem>>> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, Map<String, List<QueueItem>>> botEntry : queues.entrySet()) {
            if (botEntry == null || botEntry.getKey() == null || botEntry.getValue() == null) {
                continue;
            }
            Map<String, List<QueueItem>> playlists = new ConcurrentHashMap<>();
            for (Map.Entry<String, List<QueueItem>> playlistEntry : botEntry.getValue().entrySet()) {
                if (playlistEntry == null || playlistEntry.getKey() == null) {
                    continue;
                }
                List<QueueItem> items = playlistEntry.getValue();
                playlists.put(playlistEntry.getKey(), items == null ? List.of() : new ArrayList<>(items));
            }
            result.put(botEntry.getKey(), playlists);
        }
        return result;
    }

    private void clampPositions() {
        for (Map.Entry<String, Map<String, Integer>> botEntry : playlistPositions.entrySet()) {
            if (botEntry == null || botEntry.getKey() == null || botEntry.getValue() == null) {
                continue;
            }
            String botId = botEntry.getKey();
            Map<String, Integer> positions = botEntry.getValue();
            Map<String, List<QueueItem>> playlists = queues.get(botId);
            if (playlists == null) {
                continue;
            }
            for (Map.Entry<String, Integer> posEntry : positions.entrySet()) {
                if (posEntry == null || posEntry.getKey() == null || posEntry.getValue() == null) {
                    continue;
                }
                List<QueueItem> items = playlists.get(posEntry.getKey());
                if (items == null) {
                    continue;
                }
                int max = items.size();
                int value = Math.max(0, Math.min(max, posEntry.getValue()));
                posEntry.setValue(value);
            }
        }
    }

    private record QueueSnapshot(
        Map<String, Map<String, List<QueueItem>>> queues,
        Map<String, String> activePlaylists,
        Map<String, Map<String, Integer>> playlistPositions
    ) {
    }
}
