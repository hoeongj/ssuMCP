# ADR 0046 — 잡설계 robustness 폴리시 (⑦): 파라미터 경계검증 · 도서 썸네일 절대경로

- 상태: 채택 (2026-06-19)
- 관련: 외부 리뷰 후속 ⑦ 묶음

## 배경

외부 리뷰가 모아준 잡다한 견고성/포맷 개선 묶음(⑦). 이번엔 그중 **contained + 안전**한 항목만 처리하고, 침습적인 항목은 명시적으로 보류한다.

## 결정 (이번 PR에 포함)

1. **주간 메뉴 weekOffset 경계 클램프** — `get_meal_weekly(weekOffset)`가 값을 `LocalDate.plusWeeks()`에 그대로 넘겨, `999999` 같은 값이 들어오면 말도 안 되는 날짜/오버플로가 났다. `clampWeekOffset`로 `[-8, 8]`에 클램프(null→0).
2. **도서 썸네일 절대경로화** — Pyxis가 `thumbnailUrl`을 상대경로(`/thumbnails/x.jpg`)로 줄 수 있어 클라이언트가 못 쓴다. `absoluteUrl(url, baseUrl)`로 상대경로만 base URL(`oasis.ssu.ac.kr`) 접두. 이미 절대(http/https)·blank·null은 그대로(안전 가드).
3. **target_seat_id 양수 가드** — 기존엔 숫자 형식만 검증했다. `0`/음수는 유효 좌석이 아니므로 `parseSeatId`에서 거부(메시지로 구분).

테스트 가능하게 세 헬퍼를 package-private static으로 두고 단위테스트 추가.

## 검토한 대안과 기각/보류

- **학사일정 기간형 startDate/endDate (9)** — **보류.** `AcademicCalendarEvent`(public record)에 컴포넌트를 추가하면 Real/Mock 커넥터·서비스·MCP 도구·ToolResultCompactor·테스트 등 다수 호출부가 깨지고, 원문에서 날짜 **범위 파싱** 로직이 필요하다. 이번 폴리시 PR로는 침습/위험이 커서 별도 작업으로 분리(전용 PR).
- **전역 날짜 ISO-8601 통일** — 보류(광범위·교차절단). 학사일정 범위 작업과 함께 다루는 게 맞다.
- **빈결과 신호 / 공지 compact 응답** — ADR 0045(응답 메시지 분리)와 응답 엔벨로프가 겹쳐 충돌 위험. 별도.
- **좌석 wait 자동예약 안전장치, 식단 closures 코너 스키마** — 설계급(철칙2). 이 PR 범위 아님.

## 작동

- `MealMcpTools.clampWeekOffset`, `RealLibraryBookConnector.absoluteUrl`, `LibraryWaitMcpTool.parseSeatId` — 순수 함수, 단위테스트로 검증.
- 모두 **순수 추가/방어** 변경이라 정상 입력 경로의 동작은 불변.
