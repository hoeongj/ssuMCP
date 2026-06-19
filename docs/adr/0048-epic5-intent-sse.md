# ADR 0048 - EPIC 5: 예약 의도 상태 SSE — Redis RTopic fan-out

- **Status**: Accepted - 2026-06-19 구현
- **Date**: 2026-06-19
- **Scope**: `LibraryIntentStatusBus`, `LibraryIntentSseRegistry`, `LibraryReservationEventListener`

## 배경

예약 대기 등록(`POST /wait`) 후 클라이언트는 현재 상태를 알 수 없다. 기존 `confirm()` 엔드포인트는 8초 폴링 루프(`awaitReservationIntent`)로 결과를 기다리는데, 이는 HTTP 스레드를 블록하고 replicaCount ≥ 2 환경에서 특정 pod의 스레드 풀을 소모한다. 대기 큐(WAITING_FOR_SEAT) 경로는 폴링 루프가 없어 클라이언트가 결과를 알 방법이 없다.

EPIC 5 목표: 의도 상태 전환 시 SSE로 클라이언트에 즉시 push. 다중 pod에서 Redis RTopic으로 fan-out하여 어느 pod가 처리해도 구독 중인 모든 클라이언트에 전달.

## 결정

### D1. 기존 outbox → Spring Event 파이프라인 재사용

`LibraryReservationEventRelay`가 outbox row를 읽어 `LibraryReservationOutboxEvent`를 발행한다. `LibraryReservationEventListener`에서 수신해 `LibraryIntentStatusBus`로 전달. 이미 트랜잭션 커밋 후 발행되므로 추가 transactional outbox가 불필요하다.

### D2. LibraryIntentStatusBus — LibrarySeatEventBus와 동일한 RTopic 패턴

별도 topic(`ssuai.library.intent-status.v1`)을 쓴다. seat 이벤트와 intent 이벤트는 소비자가 다르고 메시지 구조도 달라 같은 topic을 공유하면 역직렬화 오류 위험이 있다.

### D3. LibraryIntentSseRegistry — intentId 키 emitter 맵

`LibrarySeatSseRegistry`와 동일한 구조. floor 대신 intentId로 키를 사용. terminal 이벤트(RESERVATION_SUCCEEDED, RESERVATION_FAILED, CANCELLED, EXPIRED) 수신 시 emitter를 complete()하고 맵에서 제거.

### D4. 엔드포인트: GET /api/library/reservations/wait/events/{intentId}

세션 인증 필요(`requireLibrarySession`). SseEmitter 반환. 클라이언트가 intentId를 알고 있어야 하므로 `POST /wait` 응답에서 intentId를 받아 연결한다.

## 대안 검토

- **클라이언트 폴링 GET /wait/current**: 구현 없음, 클라이언트 부담. N 클라이언트 × 폴링 주기 = N× DB 쿼리. SSE 대비 인터뷰 가치 낮음.
- **WebSocket**: 양방향 불필요. 단방향 push면 SSE로 충분. Spring MVC SSE와 달리 별도 WebSocket 설정 필요.
- **Redis Stream**: 소비자 그룹 관리 복잡. RTopic fan-out이 pod별 로컬 push에 더 적합.

## 포트폴리오 포인트

다중 pod 환경에서 특정 pod가 처리한 결과를 다른 pod의 SSE 구독자에게 전달하는 구조. Redis pub/sub + SseEmitter + 트랜잭셔널 outbox 세 레이어가 어떻게 연결되는지 면접에서 설명 가능.

**예상 면접 질문:**
1. "여러 pod가 뜨는 환경에서 어떻게 클라이언트에 실시간 알림을 보냈나요?"
2. "SSE와 WebSocket 중 SSE를 선택한 이유는?"
3. "Redis pub/sub을 사용할 때 메시지 유실 가능성은 어떻게 처리했나요?"
