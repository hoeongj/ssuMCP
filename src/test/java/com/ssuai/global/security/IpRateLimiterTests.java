package com.ssuai.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class IpRateLimiterTests {

    @Test
    void allowsUpToLimitThenDenies() {
        IpRateLimiter limiter = new IpRateLimiter(3, Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire("ip").allowed()).isTrue();
        assertThat(limiter.tryAcquire("ip").allowed()).isTrue();
        assertThat(limiter.tryAcquire("ip").allowed()).isTrue();
        IpRateLimiter.Outcome denied = limiter.tryAcquire("ip");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void windowResetsAfterItElapses() {
        AtomicLong now = new AtomicLong(0);
        IpRateLimiter limiter = new IpRateLimiter(1, Duration.ofMillis(1000), 100, now::get);

        assertThat(limiter.tryAcquire("ip").allowed()).isTrue();
        assertThat(limiter.tryAcquire("ip").allowed()).isFalse(); // same window, over limit

        now.set(1000); // window elapsed
        assertThat(limiter.tryAcquire("ip").allowed()).isTrue(); // fresh window
    }

    @Test
    void retryAfterReflectsTimeLeftInWindow() {
        AtomicLong now = new AtomicLong(0);
        IpRateLimiter limiter = new IpRateLimiter(1, Duration.ofMillis(10_000), 100, now::get);

        limiter.tryAcquire("ip");           // opens window at t=0
        now.set(3_000);                     // 3s in
        IpRateLimiter.Outcome denied = limiter.tryAcquire("ip");
        assertThat(denied.allowed()).isFalse();
        // ~7s remain (rounded up).
        assertThat(denied.retryAfterSeconds()).isBetween(6L, 8L);
    }

    @Test
    void evictsExpiredBucketsWhenMapIsFull() {
        AtomicLong now = new AtomicLong(0);
        // maxEntries=1 forces eviction logic on the second distinct key.
        IpRateLimiter limiter = new IpRateLimiter(1, Duration.ofMillis(1000), 1, now::get);

        assertThat(limiter.tryAcquire("a").allowed()).isTrue(); // map now holds "a"
        now.set(1000); // "a" window expired → eligible for eviction
        // Inserting "b" finds the map full, evicts expired "a", and proceeds.
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
    }
}
