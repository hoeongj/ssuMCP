# 장애 시나리오 — 설계와 처리 방식

> 작성일: 2026-06-19 · 갱신: 2026-07-12(Pyxis read cap fan-out sizing 반영) · 범위: ssuMCP 구현된 resilience 패턴 기준 · 관련 ADR: 0021, 0022, 0015, 0047, 0059, 0097

이 문서는 실제로 발생하거나 발생 가능한 장애 시나리오와 각각을 어떻게 처리하는지 설명한다. 단순한 "에러 있으면 500 반환" 수준을 넘어, 각 시나리오에 설계 결정과 트레이드오프가 있다.

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
     ├── RateLimiter: read 20/s cluster · 8/s per-user, write 2/s cluster · 1/s per-user
     │   → 학교 시스템 트래픽 상한 준수 + seat-scan fan-out headroom (ADR 0097)
     ├── CircuitBreaker "pyxis":
     │     slidingWindow=20, failureThreshold=50%, minCalls=10
     │     waitDuration=30s, halfOpenProbes=3
     │   → CLOSED → (50% 실패) → OPEN(30s) → HALF_OPEN(3 probe) → CLOSED
     └── Retry (read only):
           maxAttempts=3, exponentialBackoff(200ms, ×2)
         → write: retry 금지 (멱등성 미보장)
```

**CircuitBreaker가 OPEN이면** `CallNotPermittedException` → `GlobalExceptionHandler` → HTTP 503 `CIRCUIT_OPEN`

**HALF_OPEN이면** "도서관 예약 시스템이 복구 중입니다. 잠시 후 다시 시도해 주세요."로 구분해 안내한다(`GlobalExceptionHandler`가 CB 상태를 보고 OPEN/HALF_OPEN 메시지를 분기).

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

`action_audit` 테이블의 상태 머신이 이를 막는다. `ActionStatus` enum의 lifecycle은 `PENDING → EXECUTING → SUCCESS|FAILED`이고, `PENDING`은 `EXPIRED`(TTL 경과) 또는 `SUPERSEDED`(같은 owner의 새 prepare)로도 종결된다. terminal outcome 상세(race/auth/upstream/timeout/partial)는 별도 `outcome_code` 문자열(`FAILURE_RACE`, `PARTIAL_FAILURE` 등)에 남는다.

```
prepare_reserve_library_seat()
  │
  ├─→ 같은 owner의 기존 PENDING 행 전부 SUPERSEDED로 원자 전이 (ADR 0055)
  └─→ action_audit 행 INSERT (actionId, PENDING, dry_run_preview, expires_at)

confirm_action(action_id?)
  │
  ├── claimByIdAndStudentIdAndStatus: WHERE id=action_id AND student_id=owner AND status='PENDING' FOR UPDATE
  │   → 행 없음(타 owner / 이미 EXECUTING·SUCCESS / 만료) → 거부 (다른 액션으로 폴백 안 함)
  │
  └── 행 있음
        │
        ├── UPDATE status='EXECUTING'
        ├── 예약: immediate intent 큐 → worker 실행 (audit는 worker가 SoT로 종결, ADR 0059)
        ├── 반납/이석: 직접 Pyxis 호출
        └── UPDATE status='SUCCESS' or 'FAILED'(+outcome_code)
