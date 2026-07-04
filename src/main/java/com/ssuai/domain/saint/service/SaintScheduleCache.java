package com.ssuai.domain.saint.service;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.auth.saint.SaintSessionStore;
import com.ssuai.domain.saint.connector.SaintScheduleConnector;
import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.global.cache.SingleFlightCache;
import com.ssuai.global.exception.SaintSessionExpiredException;

/**
 * Per-student cumulative timetable cache with short TTL and single-flight
 * semantics (Task 16 spec §6 #5). The cumulative fetch walks the WDA7
 * "previous term" path up to 16 hops; a chat user asking "내일 1교시" three
 * times in a row should pay that cost once, not three times. Keyed by student
 * id (+ optional year/term) and folds in the {@link SaintSessionStore} cookie
 * lookup so a cached schedule survives a portal-cookie expiry (the data hasn't
 * changed, we just can't fetch a new copy).
 *
 * <p>The TTL + LRU + single-flight machinery lives in {@link SingleFlightCache}.
 * Loader exceptions (missing cookies, connector-side
 * {@link SaintSessionExpiredException}, transport faults) propagate without
 * poisoning the cache so the next caller — including the same student after
 * re-running SmartID SSO — retries cleanly.
 */
@Component
public class SaintScheduleCache {

    static final int DEFAULT_CAPACITY = 100;

    private final SaintScheduleConnector connector;
    private final SaintSessionStore sessionStore;
    private final SingleFlightCache<CacheKey, ScheduleResponse> cache;

    @Autowired
    public SaintScheduleCache(
            SaintScheduleConnector connector,
            SaintSessionStore sessionStore,
            @Value("${ssuai.saint.schedule.cache-ttl:1h}") Duration ttl
    ) {
        this(connector, sessionStore, ttl, Clock.systemUTC(), DEFAULT_CAPACITY);
    }

    SaintScheduleCache(
            SaintScheduleConnector connector,
            SaintSessionStore sessionStore,
            Duration ttl,
            Clock clock,
            int capacity
    ) {
        this.connector = connector;
        this.sessionStore = sessionStore;
        this.cache = SingleFlightCache.lruBounded("saint schedule cache", ttl, clock, capacity);
    }

    public ScheduleResponse get(String studentId) {
        return get(studentId, null, null);
    }

    public ScheduleResponse get(String studentId, Integer year, Integer term) {
        CacheKey key = CacheKey.of(studentId, year, term);
        return cache.get(key, k -> {
            PortalCookies cookies = sessionStore.cookies(k.studentId())
                    .orElseThrow(SaintSessionExpiredException::new);
            return connector.fetchSchedule(k.studentId(), cookies, k.year(), k.term());
        });
    }

    int size() {
        return cache.size();
    }

    private record CacheKey(String studentId, Integer year, Integer term) {
        private static CacheKey of(String studentId, Integer year, Integer term) {
            if ((year == null) != (term == null)) {
                throw new IllegalArgumentException("schedule year and term must be provided together");
            }
            return new CacheKey(studentId, year, term);
        }
    }
}
