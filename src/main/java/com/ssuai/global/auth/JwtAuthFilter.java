package com.ssuai.global.auth;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Parses {@code Authorization: Bearer <jwt>} on every {@code /api/*} request
 * and, on success, exposes the caller's student id and name as request
 * attributes ({@link AuthAttributes}). Controllers decide whether the
 * absence of those attributes is a 401 or fine for an anonymous endpoint.
 *
 * <p>This filter never short-circuits the chain. A missing, malformed, or
 * invalid token leaves the attributes unset and the request proceeds —
 * Spring Security is intentionally not in play here (Task 14 spec §6).
 * The trade-off is that "401 vs anonymous" lives in the controller, but
 * because all authenticated endpoints share `AuthAttributes.STUDENT_ID`
 * as the gating check, the rule is uniform.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;

    public JwtAuthFilter(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String token = extractBearer(request);
        if (token != null) {
            try {
                JwtClaims claims = jwtProvider.parse(token, JwtTokenType.ACCESS);
                request.setAttribute(AuthAttributes.STUDENT_ID, claims.studentId());
                request.setAttribute(AuthAttributes.STUDENT_NAME, claims.name());
            } catch (InvalidJwtException exception) {
                log.debug("ignored invalid access token: {}", exception.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }

    private static String extractBearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
