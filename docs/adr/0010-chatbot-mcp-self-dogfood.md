# ADR 0010 - Chatbot self-dogfoods the MCP server

- **Status**: Accepted (`feat/chatbot-mcp-dogfood` merged; chat path goes through MCP self-dogfood over local SSE)
- **Date**: 2026-05-13
- **Scope**: `backend/src/main/java/com/ssuai/domain/chat/`,
  `backend/build.gradle`, `backend/src/main/resources/application*.yml`,
  Spring AI MCP client wiring.

## Context

The chatbot slice (ADR 0009) lives in the same Spring Boot process as the
MCP server (ADR 0003). Up to this point, `LlmChatService.executeToolCall`
invoked `MealMcpTools.getTodayMeal()` and friends as plain Java method
calls, while the MCP server exposed the *same* `@Tool`-annotated beans
over SSE for external clients (Claude Desktop, Claude Code, Cursor).

That asymmetry undercut the project's MCP narrative: the MCP server was
the deliverable, but the project's own chatbot did not actually exercise
the protocol. Any breakage on the MCP request/response surface would have
gone unnoticed in the chat path. The protocol was only validated by
external clients we do not own and cannot CI.

ADR 0009 explicitly listed "MCP client dogfooding inside the same JVM" as
a deferred follow-up. This ADR resolves it.

## Decision

Wire `LlmChatService` to call the local MCP server through Spring AI's
**MCP client** starter (`spring-ai-starter-mcp-client`, HttpClient-based
SSE), rather than via direct in-process method calls. The chatbot
becomes a normal MCP consumer of its own MCP server.

- Add `spring-ai-starter-mcp-client` to `backend/build.gradle` alongside
  the existing server starter.
- Configure a single MCP client connection named `self` pointing at
  `${SSUAI_MCP_CLIENT_BASE_URL:http://localhost:8080}` with SSE endpoint
  `/sse`. Toggle via `spring.ai.mcp.client.enabled`
  (`SSUAI_MCP_CLIENT_ENABLED`) so the test profile can disable it.
- `LlmChatService` injects `List<McpSyncClient>`; constructor asserts at
  least one connection is wired and uses the first one.
- `executeToolCall(OpenAiToolCall)` builds a `CallToolRequest(name, args)`
  for each of the 4 tools and calls `mcpClient.callTool(...)`. Local
  guards (empty-query check for `search_campus_facilities`) still run
  before the MCP call so we keep the same UX and avoid round-trips.
- Tool responses are received as `McpSchema.TextContent` JSON, then
  compacted with a `JsonNode`-based reimplementation of the previous
  typed-DTO compaction (meal items: `restaurant/type/corner/menu`;
  facilities: max 6 results, drop `aliases/fax/id`).
- A hard 8 KB byte cap with a `...[truncated]` marker is applied to the
  final tool message content as a defensive token budget guard.
- `MealMcpTools`, `DormMcpTools`, `CampusMcpTools`, and `McpServerConfig`
  are unchanged — they remain the canonical tool implementations exposed
  by the MCP server.
- `MockChatService` is unchanged; mock mode does not dogfood.

## Consequences

Good:

- The MCP server's request/response surface is exercised on every chat
  request, not just by external clients.
- Splitting the chatbot into its own process later becomes a deployment
  change rather than a code change — the client already speaks HTTP/SSE.
- The portfolio story is consistent: MCP is the primary deliverable, and
  the chatbot is a first-class consumer of it.
- The 8 KB tool-content cap puts a hard ceiling on tool-driven token
  spend per turn, on top of the per-request budget knobs from ADR 0009.

Tradeoffs:

- Each tool call now adds an extra in-process HTTP/SSE round-trip
  (single-digit ms on the loopback interface, but non-zero). Acceptable
  given Tomcat's default 200-thread pool and the bounded-fallback budget.
- Debugging tool errors crosses an additional layer (chat path → MCP
  client → MCP server → tool bean). Mitigated by logging the tool name
  and exception class at the `mcp tool call failed` log site.
- Tests now mock `McpSyncClient` and feed back JSON `TextContent`, which
  is slightly more verbose than the previous typed-DTO mocks. Tradeoff
  accepted because tests now match production wire format.
- The compaction layer was rewritten against `JsonNode` instead of typed
  DTOs. If the underlying DTO shapes change, both serialization (on the
  MCP server side) and the JSON-node compaction (here) need to move
  together. The MCP tool contract is already the public schema, so this
  coupling matches reality.

## Alternatives Considered

- **Keep direct in-process calls**: simplest, but leaves the MCP
  protocol surface only validated by external clients. Rejected — this
  is the asymmetry ADR 0009 flagged as a follow-up.
- **Raw HTTP POST to `/mcp/message?sessionId=...`**: would avoid the
  client starter, but loses session/init lifecycle handling that
  `McpSyncClient` already manages. Not worth the maintenance.
- **WebFlux MCP client variant** (`spring-ai-starter-mcp-client-webflux`):
  reactive transport with no benefit for our synchronous chat path on a
  Servlet stack. Rejected.
- **Split the chatbot into its own process and have it call the MCP
  server over the network**: ultimately the strongest dogfooding story,
  but doubles infrastructure for the MVP. Deferred — this ADR keeps the
  option open by making the call site protocol-based.
- **Pass MCP JSON straight through without compaction**: the tool
  outputs would re-introduce the verbose fields ADR 0009 stripped
  (`aliases`, `fax`, `id`, full facility lists), bloating the LLM
  context. Rejected; JSON-node compaction keeps the prior contract.
