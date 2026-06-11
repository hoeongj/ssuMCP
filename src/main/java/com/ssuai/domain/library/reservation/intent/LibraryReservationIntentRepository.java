package com.ssuai.domain.library.reservation.intent;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LibraryReservationIntentRepository extends JpaRepository<LibraryReservationIntent, Long> {

    Optional<LibraryReservationIntent> findTopByStudentIdAndStatusInOrderByCreatedAtDesc(
            String studentId, Collection<LibraryReservationIntentStatus> statuses);

    Optional<LibraryReservationIntent> findTopByStudentIdOrderByCreatedAtDesc(String studentId);

    long countByStatusIn(Collection<LibraryReservationIntentStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from LibraryReservationIntent i where i.id = :id")
    Optional<LibraryReservationIntent> findByIdForUpdate(@Param("id") Long id);

    @Query(value = """
            SELECT *
              FROM library_reservation_intents
             WHERE status = 'WAITING_FOR_SEAT'
               AND next_attempt_at <= :now
               AND expires_at > :now
             ORDER BY next_attempt_at, id
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<LibraryReservationIntent> findClaimableWaitingForUpdate(
            @Param("now") Instant now,
            @Param("limit") int limit);

    @Query(value = """
            SELECT *
              FROM library_reservation_intents
             WHERE status = 'RESERVING'
               AND locked_until IS NOT NULL
               AND locked_until <= :now
             ORDER BY locked_until, id
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<LibraryReservationIntent> findExpiredLeasesForUpdate(
            @Param("now") Instant now,
            @Param("limit") int limit);

    @Query(value = """
            SELECT *
              FROM library_reservation_intents
             WHERE status IN ('REQUESTED', 'WAITING_FOR_SEAT')
               AND expires_at <= :now
             ORDER BY expires_at, id
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<LibraryReservationIntent> findExpiredWaitingForUpdate(
            @Param("now") Instant now,
            @Param("limit") int limit);
}
