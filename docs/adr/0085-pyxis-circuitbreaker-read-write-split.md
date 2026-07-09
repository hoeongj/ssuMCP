# ADR 0085 — Pyxis CircuitBreaker 읽기/쓰기 분리

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-07-10 |
| 상태 | Accepted — 구현 |
| 범위 | `global.resilience.PyxisResilience`, admin resilience 노출, Pyxis 회로차단기 테스트 |
| 연관 ADR | [0029](0029-p1-3-ratelimiter-bulkhead.md), [0069](0069-observability-three-pillars.md), [0080](0080-multipod-shared-ratelimit-dualcap.md) |

---

## 배경 — 무슨 문제

`PyxisResilience`는 좌석 조회(read)와 좌석 예약/반납(write)을 하나의
Resilience4j `CircuitBreaker`(`pyxis`)로 보호했다. 이 구조에서는 열람실 좌석
조회 API가 느려지거나 5xx를 반환해 breaker가 열리면, 독립적으로 성공할 수
있는 예약/반납 POST까지 즉시 차단된다.

장애 전파 방향이 특히 나쁘다. 사용자는 좌석 조회가 흔들릴 때 오히려 이미 고른
좌석 예약을 확정하고 싶어 한다. 그런데 공유 breaker는 "읽기 장애"를 "쓰기
불가"로 전파해 가장 중요한 순간에 예약 능력을 잃게 만든다. 반대로 예약 POST가
일시적으로 실패해도 현재 좌석/내 예약 조회는 계속 제공할 수 있어야 한다.

ADR 0080에서 추가한 Redis 기반 Pyxis dual cap(사용자 공정성 cap -> 클러스터
cap)과 로컬 RateLimiter/Bulkhead는 그대로 유지한다. 이번 결정은 rate-limit
차원이 아니라 circuit-breaker 상태 차원만 분리한다.

## 검토한 대안

### 1. 공유 CircuitBreaker 유지

구현과 대시보드가 단순하고, "Pyxis 전체 upstream이 죽었다"는 거친 신호를 하나로
볼 수 있다. 하지만 실제 장애 모드는 endpoint/operation별로 다를 수 있고, 공유
상태가 읽기 실패를 쓰기 차단으로 전파하는 현재 문제가 그대로 남는다. 기각했다.

### 2. 읽기/쓰기 CircuitBreaker 분리

`pyxis-read`와 `pyxis-write`를 같은 설정으로 만들고, read decorator chain에는
read breaker만, write decorator chain에는 write breaker만 건다. read는 기존처럼
retry를 유지하고, write는 비멱등성 때문에 retry 없이 breaker만 적용한다.

메트릭 태그 `name`도 `pyxis-read` / `pyxis-write`로 분리되어 Grafana와 admin
resilience 화면에서 어느 축이 열린 것인지 바로 구분된다. ADR 0069의 관측성
원칙처럼 도메인 의미가 있는 이름을 metric 차원에 남긴다. 채택했다.

### 3. 도메인/엔드포인트별 더 세분화

예를 들어 `seat-rooms`, `room-seats`, `current-charge`, `reserve`, `discharge`
마다 breaker를 둔다. 가장 정확하지만 상태 수가 급증하고, traffic이 낮은 endpoint는
sliding window가 충분히 차지 않아 breaker 판정이 느려진다. 현재 문제는 read/write
장애 전파이므로 이 단계의 세분화는 과하다.

## 선택

`PyxisResilience`의 circuit breaker를 두 개로 분리한다.

- `pyxis-read`: 좌석 목록, 열람실 좌석, 현재 예약 조회 같은 read path
- `pyxis-write`: 예약/반납 같은 write path

두 breaker는 기존 `pyxis` breaker와 동일한 설정을 쓴다.

- failure threshold 50%
- slow call threshold 100%, slow call duration 8s
- count-based sliding window 20, minimum calls 10
- open wait 30s, half-open probe 3
- 기록 예외: `ConnectorTimeoutException`, `ConnectorUnavailableException`
- 무시 예외: `LibrarySeatNotAvailableException`, `LibraryAuthRequiredException`,
  `ConnectorParseException`

Redis dual cap은 변경하지 않는다. 호출 흐름은 여전히 `acquireDistributed(...)`
이후 로컬 decorator chain으로 들어간다. 달라진 점은 read chain이
`pyxis-read`, write chain이 `pyxis-write`를 참조한다는 것뿐이다.

기존 `circuitBreakerState()` / `circuitBreakerFailureRate()` 접근자는 오래된
호출자 호환을 위해 aggregate 값을 반환하도록 남겼다. admin resilience 응답은 새
이름 두 개를 각각 노출한다.

## 트레이드오프

- **상태 이중화**: Pyxis 전체 장애라면 read/write breaker가 따로 열리므로 같은
  upstream 문제를 두 상태로 보게 된다. 대신 한쪽 장애가 다른 쪽을 막지 않는다.
- **설정 중복**: 현재는 두 breaker가 같은 설정을 공유하지만, 운영 중 한쪽만 조정할
  유혹이 생긴다. 설정을 분기할 때는 read/write 트래픽 특성과 비멱등성 차이를
  근거로 별도 ADR을 남겨야 한다.
- **대시보드 변경**: 기존 `name="pyxis"` 패널은 `pyxis-read` / `pyxis-write`를
  보도록 바뀌어야 한다. 대신 어떤 operation 축이 문제인지 더 빨리 식별할 수 있다.

## 검증

- read breaker를 강제로 열어도 write supplier가 실행된다.
- write breaker를 강제로 열어도 read supplier가 실행된다.
- WireMock 기반 테스트에서 GET 5xx로 read breaker가 열린 뒤에도 예약 POST가
  upstream까지 도달한다.
- WireMock 기반 테스트에서 POST 5xx로 write breaker가 열린 뒤에도 현재 예약 GET이
  upstream까지 도달한다.
- 기존 Redis dual cap 테스트는 rate-limit 순서와 fallback 동작을 그대로 검증한다.

## 예상 면접 질문

1. **왜 circuit breaker는 read/write로 나누고 rate limiter는 ADR 0080의 dual cap을 유지했나?**  
   breaker는 실패 상태 전파를 막는 장치라 operation 격리가 중요하고, rate limiter는
   학교 upstream 보호와 사용자 공정성이라는 별도 축이라 기존 dual cap이 맞다.
2. **공유 breaker가 더 안전하게 upstream을 보호하는 것 아닌가?**  
   전체 장애라면 두 breaker가 모두 열리지만, 부분 장애에서는 공유 breaker가 정상
   operation까지 죽인다. 예약/반납은 사용자 영향이 커서 읽기 장애와 격리할 가치가
   더 크다.
3. **더 세분화하지 않고 read/write까지만 나눈 이유는?**  
   현재 장애 전파 문제의 경계가 read/write이고, endpoint별 breaker는 낮은 트래픽에서
   window가 늦게 차며 운영 상태도 과도하게 늘어난다. 필요한 격리만 먼저 적용했다.
