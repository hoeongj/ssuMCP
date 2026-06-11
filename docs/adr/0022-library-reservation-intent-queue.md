# ADR 0022 - Library reservation intent queue with polling outbox

- **Status**: Accepted - PR1 and PR2 implemented 2026-06-11.
- **Date**: 2026-06-11
- **Scope**:
  - `V8__create_library_reservation_intents.sql`
  - `V9__add_action_audit_id_to_library_reservation_intents.sql`
  - `com.ssuai.domain.library.reservation.intent`
  - `LibraryWaitMcpTool`
  - `ConfirmActionMcpTool` reserve path
  - `docs/architecture.md` and `docs/mcp-tools.md`

## Background

The library reservation feature started with a synchronous `prepare_*` plus
`confirm_action` flow: the user confirms one seat, then `ConfirmActionMcpTool`
calls Pyxis directly and records `action_audit`. That is correct for consent, but
it does not solve the flagship portfolio problem: "wait for a seat and reserve it
when it opens" under concurrent demand.

The hard part is not the HTTP POST itself. The hard part is orchestration:

- many users can wait for the same seat;
- seat availability changes outside our system;
- Pyxis writes are not idempotent, so write retries are forbidden;
- the process can crash after a worker starts work;
- later SSE/mobile notification consumers need state-change events without adding
  Kafka/Redpanda too early.

PR1 therefore adds a DB-backed **reservation intent queue**. The queue is the
execution unit; `action_audit` remains the user-consent evidence and is integrated
in PR2.

PR2 routes `confirm_action` reserve confirms through the same queue. The audit
row records the user's consent and the synchronous facade outcome. The
`library_reservation_intents` row is the execution unit and stores the durable
reservation state.

## Decision

### D1. Store explicit intent state in Postgres/H2-compatible tables

`library_reservation_intents` stores the wait request, current state, lease,
expiry, and terminal outcome. `library_reservation_outbox` stores durable event
records. The migration deliberately uses `TEXT` for JSON strings and ordinary
indexes rather than `JSONB` or partial indexes because the test suite runs the
same Flyway migrations on H2 in PostgreSQL mode.

The state machine is:

```text
REQUESTED -> WAITING_FOR_SEAT -> RESERVING -> SUCCEEDED
                                        |-> FAILED_RACE
                                        |-> FAILED_AUTH
                                        |-> FAILED_UPSTREAM
REQUESTED(immediate confirm) -> RESERVING -> SUCCEEDED
                                        |-> FAILED_RACE
                                        |-> FAILED_AUTH
                                        |-> FAILED_UPSTREAM
WAITING_FOR_SEAT -> CANCELLED
WAITING_FOR_SEAT -> EXPIRED
```

`LOCKING_SEAT` is intentionally absent until the Redisson phase. Today the
deployment is a single backend pod, so row locks plus worker leases are enough.

For the LIBRARY provider, MCP stores an opaque library principal key, not the
student number. Until SAINT/LIBRARY identity unification exists, `student_id` in
the intent table follows the existing `action_audit` convention and stores that
opaque key. The raw `mcp_session_id`, Pyxis token, login ID, and password never
enter the intent or outbox tables.

PR2 adds nullable `action_audit_id`. It is set only for immediate confirm
intents. Wait intents still have no audit row because their registration call is
the consent event.

### D2. Claim rows with `FOR UPDATE SKIP LOCKED`, then release the transaction

The worker's first transaction is intentionally short:

```sql
WHERE (
        status = 'WAITING_FOR_SEAT'
        OR (status = 'REQUESTED' AND action_audit_id IS NOT NULL)
      )
  AND next_attempt_at <= now
  AND expires_at > now
ORDER BY next_attempt_at, id
LIMIT 10
FOR UPDATE SKIP LOCKED
```

Inside that transaction, rows move to `RESERVING` and receive
`locked_until = now + 30s`. The transaction commits before any Pyxis read/write.
That prevents long network calls from holding row locks and blocking vacuum or
other workers.

