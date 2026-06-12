package com.ssuai.domain.library.redis;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class LibraryRedisMetrics {

    private final MeterRegistry meterRegistry;

    public LibraryRedisMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    LibraryRedisMetrics() {
        this(new SimpleMeterRegistry());
    }

    public void countFailure(String operation, Throwable exception) {
        String exceptionName = exception == null ? "unknown" : exception.getClass().getSimpleName();
        meterRegistry.counter("library.redis.failure",
                        "operation", operation,
                        "exception", exceptionName)
                .increment();
    }

    public void countSeatEventPublish(String outcome) {
        meterRegistry.counter("library.seat_event.publish", "outcome", outcome).increment();
    }

    public void countSchedulerLock(String jobName, String outcome) {
        meterRegistry.counter("library.scheduler.lock",
                        "job", jobName,
                        "outcome", outcome)
                .increment();
    }
}
