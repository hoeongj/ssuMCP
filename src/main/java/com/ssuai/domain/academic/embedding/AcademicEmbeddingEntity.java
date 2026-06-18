package com.ssuai.domain.academic.embedding;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * A persisted chunk embedding. The {@code (chunk_hash, model)} primary key lets a
 * corpus refresh look up already-embedded chunks and skip them, so only new or
 * changed chunks cost an embedding API call. A model change yields a different key,
 * forcing a re-embed under the new model (vector spaces are not cross-compatible
 * even at equal dimensions).
 *
 * <p>The vector is stored as base64-encoded little-endian float32 in a TEXT column
 * (see {@link AcademicVectorCodec}): cosine similarity runs in-memory over a few
 * hundred chunks, so no vector index (pgvector) is needed — and prod Postgres does
 * not ship the extension.
 */
@Entity
@Table(name = "academic_embeddings")
@IdClass(AcademicEmbeddingId.class)
public class AcademicEmbeddingEntity {

    @Id
    @Column(name = "chunk_hash", length = 64, nullable = false)
    private String chunkHash;

    @Id
    @Column(name = "model", length = 128, nullable = false)
    private String model;

    @Column(name = "dimensions", nullable = false)
    private int dimensions;

    @Column(name = "embedding", nullable = false, columnDefinition = "TEXT")
    private String embedding;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AcademicEmbeddingEntity() {
        // JPA
    }

    public AcademicEmbeddingEntity(String chunkHash, String model, int dimensions, String embedding, Instant createdAt) {
        this.chunkHash = chunkHash;
        this.model = model;
        this.dimensions = dimensions;
        this.embedding = embedding;
        this.createdAt = createdAt;
    }

    public String getChunkHash() {
        return chunkHash;
    }

    public String getModel() {
        return model;
    }

    public int getDimensions() {
        return dimensions;
    }

    public String getEmbedding() {
        return embedding;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
