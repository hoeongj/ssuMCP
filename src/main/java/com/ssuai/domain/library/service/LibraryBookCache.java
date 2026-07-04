package com.ssuai.domain.library.service;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ssuai.domain.library.connector.LibraryBookConnector;
import com.ssuai.domain.library.dto.LibraryBookSearchResponse;
import com.ssuai.global.cache.SingleFlightCache;

/**
 * Per-(query, page, size) cache with short TTL and single-flight semantics.
 * Keyed by a search tuple rather than floor, and LRU-bounded so a chat user
 * spamming searches cannot grow memory unbounded. The TTL + single-flight +
 * LRU machinery lives in {@link SingleFlightCache}.
 */
@Component
public class LibraryBookCache {

    static final int DEFAULT_CAPACITY = 200;

    private final LibraryBookConnector connector;
    private final SingleFlightCache<Key, LibraryBookSearchResponse> cache;

    @Autowired
    public LibraryBookCache(
            LibraryBookConnector connector,
            @Value("${ssuai.library.book.cache-ttl:60s}") Duration ttl
    ) {
        this(connector, ttl, Clock.systemUTC(), DEFAULT_CAPACITY);
    }

    LibraryBookCache(LibraryBookConnector connector, Duration ttl, Clock clock, int capacity) {
        this.connector = connector;
        this.cache = SingleFlightCache.lruBounded("library book cache", ttl, clock, capacity);
    }

    public LibraryBookSearchResponse get(String query, int page, int size) {
        Key key = Key.of(query, page, size);
        return cache.get(key, k -> connector.search(k.query(), k.page(), k.size()));
    }

    int size() {
        return cache.size();
    }

    record Key(String query, int page, int size) {
        static Key of(String query, int page, int size) {
            String normalized = query == null ? "" : query.trim().toLowerCase();
            return new Key(normalized, page, size);
        }
    }
}
