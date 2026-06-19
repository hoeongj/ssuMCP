# 면접 Q&A 초안

> 범위: ssuMCP · ssuAgent 아키텍처 결정의 "왜"를 설명하는 Q&A.
> 목적: 면접에서 즉답할 수 있도록 근거와 트레이드오프를 미리 정리.
> 작성일: 2026-06-19

---

## Q1. "왜 LangGraph를 Spring 안에 직접 넣지 않고 별도 서비스로 뒀나?"

### 핵심 답변

MCP 프로토콜이 tool server와 agent orchestrator를 분리하도록 설계되어 있고, 분리해야 재사용성과 기술 독립성을 얻을 수 있었기 때문입니다.

### 상세

**1) MCP 프로토콜 설계 의도**

MCP(Model Context Protocol)는 "도구를 제공하는 서버"와 "도구를 결합해 추론하는 에이전트"를 분리한다. ssuMCP를 MCP tool server로 유지하면 다른 클라이언트(Claude Desktop, ChatGPT Plugin, IDE extension)도 별도 구현 없이 같은 도구를 사용할 수 있다. 에이전트 로직이 섞이면 이 재사용성이 사라진다.

**2) Python 생태계 vs JVM 위 Python FFI**

LangGraph는 Python-native다. Spring 안에 Python 프로세스를 임베딩하거나 Jython을 쓰는 것은 rusaint JNA FFI(이미 충분히 복잡)보다 훨씬 큰 기술 부채다. LangSmith 트레이싱, LangChain 도구 생태계도 Python 환경에서 바로 작동한다.

**3) 독립 배포**

LLM 교체(Gemini → GPT), 프롬프트 튜닝, 에이전트 로직 변경이 ssuMCP 재배포 없이 가능하다. ssuMCP는 학교 시스템 직접 연동이라 배포 빈도가 ssuAgent보다 낮아야 안전하다.

**4) 실제로 분리된 효과**

- Claude Desktop이 `https://ssumcp.duckdns.org/mcp`에 직접 연결해 에이전트 없이도 모든 도구를 사용할 수 있다.
- ssuAgent는 ssuMCP 없이 mock tool list로 독립 테스트·개발 가능하다.
- ssuMCP 장애 시 ssuAgent가 "upstream 불가" 에러를 사용자에게 전달하고, 재시도 없이 graceful degradation.

### 기각된 대안

| 대안 | 기각 이유 |
|------|-----------|
| Spring 내 Python subprocess 임베딩 | JVM 프로세스 생명주기 관리 복잡, 포트/IPC 설계 추가, 컨테이너 이미지 크기 2배 |
| Jython / GraalVM Polyglot | LangGraph 의존성(numpy/torch 계열) 미지원, 유지보수 위험 |
| Spring AI 에이전트 (Java 네이티브) | LangGraph의 상태머신·체크포인터·HITL 인터럽트 패턴이 Java 생태계에 없음 |

---

## Q2. "왜 Redis Keyspace Notification을 source of truth로 쓰지 않았나?"

### 핵심 답변

Redis Keyspace Notification은 at-most-once 보장이라 메시지 유실이 가능하고, 예약 상태 변경은 사용자에게 실질적 영향(좌석 배정)이 있는 이벤트라 유실 허용 불가였습니다.

### 상세

**Redis Keyspace Notification의 한계**

Redis Pub/Sub 기반 Keyspace Notification은 fire-and-forget이다:
- subscriber가 오프라인인 순간의 이벤트는 유실
- Redis 클러스터 장애나 네트워크 단절 시 이벤트 유실
- 공식 문서에도 "this data is best-effort" 명시

**예약 시나리오에서의 위험**

"좌석 N번이 비었음" 이벤트가 유실되면 대기 중인 사용자가 알림을 못 받는다. 사용자 관점에서는 "시스템이 알려주겠다고 했는데 안 알려줬다"가 된다.

**채택한 대안: Postgres LISTEN/NOTIFY + SKIP LOCKED**

- `LibraryReservationIntent` 테이블에 INSERT/UPDATE → Postgres가 durability 보장
- SKIP LOCKED worker가 PREPARED 상태 행을 순서대로 처리
- 트랜잭션 커밋과 함께 상태 전환 → at-least-once 처리 가능

