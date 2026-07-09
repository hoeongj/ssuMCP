package com.ssuai.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.redis.testcontainers.RedisContainer;

/**
 * Proves {@link SharedIpRateLimiter} actually shares its budget across pods
 * against a real Redis (SCALE-ROADMAP Phase 1 audit A1) — not a mock. Two
 * separate {@link SharedIpRateLimiter} instances stand in for two backend
 * replicas; both point at the same Redis container and rule name, so the
 * total admitted requests for one IP must not exceed the configured limit
 * even though each "pod" only sees half the traffic.
 *
 * <p>{@code disabledWithoutDocker = true} keeps the offline {@code ./gradlew
 * test} green (mirrors {@code LibrarySeatRedisIT}, ADR 0068); CI (Docker
 * present) runs it.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class SharedIpRateLimiterRedisIT {

    @Container
    static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    private static RedissonClient redisson;

    @BeforeAll
    static void startRedisson() {
        Config config = new Config();
        config.useSingleServer().setAddress(REDIS.getRedisURI());
        redisson = Redisson.create(config);
    }

    @AfterAll
    static void stopRedisson() {
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    @Test
    void twoSimulatedPodsShareOneBudgetForTheSameIp() {
        RateLimitRedisMetrics metrics = new RateLimitRedisMetrics();
        // Same rule name + same Redis => same shared key space, standing in for
        // two backend replicas load-balanced across the same client IP.
        SharedIpRateLimiter pod1 = new SharedIpRateLimiter(redisson, "shared-test-rule", 5, Duration.ofMinutes(1), metrics);
        SharedIpRateLimiter pod2 = new SharedIpRateLimiter(redisson, "shared-test-rule", 5, Duration.ofMinutes(1), metrics);

        String ip = "203.0.113.42";
        int allowed = 0;
        // Alternate pods to simulate load-balanced traffic; fire 8 total requests
        // against a limit of 5 — if pods were NOT sharing, both would independently
        // admit all 5 of their own (8 total allowed instead of 5).
        SharedIpRateLimiter[] pods = {pod1, pod2};
        for (int i = 0; i < 8; i++) {
            if (pods[i % 2].tryAcquire(ip).allowed()) {
                allowed++;
            }
        }

        assertThat(allowed).isEqualTo(5);
    }

    @Test
    void differentIpsGetIndependentBudgetsAcrossPods() {
        RateLimitRedisMetrics metrics = new RateLimitRedisMetrics();
        SharedIpRateLimiter pod1 = new SharedIpRateLimiter(redisson, "shared-test-rule-2", 2, Duration.ofMinutes(1), metrics);
        SharedIpRateLimiter pod2 = new SharedIpRateLimiter(redisson, "shared-test-rule-2", 2, Duration.ofMinutes(1), metrics);

        assertThat(pod1.tryAcquire("198.51.100.1").allowed()).isTrue();
        assertThat(pod2.tryAcquire("198.51.100.1").allowed()).isTrue();
        assertThat(pod1.tryAcquire("198.51.100.1").allowed()).isFalse(); // shared budget exhausted

        // A different IP has its own untouched shared budget.
        assertThat(pod2.tryAcquire("198.51.100.2").allowed()).isTrue();
        assertThat(pod1.tryAcquire("198.51.100.2").allowed()).isTrue();
        assertThat(pod2.tryAcquire("198.51.100.2").allowed()).isFalse();
    }

    @Test
    void budgetResetsAfterTheWindowElapses() throws InterruptedException {
        RateLimitRedisMetrics metrics = new RateLimitRedisMetrics();
        SharedIpRateLimiter limiter = new SharedIpRateLimiter(redisson, "shared-test-rule-3", 1, Duration.ofMillis(1200), metrics);

        String ip = "203.0.113.99";
        assertThat(limiter.tryAcquire(ip).allowed()).isTrue();
        assertThat(limiter.tryAcquire(ip).allowed()).isFalse();

        Thread.sleep(1300); // cross the window boundary

        assertThat(limiter.tryAcquire(ip).allowed()).isTrue();
    }
}
