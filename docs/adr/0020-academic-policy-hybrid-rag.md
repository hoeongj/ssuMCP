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

---

## 2026-06-15 Amendment — 임베딩 모델 전환: gemini-embedding-001 → gemini-embedding-2-preview

- **상태**: 배포 완료 (커밋 `35ac2ad`). `embeddingUsed:true` prod 확인 예정.

### 전환 계기

`gemini-embedding-001` 무료 티어 쿼터가 2026-06-11 CrashLoop 109회 재시도 이후 **영구 0으로 고착** (Google 공식 포럼 확인 버그 — 일별 리셋 없음, 프로젝트 교체로도 해결 불가).

### 후보 검토

| 후보 | 결과 |
|---|---|
| `gemini-embedding-001` 대기 | 불가. Google-confirmed: 오버레이지 후 무료 티어 쿼터 영구 고착. |
| `text-embedding-004` | 불가. 2026-01-14 deprecated → 이 키 티어에서 404. |
| `gemini-embedding-2` (no suffix) | 불가. OpenAI-compat endpoint에서 올바른 모델 ID 아님 → RestClientException. |
| **`gemini-embedding-2-preview`** | **선택**. 독립 쿼터 버킷, 동일 OpenAI-compat endpoint(`/v1beta/openai/embeddings`), Matryoshka 128–3072차원. |

### 왜 재임베딩이 필요 없는가

ADR 본문 "768차원 Matryoshka 접두사"는 `gemini-embedding-001` **단일 모델 내** 차원 절삭 이야기다.

**모델이 달라지면 벡터 공간 자체가 달라진다 — 차원이 같아도 호환되지 않는다.** Matryoshka는 동일 모델 내에서만 접두사가 의미를 유지함을 보장할 뿐이다.

이 전환이 투명한 실제 이유는 **아키텍처**다: `AcademicPolicyCorpusCache`는 `AtomicReference<EmbeddedCorpus>`로 corpus를 in-memory에만 유지하며 pgvector/DB에 저장하지 않는다. 재시작·갱신마다 설정된 모델로 전체 재임베딩하므로 "기존 벡터"가 존재하지 않아 마이그레이션 문제가 없다.

### 변경 파일 (커밋 `35ac2ad`)

- `deploy/charts/ssuai-backend/values.yaml` — `academicEmbeddingModel: "gemini-embedding-2-preview"`
- `src/main/resources/application.yml` — 기본값 `gemini-embedding-2-preview`

---

## 2026-06-18 Amendment — 임베딩 영속화 (in-memory → plain DB 캐시). pgvector는 재검토 후에도 기각

- **상태**: 구현·테스트 green (브랜치/커밋은 MASTERPLAN). 배포 후 부트스트랩 1회 검증 대기.

### 배경 — 본문이 택한 "in-memory 전용"의 대가가 prod에서 현실화

본 ADR은 pgvector를 기각하고 corpus 임베딩을 `AtomicReference<EmbeddedCorpus>`에 **in-memory로만** 유지했다(Consequences의 "can add contentHash-based skip-unchanged later"가 바로 이 후속). 그 대가가 실측으로 드러났다: Gemini 무료 티어 임베딩은 **하루 1000 요청**(`EmbedContentRequestsPerDayPerProjectPerModel-FreeTier`, 429 본문에서 확인) 한도인데, 216청크 corpus를 **재시작·6시간 갱신마다 전체 재임베딩**한다. main 푸시마다 pod가 롤되어 재시작이 잦고 → 1000/day 소진 → 모든 임베딩 429 → 학칙 RAG가 사실상 **항상 lexical-only**. (진단 서사는 TROUBLESHOOTING 2026-06-18.)

### 결정 — 영속화하되, pgvector가 아닌 plain 캐시

재임베딩 제거를 위해 임베딩을 DB에 영속화한다. 단 **pgvector는 재검토 후에도 기각**하고 plain TEXT 캐시를 쓴다.

