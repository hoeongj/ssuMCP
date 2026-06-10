package com.ssuai.domain.academic.embedding;

import java.util.List;

import com.ssuai.domain.academic.dto.AcademicPolicyCorpusSnapshot;

/**
 * The corpus snapshot together with its per-chunk embeddings.
 *
 * <p>{@code embeddingActive} is false when embeddings are disabled or the embedding
 * API failed during the last refresh; in that case {@code chunks} is empty and the
 * service uses lexical-only search. This holder is the single value the cache stores,
 * so a search reads the snapshot and its embeddings atomically (no drift between the
 * two when a live refresh runs).
 */
public record EmbeddedCorpus(
        AcademicPolicyCorpusSnapshot snapshot,
        List<EmbeddedChunk> chunks,
        boolean embeddingActive) {

    public EmbeddedCorpus {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    public static EmbeddedCorpus lexicalOnly(AcademicPolicyCorpusSnapshot snapshot) {
        return new EmbeddedCorpus(snapshot, List.of(), false);
    }
}
