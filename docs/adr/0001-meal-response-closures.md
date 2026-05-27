# ADR 0001 — `MealResponse` 에 `closures` 필드 추가

- **Status**: Accepted (이미 구현되어 머지됨 — `f54ba70`)
- **Date**: 2026-05-07
- **Scope**: `com.ssuai.domain.meal.dto.MealResponse`

## Context

Task 03 (`docs/tasks/03-meal-real-connector.md`) 의 §"Contracts" 는
`MealResponse(LocalDate date, List<MealItem> meals)` 만 정의했습니다.

실제 구현 시 `RealMealConnector` 가 어린이날 등 휴무 식당을 만났습니다.
이를 표현할 자리가 record 에 없었기 때문에 두 가지 선택이 있었습니다:

1. 휴무도 `MealItem.menu` 에 `["휴무"]` 식으로 끼워 넣기.
2. 별도 `closures: List<MealClosure>` 필드 추가.

옵션 1 은 클라이언트가 "메뉴인지 휴무 사유인지" 를 문자열 매칭으로 판별해야
해서 깨지기 쉽습니다. 옵션 2 를 선택했습니다.

## Decision

`MealResponse` 에 `closures: List<MealClosure>` 를 추가합니다.

```java
public record MealResponse(
        LocalDate date,
        List<MealItem> meals,
        List<MealClosure> closures
) {
    public MealResponse(LocalDate date, List<MealItem> meals) {
        this(date, meals, List.of());
    }
}
```

기존 `(date, meals)` 보조 생성자를 남겨, mock connector 와 단위 테스트의
호출부 변경을 최소화합니다.

`MealClosure(restaurant, reason)` 는 dto 패키지에 신규 record 로 추가.

## Consequences

**좋은 점**
- 클라이언트가 "휴무" 와 "정상 메뉴" 를 구조적으로 분리해서 받습니다.
- mock 응답은 여전히 `closures: []` 로 떨어져 호환 깨짐 없음.

**대가**
- Task 03 spec 의 `Contracts` 섹션과 실제 응답이 한 칸 다릅니다 — 본 ADR
  로 그 갭을 명시적으로 메웁니다.
- MCP tool wrapping 단계에서 tool schema 에도 `closures` 가 들어가야 함
  (`docs/mcp-tools.md` 갱신 필요 — Task 04 또는 별도 ticket).

## Update — connector 분해 후 (P3, 2026-05-07)

`RealMealConnector` 가 단일 식당 단위로 축소되고 `MealService` 가 6식당
fan-out 을 책임지게 되면서, `closures` 의 의미가 한 단계 확장됐습니다:

- 기존: "외부 사이트가 보고한 휴무 사유" (예: `오늘은 쉽니다.`, `어린이날`)
- 추가: "조회 자체에 실패한 식당" — `MealService` 가 `ConnectorException`
  을 잡으면 해당 식당을 `MealClosure(restaurant, "조회 실패: " + ErrorCode)`
  로 흡수해 같은 `closures` 리스트에 담음.

이렇게 통합한 이유:
- 클라이언트 입장에서는 두 경우 모두 "이 식당 메뉴를 표시할 수 없는 사유"
  로 동일하게 처리 가능 (UX 도 같음 — "오늘은 메뉴 정보 없음").
- 별도 `errors` 필드로 분리하면 record 모양이 또 한 번 바뀌어 client·MCP
  schema 유지 비용이 올라감.

대신 `reason` 문자열 prefix `"조회 실패: "` + `ErrorCode` 이름으로
프로그램적 구분을 가능하게 둡니다. 향후 클라이언트가 둘을 구별해 표시
하고 싶다면 그 prefix 를 키로 분기하면 됩니다.

**전부 실패** (모든 식당이 `ConnectorException`) 케이스만은 흡수하지 않고
마지막 예외를 그대로 throw — 운영자에게 5xx 신호가 가야 하기 때문.

## Alternatives considered

- **`MealItem.status: enum { OPEN, CLOSED }` 추가** — 끼니 단위로 휴무를
  표현할 수 있지만, "조식만 휴무" 같은 케이스가 현재 데이터에 없고 식당
  단위 휴무가 더 자연스러워 채택하지 않음.
- **HTTP 상태 코드로 표현** — 부분 휴무를 5xx 로 못 보냄. envelope 형식
  유지가 우선.
