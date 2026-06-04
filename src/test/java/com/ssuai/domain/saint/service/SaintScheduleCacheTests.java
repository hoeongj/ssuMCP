package com.ssuai.domain.saint.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.auth.saint.PortalCookies;
import com.ssuai.domain.auth.saint.SaintSessionStore;
import com.ssuai.domain.saint.connector.SaintScheduleConnector;
import com.ssuai.domain.saint.dto.CourseScheduleEntry;
import com.ssuai.domain.saint.dto.MeetingSlot;
import com.ssuai.domain.saint.dto.ScheduleResponse;
import com.ssuai.domain.saint.dto.TermSchedule;
import com.ssuai.global.exception.SaintSessionExpiredException;

class SaintScheduleCacheTests {

    private static final String STUDENT_A = "20241234";
    private static final String STUDENT_B = "20245678";
    private static final PortalCookies COOKIES_A = new PortalCookies("MYSAPSSO2=A");
    private static final PortalCookies COOKIES_B = new PortalCookies("MYSAPSSO2=B");

    @Test
    void hitWithinTtlSkipsConnector() {
        CountingConnector connector = new CountingConnector();
        StubSessionStore store = new StubSessionStore();
        store.put(STUDENT_A, COOKIES_A);
        MutableClock clock = new MutableClock(Instant.parse("2026-05-15T10:00:00Z"));
        SaintScheduleCache cache = new SaintScheduleCache(
                connector, store, Duration.ofHours(1), clock, 100);

        cache.get(STUDENT_A);
        clock.advance(Duration.ofMinutes(30));
        cache.get(STUDENT_A);

        assertThat(connector.invocationCount()).isEqualTo(1);
    }

    @Test
    void missAfterTtlTriggersRefetch() {
        CountingConnector connector = new CountingConnector();
        StubSessionStore store = new StubSessionStore();
        store.put(STUDENT_A, COOKIES_A);
        MutableClock clock = new MutableClock(Instant.parse("2026-05-15T10:00:00Z"));
        SaintScheduleCache cache = new SaintScheduleCache(
                connector, store, Duration.ofHours(1), clock, 100);

        cache.get(STUDENT_A);
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        cache.get(STUDENT_A);

        assertThat(connector.invocationCount()).isEqualTo(2);
    }

    @Test
    void perStudentIsolationKeepsTimetablesSeparate() {
        CountingConnector connector = new CountingConnector();
        StubSessionStore store = new StubSessionStore();
        store.put(STUDENT_A, COOKIES_A);
        store.put(STUDENT_B, COOKIES_B);
        Clock fixed = Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC);
        SaintScheduleCache cache = new SaintScheduleCache(
                connector, store, Duration.ofHours(1), fixed, 100);

        cache.get(STUDENT_A);
        cache.get(STUDENT_B);
        cache.get(STUDENT_A);

