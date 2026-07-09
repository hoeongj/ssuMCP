package com.ssuai.domain.library.reservation.intent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LibraryReservationOutboxRepository extends JpaRepository<LibraryReservationOutbox, Long> {

    long countByPublishedAtIsNull();

    /**
     * Retention sweep (ADR 0072): bulk-deletes rows already relayed ({@code published_at IS NOT
     * NULL} — the outbox has no status column; a non-null publish timestamp IS its terminal
     * marker) and older than the cutoff. Unpublished rows are never deleted regardless of age,
     * so the relay can always still deliver them. Returns the rows deleted.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from LibraryReservationOutbox o where o.publishedAt is not null and o.createdAt < :cutoff")
    int deletePublishedCreatedBefore(@Param("cutoff") Instant cutoff);

    @Query(value = """
            SELECT *
              FROM library_reservation_outbox o
             WHERE o.id = (
                       SELECT MIN(head.id)
                         FROM library_reservation_outbox head
                        WHERE head.published_at IS NULL
                   )
               AND (o.claimed_at IS NULL OR o.claimed_at <= :leaseCutoff)
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<LibraryReservationOutbox> findOldestUnpublishedForUpdate(
            @Param("leaseCutoff") Instant leaseCutoff);

    @Query(value = """
            SELECT *
              FROM library_reservation_outbox
             WHERE published_at IS NULL
               AND (claimed_at IS NULL OR claimed_at <= :leaseCutoff)
             ORDER BY id
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<LibraryReservationOutbox> findClaimableForUpdate(
            @Param("leaseCutoff") Instant leaseCutoff,
            @Param("limit") int limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update LibraryReservationOutbox o
               set o.publishedAt = :publishedAt,
                   o.claimedAt = null,
                   o.claimedBy = null
             where o.id in :ids
               and o.publishedAt is null
               and o.claimedBy = :claimedBy
            """)
    int markClaimedPublished(
            @Param("ids") List<Long> ids,
            @Param("claimedBy") String claimedBy,
            @Param("publishedAt") Instant publishedAt);
}
