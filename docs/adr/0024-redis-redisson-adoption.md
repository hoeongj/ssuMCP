# ADR 0024 - Redis/Redisson 도입: 좌석 2계층 캐시, 좌석 이벤트, 스케줄러 리더십 락

- **Status**: Accepted - 2026-06-12 구현
- **Date**: 2026-06-12
- **Scope**:
  - `org.redisson:redisson-spring-boot-starter:4.5.0`
  - `com.ssuai.domain.library.redis`
  - `com.ssuai.domain.library.events`
  - `LibraryRoomSeatCache`
  - `ConfirmActionMcpTool`, `LibraryReservationWorker`
  - `LibrarySeatSampleSampler`, `LibraryRoomOccupancyHourlyRollupJob`, `LibrarySeatSamplePartitionMaintenance`
  - Helm chart `deploy/charts/ssuai-backend`

## 배경

MASTERPLAN의 `Redis/Redisson 확정 스펙 (2026-06-12)`가 이 ADR의 상위 결정이다. PR #39에서 Redis를 기각했던 이유는 당시 운영 전제가 단일 backend pod였기 때문이다. 단일 pod에서는 `LibraryRoomSeatCache`의 5초 L1 single-flight만으로 같은 room read의 동시 miss를 하나의 Pyxis 호출로 합칠 수 있었다.

전제가 바뀌는 지점은 scale-out이다. replica가 2개 이상이면 각 pod가 독립 L1을 가지므로 같은 room read가 pod 수만큼 Pyxis로 나갈 수 있다. 다음 SSE 유닛에서는 어느 pod에서 발생한 좌석 변경 이벤트든 모든 pod의 SSE 구독자에게 fan-out되어야 한다. 또한 seat sampler, hourly rollup, partition maintenance 같은 `@Scheduled` 작업은 replica 수만큼 중복 실행될 수 있다.

따라서 이번 도입 목적은 Redis를 "상태의 원천"으로 쓰는 것이 아니라, scale-out을 대비한 세 가지 보조 인프라로 제한한다.

1. 좌석 live room read의 Redis L2 cache.
2. 예약/반납/이석 성공 후 좌석 변경 pub/sub 이벤트 발행.
3. scheduler 리더십 락.

기존 예약 write path의 correctness는 PostgreSQL row lock과 intent queue 직렬화로 이미 증명했다. Redis 분산 락은 correctness가 아니라 중복 작업 비용을 줄이는 efficiency 도구로만 쓴다.

Sources:

- 내부 결정 기록 - `Redis/Redisson 확정 스펙 (2026-06-12 사용자와 확정, part2 Task 4 전반부)`
- Redisson Spring Boot Starter 문서: https://redisson.pro/docs/integration-with-spring/
- Redisson lock watchdog/lease 문서: https://redisson.pro/docs/data-and-services/locks-and-synchronizers/
- Redisson pub/sub 문서: https://redisson.pro/docs/data-and-services/publish-subscribe/
- Docker Official Images Redis tag source: https://raw.githubusercontent.com/docker-library/official-images/master/library/redis
- Martin Kleppmann, distributed locking critique: https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html

## 결정

### D1. 인프라는 Helm chart 내부의 단일 Redis pod로 둔다

채택한 배치는 `deploy/charts/ssuai-backend/templates/redis.yaml`의 Redis `Deployment` + `Service`다. 같은 namespace의 backend는 `SSUAI_REDIS_HOST`로 service DNS를 받는다. 기본 host는 Helm fullname에서 파생한 `<release>-redis`이고, 필요하면 values의 `env.redisHost`로 override할 수 있다.

Redis image는 `redis:8.8.0-alpine`으로 pin했다. Docker Official Images metadata에서 2026-06-12 기준 8.8.0 alpine tag가 제공되는 것을 확인했다. `latest`나 `8-alpine`처럼 움직이는 tag는 GitOps diff와 장애 재현성을 해친다.

영속성은 끈다.

```text
redis-server --appendonly no --save "" --maxmemory 64mb --maxmemory-policy allkeys-lru
```

이번 Redis에는 cache, pub/sub, scheduler lock만 들어간다. pod가 재시작되어 cache와 pub/sub ephemeral message가 사라져도 source of truth는 Pyxis와 PostgreSQL에 남는다. 따라서 PVC, AOF, RDB snapshot은 의도적으로 쓰지 않는다. 리소스도 단일 노드 k3s에 맞춰 request `25m/64Mi`, limit `100m/128Mi`, maxmemory `64mb`로 제한했다.

