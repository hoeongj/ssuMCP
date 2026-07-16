package com.ssuai.domain.lms.export;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@Component
public class LmsExportMetrics {

    private final MeterRegistry meterRegistry;

    public LmsExportMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    static LmsExportMetrics noop() {
        return new LmsExportMetrics(new SimpleMeterRegistry());
    }

    public void countJob(String outcome, String reason) {
        meterRegistry.counter(
                "lms.export.jobs",
                "outcome", outcome,
                "reason", reason).increment();
    }

    public void countFiles(String outcome, String reason, int count) {
        if (count <= 0) {
            return;
        }
        meterRegistry.counter(
                "lms.export.files",
                "outcome", outcome,
                "reason", reason).increment(count);
    }
}
