package com.ssuai.domain.library.redis;

import java.time.Duration;
import java.util.Optional;

public interface LibraryDistributedLockClient {

    Optional<LockLease> tryAcquire(String lockName, Duration waitTime) throws InterruptedException;

    interface LockLease extends AutoCloseable {
        @Override
        void close();
    }

    static LibraryDistributedLockClient noop() {
        return (lockName, waitTime) -> Optional.of(() -> {
        });
    }
}
