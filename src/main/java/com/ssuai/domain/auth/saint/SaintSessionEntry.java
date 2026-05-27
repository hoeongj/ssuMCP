package com.ssuai.domain.auth.saint;

import java.time.Instant;

/**
 * One encrypted-at-rest portal-cookie row inside {@link SaintSessionStore}.
 *
 * <p>{@code iv} is a per-record 12-byte AES-GCM initialization vector
 * (required: unique per encryption under the same key). {@code ciphertext}
 * carries the AES-GCM-encrypted UTF-8 bytes of the cookie header plus the
 * built-in authentication tag.
 *
 * <p>{@code capturedAt} records when the store first saw the cookies; it
 * is informational only and is **not** used for TTL decisions ({@code expiresAt}
 * is the single source of truth so a follow-up "sliding refresh" can move
 * {@code expiresAt} without touching capture metadata).
 */
record SaintSessionEntry(byte[] iv, byte[] ciphertext, Instant capturedAt, Instant expiresAt) {

    SaintSessionEntry {
        if (iv == null || iv.length == 0) {
            throw new IllegalArgumentException("iv is required");
        }
        if (ciphertext == null || ciphertext.length == 0) {
            throw new IllegalArgumentException("ciphertext is required");
        }
        if (capturedAt == null || expiresAt == null) {
            throw new IllegalArgumentException("timestamps are required");
        }
    }
}
