package com.ssuai.global.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * Short-TTL in-memory cache with single-flight (request-coalescing) semantics.
 *
 * <p>The five ssuMCP read caches — library floor seats, library room seats,
 * book search, saint schedule, notice list — all repeated the same delicate
 * miss-path skeleton: check freshness, {@code putIfAbsent} an in-flight future,
 * double-check after winning the race, load once, complete the future, and have
 * losers wait on it with careful {@link InterruptedException}/{@link
 * ExecutionException} unwrapping. That block is easy to get subtly wrong (poison
 * the cache on failure, leak the in-flight entry, swallow interruption), so it
 * lives here once and each cache supplies only what differs: the key, the TTL,
 * the backing-map policy, and the loader.
 *
 * <p><b>Single-flight:</b> concurrent misses for the same key collapse into one
 * loader invocation; the winner loads while losers block on its future. On
 * loader failure the future completes exceptionally and the entry is <i>not</i>
 * stored, so the cache is never poisoned and the next caller retries cleanly.
 *
 * <p><b>Freshness</b> is clock-based ({@code expiresAt.isAfter(now)}), so tests
 * inject a mutable {@link Clock} to advance past the TTL deterministically.
 *
 * <p>Not a Spring bean — each cache owns its own instance so keys, value types,
 * and TTLs stay independent.
 *
 * @param <K> cache key (must have value-based {@code equals}/{@code hashCode})
 * @param <V> cached value
 */
public final class SingleFlightCache<K, V> {

    /**
     * A cached value with its absolute expiry. Exposed so loaders that need a
     * non-default expiry (e.g. a Redis-L2 hit stored at half TTL to avoid
     * doubling staleness) can build one via {@link #newEntry(Object, Duration)}.
     */
    public record Entry<V>(V value, Instant expiresAt) {
        public Entry {
            if (value == null) {
                throw new IllegalArgumentException("cache value cannot be null");
            }
            if (expiresAt == null) {
                throw new IllegalArgumentException("cache expiresAt cannot be null");
            }
        }
    }

    private final String label;
    private final Duration ttl;
    private final Clock clock;
    private final Map<K, Entry<V>> entries;
    private final ConcurrentHashMap<K, CompletableFuture<Entry<V>>> inflight = new ConcurrentHashMap<>();

    private SingleFlightCache(String label, Duration ttl, Clock clock, Map<K, Entry<V>> entries) {
        this.label = label;
        this.ttl = ttl;
        this.clock = clock;
        this.entries = entries;
    }

    /**
     * A cache with an unbounded {@link ConcurrentHashMap} backing map. Suitable
     * when the key space is naturally small and bounded (e.g. library floors or
     * rooms).
     */
    public static <K, V> SingleFlightCache<K, V> unbounded(String label, Duration ttl, Clock clock) {
        return new SingleFlightCache<>(label, ttl, clock, new ConcurrentHashMap<>());
    }

    /**
     * A cache with an access-ordered LRU backing map capped at {@code capacity}.
     * Suitable for open key spaces a user could grow unbounded (search queries,
     * per-student schedules).
     */
    public static <K, V> SingleFlightCache<K, V> lruBounded(
            String label, Duration ttl, Clock clock, int capacity) {
        Map<K, Entry<V>> lru = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
                return size() > capacity;
            }
        });
        return new SingleFlightCache<>(label, ttl, clock, lru);
    }

    /**
     * Return the fresh cached value for {@code key}, or load it once (coalescing
     * concurrent misses) and cache it at the default TTL.
     */
    public V get(K key, Function<? super K, ? extends V> loader) {
        return getWithEntry(key, k -> newEntry(loader.apply(k)));
    }

    /**
     * Like {@link #get(Object, Function)} but the loader returns a full {@link
     * Entry}, so it controls the expiry (and can run side effects such as an L2
     * write) for the value it produces.
     */
    public V getWithEntry(K key, Function<? super K, Entry<V>> entryLoader) {
        Entry<V> cached = entries.get(key);
        if (isFresh(cached)) {
            return cached.value();
        }

        CompletableFuture<Entry<V>> mine = new CompletableFuture<>();
        CompletableFuture<Entry<V>> winner = inflight.putIfAbsent(key, mine);
        if (winner == null) {
            try {
                Entry<V> refreshed = entries.get(key);
                if (isFresh(refreshed)) {
                    mine.complete(refreshed);
                    return refreshed.value();
                }

                Entry<V> loaded = entryLoader.apply(key);
                entries.put(key, loaded);
                mine.complete(loaded);
                return loaded.value();
            } catch (RuntimeException exception) {
                mine.completeExceptionally(exception);
                throw exception;
            } finally {
                inflight.remove(key, mine);
            }
        }

        try {
            return winner.get().value();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + " wait interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(label + " fetch failed", cause);
        }
    }

    /** Build an entry for {@code value} expiring one default TTL from now. */
    public Entry<V> newEntry(V value) {
        return new Entry<>(value, clock.instant().plus(ttl));
    }

    /** Build an entry for {@code value} expiring {@code customTtl} from now. */
    public Entry<V> newEntry(V value, Duration customTtl) {
        return new Entry<>(value, clock.instant().plus(customTtl));
    }

    /** The cache's default TTL (loaders needing a fraction of it, e.g. L2). */
    public Duration ttl() {
        return ttl;
    }

    /** Drop all cached entries (in-flight loads are unaffected). */
    public void invalidate() {
        entries.clear();
    }

    /** Number of cached entries — package-visible for cache tests. */
    public int size() {
        return entries.size();
    }

    private boolean isFresh(Entry<V> entry) {
        return entry != null && entry.expiresAt().isAfter(clock.instant());
    }
}
