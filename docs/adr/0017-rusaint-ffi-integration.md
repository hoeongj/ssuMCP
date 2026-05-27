# ADR 0017: rusaint FFI integration for realtime SAINT

- **Status**: Accepted
- **Date**: 2026-05-22
- **Scope**: SmartID/u-SAINT auth callback, realtime schedule connector, realtime grades connector, backend Docker image

## Context

The Java WebDynpro connector path for `get_my_schedule` and
`get_my_grades` accumulated many small protocol fixes over several days:
portal cookies, WAF cookies, WebDynpro hidden fields, initial
SAPEVENTQUEUE events, direct ECC versus portal iframe entry, and
`sap-ext-sid` routing. Each fix was plausible, but production behavior
still depended on SAP NetWeaver state that we did not fully control.

`yourssu/rusaint` already contains a maintained Rust implementation of
the same u-SAINT WebDynpro flows. Local ground-truth testing on
2026-05-22 confirmed that the `rusaint-cli` can authenticate and fetch
real schedule and grades data for the target account. The project should
stop owning the low-level SAP protocol and move product-specific value to
the ssuAI domain layer: encrypted session storage, caching, normalized
DTOs, cross-source tools, and observability.

## Decision

Use `rusaint-ffi` through generated UniFFI Kotlin bindings inside the
Spring Boot backend.

The SmartID callback continues to receive `sToken` and `sIdno`, but
`SaintSsoService` no longer consumes those tokens through the old Java
two-phase portal-cookie probe. Instead it calls
`USaintSessionBuilder.withToken(sIdno, sToken)` exactly once, serializes
the resulting rusaint session through `USaintSession.toJson()`, and
stores that JSON in the existing AES-GCM `SaintSessionStore`.

Schedule and grades connectors load the stored JSON with
`USaintSessionBuilder.fromJson(...)` and delegate the SAP WebDynpro
protocol to rusaint. The connector boundary still returns the existing
ssuAI DTOs, so controllers, MCP tools, frontend calls, and session-expiry
handling stay stable.

Production defaults switch to:

```yaml
ssuai.connector.saint-schedule: rusaint
ssuai.connector.saint-grades: rusaint
```

Dev/test defaults remain `mock`, and the legacy `real` Java connectors
stay in the tree for comparison until a follow-up cleanup removes the
failed WebDynpro reverse-engineering path.

## Build And Deploy

The backend Dockerfile builds `librusaint_ffi.so` in a Rust stage pinned
to commit `c2bdcf91c6efb313b971efa2a8a67ed79ad77b4b`, then copies the
shared library into `/usr/local/lib` in the runtime image. The JVM loads
it through JNA using the UniFFI default library name `rusaint_ffi` and
`LD_LIBRARY_PATH=/usr/local/lib`.

The repository remains public. The local `rusaint/` checkout, scratch
files, and credential memo files stay gitignored and are not vendored.

## Security

- `sToken` and `sIdno` remain method-scoped callback inputs and are never
  logged or persisted.
- Serialized rusaint session JSON is treated as credential material. It
  is encrypted at rest by `SaintSessionStore` and never logged.
- Logs keep using `SaintSessionStore.fingerprint(...)` for correlation.
- Existing stored cookie-header entries are not migrated. They expire
  naturally and users re-run SmartID SSO.

## Consequences

- ssuAI no longer owns SAP WebDynpro protocol details for realtime
  schedule and grades.
- The backend now has Kotlin, coroutines, JNA, and a native shared
  library in the runtime image.
- Local unit tests can mock `RusaintClient`; real rusaint verification is
  an env-gated/manual smoke because it requires private school
  credentials and live u-SAINT availability.
- A follow-up cleanup should remove unused Java WebDynpro support classes
  once the rusaint path is verified in production.
