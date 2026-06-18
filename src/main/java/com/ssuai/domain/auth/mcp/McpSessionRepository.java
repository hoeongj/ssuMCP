package com.ssuai.domain.auth.mcp;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface McpSessionRepository extends JpaRepository<McpSessionEntity, String> {

    Optional<McpSessionEntity> findBySessionIdAndExpiresAtAfter(String sessionId, Instant now);

    /** Transport id fallback lookup (ADR 0036 §1B). */
    Optional<McpSessionEntity> findFirstByTransportSessionIdAndExpiresAtAfterOrderByCreatedAtDesc(
            String transportSessionId,
            Instant now
    );

    /** OAuth sub lookup (ADR 0036 §1C). */
    Optional<McpSessionEntity> findFirstByOauthSubjectAndExpiresAtAfterOrderByCreatedAtDesc(
            String oauthSubject,
            Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update McpSessionEntity e
            set e.transportSessionId = null
            where e.transportSessionId = :transportId
              and e.sessionId <> :keepSessionId
            """)
    int releaseTransportSessionIdFromOtherSessions(
            @Param("transportId") String transportId,
            @Param("keepSessionId") String keepSessionId
    );

    int deleteByExpiresAtBefore(Instant now);
}
