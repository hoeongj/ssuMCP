# ADR 0079 — 멀티포드 백그라운드 작업의 행 단위 claim/lease

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-07-09 |
| 상태 | Draft |
| 범위 | `LibraryReservationEventRelay`, `LmsExportBuildWorker`, `library_reservation_outbox`, `lms_export_jobs` |
| 연관 문서 | ADR 0022(예약 intent/outbox), ADR 0033·0035(LMS ZIP export), ADR 0048(intent SSE), ADR 0071(event pipeline), ADR 0072(DB retention), ADR 0078(용량 계획) |

---

## 문제 정의

backend replica를 1개에서 2개로 늘리면 모든 pod에서 같은 scheduled method가 실행된다.

- 예약 이벤트 relay는 두 pod가 같은 `published_at IS NULL` 행을 읽어 같은 이벤트를 두 번 발행할 수 있다.
- LMS export worker는 두 pod가 같은 `QUEUED` job을 각각 `BUILDING`으로 바꾸고 같은 ZIP을 중복 생성할 수 있다. 원격 다운로드 비용뿐 아니라 같은 job id 기반 파일 경로 충돌도 생긴다.
- JVM 내부의 `AtomicBoolean`은 한 프로세스의 scheduled invocation 중첩만 막으며 pod 간 상호 배제에는 효력이 없다.
- 트랜잭션 row lock을 외부 이벤트 발행이나 LMS 다운로드가 끝날 때까지 유지하면 중복은 막지만, 긴 트랜잭션과 DB connection 점유를 유발한다.
- claim 직후 pod가 종료될 수 있으므로 영구적인 owner 표식만으로는 작업이 고립된다. 일정 시간이 지나면 다른 pod가 회수할 수 있어야 한다.

현재 replica=1의 poll cadence와 batch 크기, 실효 처리량은 유지해야 하며 배포 시 새 필수 설정을 요구하지 않는다.

## 대안 비교

| 대안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| PostgreSQL advisory lock 기반 leader 선출 | 앱 외 인프라 없이 구현 가능, 한 번에 한 scheduler만 실행 | connection/lock lifecycle 관리가 필요하고 한 leader에 모든 job이 직렬화됨. 행별 병렬 처리 불가 | 기각 |
| Kubernetes Lease 기반 leader 선출 | 클러스터에서 명시적인 leader와 관측 가능한 lease 제공 | Kubernetes API/RBAC 및 client 의존. 로컬·테스트 실행과 결합도가 커지고 역시 leader 하나로 직렬화 | 기각 |
| ShedLock | scheduler 단위 lock을 간단히 적용, 검증된 라이브러리 | 새 라이브러리와 lock table 필요. method 단위 상호 배제라 독립 LMS job의 pod 간 병렬성을 활용하지 못함 | 기각 |
| `FOR UPDATE SKIP LOCKED` 행 claim + DB lease | lock은 짧은 claim 트랜잭션에만 유지. 독립 행을 pod별로 나눌 수 있고 기존 PostgreSQL 외 인프라가 없음 | lease timeout 조정, 소유권 표식, 순서 보장 규칙을 직접 설계해야 함 | 선택 |

## 결정

### 1. 공통 claim/lease 모델

Flyway `V15__add_background_processor_claim_leases.sql`에서 두 테이블에 nullable
`claimed_at`, `claimed_by`를 추가한다. 기존 행은 두 값이 `NULL`이므로 즉시 claim할 수
있다. `claimed_by`는 `HOSTNAME`을 사용하고, 해당 환경 변수가 없으면 프로세스별
random UUID를 사용한다.

claim은 다음 두 단계로 분리한다.

1. 짧은 트랜잭션에서 claim 가능한 행을 `FOR UPDATE SKIP LOCKED`로 선택하고
   `claimed_at`/`claimed_by`와 실행 상태를 기록한 뒤 commit한다.
2. DB lock 없이 이벤트 발행 또는 ZIP 생성을 수행한다. 완료 트랜잭션은
   `claimed_by`가 자신과 일치할 때만 결과를 확정한다.

lease가 만료되면 다른 pod가 같은 행을 다시 claim할 수 있다. 이는 pod 종료로
고립된 작업을 복구하지만, timeout을 넘겨 살아 있는 이전 작업과 재처리가 겹칠 수
있다는 일반적인 lease trade-off가 있다.

### 2. 예약 outbox: 가장 오래된 미발행 batch 하나만 claim

outbox는 30초 lease와 기존 50행 batch를 사용한다. 먼저 전체 미발행 행 중 최소
`id`인 한 행을 batch guard로 `FOR UPDATE SKIP LOCKED` 한다. 다음 조건 중 하나면
해당 poll은 아무 행도 claim하지 않는다.

- 다른 트랜잭션이 그 guard를 잠그고 있다.
- guard가 다른 pod의 만료되지 않은 lease에 속한다.

guard를 얻은 pod만 `id ASC`로 다음 50행을 claim한다. claim transaction이 끝난
뒤에도 guard는 active lease로 남으므로 다른 pod는 뒤 batch를 앞질러 claim하지
못한다. relay는 claim 결과를 `id ASC` 순서로 발행하고, 전부 발행한 뒤 owner 조건을
붙인 bulk update로 `published_at`을 기록하며 lease를 지운다.

