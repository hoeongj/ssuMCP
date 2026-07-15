# ADR 0047 - EPIC 4: per-seat Redisson 분산 락 — 중복 Pyxis 호출 방지

- **Status**: Accepted - 2026-06-19 구현
- **Date**: 2026-06-19
- **Scope**:
  - `com.ssuai.domain.library.reservation.intent.LibraryReservationWorker`
  - `com.ssuai.domain.library.redis.LibraryRedisProperties`
  - `com.ssuai.domain.library.redis.LibraryRedisMetrics`

## 2026-06-22 Amendment — 좌석 write 락 실패는 fail-closed

초기 결정의 lock-less fallback은 ADR 0059와 commit `9a0930c`에서 폐기했다. Postgres가 intent와 audit의 내구성 있는 상태를 소유하더라도, Redis 락 없이 Pyxis write를 실행하면 여러 pod가 같은 좌석을 동시에 변경할 수 있다. 따라서 현재 계약은 다음과 같다.

- 락 획득 성공 시에만 `reservationConnector.reserve(...)`를 호출한다.
- contention, Redis 예외, interrupt는 모두 upstream write를 호출하지 않고 intent를 기존 backoff 경로로 되돌린다.
- 일시적인 락 장애는 terminal failure가 아니며 다음 worker poll에서 재시도한다.
- lock release 실패는 이미 시작된 upstream 결과를 되돌릴 수 없으므로 metric과 로그를 남기고 audit 단일 진실원천 상태머신으로 수렴한다.

이 amendment가 아래 D1의 lock-less degradation, D2의 `failRace`/fallback 흐름, D4의 failing fake 기대값, D5의 `fallback` outcome, 결과 절의 availability 설명을 대체한다. 현재 구현과 보안 계약은 [ADR 0059](0059-reservation-audit-single-source-of-truth.md)와 `LibraryReservationWorker.executeWithSeatLock()`이 기준이다.

## 배경

ADR 0024(Redis/Redisson 도입)는 Redis를 세 가지 목적으로만 쓰기로 결정했다: 좌석 L2 캐시, 좌석 이벤트 pub/sub, 스케줄러 리더십 락. 당시 예약 write path에 Redis 락을 쓰는 것은 "efficiency 도구가 아니라 correctness 도구로 쓰는 것"이므로 명시적으로 기각했다.

그러나 ADR 0024 이후 현재까지 `LibraryReservationWorker.processSeatGroup()`은 Postgres SELECT FOR UPDATE SKIP LOCKED로 의도(intent)를 claim한 뒤 Redis 락 없이 Pyxis를 직접 호출한다. 이 구조에서 replicaCount ≥ 2 환경에서는:

1. Pod A가 seatId=3179 intent X를 Postgres에서 claim
2. Pod B가 같은 seatId=3179 intent Y를 독립적으로 claim
3. 두 pod 모두 `reservationConnector.reserve(seatId=3179)`를 동시에 호출
4. Pyxis가 먼저 온 요청을 처리하고 나머지를 거절 → Pod B는 `LibrarySeatNotAvailableException` 수신 → `FAILED_RACE` 기록

k6 실험 결과(MASTERPLAN line 889): 같은 좌석 100 burst에서 SUCCESS 2 / RACE 98 / ghost 0. Ghost 0은 Postgres correctness 보장이 작동하는 증거다. 그러나 RACE 98 중 대부분은 Pyxis까지 도달한 뒤 거절당하는 불필요한 upstream 호출이다.

EPIC 4의 목표: Redis 분산 락으로 중복 Pyxis 호출을 upstream 도달 전에 차단하여 RACE 실패 비용을 줄인다.

Sources:

- `ADR 0024` — Redis/Redisson 도입 결정 및 예약 write path 제외 근거
- Martin Kleppmann, "How to do distributed locking": https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html
- `MASTERPLAN.md` EPIC 4, line 1514~1571

## 결정

### D1. 락은 efficiency 도구다 — correctness는 여전히 Postgres 담당

Kleppmann의 분석대로, fencing token 없는 분산 락은 GC pause·네트워크 파티션·Redis 재시작에서 correctness를 보장할 수 없다. 따라서 per-seat Redis 락의 역할은 다음으로 제한한다:

- **목적**: 같은 seatId를 동시에 시도하는 여러 pod 중 하나만 Pyxis를 호출하게 하여 불필요한 upstream 비용을 줄인다.
- **correctness 보장**: Postgres `SELECT FOR UPDATE SKIP LOCKED` (intent claim), `hasActiveCompletedImmediateAttemptForSeat` 체크.
- **락 실패 시**: graceful degradation — WARN 로그 + `library.seat.lock{outcome=fallback}` metric 후 락 없이 Pyxis 호출 (기존 동작 유지).

