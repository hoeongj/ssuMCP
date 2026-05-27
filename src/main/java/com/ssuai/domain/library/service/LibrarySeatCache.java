package com.ssuai.domain.library.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.connector.LibrarySeatConnector;
import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;

/**
 * Per-floor and authentication-boundary in-memory cache with short TTL and
 * single-flight semantics. Concurrent misses for the same floor and access
 * category share one upstream call so the library page is not hammered at
 * chat-message frequency.
 */
@Component
public class LibrarySeatCache {

    private final LibrarySeatConnector connector;
    private final Duration ttl;
    private final Clock clock;
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, CompletableFuture<Entry>> inflight = new ConcurrentHashMap<>();

    @Autowired
    public LibrarySeatCache(
            LibrarySeatConnector connector,
            @Value("${ssuai.library.seat.cache-ttl:30s}") Duration ttl
    ) {
        this(connector, ttl, Clock.systemUTC());
    }

    LibrarySeatCache(LibrarySeatConnector connector, Duration ttl, Clock clock) {
        this.connector = connector;
        this.ttl = ttl;
        this.clock = clock;
    }

    public LibrarySeatStatusResponse get(LibraryFloor floor, String token) {
        Key key = Key.of(floor, token);
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

                LibrarySeatStatusResponse fresh = connector.fetchSeatStatus(floor, token);
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
            throw new IllegalStateException("library seat cache wait interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("library seat cache fetch failed", cause);
        }
    }

    private boolean isFresh(Entry entry) {
        return entry != null && entry.expiresAt.isAfter(clock.instant());
    }

    /**
     * Seat counts are global, so authenticated users may share a cache entry.
     * Anonymous callers must not reuse data obtained through an authenticated request.
     */
    private record Key(LibraryFloor floor, boolean authenticated) {
        static Key of(LibraryFloor floor, String token) {
            return new Key(floor, token != null && !token.isBlank());
        }
    }

    private record Entry(LibrarySeatStatusResponse value, Instant expiresAt) {
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
