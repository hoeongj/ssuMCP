# 채용공고 수요 → mp 구현 매핑 (면접 대비)

> 신입 백엔드/AI 공고 432건(점핏·원티드·랠릿·사람인, 텍스트 보유 408건)의 키워드 출현율을 기준으로, mp(ssuMCP·ssuAI·ssuAgent·ssu-ai-service)의 실제 구현과 1줄 면접 멘트를 매핑한다. 원문/빈도 산출물: `docs/job-research/postings-2026-06-30.jsonl`, `demand-summary-2026-06-30.json`(수집 스크립트 기반). 본 표는 "공고가 원하는 것 ↔ 내가 한 것 ↔ 근거 파일"을 한 화면에 둬 면접 즉답을 돕는다.

| JD 요구 (빈도) | mp 구현 | 면접 한 줄 | 근거 |
|---|---|---|---|
| **LLM 21.8%** | chat 10-provider 자동 페일오버 | "rate-limit/quota/에러 시 10개 OpenAI-호환 provider를 상태코드·본문 신호로 판별해 자동 전환, 모델 단위까지 폴백" | `domain/chat/service/llm/`(OpenAiCompatibleProvider·LlmProviderConfig) |
| **Agent 18.4%** | LangGraph supervisor(academic/library/lms) | "StateGraph 라우팅 + Postgres 체크포인터로 대화 지속, HITL 인터럽트로 write 승인" | ssuAgent `supervisor/graph.py` |
| **RAG 8.1%** | Hybrid RAG(lexical+embedding RRF) | "RRF 융합, 임베딩 없으면 lexical로 graceful degrade, 라이브 학칙 코퍼스(217청크) 운영" | `domain/academic/` |
| **벡터/임베딩 5.4%** | 영속 임베딩 스토어(+pgvector 옵션 프로파일) | "수백 청크엔 인메모리 코사인이 충분하다고 판단(ADR 0020), pgvector는 프로파일로 역량만 증명(ADR 0070)" | `domain/academic/embedding/` |
| **MCP(헤드라인)** | Spring AI MCP 서버 52툴, OAuth2.1 세션 | "prepare→confirm HITL, readOnly/destructive 힌트, 3-tier 세션 해소" | `domain/mcp/` |
| **모니터링 17.9%·대용량 24%** | Prometheus/Grafana + Resilience4j + k6 (+SET A OTel/Tempo/Loki 계측) | "메트릭·서킷브레이커·부하테스트 운영, 분산추적+중앙로그 계측까지 추가(샘플링으로 단일노드 비용 통제)" | `deploy/`, `load-tests/`, ADR 0069 |
| **AWS 25%·Docker 14.5%·K8s 11.8%** | k3s+Helm+ArgoCD GitOps(Oracle ARM) | "Image Updater 자동 롤아웃, cert-manager TLS, read-only rootfs" | `deploy/argocd/` |
| **CI/CD 8.6%** | GitHub Actions→ghcr(ARM64)→ArgoCD | "PR 테스트→이미지 빌드→GitOps 자동배포, 공급망 SHA-pin" | `.github/workflows/` |
| **Spring 9.1%·JPA·PG 9.6%·Redis 5.9%** | Spring Boot 4, JPA, Flyway, Redisson(L2캐시/분산락) | "Connector Mock/Real 프로파일 분리로 무네트워크 빌드, per-seat 분산락" | `domain/library/redis/` |
| **테스트 품질** | Testcontainers(PG/Redis IT) + JaCoCo 래칫 + ~1030 테스트 | "H2가 못 잡는 방언/락 회귀를 실 컨테이너로 검증, 커버리지 래칫으로 회귀 차단" | `src/test/`, ADR 0068 |
| **장애대응·트러블슈팅** | TROUBLESHOOTING.md(27건) + 서킷브레이커 + (SET A 추적) | "문제→틀린가설→실제원인→해결+면접질문까지 사건별 기록(예: jjwt/Jackson3 공급망 회귀)" | `TROUBLESHOOTING.md` |
| **이벤트/Kafka 5.4%** | Redisson topic + PG LISTEN/NOTIFY (+Outbox ADR) | "분당 수백 메시지엔 Kafka가 과설계라 판단, LISTEN/NOTIFY 유지 + Kafka로 graduate하는 트리거를 ADR로 명문화" | ADR 0071 |
| **코드리뷰·문서화 9.3%** | 70+ ADR, docs/ | "모든 결정에 배경·대안·기각사유·근거(출처)·동작을 ADR로 자산화" | `docs/adr/` |
| **보안(전 직군 공통)** | 3서비스 보안 일관화 | "같은 위협→같은 통제(인증게이트·소유권바인딩·rate-limit·에러비노출·HITL)를 ssuMCP/ssuAgent/ssuAI/ssu-ai-service에 이식" | `docs/security-consistency.md` |

> 핵심 서사: "공고가 가장 많이 요구하는 LLM·Agent·RAG·MCP는 이미 강점이고, 약했던 **관측성(추적/로그)·테스트 신뢰성(컨테이너/커버리지)·벡터DB·이벤트 설계**를 수요 빈도 순으로 보강했다. 보강은 무작정 도입이 아니라 '규모에 맞는지(pgvector·Kafka는 옵션/ADR로만)'를 판단해 결정했다."
