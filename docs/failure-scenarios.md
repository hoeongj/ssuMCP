# 장애 시나리오 — 설계와 처리 방식

> 작성일: 2026-06-19 · 범위: ssuMCP 구현된 resilience 패턴 기준 · 관련 ADR: 0021, 0022, 0015

이 문서는 실제로 발생하거나 발생 가능한 5가지 장애 시나리오와 각각을 어떻게 처리하는지 설명한다. 단순한 "에러 있으면 500 반환" 수준을 넘어, 각 시나리오에 설계 결정과 트레이드오프가 있다.

---

## 1. Pyxis 500 / 타임아웃

### 무엇이 잘못되는가

`oasis.ssu.ac.kr` (Pyxis 학교 도서관 API)가 5xx 응답을 연속으로 돌려주거나 8초(slowCall 기준) 이상 응답하지 않을 때.

### 우리가 하는 일

```
Pyxis 요청
     │
     ▼
PyxisResilience.read() / write()
     │
     ├── Bulkhead: maxConcurrentCalls=10, maxWaitDuration=500ms
     │   → 과도한 스레드 점유 방지
     ├── RateLimiter: read 5/s · write 2/s
     │   → 학교 시스템 트래픽 상한 준수
     ├── CircuitBreaker "pyxis":
     │     slidingWindow=20, failureThreshold=50%, minCalls=10
     │     waitDuration=30s, halfOpenProbes=3
     │   → CLOSED → (50% 실패) → OPEN(30s) → HALF_OPEN(3 probe) → CLOSED
     └── Retry (read only):
           maxAttempts=3, exponentialBackoff(200ms, ×2)
         → write: retry 금지 (멱등성 미보장)
```

**CircuitBreaker가 OPEN이면** `CallNotPermittedException` → `GlobalExceptionHandler` → HTTP 503 `CIRCUIT_OPEN`

**HALF_OPEN이면** "복구 중" 메시지로 구분 (ADR 0047 진행 중)

**사용자가 보는 것:**
- 타임아웃: "외부 사이트 응답이 지연되어 도서관 정보를 가져오지 못했습니다."
- CB OPEN: "도서관 예약 시스템이 일시 차단되었습니다. 잠시 후 다시 시도해 주세요."

**Grafana 확인:** `resilience4j_circuitbreaker_state{name="pyxis"}` 패널 (RED 대시보드)

### 트레이드오프

write에서 retry를 금지한 이유: `POST /pyxis-api/1/api/seat-charges`는 멱등하지 않음. 재시도하면 이중 예약 가능. 대신 타임아웃 후 `getCurrentCharge` (GET)로 실제 상태 확인 (§3 참고).

---

## 2. 중복 confirm (Duplicate Confirm)

### 무엇이 잘못되는가

사용자 또는 LLM이 동일한 `actionId`로 `confirm_action`을 두 번 이상 호출하는 경우.

### 우리가 하는 일

`action_audit` 테이블의 상태 머신이 이를 막는다:

```
prepare_reserve_library_seat()
  │
  └─→ action_audit 행 INSERT (actionId, PREPARED, dry_run_preview, expires_at)

confirm_action(actionId)
  │
  ├── SELECT ... WHERE id=actionId AND status='PREPARED' FOR UPDATE
  │   → 행 없음(이미 EXECUTING/SUCCESS/FAILED) → "action already executed" 에러 반환
  │
  └── 행 있음
        │
        ├── UPDATE status='EXECUTING'
        ├── Pyxis reserve() 호출
        └── UPDATE status='SUCCESS' or 'FAILURE_*'
```

**두 번째 confirm은 무조건 실패**: `SELECT FOR UPDATE`가 이미 EXECUTING/SUCCESS인 행을 PREPARED로 보지 않기 때문.

**만료 처리**: `expires_at` 경과 행은 스케줄러가 `EXPIRED` 상태로 전환. 만료된 action confirm 시도 → 마찬가지로 "action not found or expired" 에러.

### 트레이드오프

`prepare_* → confirm_action` 2단계 패턴은 LLM이 확인 없이 즉시 행동하는 것을 막는 HITL(Human-In-The-Loop) gate이기도 하다. 서버에서 강제되므로 "LLM이 프롬프트를 무시하면 어쩌나" 걱정이 없다 (ADR 0015).

---

## 3. 같은 좌석 동시 경쟁 (Same-Seat Race)

### 무엇이 잘못되는가

