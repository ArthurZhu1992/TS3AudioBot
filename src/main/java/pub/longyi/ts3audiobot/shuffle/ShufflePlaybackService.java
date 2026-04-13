package pub.longyi.ts3audiobot.shuffle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pub.longyi.ts3audiobot.config.ConfigService;
import pub.longyi.ts3audiobot.queue.QueueItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 随机播放状态服务。
 *
 * <p>职责：
 * <ul>
 *     <li>按 bot + playlist 维护独立随机序列状态。</li>
 *     <li>在歌单变化/模式切换时重建随机队列。</li>
 *     <li>提供 next/prev 对称能力，确保随机模式可前后回退。</li>
 *     <li>将随机状态持久化到独立文件，降低与队列快照的耦合。</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public final class ShufflePlaybackService {
    private static final int SNAPSHOT_VERSION = 1;
    private static final String STORE_FILE = "shuffle-state.json";

    private final Map<ShuffleSessionKey, ShuffleSessionState> states = new ConcurrentHashMap<>();
    private final Object stateLock = new Object();
    private final ObjectMapper objectMapper;
    private final Path storePath;

    @Autowired
    public ShufflePlaybackService(ConfigService configService) {
        this.storePath = configService.getDataDir().resolve(STORE_FILE).toAbsolutePath().normalize();
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        loadSnapshot();
    }

    /**
     * 随机模式获取下一首，遵循当前随机序列。
     */
    public QueueItem next(
        String botId,
        String playlistId,
        List<QueueItem> queueItems,
        Random random,
        String currentItemId
    ) {
        synchronized (stateLock) {
            SelectionContext context = ensureReady(botId, playlistId, queueItems, random, currentItemId, false);
            if (context == null || context.orderedIds().isEmpty()) {
                return null;
            }
            ShuffleSessionState state = context.state();
            int nextIndex = state.cursor() + 1;
            if (nextIndex >= context.orderedIds().size()) {
                rebuildOrder(state, context.orderedIds(), random, currentItemId);
                nextIndex = state.cursor() + 1;
                if (nextIndex >= state.orderItemIds().size()) {
                    // 单曲场景允许重复返回当前曲目，避免随机模式“断播”。
                    nextIndex = Math.max(0, state.cursor());
                }
            }
            String targetId = state.orderItemIds().get(nextIndex);
            state.setCursor(nextIndex);
            state.touch();
            persistSnapshot();
            return context.itemById().get(targetId);
        }
    }

    /**
     * 随机模式回退上一首，按随机序列反向移动。
     */
    public QueueItem previous(
        String botId,
        String playlistId,
        List<QueueItem> queueItems,
        Random random,
        String currentItemId
    ) {
        synchronized (stateLock) {
            SelectionContext context = ensureReady(botId, playlistId, queueItems, random, currentItemId, false);
            if (context == null || context.orderedIds().isEmpty()) {
                return null;
            }
            ShuffleSessionState state = context.state();
            int prevIndex = state.cursor() - 1;
            if (prevIndex < 0) {
                prevIndex = state.cursor() >= 0 ? state.cursor() : 0;
            }
            String targetId = state.orderItemIds().get(prevIndex);
            state.setCursor(prevIndex);
            state.touch();
            persistSnapshot();
            return context.itemById().get(targetId);
        }
    }

    /**
     * 强制重建某个会话的随机序列（用于模式切换或显式重置）。
     */
    public void rebuild(
        String botId,
        String playlistId,
        List<QueueItem> queueItems,
        Random random,
        String currentItemId
    ) {
        synchronized (stateLock) {
            ensureReady(botId, playlistId, queueItems, random, currentItemId, true);
            persistSnapshot();
        }
    }

    /**
     * 标记会话脏状态。下次随机选歌会基于最新歌单重建顺序。
     */
    public void markDirty(String botId, String playlistId) {
        synchronized (stateLock) {
            ShuffleSessionKey key = safeKey(botId, playlistId);
            if (key == null) {
                return;
            }
            ShuffleSessionState state = states.computeIfAbsent(key, ignored -> new ShuffleSessionState());
            state.setDirty(true);
            state.touch();
            persistSnapshot();
        }
    }

    /**
     * 删除会话状态（如歌单被删除后调用）。
     */
    public void removeSession(String botId, String playlistId) {
        synchronized (stateLock) {
            ShuffleSessionKey key = safeKey(botId, playlistId);
            if (key == null) {
                return;
            }
            states.remove(key);
            persistSnapshot();
        }
    }

    /**
     * 歌单重命名时迁移随机状态，避免丢失上下文。
     */
    public void renamePlaylist(String botId, String fromPlaylistId, String toPlaylistId) {
        synchronized (stateLock) {
            ShuffleSessionKey from = safeKey(botId, fromPlaylistId);
            ShuffleSessionKey to = safeKey(botId, toPlaylistId);
            if (from == null || to == null || from.equals(to)) {
                return;
            }
            ShuffleSessionState state = states.remove(from);
            if (state != null) {
                state.setDirty(true);
                state.touch();
                states.put(to, state);
                persistSnapshot();
            }
        }
    }

    private SelectionContext ensureReady(
        String botId,
        String playlistId,
        List<QueueItem> queueItems,
        Random random,
        String currentItemId,
        boolean forceRebuild
    ) {
        ShuffleSessionKey key = safeKey(botId, playlistId);
        if (key == null) {
            return null;
        }
        List<QueueItem> safeItems = queueItems == null ? List.of() : queueItems;
        Map<String, QueueItem> byId = new LinkedHashMap<>();
        List<String> orderedIds = new ArrayList<>(safeItems.size());
        for (QueueItem item : safeItems) {
            if (item == null || item.id() == null || item.id().isBlank()) {
                continue;
            }
            String itemId = item.id().trim();
            if (byId.containsKey(itemId)) {
                continue;
            }
            byId.put(itemId, item);
            orderedIds.add(itemId);
        }
        ShuffleSessionState state = states.computeIfAbsent(key, ignored -> new ShuffleSessionState());
        if (orderedIds.isEmpty()) {
            state.setOrderItemIds(List.of());
            state.setCursor(-1);
            state.setPlaylistFingerprint(fingerprint(List.of()));
            state.setDirty(false);
            state.touch();
            return new SelectionContext(state, orderedIds, byId);
        }
        String nextFingerprint = fingerprint(orderedIds);
        Set<String> expectedSet = new LinkedHashSet<>(orderedIds);
        boolean structureChanged = !nextFingerprint.equals(state.playlistFingerprint());
        boolean orderInvalid = !isOrderCompatible(state.orderItemIds(), expectedSet);
        if (forceRebuild || state.dirty() || structureChanged || orderInvalid) {
            rebuildOrder(state, orderedIds, random, currentItemId);
            state.setPlaylistFingerprint(nextFingerprint);
            state.setDirty(false);
        } else {
            alignCursor(state, currentItemId);
        }
        state.touch();
        return new SelectionContext(state, orderedIds, byId);
    }

    private void rebuildOrder(ShuffleSessionState state, List<String> orderedIds, Random random, String anchorItemId) {
        List<String> rebuilt = new ArrayList<>(orderedIds);
        Random effectiveRandom = random == null ? new Random() : random;
        Collections.shuffle(rebuilt, effectiveRandom);
        int anchorIndex = indexOf(rebuilt, anchorItemId);
        if (anchorIndex >= 0) {
            String anchor = rebuilt.remove(anchorIndex);
            rebuilt.add(0, anchor);
            state.setCursor(0);
        } else {
            state.setCursor(-1);
        }
        state.setOrderItemIds(rebuilt);
    }

    private void alignCursor(ShuffleSessionState state, String currentItemId) {
        int index = indexOf(state.orderItemIds(), currentItemId);
        if (index >= 0) {
            state.setCursor(index);
        }
    }

    private int indexOf(List<String> values, String target) {
        if (values == null || values.isEmpty() || target == null || target.isBlank()) {
            return -1;
        }
        String normalized = target.trim();
        for (int i = 0; i < values.size(); i++) {
            if (normalized.equals(values.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private boolean isOrderCompatible(List<String> order, Set<String> expectedIds) {
        if (order == null || expectedIds == null) {
            return false;
        }
        if (order.size() != expectedIds.size()) {
            return false;
        }
        Set<String> actual = new LinkedHashSet<>(order);
        return actual.size() == order.size() && actual.equals(expectedIds);
    }

    private String fingerprint(List<String> orderedIds) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String itemId : orderedIds) {
                if (itemId == null) {
                    continue;
                }
                digest.update(itemId.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(orderedIds.hashCode());
        }
    }

    private ShuffleSessionKey safeKey(String botId, String playlistId) {
        try {
            return ShuffleSessionKey.of(botId, playlistId);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void loadSnapshot() {
        if (!Files.exists(storePath)) {
            return;
        }
        try {
            Snapshot snapshot = objectMapper.readValue(storePath.toFile(), Snapshot.class);
            if (snapshot == null || snapshot.sessions == null) {
                return;
            }
            if (snapshot.version > SNAPSHOT_VERSION) {
                log.warn("Shuffle snapshot version {} is newer than supported {}, loading best effort", snapshot.version, SNAPSHOT_VERSION);
            }
            for (Map.Entry<String, Map<String, PersistedSessionState>> botEntry : snapshot.sessions.entrySet()) {
                if (botEntry == null || botEntry.getKey() == null || botEntry.getValue() == null) {
                    continue;
                }
                String botId = botEntry.getKey();
                for (Map.Entry<String, PersistedSessionState> playlistEntry : botEntry.getValue().entrySet()) {
                    if (playlistEntry == null || playlistEntry.getKey() == null || playlistEntry.getValue() == null) {
                        continue;
                    }
                    ShuffleSessionKey key = safeKey(botId, playlistEntry.getKey());
                    if (key == null) {
                        continue;
                    }
                    PersistedSessionState persisted = playlistEntry.getValue();
                    ShuffleSessionState state = new ShuffleSessionState();
                    state.setOrderItemIds(persisted.orderItemIds == null ? List.of() : persisted.orderItemIds);
                    state.setCursor(persisted.cursor);
                    state.setPlaylistFingerprint(persisted.playlistFingerprint);
                    state.setDirty(persisted.dirty);
                    state.touch();
                    states.put(key, state);
                }
            }
        } catch (IOException ex) {
            log.warn("Failed to load shuffle snapshot from {}", storePath, ex);
        }
    }

    private void persistSnapshot() {
        try {
            Files.createDirectories(storePath.getParent());
            Map<String, Map<String, PersistedSessionState>> grouped = new LinkedHashMap<>();
            for (Map.Entry<ShuffleSessionKey, ShuffleSessionState> entry : states.entrySet()) {
                if (entry == null || entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                ShuffleSessionKey key = entry.getKey();
                ShuffleSessionState state = entry.getValue();
                grouped.computeIfAbsent(key.botId(), ignored -> new LinkedHashMap<>())
                    .put(key.playlistId(), new PersistedSessionState(
                        new ArrayList<>(state.orderItemIds()),
                        state.cursor(),
                        state.playlistFingerprint(),
                        state.dirty(),
                        Instant.ofEpochMilli(state.updatedAt())
                    ));
            }
            Snapshot snapshot = new Snapshot(SNAPSHOT_VERSION, grouped);
            Path tmp = storePath.resolveSibling(storePath.getFileName().toString() + ".tmp");
            objectMapper.writeValue(tmp.toFile(), snapshot);
            Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            log.warn("Failed to persist shuffle snapshot to {}", storePath, ex);
        }
    }

    private record SelectionContext(
        ShuffleSessionState state,
        List<String> orderedIds,
        Map<String, QueueItem> itemById
    ) {
    }

    private record Snapshot(
        int version,
        Map<String, Map<String, PersistedSessionState>> sessions
    ) {
    }

    private record PersistedSessionState(
        List<String> orderItemIds,
        int cursor,
        String playlistFingerprint,
        boolean dirty,
        Instant updatedAt
    ) {
    }
}
