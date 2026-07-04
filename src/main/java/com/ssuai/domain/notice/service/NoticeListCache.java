package com.ssuai.domain.notice.service;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ssuai.domain.notice.connector.NoticeConnectorProperties;
import com.ssuai.domain.notice.dto.NoticeListResponse;
import com.ssuai.global.cache.SingleFlightCache;

/**
 * TTL cache for notice list and search results. LRU-bounded to 500 entries;
 * single-flight deduplication (in {@link SingleFlightCache}) prevents a
 * cache-stampede on concurrent identical requests.
 *
 * <p>TTL is read from {@code ssuai.notice.cache-ttl} (default 5m).
 */
@Component
public class NoticeListCache {

    private static final Logger log = LoggerFactory.getLogger(NoticeListCache.class);
    static final int DEFAULT_CAPACITY = 500;

    private final SingleFlightCache<Key, NoticeListResponse> cache;

    @Autowired
    public NoticeListCache(NoticeConnectorProperties properties) {
        this(properties.getCacheTtl(), Clock.systemUTC(), DEFAULT_CAPACITY);
    }

    NoticeListCache(Duration ttl, Clock clock, int capacity) {
        Duration effectiveTtl = ttl != null ? ttl : Duration.ofMinutes(5);
        this.cache = SingleFlightCache.lruBounded("notice cache", effectiveTtl, clock, capacity);
    }

    public NoticeListResponse get(Key key, Supplier<NoticeListResponse> loader) {
        return cache.get(key, k -> {
            NoticeListResponse fresh = loader.get();
            log.debug("notice cache miss+fill: key={} items={}", k, fresh.items().size());
            return fresh;
        });
    }

    public void invalidate() {
        cache.invalidate();
    }

    int size() {
        return cache.size();
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
}
