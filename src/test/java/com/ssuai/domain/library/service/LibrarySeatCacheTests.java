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

import com.ssuai.domain.library.connector.LibrarySeatConnector;
import com.ssuai.domain.library.dto.LibraryFloor;
import com.ssuai.domain.library.dto.LibrarySeatStatusResponse;
import com.ssuai.domain.library.dto.PyxisSeatInfo;
import com.ssuai.global.exception.ConnectorTimeoutException;

class LibrarySeatCacheTests {

    @Test
    void hitWithinTtlSkipsConnector() {
        CountingConnector connector = new CountingConnector();
        MutableClock clock = new MutableClock(Instant.parse("2026-05-15T10:00:00Z"));
        LibrarySeatCache cache = new LibrarySeatCache(connector, Duration.ofSeconds(30), clock);

        LibrarySeatStatusResponse first = cache.get(LibraryFloor.F2, null);
        clock.advance(Duration.ofSeconds(20));
        LibrarySeatStatusResponse second = cache.get(LibraryFloor.F2, null);

        assertThat(second).isSameAs(first);
        assertThat(connector.callsFor(LibraryFloor.F2)).isEqualTo(1);
    }

    @Test
    void missAfterTtlTriggersRefetch() {
        CountingConnector connector = new CountingConnector();
        MutableClock clock = new MutableClock(Instant.parse("2026-05-15T10:00:00Z"));
        LibrarySeatCache cache = new LibrarySeatCache(connector, Duration.ofSeconds(30), clock);

        cache.get(LibraryFloor.F2, null);
        clock.advance(Duration.ofSeconds(31));
        cache.get(LibraryFloor.F2, null);

        assertThat(connector.callsFor(LibraryFloor.F2)).isEqualTo(2);
    }

    @Test
    void entriesAreScopedPerFloor() {
        CountingConnector connector = new CountingConnector();
        MutableClock clock = new MutableClock(Instant.parse("2026-05-15T10:00:00Z"));
        LibrarySeatCache cache = new LibrarySeatCache(connector, Duration.ofSeconds(30), clock);

        cache.get(LibraryFloor.F5, null);
        cache.get(LibraryFloor.F2, null);
        cache.get(LibraryFloor.F5, null);

        assertThat(connector.callsFor(LibraryFloor.F5)).isEqualTo(1);
        assertThat(connector.callsFor(LibraryFloor.F2)).isEqualTo(1);
    }

    @Test
    void authenticatedResultIsNotReusedForAnonymousRequest() {
        CountingConnector connector = new CountingConnector();
        MutableClock clock = new MutableClock(Instant.parse("2026-05-15T10:00:00Z"));
        LibrarySeatCache cache = new LibrarySeatCache(connector, Duration.ofSeconds(30), clock);

        cache.get(LibraryFloor.F2, "valid-token");
        cache.get(LibraryFloor.F2, null);

        assertThat(connector.callsFor(LibraryFloor.F2)).isEqualTo(2);
    }

    @Test
    void authenticatedRequestsShareGlobalSeatCountsWithinTtl() {
        CountingConnector connector = new CountingConnector();
        MutableClock clock = new MutableClock(Instant.parse("2026-05-15T10:00:00Z"));
        LibrarySeatCache cache = new LibrarySeatCache(connector, Duration.ofSeconds(30), clock);

        cache.get(LibraryFloor.F2, "first-valid-token");
        cache.get(LibraryFloor.F2, "second-valid-token");

        assertThat(connector.callsFor(LibraryFloor.F2)).isEqualTo(1);
    }

    @Test
    void concurrentMissesOnSameFloorShareOneConnectorCall() throws Exception {
        BlockingConnector connector = new BlockingConnector();
        LibrarySeatCache cache = new LibrarySeatCache(connector, Duration.ofSeconds(30),
                Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<CompletableFuture<LibrarySeatStatusResponse>> futures = List.of(
                    CompletableFuture.supplyAsync(() -> cache.get(LibraryFloor.F2, null), pool),
                    CompletableFuture.supplyAsync(() -> cache.get(LibraryFloor.F2, null), pool),
                    CompletableFuture.supplyAsync(() -> cache.get(LibraryFloor.F2, null), pool),
                    CompletableFuture.supplyAsync(() -> cache.get(LibraryFloor.F2, null), pool)
            );
            // Give the threads time to all reach the in-flight gate before releasing.
            assertThat(connector.awaitWaiter(5, TimeUnit.SECONDS)).isTrue();
            connector.release();

            LibrarySeatStatusResponse common = futures.get(0).get(5, TimeUnit.SECONDS);
            for (CompletableFuture<LibrarySeatStatusResponse> future : futures) {
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
        LibrarySeatCache cache = new LibrarySeatCache(connector, Duration.ofSeconds(30),
                Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> cache.get(LibraryFloor.F2, null))
                .isInstanceOf(ConnectorTimeoutException.class);
        connector.recover(stubResponse(LibraryFloor.F2));
        LibrarySeatStatusResponse recovered = cache.get(LibraryFloor.F2, null);

        assertThat(recovered).isNotNull();
        assertThat(connector.callCount()).isEqualTo(2);
    }

    private static LibrarySeatStatusResponse stubResponse(LibraryFloor floor) {
        return new LibrarySeatStatusResponse(
                floor.code(),
                floor.displayLabel(),
                10,
                4,
                5,
                1,
                Instant.parse("2026-05-15T10:00:00Z"),
                List.of()
        );
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

    private static final class CountingConnector implements LibrarySeatConnector {
        private final AtomicInteger f2Calls = new AtomicInteger();
        private final AtomicInteger f5Calls = new AtomicInteger();
        private final AtomicInteger f6Calls = new AtomicInteger();

        @Override
        public LibrarySeatStatusResponse fetchSeatStatus(LibraryFloor floor, String token) {
            counterFor(floor).incrementAndGet();
            return stubResponse(floor);
        }

        @Override
        public List<PyxisSeatInfo> fetchRoomSeats(int roomId, String token) {
            return List.of();
        }

        int callsFor(LibraryFloor floor) {
            return counterFor(floor).get();
        }

        private AtomicInteger counterFor(LibraryFloor floor) {
            return switch (floor) {
                case F2 -> f2Calls;
                case F5 -> f5Calls;
                case F6 -> f6Calls;
            };
        }
    }

    private static final class BlockingConnector implements LibrarySeatConnector {
        private final CountDownLatch waiters = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public LibrarySeatStatusResponse fetchSeatStatus(LibraryFloor floor, String token) {
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
            return stubResponse(floor);
        }

        @Override
        public List<PyxisSeatInfo> fetchRoomSeats(int roomId, String token) {
            return List.of();
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

    private static final class FailingConnector implements LibrarySeatConnector {
        private final AtomicInteger callCount = new AtomicInteger();
        private final AtomicReference<LibrarySeatStatusResponse> recovery = new AtomicReference<>();

        @Override
        public LibrarySeatStatusResponse fetchSeatStatus(LibraryFloor floor, String token) {
            callCount.incrementAndGet();
            LibrarySeatStatusResponse next = recovery.get();
            if (next != null) {
                return next;
            }
            throw new ConnectorTimeoutException();
        }

        @Override
        public List<PyxisSeatInfo> fetchRoomSeats(int roomId, String token) {
            return List.of();
        }

        void recover(LibrarySeatStatusResponse next) {
            recovery.set(next);
        }

        int callCount() {
            return callCount.get();
        }
    }
}
