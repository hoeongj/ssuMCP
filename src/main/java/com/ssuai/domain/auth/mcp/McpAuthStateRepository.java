package com.ssuai.domain.auth.mcp;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface McpAuthStateRepository extends JpaRepository<McpAuthStateEntity, String> {

    Optional<McpAuthStateEntity> findByStateAndExpiresAtAfter(String state, Instant now);

    /**
     * Atomically claims an active (non-expired) state token by deleting it in a single
     * statement. Returns the number of rows deleted: {@code 1} for the caller that won the
     * race, {@code 0} for a concurrent caller that lost (the row was already gone) or when
     * the state is unknown/expired. This replaces the non-atomic find-then-delete that let
     * two concurrent OAuth callbacks both consume the same one-time state.
     */
    @Modifying
    @Query("delete from McpAuthStateEntity e where e.state = :state and e.expiresAt > :now")
    int deleteIfActive(@Param("state") String state, @Param("now") Instant now);

    int deleteByExpiresAtBefore(Instant now);
}
