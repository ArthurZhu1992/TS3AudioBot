package pub.longyi.ts3audiobot.search;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class SearchCache<T> {
    private final ConcurrentHashMap<String, CacheEntry<T>> store = new ConcurrentHashMap<>();

    public Optional<T> get(String key) {
        if (key == null) {
            return Optional.empty();
        }
        CacheEntry<T> entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expiresAt != null && Instant.now().isAfter(entry.expiresAt)) {
            store.remove(key);
            return Optional.empty();
        }
        return Optional.ofNullable(entry.value);
    }

    public void put(String key, T value, Duration ttl) {
        if (key == null || ttl == null) {
            return;
        }
        Instant expiresAt = ttl.isZero() ? null : Instant.now().plus(ttl);
        store.put(key, new CacheEntry<>(value, expiresAt));
    }

    public void clear() {
        store.clear();
    }

    private static final class CacheEntry<T> {
        private final T value;
        private final Instant expiresAt;

        private CacheEntry(T value, Instant expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }
}
