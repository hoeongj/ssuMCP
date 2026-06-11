package com.ssuai.domain.library.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.connector.LibrarySeatConnector;
import com.ssuai.domain.library.dto.PyxisSeatInfo;

/**
 * Per-room live seat cache with single-flight semantics.
 * Per-seat availability changes faster than floor counts, so the TTL is short
 * while still collapsing concurrent misses into one Pyxis call per room.
 */
@Component
public class LibraryRoomSeatCache {

    private final LibrarySeatConnector connector;
    private final Duration ttl;
    private final Clock clock;
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, CompletableFuture<Entry>> inflight = new ConcurrentHashMap<>();

    @Autowired
    public LibraryRoomSeatCache(
            LibrarySeatConnector connector,
            @Value("${ssuai.library.room-seat.cache-ttl:5s}") Duration ttl
    ) {
        this(connector, ttl, Clock.systemUTC());
    }

    LibraryRoomSeatCache(LibrarySeatConnector connector, Duration ttl, Clock clock) {
        this.connector = connector;
        this.ttl = ttl;
        this.clock = clock;
    }

    public List<PyxisSeatInfo> get(int roomId, String token) {
        Key key = Key.of(roomId, token);
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

                Entry entry = new Entry(connector.fetchRoomSeats(roomId, token), clock.instant().plus(ttl));
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
            throw new IllegalStateException("library room seat cache wait interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("library room seat cache fetch failed", cause);
        }
    }

    private boolean isFresh(Entry entry) {
        return entry != null && entry.expiresAt.isAfter(clock.instant());
    }

    private record Key(int roomId, boolean authenticated) {
        static Key of(int roomId, String token) {
            return new Key(roomId, token != null && !token.isBlank());
        }
    }

    private record Entry(List<PyxisSeatInfo> value, Instant expiresAt) {
        Entry {
            value = value == null ? List.of() : List.copyOf(value);
            if (expiresAt == null) {
                throw new IllegalArgumentException("cache expiresAt cannot be null");
            }
        }
    }
}
