package com.ssuai.global.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Denylist of revoked refresh-token jtis (rotated-out or logged-out tokens),
 * so a copied/stolen refresh token cannot be replayed after rotation.
 *
 * <p><strong>Backing store.</strong> When a {@link RedissonClient} is wired
 * (dev/prod), the denylist is stored in Redis so revocations survive a JVM
 * restart and are shared across pods — a {@code ConcurrentHashMap} alone lets
 * a revoked token come back to life on restart and is invisible to other pods.
 * Each denied jti is stored as its own key with a TTL equal to the token's
 * remaining lifetime, so entries auto-expire (the set cannot grow unbounded)
 * and a revocation never outlives the token it protects.
 *
 * <p>The stored key is the jti, which is an opaque random identifier embedded
 * in the JWT — NOT the raw signed token — so the denylist never persists a
 * usable credential. (We deliberately do <em>not</em> truncate-hash it: the
 * 8-char {@code *SessionStore.fingerprint} helpers exist for log correlation
 * and have real collision risk; a key collision here would spuriously deny a
 * valid token and force a needless re-login, so the full opaque jti is used.)
 *
 * <p><strong>Fail-open on Redis errors.</strong> Both {@link #deny} (refresh
 * rotation + logout hot path) and {@link #isDenied} (refresh validation)
 * swallow Redis errors and continue: a Redis blip must not break login/refresh
 * for everyone. On a read error we treat the token as not-denied; on a write
 * error we skip the denylist entry. The token still expires on its own short
 * TTL, so a brief revocation gap is an acceptable trade against an outage that
 * blocks all refresh. With Redisson lazy-init the bean is always present, so a
 * runtime Redis outage hits this exception path — the in-memory map only runs
 * when no Redisson client is configured at all (e.g. tests).
 *
 * <p>The in-memory fallback mirrors the original behavior and is also used by
 * the unit test via the package-private {@code (Clock)} constructor.
 */
@Component
public class RefreshTokenDenylist {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenDenylist.class);

    private static final String KEY_PREFIX = "ssuai:auth:refresh-denylist:";

    private final Clock clock;
    private final RedissonClient redissonClient;
    private final Map<String, Instant> deniedJtis = new ConcurrentHashMap<>();

    @Autowired
    public RefreshTokenDenylist(ObjectProvider<RedissonClient> redissonClientProvider) {
        this(redissonClientProvider.getIfAvailable(), Clock.systemUTC());
    }

    // In-memory-only fallback (no Redis client). Used by RefreshTokenDenylistTests.
    RefreshTokenDenylist(Clock clock) {
        this(null, clock);
    }

    RefreshTokenDenylist(RedissonClient redissonClient, Clock clock) {
        this.redissonClient = redissonClient;
        this.clock = clock;
    }

    public void deny(String jti, Instant expiresAt) {
        if (jti == null || jti.isBlank() || expiresAt == null) {
            return;
        }
        Instant now = clock.instant();
        if (!expiresAt.isAfter(now)) {
            return;
        }
        if (redissonClient != null) {
            denyInRedis(jti, Duration.between(now, expiresAt));
            return;
        }
        deniedJtis.put(jti, expiresAt);
        pruneExpired();
    }

    public boolean isDenied(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        if (redissonClient != null) {
            return isDeniedInRedis(jti);
        }
        Instant expiresAt = deniedJtis.get(jti);
        if (expiresAt == null) {
            return false;
        }
        if (!expiresAt.isAfter(clock.instant())) {
            deniedJtis.remove(jti, expiresAt);
            return false;
        }
        return true;
    }

    private void denyInRedis(String jti, Duration ttl) {
        try {
            if (ttl.isZero() || ttl.isNegative()) {
                return;
            }
            RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + jti, StringCodec.INSTANCE);
            bucket.set("1", ttl);
        } catch (RuntimeException exception) {
            // Fail-open: a Redis write failure must not break refresh/logout for
            // everyone. The token still expires via its own short TTL.
            log.warn("refresh-token denylist write skipped (Redis error): {}", exception.toString());
        }
    }

    private boolean isDeniedInRedis(String jti) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + jti, StringCodec.INSTANCE);
            return bucket.isExists();
        } catch (RuntimeException exception) {
            // Fail-open: a Redis read failure must not block all refresh. Treat as
            // not-denied; the token's own short TTL still bounds the exposure.
            log.warn("refresh-token denylist check failed open (Redis error): {}", exception.toString());
            return false;
        }
    }

    int size() {
        pruneExpired();
        return deniedJtis.size();
    }

    private void pruneExpired() {
        Instant now = clock.instant();
        deniedJtis.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }
}
