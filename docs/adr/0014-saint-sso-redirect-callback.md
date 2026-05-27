# ADR 0014 — u-SAINT SSO via web redirect-callback (no SOP dance)

- **Status**: Accepted (PR 14b-3 `SaintSsoService` + PR 14b-4a/b
  `JwtAuthFilter` + `SaintSsoCallbackController` merged on `main`).
- **Date**: 2026-05-16
- **Scope**:
  `backend/src/main/java/com/ssuai/domain/auth/saint/SaintSsoCallbackController.java`,
  `backend/src/main/java/com/ssuai/domain/auth/saint/SaintSsoService.java`,
  `backend/src/main/java/com/ssuai/domain/auth/AuthProperties.java`,
  `docs/tasks/14-usaint-session-auth.md`, `docs/security.md`.

## Context

Task 14 needs ssuAI to confirm a SSU student's identity through the
official SmartID SSO flow (`smartid.ssu.ac.kr/Symtra_sso/smln.asp`).
SmartID's documented behavior is: user logs in on SmartID's own page,
then SmartID 302-redirects to the URL supplied as `apiReturnUrl`, with
two one-shot tokens (`sToken`, `sIdno`) on the query string. A subsequent
two-phase exchange against `saint.ssu.ac.kr` turns those tokens into a
confirmed student identity.

The well-known reference implementation, `jonghokim27/ssutoday`, runs in
a React Native WebView. Its trick is to point `apiReturnUrl` at
`https://saint.ssu.ac.kr/webSSO/sso.jsp` and let the WebView's
`onLoadStart` callback read the redirect URL and harvest the query
params. That works because RN WebView can observe any URL the user
navigates to, regardless of origin — it's a native API, not a browser
API.

Web is different. A browser-side popup attempt against
`saint.ssu.ac.kr` from `ssuai.vercel.app` hits Same-Origin Policy
immediately:

- `popup.document.cookie` — blocked across origins.
- `popup.location.href` — blocked across origins (only the opener can
  *write*, not *read*).
- `postMessage` — needs a listener on the target side. We can't inject
  one into `saint.ssu.ac.kr`.

Task 13's library-login work already burned this approach (Task 13 §7
#1 spike, RESOLVED NEGATIVE, 2026-05-15). We do not want to repeat it
for u-SAINT.

## Decision

**Make ssuAI's own backend the `apiReturnUrl` target.**

```text
Browser                       SmartID                    saint.ssu.ac.kr           ssuAI backend
───────                       ───────                    ─────────────             ─────────────
GET /api/auth/saint/sso-init  ─────────────────────────────────────────────────►   302 to smartid?apiReturnUrl=ssuai-backend/api/auth/saint/sso-callback
                              ◄──── 302 ────────────────────────────────────────
GET smartid/smln.asp?apiReturnUrl=…
[user enters SSU id+pw]
                              SmartID validates
                              ◄──── 302 to apiReturnUrl?sToken=…&sIdno=… ──
GET ssuai-backend/api/auth/saint/sso-callback?sToken=…&sIdno=…
                                                                                  reads @RequestParam (same origin, no SOP)
                                                                                  calls SaintSsoService.authenticate(sToken, sIdno)
                                                                                      Phase 1: GET saint/webSSO/sso.jsp?…
                                                                                      Phase 2: GET saint/irj/portal (with phase 1 cookies)
                                                                                      → UsaintAuthResult(studentId, name, major, status)
                                                                                  StudentService.upsertOnLogin(...)
                                                                                  JwtProvider.issueRefresh(student)
                              ◄──── 302 to ssuai.vercel.app/auth/return?ok=1 ──
                                    Set-Cookie: ssuai_refresh=…; HttpOnly; Secure; SameSite=None; Path=/api/auth
                                    (prod cross-site; dev/test override to SameSite=Lax — see "Cross-site cookie auth")
GET /auth/return?ok=1
[frontend POSTs /api/auth/refresh, cookie auto-attached]
GET ssuai-backend/api/auth/refresh
                                                                                  reads cookie, JwtProvider.parse(REFRESH)
                                                                                  issues new access + new refresh (rotated)
                              ◄──── 200 { accessToken, accessTtlSeconds } ──
[frontend stores accessToken in memory]
```

