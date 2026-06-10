package com.ssuai.domain.academic.embedding;

import com.ssuai.domain.academic.dto.AcademicPolicySource;

/**
 * One corpus chunk plus its embedding vector.
 *
 * <p>{@code chunkIndex} is the index of this chunk within its source document as
 * produced by {@link AcademicTextChunker}, so it lines up with the lexical search
 * path and lets RRF fuse the two rankings by {@code (sourceId, chunkIndex)}.
 */
public record EmbeddedChunk(
        AcademicPolicySource source,
        int chunkIndex,
        String text,
        float[] embedding) {

    public double cosineSimilarity(float[] query) {
        if (query == null || embedding == null || query.length == 0 || embedding.length == 0) {
            return 0.0;
        }
        int length = Math.min(query.length, embedding.length);
        double dot = 0.0;
        for (int i = 0; i < length; i++) {
            dot += (double) embedding[i] * query[i];
        }
        // Vectors are stored L2-normalized, so the dot product is already cosine similarity.
        return dot;
    }
}
