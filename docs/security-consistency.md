# 3-서비스 보안 일관화 서사 (A1) — 폴리글랏 MSA에서 보안 통제를 어떻게 통일했나

> 범위: ssuMCP(Spring Boot, Java/Kotlin) · ssuAgent(FastAPI, Python) · ssuAI(Next.js, TypeScript) 세 서비스에 동일한 보안 통제를 일관 적용한 과정과 근거.
> 목적: "보안을 **어디까지 일관되게** 적용했는가"라는 시스템-사고 면접 질문에 before/after를 코드로 답하기 위한 기록.
> 작성일: 2026-06-30. 배포 제어 색인: `security.md` §14-1.

---

## 1. 배경 — 왜 "일관화"가 다음 레벨업이었나

2026-06 보안 remediation(Wave 1~5)으로 **ssuMCP 코어는 두텁게 하드닝**됐다(rate-limit·챗 read-only·에러 비노출·CSRF·OAuth 소유권 가드·fail-closed 좌석락 등, `security.md` §14-1).

그런데 직후 여러 독립적인 보안 리뷰를 통합한 triage(2026-06-23)에서 **새로 발견된 약점이 거의 전부 "주변부"에 몰려 있었다** — 형제 서비스(ssuAgent)와 운영용 엔드포인트(`/api/admin/*`), 클라이언트(ssuAI). 즉:

> 코어에 적용한 통제가 **같은 시스템의 다른 서비스에는 안 옮겨진** 전형적 패턴.

이건 단일 서비스 보안이 아니라 **"폴리글랏(Java/Kotlin + Python + TypeScript) 마이크로서비스에서 보안 경계를 어떻게 동일하게 유지하는가"**의 문제다. 시니어가 좋아하는 시스템-사고 주제이고, 위 약점들이 전부 "코어엔 했는데 주변부엔 안 한" 형태라 **메우면 before/after를 코드로 보여줄 스토리**가 된다. → A1.

---

## 2. 제어 일관성 매트릭스 (2026-06-30 기준)

