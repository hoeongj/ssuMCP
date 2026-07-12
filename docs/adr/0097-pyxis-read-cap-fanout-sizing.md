# ADR 0097 — Pyxis READ cap fan-out sizing

- 상태: 채택 (2026-07-12)
- 관련: [0029](0029-p1-3-ratelimiter-bulkhead.md)(original Pyxis outbound cap), [0080](0080-multipod-shared-ratelimit-dualcap.md)(Redis-shared dual cap), [0093](0093-upstream-429-retry-after.md)(Retry-After/429 backoff)

## Context / 배경

실사용에서 "도서관 아무자리나 예약해줘" 첫 턴이 간헐적으로 Pyxis read limiter에 걸렸다. 문제는 상류 장애나 sampler 폭주가 아니라, 우리가 rate cap을 잡은 **작업 단위**가 실제 작업 단위와 달랐다는 점이다.

한 번의 "find any seat" 요청은 사용자 관점에서 하나의 논리 작업이지만, 내부적으로는 `LibrarySeatRecommendationService`가 `FLOOR_ROOMS`를 돌며 최대 6개 room을 순차 조회한다. 각 room read는 `LibraryRoomSeatCache.get` -> `RealLibrarySeatConnector` -> `pyxisResilience.read(principalOf(token), ...)`로 들어가고, 모두 같은 Pyxis token principal 아래에서 실행된다.

ADR 0029의 read cap은 "사용자 요청 1개 = upstream call 1개"라는 가정에 가까웠다. 그 결과 per-user read 2/s는 6-room fan-out에 대해 headroom이 0이 아니라 음수에 가깝다. 한 사용자의 정상 seat scan이 자기 자신의 per-user bucket에서 세 번째 read부터 막힐 수 있다. cluster read 5/s도 5분마다 6개 room을 읽는 background seat sampler와 공유되지만, sampler 부하는 `6 reads / 5 min` 수준이라 이 문제의 1차 원인이 아니다.

Prod는 `SSUAI_PYXIS_READ_CLUSTER_LIMIT_PER_SECOND`와 `SSUAI_PYXIS_PER_USER_READ_LIMIT_PER_SECOND`를 override하지 않는다. 따라서 `application.yml` default가 곧 운영 값이다.

## 대안 검토

1. **Lower the sampler cadence**  
   기각. sampler는 사용자 token과 다른 principal을 쓰므로 per-user self-throttle에는 아무 영향이 없다. cluster cap만 공유하는데, sampler load는 6 reads/5min으로 20/s cluster budget에서는 무시할 수 있다.

2. **Operation-granularity metering / atomic Redisson `acquire(N)` up front**  
   이론적으로 가장 깨끗한 해법이다. seat scan 전체를 raw HTTP call 6개가 아니라 "사용자 작업 1개"로 과금하고, 시작 시점에 Redisson `tryAcquire(N, timeout)` 또는 동등한 multi-permit acquire로 fan-out budget을 원자적으로 확보하면 "cost = work"에 더 가깝다.  
   이번에는 기각한다. recommendation -> connector -> `PyxisResilience` 호출 경계를 재구성해야 하고, reservation pipeline을 막 안정화한 직후라 risk/reward가 맞지 않는다. per-user fairness를 더 엄격히 해야 하는 시점이 오면, 이 방식이 자연스러운 future refactor다.

3. **Dedicated low-priority sampler bucket / load-shedding**  
   Stripe가 critical traffic에 약 20% capacity를 reserve하고 batch/non-critical traffic을 먼저 shed하는 방식처럼 sampler 전용 low-priority bucket을 둘 수도 있다. 기각. sampler 부하는 20/s cluster budget 대비 너무 작고, 별도 priority class는 현재 문제에 비해 over-engineering이다.

## 결정

Pyxis read cap을 실제 seat-scan fan-out에 맞게 조정한다.

- cluster read: 5/s -> 20/s
- per-user read: 2/s -> 8/s
- write caps unchanged: cluster 2/s, per-user 1/s

8/s per-user는 6-room scan 전체와 read retry headroom을 제공한다. 동시에 20/s cluster budget의 40% 이하라서 한 사용자가 cluster budget을 독점하지 못한다. 20/s cluster read는 authenticated, user-driven integration에 대해 방어 가능한 15-25 req/s politeness ceiling 안에 있고, ADR 0093의 Retry-After/429 backoff가 상류의 명시적 제동을 계속 존중한다.

Write cap은 유지한다. 예약/퇴실은 serial, non-idempotent operation이고 "find any seat" 같은 room fan-out이 없다. write 2/s cluster, 1/s per-user는 ADR 0029/0080의 의도를 그대로 보존한다.

## 작동 방식

호출 경로는 ADR 0080의 dual-cap 구조 그대로다.

1. per-user fairness cap을 먼저 확인한다. 같은 principal이 자기 예산을 초과하면 cluster budget을 소모하지 않고 `RequestNotPermitted`로 거부한다.
2. cluster politeness cap을 다음에 확인한다. 모든 pod와 principal이 공유하는 school-protection budget이다.
3. 두 cap 모두 Redisson `RRateLimiter`로 Redis-shared 상태를 쓴다.
4. Redis가 없거나 장애가 나면 fail-open으로 내려가 기존 local resilience4j limiter가 per-pod fallback 역할을 한다.

