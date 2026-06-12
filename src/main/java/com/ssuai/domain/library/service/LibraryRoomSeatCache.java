package com.ssuai.domain.library.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ssuai.domain.library.connector.LibrarySeatConnector;
import com.ssuai.domain.library.dto.PyxisSeatInfo;
import com.ssuai.domain.library.redis.LibraryRedisMetrics;
import com.ssuai.domain.library.redis.LibraryRoomSeatL2Cache;

/**
 * Per-room live seat cache with single-flight semantics.
 * Per-seat availability changes faster than floor counts, so the TTL is short
 * while still collapsing concurrent misses into one Pyxis call per room.
 */
@Component
public class LibraryRoomSeatCache {

    private static final Logger log = LoggerFactory.getLogger(LibraryRoomSeatCache.class);

    private final LibrarySeatConnector connector;
    private final LibraryRoomSeatL2Cache l2Cache;
    private final LibraryRedisMetrics redisMetrics;
    private final Duration ttl;
    private final Clock clock;
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Key, CompletableFuture<Entry>> inflight = new ConcurrentHashMap<>();

    @Autowired
    public LibraryRoomSeatCache(
            LibrarySeatConnector connector,
            LibraryRoomSeatL2Cache l2Cache,
            LibraryRedisMetrics redisMetrics,
            @Value("${ssuai.library.room-seat.cache-ttl:5s}") Duration ttl
    ) {
        this(connector, l2Cache, redisMetrics, ttl, Clock.systemUTC());
    }

    LibraryRoomSeatCache(LibrarySeatConnector connector, Duration ttl, Clock clock) {
        this(
                connector,
                LibraryRoomSeatL2Cache.noop(),
                new LibraryRedisMetrics(new SimpleMeterRegistry()),
                ttl,
                clock);
    }

    LibraryRoomSeatCache(
            LibrarySeatConnector connector,
            LibraryRoomSeatL2Cache l2Cache,
            LibraryRedisMetrics redisMetrics,
            Duration ttl,
            Clock clock) {
        this.connector = connector;
        this.l2Cache = l2Cache;
        this.redisMetrics = redisMetrics;
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

                Entry l2Entry = readL2(key);
                if (l2Entry != null) {
                    entries.put(key, l2Entry);
                    mine.complete(l2Entry);
                    return l2Entry.value;
                }

                Entry entry = new Entry(connector.fetchRoomSeats(roomId, token), clock.instant().plus(ttl));
                entries.put(key, entry);
                writeL2(key, entry.value);
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

    private Entry readL2(Key key) {
        try {
            return l2Cache.get(key.roomId(), key.authenticated())
                    .map(seats -> new Entry(seats, clock.instant().plus(ttl.dividedBy(2))))
                    .orElse(null);
        } catch (RuntimeException exception) {
            log.warn("library room seat Redis L2 read failed: roomId={} authenticated={}",
                    key.roomId(), key.authenticated(), exception);
            redisMetrics.countFailure("room_seat_l2_read", exception);
            return null;
        }
    }

    private void writeL2(Key key, List<PyxisSeatInfo> seats) {
        try {
            l2Cache.put(key.roomId(), key.authenticated(), seats, ttl);
        } catch (RuntimeException exception) {
            log.warn("library room seat Redis L2 write failed: roomId={} authenticated={}",
                    key.roomId(), key.authenticated(), exception);
            redisMetrics.countFailure("room_seat_l2_write", exception);
        }
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
