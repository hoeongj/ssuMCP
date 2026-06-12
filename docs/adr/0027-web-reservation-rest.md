# ADR 0027 - 도서관 예약 REST API 추가

- **Status**: Accepted
- **Date**: 2026-06-12
- **Scope**:
  - `com.ssuai.domain.library.reservation.web.LibraryReservationWebController`
  - `LibraryReservationPrepareRequest/Response`
  - `LibraryReservationWaitWebRequest`
  - `LibraryReservationConfirmResponse`

## 배경

기존 도서관 예약 UX는 MCP 도구(`prepare_reserve_library_seat`, `prepare_cancel_library_seat`, `prepare_swap_library_seat`, `confirm_action`, `wait_for_library_seat`)를 통해서만 완성할 수 있었다. 이 구조는 Claude Desktop 같은 MCP 클라이언트에는 적합하지만, ssuAI 프론트엔드가 브라우저 안에서 바로 예약 흐름을 구현하기에는 프로토콜/세션/CORS 구조가 지나치게 무겁다.

이번 작업의 목적은 도서관 예약을 웹 REST API로도 노출해서, 프론트엔드가 기존 MCP 흐름과 동일한 백엔드 상태머신을 사용하면서도 직접 UI를 만들 수 있게 하는 것이다.

## 검토 대안

- **MCP 도구를 프론트에서 직접 호출**
  - 탈락: MCP 프로토콜은 브라우저 네이티브 호출 모델이 아니고, 인증/세션/재연결을 프론트에서 직접 다루면 구현 복잡도가 커진다.
- **별도 서비스 레이어를 신규 작성**
  - 탈락: `ActionService`, `LibraryReservationIntentTransactions`, `LibraryReservationConnector`, `LibrarySeatEventPublisher`가 이미 준비되어 있어 중복 서비스는 DRY를 해친다.
- **채택: REST 컨트롤러가 기존 서비스 레이어를 직접 호출하는 thin controller 패턴**
  - 채택 이유: prepare/confirm/wait의 핵심 상태 전이는 이미 검증된 서비스에 있고, 웹 계층은 입력 파싱과 응답 포맷만 담당하면 된다.

## 선택 근거

- 기존 MCP와 웹이 같은 `ActionService`와 `LibraryReservationIntentTransactions`를 사용하므로 예약 중복 제거와 상태 일관성을 공유할 수 있다.
- 웹 컨트롤러가 서비스 로직을 직접 호출하면 테스트가 단순해지고, MCP와 웹의 동작 차이를 최소화할 수 있다.
- `GlobalExceptionHandler`가 `LibraryAuthRequiredException`과 `LibrarySeatNotAvailableException`을 일관된 API 에러로 변환하므로, 프론트는 MCP와 웹을 같은 에러 코드 체계로 처리할 수 있다.

## 동작 원리

1. 웹 요청은 `HttpServletRequest.getSession().getId()`를 `sessionKey`로 사용한다.
2. `LibrarySessionStore.has(sessionKey)`로 라이브러리 로그인 여부를 확인한다.
3. 준비/확정 로직은 기존 MCP와 동일한 서비스 흐름을 호출한다.
4. `LibraryReservationIntentTransactions.registerWait(sessionKey, request)`는 기존과 동일하게 `studentId = sessionKey` 규칙을 유지해서 MCP와 웹이 같은 활성 intent 1개 dedup을 공유한다.
5. `confirm`는 `ActionService.findPendingAction` -> `claimPendingAction` -> 액션 타입별 실행 순서를 따르고, reserve는 `createImmediateReservation(..., ActionService.ACTION_TTL)`로 intent 기반 확정 경로를 유지한다.

## 구현 레벨 선택

### 직접 서비스 호출

웹 컨트롤러가 `ActionService`, `LibraryReservationConnector`, `LibraryReservationIntentTransactions`, `LibrarySeatEventPublisher`를 직접 주입받는다.

### MCP 툴 재사용은 제외

`ConfirmActionMcpTool` 같은 MCP 툴을 Spring Bean으로 재사용하는 방법도 있었지만, 그 방식은 웹 계층이 MCP 전용 응답 타입과 인증 헬퍼에 의존하게 만들어 의존성 방향이 뒤집힌다. 또한 MCP는 프롬프트/응답 포맷이 섞여 있어 웹 API의 단순한 JSON 계약과는 목적이 다르다.

그래서 웹은 MCP 툴을 호출하는 방식이 아니라, 같은 하위 서비스 레이어를 직접 호출하는 방식으로 구현했다.

## 결과

- 프론트엔드가 `/api/library/reservations/*`로 예약 준비, 확정, 대기열 등록/조회/취소, 현재 좌석 조회를 직접 수행할 수 있다.
- MCP와 웹이 같은 예약 상태 전이와 intent dedup을 공유한다.
- 예외는 기존 글로벌 API 에러 코드 체계로 반환된다.
