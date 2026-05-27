# ADR 0018 — MCP transport SSE → Streamable HTTP 전환

- **Status**: Accepted
- **Date**: 2026-05-24
- **Supersedes**: [ADR 0003](0003-mcp-transport-sse.md) (MCP transport 로 SSE 선택)
- **Scope**: `backend/build.gradle`, `backend/src/main/resources/application.yml`,
  `LlmModeStartupSmokeTest`, `McpServerConfig`

## Context

ADR 0003 에서 MCP transport 로 SSE (`/sse` + `/mcp/message` 이중 endpoint) 를
선택했습니다. 당시 Spring AI 는 SSE 가 사실상의 표준이었습니다.

2025-03-26 MCP spec 이 **Streamable HTTP** 를 공식 transport 로 채택했습니다.
SSE 와의 핵심 차이:

| | SSE (구) | Streamable HTTP (신) |
|---|---|---|
| Endpoint | `/sse` (GET) + `/mcp/message` (POST) | `/mcp` (POST 단일) |
| 연결 방식 | 상시 SSE 스트림 유지 | 요청별 HTTP POST, 필요 시 SSE 스트림 |
| 클라이언트 호환 | Claude Desktop 0.x, mcp-proxy 필요 | Claude Desktop 최신, Cursor, Zed 직결 |
| 서버 설정 | `type: SYNC` (기본) | `type: SYNC`, `protocol: STREAMABLE` |

Claude Desktop, Cursor 등 주요 MCP 클라이언트가 Streamable HTTP 를 기본값으로
지원하기 시작했고, SSE 방식의 `mcp-proxy` 우회 단계를 없애는 것이 연결 UX
와 포트폴리오 설명 모두에 유리했습니다.

추가로, `LlmModeStartupSmokeTest` 가 SSE 프로퍼티 키를 그대로 쓰고 있어
transport 전환 후 CI 가 실패하는 문제가 발견되어 테스트도 함께 수정했습니다.

## Decision

MCP transport 를 **Streamable HTTP** 로 전환합니다.

**구현 변경:**

1. `application.yml`
   ```yaml
   spring:
     ai:
       mcp:
         server:
           protocol: STREAMABLE   # SSE → STREAMABLE
         client:
           streamable-http:       # sse → streamable-http
             connections:
               self:
                 url: ${SSUAI_MCP_CLIENT_BASE_URL:http://localhost:8080}
   ```

2. `LlmModeStartupSmokeTest`
   ```java
   // Before (CI failure)
   registry.add("spring.ai.mcp.client.sse.connections.self.url", ...);
   // After
   registry.add("spring.ai.mcp.client.streamable-http.connections.self.url", ...);
   ```

3. 단일 endpoint `/mcp` 가 Streamable HTTP 진입점이 됩니다.
   `/sse` 와 `/mcp/message` 는 더 이상 노출되지 않습니다.

## Consequences

**좋은 점**
- Claude Desktop, Cursor, MCP Inspector 에서 `mcp-proxy` 없이 직결 가능합니다.
- SSE 상시 연결 유지 부담이 사라집니다. 요청별 HTTP 라 k3s 단일 노드에서 더 안정적입니다.
- MCP 생태계 표준 transport 를 따라 향후 클라이언트 호환성 걱정이 줄어듭니다.
- CI 테스트가 올바른 프로퍼티 키를 사용하도록 수정되어 회귀 방지가 강화됩니다.

**대가**
- SSE 전용 클라이언트 (구버전 Claude Desktop 등) 는 `mcp-proxy` 어댑터가 여전히 필요합니다.
  현실적으로 최신 Claude Desktop 을 쓰는 사용자에게는 영향 없습니다.

## Alternatives considered

- **SSE 유지** — 클라이언트 호환성 변화가 없어 안전하지만, 최신 MCP 표준과 점점
  멀어지고 `mcp-proxy` 의존이 불필요한 설정 단계를 만듭니다.
- **두 transport 동시 지원** — Spring AI 1.1.x 가 서버 측에서 공식 지원하지 않습니다.
