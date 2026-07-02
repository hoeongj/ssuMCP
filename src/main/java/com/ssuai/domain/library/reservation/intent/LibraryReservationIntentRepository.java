package com.ssuai.domain.library.reservation.intent;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LibraryReservationIntentRepository extends JpaRepository<LibraryReservationIntent, Long> {

    Optional<LibraryReservationIntent> findTopByStudentIdAndStatusInOrderByCreatedAtDesc(
            String studentId, Collection<LibraryReservationIntentStatus> statuses);

    Optional<LibraryReservationIntent> findTopByStudentIdOrderByCreatedAtDesc(String studentId);

    long countByStatusIn(Collection<LibraryReservationIntentStatus> statuses);

    @Query("""
            select count(i) > 0
              from LibraryReservationIntent i
             where i.targetSeatId = :seatId
               and i.actionAuditId is not null
               and i.status in :statuses
               and i.completedAt is not null
               and i.expiresAt > :now
            """)
    boolean existsActiveCompletedImmediateAttemptForSeat(
            @Param("seatId") Long seatId,
            @Param("statuses") Collection<LibraryReservationIntentStatus> statuses,
            @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from LibraryReservationIntent i where i.id = :id")
    Optional<LibraryReservationIntent> findByIdForUpdate(@Param("id") Long id);

    @Query(value = """
            SELECT *
              FROM library_reservation_intents
             WHERE (
                    status = 'WAITING_FOR_SEAT'
                    OR (status = 'REQUESTED' AND action_audit_id IS NOT NULL)
                   )
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

    @Query("select i from LibraryReservationIntent i where i.id = :id")
    Optional<LibraryReservationIntent> findSnapshotById(@Param("id") Long id);

    /**
     * Retention sweep (ADR 0072): bulk-deletes rows that are BOTH in a terminal status AND older
     * than the cutoff, in a single DELETE statement. Callers pass only terminal statuses
     * ({@code SUCCEEDED}/{@code FAILED_*}/{@code CANCELLED}/{@code EXPIRED}); active rows
     * (REQUESTED/WAITING_FOR_SEAT/RESERVING) are never eligible regardless of age. Returns the
     * rows deleted.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from LibraryReservationIntent i where i.status in :statuses and i.createdAt < :cutoff")
    int deleteByStatusInAndCreatedAtBefore(@Param("statuses") Collection<LibraryReservationIntentStatus> statuses,
                                           @Param("cutoff") Instant cutoff);
}
