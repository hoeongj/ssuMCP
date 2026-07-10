package com.ssuai.global.resilience;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for {@link PyxisResilience}'s dual rate cap
 * ({@code ssuai.resilience.pyxis.*} — SCALE-ROADMAP Phase 1 audit A1).
 *
 * <p>Two independent caps per operation type (read/write):
 * <ul>
 *   <li><b>Cluster cap</b> — the real "don't get our egress IP blocked by
 *       oasis.ssu.ac.kr" budget. Redis-shared (via Redisson {@code
 *       RRateLimiter}) so N replicas still total this many requests/second,
 *       not {@code limit × N}. Defaults preserve the exact numbers ADR 0029
 *       picked for the single pod that existed at the time: read 5/s, write
 *       2/s.</li>
 *   <li><b>Per-user fairness cap</b> — a tighter, per-principal budget so one
 *       heavy user cannot alone consume the entire cluster cap and starve
 *       everyone else. Defaults read 2/s, write 1/s — write's cluster budget
 *       is only 2/s total, so a per-user cap equal to it would let one user
 *       monopolize it; 1/s guarantees at least two users can get a write
 *       slot concurrently.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "ssuai.resilience.pyxis")
public class PyxisResilienceProperties {

    private boolean redisEnabled = true;
    private int readClusterLimitPerSecond = 5;
    private Duration readTimeout = Duration.ofMillis(500);
    private int writeClusterLimitPerSecond = 2;
    private Duration writeTimeout = Duration.ofMillis(200);
    private int perUserReadLimitPerSecond = 2;
    private int perUserWriteLimitPerSecond = 1;
    private long retryAfterCapMs = 10_000;

    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    public void setRedisEnabled(boolean redisEnabled) {
        this.redisEnabled = redisEnabled;
    }

    public int getReadClusterLimitPerSecond() {
        return readClusterLimitPerSecond;
    }

    public void setReadClusterLimitPerSecond(int readClusterLimitPerSecond) {
        this.readClusterLimitPerSecond = requirePositive(readClusterLimitPerSecond, "readClusterLimitPerSecond");
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = requireNonNegative(readTimeout, "readTimeout");
    }

    public int getWriteClusterLimitPerSecond() {
        return writeClusterLimitPerSecond;
    }

    public void setWriteClusterLimitPerSecond(int writeClusterLimitPerSecond) {
        this.writeClusterLimitPerSecond = requirePositive(writeClusterLimitPerSecond, "writeClusterLimitPerSecond");
    }

    public Duration getWriteTimeout() {
        return writeTimeout;
    }

    public void setWriteTimeout(Duration writeTimeout) {
        this.writeTimeout = requireNonNegative(writeTimeout, "writeTimeout");
    }

    public int getPerUserReadLimitPerSecond() {
        return perUserReadLimitPerSecond;
    }

    public void setPerUserReadLimitPerSecond(int perUserReadLimitPerSecond) {
        this.perUserReadLimitPerSecond = requirePositive(perUserReadLimitPerSecond, "perUserReadLimitPerSecond");
    }

    public int getPerUserWriteLimitPerSecond() {
        return perUserWriteLimitPerSecond;
    }

    public void setPerUserWriteLimitPerSecond(int perUserWriteLimitPerSecond) {
        this.perUserWriteLimitPerSecond = requirePositive(perUserWriteLimitPerSecond, "perUserWriteLimitPerSecond");
    }

    public long getRetryAfterCapMs() {
        return retryAfterCapMs;
    }

    public void setRetryAfterCapMs(long retryAfterCapMs) {
        this.retryAfterCapMs = requireNonNegative(retryAfterCapMs, "retryAfterCapMs");
    }

    private static int requirePositive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be >= 1");
        }
        return value;
    }

    private static Duration requireNonNegative(Duration value, String field) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be zero or positive");
        }
        return value;
    }

    private static long requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be zero or positive");
        }
        return value;
    }
}
