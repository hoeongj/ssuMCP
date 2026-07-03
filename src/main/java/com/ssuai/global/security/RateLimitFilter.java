package com.ssuai.global.security;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 * per-IP (see {@link ClientIpResolver}) and per-pod (see {@link IpRateLimiter}
 * for the multi-replica caveat).</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** A single (path, limiter) rule. */
    record Rule(String path, IpRateLimiter limiter) {
    }

    private final List<Rule> rules;
    private final ObjectMapper objectMapper;

    RateLimitFilter(List<Rule> rules, ObjectMapper objectMapper) {
        this.rules = List.copyOf(rules);
        this.objectMapper = objectMapper;
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

        String clientIp = ClientIpResolver.resolve(request);
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

    // --- package-private factory used by the config + tests ----------------

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
                objectMapper);
    }
}