        assertThat(connector.invocationCount()).isEqualTo(2);
        assertThat(connector.lastStudentIdsSeen()).containsExactly(STUDENT_A, STUDENT_B);
    }

    @Test
    void missingCookiesRaiseSaintSessionExpiredAndDoNotCache() {
        CountingConnector connector = new CountingConnector();
        StubSessionStore store = new StubSessionStore();
        Clock fixed = Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC);
        SaintScheduleCache cache = new SaintScheduleCache(
                connector, store, Duration.ofHours(1), fixed, 100);

        assertThatThrownBy(() -> cache.get(STUDENT_A))
                .isInstanceOf(SaintSessionExpiredException.class);
        assertThat(connector.invocationCount()).isZero();
        assertThat(cache.size()).isZero();

        store.put(STUDENT_A, COOKIES_A);
        ScheduleResponse recovered = cache.get(STUDENT_A);
        assertThat(recovered).isNotNull();
        assertThat(connector.invocationCount()).isEqualTo(1);
    }

    @Test
    void connectorFailurePropagatesAndDoesNotPoisonCache() {
        FailingThenHealingConnector connector = new FailingThenHealingConnector();
        StubSessionStore store = new StubSessionStore();
        store.put(STUDENT_A, COOKIES_A);
        Clock fixed = Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC);
        SaintScheduleCache cache = new SaintScheduleCache(
                connector, store, Duration.ofHours(1), fixed, 100);

        assertThatThrownBy(() -> cache.get(STUDENT_A))
                .isInstanceOf(SaintSessionExpiredException.class);
        assertThat(cache.size()).isZero();

        connector.heal();
        ScheduleResponse recovered = cache.get(STUDENT_A);

        assertThat(recovered).isNotNull();
        assertThat(connector.invocationCount()).isEqualTo(2);
    }

    @Test
    void capacityEvictsLeastRecentlyUsedStudent() {
        CountingConnector connector = new CountingConnector();
        StubSessionStore store = new StubSessionStore();
        store.put("s1", COOKIES_A);
        store.put("s2", COOKIES_A);
        store.put("s3", COOKIES_A);
        Clock fixed = Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC);
        SaintScheduleCache cache = new SaintScheduleCache(
                connector, store, Duration.ofHours(1), fixed, 2);

        cache.get("s1");
        cache.get("s2");
        cache.get("s3"); // evicts s1

        assertThat(cache.size()).isEqualTo(2);
        cache.get("s1"); // forces a re-fetch
        assertThat(connector.invocationCount()).isEqualTo(4);
    }

    @Test
    void concurrentMissesOnSameStudentShareOneConnectorCall() throws Exception {
        BlockingConnector connector = new BlockingConnector();
        StubSessionStore store = new StubSessionStore();
        store.put(STUDENT_A, COOKIES_A);
        Clock fixed = Clock.fixed(Instant.parse("2026-05-15T10:00:00Z"), ZoneOffset.UTC);
        SaintScheduleCache cache = new SaintScheduleCache(
                connector, store, Duration.ofHours(1), fixed, 100);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<CompletableFuture<ScheduleResponse>> futures = List.of(
                    CompletableFuture.supplyAsync(() -> cache.get(STUDENT_A), pool),
                    CompletableFuture.supplyAsync(() -> cache.get(STUDENT_A), pool),
                    CompletableFuture.supplyAsync(() -> cache.get(STUDENT_A), pool),
                    CompletableFuture.supplyAsync(() -> cache.get(STUDENT_A), pool)
            );
            assertThat(connector.awaitWaiter(15, TimeUnit.SECONDS)).isTrue();
            connector.release();

            ScheduleResponse common = futures.get(0).get(15, TimeUnit.SECONDS);
            for (CompletableFuture<ScheduleResponse> future : futures) {
                assertThat(future.get(15, TimeUnit.SECONDS)).isSameAs(common);
            }
            assertThat(connector.invocationCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private static ScheduleResponse stubResponse() {
        return new ScheduleResponse(2024, 2026, 1, List.of(
                new TermSchedule(2026, 1, List.of(
                        new CourseScheduleEntry("자료구조", "김교수", List.of(
                                new MeetingSlot(1, "월", 3, "10:30-11:45", "정보과학관 30100")))))));
    }

    private static final class StubSessionStore extends SaintSessionStore {
        private final java.util.Map<String, PortalCookies> entries = new java.util.HashMap<>();

        StubSessionStore() {
            super(stubProperties());
        }

        @Override
        public void put(String studentId, PortalCookies cookies) {
            entries.put(studentId, cookies);
        }

        @Override
        public Optional<PortalCookies> cookies(String studentId) {
            return Optional.ofNullable(entries.get(studentId));
        }

        private static com.ssuai.domain.auth.saint.SaintSessionProperties stubProperties() {
            com.ssuai.domain.auth.saint.SaintSessionProperties properties =
                    new com.ssuai.domain.auth.saint.SaintSessionProperties();
            properties.setTtl(Duration.ofMinutes(30));
            properties.setMaxSessions(1000);
            properties.setEncryptionKey("");
            return properties;
        }
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

    private static final class CountingConnector implements SaintScheduleConnector {
        private final AtomicInteger invocations = new AtomicInteger();
        private final java.util.List<String> studentIdsSeen = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public ScheduleResponse fetchSchedule(String studentId, PortalCookies cookies) {
            invocations.incrementAndGet();
            studentIdsSeen.add(studentId);
            return stubResponse();
        }

        int invocationCount() {
            return invocations.get();
        }

        java.util.List<String> lastStudentIdsSeen() {
            return java.util.List.copyOf(studentIdsSeen);
        }
    }

    private static final class FailingThenHealingConnector implements SaintScheduleConnector {
        private final AtomicInteger invocations = new AtomicInteger();
        private volatile boolean healed = false;

        @Override
        public ScheduleResponse fetchSchedule(String studentId, PortalCookies cookies) {
            invocations.incrementAndGet();
            if (!healed) {
                throw new SaintSessionExpiredException("upstream gate");
            }
            return stubResponse();
        }

        void heal() {
            healed = true;
        }

        int invocationCount() {
            return invocations.get();
        }
    }

    private static final class BlockingConnector implements SaintScheduleConnector {
        private final CountDownLatch waiters = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public ScheduleResponse fetchSchedule(String studentId, PortalCookies cookies) {
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
}
