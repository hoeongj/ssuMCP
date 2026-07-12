# ADR 0083 — 예약 worker 결정 시점 fresh read

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-07-10 |
| 상태 | Accepted — 구현 |
| 범위 | `domain.library.reservation.intent`(`LibraryReservationWorker`·`LibraryReservationSeatSelector`), `domain.library.recommendation.LibrarySeatCatalogService` |
| 연관 ADR | [0022](0022-library-reservation-intent-queue.md)(intent queue), [0059](0059-reservation-audit-single-source-of-truth.md)(worker가 예약 감사의 단일 진실원천), [0080](0080-multipod-shared-ratelimit-dualcap.md)(Pyxis read/write dual cap) |
| 후속 결정 | [0097](0097-pyxis-read-cap-fanout-sizing.md)에서 read cap을 cluster 20/s, per-user 8/s로 조정했다. |

---

## 배경 — 무슨 문제

예약 worker는 대기 intent를 claim한 뒤 `LibraryAvailableSeatsService`의 좌석 가용성 스냅샷에서 예약할 좌석을 고른다. 이 스냅샷은 웹 좌석 조회와 공유되는 캐시 계층을 타기 때문에, 시험 기간처럼 좌석이 빠르게 소진되는 시간대에는 최대 캐시 TTL 동안 이미 사라진 좌석이 "available"로 보일 수 있다.

문제는 stale read 자체보다 그 다음 단계다. 기존 worker는 stale 스냅샷에서 고른 좌석을 곧바로 Pyxis 예약 write로 보냈다. 여러 wait intent가 같은 30초 전 스냅샷을 보면 모두 같은 좌석을 고르고, 이미 타인이 가져간 좌석에 대해 `reserve`를 호출한다. Pyxis write cap은 ADR 0080 기준 cluster 2/s라서, 실패가 확정된 write가 초당 2개씩 예산을 먹는다.

산수로 보면 더 명확하다. 같은 stale 좌석을 30명이 고르면, 기존 구조는 같은 tick grouping에 걸리지 않는 시도마다 Pyxis write를 소모한다. write cap 2/s에서는 실패 30건을 소진하는 데 이론상 15초가 걸리고, 그동안 정상적으로 성공할 수 있는 다른 예약 write가 뒤로 밀린다. 결과는 `FAILED_RACE` 폭풍과 쓰기 예산 낭비다.

## 검토한 대안

### 캐시 TTL 단축

캐시 TTL을 줄이면 stale 창은 작아진다. 하지만 웹 좌석 조회, 추천, sampler까지 같은 read budget을 공유하므로 cold miss가 늘고 Pyxis read cap(ADR 0097 기준 cluster 20/s, per-user 8/s)을 더 자주 친다. TTL을 0에 가깝게 줄여도 "조회 직후 타인이 예약"하는 race는 남는다. 쓰기 직전의 최종 판단 문제를 캐시 정책으로 해결할 수 없다.

### 예약 실패 시 전체 캐시 무효화

`FAILED_RACE`가 나오면 관련 캐시를 무효화하는 방식도 검토했다. 하지만 첫 실패 write는 여전히 필요하고, 여러 worker/pod가 동시에 stale 스냅샷을 들고 있을 때는 무효화 전후 순서가 섞인다. 또한 전체 room/all snapshot 무효화는 웹 조회자에게도 cold miss를 강제해 read 트래픽을 넓게 흔든다.

### 결정 시점 fresh 조회

채택. worker가 write를 보내기 직전, 후보 좌석이 속한 열람실만 `GET /pyxis-api/1/api/rooms/{roomId}/seats`로 fresh read한다. 이 read는 `LibraryRoomSeatCache`와 Redis L2를 거치지 않고 `LibrarySeatConnector`를 직접 호출하므로 기존 웹 조회 캐시 동작은 그대로 둔다.

## 선택

예약 decision은 이제 `seatId + roomId`를 함께 들고 이동한다.

