package com.ssuai.domain.lms.export;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface LmsExportJobRepository extends JpaRepository<LmsExportJob, String> {

    @Query(value = """
            SELECT *
              FROM lms_export_jobs
             WHERE status = 'QUEUED'
                OR (status = 'BUILDING' AND (claimed_at IS NULL OR claimed_at <= :leaseCutoff))
             ORDER BY created_at, id
             LIMIT 1
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<LmsExportJob> findClaimableForUpdate(@Param("leaseCutoff") Instant leaseCutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from LmsExportJob j where j.id = :id")
    Optional<LmsExportJob> findByIdForUpdate(@Param("id") String id);

    List<LmsExportJob> findAllByExpiresAtBefore(Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update LmsExportJob j set j.status = com.ssuai.domain.lms.export.LmsExportStatus.DOWNLOADED, "
            + "j.completedAt = :now "
            + "where j.id = :id and j.status = com.ssuai.domain.lms.export.LmsExportStatus.READY")
    int markDownloaded(@Param("id") String id, @Param("now") Instant now);
}
