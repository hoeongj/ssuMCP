package com.ssuai.domain.auth.lms;

import java.time.Instant;

record LmsSessionEntry(byte[] iv, byte[] ciphertext, Instant capturedAt, Instant expiresAt) {

    LmsSessionEntry {
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