The browser navigates to *ssuAI's own origin* to drop the `sToken` and
`sIdno`. The controller reads them via `@RequestParam` — no
cross-origin read, no SOP, no `postMessage`, no popup, no native
interceptor.

The two one-shot tokens never leave `SaintSsoService.authenticate(...)`.
After Phase 2 returns the parsed identity, `sToken` and `sIdno` become
local-variable garbage. Phase 1's portal cookies are also discarded
once Phase 2 has read the dashboard HTML.

## Trade-offs and assumptions

- **Assumes SmartID accepts an arbitrary `apiReturnUrl`.** ssutoday used
  `https://saint.ssu.ac.kr/webSSO/sso.jsp` (a saint subdomain), which is
  plausibly the whitelist. Task 14 spec §7 #1 + §10 stop-and-flag #1
  carry a spike to verify this against the real SmartID — if SmartID
  refuses, the entire web port collapses and we have to fall back to a
  browser extension, a tiny mobile app, or negotiate a whitelist with
  SSU IT. The backend work in PR 14b-4 stands either way; only the
  frontend SSO entry button is rendered moot.
- **HTTPS is mandatory in prod.** SmartID will refuse to 302 to a
  plaintext URL. `application-prod.yml` forces both `SSUAI_API_BASE_URL`
  and `SSUAI_FRONTEND_ORIGIN` to be set explicitly (empty-default
  fails fast at startup, same pattern as `WebCorsProdConfig`).
- **Refresh-cookie scoping.** `Path=/api/auth` means the cookie is
  attached only to `/api/auth/refresh` (and any future logout) — not
  every `/api/**` request. That keeps the cookie out of `/api/meals`,
  `/api/library`, etc., shrinking the surface for accidental log leaks
  and CSRF.
- **No Spring Security.** `JwtAuthFilter` parses `Authorization: Bearer`
  and pushes the student id onto the request as attributes; controllers
  decide whether the absent attribute is a 401 or fine for anonymous.
  This keeps the auth stack small and inspectable for a portfolio
  project. If the threat surface grows (CSRF tokens, role-based
  authorization, etc.) the filter is the natural place to attach
  Spring Security later — controllers won't need to change.
- **No refresh-token revocation list.** Refresh JWTs are self-contained
  and valid for 14 days. Rotation on every `/api/auth/refresh` is
  best-practice hygiene but does not invalidate the old token. A
  per-user allowlist of currently-valid refresh-jti values is the
  obvious follow-up if a "force logout other devices" requirement
  appears.

## Alternatives considered

- **`window.open` popup + `postMessage`** — needs a listener on the
  oasis/saint origin. We can't inject one. Rejected after Task 13's
  spike confirmed SOP blocks every variant.
- **Bookmarklet** — same SOP wall, plus distribution UX is poor.
- **Browser extension** — has full cross-origin access; would work, but
  installation friction is real for a student app (Chrome Web Store
  review + per-browser variants). Kept as a Plan B if SmartID refuses
  the apiReturnUrl whitelist.
- **Mobile app first** — RN/Expo WebView is exactly what ssutoday uses
  and would work today. But shipping a web flow first is core to the
  Task 14 scope (and to the broader ssuAI deliverable: a web product
  any student can use without installing anything).
