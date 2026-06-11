package com.ssuai.domain.library.reservation.intent;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ssuai.library.reservation-intent")
public class LibraryReservationIntentProperties {

    private Duration pollInterval = Duration.ofSeconds(1);
    private int batchSize = 10;
    private Duration leaseSeconds = Duration.ofSeconds(30);
    private Duration defaultExpiry = Duration.ofHours(2);
    private Duration backoff = Duration.ofSeconds(30);
    private Duration maxBackoff = Duration.ofMinutes(5);
    private Duration relayInterval = Duration.ofSeconds(2);
    private int relayBatchSize = 50;

    public Duration backoffForAttempt(int attemptCount) {
        int exponent = Math.max(0, attemptCount - 1);
        long multiplier = 1L << Math.min(exponent, 30);
        Duration next = backoff.multipliedBy(multiplier);
        return next.compareTo(maxBackoff) > 0 ? maxBackoff : next;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = positive(pollInterval, "pollInterval");
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        this.batchSize = batchSize;
    }

    public Duration getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(Duration leaseSeconds) {
        this.leaseSeconds = positive(leaseSeconds, "leaseSeconds");
    }

    public Duration getDefaultExpiry() {
        return defaultExpiry;
    }

    public void setDefaultExpiry(Duration defaultExpiry) {
        this.defaultExpiry = positive(defaultExpiry, "defaultExpiry");
    }

    public Duration getBackoff() {
        return backoff;
    }

    public void setBackoff(Duration backoff) {
        this.backoff = positive(backoff, "backoff");
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = positive(maxBackoff, "maxBackoff");
    }

    public Duration getRelayInterval() {
        return relayInterval;
    }

    public void setRelayInterval(Duration relayInterval) {
        this.relayInterval = positive(relayInterval, "relayInterval");
    }

    public int getRelayBatchSize() {
        return relayBatchSize;
    }

    public void setRelayBatchSize(int relayBatchSize) {
        if (relayBatchSize <= 0) {
            throw new IllegalArgumentException("relayBatchSize must be positive");
        }
        this.relayBatchSize = relayBatchSize;
    }

    private static Duration positive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
