package com.ssuai.domain.library.redis;

import java.util.Optional;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LibrarySchedulerLeadership {

    private static final Logger log = LoggerFactory.getLogger(LibrarySchedulerLeadership.class);

    private final LibraryDistributedLockClient lockClient;
    private final LibraryRedisProperties properties;
    private final LibraryRedisMetrics metrics;

    public LibrarySchedulerLeadership(
            LibraryDistributedLockClient lockClient,
            LibraryRedisProperties properties,
            LibraryRedisMetrics metrics) {
        this.lockClient = lockClient;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void runIfLeader(String jobName, Runnable task) {
        String lockName = properties.schedulerLockName(jobName);
        Optional<LibraryDistributedLockClient.LockLease> lease;
        try {
            lease = lockClient.tryAcquire(lockName, properties.getSchedulerLockWait());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            runWithoutLock(jobName, task, exception);
            return;
        } catch (RuntimeException exception) {
            runWithoutLock(jobName, task, exception);
            return;
        }

        if (lease.isEmpty()) {
            metrics.countSchedulerLock(jobName, "skipped");
            log.debug("library scheduler lock skipped: job={}", jobName);
            return;
        }

        metrics.countSchedulerLock(jobName, "acquired");
        try {
            task.run();
        } finally {
            release(jobName, lease.get());
        }
    }

    private void runWithoutLock(String jobName, Runnable task, Throwable exception) {
        log.warn("library scheduler lock unavailable; running without lock: job={}", jobName, exception);
        metrics.countFailure("scheduler_lock_acquire", exception);
        metrics.countSchedulerLock(jobName, "fallback");
        task.run();
    }

    private void release(String jobName, LibraryDistributedLockClient.LockLease lease) {
        try {
            lease.close();
        } catch (RuntimeException exception) {
            log.warn("library scheduler lock release failed: job={}", jobName, exception);
            metrics.countFailure("scheduler_lock_release", exception);
            metrics.countSchedulerLock(jobName, "release_failed");
        }
    }

    public static LibrarySchedulerLeadership noop() {
        LibraryRedisProperties properties = new LibraryRedisProperties();
        return new LibrarySchedulerLeadership(
                LibraryDistributedLockClient.noop(),
                properties,
                new LibraryRedisMetrics(new SimpleMeterRegistry()));
    }
}
