# ADR 0013 — Library session capture: phantom-token adaptation to legacy proprietary auth

- **Status**: Accepted for PR 13a (#80 merged — backend session store + 401 auth-required mapping live). **Revisit** when PR 13c lands with the chosen capture mechanism (manual paste vs extension vs bookmarklet — Task 13 spec §12).
- **Date**: 2026-05-15 (corrected 2026-05-15 evening after auth-mechanism reverse-engineering)
- **Scope**: `backend/src/main/java/com/ssuai/domain/library/auth/**`,
  `backend/src/main/java/com/ssuai/domain/library/service/LibrarySeatService.java`,
  `backend/src/main/java/com/ssuai/global/exception/LibraryAuthRequiredException.java`,
  `docs/tasks/13-library-session-auth.md`, `docs/security.md`.

> **Correction note (2026-05-15 evening):** earlier drafts of this ADR
> referred to the captured credential as an "`ssotoken` cookie." A real
> authenticated SPA capture against `oasis.ssu.ac.kr` proved the API
> auth is the **`Pyxis-Auth-Token` request header**, not a cookie. The
> `ssotoken` cookie only authenticates the Angular SPA shell and is
> unrelated to Pyxis API auth. All references below now use
> "Pyxis-Auth-Token" / "session token" terminology accordingly. The
> architectural argument is unchanged — it was always about "an opaque
> upstream auth credential," and what shape it takes (cookie vs
> header) does not change the phantom-token boundary, the capture
> mechanism set in §12, or the storage policy.

## Context

`docs/tasks/12-library-seat-status.md` shipped a read-only library
seat MCP tool against a deterministic mock. The follow-up PR 1b was
meant to swap the mock for a real `oasis.ssu.ac.kr` connector. Research
on 2026-05-15 found:

1. Every Pyxis API endpoint (`/pyxis-api/api/smuf/reading-rooms` and
   neighbors) returns
   `{"success":false,"code":"error.authentication.needLogin"}` for
   anonymous callers. Public-data assumption from Task 12 was wrong.
2. Pyxis has its own login (`POST /pyxis-api/api/login` with
   username/password), distinct from u-SAINT's SmartID SSO. There is
   no OAuth, no public refresh flow, no client_credentials grant.
3. After a successful login on `oasis.ssu.ac.kr/login`, the only
   credential ssuAI needs is the `ssotoken` cookie. It is a long-ish
   session cookie used as `Cookie: ssotoken=...` on every subsequent
   API call.

This sits at an awkward intersection of three industry trends visible
in 2026:

- **MCP Authorization Specification (2025-11-25)** mandates OAuth 2.1
  Resource Indicators (RFC 8707), audience-bound tokens, least
  privilege. *Pyxis speaks none of OAuth.*
- **Phantom Token Pattern** — the AI / chatbot layer should never see
  the real upstream credential; a thin proxy holds it and injects it
  only at egress.
- **Browser session delegation** (Manus Browser Operator, Anthropic
  Claude Browser Extension) — let the user authenticate normally in
  their browser, then *delegate* the resulting session to the agent
  rather than have the agent collect passwords.

ssuAI's reference project, `jonghokim27/ssutoday`, solves a structurally
similar problem (SmartID SSO → saint.ssu.ac.kr session) on React Native.
RN's WebView is allowed to read URLs across origins, so ssutoday
intercepts the SSO redirect URL via `onLoadStart` and extracts
`sToken` + `sIdno` from its query string. We attempted the analogous
web pattern (popup window + parent reads `popup.document.cookie`) and
discovered: **browser SOP forbids it**. `popup.document.cookie` throws
`SecurityError`; `popup.location.href` throws `SecurityError`;
`postMessage` requires a listener inside the oasis page that we have
no way to inject. Native-only.

So we need a capture pattern that:

1. Lets the user log in on the **official oasis page** (no passwords
   touching ssuAI).
