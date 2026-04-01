package pub.longyi.ts3audiobot.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.resolver.ResolverRegistry;
import pub.longyi.ts3audiobot.resolver.TrackResolver;
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
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by: Arthur Zhu
 * Email: zhushuai.net@gmail.com
 * Date: 2026-02-07 00:38
 * GitHub: https://github.com/ArthurZhu1992
 *
 * Description:
 * 璐熻矗 QueueService 鐩稿叧鍔熻兘銆? */


/**
 * QueueService 鐩稿叧鍔熻兘銆? *
 * <p>鑱岃矗锛氳礋璐?QueueService 鐩稿叧鍔熻兘銆?/p>
 * <p>绾跨▼瀹夊叏锛氭棤鏄惧紡淇濊瘉銆?/p>
 * <p>绾︽潫锛氳皟鐢ㄦ柟闇€閬靛畧鏂规硶濂戠害銆?/p>
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
    private final List<TrackResolver> repairResolvers;


    /**
     * 鍒涘缓 QueueService 瀹炰緥銆?     * @param configService 鍙傛暟 configService
     */
    @Autowired
    public QueueService(ConfigService configService) {
        this(configService, initRepairResolvers(configService));
    }

    QueueService(ConfigService configService, List<TrackResolver> repairResolvers) {
        this.storePath = configService.getQueueStorePath();
        this.repairResolvers = repairResolvers == null ? List.of() : List.copyOf(repairResolvers);
        this.objectMapper = new ObjectMapper()


            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        loadSnapshot();
    }


    /**
     * 鎵ц list 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @return 杩斿洖鍊?     */
    public List<QueueItem> list(String botId) {
        return list(botId, getActivePlaylist(botId));
    }

    public List<QueueItem> rawList(String botId) {
        return rawList(botId, getActivePlaylist(botId));
    }


    /**
     * 鎵ц add 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @param track 鍙傛暟 track
     * @param addedBy 鍙傛暟 addedBy
     * @return 杩斿洖鍊?     */
    public QueueItem add(String botId, Track track, String addedBy) {

        return add(botId, getActivePlaylist(botId), track, addedBy);
    }


    /**
     * 鎵ц next 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @return 杩斿洖鍊?     */
    public QueueItem next(String botId) {
        return next(botId, getActivePlaylist(botId));
    }


    /**
     * 鎵ц getPosition 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @return 杩斿洖鍊?     */
    public int getPosition(String botId) {
        return getPosition(botId, getActivePlaylist(botId));
    }


    /**
     * 鎵ц getPosition 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @param playlistId 鍙傛暟 playlistId
     * @return 杩斿洖鍊?     */
    public int getPosition(String botId, String playlistId) {
        synchronized (stateLock) {
            return resolvePosition(botId, playlistId);
        }
    }


    /**
     * 鎵ц stepBack 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     */
    public void stepBack(String botId) {
        stepBack(botId, getActivePlaylist(botId));

    }


    /**
     * 鎵ц stepBack 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @param playlistId 鍙傛暟 playlistId
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
     * 鎵ц clear 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     */
    public void clear(String botId) {

        clear(botId, getActivePlaylist(botId));
    }


    /**
     * 鎵ц list 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @param playlistId 鍙傛暟 playlistId
     * @return 杩斿洖鍊?     */
    public List<QueueItem> list(String botId, String playlistId) {
        synchronized (stateLock) {
            List<QueueItem> queue = resolveQueue(botId, playlistId);
            if (repairQueueItems(botId, normalizePlaylistId(playlistId), queue)) {
                persistSnapshot();
            }
            return Collections.unmodifiableList(new ArrayList<>(queue));
        }

    }

    public List<QueueItem> rawList(String botId, String playlistId) {
        synchronized (stateLock) {
            List<QueueItem> queue = resolveQueue(botId, playlistId);
            return Collections.unmodifiableList(new ArrayList<>(queue));
        }
    }


    /**
     * 鎵ц add 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @param playlistId 鍙傛暟 playlistId
     * @param track 鍙傛暟 track
     * @param addedBy 鍙傛暟 addedBy
     * @return 杩斿洖鍊?     */
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
     * 鎵ц next 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @param playlistId 鍙傛暟 playlistId
     * @return 杩斿洖鍊?     */
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
     * 鎵ц nextRandom 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @param playlistId 鍙傛暟 playlistId
     * @param random 鍙傛暟 random
     * @return 杩斿洖鍊?     */
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
     * 鎵ц nextLoop 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @param playlistId 鍙傛暟 playlistId
     * @return 杩斿洖鍊?     */
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
     * 鎵ц nextListLoop 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @param playlistId 鍙傛暟 playlistId
     * @return 杩斿洖鍊?     */
    public QueueItem nextListLoop(String botId, String playlistId) {
        synchronized (stateLock) {
            List<QueueItem> queue = resolveQueue(botId, playlistId);
            if (queue.isEmpty()) {
                return null;
            }
            int size = queue.size();
            int index = resolvePosition(botId, playlistId);
            if (index >= size) {
                index = 0;
            }
            QueueItem item = queue.get(index);
            setPosition(botId, playlistId, index + 1);
            persistSnapshot();
            return item;
        }
    }


    /**
     * 鎵ц clear 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @param playlistId 鍙傛暟 playlistId
     */
    public void clear(String botId, String playlistId) {
        synchronized (stateLock) {
            resolveQueue(botId, playlistId).clear();
            setPosition(botId, playlistId, 0);
            persistSnapshot();
        }
    }


    /**
     * 鎵ц listPlaylists 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @return 杩斿洖鍊?     */
    public List<String> listPlaylists(String botId) {

        Map<String, List<QueueItem>> playlists = resolvePlaylists(botId);
        List<String> result = new ArrayList<>(playlists.keySet());
        result.sort(String::compareToIgnoreCase);
        return result;
    }


    /**
     * 鎵ц getActivePlaylist 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @return 杩斿洖鍊?     */
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
     * 鎵ц setActivePlaylist 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @param playlistId 鍙傛暟 playlistId
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
     * 鎵ц createPlaylist 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @param playlistId 鍙傛暟 playlistId
     * @return 杩斿洖鍊?     */
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
     * 鎵ц removePlaylist 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @param playlistId 鍙傛暟 playlistId
     * @return 杩斿洖鍊?     */
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
     * 鎵ц renamePlaylist 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @param playlistId 鍙傛暟 playlistId
     * @param newPlaylistId 鍙傛暟 newPlaylistId
     * @return 杩斿洖鍊?     */
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
     * 鎵ц jumpTo 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @param playlistId 鍙傛暟 playlistId
     * @param itemId 鍙傛暟 itemId
     * @return 杩斿洖鍊?     */
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
     * 鎵ц removeItem 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @param playlistId 鍙傛暟 playlistId
     * @param itemId 鍙傛暟 itemId
     * @return 杩斿洖鍊?     */
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
     * 鎵ц updateTrack 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @param playlistId 鍙傛暟 playlistId
     * @param itemId 鍙傛暟 itemId
     * @param track 鍙傛暟 track
     * @return 杩斿洖鍊?     */
    public boolean updateTrack(String botId, String playlistId, String itemId, Track track) {
        if (botId == null || itemId == null || itemId.isBlank() || track == null) {
            return false;
        }
        String resolved = normalizePlaylistId(playlistId);
        synchronized (stateLock) {
            List<QueueItem> queue = resolveQueue(botId, resolved);
            int index = findIndex(queue, itemId);
            if (index < 0) {
                return false;
            }
            QueueItem existing = queue.get(index);
            queue.set(index, new QueueItem(
                existing.id(),
                existing.botId(),
                existing.playlistId(),
                track,
                existing.addedAt(),
                existing.addedBy()
            ));
            persistSnapshot();
            return true;
        }
    }

    public QueueItem refreshItem(String botId, String playlistId, String itemId) {
        if (botId == null || itemId == null || itemId.isBlank()) {
            return null;
        }
        String resolved = normalizePlaylistId(playlistId);
        synchronized (stateLock) {
            List<QueueItem> queue = resolveQueue(botId, resolved);
            int index = findIndex(queue, itemId);
            if (index < 0) {
                return null;
            }
            QueueItem item = queue.get(index);
            if (item == null || item.track() == null) {
                return item;
            }
            Track repaired = repairTrack(item.track());
            if (repaired.equals(item.track())) {
                return item;
            }
            QueueItem updated = new QueueItem(
                item.id(),
                item.botId(),
                item.playlistId(),
                repaired,
                item.addedAt(),
                item.addedBy()
            );
            queue.set(index, updated);
            persistSnapshot();
            return updated;
        }
    }


    /**
     * 鎵ц hasPlaylist 鎿嶄綔銆?     * @param botId 鍙傛暟 botId
     * @param playlistId 鍙傛暟 playlistId
     * @return 杩斿洖鍊?     */
    public boolean hasPlaylist(String botId, String playlistId) {
        String resolved = normalizePlaylistId(playlistId);
        return resolvePlaylists(botId).containsKey(resolved);
    }


    /**
     * 鎵ц listStoredBotIds 鎿嶄綔銆?     * @return 杩斿洖鍊?     */
    public List<String> listStoredBotIds() {
        return new ArrayList<>(queues.keySet());
    }


    /**
     * 鎵ц renameBotId 鎿嶄綔銆?     * @param fromBotId 鍙傛暟 fromBotId
     * @param toBotId 鍙傛暟 toBotId
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

    private static List<TrackResolver> initRepairResolvers(ConfigService configService) {
        try {
            return new ResolverRegistry(configService).list();
        } catch (Exception ex) {
            log.warn("Failed to initialize queue repair resolvers", ex);
            return List.of();
        }
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

    private boolean repairQueueItems(String botId, String playlistId, List<QueueItem> queue) {
        boolean changed = false;
        for (int i = 0; i < queue.size(); i++) {
            QueueItem item = queue.get(i);
            if (item == null || item.track() == null) {
                continue;
            }
            Track repaired = repairTrack(item.track());
            if (repaired == null || repaired.equals(item.track())) {
                continue;
            }
            queue.set(i, new QueueItem(
                item.id(),
                botId,
                playlistId,
                repaired,
                item.addedAt(),
                item.addedBy()
            ));
            changed = true;
        }
        return changed;
    }

    private Track repairTrack(Track track) {
        if (!needsRepair(track)) {
            return track;
        }
        for (TrackResolver resolver : prioritizedResolvers(track)) {
            Optional<Track> resolved = resolveQuietly(resolver, track.sourceId());
            if (resolved.isEmpty()) {
                continue;
            }
            Track merged = mergeTrack(track, resolved.get());
            if (!merged.equals(track)) {
                return merged;
            }
        }
        return track;
    }

    private List<TrackResolver> prioritizedResolvers(Track track) {
        if (repairResolvers.isEmpty()) {
            return List.of();
        }
        String canonicalSourceType = canonicalSourceType(track == null ? null : track.sourceType(), track == null ? null : track.sourceId());
        if (canonicalSourceType.isBlank()) {
            return List.of();
        }
        List<TrackResolver> prioritized = new ArrayList<>(1);
        for (TrackResolver resolver : repairResolvers) {
            if (resolver != null && equalsIgnoreCase(resolver.sourceType(), canonicalSourceType)) {
                prioritized.add(resolver);
            }
        }
        return prioritized;
    }

    private Optional<Track> resolveQuietly(TrackResolver resolver, String sourceId) {
        try {
            return resolver == null ? Optional.empty() : resolver.resolve(sourceId);
        } catch (Exception ex) {
            log.warn("Failed to repair queue track from sourceId={} via resolver={}", sourceId, resolver == null ? "" : resolver.sourceType(), ex);
            return Optional.empty();
        }
    }

    private Track mergeTrack(Track existing, Track resolved) {
        String sourceId = firstNonBlank(existing.sourceId(), resolved.sourceId());
        // Future resolver-supported fields should be merged here so lazy list loading
        // can continue repairing incomplete persisted items without adding a new flow.
        return new Track(
            firstNonBlank(existing.id(), resolved.id()),
            chooseTitle(existing.title(), sourceId, resolved.title()),
            firstNonBlank(existing.sourceType(), resolved.sourceType()),
            sourceId,
            firstNonBlank(existing.streamUrl(), resolved.streamUrl()),
            existing.durationMs() > 0 ? existing.durationMs() : Math.max(0, resolved.durationMs()),
            firstNonBlank(existing.coverUrl(), resolved.coverUrl()),
            firstNonBlank(existing.artist(), resolved.artist()),
            choosePlayCount(existing.playCount(), resolved.playCount())
        );
    }

    private boolean needsRepair(Track track) {
        if (track == null || !isHttpUrl(track.sourceId())) {
            return false;
        }
        return titleNeedsRepair(track.title(), track.sourceId())
            || isBlank(track.sourceType())
            || isBlank(track.streamUrl())
            || track.durationMs() <= 0
            || isBlank(track.coverUrl());
    }

    private boolean titleNeedsRepair(String title, String sourceId) {
        return isBlank(title) || equalsIgnoreCase(title, sourceId) || isHttpUrl(title);
    }

    private boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.trim().toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private String canonicalSourceType(String sourceType, String sourceId) {
        String normalized = normalizeSourceAlias(sourceType);
        if (!normalized.isBlank()) {
            return normalized;
        }
        if (isBlank(sourceId)) {
            return "";
        }
        String lower = sourceId.trim().toLowerCase();
        if (lower.contains("music.youtube.com")) {
            return "ytmusic";
        }
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
            return "yt";
        }
        if (lower.contains("music.163.com") || lower.contains("163.com")) {
            return "netease";
        }
        if (lower.contains("y.qq.com") || lower.contains("qq.com")) {
            return "qq";
        }
        return "";
    }

    private String normalizeSourceAlias(String sourceType) {
        if (isBlank(sourceType)) {
            return "";
        }
        String lower = sourceType.trim().toLowerCase();
        return switch (lower) {
            case "yt", "youtube" -> "yt";
            case "ytmusic", "youtube music" -> "ytmusic";
            case "netease", "netease music", "网易云音乐" -> "netease";
            case "qq", "qqmusic", "qq music", "qq音乐" -> "qq";
            default -> "";
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String chooseTitle(String currentTitle, String sourceId, String resolvedTitle) {
        if (!titleNeedsRepair(currentTitle, sourceId)) {
            return currentTitle;
        }
        return firstNonBlank(resolvedTitle, currentTitle, sourceId);
    }

    private Long choosePlayCount(Long currentPlayCount, Long resolvedPlayCount) {
        if (currentPlayCount != null && currentPlayCount > 0L) {
            return currentPlayCount;
        }
        if (resolvedPlayCount != null && resolvedPlayCount > 0L) {
            return resolvedPlayCount;
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private boolean equalsIgnoreCase(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.equalsIgnoreCase(right);
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
