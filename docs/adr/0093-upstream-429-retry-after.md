# ADR 0093 — Pyxis upstream 429와 Retry-After 처리

- 상태: 채택 (2026-07-11)
- 관련: 0021(Pyxis Resilience4j fault tolerance), 0029(Pyxis outbound rate limit/bulkhead), 0061(inbound rate limit)

## 배경
숭실대 도서관 Pyxis 연동은 `RestClientResponseException`을 공통 예외로 정규화하지만, 지금까지 HTTP 429를 별도로 다루지 않았다. 5xx가 아니면 대부분 `ConnectorParseException`이 되어 우리 응답은 502로 나갔고, 상류가 "잠시 기다려 달라"는 의미로 `Retry-After`를 내려도 읽지 않았다.

문제는 두 가지다. 첫째, 익명 도서 검색처럼 `PyxisResilience`로 감싸지지 않은 경로는 상류 429를 사용자에게 502로 보여 주어 원인을 잘못 전달한다. 둘째, `PyxisResilience`의 read retry는 timeout/5xx만 재시도하므로, Pyxis가 일시적으로 rate limit을 걸었을 때 읽기 요청도 즉시 실패한다.

## 고려한 대안
1. **기존처럼 502 유지**: 기각. 구현 변경은 없지만 rate limit이라는 의미를 잃고, 클라이언트가 재시도 시점을 판단할 수 없다. 운영 관점에서도 Pyxis 응답 해석 실패와 실제 rate limit이 같은 지표로 섞인다.
2. **429도 무조건 기존 지수 백오프로만 재시도**: 기각. read path의 재시도 횟수는 활용할 수 있지만, 상류가 준 대기 시간을 무시한다. 너무 빨리 재시도하면 같은 429를 반복하고, 너무 늦게 재시도하면 불필요하게 응답을 지연한다.
3. **전용 예외로 429를 분리하고 Retry-After를 존중**: 선택. 예외 분류, 재시도 간격, 최종 HTTP 응답이 모두 같은 의미를 공유한다. 상류가 준 힌트가 있으면 따르고, 없으면 기존 지수 백오프로 돌아갈 수 있다.

## 결정
Pyxis 커넥터의 HTTP 429는 `ConnectorRateLimitedException`으로 분리한다. 예외는 nullable `Duration retryAfter`를 가진다. `Retry-After` 파싱은 delta-seconds와 RFC 1123 HTTP-date 형식을 모두 지원하고, 파싱할 수 없으면 값이 없는 것으로 처리한다.

`PyxisResilience` read retry는 `ConnectorRateLimitedException`도 재시도한다. 기존 `IntervalFunction` 대신 `IntervalBiFunction`을 사용해 마지막 예외가 `ConnectorRateLimitedException`이고 `retryAfter`가 있으면 그 값을 우선 사용한다. `retryAfter`가 없거나 429가 아닌 transient 예외면 기존 200ms 시작, 배수 2의 지수 백오프로 처리한다. write path는 예약/퇴실의 비멱등성 때문에 계속 재시도하지 않는다.

구현 단위 결정:
- `Retry-After` 파싱 결과는 최대 60초로 clamp한다. 비정상적으로 큰 상류 값 때문에 요청 스레드가 오래 묶이는 것을 막기 위한 파서 안전장치다.
- `PyxisResilience`의 retry 대기에는 별도 cap 2초(`ssuai.resilience.pyxis.retry-after-cap-ms`, 기본 2000)를 둔다. 상류 힌트는 존중하되, read retry 한 번이 과도하게 길어지는 것은 제한한다.
- adversarial review 지적에 따라 2-OCPU 단일 노드에서 429 storm이 bulkhead slot/thread를 오래 붙잡지 않도록 cap을 2초로 둔다. 더 짧은 `Retry-After`는 2초까지 그대로 따르고, 더 긴 힌트는 2초로 제한한다.

## 동작 원리
각 Pyxis 커넥터는 `RestClientResponseException`을 잡은 뒤 5xx 처리와 일반 parse error 처리 사이에서 `status == 429`를 먼저 확인한다. 응답 헤더에 `Retry-After`가 있으면 delta-seconds 또는 HTTP-date로 파싱하고, 성공하면 `ConnectorRateLimitedException`에 담는다.

`RealLibrarySeatConnector`와 `RealLibraryReservationConnector`의 read 경로처럼 `PyxisResilience.read()`로 감싼 호출은 최대 3회까지 재시도한다. 429에 `Retry-After`가 있으면 해당 시간과 2초 cap 중 작은 값을 대기한다. 헤더가 없으면 기존 지수 백오프를 그대로 쓴다.

익명 도서 검색처럼 resilience wrapper 밖에 있는 경로, 또는 write처럼 retry하지 않는 경로에서 429가 최종적으로 전파되면 `GlobalExceptionHandler`가 우리 응답을 429로 매핑한다. 예외에 `retryAfter`가 있으면 우리 응답에도 `Retry-After` 헤더를 delta-seconds로 내려보낸다.

## 결과와 한계
결과적으로 Pyxis rate limit은 더 이상 502 parse error로 보이지 않는다. 클라이언트는 429와 `Retry-After`를 보고 재시도 정책을 세울 수 있고, read path는 상류가 알려 준 대기 시간을 먼저 따른다.

한계도 있다. `Retry-After`는 상류 힌트일 뿐 성공 보장이 아니므로 maxAttempts 3 이후에도 429가 그대로 전파될 수 있다. 또한 HTTP-date는 파싱 시점의 서버 시계를 기준으로 `Duration`으로 바꾸므로, 우리 서버와 Pyxis 사이의 시계 차이가 크면 실제 대기 시간이 조금 달라질 수 있다.
