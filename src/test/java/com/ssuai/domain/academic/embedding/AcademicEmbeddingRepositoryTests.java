package com.ssuai.domain.academic.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Boots the full context so Flyway applies V13 on H2 and Hibernate validates the
 * entity against the migration, then exercises the composite (chunkHash, model) key.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.task.scheduling.enabled=false"
})
class AcademicEmbeddingRepositoryTests {

    @Autowired
    private AcademicEmbeddingRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void persistsAndQueriesByModelAndHash() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        float[] vector = {0.1f, 0.2f, 0.3f};
        tx.executeWithoutResult(status -> repository.save(new AcademicEmbeddingEntity(
                "hash-1", "model-x", vector.length, AcademicVectorCodec.encode(vector), Instant.now())));

        List<AcademicEmbeddingEntity> found = tx.execute(status ->
                repository.findByModelAndChunkHashIn("model-x", List.of("hash-1", "hash-absent")));

        assertThat(found).hasSize(1);
        assertThat(AcademicVectorCodec.decode(found.get(0).getEmbedding())).containsExactly(vector);
    }

    @Test
    void sameHashCoexistsAcrossModels() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            repository.save(new AcademicEmbeddingEntity(
                    "shared", "model-a", 1, AcademicVectorCodec.encode(new float[] {1f}), Instant.now()));
            repository.save(new AcademicEmbeddingEntity(
                    "shared", "model-b", 1, AcademicVectorCodec.encode(new float[] {2f}), Instant.now()));
        });

        assertThat(repository.findByModelAndChunkHashIn("model-a", List.of("shared"))).hasSize(1);
        assertThat(repository.findByModelAndChunkHashIn("model-b", List.of("shared"))).hasSize(1);
    }
}
