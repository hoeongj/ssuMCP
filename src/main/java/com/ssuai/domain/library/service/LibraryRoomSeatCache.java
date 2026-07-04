package com.ssuai.domain.library.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.connector.LibrarySeatConnector;
import com.ssuai.domain.library.dto.PyxisSeatInfo;
import com.ssuai.domain.library.redis.LibraryRedisMetrics;
import com.ssuai.domain.library.redis.LibraryRoomSeatL2Cache;
import com.ssuai.global.cache.SingleFlightCache;

/**
 * Per-room live seat cache with single-flight semantics and a Redis L2 tier.
 * Per-seat availability changes faster than floor counts, so the TTL is short
 * while still collapsing concurrent misses into one Pyxis call per room.
 *
 * <p>The L1 TTL + single-flight machinery lives in {@link SingleFlightCache};
 * this class's loader adds the L2 read-through/write-behind. An L2 hit is stored
 * in L1 at <i>half</i> the TTL so an entry that already aged in Redis does not
 * get a second full lifetime in-process (staleness doubling).
 */
@Component
public class LibraryRoomSeatCache {

    private static final Logger log = LoggerFactory.getLogger(LibraryRoomSeatCache.class);

    private final LibrarySeatConnector connector;
    private final LibraryRoomSeatL2Cache l2Cache;
    private final LibraryRedisMetrics redisMetrics;
    private final SingleFlightCache<Key, List<PyxisSeatInfo>> cache;

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
        this.cache = SingleFlightCache.unbounded("library room seat cache", ttl, clock);
    }

    public List<PyxisSeatInfo> get(int roomId, String token) {
        Key key = Key.of(roomId, token);
        return cache.getWithEntry(key, k -> {
            SingleFlightCache.Entry<List<PyxisSeatInfo>> l2 = readL2(k);
            if (l2 != null) {
                return l2;
            }
            List<PyxisSeatInfo> seats = copyOf(connector.fetchRoomSeats(roomId, token));
            writeL2(k, seats);
            return cache.newEntry(seats);
        });
    }

    /** L2 hit → an L1 entry at half TTL; miss or failure → null (fall through). */
    private SingleFlightCache.Entry<List<PyxisSeatInfo>> readL2(Key key) {
        try {
            return l2Cache.get(key.roomId(), key.authenticated())
                    .map(seats -> cache.newEntry(copyOf(seats), cache.ttl().dividedBy(2)))
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
            l2Cache.put(key.roomId(), key.authenticated(), seats, cache.ttl());
        } catch (RuntimeException exception) {
            log.warn("library room seat Redis L2 write failed: roomId={} authenticated={}",
                    key.roomId(), key.authenticated(), exception);
            redisMetrics.countFailure("room_seat_l2_write", exception);
        }
    }

    private static List<PyxisSeatInfo> copyOf(List<PyxisSeatInfo> seats) {
        return seats == null ? List.of() : List.copyOf(seats);
    }

    private record Key(int roomId, boolean authenticated) {
        static Key of(int roomId, String token) {
            return new Key(roomId, token != null && !token.isBlank());
        }
    }
}