이번 ADR의 최초 cap 변경은 확인 순서, timeout, failure mode를 바꾸지 않는다. 아래 trySetRate 함정 보완만 Redis keying/TTL을 조정한다.

### 함정: Redisson trySetRate는 set-if-absent (config가 재적용되지 않음)

Redisson `RRateLimiter.trySetRate`는 기존 key가 이미 설정되어 있으면 새 rate로 덮어쓰지 않는다. 따라서 `application.yml`에서 `read-cluster-limit-per-second`를 5/s에서 20/s로 올려도, 운영 Redis에 남아 있는 long-lived cluster limiter key가 처음 본 5/s rate를 계속 들고 있으면 prod에서는 사실상 no-op이 된다. per-user key는 5분 TTL이 있어서 eventually self-heal되지만, cluster key는 TTL이 없어서 수동 Redis reset 전까지 sticky rate가 유지될 수 있었다.

Root cause는 두 가지다. 첫째, `trySetRate`가 set-if-absent라서 기존 limiter config를 overwrite하지 않는다. 둘째, cluster key가 `ssuai:resilience:pyxis:v1:<operation>:cluster`처럼 configured rate와 무관하고 TTL도 없어서 superseded config가 영구 보존될 수 있었다.

Fix는 limiter key에 configured rate를 인코딩하는 것이다. per-user key와 cluster key 모두 `:r<n>` suffix를 붙여 rate config가 바뀌면 즉시 fresh key를 사용한다. cluster key에는 10분 TTL도 추가해서 rolling deploy 이후 더 이상 쓰이지 않는 old-rate key와 idle key를 회수한다.

Rolling deploy 중에는 old-rate pod와 new-rate pod가 잠깐 서로 다른 cluster key를 쓰므로 cluster budget이 둘로 나뉜다. 이 transient는 더 permissive한 방향으로 fail하고, rollout이 끝나면 모든 pod가 new-rate key로 수렴하므로 허용한다.

## Consequences / 검증

추가 회귀 테스트:

- `PyxisResilienceRedisIT.singleUserSeatScanFanOutFitsWithinPerUserReadCap`: tuned cap(20/s cluster, 8/s per-user)에서 동일 principal의 6회 read가 모두 통과한다.
- `PyxisResilienceRedisIT.perUserReadCapAtOldValueWouldThrottleTheSameFanOut`: old per-user 2/s에서는 같은 fan-out의 세 번째 read가 `RequestNotPermitted`로 막힌다.
- `PyxisResilienceRedisIT.rateConfigChangeAppliesBecauseKeyEncodesTheRate`: 같은 test method 안에서 Redis reset 없이 old cap(10/s cluster, 2/s per-user)으로 key를 만든 뒤 tuned cap(20/s cluster, 8/s per-user)을 적용해도 fresh `:r<n>` key 때문에 같은 principal의 6회 read가 통과한다.
- `PyxisResilienceTests.defaultPyxisRateCapsMatchSeatScanFanOutSizing`: `PyxisResilienceProperties` 기본값이 read 20/8, write 2/1임을 고정한다.

운영 rollback은 코드 재배포 없이 가능하다. `SSUAI_PYXIS_READ_CLUSTER_LIMIT_PER_SECOND` 또는 `SSUAI_PYXIS_PER_USER_READ_LIMIT_PER_SECOND`를 override하면 된다. 현재 prod는 override하지 않으므로 default 변경이 곧 운영 변경이다.

리스크는 cluster read ceiling이 올라가는 점이다. 완화 요소는 세 가지다: per-user 8/s가 anti-monopoly guard로 남아 있고, cluster 20/s가 전체 상한을 계속 잡으며, 상류 429/Retry-After는 ADR 0093 경로로 backoff된다.

## References

- [ADR 0029 — P1-3 RateLimiter/Bulkhead](0029-p1-3-ratelimiter-bulkhead.md)
- [ADR 0093 — Pyxis upstream 429와 Retry-After 처리](0093-upstream-429-retry-after.md)
- [Resilience4j RateLimiter docs](https://resilience4j.readme.io/docs/ratelimiter) — fixed-cycle permit refresh via `limitRefreshPeriod` / `limitForPeriod`.
- [Redisson `RRateLimiter` source docs](https://github.com/redisson/redisson/blob/master/redisson/src/main/java/org/redisson/api/RRateLimiter.java) — Redis-backed limiter with multi-permit `tryAcquire(long permits, Duration timeout)`.
- [Stripe — Scaling your API with rate limiters](https://stripe.com/blog/rate-limiters) — request limiters, concurrency limiters, and priority/load-shedding patterns.
- [Google SRE — Cascading Failures: Reducing System Outage](https://sre.google/sre-book/addressing-cascading-failures/) — fail early, load shedding, and keeping degraded paths simple.
- [RFC 1363 — token bucket rate](https://datatracker.ietf.org/doc/html/rfc1363) — token bucket credits model where larger work consumes more credits.
