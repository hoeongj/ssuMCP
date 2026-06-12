package com.ssuai.domain.library.timeseries;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ssuai.library.seat-sample")
public class LibrarySeatSampleProperties {

    private boolean enabled = true;
    private Duration cadence = Duration.ofMinutes(5);
    private int retentionDays = 90;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getCadence() {
        return cadence;
    }

    public void setCadence(Duration cadence) {
        this.cadence = positive(cadence, "cadence");
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        if (retentionDays <= 0) {
            throw new IllegalArgumentException("retentionDays must be positive");
        }
        this.retentionDays = retentionDays;
    }

    private static Duration positive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
