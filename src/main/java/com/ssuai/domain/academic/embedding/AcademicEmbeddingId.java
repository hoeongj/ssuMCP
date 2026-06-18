package com.ssuai.domain.academic.embedding;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for {@link AcademicEmbeddingEntity}: one vector per
 * {@code (chunkHash, model)} pair. Field names must match the entity's {@code @Id}
 * fields for JPA's {@code @IdClass} mapping.
 */
public class AcademicEmbeddingId implements Serializable {

    private String chunkHash;
    private String model;

    protected AcademicEmbeddingId() {
        // JPA
    }

    public AcademicEmbeddingId(String chunkHash, String model) {
        this.chunkHash = chunkHash;
        this.model = model;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AcademicEmbeddingId that)) {
            return false;
        }
        return Objects.equals(chunkHash, that.chunkHash) && Objects.equals(model, that.model);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chunkHash, model);
    }
}
