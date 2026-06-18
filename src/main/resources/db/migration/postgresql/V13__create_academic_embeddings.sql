-- Persisted academic-policy chunk embeddings, keyed by content hash + model so a
-- corpus refresh re-embeds only new or changed chunks instead of the whole corpus.
-- This removes the per-restart full re-embed that exhausted the Gemini free-tier
-- daily request quota (1000/day) and pinned policy search to lexical-only fallback.
--
-- The vector is stored as base64-encoded little-endian float32 TEXT, not pgvector:
-- cosine similarity runs in-memory over a few hundred chunks, so no ANN index is
-- needed, and prod Postgres does not ship the `vector` extension.
CREATE TABLE academic_embeddings (
    chunk_hash   VARCHAR(64)                  NOT NULL,
    model        VARCHAR(128)                 NOT NULL,
    dimensions   INT                          NOT NULL,
    embedding    TEXT                         NOT NULL,
    created_at   TIMESTAMP(6) WITH TIME ZONE  NOT NULL,
    PRIMARY KEY (chunk_hash, model)
);