2. Gets the resulting `ssotoken` into ssuAI's backend somehow,
   respecting SOP.
3. Holds the token in a manner aligned with phantom-token principles
   (LLM never sees it; only the connector that builds the upstream
   request does).
4. Survives upstream session lifetime so the user doesn't re-auth on
   every chat message.

## Decision

Adopt **phantom-token-principle adaptation to legacy proprietary auth**
across three concrete pieces:

### 1. Token boundary — store only at the connector layer

ssuAI is split into:

```
[ LLM / chatbot ]  ──no token────►  [ MCP tool layer ]  ──no token────►  [ Service layer ]
                                                                                │
                                                                                ▼
                                                                       [ LibrarySessionStore ]  ──Pyxis-Auth-Token: <token>──►  oasis.ssu.ac.kr
                                                                                ▲
                                                          [ LibrarySeatService.getSeatStatusForSession() ]
                                                                       reads token only here,
                                                                       only when connector mode = real
```

The LLM context never contains the captured token. The MCP tool layer
(`LibrarySeatMcpTool`) never contains it. The `LibrarySeatService`
asks `LibrarySessionStore` for it **inside** the same method that
makes the upstream call, scoped to the local variable lifetime. The
connector then injects it as a `Pyxis-Auth-Token` request header on
the call to `/pyxis-api/1/seat-rooms`. This matches what 2026
phantom-token literature describes as "the agent never possesses
long-lived user credentials."

### 2. Capture mechanism — explicit user action, no password proxying

PR 13a (`#80`) builds the backend store + 401 mapping. The mechanism
that gets a captured `Pyxis-Auth-Token` INTO the store is determined
in PR 13c from spec §12. **At this ADR's date the choice is not
finalized**, but the architectural constraint is fixed:

- The user authenticates on `oasis.ssu.ac.kr` directly. ssuAI is never
  on the page that touches the password.
- Only the `Pyxis-Auth-Token` value crosses ssuAI's trust boundary.
  No username, no password, no third-party derivative.
- The capture endpoint (`POST /api/library/session`) is same-origin
  only, shape-validated (`^[A-Za-z0-9._\-+/=]+$`, 8-4096 chars).

The 5 mechanisms tracked in spec §12 (manual paste / extension /
bookmarklet / u-SAINT pivot / mock-only) all satisfy this constraint.
PR 13c picks one; this ADR remains valid regardless of which.

### 3. Token persistence — TTL-bounded, fingerprint-only logging

`LibrarySessionStore` (Task 13 PR 13a) holds entries with:

