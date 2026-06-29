package com.ssuai.domain.lms.export;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface LmsExportJobRepository extends JpaRepository<LmsExportJob, String> {

    Optional<LmsExportJob> findFirstByStatusOrderByCreatedAtAsc(LmsExportStatus status);

    List<LmsExportJob> findAllByExpiresAtBefore(Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update LmsExportJob j set j.status = com.ssuai.domain.lms.export.LmsExportStatus.DOWNLOADED, "
            + "j.completedAt = :now "
            + "where j.id = :id and j.status = com.ssuai.domain.lms.export.LmsExportStatus.READY")
    int markDownloaded(@Param("id") String id, @Param("now") Instant now);
}
