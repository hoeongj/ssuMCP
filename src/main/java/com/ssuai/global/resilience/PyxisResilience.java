package com.ssuai.global.resilience;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.LibraryAuthRequiredException;
import com.ssuai.global.exception.LibrarySeatNotAvailableException;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Fault tolerance for calls to the school's Pyxis library system (oasis.ssu.ac.kr).
 *
 * <p>One shared {@link CircuitBreaker} ("pyxis") reflects upstream health across all
 * Pyxis callers (seat reads + reservation read/write). When it opens, both reads and
 * writes short-circuit, which protects our threads and stops us hammering a struggling
 * upstream from a single shared egress IP.
 *
 * <p>Read vs write is deliberately asymmetric:
 * <ul>
 *   <li>{@link #read} adds retry with exponential backoff — reads are idempotent.</li>
 *   <li>{@link #write} never retries — reserve/discharge are NOT idempotent, so a retry
 *       could double-book a seat. The circuit breaker still guards it.</li>
 * </ul>
 *
 * <p>Business/auth exceptions ({@code LibrarySeatNotAvailableException},
 * {@code LibraryAuthRequiredException}, {@code ConnectorParseException}) are ignored by
 * the breaker — "seat is taken" or "please log in" is not an infrastructure failure and
 * must not trip the circuit. Only transient infra failures (timeout, 5xx) count.
 */
@Component
public class PyxisResilience {

    private final CircuitBreaker circuitBreaker;
    private final Retry readRetry;
    private final RateLimiter readRateLimiter;
    private final RateLimiter writeRateLimiter;
    private final Bulkhead bulkhead;

    @Autowired
    public PyxisResilience(MeterRegistry meterRegistry) {
        this(meterRegistry,
                RateLimiterConfig.custom()
                        .limitForPeriod(5)
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ofMillis(500))
                        .build(),
                RateLimiterConfig.custom()
                        .limitForPeriod(2)
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ofMillis(200))
                        .build(),
                BulkheadConfig.custom()
                        .maxConcurrentCalls(10)
                        .maxWaitDuration(Duration.ofMillis(500))
                        .build());
    }

    PyxisResilience(MeterRegistry meterRegistry,
                    RateLimiterConfig readRlConfig,
                    RateLimiterConfig writeRlConfig,
                    BulkheadConfig bulkheadConfig) {
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slowCallRateThreshold(100f)
                .slowCallDurationThreshold(Duration.ofSeconds(8))
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(ConnectorTimeoutException.class, ConnectorUnavailableException.class)
                .ignoreExceptions(
                        LibrarySeatNotAvailableException.class,
                        LibraryAuthRequiredException.class,
                        ConnectorParseException.class)
                .build();
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("pyxis");
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry).bindTo(meterRegistry);

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(200), 2.0))
                .retryExceptions(ConnectorTimeoutException.class, ConnectorUnavailableException.class)
                .build();
        RetryRegistry retryRegistry = RetryRegistry.of(retryConfig);
        this.readRetry = retryRegistry.retry("pyxis-read");
        TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(meterRegistry);

        RateLimiterRegistry rlRegistry = RateLimiterRegistry.ofDefaults();
        this.readRateLimiter = rlRegistry.rateLimiter("pyxis-read-rl", readRlConfig);
        this.writeRateLimiter = rlRegistry.rateLimiter("pyxis-write-rl", writeRlConfig);
        TaggedRateLimiterMetrics.ofRateLimiterRegistry(rlRegistry).bindTo(meterRegistry);

        BulkheadRegistry bulkheadRegistry = BulkheadRegistry.of(bulkheadConfig);
        this.bulkhead = bulkheadRegistry.bulkhead("pyxis");
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheadRegistry).bindTo(meterRegistry);
    }

    public static PyxisResilience forTesting(MeterRegistry meterRegistry) {
        return new PyxisResilience(meterRegistry,
                RateLimiterConfig.custom()
                        .limitForPeriod(100_000)
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ZERO)
                        .build(),
                RateLimiterConfig.custom()
                        .limitForPeriod(100_000)
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ZERO)
                        .build(),
                BulkheadConfig.custom()
                        .maxConcurrentCalls(100_000)
                        .maxWaitDuration(Duration.ZERO)
                        .build());
    }

    /** Idempotent reads: Bulkhead → RateLimiter → CircuitBreaker → Retry (innermost first in wrapping). */
    public <T> T read(Supplier<T> call) {
        Supplier<T> guarded = Retry.decorateSupplier(readRetry,
                CircuitBreaker.decorateSupplier(circuitBreaker, call));
        guarded = RateLimiter.decorateSupplier(readRateLimiter, guarded);
        guarded = Bulkhead.decorateSupplier(bulkhead, guarded);
        return guarded.get();
    }

    /** Non-idempotent writes (reserve/discharge): Bulkhead → RateLimiter → CircuitBreaker (no retry). */
    public <T> T write(Supplier<T> call) {
        Supplier<T> guarded = CircuitBreaker.decorateSupplier(circuitBreaker, call);
        guarded = RateLimiter.decorateSupplier(writeRateLimiter, guarded);
        guarded = Bulkhead.decorateSupplier(bulkhead, guarded);
        return guarded.get();
    }

    /** Returns current circuit breaker state for admin monitoring. */
    public CircuitBreaker.State circuitBreakerState() {
        return circuitBreaker.getState();
    }

    /** Returns current failure rate (0–100, or -1.0 if insufficient data). */
    public float circuitBreakerFailureRate() {
        return circuitBreaker.getMetrics().getFailureRate();
    }
}
