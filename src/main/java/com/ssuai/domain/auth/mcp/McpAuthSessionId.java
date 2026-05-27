package com.ssuai.domain.auth.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Opaque, random MCP auth session identifier (128-bit UUID v4).
 *
 * <p>The raw value is passed as a tool argument by external MCP clients and
 * used as the map key in {@link McpAuthSessionStore}. It is never derived from
 * or related to a student id, JWT, or any other user-identity token.
 *
 * <p>Log output must use {@link #fingerprint()} (8-char SHA-256 prefix) so
 * the raw id never appears in log files.
 */
public record McpAuthSessionId(String value) {

    public McpAuthSessionId {
        Objects.requireNonNull(value, "value required");
        if (value.isBlank()) {
            throw new IllegalArgumentException("McpAuthSessionId value must not be blank");
        }
    }

    /** Creates a new session id with 128 bits of SecureRandom entropy (UUID v4). */
    public static McpAuthSessionId generate() {
        return new McpAuthSessionId(UUID.randomUUID().toString());
    }

    /**
     * Returns the first 8 hex characters of the SHA-256 hash of {@link #value()}.
     * Safe to include in log messages.
     */
    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public String toString() {
        return "McpAuthSessionId[fp=" + fingerprint() + "]";
    }
}