100명이 동시에 좌석 9999번을 예약 시도하는 경우.

### 우리가 하는 일

**k6 부하 실험 결과 (2026-06-11, EPIC 1):**

| 모드 | 결과 (action_audit) |
|------|---------------------|
| 같은 좌석 ×100 동시 burst | SUCCESS 2 · FAILURE_RACE 98 · **고스트 예약 0** |
| 다른 좌석 ×100 동시 burst | SUCCESS 100 |

처리 구조:

```
confirm_action ×100 동시 요청
  │
  ├── SKIP LOCKED worker queue
  │   → 동시에 EXECUTING으로 전환 가능한 건 1개씩
  │
  └── Pyxis reserve() — Pyxis 자체가 최종 serializer
        SUCCESS → action_audit SUCCESS
        실패(좌석 이미 예약됨) → action_audit FAILURE_RACE
```

**의도적 설계:** Pyxis 서버를 source of truth로 사용. 우리 쪽에서 "선착순 1명"을 직접 정하려면 Redis 분산 락이 필요 (EPIC 4 계획). 현재는 단일 JVM에서만 안전.

---

## 4. Worker 크래시

### 무엇이 잘못되는가

JVM이 action이 `EXECUTING` 상태인 중간에 재시작되는 경우.

### 우리가 하는 일

`LibraryReservationIntent`는 Postgres에 영속된다 (인메모리가 아님):

```
JVM 재시작
  │
  └── 시작 시 EXECUTING 상태 intent 스캔
        │
        ├── getCurrentCharge() (GET) — 실제 Pyxis 상태 확인
        │
        ├── Pyxis에 예약 있음 → action_audit SUCCESS_UPSTREAM_CONFIRMED
        └── Pyxis에 예약 없음 → action_audit FAILURE_UPSTREAM
```

사용자가 나중에 조회하면 정확한 상태를 볼 수 있다. 크래시 전 Pyxis가 응답하지 않았어도 사용자가 알 수 없는 "좀비 EXECUTING" 상태가 남지 않는다.

### 트레이드오프

이 패턴이 가능한 이유: write는 retry 금지이지만, getCurrentCharge (read)는 멱등하므로 몇 번이고 재시도 가능. read/write 비대칭이 크래시 복구 로직을 단순하게 만든다.

---

## 5. Redis 미사용 / 미가용 (계획 중 — EPIC 4)

### 현재 상태

EPIC 4(Redis/Redisson)는 아직 구현되지 않았다. 현재 캐시와 세션은 인프로세스 `ConcurrentHashMap`이다.

### 계획된 처리 (EPIC 4 이후)

EPIC 4 구현 시 Redis는 두 가지 역할:
1. **분산 좌석 락** (Redisson `RLock`) — 멀티 팟에서 같은 좌석 동시 reserve 방지
2. **대기열 ZSET** — 좌석 알림 대기 우선순위

**Redis 미가용 시 계획:**
- 좌석 락: lock 획득 실패 시 `FAILURE_LOCK_UNAVAILABLE`로 graceful degradation
- 대기열: Redis 없으면 대기열 기능 비활성화 (좌석 직접 예약은 계속 가능)
- 세션/캐시: Postgres fallback (TTL 짧은 메모리 캐시보다 느리지만 서비스 유지)

현재 단일 팟 배포이므로 Redis 없이도 race 시나리오(§3)는 Postgres `SELECT FOR UPDATE`로 처리 가능.

---

## 요약 표

| 시나리오 | 감지 | 처리 | 사용자 영향 |
|----------|------|------|------------|
| Pyxis 5xx | `ConnectorUnavailableException` | Resilience4j CB | 503 + 안내 메시지 |
| Pyxis timeout | `ConnectorTimeoutException` | Resilience4j retry (read only) | 504 + 안내 메시지 |
| CB OPEN | `CallNotPermittedException` | 즉시 반환 (no upstream hit) | 503 + 재시도 안내 |
| Duplicate confirm | action_audit `SELECT FOR UPDATE` | "already executed" 에러 | 409 or 400 |
| Same-seat race | Pyxis 예약 실패 | action_audit `FAILURE_RACE` | "좌석이 이미 예약됨" |
| Worker crash | Postgres 영속 intent | 재시작 후 `getCurrentCharge` 복구 | 지연 상태 확인 가능 |
| Redis 미가용 | (EPIC 4 이후) | 기능 비활성화 + Postgres fallback | 대기 알림 없음, 직접 예약 가능 |
