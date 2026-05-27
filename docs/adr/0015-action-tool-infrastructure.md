# ADR 0015 — Action-tool common infrastructure (prepare + confirm + audit)

- **Status**: Proposed (no code yet — pins the design before Phase 4
  `reserve_library_seat` starts).
- **Date**: 2026-05-16
- **Scope**:
  - `docs/vision.md` §3 Layer 4 (action policy bullets),
  - `docs/security.md` §6 (consent + confirmation + audit),
  - `docs/mcp-tools.md` §8 (write-tool policy),
  - `docs/architecture.md` §14 (action_audit + Redis lock note),
  - future package `com.ssuai.domain.action` and any `@Tool` method
    that mutates school state.

## Context

ssuAI's flagship deliverable is the **도서관 좌석 자동 예약
에이전트** ([`docs/vision.md`](../vision.md) §3 Layer 4): a chatbot
that, on the user's "412번 예약해줘", actually performs the reservation
POST against the school library site. Several other write tools follow
the same shape — `cancel_library_seat_reservation`, `extend_library_seat`,
`borrow_library_book_hold`, and eventually any safe LMS / u-SAINT write
that does not violate
[`docs/vision.md`](../vision.md) §5 ("not built" list).

Read tools are easy: every output is recoverable, idempotent, and the
worst case is a stale answer. Write tools are not. The worst case for
`reserve_library_seat` is "ssuAI booked the wrong seat for a student
who didn't ask for it" — directly bad for the user, directly bad for
the school's seat availability for everyone else.

The vision doc, security doc, and mcp-tools doc all already enumerate
the rules action tools must obey (confirmation, dry-run, audit log,
distributed lock, no plaintext credentials in logs). What they do
**not** pin is the *mechanism*:

- Is "confirmation" a server-side resource or just a chat turn the LLM
  is asked to perform?
- What exact data shape carries between "prepare" and "execute"?
- Where does the `action_audit` row get written, and when?
- Is the lock in-process or in Redis?
- How does an MCP `@Tool` method actually surface a "do you want to
  proceed?" without inventing a new MCP protocol primitive?

Until those are pinned, every Phase 4 PR re-litigates the same
questions, and the LLM has too many degrees of freedom in how it
orchestrates a write. This ADR locks the mechanism so
`reserve_library_seat` can be implemented as a thin parser + connector
on top of shared infrastructure.

## Decision

### D1. Split every write tool into `prepare_X` + shared `confirm_action`

An action tool is **two MCP tools**, not one.

- `prepare_reserve_library_seat(seat_id, duration?)` — read-only
  semantically. Validates inputs, computes the dry-run preview,
  reserves no school-side state, and writes one `action_audit` row
  in state `PREPARED`. Returns:

  ```json
  {
    "pending_action_id": "act_01H...",
    "dry_run_preview": "412번 좌석을 지금부터 4시간 (~20:00) 예약합니다.",
    "expires_at": "2026-05-16T17:35:00+09:00"
  }
  ```

- `confirm_action(pending_action_id)` — **one shared tool, not per-action.**
  Looks up the pending row, transitions it to `EXECUTING`, performs the
  upstream call, and transitions to `SUCCESS` / `FAILURE_RACE` /
  `FAILURE_AUTH` / `FAILURE_UPSTREAM` / `TIMEOUT`. Returns the outcome
  + a human-readable result message.

The LLM chain therefore looks like:

```text
user:    "412번 4시간 잡아줘"
LLM:     prepare_reserve_library_seat(seat_id=412, duration=4h)
tool →   { pending_action_id=act_..., dry_run_preview=... }
LLM:     [shows preview to user, asks "진행할까요?"]
user:    "응"
LLM:     confirm_action(pending_action_id=act_...)
tool →   { state=SUCCESS, message="412번 예약 완료." }
LLM:     [reports outcome]
```

The split is the load-bearing decision. Confirmation is not "the LLM
asked the user politely"; it is **a separate tool call against a
short-lived server-side resource**. The LLM cannot accidentally
execute by re-calling `prepare_*` — that just creates another
`PREPARED` row.

### D2. `action_audit` is append-only and is the source of truth for state

```sql
CREATE TABLE action_audit (
  id                 UUID PRIMARY KEY,
  student_id         UUID NOT NULL REFERENCES student(id),
  tool_name          TEXT NOT NULL,            -- e.g. "reserve_library_seat"
  input_payload      JSONB NOT NULL,           -- sensitive fields masked
  dry_run_preview    TEXT NOT NULL,            -- exact text shown to user
  state              TEXT NOT NULL,            -- see §state-machine
  outcome_code       TEXT,                     -- SUCCESS|FAILURE_RACE|FAILURE_AUTH|FAILURE_UPSTREAM|TIMEOUT|EXPIRED|CANCELLED
  outcome_message    TEXT,                     -- human-readable, scrubbed
  trace_id           TEXT NOT NULL,            -- matches API response traceId
  prepared_at        TIMESTAMPTZ NOT NULL,
  confirmed_at       TIMESTAMPTZ,              -- null until confirm_action
  completed_at       TIMESTAMPTZ,
  UNIQUE (id)
);

CREATE INDEX action_audit_student_recent
  ON action_audit (student_id, prepared_at DESC);
```