- **Proxy SmartID through ssuAI** — would mean ssuAI sees the user's
  SSU password. Rejected by `docs/security.md` §5 ("Don't proxy a
  user's school login through ssuAI for 'convenience'").

## Consequences

- The user's SSU password never crosses ssuAI's trust boundary. SmartID
  handles it on its own login page; ssuAI sees only short-lived tokens.
- The library-side capture problem (Task 13) and the u-SAINT-side
  capture problem (Task 14) are now answered by **two completely
  different mechanisms** — manual paste / extension / bookmarklet for
  oasis (because there is no oasis SSO), and SSO-redirect-callback for
  saint (because SmartID is a real SSO). Both end up writing to ssuAI's
  own session/store; the difference is at the *capture* step.
- Realtime u-SAINT data tools (성적, 시간표, 출결 — Phase 3 / Task 15+)
  cannot reuse the cookies thrown away here. They will need either a
  fresh SSO each call (UX cost: one redirect per data fetch) or a
  session-retention policy (security cost: a stolen cookie has a TTL of
  several minutes). That decision is deferred to a separate ADR when
  the first realtime data tool actually ships.

## Addendum (2026-05-16, prod-live retrospective) — Cross-site cookie auth

Task 14 went prod-live on 2026-05-16 over four PRs (#112, #113, #114,
#116). The auth design above was correct at the *capture* step
(SSO-redirect-callback worked exactly as described) but **the
post-callback refresh handshake hit two cross-site cookie-auth
layers** that the original ADR did not call out. Recording here so the
next ADR / engineer doesn't repeat the discovery.

ssuAI's deployment topology is split-origin:

- frontend at `https://ssuai.vercel.app` (Vercel)
- backend at `https://ssumcp.duckdns.org` (k3s)

The `/api/auth/refresh` round-trip after SSO callback is therefore
**cross-site**, and modern Chromium-class browsers require BOTH of the
following on every such call:

1. **Cookie attribute `SameSite=None; Secure`** — without it, the
   browser strips the refresh cookie from cross-site POSTs (or warns +
   degrades over time per the Privacy Sandbox roadmap). The original
   spec defaulted to `SameSite=Lax`, which silently works in dev
   (`localhost:3000` ↔ `localhost:8080` is same-site under the
   public-suffix definition) but **fails the moment the frontend and
   backend live on different registrable domains**. Fixed in PR #114
   via `application-prod.yml`:
   `ssuai.auth.refresh-cookie.same-site: None`.

2. **Response header `Access-Control-Allow-Credentials: true`** —
   independent of cookie attributes. When `fetch(..., { credentials:
   'include' })` is used cross-origin, the browser:
   - sends the cookie with the request (assuming layer 1 is satisfied),
   - stores any `Set-Cookie` in the response (assuming
     `SameSite=None; Secure`),
   - but **refuses to expose the response body to JavaScript** unless
     the server explicitly opts in via this header. The fetch promise
     rejects with a network-error TypeError, NOT a meaningful HTTP
     status.

   `CorsConfiguration.allowCredentials(true)` is the Spring-side switch
   (PR #116, `ApiCorsDefaults.java`). It requires
   `allowedOrigins` to be an **explicit origin list**, not `*`, which we
   already had.

The intermediate state (layer 1 fixed, layer 2 unfixed) is uniquely
hard to debug:

- DevTools Network shows `POST /api/auth/refresh` → `200 OK` with valid
  `Set-Cookie: ssuai_refresh=…`. Looks healthy.
- The new cookie *is* stored — the next request gets it.
- Backend access log shows the same 200, no error.
- But the frontend's `await response.json()` throws, so the JS catch
  block fires and the UI displays "세션 갱신 실패".

The only durable signal is the **browser Console CORS warning**:
> Access to fetch at 'https://ssumcp.duckdns.org/api/auth/refresh'
> from origin 'https://ssuai.vercel.app' has been blocked by CORS
> policy: The value of the 'Access-Control-Allow-Credentials' header
> in the response is '' which must be 'true' when the request's
> credentials mode is 'include'.

### Implications for related work

- **Future split-origin features** (cross-site WebSocket auth, mobile
  embed via WebView, Phase 4 confirmation pages, …) reuse exactly the
  same two-layer requirement. Treat any new cookie-bearing endpoint
  the same way.
- **`docs/security.md` §7 / §8** were updated alongside this ADR — the
  cookie-attribute table now reflects the prod override and the CORS
  section explicitly enumerates the `Access-Control-Allow-Credentials`
  requirement.
- **Test coverage** — `WebCorsConfigTest` / `WebCorsProdConfigTest` now
  assert `config.getAllowCredentials() == true`, so a regression that
  flips this back to `false` fails CI rather than silently breaking
  login on the next prod deploy.

See `TROUBLESHOOTING.md` 2026-05-16 entry for the in-depth debugging
trail.
