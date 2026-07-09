package com.ssuai.global.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Global daily/monthly LLM spend circuit breaker (SCALE-ROADMAP Phase 1 audit A3).
 *
 * <h2>Problem this closes</h2>
 * <p>Every metered LLM/embedding call site is already protected by a PER-IP request
 * cap ({@code ssuai.ratelimit.chat-per-minute}, ADR 0061) and per-provider circuit
 * breakers ({@link com.ssuai.domain.chat.service.LlmProviderChain}). Neither bounds
 * the CLUSTER-WIDE call volume: many distinct IPs — more real users, or one attacker
 * rotating source IPs — each staying under their own per-IP cap can still drive an
 * unbounded daily/monthly bill. This breaker adds one more layer above both: a
 * Redis-shared call-count budget checked before the metered call and incremented
 * only after it succeeds.
 *
 * <h2>Unit of measure: call count, not tokens</h2>
 * <p>Token counts are not uniformly available at the enforcement boundary — the
 * chat surface fans out across ten independent OpenAI-compatible providers
 * ({@link com.ssuai.domain.chat.service.llm.LlmProvider}) whose responses are
 * normalized into {@link com.ssuai.domain.chat.service.llm.LlmCompletionResult},
 * which carries no usage/token field, and adding one would require touching every
 * provider's response parsing. Call count is measurable at a single choke point for
 * both meters today and is a reasonable proxy: this project's per-call token budget
 * is already capped independently ({@code ssuai.chat.llm.max-tokens=400},
 * {@code ssuai.academic-policy.embedding.batch-size=8}), so a call-count ceiling
 * indirectly bounds total token volume too. See ADR 0081 for the alternatives
 * considered.
 *
 * <h2>Two independent meters</h2>
 * <p>{@code tryAcquire}/{@code recordUsage} take a {@code meter} name ("chat" or
 * "embedding", see {@link GlobalLlmSpendProperties}) because the two surfaces have
 * unrelated cost/quota profiles — mixing them into one shared ceiling would let a
 * chat spike starve the (much smaller, free-tier-bounded) embedding budget or vice
 * versa.
 *
 * <h2>Enforcement order: check, then call, then record</h2>
 * <p>{@link #tryAcquire(String)} only READS the current daily/monthly counts — it
 * never increments. {@link #recordUsage(String)} increments both counters and must
 * be called by the caller ONLY after the metered call actually succeeded. This
 * ordering is a deliberate requirement (not an implementation detail): a blocked or
 * failed call must not consume budget, otherwise a string of upstream 5xx/429s would
 * itself exhaust the breaker and lock out genuinely successful traffic.
 *
 * <h2>Redis key shape</h2>
 * <p>{@code ssuai:resilience:llm-spend:v1:{meter}:daily:{yyyy-MM-dd}} (TTL 2 days)
 * and {@code ssuai:resilience:llm-spend:v1:{meter}:monthly:{yyyy-MM}} (TTL 32 days).
 * Encoding the period into the key itself (mirrors
 * {@link com.ssuai.global.security.SharedIpRateLimiter}'s windowed key) means
 * rollover to a new day/month is implicit — no cron reset job, no read-modify-write
 * race on a shared "current period" field. Old keys simply age out via TTL.
 *
 * <h2>Redis-outage semantics: fail-open</h2>
 * <p>Same convention as ADR 0080 (Pyxis dual cap / SharedIpRateLimiter): any
 * {@code RuntimeException} talking to Redis, and a {@code null} Redisson client
 * (feature disabled or no bean), are treated identically — WARN + the
 * {@code llm.spend.redis.fallback} metric, and the call is ALLOWED to proceed.
 * Unlike those two, there is no local (per-pod) fallback counter here: this feature
 * has no pre-existing per-pod spend cap to degrade to, and a per-pod-only counter
 * would silently under-count in the exact multi-pod scenario A3 exists to catch.
 * Redis is treated purely as an efficiency/visibility layer — never a hard
 * dependency for the backend to keep serving chat/RAG traffic.
 */
@Component
public class GlobalLlmSpendBreaker {

    private static final Logger log = LoggerFactory.getLogger(GlobalLlmSpendBreaker.class);
    private static final String KEY_PREFIX = "ssuai:resilience:llm-spend:v1";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final Duration DAILY_KEY_TTL = Duration.ofDays(2);
    private static final Duration MONTHLY_KEY_TTL = Duration.ofDays(32);
    private static final String WINDOW_DAILY = "daily";
    private static final String WINDOW_MONTHLY = "monthly";

    private final RedissonClient redissonClient;
    private final GlobalLlmSpendProperties properties;
    private final GlobalLlmSpendMetrics metrics;
    private final Clock clock;

    @Autowired
    public GlobalLlmSpendBreaker(
            GlobalLlmSpendProperties properties,
            GlobalLlmSpendMetrics metrics,
            ObjectProvider<RedissonClient> redissonClientProvider) {
        this(properties,
                metrics,
                properties.isRedisEnabled() ? redissonClientProvider.getIfAvailable() : null,
                Clock.system(KST));
    }

    /** Test constructor: inject a fake/real Redisson client and a controllable clock. */
    GlobalLlmSpendBreaker(
            GlobalLlmSpendProperties properties,
            GlobalLlmSpendMetrics metrics,
            RedissonClient redissonClient,
            Clock clock) {
        this.properties = properties;
        this.metrics = metrics;
        this.redissonClient = redissonClient;
        this.clock = clock;
    }

    /**
     * Always-allow instance for call sites' backward-compatible test constructors
     * (mirrors {@link PyxisResilience#forTesting}) — behaves exactly like "Redis
     * disabled": {@link #tryAcquire(String)} always returns {@code true} and
     * {@link #recordUsage(String)} is a no-op.
     */
    public static GlobalLlmSpendBreaker forTesting() {
        return new GlobalLlmSpendBreaker(
                new GlobalLlmSpendProperties(), new GlobalLlmSpendMetrics(), null, Clock.system(KST));
    }

    /**
     * Read-only ceiling check. Returns {@code true} when the caller may proceed
     * with the metered call. Never increments a counter and never blocks traffic
     * on a Redis outage (fail-open — see class javadoc).
     */
    public boolean tryAcquire(String meter) {
        if (redissonClient == null) {
            return true;
        }
        GlobalLlmSpendProperties.Meter ceiling = properties.meterFor(meter);
        try {
            long dailyCount = currentCount(dailyKey(meter));
            long monthlyCount = currentCount(monthlyKey(meter));
            metrics.recordUsage(meter, WINDOW_DAILY, dailyCount, ceiling.getDailyCallCeiling());
            metrics.recordUsage(meter, WINDOW_MONTHLY, monthlyCount, ceiling.getMonthlyCallCeiling());

            if (dailyCount >= ceiling.getDailyCallCeiling()) {
                metrics.countBreakerOpen(meter, WINDOW_DAILY);
                log.warn("Global LLM spend breaker OPEN — meter={} window=daily used={} ceiling={}",
                        meter, dailyCount, ceiling.getDailyCallCeiling());
                return false;
            }
            if (monthlyCount >= ceiling.getMonthlyCallCeiling()) {
                metrics.countBreakerOpen(meter, WINDOW_MONTHLY);
                log.warn("Global LLM spend breaker OPEN — meter={} window=monthly used={} ceiling={}",
                        meter, monthlyCount, ceiling.getMonthlyCallCeiling());
                return false;
            }

            warnIfNearCeiling(meter, WINDOW_DAILY, dailyCount, ceiling.getDailyCallCeiling());
            warnIfNearCeiling(meter, WINDOW_MONTHLY, monthlyCount, ceiling.getMonthlyCallCeiling());
            return true;
        } catch (RuntimeException exception) {
            log.warn("Global LLM spend breaker Redis call failed — failing open: meter={}", meter, exception);
            metrics.countRedisFallback(meter, exception);
            return true;
        }
    }

    /**
     * Increments both the daily and monthly counters for {@code meter} by one call.
     * MUST be called only after the metered call has actually succeeded — see class
     * javadoc "Enforcement order". A Redis failure here is swallowed (WARN + metric,
     * fail-open): the caller's successful result is not undone just because we
     * could not record the spend.
     */
    public void recordUsage(String meter) {
        if (redissonClient == null) {
            return;
        }
        try {
            increment(dailyKey(meter), DAILY_KEY_TTL);
            increment(monthlyKey(meter), MONTHLY_KEY_TTL);
        } catch (RuntimeException exception) {
            log.warn("Global LLM spend breaker Redis call failed while recording usage — spend not counted "
                    + "this call: meter={}", meter, exception);
            metrics.countRedisFallback(meter, exception);
        }
    }

    private void warnIfNearCeiling(String meter, String window, long used, long ceiling) {
        double ratio = ceiling <= 0 ? 0 : (double) used / (double) ceiling;
        if (ratio >= properties.getWarnThresholdRatio()) {
            log.warn("Global LLM spend breaker approaching ceiling — meter={} window={} used={} ceiling={} ratio={}",
                    meter, window, used, ceiling, String.format("%.2f", ratio));
        }
    }

    private long currentCount(String redisKey) {
        RAtomicLong counter = redissonClient.getAtomicLong(redisKey);
        return counter.isExists() ? counter.get() : 0L;
    }

    private void increment(String redisKey, Duration ttl) {
        RAtomicLong counter = redissonClient.getAtomicLong(redisKey);
        long count = counter.incrementAndGet();
        if (count == 1) {
            // First hit for this period bucket — set TTL so the key self-expires instead
            // of accumulating one Redis key per (meter, period) forever.
            counter.expire(ttl);
        }
    }

    private String dailyKey(String meter) {
        return KEY_PREFIX + ":" + meter + ":" + WINDOW_DAILY + ":" + DAY_FORMAT.format(clock.instant().atZone(KST));
    }

    private String monthlyKey(String meter) {
        return KEY_PREFIX + ":" + meter + ":" + WINDOW_MONTHLY + ":" + MONTH_FORMAT.format(clock.instant().atZone(KST));
    }
}