After the connector call, a second transaction writes the terminal state and the
outbox event(s) together. This is the transactional outbox pattern: state and
event are atomic, while actual event publication can happen later.

### D3. Use the existing availability and reservation seams

Seat discovery goes through `LibraryAvailableSeatsService`, so it reuses the
existing 30s seat cache and connector boundary. Reservation goes through
`LibraryReservationConnector.reserve(token, request)`, which already uses
`PyxisResilience.write()` and therefore never retries writes.

If no seat matches the target/preferences, the worker increments
`attempt_count` and returns the row to `WAITING_FOR_SEAT` with exponential
backoff: 30s, 60s, 120s, capped at 5m.

If several claimed intents target the same seat in one worker tick, only the
first one calls Pyxis. The rest fail locally as `FAILED_RACE`. That is the key
portfolio behavior PR2 will measure with k6: "100 same-seat confirms produce one
upstream reserve call."

The first PR2 k6 run found a subtle gap: k6 user results were already
`SUCCESS` 1 / `FAILED_RACE` 99, but WireMock still saw two upstream reserve
calls because the worker tick split the burst. Same-tick grouping alone removes
duplicates only inside one claim batch. PR2 therefore adds a narrow immediate
confirm suppress rule: if a same-seat immediate intent has already reached
`SUCCEEDED` or `FAILED_RACE` and its intent expiry has not passed, later claimed
same-seat groups fail locally as `FAILED_RACE` without another Pyxis write. The
rule is intentionally scoped to `action_audit_id is not null` immediate confirms
and expires with the action TTL. Redisson seat locks remain the longer-term
cross-instance coordination story in EPIC 4.

### D4. Recover expired leases by reading current charge, not by retrying writes

A process can die after Pyxis accepted a reservation but before the terminal DB
write. Blindly retrying `reserve` would be unsafe because the write is not
idempotent. The reaper therefore claims expired `RESERVING` rows, calls
`getCurrentCharge()`, and resolves:

- current charge exists -> `SUCCEEDED`;
- no current charge -> `FAILED_UPSTREAM`;
- token missing/rejected -> `FAILED_AUTH`.

This matches ADR 0021's read/write asymmetry: reads can verify, writes are never
retried.

### D5. Polling outbox now, Redpanda later

PR1 chooses a polling relay:

```text
library_reservation_outbox(published_at is null)
  -> ApplicationEventPublisher.publishEvent(...)
  -> mark published_at
```

The relay is at-least-once. If the JVM crashes after publish but before
`published_at`, the event can be re-emitted. Consumers must therefore be
idempotent. PR1's consumer is intentionally small: metrics/log listener. EPIC 5
SSE becomes the second consumer. When there are two or more real consumers,
Redpanda is reconsidered.

## Alternatives Considered

### JobRunr / db-scheduler

Rejected for PR1. They are mature, database-backed job schedulers, and
db-scheduler explicitly advertises persistent, cluster-friendly execution. That
is attractive operationally, but the queue internals become a library
black box. The portfolio story here is the non-obvious part: designing
`SKIP LOCKED`, leases, terminal state, and outbox around a real school
reservation domain.

Sources:

- https://www.jobrunr.io/en/documentation/alternatives/
- https://github.com/kagkarlsson/db-scheduler

### Debezium CDC outbox

Rejected for PR1. Debezium's outbox event router is the standard CDC route:
capture committed outbox rows from the database log and publish them to Kafka.
It is excellent once Kafka Connect/Redpanda is already part of the platform, but
it adds too much infrastructure for the current single-node k3s ARM deployment.
The polling relay keeps the atomic outbox contract while avoiding premature
Kafka Connect operations.

Sources:

- https://debezium.io/documentation/reference/stable/transformations/outbox-event-router.html
- https://www.lydtechconsulting.com/blog/kafka-connect-debezium-demo

### LISTEN/NOTIFY as the primary wake-up path

