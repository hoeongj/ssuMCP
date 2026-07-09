package com.ssuai.global.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * SCALE-ROADMAP Phase 1 audit A3 — unit-level coverage for
 * {@link GlobalLlmSpendBreaker}. Mirrors the fake-RAtomicLong-over-a-real-map
 * technique {@code SharedIpRateLimiterTests} uses instead of a real Redis server.
 */
class GlobalLlmSpendBreakerTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static GlobalLlmSpendProperties properties(long dailyCeiling, long monthlyCeiling) {
        GlobalLlmSpendProperties properties = new GlobalLlmSpendProperties();
        properties.setChat(new GlobalLlmSpendProperties.Meter(dailyCeiling, monthlyCeiling));
        properties.setEmbedding(new GlobalLlmSpendProperties.Meter(dailyCeiling, monthlyCeiling));
        return properties;
    }

    /** A minimal in-memory Redisson fake: one real-AtomicLong-backed RAtomicLong per key. */
    private static RedissonClient fakeRedisson(Map<String, AtomicLong> backing) {
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.getAtomicLong(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            AtomicLong value = backing.computeIfAbsent(key, k -> new AtomicLong());
            RAtomicLong counter = mock(RAtomicLong.class);
            when(counter.isExists()).thenAnswer(inv -> value.get() > 0 || backing.containsKey(key));
            when(counter.get()).thenAnswer(inv -> value.get());
            when(counter.incrementAndGet()).thenAnswer(inv -> value.incrementAndGet());
            return counter;
        });
        return redissonClient;
    }

    private static Clock fixedClockAt(String isoLocalDateTime) {
        return Clock.fixed(
                java.time.LocalDateTime.parse(isoLocalDateTime).atZone(KST).toInstant(), KST);
    }

    @Test
    void underCeilingAllowsAndRecordUsageIncrementsBothDailyAndMonthlyCounters() {
        Map<String, AtomicLong> backing = new ConcurrentHashMap<>();
        RedissonClient redissonClient = fakeRedisson(backing);
        GlobalLlmSpendBreaker breaker = new GlobalLlmSpendBreaker(
                properties(10, 100), new GlobalLlmSpendMetrics(new SimpleMeterRegistry()),
                redissonClient, fixedClockAt("2026-07-10T12:00:00"));

        assertThat(breaker.tryAcquire("chat")).isTrue();
        breaker.recordUsage("chat");
        assertThat(breaker.tryAcquire("chat")).isTrue();

        // Exactly one daily key and one monthly key touched, each incremented once.
        assertThat(backing.keySet().stream().filter(k -> k.contains(":daily:"))).hasSize(1);
        assertThat(backing.keySet().stream().filter(k -> k.contains(":monthly:"))).hasSize(1);
        assertThat(backing.values()).allSatisfy(v -> assertThat(v.get()).isEqualTo(1));
    }

    @Test
    void tryAcquireNeverIncrementsCountersOnlyRecordUsageDoes() {
        Map<String, AtomicLong> backing = new ConcurrentHashMap<>();
        RedissonClient redissonClient = fakeRedisson(backing);
        GlobalLlmSpendBreaker breaker = new GlobalLlmSpendBreaker(
                properties(10, 100), new GlobalLlmSpendMetrics(new SimpleMeterRegistry()),
                redissonClient, fixedClockAt("2026-07-10T12:00:00"));

        // Repeated read-only checks — a blocked/failed call site would call ONLY this.
        for (int i = 0; i < 5; i++) {
            assertThat(breaker.tryAcquire("chat")).isTrue();
        }
        // getAtomicLong() may create a handle, but reading it must never bump the value.
        assertThat(backing.values()).allSatisfy(v -> assertThat(v.get()).isZero());

        breaker.recordUsage("chat");
        assertThat(backing.values()).allSatisfy(v -> assertThat(v.get()).isEqualTo(1));
    }

    @Test
    void dailyCeilingReachedBlocksEvenWhenMonthlyHasRoom() {
        Map<String, AtomicLong> backing = new ConcurrentHashMap<>();
        RedissonClient redissonClient = fakeRedisson(backing);
        GlobalLlmSpendBreaker breaker = new GlobalLlmSpendBreaker(
                properties(2, 1000), new GlobalLlmSpendMetrics(new SimpleMeterRegistry()),
                redissonClient, fixedClockAt("2026-07-10T12:00:00"));

        breaker.recordUsage("chat");
        breaker.recordUsage("chat"); // daily now at ceiling (2)

        assertThat(breaker.tryAcquire("chat")).isFalse();
    }

    @Test
    void monthlyCeilingReachedBlocksEvenWhenDailyHasRoom() {
        // Two different days in the same month, small monthly ceiling reached on day 2
        // while each day's own daily ceiling still has room.
        Map<String, AtomicLong> backing = new ConcurrentHashMap<>();
        RedissonClient redissonClient = fakeRedisson(backing);
        GlobalLlmSpendProperties properties = properties(1000, 2);

        GlobalLlmSpendMetrics metrics = new GlobalLlmSpendMetrics(new SimpleMeterRegistry());
        GlobalLlmSpendBreaker day1 = new GlobalLlmSpendBreaker(
                properties, metrics, redissonClient, fixedClockAt("2026-07-10T12:00:00"));
        GlobalLlmSpendBreaker day2 = new GlobalLlmSpendBreaker(
                properties, metrics, redissonClient, fixedClockAt("2026-07-11T12:00:00"));

        day1.recordUsage("chat");
        day2.recordUsage("chat"); // monthly now at ceiling (2), each day's own daily count is only 1

        assertThat(day2.tryAcquire("chat")).isFalse(); // monthly ceiling reached
    }

    @Test
    void dayRolloverThroughInjectedClockResetsTheDailyCounterButKeepsMonthlyCarryingOver() {
        Map<String, AtomicLong> backing = new ConcurrentHashMap<>();
        RedissonClient redissonClient = fakeRedisson(backing);
        GlobalLlmSpendProperties properties = properties(2, 10); // small daily ceiling
        GlobalLlmSpendMetrics metrics = new GlobalLlmSpendMetrics(new SimpleMeterRegistry());

        // Day 1 (23:00 KST): two successful calls exhaust the daily ceiling.
        GlobalLlmSpendBreaker day1 = new GlobalLlmSpendBreaker(
                properties, metrics, redissonClient, fixedClockAt("2026-07-09T23:00:00"));
        day1.recordUsage("chat");
        day1.recordUsage("chat");
        assertThat(day1.tryAcquire("chat")).isFalse(); // daily ceiling reached

        // Day 2 (00:30 KST, same month): a fresh daily key means the breaker allows
        // again even though the ceiling was hit less than 2 hours of wall-clock time
        // earlier — proving rollover is driven by the encoded key, not a TTL wait.
        GlobalLlmSpendBreaker day2 = new GlobalLlmSpendBreaker(
                properties, metrics, redissonClient, fixedClockAt("2026-07-10T00:30:00"));
        assertThat(day2.tryAcquire("chat")).isTrue();

        // The monthly key (July 2026) is shared by both days and keeps accumulating.
        assertThat(backing.keySet().stream().filter(k -> k.contains(":monthly:"))).hasSize(1);
        assertThat(backing.keySet().stream().filter(k -> k.contains(":daily:"))).hasSize(2);
    }

    @Test
    void redisOutageFailsOpenOnTryAcquireAndRecordsFallbackMetric() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.getAtomicLong(anyString())).thenThrow(new RedisException("connection refused"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GlobalLlmSpendBreaker breaker = new GlobalLlmSpendBreaker(
                properties(1, 1), new GlobalLlmSpendMetrics(meterRegistry),
                redissonClient, fixedClockAt("2026-07-10T12:00:00"));

        // Ceiling is 1 (would deny on a healthy Redis), but the outage must fail OPEN.
        assertThat(breaker.tryAcquire("chat")).isTrue();
        assertThat(meterRegistry.find("llm.spend.redis.fallback").counter()).isNotNull();
    }

    @Test
    void redisOutageOnRecordUsageIsSwallowedAndRecordsFallbackMetric() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.getAtomicLong(anyString())).thenThrow(new RedisException("connection refused"));
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GlobalLlmSpendBreaker breaker = new GlobalLlmSpendBreaker(
                properties(1, 1), new GlobalLlmSpendMetrics(meterRegistry),
                redissonClient, fixedClockAt("2026-07-10T12:00:00"));

        // Must not throw — a Redis blip while recording usage must not fail the
        // (already successful) caller.
        breaker.recordUsage("chat");
        assertThat(meterRegistry.find("llm.spend.redis.fallback").counter()).isNotNull();
    }

    @Test
    void nullRedissonClientAlwaysAllowsAndNeverThrows() {
        GlobalLlmSpendBreaker breaker = new GlobalLlmSpendBreaker(
                properties(1, 1), new GlobalLlmSpendMetrics(new SimpleMeterRegistry()),
                null, fixedClockAt("2026-07-10T12:00:00"));

        for (int i = 0; i < 5; i++) {
            assertThat(breaker.tryAcquire("chat")).isTrue();
            breaker.recordUsage("chat"); // no-op, must not throw
        }
    }

    @Test
    void forTestingBehavesLikeRedisDisabled() {
        GlobalLlmSpendBreaker breaker = GlobalLlmSpendBreaker.forTesting();

        assertThat(breaker.tryAcquire("chat")).isTrue();
        assertThat(breaker.tryAcquire("embedding")).isTrue();
        breaker.recordUsage("chat"); // no-op, must not throw
    }

    @Test
    void breakerOpenEventIsRecordedWhenCeilingIsReached() {
        Map<String, AtomicLong> backing = new ConcurrentHashMap<>();
        RedissonClient redissonClient = fakeRedisson(backing);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        GlobalLlmSpendBreaker breaker = new GlobalLlmSpendBreaker(
                properties(1, 100), new GlobalLlmSpendMetrics(meterRegistry),
                redissonClient, fixedClockAt("2026-07-10T12:00:00"));

        breaker.recordUsage("chat");
        assertThat(breaker.tryAcquire("chat")).isFalse();

        assertThat(meterRegistry.find("llm.spend.breaker.open").tag("window", "daily").counter()).isNotNull();
    }

    @Test
    void unknownMeterNameIsRejected() {
        GlobalLlmSpendBreaker breaker = GlobalLlmSpendBreaker.forTesting();
        assertThat(breaker.tryAcquire("chat")).isTrue(); // sanity: known meter still works via forTesting no-op

        Map<String, AtomicLong> backing = new ConcurrentHashMap<>();
        RedissonClient redissonClient = fakeRedisson(backing);
        GlobalLlmSpendBreaker realBreaker = new GlobalLlmSpendBreaker(
                properties(1, 1), new GlobalLlmSpendMetrics(new SimpleMeterRegistry()),
                redissonClient, fixedClockAt("2026-07-10T12:00:00"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> realBreaker.tryAcquire("unknown-meter"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Duration/TTL sanity (values referenced by the class javadoc) ---------

    @Test
    void keyPrefixesEncodeMeterAndPeriod() {
        Map<String, AtomicLong> backing = new ConcurrentHashMap<>();
        RedissonClient redissonClient = fakeRedisson(backing);
        GlobalLlmSpendBreaker breaker = new GlobalLlmSpendBreaker(
                properties(10, 100), new GlobalLlmSpendMetrics(new SimpleMeterRegistry()),
                redissonClient, fixedClockAt("2026-07-10T12:00:00"));

        breaker.recordUsage("embedding");

        assertThat(backing.keySet())
                .anySatisfy(key -> assertThat(key).contains("embedding").contains("daily").contains("2026-07-10"))
                .anySatisfy(key -> assertThat(key).contains("embedding").contains("monthly").contains("2026-07"));
    }
}