**Redis Keyspace가 적합한 곳**

"캐시 TTL 만료 알림", "세션 만료 정리" — 유실해도 다음 조회 때 재확인 가능한 곳에는 적합하다. 상태 전환의 source of truth로는 부적합하다.

### 근거 자료

- Redis 공식: [Keyspace Notifications — Key Expiry Events (best-effort)](https://redis.io/docs/latest/develop/pubsub/keyspace-notifications/)
- ADR 0022 (LibraryReservationIntentQueue)

---

## Q3. "예약 POST timeout을 왜 단순 retry하지 않았나?"

### 핵심 답변

`POST /pyxis-api/1/api/seat-charges`는 멱등하지 않습니다. 재시도하면 이중 예약(double-book) 위험이 있습니다. Timeout은 실패가 아니라 "결과 미확인"이기 때문에 재시도 대신 결과 확인 경로를 설계했습니다.

### 상세

**HTTP Timeout의 의미**

네트워크 timeout이 발생해도 Pyxis 서버 측에서는 이미 예약을 완료했을 수 있다. 이 경우 retry하면 같은 좌석을 두 번 예약하는 결과가 된다.

**설계 원칙: Read/Write 비대칭**

`PyxisResilience` 코드:

```java
// Read: idempotent → retry OK
public <T> T read(Supplier<T> call) {
    Supplier<T> guarded = Retry.decorateSupplier(readRetry, 
        CircuitBreaker.decorateSupplier(circuitBreaker, call));
    ...
}

// Write: NOT idempotent → retry FORBIDDEN
public <T> T write(Supplier<T> call) {
    Supplier<T> guarded = CircuitBreaker.decorateSupplier(circuitBreaker, call);
    // No retry wrapper
    ...
}
```

**Timeout 시 처리 흐름**

```
reserve() timeout
       │
       ├── action_audit: TIMEOUT 상태 기록
       └── worker: getCurrentCharge() (GET, idempotent)
              ├── Pyxis에 예약 있음 → SUCCESS_UPSTREAM_CONFIRMED
              └── Pyxis에 예약 없음 → FAILURE_UPSTREAM
```

GET으로 결과 확인 → 사용자에게 정확한 최종 상태 전달. "timeout이 났지만 알고 보니 예약됐다" 케이스도 처리.

### 근거

- ADR 0021 (PyxisResilience Fault Tolerance)
- k6 실험: write 경로에서 retry 없이도 single-seat 동시 100 burst에서 ghost reservation 0

---

## Q4. "MCP tool server와 agent orchestrator의 책임을 어떻게 나눴나?"

### 핵심 답변

**ssuMCP (tool server)**: "무엇을 할 수 있는가" — 원자적 도메인 도구, 학교 시스템 직접 연동, auth, resilience, audit.

**ssuAgent (orchestrator)**: "어떻게 할 것인가" — 자연어 파악, 도구 선택과 조합, HITL 인터럽트, 멀티 LLM fallback.

### 상세

**책임 분리 기준**

| 관심사 | ssuMCP | ssuAgent |
|--------|--------|----------|
| 학교 시스템 연동 | ✅ Connector 패턴 | ❌ |
| auth (SAINT/LMS/도서관 세션) | ✅ McpAuthSession | ❌ (세션 ID만 전달) |
| write action 감사 | ✅ action_audit 상태머신 | ❌ |
| Resilience (CB/retry/ratelimit) | ✅ PyxisResilience | ❌ |
| 자연어 → 도구 선택 | ❌ | ✅ LangGraph supervisor |
| HITL 인터럽트 | ❌ (confirm_action 도구 제공) | ✅ interrupt() 관리 |
| LLM 프로바이더 fallback | ✅ LlmProviderChain (채팅 모드) | ✅ llm_factory.py |
| 스트리밍 UX | ❌ | ✅ SSE stream to ssuAI |

**이 분리의 실제 이점**

1. Claude Desktop → ssuMCP 직접 연결: 에이전트 없이 모든 도구 사용 가능
2. ssuAgent는 ssuMCP가 아닌 다른 MCP 서버도 연결 가능 (미래 확장)
3. MCP 표준 준수 → 미래 클라이언트 호환성

**어느 쪽이 write action을 "확정"하는가**

`prepare_reserve_library_seat()`와 `confirm_action()`은 둘 다 ssuMCP 도구다. ssuAgent는 LLM 판단으로 `confirm_action`을 호출하지만, 실제 Pyxis 호출과 audit 로깅은 ssuMCP 내에서 발생한다. LLM이 프롬프트를 잘못 이해해도 서버 측 PREPARED 상태 확인이 최후의 게이트다.

---

## Q5. "n8n을 핵심 로직에 쓰지 않은 이유는?"

### 핵심 답변

핵심 비즈니스 로직(예약 상태 머신, auth, resilience)을 no-code 플랫폼에 넣으면 테스트·버전관리·디버깅이 어려워집니다. n8n은 "이미 완성된 서비스를 연결하는 운영 자동화"에 쓰입니다.

### 상세

**n8n을 핵심에 넣지 않은 이유**

1. **테스트 불가**: n8n workflow는 단위 테스트가 없다. Jest/JUnit으로 assertion을 쓸 수 없다.
2. **버전 관리**: workflow는 JSON export이고 diff가 어렵다. git blame, PR review가 사실상 불가능.
3. **보안 경계**: `mcp_session_id`, 도서관 자격증명 같은 민감 데이터를 n8n에 저장하면 보안 경계가 흐려진다. 이 프로젝트의 보안 정책(docs/security.md §3)은 시크릿을 환경 변수/KMS에만 보관한다.
4. **디버깅**: 프로덕션 장애 시 n8n UI에서 실행 로그를 찾는 것은 Spring 로그나 Grafana보다 훨씬 비효율적이다.

**n8n이 적합한 곳 (실제 사용 계획)**

| 사용처 | 이유 |
|--------|------|
| 새 학사일정 공지 → Discord 알림 | 단순 API 연결, 실패해도 무관 |
| ArgoCD 배포 완료 → Slack 핑 | 운영 팀 편의, 핵심 경로 아님 |
| 주간 성능 리포트 생성 → 이메일 | 정기 작업, 비즈니스 로직 없음 |

**결론**

n8n = "외부 API를 연결하는 파이프라인 접착제". 예약 상태 머신·auth 같은 도메인 로직이 들어가면 n8n이 아니라 코드로 써야 한다. 이 구분을 명확히 한 것이 이 프로젝트에서 n8n을 "데모용 운영 자동화"로만 쓰는 이유다.

---

## 보너스: 자주 나오는 추가 질문

### "Resilience4j를 Spring Cloud Circuit Breaker로 쓰지 않고 직접 쓴 이유?"

Spring Cloud Circuit Breaker는 Resilience4j 위의 추상화 레이어다. 이 프로젝트에서는:
1. `read()`와 `write()`의 retry 비대칭이 핵심 — 이를 추상화 레이어 없이 코드에 명시적으로 표현하고 싶었다.
2. `PyxisResilience.forTesting(meterRegistry)` 같은 테스트 팩토리가 직접 생성 방식에서 더 자연스럽다.
3. Spring Boot 4 호환성 확인이 직접 쓸 때 더 명확하다.

### "단일 CircuitBreaker를 read/write가 공유하는 이유?"

Pyxis는 단일 upstream (`oasis.ssu.ac.kr`). 읽기가 실패하면 쓰기도 실패할 가능성이 높다. 공유 CB가 "upstream 전체 건강"을 반영한다. read 전용/write 전용으로 나누면 실제로 upstream이 죽었는데도 write CB만 OPEN이고 read CB는 CLOSED인 불일치가 생길 수 있다.

### "action_audit 테이블을 append-only로 설계한 이유?"

상태를 in-place UPDATE하는 방식이 더 단순하지만, 감사(audit) 목적상 "각 상태 전환 시각과 내용"이 보존되어야 한다. PREPARED→EXECUTING→SUCCESS 전환을 별도 행으로 기록하면 "언제 어떤 상태였는가" 히스토리가 유지된다. 현재 구현은 단일 행 UPDATE지만, ADR 0015가 append-only 방향을 권고한다.