대안과 기각:

- **외부 managed Redis**: 운영 안정성은 좋지만 이 프로젝트의 현재 병목은 infra SLA가 아니라 portfolio용 scale-out backbone 검증이다. 비용과 credential surface가 늘고, 단일 k3s 데모에서 과하다.
- **Redis persistence/PVC**: cache/pub-sub/lock 용도에는 복구해야 할 durable state가 없다. persistence를 켜면 node disk, backup, fsync 지연, PVC 장애면을 새로 설명해야 한다.
- **Redis Cluster/Sentinel**: 현재는 single-node k3s다. 고가용 Redis를 흉내 내도 실제 failure domain이 하나라 포트폴리오 설명이 약하다.
- **Bitnami 등 subchart**: 설정이 풍부하지만 이번 요구는 단일 Deployment/Service면 충분하다. chart 표면이 커지면 리뷰와 운영 설명 비용이 증가한다.

### D2. 클라이언트는 Redisson starter 4.5.0을 pin한다

`build.gradle`에 `org.redisson:redisson-spring-boot-starter:4.5.0`을 추가했다. Redisson 문서는 이 starter가 Spring Boot 4.0.x를 지원하고 `spring.data.redis.*` 설정을 읽는다고 설명한다. 현재 프로젝트는 Spring Boot `4.0.6`이므로 starter의 기본 `redisson-spring-data-40` 계열과 맞다.

연결 설정은 다음 env key로 노출한다.

| env | 기본값 | 용도 |
| --- | --- | --- |
| `SSUAI_REDIS_HOST` | `localhost` | local default; prod chart는 Redis Service DNS |
| `SSUAI_REDIS_PORT` | `6379` | Redis TCP port |
| `SSUAI_REDIS_TIMEOUT` | `500ms` | command timeout |
| `SSUAI_REDIS_CONNECT_TIMEOUT` | `500ms` | connect timeout |
| `SSUAI_LIBRARY_REDIS_ENABLED` | `true` | adapter 사용 여부 |
| `SSUAI_REDIS_HEALTH_ENABLED` | `false` | actuator Redis health opt-in |

Redis health indicator는 기본 비활성화했다. 이번 Redis는 graceful-degradation dependency이므로 Redis down을 readiness down으로 연결하면 "Redis 장애 시 L1-only로 서비스 지속"이라는 요구와 충돌한다. 장애는 `library.redis.failure` metric과 WARN log로 관측한다.

Redisson auto-configuration에는 `RedissonAutoConfigurationCustomizer`를 붙여 `Config.setLazyInitialization(true)`를 켠다. Redis가 아직 뜨지 않았거나 일시적으로 unreachable이면 application startup에서 실패하지 않고, 첫 Redis operation에서 예외가 발생한다. 그 예외는 L2 cache/event/lock adapter가 잡아 L1-only 또는 lock 없는 fallback으로 처리한다. 즉 "connect failure도 graceful degradation 대상"이라는 요구를 startup 경계까지 확장한다.

대안과 기각:

- **Lettuce/Spring Data Redis 직접 사용**: pub/sub와 simple cache에는 충분하지만, scheduler lock까지 직접 구현해야 한다. Redisson은 `RLock`, `RTopic`, bucket TTL API를 제공하므로 이번 범위에 더 직접 맞는다.
- **Redisson 3.x 유지**: 최신 Spring Boot 4 프로젝트에서 starter 문서가 4.x line과 `redisson-spring-data-40`을 제시하므로 3.x를 고정할 이유가 없다.
- **unversioned dependency**: 빌드 재현성과 PR 리뷰가 약해진다. portfolio 기록에도 "어떤 버전으로 왜"를 설명할 수 없다.
- **eager connection validation 유지**: Redis가 hard dependency라면 빠른 실패가 맞지만, 이번 Redis는 cache/pub-sub/lock 보조 계층이다. Redis가 늦게 뜬다는 이유로 backend readiness가 죽으면 요구한 graceful degradation과 충돌한다.
- **`SSUAI_LIBRARY_REDIS_ENABLED=false`를 local 기본값으로 둠**: 로컬 부팅 안정성은 좋지만 prod에서 env 누락 시 Redis 기능이 조용히 꺼질 수 있다. 기본값은 true로 두고 lazy init + 실패 metric으로 관측하는 편이 더 명시적이다.

### D3. 좌석 L2 cache는 `roomId + auth boundary` key와 JSON DTO 문자열을 쓴다

