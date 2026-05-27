package com.ssuai.domain.saint.service;

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

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.auth.saint.SaintSessionStore;
import com.ssuai.domain.saint.connector.SaintScheduleConnector;
import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.global.exception.SaintSessionExpiredException;

/**
 * Per-student cumulative timetable cache with short TTL and single-flight
 * semantics (Task 16 spec §6 #5). The cumulative fetch walks the WDA7
 * "previous term" path up to 16 hops; a chat user asking "내일 1교시" three
 * times in a row should pay that cost once, not three times. Mirrors the
 * {@link com.ssuai.domain.library.service.LibraryBookCache} shape — LRU
 * bound, clock-based expiry, single-flight on the miss path — but keyed by
 * student id and folding in the {@link SaintSessionStore} cookie lookup so
 * a cached schedule survives a portal-cookie expiry (the data hasn't
 * changed, we just can't fetch a new copy).
 *
 * <p>Exceptions from the loader (missing cookies, connector-side
 * {@link SaintSessionExpiredException}, transport faults) propagate without
 * poisoning the cache so the next caller — including the same student
 * after re-running SmartID SSO — retries cleanly.
 */
@Component
public class SaintScheduleCache {

    static final int DEFAULT_CAPACITY = 100;

    private final SaintScheduleConnector connector;
    private final SaintSessionStore sessionStore;
    private final Duration ttl;
    private final Clock clock;
    private final int capacity;
    private final Map<String, Entry> entries;
    private final ConcurrentHashMap<String, CompletableFuture<Entry>> inflight = new ConcurrentHashMap<>();

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
        this.ttl = ttl;
        this.clock = clock;
        this.capacity = capacity;
        this.entries = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > SaintScheduleCache.this.capacity;
            }
        });
    }

    public ScheduleResponse get(String studentId) {
        Entry cached = entries.get(studentId);
        if (isFresh(cached)) {
            return cached.value;
        }

        CompletableFuture<Entry> mine = new CompletableFuture<>();
        CompletableFuture<Entry> winner = inflight.putIfAbsent(studentId, mine);
        if (winner == null) {
            try {
                Entry refreshed = entries.get(studentId);
                if (isFresh(refreshed)) {
                    mine.complete(refreshed);
                    return refreshed.value;
                }

                PortalCookies cookies = sessionStore.cookies(studentId)
                        .orElseThrow(SaintSessionExpiredException::new);
                ScheduleResponse fresh = connector.fetchSchedule(studentId, cookies);
                Entry entry = new Entry(fresh, clock.instant().plus(ttl));
                entries.put(studentId, entry);
                mine.complete(entry);
                return entry.value;
            } catch (RuntimeException exception) {
                mine.completeExceptionally(exception);
                throw exception;
            } finally {
                inflight.remove(studentId, mine);
            }
        }

        try {
            return winner.get().value;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("saint schedule cache wait interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("saint schedule cache fetch failed", cause);
        }
    }

    private boolean isFresh(Entry entry) {
        return entry != null && entry.expiresAt.isAfter(clock.instant());
    }

    int size() {
        return entries.size();
    }

    private record Entry(ScheduleResponse value, Instant expiresAt) {
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
