package com.ssuai.global.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the shared {@link SingleFlightCache} skeleton. The five domain
 * caches delegate to it, so verifying freshness, LRU eviction, single-flight
 * coalescing, failure isolation, and custom-expiry loaders here covers the
 * concurrency-sensitive core once for all of them.
 */
class SingleFlightCacheTests {

    private static final Instant T0 = Instant.parse("2026-05-15T10:00:00Z");

    @Test
    void hitWithinTtlSkipsLoader() {
        AtomicInteger calls = new AtomicInteger();
        var cache = SingleFlightCache.<String, String>unbounded(
                "test cache", Duration.ofSeconds(30), Clock.fixed(T0, ZoneOffset.UTC));

        assertThat(cache.get("k", counting(calls, "v"))).isEqualTo("v");
        assertThat(cache.get("k", counting(calls, "v"))).isEqualTo("v");

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void missAfterTtlReloads() {
        AtomicInteger calls = new AtomicInteger();
        MutableClock clock = new MutableClock(T0);
        var cache = SingleFlightCache.<String, String>unbounded("test cache", Duration.ofSeconds(30), clock);

        cache.get("k", counting(calls, "v"));
        clock.advance(Duration.ofSeconds(31));
        cache.get("k", counting(calls, "v"));

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void lruBoundedEvictsLeastRecentlyUsed() {
        AtomicInteger calls = new AtomicInteger();
        var cache = SingleFlightCache.<String, String>lruBounded(
                "test cache", Duration.ofMinutes(5), Clock.fixed(T0, ZoneOffset.UTC), 2);

        cache.get("a", counting(calls, "a"));
        cache.get("b", counting(calls, "b"));
        cache.get("a", counting(calls, "a")); // touch a → b is now least-recently-used
        cache.get("c", counting(calls, "c")); // evicts b
        assertThat(cache.size()).isEqualTo(2);

        cache.get("b", counting(calls, "b")); // b was evicted → reloads
        // a(1) b(1) a-hit c(1) b-reload(1) = 4 loads
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void concurrentMissesOnSameKeyShareOneLoad() throws Exception {
        var cache = SingleFlightCache.<String, String>unbounded(
                "test cache", Duration.ofSeconds(30), Clock.fixed(T0, ZoneOffset.UTC));
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Function<String, String> blocking = key -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "value";
        };

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<CompletableFuture<String>> futures = List.of(
                    CompletableFuture.supplyAsync(() -> cache.get("k", blocking), pool),
                    CompletableFuture.supplyAsync(() -> cache.get("k", blocking), pool),
                    CompletableFuture.supplyAsync(() -> cache.get("k", blocking), pool),
                    CompletableFuture.supplyAsync(() -> cache.get("k", blocking), pool));

            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            for (CompletableFuture<String> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("value");
            }
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void loaderFailurePropagatesAndDoesNotPoisonCache() {
        var cache = SingleFlightCache.<String, String>unbounded(
                "test cache", Duration.ofSeconds(30), Clock.fixed(T0, ZoneOffset.UTC));
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> cache.get("k", key -> {
            calls.incrementAndGet();
            throw new IllegalStateException("upstream boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("upstream boom");

        // A failed load is not cached: the next caller retries and can succeed.
        assertThat(cache.get("k", counting(calls, "recovered"))).isEqualTo("recovered");
        assertThat(cache.size()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void getWithEntryHonoursCustomExpiry() {
        MutableClock clock = new MutableClock(T0);
        var cache = SingleFlightCache.<String, String>unbounded("test cache", Duration.ofSeconds(30), clock);
        AtomicInteger calls = new AtomicInteger();

        // Stored at half the TTL (15s) via a custom-expiry entry loader.
        cache.getWithEntry("k", key -> {
            calls.incrementAndGet();
            return cache.newEntry("v", cache.ttl().dividedBy(2));
        });
        clock.advance(Duration.ofSeconds(16)); // past 15s, before 30s
        cache.getWithEntry("k", key -> {
            calls.incrementAndGet();
            return cache.newEntry("v", cache.ttl().dividedBy(2));
        });

        assertThat(calls.get()).isEqualTo(2); // expired at 15s, so it reloaded
    }

    @Test
    void invalidateClearsEntries() {
        AtomicInteger calls = new AtomicInteger();
        var cache = SingleFlightCache.<String, String>unbounded(
                "test cache", Duration.ofMinutes(5), Clock.fixed(T0, ZoneOffset.UTC));

        cache.get("k", counting(calls, "v"));
        cache.invalidate();
        assertThat(cache.size()).isZero();
        cache.get("k", counting(calls, "v")); // reloads after invalidation

        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void nullValueIsRejected() {
        assertThatThrownBy(() -> new SingleFlightCache.Entry<>(null, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SingleFlightCache.Entry<>("v", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Function<String, String> counting(AtomicInteger counter, String value) {
        return key -> {
            counter.incrementAndGet();
            return value;
        };
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration delta) {
            this.now = this.now.plus(delta);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
