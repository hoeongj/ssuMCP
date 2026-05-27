# ADR 0016: SAINT portal-navigation ECC entry

- **Status**: Accepted
- **Date**: 2026-05-22
- **Scope**: realtime u-SAINT schedule and grades connectors

## Context

`get_my_schedule` and `get_my_grades` use portal cookies captured during
the SmartID/u-SAINT login flow. Direct ECC component requests looked
plausible, but browser captures showed the working path is not a plain
component URL:

- The first working ECC request enters through `ecc.ssu.ac.kr:8443`.
- The URL includes a portal-issued path matrix parameter,
  `;sap-ext-sid=...`.
- The request is a portal iframe `POST`, with `Referer:
  https://saint.ssu.ac.kr/`, `Origin: https://saint.ssu.ac.kr`, and
  `Sec-Fetch-*` navigation headers.
- A rendered response may still carry `sap-contextid=SID:ANON`; that
  value alone is not a login-failure signal. The real auth gate is
  whether the expected WebDynpro page structure renders.

The previous connector path mixed several direct-ECC assumptions:
standard HTTPS port, direct `GET`, and no portal-issued `sap-ext-sid`.
That made the connector sensitive to SAP routing and application-server
selection.

## Decision

Add a small `PortalNavigationService` in the SAINT connector layer.

For each realtime SAINT connector:

1. Fetch the authenticated `saint.ssu.ac.kr/irj/portal` page using the
   same cookie-aware `HttpClient` as the ECC request.
2. Extract a candidate WebDynpro entry URL for the target component
   (`ZCMW2102` for schedule, `ZCMB3W0017` for grades) from iframe,
   anchor, form, data attribute, or script text values.
3. Prefer candidates that contain `;sap-ext-sid=...`, and normalize
   `ecc.ssu.ac.kr` to port `8443`.
4. Enter the resolved URL with a `POST` that carries the portal iframe
   headers.
5. Continue the existing WebDynpro bootstrap flow: parse
   `sap-wd-secure-id`, send the initial placeholder-load event, then
   iterate with the existing button-press POSTs.

If no portal URL is configured, the connector uses the direct fallback
URL. This keeps low-level unit tests and local manual experiments from
calling the real portal. Production config enables portal navigation
through `ssuai.saint.schedule.portal-url` and
`ssuai.saint.grades.portal-url`.

## Security

- Do not log the resolved entry URL, because `sap-ext-sid` is session
  material.
- Do not log authenticated portal/ECC HTML bodies. Logs record only
  status, body byte count, component name, and boolean flags such as
  whether `sap-ext-sid` was present.
- Continue to log student identity only through the existing
  `SaintSessionStore.fingerprint(...)` helper.

## Consequences

- Schedule and grades now share the same portal-entry resolution rule.
- The direct ECC URL remains as a fallback and as a test seam, but it is
  not the production happy path.
- Future SAINT WebDynpro tools can reuse the same service by passing a
  different component name.
