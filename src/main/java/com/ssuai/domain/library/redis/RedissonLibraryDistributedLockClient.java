package com.ssuai.domain.library.redis;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

class RedissonLibraryDistributedLockClient implements LibraryDistributedLockClient {

    private final RedissonClient redissonClient;

    RedissonLibraryDistributedLockClient(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public Optional<LockLease> tryAcquire(String lockName, Duration waitTime) throws InterruptedException {
        RLock lock = redissonClient.getLock(lockName);
        boolean acquired = lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS);
        if (!acquired) {
            return Optional.empty();
        }
        return Optional.of(() -> {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        });
    }
}
