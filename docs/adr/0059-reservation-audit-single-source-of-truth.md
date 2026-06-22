# ADR 0059 — 예약 audit 단일 진실원천: 타임아웃≠실패 + fail-closed 좌석락 + swap 보상

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-22 |
| 상태 | Accepted — 구현·배포(`3b6614c` #116, `6d3886f` #125) |
| 범위 | `ActionService.finalizeFromIntent` / `LibraryReservationIntentTransactions` / `LibraryReservationWorker` / `ConfirmActionMcpTool` / `LibraryReservationWebController` |
| 연관 ADR | [0015](0015-action-tool-infrastructure.md)(action 감사), [0022](0022-library-reservation-intent-queue.md)(intent 큐), [0047](0047-epic4-seat-lock.md)(분산 락) |
| 연관 사건 | TROUBLESHOOTING 사건 16 |

---

## 배경 — 무슨 문제

좌석 예약(intent 큐 경로)에서 종단 감사 상태(`action_audit`)를 쓰는 주체가 **둘**이었다:

1. 동기 confirm 경로(`ConfirmActionMcpTool`/`LibraryReservationWebController`)가 worker 결과를 ~8초 동기 대기 후, 타임아웃이면 audit를 종단(FAILED/TIMEOUT)으로 마킹.
2. 비동기 예약 worker가 intent를 종단시킴.

둘 사이 동기화가 없어, 동기 경로가 먼저 FAILED로 닫은 뒤 worker가 실제로 좌석을 잡아 성공해도 audit는 FAILED로 굳었다 → **API는 "실패/타임아웃" 응답인데 좌석은 실제 예약됨, 감사 로그 영구 오류** = 금전 거래급 이중 상태(double-state) 사고. 틀린 가설은 "동기 대기 타임아웃 = 비즈니스 실패"였다(실제로 타임아웃은 응답 상태일 뿐).

추가로 Wave5에서 두 인접 결함이 드러났다:
- **좌석락 fail-open(#13)**: worker가 Redisson 좌석락 획득에 실패(예외·인터럽트·contention)하면 락 없이 예약을 진행 → 멀티포드·경쟁에서 이중 예약 위험.
- **swap 비원자(#12)**: 이석은 "기존 좌석 반납 → 새 좌석 예약" 2단계인데, upstream에 원자 swap이 없어 반납 성공 후 새 좌석 예약이 실패하면 사용자가 **두 좌석 다 잃는다**.

## 결정

### 1. worker가 audit의 단일 진실원천 (SoT), 타임아웃은 비종단

- 동기 confirm/web 경로를 **observe-only**로 전환 — 예약 경로에서 audit를 절대 쓰지 않는다. 타임아웃 시 audit를 `EXECUTING`(진행 중)에 남긴 채 웹은 신규 비종단 status `PROCESSING`("백그라운드 처리 중")만 반환한다.
- `LibraryReservationIntentTransactions`의 모든 종단 전이(`succeed`/`failRace`/`failAuth`/`failUpstream`)에서 intent 락과 **같은 트랜잭션** 안에 `ActionService.finalizeFromIntent(actionAuditId, outcome, msg)`를 호출한다. intent 종단 write와 audit 완료가 원자적으로 커밋되어 좌석 상태와 audit가 절대 어긋날 수 없다.
- `finalizeFromIntent`는 **멱등**: `actionAuditId==null`(대기 intent)·row 없음·이미 종단(SUCCESS/FAILED/EXPIRED/SUPERSEDED)·아직 PENDING이면 no-op이고, 오직 `EXECUTING`만 1회 완료. 두 번째 finalize·동기 경로가 먼저 닫은 경우 모두 안전.
- 누락 전이 2종 보강(이번 변경이 만들 회귀까지 선제 차단): ① `expireWaiting`가 REQUESTED 즉시예약 intent를 EXPIRED시키면 연결 audit를 `TIMEOUT`으로 finalize(안 하면 EXECUTING 영구 잔류) ② `cancelActive`(대기열 취소 엔드포인트)는 즉시예약 in-flight intent를 건너뜀(CANCELLED로 종단시키면 audit stranded). cancel/swap의 기존 동기 `completeAction` 흐름은 변경 없음.

### 2. 좌석락 fail-CLOSED + 기존 backoff requeue

worker의 좌석락 획득 실패 3종(인터럽트·런타임 예외·contention/wait 만료) 전부 **종단 실패가 아니라 `returnToWaiting`(기존 attemptCount/nextAttemptAt backoff → WAITING)으로 defer**한다. 락 없이는 절대 예약하지 않는다 → 다음 tick에 같은 좌석을 재타깃해 락이 풀리면 성공하거나 upstream "taken"으로 `FAILURE_RACE` 종단. audit stranding 없음(reserve 미호출).

### 3. swap 보상 + PARTIAL_FAILURE

이석에서 기존 좌석 반납 성공 후 새 좌석 예약이 실패(선점/upstream 오류)하면, **원좌석을 재획득(compensate)**해 사용자 이전 상태를 복원한다. 보상 성공 → 원좌석 RETAINED + swap 실패 안내. 보상마저 실패 → 새 outcome `PARTIAL_FAILURE`로 종단하고 사용자에게 명확히 알린다(조용히 삼키지 않음).

## 대안과 기각 이유

- **동기 경로가 종단을 계속 쓰되 worker가 덮어쓰기**: 두 writer가 남아 race·순서 의존이 그대로. 단일 writer(worker)로 좁히는 것이 구조적 해법. 기각.
- **타임아웃을 FAILED로 종단 + 보상 잡으로 정정**: 사용자가 그 사이 "실패" 응답을 보고 재시도 → 이중 예약. 타임아웃을 애초에 비종단으로. 기각.
- **좌석락 실패 시 락 없이 예약(기존 fail-open)**: DB `SELECT FOR UPDATE`가 막아준다는 논리지만, 멀티포드에서 락이 효율·정확성 모두 기여하므로 fail-closed가 안전하고 backoff로 가용성도 유지. 기각.
- **swap 보상 없이 "둘 다 실패" 반환**: 사용자가 좌석을 잃은 채 방치. 보상으로 원상복구. 기각.
- **마이그레이션으로 새 상태 추가**: `EXECUTING` 재사용·기존 outcome 문자열 재사용이라 불필요. `status`는 `VARCHAR(16)` `@Enumerated(STRING)`·CHECK 제약 없음. 기각.

## 동작 방식

```
confirm_action(reserve) → claim(PENDING→EXECUTING) → immediate intent insert(actionAuditId 연결)
  → worker가 ~8초 내 종단 안 하면: 웹/MCP는 PROCESSING(audit는 EXECUTING 유지)
worker tick:
  좌석 발견 → 좌석락 tryAcquire
    실패(예외/인터럽트/contention) → returnToWaiting (fail-closed, 다음 tick 재시도)
    성공 → reserve → [같은 txn] intent.succeed + finalizeFromIntent(EXECUTING→SUCCESS)
  upstream "taken" → [같은 txn] failRace + finalizeFromIntent(→FAILED, FAILURE_RACE)
expireWaiting(REQUESTED 즉시예약 만료) → finalizeFromIntent(→FAILED, TIMEOUT)
```

### 검증

- 회귀 2건: fast-path double-write 제거, expireWaiting/cancel 경로 audit stranding 차단(종단 전이 6종 전수 점검).
- 947 테스트 green, 하드리뷰 완료. 메트릭 태그(좌석락 acquired/skipped/deferred) 대시보드 참조 없음 확인.
- 교차레포 계약: 새 `PROCESSING` status는 ssuAI 모달이 SUCCESS 외 전부 "실패"로 처리하던 버그와 충돌 → **ssuAI PROCESSING 처리(`0c0ec20` #202)를 먼저 머지**한 뒤 #116 머지(break window 제거).

## 예상 면접 질문

1. 동기 응답과 비동기 worker가 같은 상태를 쓸 때 이중 기록을 어떻게 구조적으로 제거했나? 왜 "단일 진실원천 + 동일 트랜잭션 finalize + 멱등성"인가?
2. `finalizeFromIntent`는 어떤 상태들에서 no-op이며, 그 멱등 가드가 없으면 어떤 레이스로 audit가 뒤집히나?
3. 좌석락 획득 실패를 fail-open이 아니라 fail-closed로 바꾼 이유는? DB `SELECT FOR UPDATE`가 이미 있는데도 락이 필요한 멀티포드 시나리오는?
4. 원자 swap이 없는 upstream에서 "둘 다 잃음"을 어떻게 보상 트랜잭션으로 복구했고, PARTIAL_FAILURE는 언제 나오나?
5. 새 `PROCESSING` 상태가 cross-repo 계약 변경이었다. 프론트를 먼저 머지한 이유(break window)는?
