package com.ssuai.domain.library.auth;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LibrarySessionRepository extends JpaRepository<LibrarySessionEntity, String> {

    @Modifying
    @Query("DELETE FROM LibrarySessionEntity e WHERE e.expiresAt < :now")
    void deleteExpiredBefore(@Param("now") Instant now);
}