### D2. 구현 위치: `LibraryReservationWorker.executeWithSeatLock()`

`processSeatGroup()`에서 winner를 선정한 뒤 `executeReservation()` 대신 `executeWithSeatLock()`을 호출한다.

```
processSeatGroup(seatId, intents)
  → winner 선정, 나머지 failRace
  → executeWithSeatLock(winner, seatId)
      → lockClient.tryAcquire("ssuai:library:seat-lock:{seatId}", seatLockWaitTime=0ms)
          ├── acquired  → executeReservation() → finally: lock.close()
          ├── empty     → failRace("Another pod holds the seat reservation lock.")
          └── exception → WARN + metric "fallback" + executeReservation() (lock 없이)
```

wait time 0ms: 락을 즉시 못 잡으면 대기하지 않고 failRace. 좌석은 Postgres SKIP LOCKED가 다음 poll tick에서 다른 pod에게 배분하므로, 대기가 필요 없다. 대기 시간을 늘리면 worker poll thread를 블록하여 throughput이 줄어든다.

lock lease: watchdog 방식 (explicit leaseTime 없음). Redisson watchdog은 lock holder JVM이 살아있는 한 자동 갱신하고, JVM crash 시 30초 기본 TTL로 자동 만료된다. Pyxis API timeout이 몇 초 이내이므로 watchdog이 안전하다. explicit leaseTime을 쓰면 Pyxis가 느릴 때 lease가 만료된 채 실행되는 위험이 있다.

### D3. 설정은 `LibraryRedisProperties`에 추가한다

```
ssuai.library.redis.seat-lock-prefix = ssuai:library:seat-lock:   (기본값)
ssuai.library.redis.seat-lock-wait-time = 0ms                     (기본값)
```

prod 환경에서 별도 env 설정 없이 기본값으로 작동한다.

### D4. 테스트는 FakeLockClient fake 전략을 쓴다

ADR 0024 D6와 동일한 이유: Windows에서 Docker 없이 `gradlew.bat test`가 GREEN이어야 한다. `LibraryReservationWorkerTests`에 `FakeLockClient`를 추가하고 3가지 시나리오를 검증한다.

| 시나리오 | FakeLockClient 모드 | 검증 내용 |
|----------|---------------------|-----------|
| 락 획득 | acquired | connector.reserve() 호출됨, lock.close() 1회, metric "acquired" |
| 락 경합 | skipped | connector.reserve() 호출 안 됨, failRace() 호출됨, metric "skipped" |
| Redis 장애 | failing | connector.reserve() 호출됨 (fallback), metric "fallback" |

### D5. 메트릭

| metric | tag | 의미 |
|--------|-----|------|
| `library.seat.lock` | `outcome=acquired\|skipped\|fallback` | per-seat 락 획득 결과 |
| `library.redis.failure` | `operation=seat_lock_acquire\|seat_lock_release` | Redis 오류 발생 |

## 기각된 대안

- **LOCKING_SEAT 상태 추가**: status 전환 추가 = cancel 체크·repository query 수정 필요. 복잡성 대비 포트폴리오 가치가 낮다. RESERVING 상태가 "Redis 락 시도 중 + Pyxis 호출 중"을 모두 커버한다.
- **RFairLock**: 좌석 단위 fairness(오래 기다린 사람 우선)는 Postgres SKIP LOCKED + intent 생성 시각 ordering이 담당한다. Redis 수준에서 fairness를 중복 구현할 이유가 없다.
- **explicit leaseTime(30s)**: 고정 lease는 Pyxis가 일시적으로 느릴 때 만료 후 다른 pod가 같은 seat를 시도하게 만들 수 있다. watchdog이 더 안전하다.
- **ZSET 대기열 순번 관리**: get_library_wait_status MCP tool에서 DB 기반 intent 순번을 이미 제공한다. Redis ZSET 중복은 EPIC 5(SSE) 설계 시 필요성을 재평가한다.

## 결과

- same-seat 경쟁에서 pod 수 × Pyxis 호출 → 최대 1회 Pyxis 호출로 감소.
- RACE 실패 원인이 "Pyxis 거절" 에서 "pod가 락을 못 잡음"으로 전환 → Pyxis upstream 부하 감소.
- Redis 장애 시 correctness 유지(Postgres fallback), availability 유지(락 없이 실행).
