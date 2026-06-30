-- Optional pgvector ANN column for academic_embeddings. This migration lives ONLY under
-- the `pgvector` profile's Flyway location (application-pgvector.yml), NOT the default
-- vendor path, because prod Postgres does not ship the `vector` extension and the default
-- store (PersistentAcademicEmbeddingStore, ADR 0020) runs in-memory cosine over a few
-- hundred chunks. So prod's default migration path gains ZERO new migrations from this.
--
-- Activated only in the pgvector profile (Testcontainers proof) to demonstrate ANN search
-- without changing the live RAG path. See ADR 0070.
CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE academic_embeddings
    ADD COLUMN IF NOT EXISTS embedding_vec vector(768);

-- HNSW with cosine-distance ops: sub-linear ANN. (IVFFlat would need a trained list count;
-- HNSW is build-once and robust for an incrementally-filled table.)
CREATE INDEX IF NOT EXISTS idx_academic_embeddings_vec
    ON academic_embeddings USING hnsw (embedding_vec vector_cosine_ops);
