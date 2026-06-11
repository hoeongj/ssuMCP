package com.ssuai.domain.library.reservation.intent;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LibraryReservationOutboxRepository extends JpaRepository<LibraryReservationOutbox, Long> {

    long countByPublishedAtIsNull();

    @Query(value = """
            SELECT *
              FROM library_reservation_outbox
             WHERE published_at IS NULL
             ORDER BY id
             LIMIT :limit
            """, nativeQuery = true)
    List<LibraryReservationOutbox> findUnpublished(@Param("limit") int limit);
}