- Key: ssuAI session id (`HttpSession.getId()` for MVP; will migrate
  to `Student.studentId` after Task 14 lands ssuAI's own user system)
- Value: raw `Pyxis-Auth-Token` string
- TTL: defaults to 2 hours, configurable via
  `ssuai.library.session.ttl`. **Final TTL choice pending the
  spike documented in `scripts/spike-ssotoken-ttl.{sh,ps1}`** (see PR
  [#81](https://github.com/hoeongj/ssuAI/pull/81)). The decision tree:

  | Measured Pyxis TTL | Storage policy |
  |---|---|
  | ≥ 1 week | In-memory entry survives until JVM restart OR Pyxis 401. Persist to H2 + AES-GCM if multi-instance. |
  | hours – 1 day | Same as above; users re-capture daily. Acceptable. |
  | < 2 hours sliding | Escalate. Likely options: hunt for a Pyxis refresh endpoint, surface "로그인 상태 유지" guidance, or revisit spec §12 D / F. |

- Logging: only `sha256(token).hex()[:8]` fingerprint appears in logs.
  Raw token never logged. Implemented as
  `LibrarySessionStore.fingerprint(String)`.

### 4. Error contract — explicit, parseable, frontend-actionable

When the seat service is in real mode and the current session has no
captured token, throw `LibraryAuthRequiredException`. The
`GlobalExceptionHandler` maps it to HTTP 401 with code
`LIBRARY_SESSION_REQUIRED`. The frontend keys off this code to render
the "도서관 로그인" CTA. The error code is distinct from generic 401
so the frontend can branch precisely.

## Why not the discarded alternatives

- **Backend password proxy (spec §12 F)**: would require ssuAI to
  process the user's SSU password to call `/pyxis-api/api/login` on
  their behalf. Violates the trust boundary. Single-server compromise
  exposes all users' u-SAINT credentials too (same SSU account, used
  for grades / 수강신청 / 장학금). 2026 articles ("Your AI Agent Knows
  Your Passwords — Here's How I Fixed It") cite this exact pattern as
  the anti-pattern.
- **Popup + cookie read**: shown impossible by SOP spike (Task 13 §7
  #1, resolved negative on 2026-05-15). Doubly moot since the API
  doesn't even use the `ssotoken` cookie for auth — though the SOP
  problem would still have killed any cross-origin storage read,
  including localStorage where the SPA actually keeps the token.
- **Local MCP server holding credentials** (a fashionable 2026 idea):
  fails ssuAI's mobile-first usage — most students ask "지금 4층
  자리 있어?" from their phone, not from a PC running a local server.
- **Full OAuth flow**: Pyxis does not support OAuth. There is no
  authorization endpoint to redirect to.

## Consequences

### Positive

- LLM remains decoupled from credentials entirely. Phantom-token
  principle is preserved in spirit even though Pyxis is OAuth-illiterate.
- Backend session store shape (`LibrarySessionStore` +
  `LibraryAuthRequiredException` + `POST /api/library/session`) is
  **mechanism-agnostic**. Any of the 5 capture mechanisms in spec §12
  can be wired in PR 13c without re-shaping the backend.
- Reusable for any future SSU service that follows the same legacy
  auth shape (single cookie, no OAuth). The pattern generalizes.
- Honest portfolio narrative: not "we picked the shiniest tech," but
  "we adapted a current spec's *principle* to a legacy upstream
  faithfully."

### Negative

- The user has to interact with the capture flow at least once
  per token lifetime. UX depends entirely on the TTL spike outcome
  (see decision tree above).
- Without a real frontend capture mechanism shipped (PR 13c blocked
  on spec §12 decision), the library data plane stays mocked. PR 13a
  is preparation, not delivery.
- We are not OAuth-compliant. If SSU IT ever publishes an OAuth bridge
  for library, this whole pattern becomes obsolete on that day —
  acceptable risk.

## Implementation status

- **PR 13a** ([#80](https://github.com/hoeongj/ssuAI/pull/80)) —
  shipped: `LibrarySessionStore`, `LibrarySessionProperties`,
  `LibraryAuthRequiredException`, `LIBRARY_SESSION_REQUIRED` error
  code, `POST /api/library/session`, auth-aware
  `LibrarySeatService.getSeatStatusForSession()`. Backend
  mechanism-ready, gated behind `ssuai.connector.library-seat=mock`
  default.
- **TTL measurement** ([#81](https://github.com/hoeongj/ssuAI/pull/81)) —
  spike script. Final storage policy depends on this number.
- **PR 13b** — `RealLibrarySeatConnector`. Pending PR 13a merge +
  having a captured token to pin parse fixtures.
- **PR 13c** — frontend capture flow. **Blocked** on spec §12
  decision + TTL spike result.

## References

- [`docs/tasks/13-library-session-auth.md`](../tasks/13-library-session-auth.md)
- [`docs/tasks/14-usaint-session-auth.md`](../tasks/14-usaint-session-auth.md) — sibling Phase 3 task, different shape
- [`jonghokim27/ssutoday`](https://github.com/jonghokim27/ssutoday) — reference for the analogous SmartID flow
- MCP Authorization Specification (2025-11-25) — OAuth 2.1 Resource Indicators
- "Credential Protection for AI Agents: The Phantom Token Pattern" — pattern source
- Auth0 Token Vault — token persistence reference
