package com.ssuai.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

/**
 * SCALE-ROADMAP Phase 1 audit A1 — unit-level coverage for
 * {@link SharedIpRateLimiter}'s Redis-outage fallback. Genuine cross-pod
 * budget sharing against a real Redis is covered separately by
 * {@link SharedIpRateLimiterRedisIT} (Testcontainers).
 */
class SharedIpRateLimiterTests {

    @Test
    void nullRedissonClientBehavesLikePlainLocalLimiter() {
        // redissonClient == null is the "Redis feature disabled" steady state —
        // must enforce the exact same limit as IpRateLimiter, with no metric noise.
        RateLimitRedisMetrics metrics = mock(RateLimitRedisMetrics.class);
        SharedIpRateLimiter limiter = new SharedIpRateLimiter(null, "login", 2, Duration.ofMinutes(1), metrics);

        assertThat(limiter.tryAcquire("1.2.3.4").allowed()).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4").allowed()).isTrue();
        assertThat(limiter.tryAcquire("1.2.3.4").allowed()).isFalse();
    }

    @Test
    void redisFailureFallsBackToLocalLimiterAndRecordsMetric() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.getAtomicLong(anyString())).thenThrow(new RedisException("connection refused"));
        RateLimitRedisMetrics metrics = mock(RateLimitRedisMetrics.class);

        SharedIpRateLimiter limiter = new SharedIpRateLimiter(redissonClient, "chat", 2, Duration.ofMinutes(1), metrics);

        assertThat(limiter.tryAcquire("5.5.5.5").allowed()).isTrue();
        assertThat(limiter.tryAcquire("5.5.5.5").allowed()).isTrue();
        assertThat(limiter.tryAcquire("5.5.5.5").allowed()).isFalse(); // fallback still enforces the limit

        verify(metrics, org.mockito.Mockito.atLeastOnce()).countFallback(org.mockito.Mockito.eq("chat"), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void redisSuccessSharesCounterAcrossCallsToTheSameKey() {
        // A minimal fake RAtomicLong backed by a real AtomicLong proves the
        // increment-then-compare wiring without needing a real Redis server.
        RedissonClient redissonClient = mock(RedissonClient.class);
        AtomicLong backing = new AtomicLong();
        RAtomicLong fakeCounter = mock(RAtomicLong.class);
        when(fakeCounter.incrementAndGet()).thenAnswer(invocation -> backing.incrementAndGet());
        when(redissonClient.getAtomicLong(anyString())).thenReturn(fakeCounter);

        RateLimitRedisMetrics metrics = mock(RateLimitRedisMetrics.class);
        SharedIpRateLimiter limiter = new SharedIpRateLimiter(redissonClient, "confirm", 3, Duration.ofMinutes(1), metrics);

        assertThat(limiter.tryAcquire("9.9.9.9").allowed()).isTrue();
        assertThat(limiter.tryAcquire("9.9.9.9").allowed()).isTrue();
        assertThat(limiter.tryAcquire("9.9.9.9").allowed()).isTrue();
        assertThat(limiter.tryAcquire("9.9.9.9").allowed()).isFalse();

        verify(fakeCounter).expire(org.mockito.ArgumentMatchers.any(Duration.class)); // TTL set exactly once (first hit)
    }

    @Test
    void constructorRejectsNonPositiveLimit() {
        RateLimitRedisMetrics metrics = mock(RateLimitRedisMetrics.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new SharedIpRateLimiter(null, "login", 0, Duration.ofMinutes(1), metrics))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
