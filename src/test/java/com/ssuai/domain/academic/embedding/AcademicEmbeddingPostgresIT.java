package com.ssuai.domain.academic.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ssuai.support.AbstractPostgresIT;

/**
 * The {@link AcademicEmbeddingRepositoryTests} scenario re-run against a real Postgres
 * container. The V13 {@code postgresql} migration must apply cleanly and the entity's
 * composite {@code (chunk_hash, model)} key + base64 {@code TEXT} vector column must
 * round-trip on the prod dialect — the {@code TIMESTAMP(6) WITH TIME ZONE}/{@code TEXT}
 * typing and Hibernate {@code validate} that H2 silently tolerates but PG can diverge on.
 */
class AcademicEmbeddingPostgresIT extends AbstractPostgresIT {

    @Autowired
    private AcademicEmbeddingRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void persistsAndQueriesByModelAndHashOnRealPostgres() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        float[] vector = {0.11f, -0.22f, 0.33f};
        tx.executeWithoutResult(status -> repository.save(new AcademicEmbeddingEntity(
                "pg-hash-1", "model-pg", vector.length, AcademicVectorCodec.encode(vector), Instant.now())));

        List<AcademicEmbeddingEntity> found = tx.execute(status ->
                repository.findByModelAndChunkHashIn("model-pg", List.of("pg-hash-1", "absent")));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getDimensions()).isEqualTo(vector.length);
        assertThat(AcademicVectorCodec.decode(found.get(0).getEmbedding())).containsExactly(vector);
    }

    @Test
    void sameHashCoexistsAcrossModelsOnRealPostgres() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            repository.save(new AcademicEmbeddingEntity(
                    "pg-shared", "model-a", 1, AcademicVectorCodec.encode(new float[] {1f}), Instant.now()));
            repository.save(new AcademicEmbeddingEntity(
                    "pg-shared", "model-b", 1, AcademicVectorCodec.encode(new float[] {2f}), Instant.now()));
        });

        assertThat(repository.findByModelAndChunkHashIn("model-a", List.of("pg-shared"))).hasSize(1);
        assertThat(repository.findByModelAndChunkHashIn("model-b", List.of("pg-shared"))).hasSize(1);
    }
}
