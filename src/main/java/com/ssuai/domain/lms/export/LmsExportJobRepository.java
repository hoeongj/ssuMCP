package com.ssuai.domain.lms.export;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LmsExportJobRepository extends JpaRepository<LmsExportJob, String> {

    Optional<LmsExportJob> findFirstByStatusOrderByCreatedAtAsc(LmsExportStatus status);

    List<LmsExportJob> findAllByExpiresAtBefore(Instant now);
}
