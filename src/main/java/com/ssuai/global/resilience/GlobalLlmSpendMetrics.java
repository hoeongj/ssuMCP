package com.ssuai.global.resilience;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Observability for {@link GlobalLlmSpendBreaker} (ADR 0081, following the metric
 * naming/shape conventions of ADR 0069 + the {@code pyxis.ratelimit.*} /
 * {@code ratelimit.*} counters from ADR 0080).
 *
 * <p>Gauges are backed by an internal {@link AtomicLong} per (meter, window) pair
 * so Micrometer/Prometheus can poll the last-observed value at scrape time without
 * a Redis round-trip per scrape — the value is refreshed as a side effect of every
 * {@link GlobalLlmSpendBreaker#tryAcquire(String)} call.
 */
@Component
public class GlobalLlmSpendMetrics {

    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicLong> usedGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> ceilingGauges = new ConcurrentHashMap<>();

    public GlobalLlmSpendMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    GlobalLlmSpendMetrics() {
        this(new SimpleMeterRegistry());
    }

    /** Records the current used-vs-ceiling snapshot for one (meter, window) pair. */
    public void recordUsage(String meter, String window, long used, long ceiling) {
        gaugeFor(usedGauges, "llm.spend.used", meter, window).set(used);
        gaugeFor(ceilingGauges, "llm.spend.ceiling", meter, window).set(ceiling);
    }

    /** A daily or monthly ceiling was reached — the call was denied (breaker-open event). */
    public void countBreakerOpen(String meter, String window) {
        meterRegistry.counter("llm.spend.breaker.open", "meter", meter, "window", window).increment();
    }

    /** Redis was unreachable/disabled — the breaker failed open (no ceiling enforced this call). */
    public void countRedisFallback(String meter, Throwable exception) {
        String exceptionName = exception == null ? "unknown" : exception.getClass().getSimpleName();
        meterRegistry.counter("llm.spend.redis.fallback",
                        "meter", meter,
                        "exception", exceptionName)
                .increment();
    }

    private AtomicLong gaugeFor(Map<String, AtomicLong> cache, String name, String meter, String window) {
        return cache.computeIfAbsent(meter + ":" + window, key -> {
            AtomicLong value = new AtomicLong();
            meterRegistry.gauge(name, Tags.of("meter", meter, "window", window), value);
            return value;
        });
    }
}
