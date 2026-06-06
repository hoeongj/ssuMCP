package com.ssuai.domain.notice.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.ssuai.domain.notice.entity.NoticeIndexEntry;

@Repository
public interface NoticeIndexRepository extends JpaRepository<NoticeIndexEntry, Long> {

    Optional<NoticeIndexEntry> findByLink(String link);

    /**
     * Title keyword search with optional category filter.
     * Results are ordered by posting date descending (most recent first),
     * with null dates sorted last, then by index timestamp as tiebreaker.
     * An empty keyword matches all notices.
     */
    @Query("SELECT n FROM NoticeIndexEntry n WHERE "
            + "(:keyword = '' OR LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%'))) "
            + "AND (:category = '' OR n.category = :category) "
            + "ORDER BY n.postedDate DESC NULLS LAST, n.indexedAt DESC")
    Page<NoticeIndexEntry> search(
            @Param("keyword") String keyword,
            @Param("category") String category,
            Pageable pageable);
}
