package com.ssuai.domain.auth.mcp;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface McpSessionRepository extends JpaRepository<McpSessionEntity, String> {

    Optional<McpSessionEntity> findBySessionIdAndExpiresAtAfter(String sessionId, Instant now);

    int deleteByExpiresAtBefore(Instant now);
}
