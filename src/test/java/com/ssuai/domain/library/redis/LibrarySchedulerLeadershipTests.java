package com.ssuai.domain.library.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class LibrarySchedulerLeadershipTests {

    @Test
    void acquiredLockRunsTaskAndReleasesLease() {
        FakeLockClient lockClient = FakeLockClient.acquired();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LibrarySchedulerLeadership leadership = leadership(lockClient, meterRegistry);
        AtomicBoolean ran = new AtomicBoolean(false);

        leadership.runIfLeader("seat-sampler", () -> ran.set(true));

        assertThat(ran).isTrue();
        assertThat(lockClient.releases.get()).isEqualTo(1);
        assertThat(meterRegistry.find("library.scheduler.lock")
                .tag("job", "seat-sampler")
                .tag("outcome", "acquired")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void lockContentionSkipsTask() {
        FakeLockClient lockClient = FakeLockClient.skipped();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LibrarySchedulerLeadership leadership = leadership(lockClient, meterRegistry);
        AtomicBoolean ran = new AtomicBoolean(false);

        leadership.runIfLeader("seat-hourly-rollup", () -> ran.set(true));

        assertThat(ran).isFalse();
        assertThat(meterRegistry.find("library.scheduler.lock")
                .tag("job", "seat-hourly-rollup")
                .tag("outcome", "skipped")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void redisFailureRunsTaskWithoutLockAndRecordsFallback() {
        FakeLockClient lockClient = FakeLockClient.failing();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LibrarySchedulerLeadership leadership = leadership(lockClient, meterRegistry);
        AtomicBoolean ran = new AtomicBoolean(false);

        leadership.runIfLeader("seat-partition-maintenance", () -> ran.set(true));

        assertThat(ran).isTrue();
        assertThat(meterRegistry.find("library.scheduler.lock")
                .tag("job", "seat-partition-maintenance")
                .tag("outcome", "fallback")
                .counter()
                .count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("library.redis.failure")
                .tag("operation", "scheduler_lock_acquire")
                .counter()
                .count()).isEqualTo(1.0);
    }

    private static LibrarySchedulerLeadership leadership(
            LibraryDistributedLockClient lockClient,
            SimpleMeterRegistry meterRegistry) {
        LibraryRedisProperties properties = new LibraryRedisProperties();
        properties.setSchedulerLockWait(Duration.ZERO);
        return new LibrarySchedulerLeadership(lockClient, properties, new LibraryRedisMetrics(meterRegistry));
    }

    private static final class FakeLockClient implements LibraryDistributedLockClient {
        private final String mode;
        private final AtomicInteger releases = new AtomicInteger();

        private FakeLockClient(String mode) {
            this.mode = mode;
        }

        static FakeLockClient acquired() {
            return new FakeLockClient("acquired");
        }

        static FakeLockClient skipped() {
            return new FakeLockClient("skipped");
        }

        static FakeLockClient failing() {
            return new FakeLockClient("failing");
        }

        @Override
        public Optional<LockLease> tryAcquire(String lockName, Duration waitTime) {
            return switch (mode) {
                case "acquired" -> Optional.of(releases::incrementAndGet);
                case "skipped" -> Optional.empty();
                case "failing" -> throw new IllegalStateException("redis down");
                default -> throw new IllegalStateException("unknown mode");
            };
        }
    }
}