1. 캐시 기반 selector는 기존처럼 대기 조건에 맞는 후보를 고르되, 후보 좌석의 roomId도 같이 반환한다.
2. 즉시 예약 intent는 저장된 target seat ID만 있으므로 seat catalog에서 roomId를 역조회한다. roomId를 못 찾으면 fresh 검증을 할 수 없으므로 blind write 대신 upstream failure로 끝낸다.
3. worker는 후보 seat lock을 잡은 뒤 fresh room read를 수행한다.
4. fresh read에서 후보가 여전히 available이면 그때만 Pyxis `reserve` write를 호출한다.
5. 후보가 사라졌고 target-specific intent가 아니면, 같은 fresh room 데이터에서 조건에 맞는 다른 좌석을 고른다.
6. 다른 좌석으로 retarget되면 기존 seat lock을 해제하고 새 seat lock을 다시 잡은 뒤 fresh read를 반복한다. 새 좌석을 lock 없이 예약하지 않는다.
7. 재선택은 최대 2회로 제한한다. 초기 후보 + 최대 2개 대체 후보까지만 확인하고, 계속 바뀌면 `FAILED_RACE`로 끝낸다.

## fresh 조회 예산 영향

fresh 조회 단위는 "전체 좌석 snapshot"이 아니라 "후보가 속한 room"이다. 현재 Pyxis에는 seat 하나만 읽는 connector seam이 없고, 이미 검증된 가장 좁은 API가 `rooms/{roomId}/seats`다. 따라서 후보 room read가 최소 실용 단위다.

read budget 영향은 intent 하나당 최악 초기 후보 1회 + 재선택 2회 = 3 room reads다. ADR 0097의 현재 read cluster cap 20/s 기준으로, 포화 상태에서 intent 하나가 최대로 점유하는 read 슬롯은 3개다. 대신 fresh read가 "이미 사라진 좌석"을 감지하면 write는 0회다. 기존 구조에서 같은 상황은 write cap 2/s를 실패 write로 태웠으므로, 병목 자원을 더 비싼 write budget에서 더 싼 read budget으로 옮기는 선택이다.

같은 worker tick에서 stale 후보가 같은 intent들은 기존 same-seat grouping이 먼저 하나의 winner만 실행하고 나머지는 local `FAILED_RACE`로 접는다. 따라서 동일 tick 중복은 여전히 write/read를 증폭하지 않는다. tick을 넘어 stale 후보가 반복되는 경우에도 각 winner는 fresh room read 후 taken이면 write를 호출하지 않는다.

## 트레이드오프

- **read 비용 증가**: 성공 예약도 write 직전에 room read 1회를 추가로 쓴다. 하지만 예약 write는 2/s로 더 좁고 비멱등이라, doomed write를 줄이는 편이 더 중요하다.
- **fresh read 이후에도 race 가능**: fresh read와 write 사이에 타인이 먼저 예약할 수 있다. 이 경우 기존처럼 Pyxis write가 `FAILED_RACE`로 끝난다. 이번 결정은 "이미 fresh read 시점에 사라진 좌석"에 대한 확정 실패 write를 제거하는 것이다.
- **room catalog 의존**: 즉시 예약의 target seat은 catalog로 roomId를 찾아야 한다. catalog에 없는 좌석은 blind write하지 않고 upstream failure로 끝낸다. 운영상 catalog 누락은 별도 데이터 품질 문제로 다룬다.
- **재선택 범위 제한**: full snapshot을 새로 읽지 않으므로 다른 room에 빈 좌석이 있어도 현재 후보 room에서 못 찾으면 실패할 수 있다. 이는 read budget 보호를 위해 의도적으로 선택한 제한이다.

## 예상 면접 질문

1. **왜 캐시 TTL을 줄이지 않고 write 직전 fresh read를 넣었나?** — TTL 단축은 stale 확률만 낮추고 read cap을 계속 압박한다. write 직전 fresh read는 reserve 바로 앞의 의사결정 품질을 높이고, 이미 실패가 확정된 non-idempotent write를 제거한다.
2. **왜 전체 좌석 snapshot이 아니라 room read인가?** — 후보 좌석이 속한 room만 확인하면 decision에 필요한 정보가 충분하다. 전체 7개 room을 fresh로 읽으면 intent 하나가 read budget을 크게 잡아먹어, 시험 기간에는 read cap 자체가 새 병목이 된다.
3. **fresh read 후에도 race가 나면 이 변경은 무슨 의미가 있나?** — fresh read와 write 사이의 진짜 race는 피할 수 없고 기존 `FAILED_RACE` 경로가 처리한다. 이번 변경은 stale 캐시 때문에 이미 taken인 좌석에 쓰기를 보내는 확정 실패를 제거해 write budget을 보호하는 데 의미가 있다.
