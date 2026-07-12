package com.ssuai.global.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.redis.testcontainers.RedisContainer;

import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Proves {@link PyxisResilience}'s dual cap (SCALE-ROADMAP Phase 1 audit A1)
 * against a real Redis, not a mock: (1) the cluster cap is genuinely shared
 * across simulated pods, and (2) the per-user fairness cap throttles one
 * principal without touching another's budget. Mirrors {@code
 * LibrarySeatRedisIT} (ADR 0068) — {@code disabledWithoutDocker = true} keeps
 * the offline {@code ./gradlew test} green; CI (Docker present) runs it.
 */
@Testcontainers(disabledWithoutDocker = true)
class PyxisResilienceRedisIT {

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

    @BeforeEach
    void clearRedisState() {
        // Redisson RRateLimiter configuration is sticky once set (trySetRate is a no-op
        // on an already-configured key) — flush between tests so each one gets a fresh
        // rate config instead of inheriting the previous test's limit.
        redisson.getKeys().flushdb();
    }

    private static PyxisResilienceProperties propertiesWithClusterLimit(int clusterLimit, int perUserLimit) {
        PyxisResilienceProperties properties = new PyxisResilienceProperties();
        properties.setReadClusterLimitPerSecond(clusterLimit);
        properties.setPerUserReadLimitPerSecond(perUserLimit);
        // Small but non-zero: a real wait-then-reject, avoiding any zero-duration edge case.
        properties.setReadTimeout(java.time.Duration.ofMillis(50));
        return properties;
    }

    /**
     * ADR 0097: a single "find any seat" operation can fan out to 6 room reads,
     * so the tuned per-user read cap must let that legitimate scan pass.
     */
    @Test
    void singleUserSeatScanFanOutFitsWithinPerUserReadCap() {
        PyxisResilienceProperties properties = propertiesWithClusterLimit(20, 8);
        PyxisResilience pod = PyxisResilience.forTestingWithRedis(new SimpleMeterRegistry(), redisson, properties);

        for (int i = 0; i < 6; i++) {
            assertThat(pod.read("scanning-user", () -> "ok")).isEqualTo("ok");
        }
    }

    /**
     * ADR 0097: documents the old 2/s per-user read cap self-throttling the
     * same 6-room fan-out before a legitimate scan can finish.
     */
    @Test
    void perUserReadCapAtOldValueWouldThrottleTheSameFanOut() {
        PyxisResilienceProperties properties = propertiesWithClusterLimit(20, 2);
        PyxisResilience pod = PyxisResilience.forTestingWithRedis(new SimpleMeterRegistry(), redisson, properties);

        assertThat(pod.read("scanning-user", () -> "ok")).isEqualTo("ok");
        assertThat(pod.read("scanning-user", () -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> pod.read("scanning-user", () -> "should-not-run"))
                .isInstanceOf(RequestNotPermitted.class);
    }

    /**
     * ADR 0097 trySetRate trap: Redisson only applies limiter config when a key
     * is absent, so a rate change must work without a manual Redis reset.
     */
    @Test
    void rateConfigChangeAppliesBecauseKeyEncodesTheRate() {
        PyxisResilience old = PyxisResilience.forTestingWithRedis(
                new SimpleMeterRegistry(), redisson, propertiesWithClusterLimit(10, 2));

        assertThat(old.read("scanner", () -> "ok")).isEqualTo("ok");
        assertThat(old.read("scanner", () -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> old.read("scanner", () -> "should-not-run"))
                .isInstanceOf(RequestNotPermitted.class);

        PyxisResilience tuned = PyxisResilience.forTestingWithRedis(
                new SimpleMeterRegistry(), redisson, propertiesWithClusterLimit(20, 8));

        for (int i = 0; i < 6; i++) {
            assertThat(tuned.read("scanner", () -> "ok")).isEqualTo("ok");
        }
    }

    @Test
    void clusterCapIsSharedAcrossTwoSimulatedPods() {
        // cluster budget = 2/s, per-user budget generous (10/s) so only the cluster cap binds.
        PyxisResilienceProperties properties = propertiesWithClusterLimit(2, 10);
        PyxisResilience pod1 = PyxisResilience.forTestingWithRedis(new SimpleMeterRegistry(), redisson, properties);
        PyxisResilience pod2 = PyxisResilience.forTestingWithRedis(new SimpleMeterRegistry(), redisson, properties);

        // Different principals so the per-user cap never binds — only the cluster cap is under test.
        assertThat(pod1.read("user-1", () -> "ok")).isEqualTo("ok");
        assertThat(pod2.read("user-2", () -> "ok")).isEqualTo("ok");

        // The cluster budget (2/s) is now exhausted across BOTH pods combined —
        // a third call on either pod, from yet another user, must be denied.
        assertThatThrownBy(() -> pod1.read("user-3", () -> "should-not-run"))
                .isInstanceOf(RequestNotPermitted.class);
    }

    @Test
    void perUserFairnessCapThrottlesOneUserWithoutAffectingAnother() {
        // Cluster budget generous (10/s); per-user budget tight (1/s) so the fairness cap binds first.
        PyxisResilienceProperties properties = propertiesWithClusterLimit(10, 1);
        PyxisResilience pod1 = PyxisResilience.forTestingWithRedis(new SimpleMeterRegistry(), redisson, properties);
        PyxisResilience pod2 = PyxisResilience.forTestingWithRedis(new SimpleMeterRegistry(), redisson, properties);

        assertThat(pod1.read("heavy-user", () -> "ok")).isEqualTo("ok");
        // Same user, different pod — the fairness cap is Redis-shared, so this is denied
        // even though pod2's own local counters have never seen "heavy-user" before.
        assertThatThrownBy(() -> pod2.read("heavy-user", () -> "should-not-run"))
                .isInstanceOf(RequestNotPermitted.class);

        // A different user is unaffected — their own fairness budget is untouched.
        assertThat(pod2.read("other-user", () -> "ok")).isEqualTo("ok");
    }
}
