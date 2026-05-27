package com.ssuai.domain.library.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.connector.LibraryBookConnector;
import com.ssuai.domain.library.dto.LibraryBookSearchResponse;

/**
 * Per-(query, page, size) cache with short TTL and single-flight semantics.
 * Mirrors `LibrarySeatCache` but keyed by a search tuple rather than floor.
 * LRU-bounded so a chat user spamming searches cannot grow memory unbounded.
 */
@Component
public class LibraryBookCache {

    static final int DEFAULT_CAPACITY = 200;

    private final LibraryBookConnector connector;
    private final Duration ttl;
    private final Clock clock;
    private final int capacity;
    private final Map<Key, Entry> entries;
    private final ConcurrentHashMap<Key, CompletableFuture<Entry>> inflight = new ConcurrentHashMap<>();

    @Autowired
    public LibraryBookCache(
            LibraryBookConnector connector,
            @Value("${ssuai.library.book.cache-ttl:60s}") Duration ttl
    ) {
        this(connector, ttl, Clock.systemUTC(), DEFAULT_CAPACITY);
    }

    LibraryBookCache(LibraryBookConnector connector, Duration ttl, Clock clock, int capacity) {
        this.connector = connector;
        this.ttl = ttl;
        this.clock = clock;
        this.capacity = capacity;
        this.entries = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Entry> eldest) {
                return size() > LibraryBookCache.this.capacity;
            }
        });
    }

    public LibraryBookSearchResponse get(String query, int page, int size) {
        Key key = Key.of(query, page, size);
        Entry cached = entries.get(key);
        if (isFresh(cached)) {
            return cached.value;
        }

        CompletableFuture<Entry> mine = new CompletableFuture<>();
        CompletableFuture<Entry> winner = inflight.putIfAbsent(key, mine);
        if (winner == null) {
            try {
                Entry refreshed = entries.get(key);
                if (isFresh(refreshed)) {
                    mine.complete(refreshed);
                    return refreshed.value;
                }

                LibraryBookSearchResponse fresh = connector.search(key.query(), key.page(), key.size());
                Entry entry = new Entry(fresh, clock.instant().plus(ttl));
                entries.put(key, entry);
                mine.complete(entry);
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
            throw new IllegalStateException("library book cache wait interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("library book cache fetch failed", cause);
        }
    }

    private boolean isFresh(Entry entry) {
        return entry != null && entry.expiresAt.isAfter(clock.instant());
    }

    int size() {
        return entries.size();
    }

    record Key(String query, int page, int size) {
        static Key of(String query, int page, int size) {
            String normalized = query == null ? "" : query.trim().toLowerCase();
            return new Key(normalized, page, size);
        }
    }

    private record Entry(LibraryBookSearchResponse value, Instant expiresAt) {
        Entry {
            if (value == null) {
                throw new IllegalArgumentException("cache value cannot be null");
            }
            if (expiresAt == null) {
                throw new IllegalArgumentException("cache expiresAt cannot be null");
            }
        }
    }
}
