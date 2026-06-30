# ADR 0064 — 웹 좌석 swap 보상(compensation) 경로

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-23 |
| 상태 | Accepted — 구현(브랜치 `fix/library-web-swap-compensation`) |
| 범위 | `LibraryReservationWebController.executeSwap`(+`compensateSwap`/`partialSwapFailure`) |
| 연관 | `ConfirmActionMcpTool.compensateSwap`(MCP 경로 원본 보상 로직) · 내부 분석에서 발견 |

---

## 배경 — 무슨 문제

좌석 이석(swap)은 upstream(Pyxis)에 **원자적 swap API가 없어** 2단계로 구현된다: ① 기존 좌석 discharge(반납) → ② 새 좌석 reserve(예약). MCP 경로(`ConfirmActionMcpTool`)는 ②가 실패하면 **기존 좌석을 재예약하는 보상(compensation)** 으로 사용자 상태를 복구한다(ADR · 외부 리뷰 #12). 그러나 **웹 컨트롤러(`LibraryReservationWebController.executeSwap`)에는 그 보상이 없었다** — ① 성공 후 ② 실패 시 `FAILED_RACE`/`FAILED_UPSTREAM`만 반환하고 끝. 결과: 사용자가 **기존 좌석도 잃고 새 좌석도 못 얻는** 실제 데이터 손실. "코어/MCP엔 적용했는데 형제 경로(웹)엔 미이식"의 전형.

## 결정

웹 `executeSwap`에 MCP와 **동일한 보상 로직**을 이식한다(중복이지만 두 경로가 같은 안전 보장을 갖도록; 공용 executor 추출은 호출 컨텍스트/응답타입이 달라 후속).

- ② reserve(new) 실패(`LibrarySeatNotAvailableException`·`RuntimeException` 양쪽) → `compensateSwap`: 기존 좌석(`oldSeatId`) **재예약 시도**.
- 보상 성공 → 감사 `OUTCOME_FAILURE_RACE` + `swapReserve(old)` 이벤트 재발행(discharge가 이미 좌석맵에서 비웠으므로) + 사용자에게 "기존 좌석 유지됨" 안내(status `FAILED_RACE`).
- 보상 실패 → 감사 `OUTCOME_PARTIAL_FAILURE` + "현재 보유 좌석 없음, 재예약 필요" 안내(status `FAILED_UPSTREAM`), warn 로깅(운영 가시성).
- `oldSeatId`이 null인 방어 케이스 → 곧장 partial-failure.

## 대안과 기각 이유

- **공용 swap executor로 MCP·웹 단일화** — 가장 깔끔하나 MCP는 `McpPrivateToolResponse<String>`(한국어 LLM 메시지), 웹은 `LibraryReservationConfirmResponse`(status 코드)로 응답·메시지 체계가 다르고 세션/감사 처리도 미묘하게 달라, 한 번에 추출하면 회귀 위험이 큼. **지금은 로직 미러**(동등 안전성 확보)하고 공용화는 후속 리팩터로 분리.
- **보상 없이 명확한 에러만** — 사용자가 좌석을 잃는 실제 피해를 방치 → 기각.
- **응답에 새 status 코드 추가** — 프론트(`ReservationConfirmModal`)가 `SUCCESS`/`PROCESSING` 외 전부를 "실패+메시지 표시"로 처리하므로, 기존 `FAILED_RACE`/`FAILED_UPSTREAM` 재사용이 안전(타입 드리프트·프론트 변경 0). 사용자 구분은 **메시지**로 전달.

## 동작 방식 / 검증

- 단위테스트(@WebMvcTest, 신규 — 기존엔 swap 경로 테스트가 0이라 갭이 숨어 있었음): ① reserve(new) 실패 → reserve(old) 성공 → status `FAILED_RACE` + 보상 reserve 호출 + `OUTCOME_FAILURE_RACE` 검증. ② reserve(new)·reserve(old) 둘 다 실패 → status `FAILED_UPSTREAM` + `OUTCOME_PARTIAL_FAILURE` 검증.

## 예상 면접 질문

1. **"원자적 연산이 없는 외부 시스템에서 일관성을 어떻게 지켰나?"** — discharge→reserve 2단계 swap에 보상 트랜잭션(Saga의 compensating action)을 적용. 2단계째 실패 시 1단계를 역연산(기존 좌석 재예약)해 사용자 상태를 복원하고, 복원마저 실패하면 명시적 PARTIAL_FAILURE로 사용자에게 "좌석 없음"을 알림.
2. **"같은 버그가 왜 MCP엔 없고 웹엔 있었나?"** — 동일 도메인 동작이 두 진입점(MCP 도구 / REST 웹)에 중복 구현됐고 보상 로직이 한쪽에만 들어갔다. 분석에서 두 경로를 대조해 갭을 찾았고, 교훈은 "중복 경로는 공용화하거나 최소한 동일 테스트로 양쪽을 묶어라"(웹 경로엔 swap 테스트가 아예 없어 갭이 숨었다).
3. **"보상도 실패하면?"** — PARTIAL_FAILURE outcome으로 감사 기록 + warn 로깅 + 사용자에게 현재 무좌석임을 정직히 안내. 멱등/재시도가 아니라 사용자 결정(재예약)으로 넘긴다.
