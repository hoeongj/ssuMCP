package com.ssuai.global.security;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

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
 * CSRF defense-in-depth via strict {@code Origin}/{@code Referer} validation
 * (security review Wave 3).
 *
 * <h2>Why an Origin/Referer check and not token-CSRF?</h2>
 * <p>Spring's CSRF token protection is globally disabled and the auth refresh
 * cookie is intentionally {@code SameSite=None} because the Vercel frontend
 * ({@code https://ssuai.vercel.app}) calls this backend
 * ({@code ssumcp.duckdns.org}) <em>cross-site</em> — the refresh cookie must
 * ride cross-site or {@code POST /api/auth/refresh} breaks (see
 * {@code application-prod.yml} §refresh-cookie). {@code SameSite=Lax} is not an
 * option. So we defend cookie-authenticated, state-changing requests by
 * verifying that the browser-supplied {@code Origin}/{@code Referer} matches the
 * exact allowlisted frontend origin — the same origin the CORS layer pins. The
 * browser always sends {@code Origin}/{@code Referer} on cross-site
 * state-changing fetches, so a forged cross-site request from {@code evil.example}
 * is rejected while the legitimate frontend passes. This stacks on top of the
 * existing HttpOnly + Secure cookie flags and the CORS origin pin.</p>
 *
 * <h2>Decision (only for {@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE})</h2>
 * <ol>
 *   <li>{@code Origin} present → must be in the allowed set, else 403.</li>
 *   <li>else {@code Referer} present → its scheme+host(+port) origin must be in
 *       the allowed set, else 403.</li>
 *   <li>else (neither header) → ALLOW. A real CSRF attack is browser-driven and
 *       the browser always attaches {@code Origin}/{@code Referer} on cross-site
 *       state-changing fetches; non-browser/server clients legitimately omit
 *       them and must not be blocked.</li>
 * </ol>
 *
 * <h2>Scope</h2>
 * <p>Registered for {@code /api/*} only (so {@code /mcp/**} Bearer endpoints and
 * {@code /actuator/**} probes — which are not under {@code /api/}) never reach
 * this filter). The identity-provider login callbacks under
 * {@code /api/mcp/auth/**} are explicitly excluded: they receive provider
 * redirects / top-level navigations and form posts during SSO login, not
 * same-site frontend fetches, so an Origin check would break login. The u-SAINT
 * and LMS {@code sso-callback} endpoints under {@code /api/auth/saint/} and
 * {@code /api/auth/lms/} are {@code GET} and therefore method-excluded.</p>
 */
public class CsrfOriginGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CsrfOriginGuardFilter.class);

    /**
     * Exact paths that are CSRF-exempt (was a broad {@code /api/mcp/auth/}
     * prefix — a later follow-up, ADR 0063). Exact-matching prevents any future
     * write endpoint added under {@code /api/mcp/auth/} from being silently
     * exempted (a CSRF bypass).
     *
     * <ul>
     *   <li>{@code POST /api/mcp/auth/library/callback} — genuine provider SSO
     *       form-post; its {@code Origin} is the identity provider, not the
     *       frontend, so an Origin check would break login.</li>
     *   <li>{@code POST /api/mcp/auth/web-session} — Bearer-authenticated (not
     *       cookie), so CSRF does not apply; kept exempt to preserve behavior.</li>
     * </ul>
     *
     * <p>The SAINT/LMS {@code start}/{@code callback} endpoints are {@code GET}
     * and therefore already method-excluded.</p>
     */
    private static final Set<String> CSRF_EXEMPT_PATHS =
            Set.of("/api/mcp/auth/library/callback", "/api/mcp/auth/web-session");

    private static final Set<String> STATE_CHANGING_METHODS =
            Set.of("POST", "PUT", "PATCH", "DELETE");

    private final Set<String> allowedOrigins;
    private final ObjectMapper objectMapper;

    public CsrfOriginGuardFilter(Set<String> allowedOrigins, ObjectMapper objectMapper) {
        this.allowedOrigins = Set.copyOf(allowedOrigins);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only state-changing methods can be CSRF targets.
        if (!STATE_CHANGING_METHODS.contains(request.getMethod())) {
            return true;
        }
        // Exclude only the exact provider SSO form-post callback (its Origin is
        // the identity provider, not the frontend, so an Origin check would break
        // login). Exact paths, not a prefix, so a future write endpoint under
        // /api/mcp/auth/ is not silently exempted.
        String path = request.getRequestURI();
        return path != null && CSRF_EXEMPT_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !origin.isBlank()) {
            if (!allowedOrigins.contains(origin)) {
                reject(request, response, "Origin", origin);
                return;
            }
        } else {
            String referer = request.getHeader(HttpHeaders.REFERER);
            if (referer != null && !referer.isBlank()) {
                String refererOrigin = toOrigin(referer);
                if (refererOrigin == null || !allowedOrigins.contains(refererOrigin)) {
                    reject(request, response, "Referer", referer);
                    return;
                }
            }
            // else: neither Origin nor Referer → allow (non-browser client).
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Reduces a Referer URL to its {@code scheme://host[:port]} origin so it can
     * be compared against the allowed set. The port segment is omitted when the
     * URL carries no explicit port (so {@code https://ssuai.vercel.app/dashboard}
     * normalizes to {@code https://ssuai.vercel.app}, matching the configured
     * origin). Returns {@code null} for a malformed or schemeless/hostless URL.
     */
    static String toOrigin(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            int port = uri.getPort();
            String portPart = port == -1 ? "" : ":" + port;
            return scheme + "://" + host + portPart;
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            String headerName,
            String headerValue) throws IOException {
        // Do not log the raw header value at info level to avoid log injection /
        // noise; the path + which header failed is enough to diagnose.
        log.warn("CSRF guard blocked {} {} — {} not in allowed origin set",
                request.getMethod(), request.getRequestURI(), headerName);
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        ApiResponse<Void> body = ApiResponse.error(new ErrorResponse(
                "CSRF_ORIGIN_NOT_ALLOWED",
                "요청 출처(Origin/Referer)가 허용되지 않았습니다."));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
