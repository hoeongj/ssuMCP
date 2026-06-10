# ADR 0021 — Pyxis fault tolerance with Resilience4j (circuit breaker + selective retry)

- **Status**: Accepted — implemented 2026-06-10 (Pyxis library connectors).
  LLM per-provider cooldown is the same pattern, deferred to its own PR
  (see "Scope / deferred").
- **Date**: 2026-06-10
- **Scope**:
  - `com.ssuai.global.resilience.PyxisResilience` (new),
  - `RealLibraryReservationConnector` (read + write paths),
  - `RealLibrarySeatConnector` (seat-status + per-seat reads),
  - `docs/architecture.md` external-call section.

## Context

All library features call the school's **Pyxis** system (`oasis.ssu.ac.kr`) over
HTTP. Two problems:

1. **No failure isolation.** When Pyxis is slow or 5xx-ing, every request hits it
   and our request threads block on the timeout. Worse, **all users share one egress
   IP** (single k3s node — see the "요청별 Pod 기각" decision in MASTERPLAN), so an
   unthrottled burst of retries against a struggling upstream risks an IP ban or
   looking like a DoS to the school's WAF.
2. **No memory of failure.** A dead upstream is retried on every request.

This is the **L3** of the upstream-protection design in MASTERPLAN (L1 cache +
single-flight, L2 outbound token bucket, **L3 circuit breaker**, L4 queue
serialization).

## Decision

Add **Resilience4j** at the connector seam — which is ideal because every Pyxis
call already funnels through `RestClient` and normalizes failures into
`ConnectorTimeoutException` / `ConnectorUnavailableException` (transient) vs
`LibrarySeatNotAvailableException` / `LibraryAuthRequiredException` /
`ConnectorParseException` (business/auth).

### Integration: core (functional), not the Spring Boot starter

We use `resilience4j-circuitbreaker` / `-retry` / `-micrometer` **core** modules and
wrap calls in code, **not** the `resilience4j-spring-boot` annotation starter.

| Option | Verdict | Why |
|---|---|---|
| `@CircuitBreaker`/`@Retry` annotations + Spring Boot starter | rejected | The starter ties to Spring Boot autoconfig; **this project is on Spring Boot 4.0.6** and starter/autoconfig compatibility for Boot 4 was unverified (web search was rate-limited). AOP proxying adds a moving part. |
| **Core functional** | **chosen** | Pure Java, no Spring autoconfig → **framework-version agnostic**. Verified empirically: `gradle dependencies` resolves resilience4j 2.3.0 cleanly on the Boot 4 classpath. Lets read/write policy live in code (clearer than annotations). Fits the project's hand-rolled integration story. `resilience4j-micrometer` still exposes state to the existing Prometheus/Grafana. |

### Read vs write asymmetry (the key correctness decision)

One shared circuit breaker `"pyxis"` reflects upstream health across all callers.

- **Reads** (`getCurrentCharge`, seat status, per-seat) — `read()`: circuit breaker
  **+ retry** (3 attempts, exponential backoff 200ms×2). Reads are idempotent.
- **Writes** (`reserve`, `discharge`) — `write()`: circuit breaker **only, never
  retried**. These are **not idempotent** — a blind retry after a timeout could
  **double-book a seat**. On a write timeout the caller verifies the real outcome via
  `getCurrentCharge` instead of retrying.

### Business exceptions must not trip the breaker

`LibrarySeatNotAvailableException` ("seat taken"), `LibraryAuthRequiredException`
("please log in"), and `ConnectorParseException` are **ignored** by the breaker —
they are normal outcomes, not outages. Only `ConnectorTimeoutException` /
`ConnectorUnavailableException` count as failures. Without this, a busy exam-week
library (lots of "seat taken") would falsely open the circuit and deny service.

### Config (v1 constants, tunable later)

Failure-rate 50% over a 20-call count window, min 10 calls, open 30s, 3 half-open
probes, auto half-open. Slow-call threshold 8s. When open, both reads and writes
short-circuit and surface as `ConnectorUnavailableException` (existing "학교 서버가
불안정합니다" UX).

## Scope / deferred

- **LLM per-provider cooldown** (the other half of P1-3) uses the identical pattern
  (a `CircuitBreaker` per provider so a dead provider is skipped for a cooldown
  instead of retried every request). **Deferred to its own PR**: it must wrap the
  provider loop inside `LlmChatService`, a 400+ line god-class flagged for the P2-2
  split, and a circuit breaker interacts with the many existing provider-fallback
  tests. Doing it after/with the P2-2 refactor keeps it isolated and low-risk.

## Why this is a good portfolio story

Interview framing: *"Calling a legacy external system from a single shared IP, I
isolated failures with a circuit breaker and — crucially — retried reads but never
writes, because the reserve endpoint isn't idempotent and a retry-on-timeout could
double-book. Business outcomes like 'seat taken' are excluded from the breaker so a
busy library doesn't trip it."* This shows you understand idempotency, failure
classification, and protecting a third party, not just "I added Resilience4j."

## Consequences

- Reads now make up to 3 attempts on transient failure (connector tests updated to
  reflect this).
- Circuit-breaker/retry state is exported to Prometheus (`resilience4j_*` metrics) →
  a Grafana panel can show open/closed transitions and retry counts.
- Thresholds are constants for v1; promote to `@ConfigurationProperties` if tuning
  in prod without a rebuild becomes necessary.
