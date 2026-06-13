package com.ssuai.global.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.LibrarySeatNotAvailableException;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
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
}
