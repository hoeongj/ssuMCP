package com.ssuai.domain.academic.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves pgvector ANN works end-to-end on the real schema (V14 applied under the pgvector
 * profile, on a pgvector/pgvector image) without touching the live in-memory RAG store.
 * Seeds three orthogonal 768-d vectors, writes them via {@link PgVectorAnnIndex}, then asserts
 * the cosine-nearest neighbour of a query biased toward one chunk is exactly that chunk.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles({"test", "pgvector"})
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.task.scheduling.enabled=false"
})
class PgVectorAnnIndexIT {

    private static final int DIM = 768;
    private static final String MODEL = "model-x";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> PGVECTOR =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired
    private PgVectorAnnIndex annIndex;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void cosineNearestNeighbourReturnsTheMatchingChunk() {
        seed("chunk-a", oneHot(0));
        seed("chunk-b", oneHot(1));
        seed("chunk-c", oneHot(2));

        float[] query = new float[DIM];
        query[0] = 0.9f;
        query[1] = 0.1f;   // biased toward chunk-a's direction

        List<String> nearest = annIndex.nearestChunkHashes(MODEL, query, 2);

        assertThat(nearest).isNotEmpty();
        assertThat(nearest.get(0)).isEqualTo("chunk-a");
    }

    private void seed(String chunkHash, float[] vector) {
        jdbcTemplate.update(
                "INSERT INTO academic_embeddings (chunk_hash, model, dimensions, embedding, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                chunkHash, MODEL, vector.length, "base64-text-not-used-here", OffsetDateTime.now());
        int updated = annIndex.upsertVector(chunkHash, MODEL, vector);
        assertThat(updated).isEqualTo(1);
    }

    private static float[] oneHot(int index) {
        float[] vector = new float[DIM];
        vector[index] = 1f;
        return vector;
    }
}
