# ADR 0028 - Seat Catalog Room-Scoped Index

- **Status**: Accepted
- **Date**: 2026-06-13
- **Scope**:
  - `LibrarySeatCatalogService`
  - `LibraryReservationSeatSelector`

## 배경

`LibrarySeatCatalogService`는 Pyxis `externalSeatId`를 사용자에게 보이는 좌석 번호(`seatId`/`label`)와 정적 좌석 메타데이터로 해석한다. 기존 구현은 `Map<String, LibrarySeatCatalogEntry>` 하나에 모든 열람실의 `externalSeatId`를 넣고, 중복 키는 first-wins 병합으로 버렸다.

운영 카탈로그에는 `externalSeatId` 33개가 2F 숭실스퀘어ON과 5F 숭실멀티라운지에 동시에 등장한다. 이 값들은 같은 열람실 안에서 중복된 데이터가 아니라 서로 다른 Pyxis room 안의 좌석이다. 전역 first-wins 인덱스는 5F 항목을 조용히 떨어뜨리므로, roomId를 알고 있는 예약 대기 경로가 잘못된 방의 속성으로 좌석 필터를 판단할 수 있었다.

## 대안

### 1. upstream/static data에서 externalSeatId를 전역 deduplicate

거절했다. 중복 자체가 오류라는 근거가 없다. 현재 중복 33건은 서로 다른 roomCode에 걸쳐 있고, Pyxis per-room seat endpoint가 `roomId`를 경계로 동작하므로 전역 deduplicate는 실제 모델을 잃는다.

### 2. room-scoped index + global fallback

채택했다. `roomId -> externalSeatId -> catalog entry` 구조는 Pyxis API 경계와 맞고, 이미 `LibraryReservationSeatSelector`는 live availability loop에서 `roomId`를 갖고 있다. 동시에 `LibrarySeatEventSeatResolver`, `SeatDisplay`처럼 roomId 없이 externalSeatId만 받는 경로가 남아 있으므로 1-argument fallback을 유지하고 ambiguous lookup은 WARN으로 드러낸다.

### 3. 모든 caller에 roomId를 강제하고 fallback 제거

거절했다. 이벤트 발행 fallback은 externalSeatId를 이용해 roomId를 찾는 역할이라 roomId를 먼저 받을 수 없다. 모든 경로에 roomId를 강제하면 순환 의존적인 lookup이 생기거나 호출부가 추측값을 만들게 된다.

## 선택

room-scoped index를 기본 경로로 채택하고, roomId가 없는 경로에는 전역 fallback을 남긴다. fallback은 같은 `externalSeatId`가 여러 room에서 발견되면 WARN 로그를 남기고 첫 번째 매치를 반환한다.

5F 멀티라운지의 정적 seat catalog roomCode는 `multi-lounge-5f`이고, room catalog는 같은 98석 열람실을 `pc-multi-zone-5f`/roomId 60으로 가지고 있다. public room catalog 출력은 바꾸지 않고, seat catalog index builder에서 이 alias만 roomId 60으로 정규화한다.

## 동작 방식

1. `LibrarySeatCatalogService`가 `seat-catalog.json`과 `seat-room-catalog.json`을 함께 로드한다.
2. room catalog에서 `roomCode -> roomId` 맵을 만들고, `multi-lounge-5f -> pc-multi-zone-5f` alias를 적용한다.
3. seat catalog를 순회하며 `Map<Integer, Map<String, LibrarySeatCatalogEntry>> entriesByRoomAndExternalSeatId`를 만든다.
4. `findByExternalSeatId(externalSeatId, roomId)`는 해당 room map 안에서만 좌석을 찾는다.
5. `findByExternalSeatId(externalSeatId)`는 모든 room map을 검색하고, 여러 room에서 발견되면 WARN을 남긴다.
6. `LibraryReservationSeatSelector`는 `LibraryAllAvailableSeatsRoomSummary.roomId()`를 이미 가지고 있으므로 2-argument lookup을 사용한다.

## 검증

- `roomScopedLookup_findsCorrectRoom`: `externalSeatId=3423`이 roomId 53에서는 숭실스퀘어ON 1번, roomId 60에서는 숭실멀티라운지 66번으로 해석되는지 검증한다.
- `globalFallback_logsWarnOnAmbiguous`: roomId 없는 fallback이 ambiguous lookup에서 WARN을 남기는지 검증한다.
- `allDuplicates_areCrossRoom`: 중복 `externalSeatId` 33건이 모두 서로 다른 roomCode에만 걸쳐 있음을 검증해 same-room duplicate를 데이터 오류로 잡는다.
