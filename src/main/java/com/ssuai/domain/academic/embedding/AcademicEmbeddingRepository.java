package com.ssuai.domain.academic.embedding;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AcademicEmbeddingRepository
        extends JpaRepository<AcademicEmbeddingEntity, AcademicEmbeddingId> {

    /** Loads the persisted vectors for the given chunk hashes under one model. */
    List<AcademicEmbeddingEntity> findByModelAndChunkHashIn(String model, Collection<String> chunkHashes);
}
