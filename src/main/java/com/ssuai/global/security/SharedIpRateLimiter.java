package com.ssuai.global.security;

import java.time.Duration;
import java.util.function.LongSupplier;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis-shared per-IP fixed-window counter (SCALE-ROADMAP Phase 1 audit A1).
 *
 * <h2>Problem this replaces</h2>
 * <p>{@link IpRateLimiter} keeps its counters in a JVM-local map, so with N
 * replicas each pod grants the full configured limit independently — the
 * effective ceiling becomes {@code limit × N} instead of {@code limit}. This
 * class counts in Redis instead, so all pods share one budget per IP per
 * rule.</p>
 *
 * <h2>Why a hand-rolled bucket key instead of Redisson {@code RRateLimiter}</h2>
 * <p>Inbound HTTP throttling must decide instantly (a 429 response, not a
 * blocked request thread) and needs an exact "seconds until the window
 * resets" value for the {@code Retry-After} header — the same contract
 * {@link IpRateLimiter} already exposes via {@link IpRateLimiter.Outcome}.
 * Encoding the window boundary into the Redis key itself
 * ({@code prefix:rule:ip:windowIndex}) gives us both for free: the key's TTL
 * self-expires the bucket (no manual eviction, unlike the local map's
 * {@code maxEntries} guard), and the boundary is derivable from
 * {@code now}. {@code RRateLimiter} is used instead for {@link
 * com.ssuai.global.resilience.PyxisResilience}'s dual cap, where a bounded
 * <em>wait</em> for a free slot (not an instant reject) is the desired
 * semantic — see that class's javadoc.</p>
 *
 * <h2>Redis-outage semantics: fail-open to the local fallback</h2>
 * <p>Any {@code RuntimeException} from the Redis call (timeout, connection
 * refused, command error) is caught here: we log a WARN, record the {@code
 * ratelimit.redis.fallback} metric, and delegate the request to an embedded
 * per-pod {@link IpRateLimiter} configured with the exact same limit/window.
 * A Redis blip degrades this feature back to today's per-pod behavior instead
 * of rejecting or — worse — silently admitting unlimited traffic. This
 * mirrors the graceful-degradation convention already established for the
 * library Redis L2 cache/pub-sub/scheduler-lock adapters (ADR 0024): Redis is
 * an efficiency layer here, never a hard dependency for correctness.</p>
 */
final class SharedIpRateLimiter implements RateLimiterGate {

    private static final Logger log = LoggerFactory.getLogger(SharedIpRateLimiter.class);
    private static final String KEY_PREFIX = "ssuai:ratelimit:v1";

    private final RedissonClient redissonClient;
    private final String ruleName;
    private final int limit;
    private final long windowMillis;
    private final RateLimitRedisMetrics metrics;
    private final LongSupplier clock;
    private final IpRateLimiter fallback;

    /**
     * @param redissonClient may be {@code null} — treated exactly like a Redis
     *                       outage (always uses the local fallback), so the
     *                       "Redis feature disabled" and "Redis unreachable"
     *                       paths share one code path.
     */
    SharedIpRateLimiter(
            RedissonClient redissonClient,
            String ruleName,
            int limit,
            Duration window,
            RateLimitRedisMetrics metrics) {
        this(redissonClient, ruleName, limit, window, metrics, System::currentTimeMillis);
    }

    /** Test constructor: inject a controllable clock. */
    SharedIpRateLimiter(
            RedissonClient redissonClient,
            String ruleName,
            int limit,
            Duration window,
            RateLimitRedisMetrics metrics,
            LongSupplier clock) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        this.redissonClient = redissonClient;
        this.ruleName = ruleName;
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.metrics = metrics;
        this.clock = clock;
        this.fallback = new IpRateLimiter(limit, window, IpRateLimiter.DEFAULT_MAX_ENTRIES, clock);
    }

    @Override
    public IpRateLimiter.Outcome tryAcquire(String key) {
        if (redissonClient == null) {
            return fallback.tryAcquire(key);
        }
        try {
            return acquireDistributed(key);
        } catch (RuntimeException exception) {
            log.warn("Shared rate limiter Redis call failed — falling back to per-pod limiter: rule={}",
                    ruleName, exception);
            metrics.countFallback(ruleName, exception);
            return fallback.tryAcquire(key);
        }
    }

    private IpRateLimiter.Outcome acquireDistributed(String key) {
        long now = clock.getAsLong();
        long windowIndex = now / windowMillis;
        String redisKey = KEY_PREFIX + ":" + ruleName + ":" + key + ":" + windowIndex;

        RAtomicLong counter = redissonClient.getAtomicLong(redisKey);
        long count = counter.incrementAndGet();
        if (count == 1) {
            // First hit in this window bucket — set the TTL so the key self-expires
            // instead of accumulating one Redis key per (ip, window) forever.
            counter.expire(Duration.ofMillis(windowMillis + 1000));
        }
        if (count <= limit) {
            return IpRateLimiter.Outcome.permit();
        }
        long elapsedInWindow = now % windowMillis;
        long retryAfterSeconds = Math.max(1, (windowMillis - elapsedInWindow + 999) / 1000);
        return IpRateLimiter.Outcome.deny(retryAfterSeconds);
    }
}
