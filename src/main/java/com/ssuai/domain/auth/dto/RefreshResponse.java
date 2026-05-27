package com.ssuai.domain.auth.dto;

/**
 * Returned by {@code POST /api/auth/refresh}. Contains only the newly
 * issued access JWT — the rotated refresh JWT goes back as an HttpOnly
 * cookie, never in this body.
 */
public record RefreshResponse(
        String accessToken,
        long accessTtlSeconds
) {
}
