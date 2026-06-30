package com.ssuai.domain.auth.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import java.time.Duration;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.auth.AuthProperties;
import com.ssuai.domain.auth.dto.MeResponse;
import com.ssuai.domain.auth.dto.RefreshResponse;
import com.ssuai.domain.user.entity.Student;
import com.ssuai.domain.user.service.StudentService;
import com.ssuai.global.auth.AuthAttributes;
import com.ssuai.global.auth.InvalidJwtException;
import com.ssuai.global.auth.JwtClaims;
import com.ssuai.global.auth.JwtProperties;
import com.ssuai.global.auth.JwtProvider;
import com.ssuai.global.auth.JwtTokenType;
import com.ssuai.global.auth.RefreshTokenDenylist;
import com.ssuai.global.exception.UnauthorizedException;
import com.ssuai.global.response.ApiResponse;

/**
 * Authenticated endpoints for the current ssuAI user. Reads the student
 * identity off the request attributes populated by
 * {@code JwtAuthFilter} for {@code /me} — Spring Security is intentionally
 * not in play (Task 14 spec §6). The refresh endpoint reads the refresh
 * JWT directly out of the HttpOnly cookie set by the SSO callback.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final StudentService studentService;
    private final JwtProvider jwtProvider;
    private final JwtProperties jwtProperties;
    private final AuthProperties authProperties;
    private final RefreshTokenDenylist refreshTokenDenylist;

    public AuthController(
            StudentService studentService,
            JwtProvider jwtProvider,
            JwtProperties jwtProperties,
            AuthProperties authProperties,
            RefreshTokenDenylist refreshTokenDenylist) {
        this.studentService = studentService;
        this.jwtProvider = jwtProvider;
        this.jwtProperties = jwtProperties;
        this.authProperties = authProperties;
        this.refreshTokenDenylist = refreshTokenDenylist;
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(HttpServletRequest request) {
        Object studentId = request.getAttribute(AuthAttributes.STUDENT_ID);
        if (!(studentId instanceof String id) || id.isBlank()) {
            throw new UnauthorizedException();
        }
        Student student = studentService.findById(id)
                .orElseThrow(UnauthorizedException::new);
        return ApiResponse.success(MeResponse.from(student));
    }

    /**
     * Reads the refresh JWT out of the HttpOnly cookie, validates + parses
     * it, issues a fresh access JWT, and rotates the refresh JWT (the
     * new refresh JWT is written back as a Set-Cookie). The access JWT
     * is returned in the body — the frontend keeps it in memory only.
     */
    @PostMapping("/refresh")
    public ApiResponse<RefreshResponse> refresh(
            HttpServletRequest request,
            HttpServletResponse response) {
        String refreshToken = readRefreshCookie(request);
        if (refreshToken == null) {
            throw new UnauthorizedException();
        }
        JwtClaims claims;
        try {
            claims = jwtProvider.parse(refreshToken, JwtTokenType.REFRESH);
        } catch (InvalidJwtException exception) {
            throw new UnauthorizedException();
        }
        // Refresh-token reuse-denylist removed (was the real login-outage cause): the rotated
        // refresh Set-Cookie does not reliably replace the old cross-site cookie (Vercel proxy),
        // and the /auth/return page can call refresh more than once, so the browser legitimately
        // re-sends a refresh token whose jti was already denied on first use -> 401 -> the user
        // is bounced back to login. A refresh token is now accepted for its full TTL (refresh
        // reuse is acceptable here). The earlier jjwt/Jackson hypothesis was a red herring.
        Student student = studentService.findById(claims.studentId())
                .orElseThrow(UnauthorizedException::new);

        String accessToken = jwtProvider.issueAccess(student);
        String rotatedRefresh = jwtProvider.issueRefresh(student);
        response.addHeader(HttpHeaders.SET_COOKIE, buildRefreshCookie(rotatedRefresh).toString());

        return ApiResponse.success(new RefreshResponse(
                accessToken,
                jwtProperties.getAccessTtl().getSeconds()));
    }

    private String readRefreshCookie(HttpServletRequest request) {
        String cookieName = authProperties.getRefreshCookie().getName();
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                String value = cookie.getValue();
                return (value == null || value.isBlank()) ? null : value;
            }
        }
        return null;
    }

    /**
     * Clears the refresh cookie by overwriting it with an empty
     * {@code Max-Age=0} cookie. If a valid refresh cookie is present, its
     * jti is also denied so a copied token cannot be reused on this JVM.
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            HttpServletRequest request,
            HttpServletResponse response) {
        denyRefreshCookieIfPresent(request);
        AuthProperties.RefreshCookie cookieConfig = authProperties.getRefreshCookie();
        ResponseCookie cleared = ResponseCookie.from(cookieConfig.getName(), "")
                .httpOnly(true)
                .secure(cookieConfig.isSecure())
                .sameSite(cookieConfig.getSameSite())
                .path(cookieConfig.getPath())
                .maxAge(Duration.ZERO)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cleared.toString());
        return ApiResponse.success(null);
    }

    private void denyRefreshCookieIfPresent(HttpServletRequest request) {
        String refreshToken = readRefreshCookie(request);
        if (refreshToken == null) {
            return;
        }
        try {
            JwtClaims claims = jwtProvider.parse(refreshToken, JwtTokenType.REFRESH);
            refreshTokenDenylist.deny(claims.jti(), claims.expiresAt());
        } catch (InvalidJwtException ignored) {
            // Logout remains idempotent; an invalid cookie is just cleared.
        }
    }

    private ResponseCookie buildRefreshCookie(String refreshToken) {
        AuthProperties.RefreshCookie cookieConfig = authProperties.getRefreshCookie();
        return ResponseCookie.from(cookieConfig.getName(), refreshToken)
                .httpOnly(true)
                .secure(cookieConfig.isSecure())
                .sameSite(cookieConfig.getSameSite())
                .path(cookieConfig.getPath())
                .maxAge(jwtProperties.getRefreshTtl())
                .build();
    }
}
