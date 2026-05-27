package com.ssuai.domain.library.service;

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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.library.connector.LibraryBookConnector;
import com.ssuai.domain.library.dto.BookStatus;
import com.ssuai.domain.library.dto.LibraryBook;
import com.ssuai.domain.library.dto.LibraryBookSearchResponse;
import com.ssuai.global.exception.ConnectorTimeoutException;

class LibraryBookCacheTests {

    @Test
    void hitWithinTtlSkipsConnector() {
        CountingConnector connector = new CountingConnector();
        MutableClock clock = new MutableClock(Instant.parse("2026-05-15T10:00:00Z"));
        LibraryBookCache cache = new LibraryBookCache(connector, Duration.ofSeconds(60), clock, 200);

        cache.get("파이썬", 0, 10);
        clock.advance(Duration.ofSeconds(40));
        cache.get("파이썬", 0, 10);

        assertThat(connector.invocationCount()).isEqualTo(1);
    }

    @Test
    void missAfterTtlTriggersRefetch() {
        CountingConnector connector = new CountingConnector();
        MutableClock clock = new MutableClock(Instant.parse("2026-05-15T10:00:00Z"));
        LibraryBookCache cache = new LibraryBookCache(connector, Duration.ofSeconds(60), clock, 200);

        cache.get("파이썬", 0, 10);
        clock.advance(Duration.ofSeconds(61));
        cache.get("파이썬", 0, 10);

        assertThat(connector.invocationCount()).isEqualTo(2);
    }

    @Test
    void differentQueriesAreCachedIndependently() {
        CountingConnector connector = new CountingConnector();
        MutableClock clock = new MutableClock(Instant.parse("2026-05-15T10:00:00Z"));
        LibraryBookCache cache = new LibraryBookCache(connector, Duration.ofSeconds(60), clock, 200);

        cache.get("파이썬", 0, 10);
        cache.get("자바", 0, 10);
        cache.get("파이썬", 0, 10);

        assertThat(connector.invocationCount()).isEqualTo(2);
    }

    @Test
    void differentPagesAreCachedIndependently() {
        CountingConnector connector = new CountingConnector();
        MutableClock clock = new MutableClock(Instant.parse("2026-05-15T10:00:00Z"));
        LibraryBookCache cache = new LibraryBookCache(connector, Duration.ofSeconds(60), clock, 200);

        cache.get("파이썬", 0, 10);
        cache.get("파이썬", 1, 10);

        assertThat(connector.invocationCount()).isEqualTo(2);
    }

    @Test
    void queryKeyIsNormalizedForCaseAndWhitespace() {
        CountingConnector connector = new CountingConnector();
        MutableClock clock = new MutableClock(Instant.parse("2026-05-15T10:00:00Z"));
        LibraryBookCache cache = new LibraryBookCache(connector, Duration.ofSeconds(60), clock, 200);

        cache.get("Python", 0, 10);
        cache.get("  python  ", 0, 10);
        cache.get("PYTHON", 0, 10);

        assertThat(connector.invocationCount()).isEqualTo(1);
    }

    @Test
    void capacityEvictsLeastRecentlyUsed() {
        CountingConnector connector = new CountingConnector();
        Clock fixed = Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC);
        LibraryBookCache cache = new LibraryBookCache(connector, Duration.ofSeconds(60), fixed, 2);

        cache.get("a", 0, 10);
        cache.get("b", 0, 10);
        cache.get("c", 0, 10); // should evict "a"

        assertThat(cache.size()).isEqualTo(2);
        // re-fetching "a" forces another connector call
        cache.get("a", 0, 10);
        assertThat(connector.invocationCount()).isEqualTo(4);
    }

    @Test
    void concurrentMissesOnSameKeyShareOneConnectorCall() throws Exception {
        BlockingConnector connector = new BlockingConnector();
        LibraryBookCache cache = new LibraryBookCache(
                connector, Duration.ofSeconds(60),
                Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC),
                200);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<CompletableFuture<LibraryBookSearchResponse>> futures = List.of(
                    CompletableFuture.supplyAsync(() -> cache.get("파이썬", 0, 10), pool),
                    CompletableFuture.supplyAsync(() -> cache.get("파이썬", 0, 10), pool),
                    CompletableFuture.supplyAsync(() -> cache.get("파이썬", 0, 10), pool),
                    CompletableFuture.supplyAsync(() -> cache.get("파이썬", 0, 10), pool)
            );
            assertThat(connector.awaitWaiter(5, TimeUnit.SECONDS)).isTrue();
            connector.release();

            LibraryBookSearchResponse common = futures.get(0).get(5, TimeUnit.SECONDS);
            for (CompletableFuture<LibraryBookSearchResponse> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isSameAs(common);
            }
            assertThat(connector.invocationCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void connectorFailurePropagatesAndDoesNotPoisonCache() {
        FailingConnector connector = new FailingConnector();
        LibraryBookCache cache = new LibraryBookCache(
                connector, Duration.ofSeconds(60),
                Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC),
                200);

        assertThatThrownBy(() -> cache.get("파이썬", 0, 10))
                .isInstanceOf(ConnectorTimeoutException.class);
        connector.recover(stubResponse());
        LibraryBookSearchResponse recovered = cache.get("파이썬", 0, 10);

        assertThat(recovered).isNotNull();
        assertThat(connector.callCount()).isEqualTo(2);
    }

    private static LibraryBookSearchResponse stubResponse() {
        return new LibraryBookSearchResponse(1, 0, 10, List.of(
                new LibraryBook(1L, "stub", "stub author", "pub", null, null,
                        "000.0", "중앙도서관", BookStatus.AVAILABLE)
        ));
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

    private static final class CountingConnector implements LibraryBookConnector {
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public LibraryBookSearchResponse search(String query, int page, int size) {
            invocations.incrementAndGet();
            return stubResponse();
        }

        int invocationCount() {
            return invocations.get();
        }
    }

    private static final class BlockingConnector implements LibraryBookConnector {
        private final CountDownLatch waiters = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public LibraryBookSearchResponse search(String query, int page, int size) {
            int call = invocations.incrementAndGet();
            waiters.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("blocking connector never released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            if (call != 1) {
                throw new IllegalStateException("single-flight violation: connector entered " + call + " times");
            }
            return stubResponse();
        }

        boolean awaitWaiter(long timeout, TimeUnit unit) throws InterruptedException {
            return waiters.await(timeout, unit);
        }

        void release() {
            release.countDown();
        }

        int invocationCount() {
            return invocations.get();
        }
    }

    private static final class FailingConnector implements LibraryBookConnector {
        private final AtomicInteger callCount = new AtomicInteger();
        private final AtomicReference<LibraryBookSearchResponse> recovery = new AtomicReference<>();

        @Override
        public LibraryBookSearchResponse search(String query, int page, int size) {
            callCount.incrementAndGet();
            LibraryBookSearchResponse next = recovery.get();
            if (next != null) {
                return next;
            }
            throw new ConnectorTimeoutException();
        }

        void recover(LibraryBookSearchResponse next) {
            recovery.set(next);
        }

        int callCount() {
            return callCount.get();
        }
    }
}
