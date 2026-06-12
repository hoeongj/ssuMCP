# ADR 0026 - 실시간 좌석 업데이트를 위한 SSE(Server-Sent Events) 및 Redis Pub/Sub 연동

- **Status**: Accepted
- **Date**: 2026-06-12
- **Scope**:
  - `com.ssuai.domain.library.events.LibrarySeatSseRegistry`
  - `com.ssuai.domain.library.controller.LibrarySeatController`
  - `ssuAI/hooks/useLibrarySeatSse.ts`
  - `ssuAI/components/library/LibrarySeatCard.tsx`

## 배경

현재 ssuAI 프론트엔드는 도서관 좌석 현황을 30초 주기의 REST Polling 방식으로 가져오고 있습니다. 하지만 사용자가 좌석 예약, 취소, 이석(swap)을 진행할 때 상태가 즉각적으로 화면에 반영되지 않아 UX 반응성이 떨어집니다. 

이전 작업(ADR 0024)에서 Redis/Redisson을 도입하여 예약/취소/이석 성공 시 `ssuai.library.seat-events.v1` pub/sub 토픽으로 이벤트를 발행하게 설계해 둔 상태입니다. 

본 작업의 목표는 이 Redis 변경 이벤트를 실시간으로 구독해 브라우저까지 연결하는 **Server-Sent Events (SSE) 스트리밍** 파이프라인을 구축하고, 다중 복제본(Scale-out) 운영 환경에서 어느 backend pod로 사용자 요청이 들어가더라도 좌석 상태 변화가 모든 접속자에게 실시간으로 전파(Broadcast)되도록 하는 것입니다.

---

## 결정

### D1. 통신 프로토콜로 Server-Sent Events (SSE) 채택

서버에서 브라우저로 실시간 상태 변화를 푸시하기 위해 WebSocket 대신 **SSE**를 채택합니다.

*   **선정 근거**:
    *   **단방향성 부합**: 좌석 실시간 업데이트는 서버에서 클라이언트로만 이벤트를 전송하는 단방향 흐름입니다. 양방향 전송인 WebSocket은 불필요한 스펙입니다.
    *   **HTTP/2 친화성**: SSE는 표준 HTTP 프로토콜을 그대로 사용합니다. HTTP/2 환경에서는 connection multiplexing이 가능하여 WebSocket처럼 별도의 포트나 프로토콜 핸드셰이크를 요구하지 않고 단일 TCP 연결로 많은 SSE 스트림을 처리할 수 있습니다.
    *   **재연결 지원**: 브라우저 내장 `EventSource`는 네트워크 불안정으로 끊겼을 때 자동으로 재연결(Auto-reconnect) 및 `Last-Event-ID`를 통한 유실 이벤트 복구 메커니즘을 기본 제공합니다.
*   **기각 사유**:
    *   **WebSocket**: 추가적인 Connection keep-alive(ping/pong) 프레임 관리, 프로토콜 헤더 오버헤드, 프런트엔드 재연결 로직 직접 구현 등의 추가 복잡성이 따릅니다.
    *   **Short/Long Polling**: thundering herd 부하가 발생하고 실시간 전달 지연이 생깁니다.

---

### D2. 대역폭 최적화를 위한 층(Floor) 단위 필터링 설계

모든 좌석 변경 이벤트를 모든 커넥션에 브로드캐스트하는 대신, **클라이언트가 조회 중인 층(Floor)으로 필터링하여 이벤트를 송신**합니다.

*   **구현 방식**:
    *   `LibrarySeatSseRegistry`는 `ConcurrentHashMap<Integer, List<SseEmitter>>`를 사용하여 층별(2, 5, 6층) 활성 SSE 커넥션을 맵 형태로 유지합니다.
    *   Redis Topic에서 `LibrarySeatEvent`가 유입되면 해당 이벤트의 `roomId`를 층 코드로 변환하여(예: 53/54 -> 2층) 매핑되는 `SseEmitter` 리스트에만 이벤트를 발송합니다.
    *   이를 통해 유휴 연결 브라우저의 불필요한 네트워크 대역폭 및 React re-render 낭비를 차단합니다.

---

### D3. 안전 장치 및 리소스 누수 방지 (Graceful Degradation & Cleanup)

다중 클라이언트 연결 시 메모리 누수(Memory Leak) 및 스레드 블록킹을 방지하기 위한 안전장치를 구성합니다.

*   **만료 시간 설정**: `SseEmitter`에 30분(`1,800,000ms`) 타임아웃을 지정하여 장시간 미사용 커넥션을 자동으로 정리합니다.
*   **생명주기 콜백**: `onCompletion`, `onTimeout`, `onError` 콜백을 등록하여 연결이 해제되거나 비정상 종료 시 즉시 레지스트리 맵에서 제거합니다.
*   **초기 연결 신호**: 연결 직후 `"connect"` 이벤트를 발송해 브라우저가 프록시(Nginx 등)의 Read Timeout에 의해 연결이 끊기지 않게 방어합니다.
*   **예외 격리**: 특정 클라이언트 송신 실패(`IOException` 등)가 발생해도 다른 Emitter 전송이나 애플리케이션 전체에 영향을 주지 않도록 루프 내에서 예외를 캐치해 대상 Emitter만 정리합니다.

---

### D4. 프론트엔드 SSR 및 테스트 환경 방어

Next.js 환경에서 Server-Side Rendering(SSR) 시점이나 Vitest (JSDOM) 유닛 테스트 시점에는 브라우저 API인 `EventSource`가 정의되지 않아 `ReferenceError`가 발생합니다.

*   **해결 방식**:
    *   `useLibrarySeatSse` 훅 내부에서 `typeof window === "undefined" || typeof EventSource === "undefined"` 조건을 체크하여, 브라우저 환경이 아닐 경우 EventSource 인스턴스 생성을 조기 반환(Early Return)하게 격리합니다.
    *   이를 통해 Next.js SSR 기동과 Vitest 테스트 프레임워크가 오류 없이 정상 동작하게 보장합니다.

---

## 동작 요약

```text
좌석 상태 변경 (reserve/cancel/swap)
  -> Redis Pub/Sub 토픽 발행 (ssuai.library.seat-events.v1)
  -> ssuMCP 각 Replica의 LibrarySeatSseRegistry 이벤트 감지
  -> roomId를 기준으로 층(floorCode) 식별
  -> floorEmitters 맵에서 해당 층에 연결된 SseEmitter 리스트 조회
  -> 각 SseEmitter에 JSON 포맷 이벤트 전송
  -> 브라우저 useLibrarySeatSse 훅 수신
  -> React Query queryClient.invalidateQueries(["library", "seats", floor]) 호출
  -> REST API 즉시 재조회 및 화면 갱신
```

## 관측 지표 및 설정

*   기존 Redis metrics 계층에 실패율 및 커넥션 모니터링 로그 기록 연동.
*   `SSUAI_REDIS_HOST` 및 `SSUAI_LIBRARY_SEAT_EVENT_CHANNEL` 설정을 통해 동적으로 토픽 채널 및 서버 연결 오버라이드 가능.
