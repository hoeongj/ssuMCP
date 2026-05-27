# ADR 0002 — meal fan-out 성능 개선

- **Status**: Accepted (retroactive — 이미 구현되어 머지됨, 관련 commit: `c4e4115`)
- **Date**: 2026-05-07
- **Scope**: `com.ssuai.domain.meal.connector.RealMealConnector`, `com.ssuai.domain.meal.service.MealService`

## Context

학식 weekly export 를 실제 사이트로 처음 돌렸을 때 7일 × 식당 6개 fan-out
소요 시간이 1분 22초였습니다. 42개 외부 호출이 필요하므로 어느 정도
시간이 걸리는 것은 자연스럽지만, MVP 단계의 수동 검증과 향후 배치 실행을
생각하면 그대로 두기에는 체감 지연이 컸습니다.

이 작업은 단순한 micro-optimization 이 아니라 export workflow 의 기본
사용성 문제였습니다. 한 번 실행할 때마다 1분 이상 기다리면 connector 검증,
fixture 갱신, PR 전 수동 확인이 모두 느려집니다. 반대로 같은 요청 수를
유지하면서 병렬성을 회복할 수 있다면 외부 사이트 부담을 크게 늘리지 않고
개발 속도를 개선할 수 있었습니다.

원인은 두 겹이었습니다. 먼저 `MealService.getMeal` 이 `MealRestaurant` 를
sequential `for` loop 로 돌며 한 식당씩 호출했습니다. 여기에
`RealMealConnector.waitForRateLimit()` 이 `synchronized` + 단일
`lastCallAtMs` 를 사용하고 있어, 같은 connector 인스턴스 안의 모든 호출이
1초 간격으로 전역 직렬화됐습니다.

단순히 `MealService.getMeal` 에 `parallelStream` 만 적용해도 효과가 거의
없는 구조였습니다. 병렬 stream 이 여러 호출을 동시에 시작해도 결국
`waitForRateLimit()` 의 같은 synchronized lock 에서 줄을 서기 때문입니다.
따라서 의미 있는 개선을 하려면 fan-out 방식뿐 아니라 rate-limit 정책의
lock 범위 자체를 조정해야 했습니다.

## Decision

rate-limit 의 lock 과 `lastCallAtMs` 를 식당 코드(rcd) 단위로 분리합니다.
같은 식당에 대한 연속 호출은 계속 1초 간격을 지키지만, 서로 다른 식당은
동시에 조회할 수 있게 합니다.

```java
private final ConcurrentMap<String, Object> rateLimitLocksByRcd = new ConcurrentHashMap<>();
private final ConcurrentMap<String, Long> lastCallAtMsByRcd = new ConcurrentHashMap<>();
```

`MealService.getMeal` 의 식당 fan-out 은 `parallelStream` 으로 변경합니다.
결과 항목 순서는 `MealRestaurant` enum 선언 순서를 유지합니다. Java Stream
의 encounter order 보장을 활용해 parallel 처리 후에도 클라이언트가 보는
식당 순서가 흔들리지 않게 했습니다.

rate-limit 1초 간격 자체는 유지합니다. 외부 사이트의 명확한 정책을 모르는
상태에서 간격을 줄이는 것은 성능은 좋아지지만 사이트 매너 측면의 근거가
약합니다.

부분 실패 처리 방식은 바꾸지 않습니다. 특정 식당 connector 호출만 실패하면
`MealService` 가 기존처럼 `closures` 로 흡수하고, 모든 식당이 실패했을 때만
마지막 `ConnectorException` 을 다시 던집니다. 이번 결정의 초점은 error
semantics 가 아니라 같은 semantics 안에서 fan-out 시간을 줄이는 것입니다.

## Consequences

**좋은 점**
- weekly export 시간이 1분 22초에서 26초로 줄었습니다. 약 3.2배 단축입니다.
- 같은 식당 연속 호출은 여전히 1초 간격이라 기존 사이트 부담 정책은 유지됩니다.
- daily endpoint 도 자동으로 빨라집니다. 한 날짜의 6개 식당 조회가 거의 동시에 진행됩니다.
- 결과 순서를 유지해 frontend 나 MCP client 가 정렬 변화를 신경 쓰지 않아도 됩니다.

**대가**
- 같은 사이트에 동시 6요청이 발생합니다. 일반 사용자가 식당 탭 6개를 여는 정도라 현 단계에서는 부담이 작다고 판단했습니다.
- `ForkJoinPool.commonPool` 을 사용합니다. IO 차단 작업이라 다른 parallel 작업과 contention 가능성이 있습니다.
- per-rcd lock map 이 생겨 connector 상태가 약간 늘었습니다. 다만 rcd 종류가 enum 기반 6개라 실질적인 무한 증가는 없습니다.
- 동시성 테스트와 순서 보장 테스트가 필요해졌습니다. 성능 개선이 응답 contract 를 흔들면 안 되기 때문입니다.

## Alternatives considered

- **In-memory caching (Caffeine)** — 첫 호출만 느리고 이후 조회는 ms 단위가 되지만, architecture §7 의 Redis 캐시 전략과 겹치는 더 큰 결정이라 이번 범위에서 제외했습니다.
- **rate-limit 1초 → 짧게** — 학교 사이트 정책과 `robots.txt` 를 충분히 확인하지 않은 상태라 보수적으로 유지했습니다.
- **별도 executor + IO-friendly thread pool** — commonPool 보다 정확하지만, 식당 6개 fan-out 만 있는 MVP 단계에서는 운영 복잡도 대비 이득이 작습니다.
