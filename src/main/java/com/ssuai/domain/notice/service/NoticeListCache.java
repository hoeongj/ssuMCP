package com.ssuai.domain.notice.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ssuai.domain.notice.connector.NoticeConnectorProperties;
import com.ssuai.domain.notice.dto.NoticeListResponse;

/**
 * TTL cache for notice list and search results.
 * LRU-bounded to 500 entries. Single-flight deduplication prevents
 * cache-stampede on concurrent identical requests.
 *
 * <p>TTL is read from {@code ssuai.notice.cache-ttl} (default 5m).
 */
@Component
public class NoticeListCache {

    private static final Logger log = LoggerFactory.getLogger(NoticeListCache.class);
    static final int DEFAULT_CAPACITY = 500;

    private final Duration ttl;
    private final Clock clock;
    private final Map<Key, Entry> entries;
    private final ConcurrentHashMap<Key, CompletableFuture<Entry>> inflight = new ConcurrentHashMap<>();

    @Autowired
    public NoticeListCache(NoticeConnectorProperties properties) {
        this(properties.getCacheTtl(), Clock.systemUTC(), DEFAULT_CAPACITY);
    }

    NoticeListCache(Duration ttl, Clock clock, int capacity) {
        this.ttl = ttl != null ? ttl : Duration.ofMinutes(5);
        this.clock = clock;
        this.entries = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
                return size() > capacity;
            }
        });
    }

    public NoticeListResponse get(Key key, Supplier<NoticeListResponse> loader) {
        Entry cached = entries.get(key);
        if (isFresh(cached)) {
            log.debug("notice cache hit: key={}", key);
            return cached.value;
        }

        CompletableFuture<Entry> mine = new CompletableFuture<>();
        CompletableFuture<Entry> winner = inflight.putIfAbsent(key, mine);
        if (winner == null) {
            try {
                Entry recheck = entries.get(key);
                if (isFresh(recheck)) {
                    mine.complete(recheck);
                    return recheck.value;
                }
                NoticeListResponse fresh = loader.get();
                Entry entry = new Entry(fresh, clock.instant().plus(ttl));
                entries.put(key, entry);
                mine.complete(entry);
                log.debug("notice cache miss+fill: key={} items={}", key, fresh.items().size());
                return entry.value;
            } catch (RuntimeException exception) {
                mine.completeExceptionally(exception);
                throw exception;
            } finally {
                inflight.remove(key, mine);
            }
        }

        try {
            return winner.get().value;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("notice cache wait interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("notice cache fetch failed", cause);
        }
    }

    public void invalidate() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }

    private boolean isFresh(Entry entry) {
        return entry != null && entry.expiresAt.isAfter(clock.instant());
    }

    public record Key(String operation, String keyword, String category, int page) {
        public static Key forList(String category, int page) {
            return new Key("list", "", category == null ? "" : category, page);
        }
        public static Key forSearch(String keyword, String category, int page) {
            return new Key("search",
                    keyword == null ? "" : keyword.toLowerCase(),
                    category == null ? "" : category,
                    page);
        }
    }

    private record Entry(NoticeListResponse value, Instant expiresAt) {
    }
}
