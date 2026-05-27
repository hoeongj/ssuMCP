# ADR 0005 — REST 와 MCP 의 error UX 책임 분리

- **Status**: Accepted (retroactive — 이미 구현되어 머지됨, 관련 commit: `b6051af`, `1a2c9bf`)
- **Date**: 2026-05-07
- **Scope**: `com.ssuai.global.exception.GlobalExceptionHandler`, `com.ssuai.domain.mcp.tool.ConnectorErrorMessages`, MCP tool 클래스들

## Context

ssuAI 는 같은 도메인 Service 를 REST 와 MCP 두 표면에서 공유합니다.
architecture §11 의 의도도 MCP tool 이 Service layer 를 우회하지 않고 REST
controller 와 같은 business logic 을 사용하게 하는 것입니다.

문제는 같은 도메인 예외라도 client 에 보여줄 모양이 표면마다 다르다는
점입니다. REST client 는 `ApiResponse<ErrorResponse>` envelope, 안정된
`error.code`, HTTP status code 를 기대합니다. 예를 들어 connector timeout 은
`CONNECTOR_TIMEOUT` 과 504 로 표현되는 것이 좋습니다.

MCP client 의 주 caller 는 LLM 입니다. 이 표면에서는 enum code 만 전달하는
것보다 "외부 사이트 응답이 지연되어 학식 정보를 가져오지 못했습니다" 같은
자연어 안내가 다음 응답 생성과 사용자 설명에 더 직접적으로 유용합니다.

따라서 책임을 어디에 둘지 결정해야 했습니다. Service 가 표면 무관한
사용자 메시지를 만들지, REST 와 MCP 각각의 boundary layer 가 자기 표면의
error UX 를 변환할지 선택이 필요했습니다.

## Decision

각 표면이 자기 표면의 error UX 를 책임집니다. Service 와 Connector 는
표면을 모르고 도메인 예외만 던집니다.

REST 표면에서는 `GlobalExceptionHandler` 가 `ConnectorException` 서브타입을
각각 잡아 `ErrorCode` 와 HTTP status 로 매핑합니다.

```text
ConnectorTimeoutException     -> CONNECTOR_TIMEOUT / 504
ConnectorUnavailableException -> CONNECTOR_UNAVAILABLE / 503
ConnectorParseException       -> CONNECTOR_PARSE_ERROR / 502
ConnectorException            -> CONNECTOR_ERROR / 502
```

MCP 표면에서는 tool layer 가 `ConnectorException` 을 catch 한 뒤
`ConnectorErrorMessages.forResource(...)` 로 한국어 안내문을 만들고
`IllegalStateException` 으로 rewrap 합니다. resource label 은 학식 tool 에서
`학식`, dorm tool 에서 `기숙사 식단` 을 사용합니다.

`IllegalArgumentException` 도 같은 정책으로 처리합니다. REST 에서는
`GlobalExceptionHandler` 가 400 / `VALIDATION_FAILED` envelope 으로
변환합니다. MCP 에서는 Spring AI 가 exception message 를 client 로
전달하도록 둡니다.

## Consequences

**좋은 점**
- Service 와 Connector 가 표면 책임 없이 도메인 로직에 집중합니다.
- REST 는 구조화된 envelope + status code 를 유지합니다.
- MCP 는 LLM 친화적인 자연어 error message 를 받을 수 있습니다.
- 한 표면의 메시지 정책을 바꿔도 다른 표면에 영향을 주지 않습니다.

**대가**
- 같은 `ConnectorException` 정보를 REST handler 와 MCP helper 두 곳에서 메시지로 풀어쓰는 중복이 생깁니다.
- 향후 GraphQL, gRPC 같은 새 표면이 생기면 그 표면 layer 에서 매핑을 한 번 더 작성해야 합니다.
- 표면별 메시지가 달라지므로 문서와 테스트로 의도를 계속 고정해야 합니다.

## Alternatives considered

- **공통 error mapper** — `ConnectorException` 을 표면 무관 사용자 메시지로 바꾸는 helper 를 만들 수 있지만, REST envelope 와 MCP 자연어가 본질적으로 달라 공통화 가치가 낮습니다.
- **Service 가 `ApiResponse` 류 반환** — REST 에서는 편해 보이지만 MCP 에서는 envelope 이 불필요한 wrapping 이 되고 responsibility 가 Service 로 누수됩니다.
- **MCP 도 ErrorCode 만 노출** — 구현은 단순하지만 LLM-facing UX 가 약하고 사용자가 바로 이해하기 어렵습니다.
