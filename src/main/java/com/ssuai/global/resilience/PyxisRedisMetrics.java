package com.ssuai.global.resilience;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Observability for {@link PyxisResilience}'s Redis dependency (mirrors
 * {@code LibraryRedisMetrics}, ADR 0024). Recorded whenever the distributed
 * cluster/per-user cap check falls back to the per-pod resilience4j
 * {@code RateLimiter} because Redis is unreachable.
 */
@Component
public class PyxisRedisMetrics {

    private final MeterRegistry meterRegistry;

    public PyxisRedisMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    PyxisRedisMetrics() {
        this(new SimpleMeterRegistry());
    }

    public void countFallback(String operation, Throwable exception) {
        String exceptionName = exception == null ? "unknown" : exception.getClass().getSimpleName();
        meterRegistry.counter("pyxis.ratelimit.redis.fallback",
                        "operation", operation,
                        "exception", exceptionName)
                .increment();
    }
}