| 대안 | 결과 | 이유 |
|---|---|---|
| **plain 캐시 (`academic_embeddings`, base64(float32) TEXT, 키 `(chunk_hash, model)`)** | **채택** | 재임베딩 제거 목표 달성. 확장 의존성 0, H2(MODE=PostgreSQL) `validate` 통과(기존 `payload TEXT` 선례), 인메모리 코사인 경로 그대로. |
| pgvector `vector(768)` 캐시 | 기각 | prod Postgres(외부 stock 17.10)에 `vector` 확장 **부재 실측**(`pg_available_extensions` 빈 결과) → `CREATE EXTENSION` 부팅 크래시(본 ADR 기각 사유 #1 실증). 코사인이 in-memory(216청크 ~1ms)라 인덱스 미사용 → "벡터 타입 캐시"에 불과. |
| pgvector + `<=>` 검색으로 전환 | 기각 | 위 확장 부재에 더해 RRF 융합을 DB 검색으로 재설계해야 하고, corpus 규모에선 네트워크 왕복이 인메모리보다 느림. 과투자. |

> 사용자는 포트폴리오 가치를 위해 "(a) 영속화"를 택했고(본 ADR 기각 사유 #2 "overkill"를 의도적으로 override), 구현 레벨에서 **확장 부재(기각 사유 #1, 미해소된 prereq)** 와 인메모리 코사인을 근거로 도구를 pgvector→plain으로 좁혔다.

### 작동

1. 갱신 시 각 청크 텍스트의 SHA-256으로 `(chunk_hash, model)` 키 생성(`model`=`properties.getModel()`).
2. `findByModelAndChunkHashIn`으로 영속 벡터 조회 → **캐시 히트는 스킵**(차원이 현 설정과 다르면 무시→재임베딩).
3. 미스 텍스트만 `AcademicEmbeddingClient.embed`로 임베딩 → base64 인코딩해 `saveAll`(merge=upsert).
4. 청크 순서대로 벡터 조립. 전부 해결되면 `embeddingActive=true`, 하나라도 미해결이면 lexical-only(단 이번에 성공한 미스는 이미 영속).
- **정상상태**: 문서 불변이면 전 청크 캐시 히트 → API 0 → 1000/day 무소비. 최초 1회(또는 문서 변경분)만 비용.
- **모델/차원 변경**: 키(`model`)·차원 가드로 자동 재임베딩 → 벡터공간/길이 불일치 방지.

### 텍스트 안정성 (캐시 히트의 전제)

캐시는 재추출 텍스트가 byte-identical이어야 작동한다. `RealAcademicPolicyConnector.parseText`가 `script/style/nav/footer/header/iframe` 제거 + NBSP→space + 공백압축 + trim으로 정규화하고, 규정 소스는 `SEQ_HISTORY`로 불변 과거버전 URL에 고정되므로 안정적. 드리프트가 나더라도 해당 청크만 미스(재임베딩)로 graceful 강등.

### 구현 레벨 결정 (Rule 3 granularity)

- **벡터 저장 = base64(float32 LE) TEXT** (not bytea/real[]): `ddl-auto: validate` + H2/Postgres 양쪽 이식성을 최우선. `String`+`columnDefinition=TEXT`는 기존 엔티티(payload) 선례라 validate 무위험, 라운드트립 무손실. bytea는 벤더별 타입·Hibernate 매핑 마찰, real[]는 array 매핑 마찰.
- **복합키 `@IdClass(AcademicEmbeddingId)`**: 자연키 `(chunk_hash, model)`로 조회·upsert — 대리키+유니크 제약보다 의미 명확.
- **마이그레이션 벤더분리(h2/postgresql 동일 내용)**: V10~12 최신 컨벤션 준수. 모든 타입이 H2-PostgreSQL 이식가능이라 내용 동일.
- **store = 인터페이스(`AcademicEmbeddingStore`) + `PersistentAcademicEmbeddingStore`**: Connector 패턴 idiom. 캐시는 인터페이스에 의존 → 기존 캐시 유닛테스트는 DB 없이 fake store로 유지.

### 한계 / 후속

- 1회 임베딩이 all-or-nothing이라 콜드 부트스트랩 중 일부 배치 429면 그 회차 영속 0(일일예산 1000≫216이라 리셋 후 첫 완주에서 채워짐). **배치 단위 점진 영속**은 후속 과제.
- 쿼리 임베딩은 캐시 안 함(유니크) — corpus 재임베딩 드레인을 없앤 것이지 임베딩이 공짜는 아님.
- 옛 모델 행은 잔존(무해) — 필요 시 정리 마이그레이션.
