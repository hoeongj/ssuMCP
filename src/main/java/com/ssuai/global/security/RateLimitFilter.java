package com.ssuai.global.security;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ssuai.global.response.ApiResponse;
import com.ssuai.global.response.ErrorResponse;

/**
 * Per-IP request throttling on abuse-prone endpoints (security review Wave 3).
 *
 * <h2>What it protects</h2>
 * <ul>
 *   <li>{@code POST /api/library/login} — password brute-force against
 *       oasis.ssu.ac.kr, and the upstream WAF could block our shared egress IP
 *       for abnormal login volume.</li>
 *   <li>{@code POST /api/chat} — LLM cost-exhaustion (every request can fan out
 *       to a paid provider).</li>
 * </ul>
 *
 * <h2>Why a servlet filter (not {@code @RestControllerAdvice})</h2>
 * <p>Like {@link CsrfOriginGuardFilter}, this runs <em>outside</em> the Spring
 * MVC dispatcher, so an exception thrown here would never reach the
 * {@code @RestControllerAdvice}. We therefore write the {@code 429} response
 * inline, mirroring the CSRF guard: a JSON {@link ApiResponse} error envelope
 * plus a {@code Retry-After} header. The {@code RATE_LIMITED} code is built
 * inline (no new {@code ErrorCode} enum entry needed).</p>
 *
 * <h2>Limits</h2>
 * <p>Generous by design — this stops abuse, it must not lock out a normal user
 * clicking around. Defaults live in {@link RateLimitProperties}
 * ({@code ssuai.ratelimit.*}) and are tunable per environment. The limiter is
 * per-IP (see {@link ClientIpResolver}, which resolves the client from a
 * trusted-hop position in {@code X-Forwarded-For} — SCALE-ROADMAP Phase 1
 * audit A2) and, in production, Redis-shared across pods via
 * {@link SharedIpRateLimiter} with a per-pod fallback (audit A1). The plain
 * per-pod {@link IpRateLimiter} still exists as that fallback and as the
 * simple local limiter {@link #forRules} builds for tests.</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Trusted proxy hop default used by the legacy (test-only) local-limiter factory. */
    private static final int DEFAULT_TRUSTED_PROXY_COUNT = 1;

    /** A single (path, limiter) rule. */
    record Rule(String path, RateLimiterGate limiter) {
    }

    private final List<Rule> rules;
    private final ObjectMapper objectMapper;
    private final int trustedProxyCount;

    RateLimitFilter(List<Rule> rules, ObjectMapper objectMapper, int trustedProxyCount) {
        this.rules = List.copyOf(rules);
        this.objectMapper = objectMapper;
        this.trustedProxyCount = trustedProxyCount;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only POST is throttled (the protected endpoints are POST-only); skip
        // everything that doesn't match a configured rule path.
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return matchingRule(request) == null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        Rule rule = matchingRule(request);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = ClientIpResolver.resolve(request, trustedProxyCount);
        IpRateLimiter.Outcome outcome = rule.limiter().tryAcquire(clientIp);
        if (outcome.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        reject(request, response, outcome.retryAfterSeconds());
    }

    private Rule matchingRule(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return null;
        }
        for (Rule rule : rules) {
            if (path.equals(rule.path())) {
                return rule;
            }
        }
        return null;
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            long retryAfterSeconds) throws IOException {
        // Do not log the client IP at info level; the path is enough to diagnose.
        log.warn("Rate limit exceeded: {} {} — throttled", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        ApiResponse<Void> body = ApiResponse.error(new ErrorResponse(
                "RATE_LIMITED",
                "요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요."));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    // --- package-private factories used by the config + tests --------------

    /**
     * Builds a filter backed purely by per-pod {@link IpRateLimiter}s (no
     * Redis). Used by unit tests that exercise filter/path-matching behavior
     * without needing a Redisson client, and equivalent to the old (pre-A1)
     * behavior at replica=1.
     */
    static RateLimitFilter forRules(
            int loginLimit,
            int chatLimit,
            int confirmLimit,
            int refreshLimit,
            Duration window,
            ObjectMapper objectMapper) {
        return new RateLimitFilter(List.of(
                new Rule("/api/library/login", new IpRateLimiter(loginLimit, window)),
                new Rule("/api/chat", new IpRateLimiter(chatLimit, window)),
                new Rule("/api/library/reservations/confirm", new IpRateLimiter(confirmLimit, window)),
                new Rule("/api/auth/refresh", new IpRateLimiter(refreshLimit, window))),
                objectMapper, DEFAULT_TRUSTED_PROXY_COUNT);
    }

    /**
     * Builds the production filter: each rule is backed by a
     * {@link SharedIpRateLimiter} (Redis-shared across pods with a per-pod
     * fallback — SCALE-ROADMAP Phase 1 audit A1). {@code redissonClient} may
     * be {@code null} (feature disabled via {@link RateLimitProperties#isRedisEnabled()}
     * or no Redisson bean available) — {@link SharedIpRateLimiter} treats that
     * identically to a Redis outage and runs purely per-pod.
     */
    static RateLimitFilter forSharedRules(
            RateLimitProperties properties,
            RedissonClient redissonClient,
            RateLimitRedisMetrics redisMetrics,
            ObjectMapper objectMapper) {
        Duration window = properties.getWindow();
        return new RateLimitFilter(List.of(
                new Rule("/api/library/login",
                        new SharedIpRateLimiter(redissonClient, "login", properties.getLoginPerMinute(), window, redisMetrics)),
                new Rule("/api/chat",
                        new SharedIpRateLimiter(redissonClient, "chat", properties.getChatPerMinute(), window, redisMetrics)),
                new Rule("/api/library/reservations/confirm",
                        new SharedIpRateLimiter(redissonClient, "confirm", properties.getConfirmPerMinute(), window, redisMetrics)),
                new Rule("/api/auth/refresh",
                        new SharedIpRateLimiter(redissonClient, "refresh", properties.getRefreshPerMinute(), window, redisMetrics))),
                objectMapper, properties.getTrustedProxyCount());
    }
}
