package com.ssuai.domain.auth.mcp;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface McpSessionRepository extends JpaRepository<McpSessionEntity, String> {

    Optional<McpSessionEntity> findBySessionIdAndExpiresAtAfter(String sessionId, Instant now);

    /** Transport id fallback lookup (ADR 0036 §1B). */
    Optional<McpSessionEntity> findByTransportSessionIdAndExpiresAtAfter(String transportSessionId, Instant now);

    /** OAuth sub lookup (ADR 0036 §1C). */
    Optional<McpSessionEntity> findByOauthSubjectAndExpiresAtAfter(String oauthSubject, Instant now);

    int deleteByExpiresAtBefore(Instant now);
}
