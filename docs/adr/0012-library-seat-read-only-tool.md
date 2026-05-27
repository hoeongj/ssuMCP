# ADR 0012 - Library seat status as a read-only MCP tool with short-TTL cache

- **Status**: Accepted (Task 12 merged; `get_library_seat_status` live with mock connector + 30s cache. Real connector pends Task 13 session-auth landing.)
- **Date**: 2026-05-15
- **Scope**: `backend/src/main/java/com/ssuai/domain/library/**`,
  `backend/src/main/java/com/ssuai/domain/mcp/tool/LibrarySeatMcpTool.java`,
  `docs/tasks/12-library-seat-status.md`, `docs/vision.md`.

## Context

`docs/vision.md` positions the library seat reservation agent as the
flagship Phase 4 demo. Before any reservation flow can exist, the chatbot
needs to know **which floor has open seats**, and the MCP toolset needs
a stable shape for "library seat data" so the reservation action tool
(Phase 4) can later be slotted in without re-shaping the agent's prompt.

Two product questions force a clean line up front:

1. **Read vs. write boundary.** A reservation action would require
   u-SAINT-style authentication, idempotency keys, and an explicit
   confirmation step. A read-only seat lookup doesn't. Mixing the two
   under one tool would entangle the auth/idempotency story with the
   chatbot's already-shipping public-data path.
2. **Freshness vs. politeness.** Seat occupancy is genuinely volatile,
   but the library page belongs to the school — a chat message bursting
   N tool calls into one page within seconds is unfriendly.

## Decision

Ship Phase 2's library slice as a strictly read-only tool with a small,
explicit cache layer.

- **Tool surface.** One MCP tool, `get_library_seat_status(floor)`. It
  returns total / available / reserved / out-of-service counts for the
  floor and a `zones[]` breakdown that, where the upstream page exposes
  them, includes individual `seatIds`. No write/action verbs.
- **Reservation is a separate tool, later.** Phase 4 will add a distinct
  tool whose name starts with `reserve_` and which lives in its own MCP
  tool class. The seat-status output's `seatIds` is the contract those
  two tools share.
- **Cache shape.** Per-floor in-memory `ConcurrentHashMap` with a 30s
  default TTL and single-flight semantics: a concurrent miss for floor N
  waits for the in-flight upstream call rather than starting a parallel
  one. TTL is tunable via `ssuai.library.seat.cache-ttl`.
- **Error shape.** Reuse the existing
  `ConnectorTimeoutException` / `ConnectorUnavailableException` /
  `ConnectorParseException` family and the matching `ErrorCode` mappings
  in `GlobalExceptionHandler`. No new error codes; the LLM-facing
  messages come from the existing `ConnectorErrorMessages` helper.
- **Connector seam preserved.** A `LibrarySeatConnector` interface with a
  `Mock` default. The `Real` Jsoup-based implementation is held back
  until the upstream URL/markup is settled — see Phase rollout below.

## Cache TTL — why 30 seconds

Two opposing constraints:

- **Freshness** — a student about to walk over wants the answer to
  reflect occupancy roughly now, not 5 minutes ago.
- **Politeness** — at chat-message frequency a 1s TTL would mean a
  single popular minute could fan out dozens of upstream calls per
  floor.

30s lands in the middle: a student in transit gets data no older than
half a minute, and at most 2 upstream calls per floor per minute even
under load. Tunable down to 15s if the upstream proves unstable.

Single-flight does the rest: even if 50 chat messages hit
`get_library_seat_status(4)` simultaneously, only the first triggers a
scrape; the rest block on its completion.

## Read-only boundary, explicitly

`get_library_seat_status` is named and described so the LLM cannot
mistake it for a reservation action:

- The MCP description includes "이 도구는 읽기 전용이며, 좌석 예약은
  별도의 동작 도구로 분리되어 있습니다."
- The tool method returns a plain status DTO; there is no `confirm`,
  `commit`, or `reserve` verb anywhere in the connector or service.
- The connector never touches `u-saint` or any authenticated endpoint —
  the seat status page is anonymous. If implementation discovers
  otherwise, the task's Stop-and-flag rule kicks in and the slice
  pauses.

## Phase rollout

This ADR covers the read-only mock slice. The intended phases:

1. **Now (this ADR).** Mock connector wired end-to-end through
   service / cache / controller / MCP tool. Tests pass. No real upstream
   traffic. The chatbot can answer "4층 자리 있어?" against fixture
   data, exercising the whole prompt+tool plumbing.
2. **Real connector (URL-provided).** Drop in a `Real` Jsoup-based
   implementation, switch `ssuai.connector.library-seat=real` in prod.
   Mock stays as the default for dev and CI to keep tests fast and
   offline.
3. **Phase 4 reservation tool.** A separate tool, separate ADR, separate
   auth design. The seat-status output's `seatIds` is the input
   contract.

## Consequences

Good:

- **Clean failure boundaries.** Read-only tool failures degrade the
  chatbot's library answer without affecting auth, reservation, or any
  Phase 4 surface that doesn't exist yet.
- **Politely bounded outbound traffic.** Worst case at 30s TTL with
  single-flight per floor is 2 scrape requests per floor per minute,
  regardless of chatbot load.
- **Phase 4 hands.** The `seatIds` field gives Phase 4 a concrete list
  of identifiers to reserve. The reservation tool will take a seat id
  the chatbot already knows about.

Tradeoffs:

- **Cache layer is hand-rolled, not Caffeine.** A small `Map` with TTL
  + single-flight is enough and stays visible on the call path, which is
  the right portfolio surface for explaining "why a cache here." If we
  ever add a second tool with similar shape we'll consider promoting it
  to a shared component.
- **Reused `CONNECTOR_UNAVAILABLE` returns 503, not 502.** Task 12 spec
  drafted four library-specific error codes
  (`LIBRARY_SEAT_UPSTREAM_UNAVAILABLE` → 502 etc.). Reusing the existing
  enum keeps `GlobalExceptionHandler` from ballooning, but the
  unavailability case responds 503 instead of the spec's 502. The status
  code is still in the 5xx upstream-fault range and the JSON envelope's
  `error.code` (`CONNECTOR_UNAVAILABLE`) is unambiguous; not worth a new
  error code.
- **Mock fixture data is stable across calls.** Tests can rely on it but
  a developer eyeballing the dashboard during local dev will see the
  same numbers every refresh. Acceptable for Phase 2; Phase 3 (real
  connector) replaces it.

## Alternatives Considered

- **One tool that does read + reserve.** Tempting (one trip from the
  LLM) but couples auth into a public read path and means every read
  failure has to consider reservation rollback. Rejected; keep the two
  surfaces independent.
- **Spring Cache + Caffeine.** Less code, but the TTL/single-flight
  semantics become invisible at the call site. For a project where the
  cache shape is itself a discussion point, keep it explicit.
- **No cache, rely on upstream.** Politeness fails immediately — the
  chatbot would scrape the library page on every "4층 자리 있어?"
  message.
- **Per-floor scheduled prefetch (like the weekly meal cache).**
  Overkill: seat occupancy doesn't have a natural weekly cadence, and
  scheduled prefetch would hit the library 7×N times per refresh
  window even when no student is asking.