State machine:

```text
PREPARED ──confirm_action──► EXECUTING ──┬──► SUCCESS
                                          ├──► FAILURE_RACE
                                          ├──► FAILURE_AUTH
                                          ├──► FAILURE_UPSTREAM
                                          └──► TIMEOUT
PREPARED ──(no confirm in TTL)──► EXPIRED
PREPARED ──user cancel────────► CANCELLED
```

Append-only is enforced by **application-level discipline + DB grant**:
the application user has `INSERT, SELECT, UPDATE` on `action_audit` but
no `DELETE`. State transitions are `UPDATE`s, not `INSERT`s — one row
per action, mutated in place, so a user reading their own history sees
the final outcome without joining multiple rows.

The audit row is **written before the upstream call**, so even if the
process is killed mid-call, the row's `EXECUTING` state plus `trace_id`
lets operators reconstruct what happened.

### D3. `pending_action_id` TTL = 5 minutes, single-use

`prepare_X` writes the audit row in `PREPARED` and returns the row id
as `pending_action_id`. The id is opaque (UUID, not row index). TTL
behavior:

- A scheduled task transitions any `PREPARED` row older than 5 min to
  `EXPIRED`. `confirm_action` against an `EXPIRED` id returns
  `EXPIRED` outcome, no upstream call.
- Single-use: `confirm_action` against a row not in state `PREPARED`
  returns the current state and refuses to re-execute. Idempotent on
  the LLM side: re-calling with the same id is safe.

Five minutes is long enough for a chat round-trip + user thinking, and
short enough that a stale id leaked into logs is harmless.

### D4. Lock = in-process `ConcurrentHashMap<String, ReentrantLock>` for MVP, Redis when multi-instance

Key: `actionlock:{studentId}:{toolName}`. Held inside the
`confirm_action` execution scope. Released on completion or timeout
(timeout = `upstream-call-deadline + 1s`).

If a second `confirm_action` arrives while the lock is held, return
`FAILURE_RACE` immediately with message `"동일한 액션이 진행 중입니다."`
— do **not** wait. This makes double-click safe and protects against
the LLM accidentally fan-outing confirm calls.

The in-process map is correct as long as ssuAI runs as a single JVM
(today's `docs/architecture.md` §2 topology). The day deployment goes
multi-instance, swap the implementation for Redis `SETNX` behind the
same `ActionLock` interface. The interface is the seam, not the
implementation.

### D5. Authentication

Every action tool — both `prepare_X` and `confirm_action` — requires
the access JWT. `JwtAuthFilter` populates `ssuai.studentId`; both
methods read it from request attributes and treat absence as 401
`UNAUTHENTICATED`. There is no anonymous action path.

A MCP client that calls `prepare_X` without an access JWT gets a 401.
This is enforced at the `@Tool` method, not the connector, so the
audit row is **not written** for anonymous calls (otherwise an
unauthenticated caller could flood the audit table).

### D6. Errors and race handling

The upstream school site can refuse a write for several real reasons:

| Connector signal                       | Audit outcome      | User-visible message hint                       |
|----------------------------------------|--------------------|-------------------------------------------------|
| 200 + success body                     | `SUCCESS`          | `<tool-specific success text>`                  |
| 4xx + "seat already taken" body        | `FAILURE_RACE`     | "직전에 다른 학생이 선점했어요."                |
| 401 / cookie expired                   | `FAILURE_AUTH`     | "도서관 세션이 만료됐어요. 다시 로그인해주세요." |
| Network timeout                        | `TIMEOUT`          | "학교 서버가 응답이 없어요. 잠시 후 다시 시도."  |
| Other 5xx / parse failure              | `FAILURE_UPSTREAM` | "학교 시스템에서 오류가 발생했어요."            |

`FAILURE_RACE` exists as a distinct outcome because the agent loop
should be able to *suggest the next available seat* automatically, not
just give up. The Phase 4 `reserve_library_seat` ADR (not this one)
will specify whether the agent re-prepares automatically or asks the
user; this ADR just makes sure the outcome is distinguishable.

### D7. What never goes into the audit row

- The user's school password (we don't store it anyway — see
  `docs/security.md` §5).
- u-SAINT / library / LMS session cookies (decrypt-and-use, never
  audit).
- Full upstream HTML.
- Any field flagged sensitive on the input DTO (`@SensitiveField`
  masking, same machinery
  [`docs/security.md`](../security.md) §4 already requires for logs).

`outcome_message` is **for the user**, so it must be free of any of
the above. Connectors emit user-safe messages; the service does not
forward upstream exception text verbatim.

## Trade-offs and assumptions

- **Two MCP tools per write is more chatter.** Every reservation is
  one `prepare_*` call + one `confirm_action` call instead of one
  combined tool. The chatter is the price of making confirmation a
  server-enforced property; we judge it cheap. Alternative:
  single-tool with `dry_run: true` boolean — rejected as D-alt below.
