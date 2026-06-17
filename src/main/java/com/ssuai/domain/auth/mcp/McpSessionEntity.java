package com.ssuai.domain.auth.mcp;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "mcp_sessions")
public class McpSessionEntity {

    @Id
    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "providers", nullable = false, columnDefinition = "TEXT")
    private String providers = "{}";

    /**
     * HTTP-layer transport session id ({@code Mcp-Session-Id} header).
     * Bound on {@code start_auth}; null for sessions created before ADR 0036.
     * Used as a fallback lookup key when the LLM loses the opaque
     * {@code mcp_session_id} across turns (e.g. ChatGPT turn-boundary drop).
     */
    @Column(name = "transport_session_id", length = 128)
    private String transportSessionId;

    /**
     * OAuth {@code sub} claim from a verified Bearer JWT (opt-in mode).
     * Bound on the first authenticated request carrying a valid JWT.
     * Null for sessions in classic (non-OAuth) mode.
     */
    @Column(name = "oauth_subject", length = 255)
    private String oauthSubject;

    protected McpSessionEntity() {
        // JPA
    }

    public McpSessionEntity(String sessionId, Instant createdAt, Instant expiresAt, String providers) {
        this.sessionId = sessionId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.providers = providers == null || providers.isBlank() ? "{}" : providers;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getProviders() {
        return providers;
    }

    public void setProviders(String providers) {
        this.providers = providers == null || providers.isBlank() ? "{}" : providers;
    }

    public String getTransportSessionId() {
        return transportSessionId;
    }

    public void setTransportSessionId(String transportSessionId) {
        this.transportSessionId = transportSessionId;
    }

    public String getOauthSubject() {
        return oauthSubject;
    }

    public void setOauthSubject(String oauthSubject) {
        this.oauthSubject = oauthSubject;
    }
}
