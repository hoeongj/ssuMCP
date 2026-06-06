package com.ssuai.domain.library.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "library_sessions")
public class LibrarySessionEntity {

    @Id
    @Column(name = "session_key", length = 255)
    private String sessionKey;

    @Column(name = "iv_b64", nullable = false, length = 64)
    private String ivB64;

    @Column(name = "cipher_b64", nullable = false, columnDefinition = "TEXT")
    private String cipherB64;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected LibrarySessionEntity() {
        // JPA
    }

    public LibrarySessionEntity(
            String sessionKey,
            String ivB64,
            String cipherB64,
            Instant capturedAt,
            Instant expiresAt
    ) {
        this.sessionKey = sessionKey;
        this.ivB64 = ivB64;
        this.cipherB64 = cipherB64;
        this.capturedAt = capturedAt;
        this.expiresAt = expiresAt;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public void setSessionKey(String sessionKey) {
        this.sessionKey = sessionKey;
    }

    public String getIvB64() {
        return ivB64;
    }

    public void setIvB64(String ivB64) {
        this.ivB64 = ivB64;
    }

    public String getCipherB64() {
        return cipherB64;
    }

    public void setCipherB64(String cipherB64) {
        this.cipherB64 = cipherB64;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Instant capturedAt) {
        this.capturedAt = capturedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