기존 L1 `LibraryRoomSeatCache`는 유지한다. read order는 다음이다.

```text
LibraryRoomSeatCache.get(roomId, token)
  -> L1 ConcurrentHashMap fresh hit
  -> 같은 key miss single-flight winner 1개만 L2 조회
  -> Redis L2 fresh hit: L1에 5초 TTL로 populate 후 반환
  -> L2 miss: Pyxis upstream fetch
  -> L1 populate
  -> Redis L2에 같은 5초 TTL로 best-effort write
```

L2 key는 `ssuai:library:room-seats:v1:room:{roomId}:auth:{0|1}`이다. auth boundary는 기존 L1과 동일하게 "token 문자열 자체"가 아니라 "인증 호출인지 여부"만 반영한다. 토큰을 key에 넣으면 개인정보성 세션 material이 Redis key space에 남고 cross-user hit가 사라진다. 기존 정책도 token-a와 token-b를 같은 authenticated boundary로 묶는다.

TTL은 L1과 같은 `ssuai.library.room-seat.cache-ttl`, 기본 5초다. Redis L2만 더 길게 두면 pod 간 cache hit는 늘지만 좌석 live 상태가 stale해진다. L1보다 짧게 두면 cross-pod 효과가 약하다. 따라서 "실시간성 우선, cross-pod thundering herd 완화"라는 기존 목표에 맞춰 같은 TTL을 쓴다.

Serialization은 Jackson JSON array of `PyxisSeatInfo`를 `StringCodec` bucket에 저장한다. 예시는 다음 shape이다.

```json
[
  {
    "externalSeatId": 3179,
    "label": "74",
    "seatType": "general",
    "status": "available",
    "remainingTime": 0,
    "chargeTime": 0
  }
]
```

Graceful degradation은 cache class가 책임진다. L2 read/write에서 어떤 `RuntimeException`이 나도 WARN log와 `library.redis.failure{operation=room_seat_l2_read|room_seat_l2_write}` metric만 남기고 L1/upstream path를 계속 진행한다. Redis 때문에 좌석 read가 실패하지 않는다.

대안과 기각:

- **Redisson `RLocalCachedMap`으로 L1까지 교체**: Redisson local cache는 강력하지만 이번 요구는 기존 single-flight L1을 유지하는 것이다. 또한 in-flight future 공유 정책이 코드에서 덜 명시적이 된다.
- **Spring Cache abstraction**: key auth boundary, single-flight, Redis fallback metric을 세밀하게 표현하기 어렵다.
- **Kryo/Java binary serialization**: payload가 작고 다음 SSE/debug 단계에서 사람이 읽을 수 있는 JSON이 더 유리하다. DTO schema change도 JSON이 설명하기 쉽다.
- **TTL 30초 이상**: floor-level count cache와 달리 per-seat 상태는 예약/반납 직후 바뀐다. 5초 이상으로 늘리면 사용자가 실제로 이동하는 도서관 좌석 추천에서 stale risk가 커진다.
- **token별 key**: privacy와 hit rate 모두에서 불리하다. 현재 Pyxis room seat response가 사용자별로 달라지는 증거도 없다.

### D4. 좌석 변경 pub/sub은 일반 Redis topic + JSON string payload로 시작한다

채널명은 `ssuai.library.seat-events.v1`이고 env `SSUAI_LIBRARY_SEAT_EVENT_CHANNEL`로 바꿀 수 있다. payload는 `LibrarySeatEvent` JSON이다.

| field | type | 설명 |
| --- | --- | --- |
| `schemaVersion` | number | 현재 `1` |
| `roomId` | number/null | Pyxis room id. current charge payload가 구버전이거나 upstream이 id를 주지 않으면 null 가능 |
| `seatId` | number/null | Pyxis external seat id |
| `action` | string | `RESERVE`, `CANCEL`, `SWAP_DISCHARGE`, `SWAP_RESERVE` |
| `occurredAt` | ISO-8601 string | backend UTC `Clock` 기준 |

publish 지점은 "학교 시스템 상태 변경이 성공한 뒤"다.