Rejected as the primary mechanism. LISTEN/NOTIFY is useful for low-latency
wake-up, but it is not durable enough to be the only dispatcher. A listener that
is down misses notifications, and future scheduled/backoff jobs still require
polling when time "ticks over." The backlog item is therefore "polling mainline +
LISTEN/NOTIFY wake-up optimization."

Sources:

- https://pgdog.dev/blog/scaling-postgres-listen-notify
- https://worker.graphile.org/docs/faq

### Plain `FOR UPDATE` or advisory locks

Rejected. Plain `FOR UPDATE` serializes workers behind locked rows. Advisory
locks move lock lifecycle into application code and can leave stuck locks if
the application gets the lifecycle wrong. `FOR UPDATE SKIP LOCKED` gives row
locks tied to transactions, and competing workers skip rows already claimed by
others.

Sources:

- https://vladmihalcea.com/database-job-queue-skip-locked/
- https://planetscale.com/blog/keeping-a-postgres-queue-healthy
- https://www.netdata.cloud/academy/update-skip-locked/

## Implementation Notes

- The active-intent limit is one per library principal key. Duplicate
  `wait_for_library_seat` calls return the existing active intent instead of
  creating another queue row.
- `confirm_action` reserve creates an immediate intent with fixed
  `target_seat_id`, `action_audit_id`, and a five-minute expiry matching
  `ActionService.ACTION_TTL`. It then polls the intent for up to about 8 seconds
  and returns either the terminal outcome or "processing" with `intentId`.
- `wait_for_library_seat` is a deliberate exception to the usual
  `prepare_* -> confirm_action` write pattern. The tool description and response
  state that registration itself is consent and that the worker may reserve
  autonomously later.
- Outbox payloads include event type, intent ID, status, target seat ID, attempt
  count, outcome code/message, and timestamp only. They never include tokens,
  passwords, login IDs, `mcp_session_id`, or raw session keys.
- Metrics:
  - `library.intent{status,outcome}` counter for transitions.
  - `library.intent.depth` gauge for active queue depth.
  - `library.intent.outbox.unpublished` gauge for relay backlog.
  - `library.intent.outbox.relay{event_type}` counter for dispatches.

## Verification

PR1 test coverage:

- state-machine transition unit tests;
- native claim competition: two concurrent claimers produce one claim;
- worker happy path;
- same-seat grouping: one reserve call and remaining intents `FAILED_RACE`;
- missing session token -> `FAILED_AUTH`;
- no matching seat -> backoff and `WAITING_FOR_SEAT`;
- reaper verifies `getCurrentCharge()` for expired leases;
- outbox relay publishes and marks `published_at`;
- MCP tool registration in `McpServerConfig` and over Streamable HTTP self-dogfood.

`gradlew.bat test` passed on 2026-06-11.

PR2 verification:

- immediate reservation state-machine path can skip `WAITING_FOR_SEAT` and move
  from `REQUESTED` to `RESERVING`;
- native claim query includes immediate `REQUESTED` rows with `action_audit_id`;
- `ConfirmActionMcpTool` reserve path creates an immediate intent and does not
  call `LibraryReservationConnector.reserve()` directly;
- worker skips seat scanning for immediate intents and uses the fixed target
  seat;
- recent same-seat immediate terminal attempts suppress later tick-boundary
  duplicate upstream writes;
- same-seat k6 100 confirm run passed thresholds and WireMock count verified one
  upstream reserve call.

## Interview Questions

1. Why is the Pyxis reserve call outside the claim transaction?
   - Because DB row locks should protect only queue ownership, not slow network
     calls. Holding locks across HTTP calls increases contention and can delay
     vacuum.
2. Why does the reaper call `getCurrentCharge()` instead of retrying `reserve()`?
   - Reserve is not idempotent. A timeout can mean "accepted but response lost."
     A read verifies the real state without risking a duplicate write.
3. Why start with a polling outbox instead of Debezium?
   - The outbox table already gives atomic state+event writes. Debezium adds
     Kafka Connect operations that are not justified until there are multiple
     real consumers.