#### 순서 보장 분석

outbox 생성 순서는 intent 상태 전이 순서이며 relay의 downstream은
`ApplicationEventPublisher → Redisson RTopic → LibraryIntentSseRegistry`다. Redis
topic은 여러 publisher 사이의 전역 순서를 만들어 주지 않는다. 특히 terminal
이벤트가 먼저 도착하면 SSE registry가 emitter를 완료하므로, 뒤늦은
`WAIT_REGISTERED`/`SEAT_FOUND`는 사용자에게 전달되지 않는다.

따라서 단순히 각 pod가 `SKIP LOCKED`로 서로 다른 batch를 가져가는 방식은 사용하지
않는다. intent별 직렬화만으로도 기능상 충분하지만 쿼리와 상태가 복잡해지고 기존
단일 relay가 제공하던 전역 `id` 순서보다 계약이 약해진다. 이번 단계에서는
**oldest-row batch guard로 relay 전체의 전역 `id` 순서를 유지**한다. relay는
멀티포드 처리량 확장 대상이 아니라 장애 인계 대상이고, replica=1의 cadence,
batch 크기, 실효 처리량은 그대로다.

발행 후 `published_at` commit 전에 pod가 종료되면 lease 만료 후 같은 이벤트가 다시
발행될 수 있다. 이는 ADR 0022의 기존 at-least-once 계약과 동일하며, lease는
exactly-once를 보장하지 않는다.

### 3. LMS export: 독립 job을 행 단위로 병렬 claim

LMS worker는 다음 행 하나를 `created_at, id` 순서로 선택한다.

```sql
status = 'QUEUED'
OR (status = 'BUILDING' AND (claimed_at IS NULL OR claimed_at <= :leaseCutoff))
ORDER BY created_at, id
LIMIT 1
FOR UPDATE SKIP LOCKED
```

선택한 job은 `BUILDING`과 owner/claim 시각을 같은 트랜잭션에 기록한다. 두 pod는
서로 잠긴 행을 건너뛰므로 같은 유효 lease의 job을 처리하지 않고, 서로 다른 job은
병렬로 만들 수 있다. 기본 lease는 5분이다. 기존 배포 중 생성된 owner 없는
`BUILDING` 행도 회수 대상으로 포함한다.

완료 시 job을 다시 잠그고 DB의 `claimed_by`가 현재 worker와 같을 때만
`READY`/`FAILED` 결과를 저장한다. lease 만료 후 새 pod가 회수한 상태를 이전
worker가 덮어쓰는 것을 막는다. terminal 전이와 expiry는 lease 값을 지운다.

`relay-lease`와 `lease-duration`은 코드 기본값이 있으므로 replica=1 배포에 새
설정이 필요 없다. 기존 poll interval(예약 relay 2초, LMS 5초)도 바꾸지 않는다.

## 트레이드오프와 운영 기준

- outbox는 순서를 위해 pod 간 병렬 발행을 포기한다. 대신 어느 pod든 30초 뒤
  중단된 batch를 인계할 수 있다.
- LMS lease는 무한 대기를 제거하지만 exactly-once 실행을 만들지는 않는다.
  5분보다 오래 응답하지 않는 단일 외부 다운로드가 계속 살아 있으면 lease 만료 후
  재처리와 일부 겹칠 수 있다. owner 조건은 stale DB 완료를 막지만 이미 발생한
  원격 I/O를 취소하지는 못한다. 실제 build latency가 lease에 접근하면 heartbeat
  또는 lease 연장을 후속 적용한다.
- `SKIP LOCKED` 정확성은 H2로 검증할 수 없다. 동시 claim과 lease 만료 테스트는
  저장소 공통 `AbstractPostgresIT`를 사용해 PostgreSQL 16 Testcontainers에서
  실행한다. Docker가 없는 개발 환경에서는 기존 정책대로 해당 IT만 skip한다.
- 새 claim index는 polling 비용을 제한하는 대신 두 테이블의 insert/update 비용과
  저장 공간을 소폭 늘린다.

## 예상 면접 질문

1. **왜 outbox도 LMS처럼 pod별로 서로 다른 batch를 병렬 발행하지 않았나?**  
   terminal 이벤트가 SSE emitter를 닫기 때문에 여러 Redis publisher의 순서 역전은
   중간 상태 유실로 이어진다. oldest-row guard로 기존 전역 outbox id 순서를 유지했다.

2. **lease가 있으면 exactly-once 처리가 보장되나?**  
   아니다. crash-after-side-effect-before-commit과 timeout을 넘긴 살아 있는 worker
   때문에 재실행 가능성이 남는다. outbox는 기존 at-least-once 계약을 유지하고,
   LMS는 owner 확인으로 stale DB 완료만 차단한다.

3. **leader election보다 row claim을 선택한 이유는 무엇인가?**  
   leader election은 scheduler 전체를 한 pod로 직렬화한다. row claim은 짧은 DB
   트랜잭션만 조정하면서 독립 LMS job을 여러 pod에 분배하고 별도 Kubernetes
   API/RBAC이나 lock 라이브러리를 요구하지 않는다.
