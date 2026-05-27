package com.ssuai.global.auth;

import java.time.Instant;

public record JwtClaims(
        String studentId,
        String name,
        JwtTokenType type,
        Instant issuedAt,
        Instant expiresAt
) {
}
