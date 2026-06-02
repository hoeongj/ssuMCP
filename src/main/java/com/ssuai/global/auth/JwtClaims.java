package com.ssuai.global.auth;

import java.time.Instant;

public record JwtClaims(
        String studentId,
        String name,
        JwtTokenType type,
        Instant issuedAt,
        Instant expiresAt,
        String jti
) {

    public JwtClaims(
            String studentId,
            String name,
            JwtTokenType type,
            Instant issuedAt,
            Instant expiresAt) {
        this(studentId, name, type, issuedAt, expiresAt, null);
    }
}
