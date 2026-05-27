# ADR 0004 — MCP tool layer 입력 검증

- **Status**: Accepted (retroactive — 이미 구현되어 머지됨, 관련 commit: `b6051af`)
- **Date**: 2026-05-07
- **Scope**: `com.ssuai.domain.mcp.tool.MealMcpTools`, `com.ssuai.domain.mcp.tool.CampusMcpTools`

## Context

MCP inspector 로 `get_meal_by_date` 에 빈 입력을 넣었을 때 client 에 다음
메시지가 그대로 전달됐습니다.

```text
Text '' could not be parsed at index 0
```

원인은 단순했습니다. `LocalDate.parse("")` 가 던지는
`DateTimeParseException.getMessage()` 가 Spring AI MCP server 를 통해 raw
message 로 propagate 됐습니다. error 변환 자체는 정상 동작이지만, 이
메시지는 LLM 이나 한국어 사용자에게 친절하지 않습니다.

MCP tool 의 caller 는 대개 LLM 입니다. LLM-facing API 에서는 실패 메시지가
다음 호출을 고치는 힌트가 됩니다. "어떤 field 가 어떤 형식이어야 하고,
받은 값이 무엇이었는지" 가 메시지에 들어가야 모델이 바로 재시도할 수
있습니다.

## Decision

입력 검증 위치는 tool layer 로 둡니다. `com.ssuai.domain.mcp.tool` 안의
tool method 가 LLM-facing boundary 이므로, 여기서 null / blank / format /
length 를 명시적으로 확인합니다. Service 는 도메인 로직만 책임지고
LLM-facing 문구를 알지 않게 합니다.

예외 타입은 일반 `IllegalArgumentException` 을 사용합니다. Spring AI 가
`getMessage()` 를 client 로 전달하므로 별도 custom exception 을 만들
필요가 없습니다.

메시지는 한국어로 작성합니다. 형식은 다음 패턴을 따릅니다.

```text
<항목>: <조건>. 예: <good example>. 받은 값: '<actual>'.
```

받은 값이 너무 길면 `displayValue()` 로 잘라 `...` 를 붙입니다. error
message 자체가 과도하게 길어져 MCP client UI 를 망가뜨리거나 prompt 에
불필요하게 긴 값을 넣는 것을 막기 위함입니다.

적용 범위는 두 tool 입니다. `get_meal_by_date` 는 null, blank, yyyy-MM-dd
형식 위반을 검증합니다. `search_campus_facilities` 는 query 64자 초과를
거부합니다. `@ToolParam(description=...)` 에도 입력 형식과 길이 제한을
명시합니다.

## Consequences

**좋은 점**
- LLM 이 잘못된 호출 후 메시지를 보고 바로 다음 호출을 정정할 수 있습니다.
- Service 는 LLM-facing 책임 없이 순수 도메인 로직을 유지합니다.
- raw Java exception message 가 사용자에게 노출되는 일을 줄입니다.
- "raw exception → 친절 메시지" 정책 자체가 portfolio 에서 설명 가능한 구현 narrative 가 됩니다.

**대가**
- REST controller 의 `MealController.resolveStartDate` 와 MCP tool 검증이 일부 중복됩니다.
- REST 는 envelope 의 `error.message`, MCP 는 exception message 라 표면별 메시지 형식이 미세하게 달라질 수 있습니다.
- tool 이 늘어날수록 boundary 검증을 빠뜨리지 않도록 테스트가 필요합니다.

## Alternatives considered

- **Service layer 검증** — 도메인 코드에 LLM-facing 책임이 섞이고, 표면별 메시지 정책을 다르게 가져가기 어렵습니다.
- **Spring AI 전용 custom exception 사용** — `IllegalArgumentException` 으로 충분히 목적을 달성해 추가 타입을 만들지 않았습니다.
- **`@ToolParam(required=true)` 만 의존** — required 위반 일부는 처리되지만 yyyy-MM-dd 형식 검증과 query length 제한까지 보장하지 못합니다.
