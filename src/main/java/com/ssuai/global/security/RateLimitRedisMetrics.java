package com.ssuai.global.security;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Observability for {@link SharedIpRateLimiter}'s Redis dependency (mirrors
 * {@code LibraryRedisMetrics}, ADR 0024). A failed Redis call never blocks a
 * request — this metric is the only signal an operator has that the shared
 * inbound limiter fell back to per-pod counting.
 */
@Component
public class RateLimitRedisMetrics {

    private final MeterRegistry meterRegistry;

    public RateLimitRedisMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    RateLimitRedisMetrics() {
        this(new SimpleMeterRegistry());
    }

    public void countFallback(String rule, Throwable exception) {
        String exceptionName = exception == null ? "unknown" : exception.getClass().getSimpleName();
        meterRegistry.counter("ratelimit.redis.fallback",
                        "rule", rule,
                        "exception", exceptionName)
                .increment();
    }
}