```

**두 번째 confirm은 무조건 실패**: `SELECT FOR UPDATE`가 이미 EXECUTING/SUCCESS인 행을 PENDING으로 보지 않기 때문. **stale 액션 실행도 차단**: prepare 시 직전 PENDING이 SUPERSEDED로 전이되므로 owner당 활성 PENDING은 최대 1건(ADR 0055).

**만료 처리**: `expires_at` 경과 행은 스케줄러가 `EXPIRED` 상태로 전환. 만료된 action confirm 시도 → 마찬가지로 거부.

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

**의도적 설계:** Pyxis 서버를 최종 serializer로 두고, 정합성의 source of truth는 Postgres `SELECT … FOR UPDATE`가 잡는다. 좌석 단위 직렬화는 EPIC 4에서 출시한 Redisson 분산 락(`LibraryDistributedLockClient`)이 더한다.

**좌석락 실패는 fail-CLOSED (보안 remediation #13, ADR 0059):** 예약 worker가 좌석락을 못 잡는 3종 경우(인터럽트·런타임 예외·contention/wait 만료) 전부, 락 없이 예약을 강행하지 않고 **`returnToWaiting`으로 defer**한다(기존 backoff machinery → WAITING, 다음 tick 재시도). 이전 fail-open(락 죽으면 락 없이 진행) 동작을 뒤집었다 — DB `SELECT FOR UPDATE`가 정합성을 지키더라도, 멀티포드에서 락은 효율·정확성 모두에 기여하므로 락 없는 예약을 허용하지 않는다. defer는 audit를 종단시키지 않으므로 stranding도 없다. (주의: 아래 §5의 scheduler 리더십 락은 별개로 fail-open을 유지한다 — 중복 rollup이 무해하기 때문.)

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

## 5. Redis 미가용 (EPIC 4 — 출시 완료)

### 현재 상태

EPIC 4(Redis/Redisson)는 출시되었다 (ADR 0047, 2026-06-19). Redis는 세 가지 역할을 한다:
1. **분산 좌석 락** (Redisson `RLock`, `RedissonLibraryDistributedLockClient`) — 멀티 팟에서 같은 좌석 동시 reserve 방지
2. **좌석 L2 캐시** (`RedissonLibraryRoomSeatL2Cache`) — 열람실 좌석 현황 캐싱
3. **좌석 이벤트 pub/sub** (`RedissonLibrarySeatEventBus`) 및 예약 intent 상태 버스

### Redis 미가용 시 처리 (실제 동작)

Redis 연결이 비활성/불가일 때의 degradation은 **락의 종류에 따라 다르다**:

- **예약 좌석 락 (fail-CLOSED, 보안 remediation #13/ADR 0059)**: 좌석락을 못 잡으면 락 없이 예약하지 않고 `returnToWaiting`으로 defer해 다음 tick에 재시도한다(§3). 락 없는 예약을 허용하지 않으므로 멀티포드에서도 이중 예약이 구조적으로 차단된다. 트레이드오프는 Redis 장애 중 예약 지연이지, 잘못된 예약이 아니다.
- **scheduler 리더십 락 (fail-OPEN, 의도적)**: seat sampler / hourly rollup / partition maintenance는 락 획득 실패 시 단일 pod fallback 원칙으로 **락 없이 실행**한다. 중복 실행돼도 rollup은 idempotent upsert, partition maintenance는 `IF EXISTS`라 무해하기 때문(architecture.md §7-1). 예약 정확성과 무관해 예약 락과 다른 정책을 쓴다.
- **L2 캐시**: `LibraryRoomSeatL2Cache.noop()` 폴백 — 캐시 없이 Pyxis 실시간 조회 (느리지만 서비스 유지).
- **이벤트 버스**: pub/sub 비활성화 시 SSE 실시간 갱신만 영향, 직접 조회·예약은 계속 가능.

단일 팟 배포에서도 Redis 없이 race 시나리오(§3)는 Postgres `SELECT FOR UPDATE`로 처리되며, 멀티 팟에서는 fail-closed 좌석 락이 추가 직렬화를 제공한다.

---

## 6. 동기 confirm 타임아웃 vs 비동기 worker 성공 (이중 상태 사고)

### 무엇이 잘못되는가

예약 confirm 경로는 worker 결과를 ~8초만 동기 대기한다. 타임아웃이 나면 "실패"로 단정하기 쉽지만, worker는 계속 돌아 실제로 좌석을 잡을 수 있다. 동기 경로가 audit를 FAILED로 닫은 뒤 worker가 성공하면 — **API는 "실패" 응답인데 좌석은 예약됨, 감사 로그 영구 오류** (금전 거래급 double-state).

### 우리가 하는 일 (ADR 0059, 사건 16)

- **타임아웃은 비종단**: 동기 경로를 observe-only로 전환. 예약 경로에서 audit를 절대 쓰지 않고, 타임아웃 시 audit를 `EXECUTING`에 남긴 채 웹은 비종단 status `PROCESSING`("백그라운드 처리 중")만 반환한다.
- **worker가 audit의 단일 진실원천**: intent 종단 전이(`succeed`/`failRace`/`failAuth`/`failUpstream`)에서 intent 락과 **같은 트랜잭션** 안에 `ActionService.finalizeFromIntent`를 호출. 좌석 상태와 audit가 원자적으로 함께 커밋된다.
- **멱등**: `finalizeFromIntent`는 `EXECUTING`만 1회 완료하고, null·미존재·이미 종단·아직 PENDING이면 no-op → 동기 경로가 먼저 닫았어도 안전.
- **누락 전이 보강**: `expireWaiting`(즉시예약 만료) → audit `TIMEOUT`으로 finalize, `cancelActive`는 in-flight 즉시예약을 건너뜀(stranding 방지).

### 트레이드오프

타임아웃 audit를 EXECUTING에 남기므로 미완 EXECUTING이 잠시 누수될 수 있으나, expireWaiting/cancel 경로가 닫는다. "타임아웃=응답 상태, worker=비즈니스 진실"의 분리.

---

## 7. 이석(swap) 부분 실패 — 두 좌석 다 잃을 위험

### 무엇이 잘못되는가

이석은 "기존 좌석 반납 → 새 좌석 예약" 2단계인데 upstream에 원자 swap이 없다. 반납 성공 후 새 좌석 예약이 실패(선점/upstream 오류)하면 사용자가 **두 좌석 다 잃는다**.

### 우리가 하는 일 (ADR 0059, 보안 remediation #12)

- **보상 재획득**: 새 좌석 예약 실패 시 `compensateSwap`이 **원좌석을 재예약**해 이전 상태로 복원한다.
- **보상 성공** → 원좌석 RETAINED + "이석 실패, 기존 좌석 유지" 안내.
- **보상마저 실패** → outcome `PARTIAL_FAILURE`로 종단하고 사용자에게 명확히 알린다(조용히 삼키지 않음, security.md §6 요구).

---

## 요약 표

| 시나리오 | 감지 | 처리 | 사용자 영향 |
|----------|------|------|------------|
| Pyxis 5xx | `ConnectorUnavailableException` | Resilience4j CB | 503 + 안내 메시지 |
| Pyxis timeout | `ConnectorTimeoutException` | Resilience4j retry (read only) | 504 + 안내 메시지 |
| CB OPEN | `CallNotPermittedException` | 즉시 반환 (no upstream hit) | 503 + 재시도 안내 |
| Duplicate confirm | action_audit `SELECT FOR UPDATE` | "already executed" 거부 | 409 or 400 |
| Stale action 실행 | prepare 시 SUPERSEDED 전이 | owner당 PENDING 1건 (ADR 0055) | 오래된 액션 실행 불가 |
| Same-seat race | Pyxis 예약 실패 | action_audit outcome `FAILURE_RACE` | "좌석이 이미 예약됨" |
| Worker crash | Postgres 영속 intent | 재시작 후 `getCurrentCharge` 복구 | 지연 상태 확인 가능 |
| 좌석락 획득 실패 | 예외/인터럽트/contention | **fail-closed** → `returnToWaiting` 재시도 (ADR 0059) | 예약 지연 (이중 예약 없음) |
| confirm 타임아웃 vs worker 성공 | 동기 ~8초 대기 만료 | **observe-only** + worker SoT finalize (ADR 0059) | `PROCESSING` 응답, audit 정확 |
| Swap 부분 실패 | 반납 성공·새좌석 실패 | 원좌석 보상 재획득 / `PARTIAL_FAILURE` | 원좌석 유지 또는 명확한 실패 안내 |
| Redis 미가용 | 락/캐시/이벤트 폴백 | 예약락 fail-closed · scheduler락 fail-open · L2 noop | 실시간 갱신만 영향, 직접 예약 가능 |
