package com.ssuai.global.security;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A small, reusable in-memory <em>fixed-window</em> per-IP request counter used
 * to throttle abuse-prone endpoints (library login brute-force, chat LLM-cost
 * exhaustion) — security review Wave 3.
 *
 * <h2>Design</h2>
 * <p>Keyed by client IP in a {@link ConcurrentHashMap}. Each bucket holds a
 * window-start timestamp and a counter; once the window ({@code windowMillis})
 * elapses the bucket resets on the next request. This is a fixed-window counter
 * (simpler and cheaper than a true sliding log); with the generous limits we
 * pick the burst-at-boundary imprecision is irrelevant — the goal is to stop
 * abusive volume, not to meter precisely.</p>
 *
 * <h2>Single-replica scope</h2>
 * <p>The map lives in one JVM, so counters are per-pod. The deployment is a
 * single replica today (see {@code deploy/} Helm values), so this is correct.
 * A multi-pod rollout would need a shared store (e.g. Redis with
 * {@code INCR}+{@code EXPIRE}) or sticky sessions, otherwise each pod would
 * allow the full limit and the effective ceiling would be {@code limit ×
 * replicas}. Documented here so the limitation is explicit.</p>
 *
 * <h2>Unbounded-map guard</h2>
 * <p>A naive per-IP map grows with every distinct (or spoofed) client IP — a
 * slow memory-DoS in its own right. We cap the map at {@code maxEntries} and,
 * when full, opportunistically evict buckets whose window has already expired
 * before inserting a new one. Under a flood of unique IPs this bounds memory at
 * the cost of occasionally resetting a stale counter early (harmless).</p>
 *
 * <h2>Role since the multi-pod rollout (SCALE-ROADMAP Phase 1 A1)</h2>
 * <p>This class is no longer the only limiter: {@link SharedIpRateLimiter}
 * wraps it as the per-pod fallback used when Redis is disabled or briefly
 * unavailable (fail-open — see its javadoc). At replica=1 the two are
 * equivalent; the caveat above only applies when this class runs standalone
 * without a shared backing store.</p>
 */
final class IpRateLimiter implements RateLimiterGate {

    /** Default ceiling on tracked IPs — bounds worst-case memory. */
    static final int DEFAULT_MAX_ENTRIES = 100_000;

    private final int limit;
    private final long windowMillis;
    private final int maxEntries;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Window> buckets = new ConcurrentHashMap<>();

    IpRateLimiter(int limit, Duration window) {
        this(limit, window, DEFAULT_MAX_ENTRIES, System::currentTimeMillis);
    }

    /** Test constructor: inject a controllable clock and a small map cap. */
    IpRateLimiter(int limit, Duration window, int maxEntries, LongSupplier clock) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    /**
     * Records one request for {@code key} and reports whether it is permitted.
     * Returns an outcome carrying the allow/deny decision and, on denial, the
     * seconds until the current window resets (for a {@code Retry-After}
     * header). Atomic per key via {@link ConcurrentHashMap#compute}.
     */
    @Override
    public Outcome tryAcquire(String key) {
        long now = clock.getAsLong();
        if (buckets.size() >= maxEntries && !buckets.containsKey(key)) {
            evictExpired(now);
        }
        Window window = buckets.compute(key, (ignoredKey, existing) -> {
            if (existing == null || now - existing.startMillis.get() >= windowMillis) {
                return new Window(now);
            }
            existing.count.incrementAndGet();
            return existing;
        });
        int used = window.count.get();
        if (used <= limit) {
            return Outcome.permit();
        }
        long elapsed = now - window.startMillis.get();
        long retryAfterSeconds = Math.max(1, (windowMillis - elapsed + 999) / 1000);
        return Outcome.deny(retryAfterSeconds);
    }

    /** Drops buckets whose window has elapsed; called only when the map is full. */
    private void evictExpired(long now) {
        buckets.forEach((key, window) -> {
            if (now - window.startMillis.get() >= windowMillis) {
                buckets.remove(key, window);
            }
        });
    }

    private static final class Window {
        private final AtomicLong startMillis;
        private final AtomicInteger count;

        private Window(long startMillis) {
            this.startMillis = new AtomicLong(startMillis);
            this.count = new AtomicInteger(1);
        }
    }

    /** Result of a {@link #tryAcquire} call. */
    record Outcome(boolean allowed, long retryAfterSeconds) {
        static Outcome permit() {
            return new Outcome(true, 0);
        }

        static Outcome deny(long retryAfterSeconds) {
            return new Outcome(false, retryAfterSeconds);
        }
    }
}
