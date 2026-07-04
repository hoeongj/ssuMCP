package com.ssuai.domain.library.service;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.connector.LibrarySeatConnector;
import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.global.cache.SingleFlightCache;

/**
 * Per-floor and authentication-boundary in-memory cache with short TTL and
 * single-flight semantics. Concurrent misses for the same floor and access
 * category share one upstream call so the library page is not hammered at
 * chat-message frequency. The TTL + single-flight machinery lives in
 * {@link SingleFlightCache}; this class supplies the key and the loader.
 */
@Component
public class LibrarySeatCache {

    private final LibrarySeatConnector connector;
    private final SingleFlightCache<Key, LibrarySeatStatusResponse> cache;

    @Autowired
    public LibrarySeatCache(
            LibrarySeatConnector connector,
            @Value("${ssuai.library.seat.cache-ttl:30s}") Duration ttl
    ) {
        this(connector, ttl, Clock.systemUTC());
    }

    LibrarySeatCache(LibrarySeatConnector connector, Duration ttl, Clock clock) {
        this.connector = connector;
        this.cache = SingleFlightCache.unbounded("library seat cache", ttl, clock);
    }

    public LibrarySeatStatusResponse get(LibraryFloor floor, String token) {
        return cache.get(Key.of(floor, token), key -> connector.fetchSeatStatus(floor, token));
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
}