| 보안 통제 | ssuMCP (Java/Kotlin) | ssuAgent (Python) | ssuAI (TypeScript) |
|---|---|---|---|
| **인증 게이트** | MCP 2-mode 세션 인증(ADR 0036), private 도구 `mcp_session_id` 필수 | `/agent/*` API-key 강제(`X-Agent-Key`, `verify_agent_key`) — **#11 활성화** | `/admin` 로그인+오너 가드(`a1ad591`), 에이전트 same-origin 프록시가 server-only 키 주입(`c891ba6`) |
| **호출자별 소유권 바인딩** | 세션↔OAuth-sub bind-or-verify(ADR 0056), 챗 격리 `(owner, conversationId)` 복합키(#1), `confirm_action` 소유권·stale 가드(ADR 0055) | `thread_id`↔생성 `mcp_session_id` 바인딩, 교차 세션 403(**#12**, `thread_owners` 테이블, ADR 0010 / `0c8931f`) | 로그아웃 시 thread+mcp_session 완전 초기화(`272dd42`) → 서버 소유권 바인딩과 클라 상태 경계 일치 |
| **per-IP rate limit + 입력 상한** | `RateLimitFilter` 로그인 10/min·챗 30/min, `@Size` DTO 상한(ADR 0061) | slowapi per-IP `AGENT_RATE_LIMIT`(기본 30/min) + `AGENT_MAX_MESSAGE_CHARS` 상한(ADR 0009 / `0dd88c2`) | 프록시가 백엔드 제한에 위임(자체 LLM 호출 없음) |
| **내부 에러 비노출** | 개인 도구 OK 응답 canonical화, 401 본문 직렬화, 학번 `fingerprint()` 로깅 | 스트림 예외를 클라에 反映 안 함(`"처리 중 오류..."` 고정), 전체 traceback은 서버 로그만 | 프록시가 upstream 에러를 그대로 패스스루(자체 stack 비노출) |
| **CORS / 교차출처 경계** | `CsrfOriginGuardFilter` Origin/Referer allowlist(ADR 0057), SameSite=None 유지(cross-site 인증) | `ALLOWED_ORIGINS`를 prod 프론트 origin으로 핀(`*`→`https://ssuai.vercel.app`, ADR 0009) | Next 프록시로 클라→ssuAgent 직접호출 제거(same-origin화) |
| **쓰기/HITL 경계** | 챗 LLM에서 write/confirm 13종 제외 + 방어적 거부(ADR 0060). write는 HITL 경로에만 | `/agent` 그래프 interrupt→`/agent/resume` confirm(HITL) | 예약 confirm UI가 HITL interrupt를 표면화 |
| **인프라/공급망** | Actions SHA 핀·wrapper sha256·base image digest 핀·pod seccomp/no-priv-esc(ADR 0062) | 컨테이너 non-root(1001)·drop ALL·readOnlyRootFilesystem 후보 | Vercel 관리형, server-only env로 키 격리 |

> N/A·보류: ssu-ai-service(`/v1/embeddings`)는 **미배포 유휴 서비스**라 노출면 없음(#6). read-only rootfs(#2)는 **적용·prod 검증 완료**(`readOnlyRootFilesystem: true`+`/tmp` emptyDir, JNA/rusaint FFI도 read-only rootfs에서 동작 확인, ADR 0066 / `dc7df68`). k8s NetworkPolicy(#1)는 **결정 후 보류** — k3s 내장 kube-router가 FQDN egress 미지원 + SaaS 동적 IP라 CIDR allowlist 비현실적이라 Cilium CNI 교체가 진짜 해법(`241df2a`). Mistral opt-out attestation(#7)은 외부 계정 의존.

---

## 3. 핵심 원칙 — "같은 위협 → 같은 통제, 스택에 맞게 변형"

세 서비스는 언어·프레임워크가 다르지만 **위협 모델은 공유**한다(개인 학사정보, LLM 비용, 학교 시스템 연동). 그래서 통제를 "복붙"이 아니라 **동일 의도를 각 스택의 관용구로 재구현**했다:

- **Rate limit**: ssuMCP는 서블릿 `Filter`, ssuAgent는 `slowapi` 데코레이터 — **구현체는 다르지만 "per-IP fixed-window + 429"라는 계약은 동일**. 둘 다 "LLM 비용 소진/brute-force"라는 같은 위협을 막는다. (caveat도 공유: 카운터가 per-pod라 멀티포드 전환 시 shared store 필요 — #4.)
- **소유권 바인딩**: ssuMCP 챗은 `(owner, conversationId)`, ssuAgent는 `thread_id↔mcp_session_id`. **같은 "남의 대화 IDOR" 위협을, 같은 owner-key 패턴으로** 양쪽에 적용. ssuAgent #12는 ssuMCP #1을 의도적으로 미러링했다.
- **방어 심층화(defense-in-depth)**: ssuAgent가 서버에서 thread 소유권을 403으로 막아도, ssuAI가 **로그아웃 시 클라 상태까지 초기화**해야 "이전 사용자 대화 노출"과 stale 요청을 클라에서 먼저 제거한다 → 보안 경계와 UX 상태 경계를 일치시킴(사건 23).
- **키 격리(폴리글랏 특유 함정)**: 브라우저가 `/agent/*`를 직접 호출하면 API 키를 클라에 둘 수밖에 없다 → ssuAI에 **Next Route Handler 프록시**를 신설해 server-only env로 키를 주입(`c891ba6`). "서버 측 트러스트 엣지가 검증된 신원/자격을 주입한다"는 표준 MSA 패턴.

---

## 4. before / after (코드로 보여줄 수 있는 변화)

| 항목 | before | after | 근거 |
|---|---|---|---|
| ssuAgent `/agent` | 무인증·무 rate-limit·무 크기상한·CORS `*` (누구나 POST→LLM 비용소진/DoS) | API-key 강제 + per-IP 30/min + 8000자 상한 + CORS 핀 | ADR 0009 + #11 |
| ssuAgent 대화 | `thread_id` 알면 남의 대화 read/resume(IDOR) | 소유 세션만 접근, 교차 세션 **403 실측** | ADR 0010 / #12 |
| ssuAI `/admin` | 가드 없음 | 로그인 + 오너 allowlist | `a1ad591` |
| ssuAI 에이전트 키 | 클라 노출 불가피(직접 호출) | server-only 프록시 주입 | `c891ba6` |
| ssuAI 로그아웃 | thread/session 잔류(같은 탭 사용자 전환 bleed) | 완전 초기화 | `272dd42` / 사건 23 |

---

## 5. 면접 Q&A

**Q. 폴리글랏 마이크로서비스에서 보안 통제를 어떻게 일관되게 적용했나?**
위협 모델(개인정보·LLM비용·학교연동)을 서비스 공통으로 정의하고, 각 통제를 "계약(contract)"으로 추상화한 뒤 스택별 관용구로 재구현했다. 예: rate-limit은 "per-IP fixed-window + 429"라는 계약으로 고정하고 ssuMCP는 서블릿 Filter, ssuAgent는 slowapi로 구현. 핵심은 구현체 통일이 아니라 **위협→통제 매핑의 통일**이다.

**Q. 코어(ssuMCP)는 견고한데 왜 주변부에 약점이 몰렸나?**
보안 remediation을 코어 중심으로 집중했고, 형제 서비스·운영 엔드포인트는 "내부/저트래픽"이라는 암묵 가정으로 후순위였다. 통합 triage에서 이 gap을 정량 확인(새 발견의 대부분이 ssuAgent·`/admin`·클라)했고, A1로 메웠다. 교훈: **단일 서비스 하드닝은 시스템 보안이 아니다.**

**Q. ssuMCP 챗 격리와 ssuAgent thread 바인딩을 왜 같은 패턴으로 했나?**
둘 다 "호출자가 남의 대화 컨텍스트에 접근하는 IDOR"라는 동일 위협이라, ssuMCP의 `(owner, conversationId)` owner-key를 ssuAgent `thread_id↔mcp_session_id`로 그대로 미러링했다. 같은 패턴을 쓰면 리뷰·테스트·설명이 한 번의 사고로 끝나고, 한쪽에서 검증된 엣지케이스가 다른 쪽에도 적용된다.

**Q. 서버가 이미 403으로 막는데 ssuAI 로그아웃 초기화는 왜 필요했나?**
보안 경계(서버 인가)와 UX 상태 경계(클라 렌더 상태)는 다르다. 서버 403은 마지막 방어선일 뿐, 클라가 이전 사용자의 대화·pending 상태를 그대로 보여주거나 stale thread를 재전송하는 건 같은 탭 사용자 전환에서 명백한 session bleed다. 서버 강화 + 클라 reset이 함께 있어야 trust boundary가 일치한다(defense-in-depth).

**Q. ssuAgent에 키 인증을 켜기 전에 ssuAI 프록시가 선결이었던 이유는?**
브라우저가 ssuAgent를 직접 호출하던 구조라 키를 클라에 두면 노출된다. 그래서 same-origin Next 프록시(server-only 키 주입)를 먼저 배포하고(하위호환: 키 없으면 no-op), 그 다음 양쪽에 동일 키를 설정해 강제를 켰다. 순서가 바뀌면 정상 트래픽이 깨진다 — **무중단 보안 활성화의 롤아웃 순서** 설계.
