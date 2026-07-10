package com.ssuai.domain.academic.embedding;

import java.util.List;
import java.util.StringJoiner;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * pgvector-backed approximate-nearest-neighbour search over
 * {@code academic_embeddings.embedding_vec}, active only under the {@code pgvector} profile.
 *
 * <p>Prod stays on the in-memory cosine store ({@link PersistentAcademicEmbeddingStore},
 * ADR 0020) for the live RAG serving path because a few hundred chunks do not need an ANN index.
 * The {@code vector} extension IS present in prod now (pgvector/pgvector:pg17, and the prod profile
 * includes {@code pgvector}), so {@code embedding_vec} and its HNSW index exist — but this component
 * still has no production caller. It demonstrates the pgvector capability in isolation (proven by
 * {@code PgVectorAnnIndexIT}); the integration seam is documented in ADR 0070 (2026-07-09 amendment).
 *
 * <p>{@code embedding_vec} is deliberately NOT mapped on {@link AcademicEmbeddingEntity}
 * (avoids a hibernate-vector dependency); all access here is native via {@link JdbcTemplate}.
 */
@Component
@Profile("pgvector")
public class PgVectorAnnIndex {

    private final JdbcTemplate jdbcTemplate;

    public PgVectorAnnIndex(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Writes the pgvector representation for an already-persisted {@code (chunkHash, model)}
     * row (dual-write alongside the base64 TEXT column). Returns the number of rows updated.
     */
    public int upsertVector(String chunkHash, String model, float[] vector) {
        return jdbcTemplate.update(
                "UPDATE academic_embeddings SET embedding_vec = CAST(? AS vector) "
                        + "WHERE chunk_hash = ? AND model = ?",
                toVectorLiteral(vector), chunkHash, model);
    }

    /**
     * Returns the chunk hashes nearest to {@code query} by cosine distance ({@code <=>}),
     * nearest first, limited to {@code k}. Uses the HNSW index from V14.
     */
    public List<String> nearestChunkHashes(String model, float[] query, int k) {
        return jdbcTemplate.queryForList(
                "SELECT chunk_hash FROM academic_embeddings "
                        + "WHERE model = ? AND embedding_vec IS NOT NULL "
                        + "ORDER BY embedding_vec <=> CAST(? AS vector) LIMIT ?",
                String.class, model, toVectorLiteral(query), k);
    }

    /** pgvector text literal: {@code [f1,f2,...]}. */
    static String toVectorLiteral(float[] vector) {
        StringJoiner joiner = new StringJoiner(",", "[", "]");
        for (float value : vector) {
            joiner.add(Float.toString(value));
        }
        return joiner.toString();
    }
}
