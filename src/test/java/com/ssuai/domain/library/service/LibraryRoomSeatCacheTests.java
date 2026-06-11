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

class LibraryRoomSeatCacheTests {

    @Test
    void hitWithinTtlSkipsConnector() {
        CountingConnector connector = new CountingConnector();
        MutableClock clock = new MutableClock(Instant.parse("2026-06-12T00:00:00Z"));
        LibraryRoomSeatCache cache = new LibraryRoomSeatCache(connector, Duration.ofSeconds(5), clock);

        List<PyxisSeatInfo> first = cache.get(57, "token-a");
        clock.advance(Duration.ofSeconds(4));
        List<PyxisSeatInfo> second = cache.get(57, "token-b");

        assertThat(second).isSameAs(first);
        assertThat(connector.callsFor(57)).isEqualTo(1);
    }

    @Test
    void missAfterTtlTriggersRefetch() {
        CountingConnector connector = new CountingConnector();
        MutableClock clock = new MutableClock(Instant.parse("2026-06-12T00:00:00Z"));
        LibraryRoomSeatCache cache = new LibraryRoomSeatCache(connector, Duration.ofSeconds(5), clock);

        cache.get(57, "token-a");
        clock.advance(Duration.ofSeconds(6));
        cache.get(57, "token-a");

        assertThat(connector.callsFor(57)).isEqualTo(2);
    }

    @Test
    void entriesAreScopedPerRoomAndAuthenticationBoundary() {
        CountingConnector connector = new CountingConnector();
        MutableClock clock = new MutableClock(Instant.parse("2026-06-12T00:00:00Z"));
        LibraryRoomSeatCache cache = new LibraryRoomSeatCache(connector, Duration.ofSeconds(5), clock);

        cache.get(57, "token-a");
        cache.get(57, "token-b");
        cache.get(57, null);
        cache.get(58, "token-a");

        assertThat(connector.callsFor(57)).isEqualTo(2);
        assertThat(connector.callsFor(58)).isEqualTo(1);
    }

    @Test
    void concurrentMissesOnSameRoomShareOneConnectorCall() throws Exception {
        BlockingConnector connector = new BlockingConnector();
        LibraryRoomSeatCache cache = new LibraryRoomSeatCache(
                connector,
                Duration.ofSeconds(5),
                Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneOffset.UTC));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<CompletableFuture<List<PyxisSeatInfo>>> futures = List.of(
                    CompletableFuture.supplyAsync(() -> cache.get(57, "token-a"), pool),
                    CompletableFuture.supplyAsync(() -> cache.get(57, "token-b"), pool),
                    CompletableFuture.supplyAsync(() -> cache.get(57, "token-c"), pool),
                    CompletableFuture.supplyAsync(() -> cache.get(57, "token-d"), pool)
            );
            assertThat(connector.awaitWaiter(5, TimeUnit.SECONDS)).isTrue();
            connector.release();

            List<PyxisSeatInfo> common = futures.get(0).get(5, TimeUnit.SECONDS);
            for (CompletableFuture<List<PyxisSeatInfo>> future : futures) {
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
        LibraryRoomSeatCache cache = new LibraryRoomSeatCache(
                connector,
                Duration.ofSeconds(5),
                Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> cache.get(57, "token-a"))
                .isInstanceOf(ConnectorTimeoutException.class);
        connector.recover(stubSeats(57));

        assertThat(cache.get(57, "token-a")).isNotEmpty();
        assertThat(connector.callCount()).isEqualTo(2);
    }

    private static List<PyxisSeatInfo> stubSeats(int roomId) {
        return List.of(new PyxisSeatInfo(roomId * 100 + 1, "1", "일반", "available", 0, 0));
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
        private final AtomicInteger room57Calls = new AtomicInteger();
        private final AtomicInteger room58Calls = new AtomicInteger();

        @Override
        public LibrarySeatStatusResponse fetchSeatStatus(LibraryFloor floor, String token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PyxisSeatInfo> fetchRoomSeats(int roomId, String token) {
            counterFor(roomId).incrementAndGet();
            return stubSeats(roomId);
        }

        int callsFor(int roomId) {
            return counterFor(roomId).get();
        }

        private AtomicInteger counterFor(int roomId) {
            return roomId == 57 ? room57Calls : room58Calls;
        }
    }

    private static final class BlockingConnector implements LibrarySeatConnector {
        private final CountDownLatch waiters = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public LibrarySeatStatusResponse fetchSeatStatus(LibraryFloor floor, String token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PyxisSeatInfo> fetchRoomSeats(int roomId, String token) {
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
            return stubSeats(roomId);
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
        private final AtomicReference<List<PyxisSeatInfo>> recovery = new AtomicReference<>();

        @Override
        public LibrarySeatStatusResponse fetchSeatStatus(LibraryFloor floor, String token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PyxisSeatInfo> fetchRoomSeats(int roomId, String token) {
            callCount.incrementAndGet();
            List<PyxisSeatInfo> next = recovery.get();
            if (next != null) {
                return next;
            }
            throw new ConnectorTimeoutException();
        }

        void recover(List<PyxisSeatInfo> next) {
            recovery.set(next);
        }

        int callCount() {
            return callCount.get();
        }
    }
}