- **Single shared `confirm_action` couples action tools.** A bug in
  `confirm_action` impacts every write. Mitigated by the state
  machine: `confirm_action` is pure orchestration (lookup → lock →
  dispatch → audit transition); per-tool execution lives in
  per-tool services it dispatches into.
- **Append-only via grant + discipline, not DB-enforced triggers.**
  Postgres can enforce `BEFORE DELETE` triggers that block deletion,
  but the simpler grant-only approach matches the rest of ssuAI's
  "lightweight infra" stance. Revisit if we ever need
  tamper-evidence (chained-hash audit etc.).
- **5-min TTL is a guess.** If real chats routinely exceed 5 min
  between prepare and confirm (the user steps away from the chat),
  expand to 10 min. Tracking this is cheap — count `EXPIRED` rows.
- **The 30-min credential session TTL** (Task 16 / 17 `SaintSessionStore`
  / `LmsSessionStore`) is independent of the 5-min `pending_action_id`
  TTL. The cookie store outlives the pending action.
- **Spring AI's `@Tool` annotation model** is assumed to support both
  authenticated argument injection (Task 16 §9 stop-and-flag #4) and
  arbitrary string return shapes. If the framework forces a specific
  return schema, the prepare-tool output becomes a typed DTO instead
  of free-form JSON.

## Alternatives considered

### D-alt-1. Single tool with `dry_run: true` boolean

Instead of `prepare_reserve_library_seat` + `confirm_action`, have one
`reserve_library_seat(seat_id, duration, dry_run)`. Setting
`dry_run=true` returns the preview; setting `dry_run=false` executes.

Rejected because:

- The execution path takes the same arguments. The LLM can call
  `reserve_library_seat(seat_id=412, dry_run=false)` directly without
  ever calling the dry-run variant. Confirmation collapses to "we hope
  the LLM is well-behaved."
- The audit row has to be invented at execution time, with no
  preview-shown row earlier. We lose the "this is the exact text we
  showed the user" guarantee.

### D-alt-2. Confirmation lives entirely in the chat turn (no server resource)

The LLM is instructed to ask "진행할까요?" and only then call
`reserve_library_seat`. No server-side prepared row.

Rejected because:

- LLM prompt-following is not a security boundary. A misbehaving
  model — or, more realistically, an upstream prompt-injection inside
  the chat (e.g., a malicious assignment title from LMS that contains
  "ignore previous instructions") — can skip the confirmation step.
- The audit log has no link from preview to execution.

### D-alt-3. Two-tool with separate `confirm_reserve_library_seat`

Instead of one shared `confirm_action`, every write has its own
`confirm_X`. So `prepare_reserve_library_seat` pairs with
`confirm_reserve_library_seat`.

Rejected because:

- MCP tool catalog bloat — every write doubles tool count.
- The confirm step's only argument is the `pending_action_id`, which
  is opaque. There is nothing per-action to specialise.

### D-alt-4. Redis lock from day one

Use Redis SETNX immediately, accept the Redis dependency.

Rejected for the MVP. Current architecture is single-JVM
([`docs/architecture.md`](../architecture.md) §2); an in-process lock
is correct and zero-cost. Swap to Redis when there is a real reason
(multi-instance deploy, blue/green rollout, …). The seam is the
`ActionLock` interface.

## Consequences

- **Implementation order is now obvious.** Before any `reserve_*` PR
  lands, the project needs: `action_audit` migration (Flyway script),
  `ActionAuditService`, `ActionLock` interface + in-process impl,
  `prepare`/`confirm` controller path, the shared `confirm_action`
  `@Tool` method, and the `PreparedActionExpiryRunner` scheduled task.
  All of that is one PR (an "ActionInfrastructure" PR) before any
  real write tool exists.
- **Frontend gets a new error code** (`PENDING_ACTION_EXPIRED`) to
  render the "prepared but not confirmed in time" state, plus
  per-outcome rendering for `FAILURE_RACE` etc.
- **The flagship's spec becomes smaller.** Once this ADR is the
  foundation, `reserve_library_seat`'s own spec is just: parsing the
  seat reservation form, the connector's POST shape, race-body
  detection, and the success-state regex. Everything else is inherited.
- **The LLM's prompt grows by ~one paragraph** — it has to learn the
  `prepare_*` → "ask user" → `confirm_action` pattern. Worth pinning
  into the chat system prompt rather than relying on tool description
  alone.
- **Open follow-ups not decided here**:
  - Whether the agent re-prepares automatically on `FAILURE_RACE`.
    Belongs in the per-tool ADR.
  - Whether `action_audit` rows are user-visible in a "내 액션 기록"
    page. Probably yes, but a Phase 4 UX call.
  - Whether non-MCP REST callers (a future "예약" button on the
    dashboard) reuse `prepare`/`confirm` or get a single endpoint.
    Likely reuse — the button does prepare, shows a modal, the modal
    confirms. Pin in the dashboard task when it exists.
