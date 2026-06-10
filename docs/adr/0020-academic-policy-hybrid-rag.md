# ADR 0020 — Academic-policy hybrid RAG (lexical + embedding, RRF fusion)

- **Status**: Accepted — implemented 2026-06-10. Ships **OFF by default**;
  enabled per-environment by `SSUAI_ACADEMIC_EMBEDDING_ENABLED=true`.
- **Date**: 2026-06-10
- **Scope**:
  - `com.ssuai.domain.academic.embedding` (new package),
  - `AcademicPolicyService` search ranking,
  - `AcademicPolicyCorpusCache` refresh/enrichment,
  - `docs/mcp-tools.md` (`search_academic_policy_sources` response fields).

## Context

`search_academic_policy_sources` / `get_academic_policy_brief` /
`check_scholarship_policy` retrieve evidence from the official-source policy
corpus (`rule.ssu.ac.kr`, `ssu.ac.kr`), refreshed in-memory (ADR-less feature
shipped via PR #25/#26).

The original ranking was **pure lexical**: it tokenizes the query and counts how
many times each token literally appears in each 700-char chunk (`indexOf`
counting — not even BM25/IDF). This is strong for exact matches (학점 numbers,
proper nouns like 백마성적우수장학금) but blind to **semantic paraphrase**: a
question worded "졸업하려면 점수가 얼마나 필요해?" shares no literal token with a
document that says "학사과정 이수 단위는 총 130" and scores zero.

We want to add semantic retrieval (embeddings) **without**:
- introducing a vector DB / pgvector (prod Postgres lives outside this GitOps
  repo — a `CREATE EXTENSION vector` migration would crash prod; see roadmap),
- self-hosting an embedding model (the Oracle ARM node has no GPU; running a
  local model is disproportionate overhead),
- regressing the existing lexical strengths or blocking the feature when the
  embedding API is unavailable.

## Decision

A **hybrid** retriever: keep lexical scoring, add embedding cosine similarity,
and fuse the two rankings.

### Alternatives considered

| Option | Verdict | Why |
|---|---|---|
| pgvector + persisted vectors | rejected | prod Postgres outside GitOps repo → migration risk; corpus is tiny so a vector DB is overkill |
| Self-host BGE-M3 on the node | rejected | no GPU; CPU serving is slow + memory-heavy on a 24 GB shared node; disproportionate |
| **Gemini API embeddings, in-memory cosine** | **chosen** | corpus is a few hundred chunks → in-memory cosine is ~1 ms; reuses the Gemini key we already have; $0 at this scale |
| Spring AI EmbeddingModel starter | rejected | Vertex starter needs a GCP **service account**; we hold an AI-Studio **API key**. Mixing a framework abstraction in for one feature splits the integration story — chat is hand-rolled over OpenAI-compatible REST |
| Weighted score fusion (normalize + 0.5·lex + 0.5·vec) | rejected | requires normalizing two incomparable score scales; brittle |
| **RRF (Reciprocal Rank Fusion), k=60** | **chosen** | works on rank positions, not raw scores → no normalization; industry default in Elastic/Azure AI Search/Mongo Atlas/Weaviate |

### Spec

- **Embedding call**: `AcademicEmbeddingClient` calls Gemini's OpenAI-compatible
  `/v1beta/openai/embeddings`, reusing the same REST approach as
  `OpenAiCompatibleProvider` (one provider-agnostic integration story, one key).
- **Dimensions = 768**: gemini-embedding-001 uses Matryoshka Representation
  Learning, so the 768-prefix of the 3072 vector stays meaningful. We truncate
  client-side **and re-normalize to unit length**, guaranteeing a fixed 768-d
  regardless of whether the OpenAI-compat layer honours the `dimensions` field.
  In-memory cosine and memory footprint drop ~4× vs 3072.
- **Fusion = RRF, k=60**: each candidate scores `Σ 1/(k + rank)` over the lexical
  and vector ranked lists, keyed by `(sourceId, chunkIndex)`. Both paths chunk via
  the shared `AcademicTextChunker` so the indices line up.
- **Corpus enrichment**: `AcademicPolicyCorpusCache` embeds all chunks once per
  refresh and stores an `EmbeddedCorpus` (snapshot + per-chunk vectors) in a single
  `AtomicReference`, so a search reads snapshot and vectors atomically.
- **Response metadata**: `embeddingUsed` and `fusionMethod` ("rrf" | "lexical")
  are returned so the result never hides whether semantic ranking was applied.

### Failure behaviour (degrade, never block)

Embeddings are **OFF by default**. When disabled, no API key, the corpus
embedding call fails, or the per-query embedding fails, search silently falls
back to **lexical-only** (the prior behaviour). The feature can never take the
policy tools down.

## Why this is a good portfolio story

This is the textbook **startup pattern**: no money for GPUs, so use a commercial
embedding API — and control the three risks that come with an external dependency:

1. **Cost/memory** — Matryoshka truncation to 768-d.
2. **Network failure** — automatic fallback to lexical via the reused REST path.
3. **Quality** — don't trust a single retriever; fuse lexical + vector with RRF,
   the same algorithm commercial search engines use.

Interview framing: *"On a GPU-less ARM node, self-hosting an embedding model is
the wrong call, so I used Gemini's API as a deliberate trade-off and engineered
around the three failure modes of an external dependency."*

## Consequences

- Enabling in prod costs Gemini embedding tokens per corpus refresh (small corpus,
  6 h interval) + one embedding per query. Acceptable; can add contentHash-based
  skip-unchanged later.
- Future: if the corpus grows large, revisit pgvector for persistence + ANN.
