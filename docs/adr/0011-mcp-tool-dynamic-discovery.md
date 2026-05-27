# ADR 0011 - Discover chat tools from the MCP server at runtime

- **Status**: Accepted (`feat/chatbot-mcp-tool-discovery` merged; `LlmChatService` discovers tools from MCP at runtime, cached after first turn)
- **Date**: 2026-05-13
- **Scope**: `backend/src/main/java/com/ssuai/domain/chat/service/LlmChatService.java`.

## Context

ADR 0010 closed the asymmetry where the chatbot reached its MCP tools as
plain Java method calls. The chatbot now talks to its own MCP server over
SSE, but the **tool list itself** that the chatbot advertises to the LLM
(name, description, input schema) was still hardcoded in
`LlmChatService.createTools()`. The MCP server, in parallel, derives the
same surface from `@Tool` and `@ToolParam` annotations on
`MealMcpTools`, `DormMcpTools`, and `CampusMcpTools`.

Two practical issues followed:

1. **Drift.** Tool descriptions diverged. `CampusMcpTools.search_campus_facilities`
   on the server side said "query 가 비어 있으면 전체 시설 목록을 반환합니다,"
   while `LlmChatService`'s static description said "비워두지 마세요." The LLM
   only ever saw the chat-side description; external MCP clients only ever
   saw the server-side one. Same tool, two narratives.
2. **Add a tool, touch two places.** A new MCP tool requires both an
   `@Tool` bean and a hand-written `OpenAiChatCompletionRequest.Tool`
   entry. Easy to forget, and a silent regression — the LLM just stops
   being told the tool exists.

## Decision

Discover the chat tool list at runtime by calling
`McpSyncClient.listTools()` and mapping each `McpSchema.Tool` to an
OpenAI-compatible tool definition. The MCP server's `@Tool` annotation
becomes the single source of truth for what the chatbot exposes to the
LLM.

- `LlmChatService` no longer holds a static `CHAT_TOOLS` list. Instead it
  has a lazily-populated `cachedChatTools` field, computed on first chat
  reply via `discoverChatTools()`.
- `discoverChatTools()` calls `mcpClient.initialize()` if not yet
  initialized, then `mcpClient.listTools()`. Each returned
  `McpSchema.Tool` is mapped to `OpenAiChatCompletionRequest.Tool` by
  copying `name`, `description`, and `inputSchema` (`type`, `properties`,
  `required`, `additionalProperties`) — which is already JSON Schema, the
  format OpenAI tool definitions expect.
- The cache lives for the JVM's lifetime. The set of MCP tools is part of
  the build artifact, so tool drift across restarts is not a concern that
  warrants a TTL. A restart re-discovers.
- If `listTools()` throws on first call, we log
  `mcp listTools failed: error=...` and surface a `ChatUnavailableException`.
  The cache is not populated, so a later request retries — useful if the
  server bean was slower to come up than the first chat request.
- Local guards (e.g. the empty-`query` reject for
  `search_campus_facilities`) **stay in the chat path**. They aren't part
  of the MCP tool contract; they reflect a chat-only UX preference for
  bounded tool results. The LLM may still issue a search with an empty
  query because the MCP description allows it; the chat-side guard then
  short-circuits with the same `검색어가 필요합니다` text as before.

## Consequences

Good:

- **Single source of truth.** Adding, renaming, or re-describing a tool
  is now a one-place change on the MCP server (`@Tool` / `@ToolParam`).
- **Description quality flows to the chatbot.** The server already
  carries the richer, externally-published descriptions (used by Claude
  Desktop). The chatbot now sees the same text — and it's strictly more
  detailed than the previous chat-side strings.
- **One less file to touch on schema changes.** Input schema for tools
  with parameters (e.g. `date` for `get_meal_by_date`) is reflected
  automatically. Previously the chat-side schema had to be kept in sync
  by hand.
- **Aligns with Spring AI's recommended pattern.** Spring AI 1.1's
  `SyncMcpToolCallbackProvider` is the framework's own version of this
  same idea; we keep our chat path on the OpenAI-tool surface but borrow
  the discovery shape.

Tradeoffs:

- **First chat call pays one extra MCP round-trip** (the `listTools`
  call). It's an in-process loopback against the local SSE server, so
  single-digit ms — but it is non-zero, and a cold-start chat will
  experience it once.
- **Chat startup now soft-depends on the MCP server bean being up.** If
  the MCP server fails to initialize, the chat path can't discover tools
  and returns `ChatUnavailableException`. Acceptable: in this monolith,
  the MCP server going down is also our outage, and the chat path was
  already useless without it.
- **Description drift between MCP intent and chat-side guards.** The MCP
  description for `search_campus_facilities` says empty query returns the
  full list; the chat path still rejects empty query for context-budget
  reasons. The LLM occasionally tries an empty-query call and gets a
  guard response. Net effect is one extra round-trip, not a regression.
  See "Alternatives" below for why we kept it this way.

## Alternatives Considered

- **Keep the static list.** Smaller diff, but locks in the drift problem
  and the two-places-to-edit rule. Rejected as the natural follow-up to
  ADR 0010.
- **Discover at startup via `ApplicationReadyEvent` and fail-fast.**
  Stronger guarantee (no first-request latency), but the `LlmChatService`
  is `@ConditionalOnProperty(ssuai.connector.chat=llm)`, and the MCP
  client / MCP server beans only finish their handshake during Tomcat's
  `SmartLifecycle` phase. Discovering on first use is simpler and keeps
  context startup independent of the MCP client's readiness.
- **Use `SyncMcpToolCallbackProvider` directly.** Spring AI's bridge maps
  MCP tools onto its own `ToolCallback` / `ChatClient` surface. We don't
  use `ChatClient` (the chat path is OpenAI-compatible REST, see ADR 0009),
  so adopting `SyncMcpToolCallbackProvider` would also require swapping
  the chat transport. Out of scope; revisit if we ever consolidate on
  Spring AI's `ChatClient`.
- **Drop the local empty-query guard** so the MCP description and chat
  behaviour line up exactly. Tempting, but the guard exists to keep the
  LLM context bounded and the user response short. Treat the description
  drift as a documentation problem, not a correctness one.
- **Refresh the tool cache on a TTL.** Adds complexity for a benefit
  (catching mid-runtime tool list changes) we don't currently produce —
  the tools change only when we deploy a new build. JVM-lifetime cache is
  fine.
