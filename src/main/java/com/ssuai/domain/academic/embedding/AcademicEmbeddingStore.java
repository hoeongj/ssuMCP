package com.ssuai.domain.academic.embedding;

import java.util.List;

/**
 * Produces per-chunk embedding vectors for a corpus, transparently reusing
 * previously persisted vectors so a refresh embeds only new or changed chunks.
 *
 * <p>Mirrors the {@code embed} contract of {@link AcademicEmbeddingClient}, so the
 * corpus cache swaps one for the other with no behaviour change beyond persistence.
 */
public interface AcademicEmbeddingStore {

    /** Whether embeddings are enabled and a credential is present. */
    boolean isUsable();

    /**
     * Returns one vector per input chunk, in order. Cached chunks are served from
     * persistence; only missing chunks are embedded via the API and, on success,
     * persisted so later refreshes skip them. Returns an empty list if embeddings
     * are unusable or the missing chunks could not be embedded (e.g. quota) — the
     * caller then degrades to lexical-only search.
     */
    List<float[]> embed(List<String> chunkTexts);
}
