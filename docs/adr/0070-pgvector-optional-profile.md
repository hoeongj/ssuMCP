# ADR 0070 — pgvector 옵션 프로파일 (기존 평문 임베딩 테이블 유지)

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-30 |
| 상태 | Accepted — 옵션 프로파일로 역량 증명, prod 기본 경로 불변 |
| 범위 | `db/migration/pgvector/V14`, `application-pgvector.yml`, `PgVectorAnnIndex`, `PgVectorAnnIndexIT` |
| 연관 문서 | ADR 0020(평문 임베딩 테이블 결정), ADR 0065(RAG 임베딩 복구), ADR 0068(Testcontainers) |

> 스펙 초안은 0068로 적었으나 0068은 Testcontainers+JaCoCo가 점유 → 0070으로 재배정.

---

## 배경

학칙 RAG의 임베딩은 ADR 0020에서 **평문 base64 TEXT 컬럼 + 인메모리 코사인**으로 결정했다. 근거: 코퍼스가 수백 청크(현 217)라 ANN 인덱스(pgvector HNSW)의 이점이 발현되지 않고, **prod Postgres에 `vector` 확장이 없다**. 이 결정은 웹서치상으로도 옳다(Neon/severalnines: 수백~수천 벡터에선 brute-force exact가 충분, HNSW는 수만+에서 이득).

그러나 채용공고 수요분석에서 "벡터/임베딩 5.4% + Qdrant/FAISS/pgvector 경험 우대"가 나온다. 즉 **"규모상 안 쓴 게 맞지만, 쓸 줄은 안다"를 증명**할 필요가 있다.

## 결정

**prod 기본 경로는 그대로 두고**, `pgvector` 프로파일에서만 활성화되는 ANN 경로를 추가해 **역량을 격리 증명**한다.

- **Flyway**: `db/migration/pgvector/V14`(`CREATE EXTENSION vector` + `embedding_vec vector(768)` 컬럼 + HNSW cosine 인덱스)는 **`pgvector` 프로파일의 location에만** 둔다(`application-pgvector.yml`). 기본/`postgresql`/`h2` 경로엔 없으므로 **prod 기본 마이그레이션 경로에 새 migration이 0개** → prod 무영향(확장 없는 prod에서 `CREATE EXTENSION` 실패 회피).
- **코드**: `PgVectorAnnIndex`(`@Profile("pgvector")`)가 `embedding_vec` upsert + `<=>` 코사인 ANN을 **네이티브 쿼리**로 수행. `embedding_vec`는 JPA 엔티티에 매핑하지 않음(hibernate-vector 의존 회피).
- **증명**: `PgVectorAnnIndexIT`가 **`pgvector/pgvector:pg16` Testcontainers**(SET B 베이스 재사용)로 V14 적용 + 768-d 벡터 3개 삽입 후 코사인 최근접이 기대 청크를 반환함을 단언. 라이브 RAG는 **건드리지 않는다**.

## 대안과 기각 이유

- **prod 통째 pgvector 교체** ❌ — prod에 확장이 없고, 규모상 불필요하며, 라이브 RAG(현재 정상 동작 중)를 바꾸는 회귀 위험만 크다.
- **외부 벡터DB(Pinecone/Qdrant/Weaviate)** ❌ — 인프라·비용·운영 표면 증가. 단일 노드 자가호스팅 서사와 안 맞음.
- **라이브 스토어를 `@Primary`로 스왑(pgvector 프로파일)** △ — 가능하지만, 임베딩 **생성** 컨트랙트(`AcademicEmbeddingStore.embed`)와 벡터 **검색**(ANN)은 다른 관심사라 억지로 합치면 RRF 융합부까지 손대야 한다. 이번엔 **검색측 격리 컴포넌트(`PgVectorAnnIndex`)**로 capability만 증명하고, 라이브 RRF에 ANN을 끼우는 것은 **의도적으로 후속 단계**로 둔다(회귀 위험 0).
- 인덱스: **HNSW(cosine_ops)** ✓ vs IVFFlat △ — IVFFlat은 list 수 튜닝/학습이 필요하고 증분 삽입에 약하다. HNSW는 build-once·증분 친화.

## prod엔 왜 강제하지 않았나 (핵심 면접 포인트)

규모 판단이다. 217청크에서 인메모리 exact 코사인은 단순·정확·의존성 0이고, prod에 확장 도입·이중쓰기·HNSW 인덱스 유지 비용을 더해도 검색 품질/지연 이득이 사실상 없다. "트렌디한 도구를 넣는 것"보다 **"언제 넣지 않는지를 판단하고, 필요해질 때를 위해 프로파일로 증명해 둔 것"**이 더 강한 신호다.

## 동작 방식

1. `pgvector` 프로파일 활성 → Flyway location에 `db/migration/pgvector` 추가 → V14 적용(확장+컬럼+HNSW).
2. `PgVectorAnnIndex.upsertVector`가 `(chunk_hash, model)` 행의 `embedding_vec`를 `CAST(? AS vector)`로 기록(base64 TEXT와 이중쓰기).
3. `nearestChunkHashes`가 `ORDER BY embedding_vec <=> CAST(? AS vector) LIMIT k`로 코사인 최근접 청크 해시 반환.
4. 라이브 RAG(비-pgvector)는 `PersistentAcademicEmbeddingStore` 인메모리 코사인 그대로.

## 검증

- `PgVectorAnnIndexIT`(CI Docker): pgvector 컨테이너에서 V14 적용 + ANN 최근접 정확 반환. 오프라인은 `disabledWithoutDocker`로 스킵.
- prod 기본 경로: 신규 migration 0개(pgvector location 미포함) → prod 동작·스키마 불변.

## 예상 면접 질문

1. 평문 테이블을 왜 안 바꿨고 pgvector를 왜 프로파일로만 뒀나? (규모: 수백 청크엔 exact 코사인 충분, prod 확장 부재. 역량은 프로파일+IT로 증명, 강제는 과설계)
2. HNSW vs IVFFlat, cosine ops를 고른 이유는? (증분 삽입·build-once → HNSW, 임베딩 정규화 가정상 cosine)
3. 이중쓰기(TEXT + vector) 일관성은? (같은 행 UPDATE라 원자적; 라이브는 TEXT만 쓰고 ANN 경로는 프로파일에서만 vector 사용)
