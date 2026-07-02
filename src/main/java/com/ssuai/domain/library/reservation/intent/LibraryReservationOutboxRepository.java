package com.ssuai.domain.library.reservation.intent;

import java.time.Instant;
import java.util.List;

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
              FROM library_reservation_outbox
             WHERE published_at IS NULL
             ORDER BY id
             LIMIT :limit
            """, nativeQuery = true)
    List<LibraryReservationOutbox> findUnpublished(@Param("limit") int limit);
}
