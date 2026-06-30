package com.ssuai.domain.library.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

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
import com.ssuai.domain.library.redis.LibraryDistributedLockClient.LockLease;

/**
 * Exercises the Redisson-backed per-seat distributed lock (ADR 0047) against a real Redis
 * container, not a mock — proving the lock actually serialises across holders. The default
 * {@code test} profile disables Redisson, so this boots a standalone {@link RedissonClient}
 * pointed at the container instead of going through the Spring context.
 *
 * <p>{@code disabledWithoutDocker = true} keeps the offline {@code ./gradlew test} green;
 * CI (Docker present) runs it.
 */
@Testcontainers(disabledWithoutDocker = true)
class LibrarySeatRedisIT {

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
    void acquiresThenReReleasesSeatLock() throws InterruptedException {
        RedissonLibraryDistributedLockClient client = new RedissonLibraryDistributedLockClient(redisson);
        String key = "seat:lock:room-1:seat-7";

        Optional<LockLease> lease = client.tryAcquire(key, Duration.ofSeconds(1));
        assertThat(lease).isPresent();
        lease.get().close();

        // Re-acquiring after release succeeds — the lease.close() genuinely unlocked Redis.
        Optional<LockLease> again = client.tryAcquire(key, Duration.ofSeconds(1));
        assertThat(again).isPresent();
        again.get().close();
    }

    @Test
    void secondHolderIsBlockedWhileLockHeld() throws InterruptedException {
        RedissonLibraryDistributedLockClient client = new RedissonLibraryDistributedLockClient(redisson);
        String key = "seat:lock:room-2:seat-3";

        Optional<LockLease> held = client.tryAcquire(key, Duration.ofSeconds(1));
        assertThat(held).isPresent();

        // Redisson locks are thread-owned + reentrant, so a genuine mutual-exclusion check
        // must contend from a *different* thread; same-thread re-acquire would reenter.
        Optional<LockLease> contender = tryAcquireOnOtherThread(client, key, Duration.ofMillis(300));
        assertThat(contender).isEmpty();

        held.get().close();
    }

    private static Optional<LockLease> tryAcquireOnOtherThread(
            RedissonLibraryDistributedLockClient client, String key, Duration wait) throws InterruptedException {
        AtomicReference<Optional<LockLease>> ref = new AtomicReference<>(Optional.empty());
        Thread t = new Thread(() -> {
            try {
                ref.set(client.tryAcquire(key, wait));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        t.start();
        t.join();
        return ref.get();
    }
}
