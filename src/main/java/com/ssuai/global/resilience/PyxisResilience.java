package com.ssuai.global.resilience;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ssuai.global.exception.ConnectorParseException;
import com.ssuai.global.exception.ConnectorRateLimitedException;
import com.ssuai.global.exception.ConnectorTimeoutException;
import com.ssuai.global.exception.ConnectorUnavailableException;
import com.ssuai.global.exception.LibraryAuthRequiredException;
import com.ssuai.global.exception.LibrarySeatNotAvailableException;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Fault tolerance for calls to the school's Pyxis library system (oasis.ssu.ac.kr).
 *
 * <p>Two {@link CircuitBreaker}s reflect upstream health by operation type:
 * {@code pyxis-read} for seat/current-reservation queries and {@code pyxis-write}
 * for reserve/discharge mutations. A read-side outage must not block reservations,
 * and a write-side outage must not suppress still-useful seat status reads.
 *
 * <p>Read vs write is deliberately asymmetric:
 * <ul>
 *   <li>{@link #read} adds retry — reads are idempotent. HTTP 429 honors
 *       upstream {@code Retry-After}; other transient failures use exponential backoff.</li>
 *   <li>{@link #write} never retries — reserve/discharge are NOT idempotent, so a retry
 *       could double-book a seat. The circuit breaker still guards it.</li>
 * </ul>
 *
 * <p>Business/auth exceptions ({@code LibrarySeatNotAvailableException},
 * {@code LibraryAuthRequiredException}, {@code ConnectorParseException}) are ignored by
 * the breaker — "seat is taken" or "please log in" is not an infrastructure failure and
 * must not trip the circuit. {@code ConnectorRateLimitedException} is also ignored by
 * the breaker and handled by retry/HTTP mapping instead. Only transient infra failures
 * (timeout, 5xx) count.
 *
 * <h2>Dual rate cap (SCALE-ROADMAP Phase 1 audit A1)</h2>
 * <p>Before ADR-next, the read/write {@link RateLimiter} below (limitForPeriod 5/s,
 * 2/s — ADR 0029) was the <em>only</em> upstream cap, and it lived per-pod: with N
 * replicas the real ceiling on requests to oasis.ssu.ac.kr became {@code limit × N}, not
 * {@code limit}. It also had no notion of "one user" — a single heavy caller could
 * consume the whole budget.
 *
 * <p>{@link #read} and {@link #write} now check two Redis-shared
 * {@link RRateLimiter}s (via {@link #acquireDistributed}) before reaching the existing
 * decorator chain below, in this order:
 * <ol>
 *   <li><b>per-user fairness cap</b> — an {@code RRateLimiter} keyed by a SHA-256
 *       fingerprint of the caller's Pyxis auth token (never the raw token — same
 *       privacy stance as ADR 0024 D3 and {@code LibrarySessionStore.fingerprint}), at a
 *       tighter budget so one principal cannot alone exhaust the cluster cap. Checked
 *       FIRST so a fairness-denied caller wastes only its own budget — if the cluster
 *       permit were taken first, each denied attempt would still consume a slice of the
 *       shared budget and one greedy user could drain it while being "denied".</li>
 *   <li><b>cluster cap</b> — one Redisson {@code RRateLimiter} per operation type,
 *       keyed the same regardless of which pod calls it, configured at the real
 *       school-protection budget ({@link PyxisResilienceProperties#getReadClusterLimitPerSecond()}
 *       / {@code getWriteClusterLimitPerSecond()}). N replicas now share this one
 *       budget instead of multiplying it.</li>
 * </ol>
 *
 * <p>Redisson {@code RRateLimiter.trySetRate} is set-if-absent: once a limiter
 * key exists, a redeploy with a different configured rate would otherwise be
 * silently ignored. Distributed limiter keys therefore encode the configured
 * rate as {@code :r<n>} so deploy-time config changes use fresh keys immediately;
 * both per-user and cluster keys also carry TTLs to reclaim idle or superseded
 * rate keys.
 *
 * <p>Both use {@code RRateLimiter.tryAcquire(1, timeout)} — a bounded
 * <em>wait</em> for a free slot, not an instant reject — with the exact same
 * {@code timeoutDuration} the local resilience4j {@link RateLimiter} already used
 * (500ms read / 200ms write). This preserves the existing failure mode: on denial we
 * throw the exact same {@link RequestNotPermitted} exception the local limiter would
 * have thrown, which is not specifically caught by {@code GlobalExceptionHandler} and
 * therefore surfaces as the same generic-500 path as before — only the counter's scope
 * changed, not what happens when the cap is hit.
 *
 * <h2>Redis-outage semantics: fail-open to the existing per-pod limiter</h2>
 * <p>Any {@code RuntimeException} while talking to Redis (timeout, connection refused)
 * is caught in {@link #acquireDistributed}: we log a WARN, record
 * {@code pyxis.ratelimit.redis.fallback}, and simply skip the distributed checks for
 * that call. Execution falls straight through to the unchanged local resilience4j
 * {@link RateLimiter} below — which is already configured at the same real limit (20/s
 * read, 2/s write — ADR 0097) — so a Redis blip degrades this feature back to
 * today's per-pod behavior instead of blocking all Pyxis traffic. The same happens by
 * construction when Redis is disabled ({@link PyxisResilienceProperties#isRedisEnabled()}
 * false) or no Redisson bean is available.
 */
@Component
public class PyxisResilience {

    private static final Logger log = LoggerFactory.getLogger(PyxisResilience.class);
    private static final String DEFAULT_PRINCIPAL = "unknown";
    private static final String KEY_PREFIX = "ssuai:resilience:pyxis:v1";
    /** Per-user Redis keys self-expire after this much inactivity (bounds Redis memory). */
    private static final Duration PER_USER_KEY_TTL = Duration.ofMinutes(5);
    /** Cluster limiter keys self-expire after inactivity so rate changes and superseded-rate keys reclaim; rate is encoded in the key for immediate deploy-time config changes. */
    private static final Duration CLUSTER_KEY_TTL = Duration.ofMinutes(10);
    private static final long READ_RETRY_BASE_MS = 200L;
    private static final double READ_RETRY_MULTIPLIER = 2.0;

    private final CircuitBreaker readCircuitBreaker;
    private final CircuitBreaker writeCircuitBreaker;
    private final Retry readRetry;
    private final RateLimiter readRateLimiter;
    private final RateLimiter writeRateLimiter;
    private final Bulkhead bulkhead;

    private final RedissonClient redissonClient;
    private final PyxisResilienceProperties properties;
    private final PyxisRedisMetrics redisMetrics;

    @Autowired
    public PyxisResilience(
            MeterRegistry meterRegistry,
            PyxisResilienceProperties properties,
            PyxisRedisMetrics redisMetrics,
            ObjectProvider<RedissonClient> redissonClientProvider) {
        this(meterRegistry,
                properties.isRedisEnabled() ? redissonClientProvider.getIfAvailable() : null,
                properties,
                redisMetrics,
                RateLimiterConfig.custom()
                        .limitForPeriod(properties.getReadClusterLimitPerSecond())
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(properties.getReadTimeout())
                        .build(),
                RateLimiterConfig.custom()
                        .limitForPeriod(properties.getWriteClusterLimitPerSecond())
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(properties.getWriteTimeout())
                        .build(),
                BulkheadConfig.custom()
                        .maxConcurrentCalls(10)
                        .maxWaitDuration(Duration.ofMillis(500))
                        .build());
    }

    PyxisResilience(MeterRegistry meterRegistry,
                    RedissonClient redissonClient,
                    PyxisResilienceProperties properties,
                    PyxisRedisMetrics redisMetrics,
                    RateLimiterConfig readRlConfig,
                    RateLimiterConfig writeRlConfig,
                    BulkheadConfig bulkheadConfig) {
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.redisMetrics = redisMetrics;

        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slowCallRateThreshold(100f)
                .slowCallDurationThreshold(Duration.ofSeconds(8))
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(ConnectorTimeoutException.class, ConnectorUnavailableException.class)
                .ignoreExceptions(
                        LibrarySeatNotAvailableException.class,
                        LibraryAuthRequiredException.class,
                        ConnectorParseException.class,
                        ConnectorRateLimitedException.class)
                .build();
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);
        this.readCircuitBreaker = circuitBreakerRegistry.circuitBreaker("pyxis-read");
        this.writeCircuitBreaker = circuitBreakerRegistry.circuitBreaker("pyxis-write");
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry).bindTo(meterRegistry);

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .intervalBiFunction(retryIntervalBiFunction(properties.getRetryAfterCapMs()))
                .retryExceptions(
                        ConnectorTimeoutException.class,
                        ConnectorUnavailableException.class,
                        ConnectorRateLimitedException.class)
                .build();
        RetryRegistry retryRegistry = RetryRegistry.of(retryConfig);
        this.readRetry = retryRegistry.retry("pyxis-read");
        TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(meterRegistry);

        RateLimiterRegistry rlRegistry = RateLimiterRegistry.ofDefaults();
        this.readRateLimiter = rlRegistry.rateLimiter("pyxis-read-rl", readRlConfig);
        this.writeRateLimiter = rlRegistry.rateLimiter("pyxis-write-rl", writeRlConfig);
        TaggedRateLimiterMetrics.ofRateLimiterRegistry(rlRegistry).bindTo(meterRegistry);

        BulkheadRegistry bulkheadRegistry = BulkheadRegistry.of(bulkheadConfig);
        this.bulkhead = bulkheadRegistry.bulkhead("pyxis");
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheadRegistry).bindTo(meterRegistry);
    }

    public static PyxisResilience forTesting(MeterRegistry meterRegistry) {
        PyxisResilienceProperties properties = new PyxisResilienceProperties();
        properties.setRetryAfterCapMs(1);
        return forTesting(meterRegistry, properties);
    }

    public static PyxisResilience forTesting(MeterRegistry meterRegistry, PyxisResilienceProperties properties) {
        return new PyxisResilience(meterRegistry,
                null, // no Redis in tests — exercises the exact same per-pod path as before
                properties,
                new PyxisRedisMetrics(meterRegistry),
                RateLimiterConfig.custom()
                        .limitForPeriod(100_000)
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ZERO)
                        .build(),
                RateLimiterConfig.custom()
                        .limitForPeriod(100_000)
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ZERO)
                        .build(),
                BulkheadConfig.custom()
                        .maxConcurrentCalls(100_000)
                        .maxWaitDuration(Duration.ZERO)
                        .build());
    }

    private static IntervalBiFunction<Object> retryIntervalBiFunction(long retryAfterCapMs) {
        return (attempt, result) -> retryIntervalMillis(
                attempt,
                result.isLeft() ? result.getLeft() : null,
                retryAfterCapMs);
    }

    static long retryIntervalMillis(int attempt, Throwable throwable, long retryAfterCapMs) {
        if (throwable instanceof ConnectorRateLimitedException exception && exception.getRetryAfter() != null) {
            long retryAfterMillis = Math.max(0L, exception.getRetryAfter().toMillis());
            return Math.min(retryAfterMillis, retryAfterCapMs);
        }
        return exponentialBackoffMillis(attempt);
    }

    private static long exponentialBackoffMillis(int attempt) {
        int exponent = Math.max(0, attempt - 1);
        double millis = READ_RETRY_BASE_MS * Math.pow(READ_RETRY_MULTIPLIER, exponent);
        if (millis >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return (long) millis;
    }

    /** Test-only factory that wires in a real/fake Redisson client to exercise the dual cap. */
    static PyxisResilience forTestingWithRedis(MeterRegistry meterRegistry, RedissonClient redissonClient,
                                                PyxisResilienceProperties properties) {
        return new PyxisResilience(meterRegistry,
                redissonClient,
                properties,
                new PyxisRedisMetrics(meterRegistry),
                RateLimiterConfig.custom()
                        .limitForPeriod(100_000)
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ZERO)
                        .build(),
                RateLimiterConfig.custom()
                        .limitForPeriod(100_000)
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(Duration.ZERO)
                        .build(),
                BulkheadConfig.custom()
                        .maxConcurrentCalls(100_000)
                        .maxWaitDuration(Duration.ZERO)
                        .build());
    }

    /** Idempotent reads, anonymous principal (kept for callers with no natural per-user key). */
    public <T> T read(Supplier<T> call) {
        return read(DEFAULT_PRINCIPAL, call);
    }

    /** Idempotent reads: distributed dual cap, then Bulkhead → RateLimiter → CircuitBreaker → Retry. */
    public <T> T read(String principal, Supplier<T> call) {
        acquireDistributed("read", principal,
                properties.getReadClusterLimitPerSecond(), properties.getPerUserReadLimitPerSecond(),
                properties.getReadTimeout(), readRateLimiter);

        Supplier<T> guarded = Retry.decorateSupplier(readRetry,
                CircuitBreaker.decorateSupplier(readCircuitBreaker, call));
        guarded = RateLimiter.decorateSupplier(readRateLimiter, guarded);
        guarded = Bulkhead.decorateSupplier(bulkhead, guarded);
        return guarded.get();
    }

    /** Non-idempotent writes (reserve/discharge), anonymous principal. */
    public <T> T write(Supplier<T> call) {
        return write(DEFAULT_PRINCIPAL, call);
    }

    /** Non-idempotent writes: distributed dual cap, then Bulkhead → RateLimiter → CircuitBreaker (no retry). */
    public <T> T write(String principal, Supplier<T> call) {
        acquireDistributed("write", principal,
                properties.getWriteClusterLimitPerSecond(), properties.getPerUserWriteLimitPerSecond(),
                properties.getWriteTimeout(), writeRateLimiter);

        Supplier<T> guarded = CircuitBreaker.decorateSupplier(writeCircuitBreaker, call);
        guarded = RateLimiter.decorateSupplier(writeRateLimiter, guarded);
        guarded = Bulkhead.decorateSupplier(bulkhead, guarded);
        return guarded.get();
    }

    /**
     * Fingerprints an upstream auth token into a stable per-user key. Never
     * stores/keys on the raw token (privacy — same stance as
     * {@code LibrarySessionStore.fingerprint}, ADR 0024 D3).
     */
    public static String principalOf(String pyxisAuthToken) {
        if (pyxisAuthToken == null || pyxisAuthToken.isBlank()) {
            return DEFAULT_PRINCIPAL;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(pyxisAuthToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).substring(0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /**
     * Checks the per-user fairness cap and then the cluster-wide cap (both Redisson
     * {@link RRateLimiter}s) before the call reaches the local decorator chain — see
     * the ordering rationale inline: fairness must be judged before any shared budget
     * is spent. Denial throws the same {@link RequestNotPermitted} the local
     * {@link RateLimiter} would throw (preserving the existing failure mode); any
     * Redis-side {@code RuntimeException} is treated as an outage and falls through
     * silently (WARN + metric), letting the unchanged local per-pod chain below act
     * as the safety net.
     *
     * <p>Redisson {@code trySetRate} never overwrites an already-configured key, so
     * rate config changes to existing limiter keys are silently ignored. The configured
     * rate is encoded in each limiter key ({@code :r<n>}) so a deploy-time change
     * applies immediately, and both keys have TTLs to reclaim idle or superseded-rate
     * limiter state.
     */
    private void acquireDistributed(
            String operation,
            String principal,
            int clusterLimit,
            int perUserLimit,
            Duration timeout,
            RateLimiter localLimiterForExceptionIdentity) {
        if (redissonClient == null) {
            return;
        }
        try {
            // Per-user fairness cap FIRST: a fairness-denied caller must waste only its
            // OWN budget. If the cluster permit were acquired first, every denied attempt
            // by one greedy principal would still consume a slice of the shared
            // school-protection budget — draining it for everyone and defeating the
            // fairness cap's purpose. The reverse waste (user permit consumed, then the
            // cluster cap denies) only harms that same caller, which is acceptable.
            String principalKey = KEY_PREFIX + ":" + operation + ":user:" + safePrincipal(principal)
                    + ":r" + perUserLimit;
            RRateLimiter perUser = redissonClient.getRateLimiter(principalKey);
            perUser.trySetRate(RateType.OVERALL, perUserLimit, Duration.ofSeconds(1));
            perUser.expire(PER_USER_KEY_TTL);
            if (!perUser.tryAcquire(1, timeout)) {
                throw RequestNotPermitted.createRequestNotPermitted(localLimiterForExceptionIdentity);
            }

            RRateLimiter cluster = redissonClient.getRateLimiter(KEY_PREFIX + ":" + operation
                    + ":cluster:r" + clusterLimit);
            cluster.trySetRate(RateType.OVERALL, clusterLimit, Duration.ofSeconds(1));
            cluster.expire(CLUSTER_KEY_TTL);
            if (!cluster.tryAcquire(1, timeout)) {
                throw RequestNotPermitted.createRequestNotPermitted(localLimiterForExceptionIdentity);
            }
        } catch (RequestNotPermitted exception) {
            throw exception; // genuine denial — propagate exactly like the local limiter would.
        } catch (RuntimeException exception) {
            log.warn("Pyxis distributed rate limiter unavailable — falling back to per-pod cap: operation={}",
                    operation, exception);
            redisMetrics.countFallback(operation, exception);
        }
    }

    private static String safePrincipal(String principal) {
        return (principal == null || principal.isBlank()) ? DEFAULT_PRINCIPAL : principal;
    }

    /** Returns aggregate circuit breaker state for compatibility with older callers. */
    public CircuitBreaker.State circuitBreakerState() {
        return List.of(readCircuitBreaker, writeCircuitBreaker).stream()
                .map(CircuitBreaker::getState)
                .max(Comparator.comparingInt(PyxisResilience::stateSeverity))
                .orElse(CircuitBreaker.State.CLOSED);
    }

    /** Returns aggregate failure rate (0–100, or -1.0 if insufficient data) for older callers. */
    public float circuitBreakerFailureRate() {
        return Math.max(readCircuitBreakerFailureRate(), writeCircuitBreakerFailureRate());
    }

    public CircuitBreaker.State readCircuitBreakerState() {
        return readCircuitBreaker.getState();
    }

    public CircuitBreaker.State writeCircuitBreakerState() {
        return writeCircuitBreaker.getState();
    }

    public float readCircuitBreakerFailureRate() {
        return readCircuitBreaker.getMetrics().getFailureRate();
    }

    public float writeCircuitBreakerFailureRate() {
        return writeCircuitBreaker.getMetrics().getFailureRate();
    }

    public float readCircuitBreakerSlowCallRate() {
        return readCircuitBreaker.getMetrics().getSlowCallRate();
    }

    public float writeCircuitBreakerSlowCallRate() {
        return writeCircuitBreaker.getMetrics().getSlowCallRate();
    }

    private static int stateSeverity(CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED, METRICS_ONLY, DISABLED -> 0;
            case HALF_OPEN -> 1;
            case OPEN -> 2;
            case FORCED_OPEN -> 3;
        };
    }
}