- reserve: `LibraryReservationWorker`가 `reservationConnector.reserve(...)` 성공 후 `transactions.succeed(...)`까지 끝낸 뒤 `RESERVE` 발행.
- cancel: `ConfirmActionMcpTool`이 `discharge(...)` 성공과 action success 기록 후 `CANCEL` 발행.
- swap: 기존 좌석 `discharge(...)` 성공 직후 `SWAP_DISCHARGE` 발행, 새 좌석 `reserve(...)` 성공과 action success 기록 후 `SWAP_RESERVE` 발행. 새 좌석 예약이 실패해도 기존 좌석 반납은 이미 상태 변경이므로 discharge 이벤트는 발행 대상이다.
- expired lease recovery: worker가 current charge로 성공을 복구하면 `RESERVE`를 발행한다. crash 경계에서는 duplicate event가 가능하지만 pub/sub은 best-effort 신호이고 다음 SSE consumer가 최신 room read로 수렴하면 된다.

`LibrarySeatEventBus.subscribe(...)`는 얇은 subscriber abstraction만 제공한다. 이번 유닛에는 production consumer를 붙이지 않는다. 다음 SSE 유닛이 이 인터페이스를 통해 Redis topic을 구독한다.

Publish 실패는 action flow를 깨지 않는다. `LibrarySeatEventPublisher`가 예외를 잡고 WARN log, `library.redis.failure{operation=seat_event_publish}`, `library.seat_event.publish{outcome=failure}`만 남긴다.

대안과 기각:

- **Redis Stream/Reliable PubSub/outbox**: durable replay가 필요하면 맞지만 이번 이벤트는 SSE fan-out용 volatile signal이다. source of truth는 Pyxis room read와 PostgreSQL audit이다.
- **sampler diff 기반 이벤트도 즉시 발행**: 좌석이 우리 action 밖에서 바뀌는 경우를 잡을 수 있지만, diff noise와 sampling cadence 정책은 SSE 설계 때 결정하는 것이 맞다.
- **payload에 사용자/session/action audit id 포함**: SSE fan-out에 필요한 것은 어느 room/seat가 바뀌었는지다. 민감하거나 사용자 추적 가능한 값은 채널 payload에 넣지 않는다.
- **roomName/seatCode 중심 payload**: 사람이 보기 쉽지만 consumer가 room read invalidation과 routing을 하기에는 numeric id가 더 안정적이다.

### D5. scheduler lock은 Redisson `RLock.tryLock(0ms)` + watchdog을 쓴다

대상 job은 세 개다.

| job name | 대상 |
| --- | --- |
| `seat-sampler` | `LibrarySeatSampleSampler.sampleScheduled()` |
| `seat-hourly-rollup` | `LibraryRoomOccupancyHourlyRollupJob.rollupScheduled()` |
| `seat-partition-maintenance` | boot + daily partition maintenance |

lock key는 `ssuai:library:scheduler:{jobName}`이다. `SSUAI_LIBRARY_SCHEDULER_LOCK_WAIT` 기본값은 `0ms`다. lock을 못 잡으면 다른 pod가 실행 중이라는 뜻이므로 skip한다. Redis acquire 자체가 실패하면 WARN + metric 후 lock 없이 실행한다. 이는 "단일 pod fallback" 정책이다.

lease 전략은 explicit lease가 아니라 Redisson watchdog이다. Redisson 문서는 explicit `leaseTime`을 주면 지정 시간 뒤 자동 release되고, lease 없이 lock을 잡으면 watchdog이 holder JVM이 살아 있는 동안 lock 만료를 연장한다고 설명한다. sampler는 외부 Pyxis read 수와 timeout에 따라 실행 시간이 변할 수 있으므로 고정 lease보다 watchdog이 맞다.

안전 envelope:

- Redis lock loss 또는 Redis down에서 replica 2개가 같은 scheduler를 동시에 실행할 수 있다.
- sampler 중복은 raw sample row가 같은 `sampled_at`이면 PK 충돌 가능성이 있으나 scheduled tick의 `clock.instant()`가 pod마다 다르고, 최악의 경우 추가 row 시도/실패 로그 수준이다.
- hourly rollup은 PostgreSQL `ON CONFLICT DO UPDATE`라 idempotent이다. H2 path는 test-only다.
- partition maintenance는 `CREATE TABLE IF NOT EXISTS`, `DROP TABLE IF EXISTS`라 idempotent이다.
- 따라서 worst case는 중복 rollup 1회 또는 중복 maintenance 1회이며, 예약 correctness와 사용자 write action에는 영향을 주지 않는다.

대안과 기각:

- **예약/intention write path까지 Redisson lock으로 전환**: MASTERPLAN에서 명시적으로 기각했다. DB row lock으로 증명한 correctness를 Redis lease/GC pause/재시작 영향을 받는 best-effort lock으로 약화시킨다. Kleppmann의 글도 lock이 correctness를 책임질 때는 fencing token 같은 추가 안전장치가 필요하다는 점을 강조한다.
- **explicit lease 10분**: stuck lock 방지는 명확하지만 sampler가 일시적으로 오래 걸리면 lease 만료 후 다른 pod가 들어올 수 있다. 현재 connector timeout으로 무한 실행 가능성은 낮고, watchdog이 더 단순하다.
- **PostgreSQL advisory lock**: 새 infra 없이 가능하지만 이번 유닛의 목적은 Redis/Redisson adoption이다. 또한 Redis down fallback과 pub/sub/cache를 같은 operational story로 묶는 것이 더 설명력이 있다.
- **락 없음**: replica 2 이상에서 scheduler 중복이 구조적으로 남는다. Redis 도입의 세 번째 목적을 충족하지 못한다.

### D6. 테스트는 interface fake 전략을 채택한다

`gradlew.bat test`가 Windows에서 Docker 없이 green이어야 하므로 Redis 서버를 테스트 전제에 두지 않는다. Redis 사용 지점은 다음 인터페이스 뒤에 있다.

- `LibraryRoomSeatL2Cache`
- `LibrarySeatEventBus`
- `LibraryDistributedLockClient`

테스트는 fake implementation으로 다음을 고정한다.

- L2 hit: upstream connector를 호출하지 않고 L1에 populate.
- L2 miss: upstream fetch 후 Redis put에 TTL 5초 전달.
- Redis read/write failure: read는 upstream으로 fallback, write는 무시, metric 기록.
- action publish: cancel/swap/worker reserve 성공 후 publisher 호출.
- publish failure: action flow로 예외가 새지 않고 metric 기록.
- lock acquire/skip/fallback: task 실행 여부와 metric.

대안과 기각:

- **embedded Redis**: Java embedded Redis 라이브러리는 OS/architecture 호환성과 유지보수 상태가 테스트 안정성 리스크다.
- **Testcontainers**: 실제 Redis 호환성 검증은 좋지만 Docker가 필요하다. 이번 명시 요구인 Windows full test no-Docker와 충돌한다.
- **로컬 Redis에 붙는 통합 테스트**: 개발자 PC 상태에 따라 flaky하다. CI와 local 결과가 달라진다.

## 동작 요약

```text
room seat read
  L1 fresh hit
  -> L2 Redis JSON bucket hit
  -> Pyxis upstream
  -> L1 + L2 populate
  -> Redis failure: WARN + metric + L1/upstream continuation

seat action success
  Pyxis reserve/discharge success
  -> DB action/intent success 기록
  -> Redis topic publish JSON
  -> publish failure: WARN + metric + action success 유지

scheduled job
  Redisson RLock tryLock(0ms)
  -> acquired: run + unlock
  -> contention: skip
  -> Redis unavailable: WARN + metric + run without lock
```

## 관측 지표

| metric | tag | 의미 |
| --- | --- | --- |
| `library.redis.failure` | `operation`, `exception` | Redis L2, pub/sub, scheduler lock failure |
| `library.seat_event.publish` | `outcome=success|failure` | 좌석 이벤트 발행 결과 |
| `library.scheduler.lock` | `job`, `outcome=acquired|skipped|fallback|release_failed` | scheduler lock 결정 |

## 운영 설정

| env | 기본값 | 설명 |
| --- | --- | --- |
| `SSUAI_REDIS_HOST` | local `localhost`, prod service DNS | Redisson single server host |
| `SSUAI_REDIS_PORT` | `6379` | Redis port |
| `SSUAI_REDIS_TIMEOUT` | `500ms` | command timeout |
| `SSUAI_REDIS_CONNECT_TIMEOUT` | `500ms` | connect timeout |
| `SSUAI_LIBRARY_REDIS_ENABLED` | `true` | false면 no-op adapter |
| `SSUAI_LIBRARY_REDIS_ROOM_SEAT_CACHE_KEY_PREFIX` | `ssuai:library:room-seats:v1` | L2 key prefix |
| `SSUAI_LIBRARY_SEAT_EVENT_CHANNEL` | `ssuai.library.seat-events.v1` | pub/sub channel |
| `SSUAI_LIBRARY_SCHEDULER_LOCK_PREFIX` | `ssuai:library:scheduler:` | lock key prefix |
| `SSUAI_LIBRARY_SCHEDULER_LOCK_WAIT` | `0ms` | tryLock wait time |
| `SSUAI_REDIS_HEALTH_ENABLED` | `false` | actuator Redis health opt-in |
