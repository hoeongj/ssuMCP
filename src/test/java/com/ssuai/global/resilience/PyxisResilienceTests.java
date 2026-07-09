package com.ssuai.global.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.LibrarySeatNotAvailableException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PyxisResilienceTests {

    private PyxisResilience newResilience() {
        return PyxisResilience.forTesting(new SimpleMeterRegistry());
    }

    @Test
    void readRetriesTransientFailuresThenSucceeds() {
        PyxisResilience resilience = newResilience();
        AtomicInteger calls = new AtomicInteger();

        String result = resilience.read(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new ConnectorTimeoutException(new RuntimeException("timeout"));
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3); // retried twice, succeeded on the 3rd
    }

    @Test
    void writeNeverRetriesTransientFailures() {
        PyxisResilience resilience = newResilience();
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> resilience.write(() -> {
            calls.incrementAndGet();
            throw new ConnectorTimeoutException(new RuntimeException("timeout"));
        })).isInstanceOf(ConnectorTimeoutException.class);

        assertThat(calls.get()).isEqualTo(1); // write is not idempotent → no retry
    }

    @Test
    void businessExceptionDoesNotRetryAndDoesNotOpenBreaker() {
        PyxisResilience resilience = newResilience();
        AtomicInteger calls = new AtomicInteger();

        // 30 "seat taken" results — a business outcome, not an outage.
        for (int i = 0; i < 30; i++) {
            assertThatThrownBy(() -> resilience.read(() -> {
                calls.incrementAndGet();
                throw new LibrarySeatNotAvailableException("warning.seat.occupied");
            })).isInstanceOf(LibrarySeatNotAvailableException.class);
        }

        // Breaker stayed closed (ignored business exceptions), so this still executes.
        AtomicInteger probe = new AtomicInteger();
        String result = resilience.read(() -> {
            probe.incrementAndGet();
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(probe.get()).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(30); // each business failure ran exactly once (no retry)
    }

    @Test
    void breakerOpensAfterRepeatedInfraFailuresAndShortCircuits() {
        PyxisResilience resilience = newResilience();

        // 10 infra failures (minimumNumberOfCalls=10, 100% failure ≥ 50% threshold) → open.
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> resilience.write(() -> {
                throw new ConnectorUnavailableException(new RuntimeException("5xx"));
            })).isInstanceOf(ConnectorUnavailableException.class);
        }

        AtomicInteger callsWhileOpen = new AtomicInteger();
        assertThatThrownBy(() -> resilience.write(() -> {
            callsWhileOpen.incrementAndGet();
            return "should-not-run";
        })).isInstanceOf(CallNotPermittedException.class);

        assertThat(callsWhileOpen.get()).isZero(); // short-circuited: supplier never invoked
    }

    // --- SCALE-ROADMAP Phase 1 audit A1: Pyxis dual cap ---------------------

    private static RRateLimiter rateLimiterMock(boolean acquires) {
        RRateLimiter limiter = mock(RRateLimiter.class);
        when(limiter.trySetRate(eq(RateType.OVERALL), anyLong(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(limiter.tryAcquire(eq(1L), org.mockito.ArgumentMatchers.any(Duration.class)))
                .thenReturn(acquires);
        return limiter;
    }

    @Test
    void clusterCapDenialThrowsRequestNotPermittedAndSupplierNeverRuns() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RRateLimiter clusterLimiter = rateLimiterMock(false);
        RRateLimiter userLimiter = rateLimiterMock(true);
        when(redissonClient.getRateLimiter(contains(":cluster"))).thenReturn(clusterLimiter);
        when(redissonClient.getRateLimiter(contains(":user:"))).thenReturn(userLimiter);

        PyxisResilience resilience = PyxisResilience.forTestingWithRedis(
                new SimpleMeterRegistry(), redissonClient, new PyxisResilienceProperties());

        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> resilience.read("user-a", () -> {
            calls.incrementAndGet();
            return "should-not-run";
        })).isInstanceOf(RequestNotPermitted.class); // same exception the local limiter would throw

        assertThat(calls.get()).isZero();
    }

    @Test
    void perUserFairnessCapDeniesEvenWhenClusterCapHasRoom() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RRateLimiter clusterLimiter = rateLimiterMock(true);
        RRateLimiter userLimiter = rateLimiterMock(false);
        when(redissonClient.getRateLimiter(contains(":cluster"))).thenReturn(clusterLimiter);
        when(redissonClient.getRateLimiter(contains(":user:"))).thenReturn(userLimiter);

        PyxisResilience resilience = PyxisResilience.forTestingWithRedis(
                new SimpleMeterRegistry(), redissonClient, new PyxisResilienceProperties());

        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> resilience.write("heavy-user", () -> {
            calls.incrementAndGet();
            return "should-not-run";
        })).isInstanceOf(RequestNotPermitted.class);

        assertThat(calls.get()).isZero();

        // Ordering regression guard: the per-user fairness cap is checked FIRST, so a
        // fairness-denied caller must never consume a cluster (school-protection)
        // permit — otherwise one greedy user could drain the shared budget while
        // being "denied" on every attempt.
        verify(clusterLimiter, never()).tryAcquire(anyLong(), org.mockito.ArgumentMatchers.any(Duration.class));
        verify(clusterLimiter, never()).tryAcquire();
        verify(clusterLimiter, never()).tryAcquire(anyLong());
    }

    @Test
    void bothCapsGrantingPermitsExecutesTheCall() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RRateLimiter alwaysGrants = rateLimiterMock(true);
        when(redissonClient.getRateLimiter(anyString())).thenReturn(alwaysGrants);

        PyxisResilience resilience = PyxisResilience.forTestingWithRedis(
                new SimpleMeterRegistry(), redissonClient, new PyxisResilienceProperties());

        String result = resilience.read("user-a", () -> "ok");
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void redisOutageFallsBackToLocalPerPodCapAndStillExecutesTheCall() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        when(redissonClient.getRateLimiter(anyString())).thenThrow(new RedisException("connection refused"));

        PyxisResilience resilience = PyxisResilience.forTestingWithRedis(
                new SimpleMeterRegistry(), redissonClient, new PyxisResilienceProperties());

        // forTestingWithRedis's local resilience4j limiter is generous (100_000/s),
        // so the call still succeeds via the per-pod fallback chain.
        String result = resilience.write("user-a", () -> "ok");
        assertThat(result).isEqualTo("ok");
    }

    @Test
    void noRedissonClientSkipsDistributedChecksEntirely() {
        // redissonClient == null (Redis disabled) — identical code path to an
        // outage, exercised separately here for clarity.
        PyxisResilience resilience = PyxisResilience.forTesting(new SimpleMeterRegistry());
        assertThat(resilience.read("user-a", () -> "ok")).isEqualTo("ok");
    }

    // --- principal fingerprinting --------------------------------------------

    @Test
    void principalOfIsStableAndNeverExposesTheRawToken() {
        String fingerprint = PyxisResilience.principalOf("some-pyxis-auth-token");
        assertThat(fingerprint)
                .hasSize(16)
                .doesNotContain("some-pyxis-auth-token")
                .isEqualTo(PyxisResilience.principalOf("some-pyxis-auth-token")); // deterministic
    }

    @Test
    void principalOfFallsBackToUnknownForNullOrBlankToken() {
        assertThat(PyxisResilience.principalOf(null)).isEqualTo("unknown");
        assertThat(PyxisResilience.principalOf("  ")).isEqualTo("unknown");
    }
}
