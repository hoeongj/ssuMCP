# ssuAI Architecture

## Goals of this document

Give a single, scannable view of how ssuAI is put together: the layers, the
packages, the runtime processes, and the contracts between them. Anyone
joining the project (or a reviewer) should be able to read this in five
minutes and know where any new feature should live.

## Non-goals

This document covers the **MVP plus near-term extension points**, not the
final-state system. It does not specify class names beyond the package layout
(those belong in per-feature design docs), does not design authentication,
LMS, or u-SAINT in detail, and does not cover deployment topology — those
get their own docs once they are real.

---

## 1. System context

```mermaid
flowchart LR
    subgraph Clients
        U1[Student via Web]
        U2[Student via Chatbot UI]
        U3[Claude Desktop / IDE MCP client]
    end

    subgraph ssuAI["ssuAI Backend (single Spring Boot app)"]
        REST[REST Controllers]
        MCP[MCP Server tools]
        SVC[Service layer]
        REPO[(JPA Repositories)]
        CONN[Connectors]
    end

    subgraph Infra
        PG[(PostgreSQL)]
        RD[(Redis cache)]
    end

    subgraph School["Soongsil systems"]
        MEAL[Cafeteria site]
        LIB[Library site]
        LMS[LMS]
        USAINT[u-SAINT]
    end

    U1 -->|HTTP/JSON| REST
    U2 -->|HTTP/JSON| REST
    U3 -->|MCP protocol| MCP
    REST --> SVC
    MCP --> SVC
    SVC --> REPO
    SVC --> CONN
    REPO --> PG
    SVC --> RD
    CONN --> MEAL
    CONN --> LIB
    CONN -.future.-> LMS
    CONN -.future.-> USAINT
```

Solid arrows are MVP. Dashed arrows are deferred (LMS, u-SAINT) and exist on
the diagram only to show **where** they will plug in — not as work scheduled
for week 1.

---

## 2. Runtime topology

The MVP is **one Spring Boot process**. It exposes:

- A REST API for the web dashboard and chatbot UI.
- An MCP server (via Spring AI) for Claude Desktop / Claude Code.

Both surfaces share the **same Service layer**, the same Connectors, and the
same Redis/PostgreSQL infrastructure. There is no duplicated business logic
between REST and MCP.

```
┌────────────────────────────────────────────┐
│  ssuAI Spring Boot application (one JVM)   │
│                                            │
│   ┌───────────┐         ┌──────────────┐   │
│   │ REST API  │         │ MCP server   │   │
│   └─────┬─────┘         └──────┬───────┘   │
│         └──────────┬───────────┘           │
│                    ▼                       │
│              Service layer                 │
│                    │                       │
│       ┌────────────┴────────────┐          │
│       ▼                         ▼          │
│  Repositories             Connectors       │
└───────┬──────────────────────────┬─────────┘
        ▼                          ▼
   PostgreSQL                External school
   + Redis                      systems
```

**Why one process for now:** one deployment unit, one config surface, no
duplicated business logic, fits Spring AI's MCP server support out of the
box. If load or independent release cadence ever justifies it, the MCP
server can be split into a separate process — but only after there is a
real reason to.

---

## 3. Layered architecture

The project follows the layered structure described in `CLAUDE.md`. The
short version of who owns what:

- **Controller** — receive HTTP requests, validate request DTOs, call a
  service, return a response DTO. No business decisions, no DB access, no
  parsing of school-site HTML.
- **Service** — application logic, transaction boundaries, deciding cache
  strategy, combining Repository and Connector results. No browser
  automation, no SQL strings, no HTML parsing.
- **Repository** — database access only (Spring Data JPA).
- **Connector** — every external school-system call. Owns the HTTP client,
  the Jsoup/Playwright parsing, and the mapping into internal DTOs.
  Connectors are interfaces with at least one implementation; they must be
  swappable and testable.

A request never crosses a layer it shouldn't. A Controller never calls a
Connector directly; a Connector never reads from the database.

---

## 4. Package layout

```
com.ssuai
├── global
│   ├── auth            // JwtProvider, JwtProperties, JwtTokenType, JwtClaims, InvalidJwtException
│   ├── config          // @Configuration classes — CORS, OpenAPI, TraceId filter, RestClient
│   ├── exception       // ConnectorException hierarchy, ApiException, GlobalExceptionHandler
│   └── response        // ApiResponse<T> envelope, ErrorResponse
└── domain
    ├── auth
    │   ├── controller  // SmartID / LMS SSO callback controllers (web-path auth)
    │   ├── dto         // Auth request / response DTOs
    │   ├── lms         // LmsSessionStore (AES-256-GCM, 2h TTL), LmsCredentialLoginService
    │   ├── mcp         // MCP auth session layer (Task 18)
    │   │   ├── McpAuthSession, McpAuthSessionId, McpProviderLink, McpAuthStateEntry
    │   │   ├── McpAuthSessionStore (LRU, 4h TTL), McpAuthStateStore (one-time, 10min TTL)
    │   │   ├── McpAuthService, McpAuthUrlFactory
    │   │   ├── McpSaintAuthController  // GET /api/mcp/auth/saint/start|callback
    │   │   ├── McpLmsAuthController    // GET /api/mcp/auth/lms/start|callback
    │   │   ├── McpLibraryAuthController // GET /api/mcp/auth/library/start|callback
    │   │   └── dto  // McpPrivateToolResponse<T>, McpAuthStatusResponse, etc.
    │   └── saint       // SaintSsoService — SmartID 2-phase SSO (phase1: token, phase2: portal)
    ├── campus          // controller / dto / service — 캠퍼스 시설 검색 (정적 데이터)
    ├── chat
    │   ├── controller  // ChatController — POST /api/chat
    │   ├── config      // LlmChatProperties, ChatMemoryProperties
    │   ├── dto         // ChatRequest/Response, OpenAI-compatible request/response DTOs
    │   ├── memory      // ChatConversationStore (in-memory LRU, 30m TTL, 12 turns cap)
    │   └── service
    │       ├── ChatService (interface), MockChatService
    │       ├── LlmChatService  // 10-provider fallback, MCP tool dispatch, secret guard
    │       └── llm  // LlmProvider (interface), LlmProviderConfig, LlmCompletionRequest/Result
    ├── dorm            // connector / controller / service — 레지던스홀 기숙사 식단
    ├── library
    │   ├── auth        // LibrarySessionStore (AES-256-GCM, 24h TTL), LibraryCredentialLoginService
    │   │   └── dto
    │   ├── connector   // LibraryBookConnector (mock / real Pyxis JSON API)
    │   │               // LibrarySeatConnector (mock / real oasis scrape)
    │   │               // LibraryLoansConnector (mock / real)
    │   ├── controller  // LibraryBookController, LibrarySeatController
    │   ├── dto         // LibraryBook, LibraryBookSearchResponse, LibrarySeatStatusResponse, etc.
    │   ├── mcp         // LibraryToolContext — ThreadLocal scope (chat path only)
    │   └── service     // LibraryBookService (LRU 200, 60s TTL), LibrarySeatService (30s TTL)
    │                   // LibraryLoansService
    ├── lms
    │   ├── connector   // LmsAssignmentsConnector (mock / real — Canvas LMS SSO)
    │   ├── controller  // LmsAssignmentsController — GET /api/lms/assignments
    │   ├── dto         // AssignmentItem, LmsAssignmentsResponse
    │   ├── mcp         // LmsToolContext — ThreadLocal scope (chat path only)
    │   └── service     // LmsAssignmentsService
    ├── mcp
    │   ├── config      // McpServerConfig — ToolCallbackProvider bean + tool readOnly/destructive annotations
    │   └── tool        // All @Tool classes (23 tools total — see §11)
    │                   // McpAuthHelper — shared principalKey lookup + AUTH_REQUIRED factory
    ├── meal
    │   ├── config      // MealFanOutConfig — parallelStream fan-out for weekly export
    │   ├── connector   // MealConnector (interface), MockMealConnector, RealMealConnector (Jsoup)
    │   ├── controller  // MealController — GET /api/meals/today|weekly
    │   ├── dto         // MealResponse, MealItem, MealRestaurant, MealType, WeeklyMealResponse
    │   └── service     // MealService + WeeklyMealCache (startup warm + @Scheduled Mon 06:00 KST)
    ├── notice
    │   ├── connector   // NoticeConnector (mock / real — scatch.ssu.ac.kr)
    │   │               // DepartmentNoticeConnector (real — ssufid API)
    │   ├── controller  // NoticeController — GET /api/notices/*
    │   ├── dto         // NoticeItem, NoticeListResponse, NoticeDetailResponse, NoticeCategoriesResponse
    │   └── service     // NoticeService + NoticeCache (5m TTL)
    ├── saint
    │   ├── connector   // SaintScheduleConnector, SaintGradesConnector (mock / real / rusaint)
    │   │               // SaintChapelConnector, SaintGraduationConnector, SaintScholarshipConnector
    │   ├── controller  // SaintScheduleController, SaintGradesController, etc.
    │   ├── dto         // ScheduleResponse, GradesResponse, ChapelInfo, GraduationRequirements, etc.
    │   ├── mcp         // SaintToolContext — ThreadLocal scope (chat path only)
    │   └── service     // SaintScheduleService, SaintGradesService, SaintExtendedService
    │                   // PortalNavigationService — resolves WebDynpro component entry URLs
    └── user
        ├── entity      // Student (JPA — studentId PK, name, lastLoginAt)
        ├── repository  // StudentRepository
        └── service     // StudentService.upsertOnLogin — upsert on SmartID callback
```

Rule: **do not create a package before there is code that needs it.** The
layout above reflects the actual on-disk tree as of Phase 3 complete.

---

## 5. Connector pattern

This is the most important pattern in the codebase. Every external
school-system call goes through a Connector.

### Shape

```java
public interface MealConnector {
    DailyMeal fetchMeal(LocalDate date);
}
```

For each Connector there are at least two implementations:

- `MockMealConnector` — returns deterministic fixture data. **Always present**
  in the codebase and clearly named `Mock*`. Used by tests, by local dev
  before the real site is reverse-engineered, and as a fallback worth
  considering for demos.
- `RealMealConnector` — real implementation (Jsoup for static pages,
  Playwright for JS-heavy or login-required pages, plain HTTP for JSON APIs).

### Selection

Profiles and a config property decide which one is wired:

```yaml
# application.yml
ssuai:
  connector:
    meal: mock        # mock | real
    library-book: mock
    library-seat: mock
```

Each implementation is registered with `@ConditionalOnProperty`, e.g.:

```java
@Component
@ConditionalOnProperty(name = "ssuai.connector.meal", havingValue = "mock", matchIfMissing = true)
class MockMealConnector implements MealConnector { ... }
```

Default is `mock` so a fresh checkout runs without any external dependency.
`application-prod.yml` flips the relevant entries to `real`.

### Boundaries

- Connectors return **internal DTOs**, never raw HTML, raw JSON, or a
  `Document` from Jsoup. The "shape of the school's site" stops at the
  Connector boundary.
- Connectors throw a typed `ConnectorException` (with subtypes like
  `ConnectorTimeoutException`, `ConnectorParseException`,
  `ConnectorUnavailableException`). The Service decides what to do with it
  — return stale cache, surface a 503, or fall back to mock.
- Connectors do **not** cache. Caching is a Service-layer concern (see §8).

---

## 6. Standard response & error contract

Every REST endpoint returns the same envelope so the frontend, the chatbot,
and any future client can parse responses uniformly.

### Success

```json
{
  "data": { "...": "..." },
  "error": null,
  "traceId": "f3c1...e9"
}
```

### Error

```json
{
  "data": null,
  "error": {
    "code": "CONNECTOR_UNAVAILABLE",
    "message": "Cafeteria site is temporarily unreachable."
  },
  "traceId": "f3c1...e9"
}
```

`ApiResponse<T>` lives in `global.response`. A `@RestControllerAdvice` in
`global.exception` maps exceptions to HTTP status + error code:

| Exception                       | HTTP | `error.code`             |
|---------------------------------|------|--------------------------|
| `MethodArgumentNotValidException` | 400  | `VALIDATION_FAILED`      |
| `ApiException` (domain-thrown)  | 4xx  | exception's own code     |
| `ConnectorTimeoutException`     | 504  | `CONNECTOR_TIMEOUT`      |
| `ConnectorUnavailableException` | 503  | `CONNECTOR_UNAVAILABLE`  |
| Anything else                   | 500  | `INTERNAL_ERROR`         |

The `traceId` is whatever Micrometer / Spring Boot's observability puts on
the current request — we propagate it into the response so a user-reported
error can be looked up in logs.

---

## 7. Caching strategy

The cache-aside pattern lives in the **Service layer** (not the
Connector, not the Controller). The table below describes the
*target shape*; the current MVP implements only the cafeteria entry —
see "Current implementation" below.

Redis is the eventual store; the MVP uses an in-memory `ConcurrentMap`
(`WeeklyMealCache`) as a stepping stone since the only cached data so
far is the cafeteria menu and it refreshes weekly. Moving to Redis is a
swap at the cache-aside service boundary, not a layer rewrite.

| Data                      | Key                                     | TTL                  | Notes                                                    |
|---------------------------|-----------------------------------------|----------------------|----------------------------------------------------------|
| Today's cafeteria meal    | `meal:{date}`                           | until midnight       | Menu rarely changes mid-day; refresh once per day.       |
| Library book search       | `library:book:{normalized-query}`       | 5 min                | Search results are stable enough; user can re-search.    |
| Library seat status       | `library:seat:{room-id}`                | 30 s                 | Changes minute-to-minute; very short TTL.                |
| (future) LMS assignments  | `lms:assignments:{userId}`              | 5–15 min             | Per-user; invalidated on user-triggered refresh.         |

Keys are namespaced (`<domain>:<entity>:<id>`) so a future bulk-invalidate
is straightforward.

Cache misses fall through to the Connector. Connector failures while a stale
cache value exists are an explicit Service decision — for the MVP, prefer
returning a 5xx and let the client retry rather than serving stale data
silently. Reconsider per-feature when real data arrives.

### Current implementation — `WeeklyMealCache`

The cafeteria menu changes once per week. Rather than scrape
`soongguri.com` on every chat turn or REST request, `WeeklyMealCache`
preloads the data:

- `@PostConstruct` warms the cache for the current week on application
  startup (all 6 restaurants × 7 days = 42 entries).
- `@Scheduled(cron = "0 0 6 ? * MON", zone = "Asia/Seoul")` refreshes
  the cache every Monday at 06:00 KST.
- `MealService.getMealForRestaurant(date, restaurant)` is a cache-aside
  lookup with connector fallback for cache misses (e.g. dates outside
  the current week).

This is the only cache active in the MVP. Library / LMS rows in the
table above stay aspirational until those domains land.

---

## 8. Configuration & profiles

Three profiles to start:

- `dev` — default for local runs. All connectors `mock`. H2 or local
  Postgres. Permissive logging.
- `test` — used by Gradle test tasks. All connectors `mock`. H2 in-memory.
  No external network.
- `prod` — real Postgres, real Redis, connectors flipped to `real` per
  feature as they become production-ready.

Files: `application.yml` (shared defaults) + `application-{profile}.yml`.
Secrets are **never** committed. They come from environment variables and
are referenced as `${ENV_VAR_NAME}` in the YAML.

The MVP needs no secrets (all data is public). The placeholders below are
documented now so they're ready when the relevant features arrive:

| Env var                | Used by         | When           |
|------------------------|-----------------|----------------|
| `SSUAI_DB_URL`         | Spring Data JPA | from Task 14 — defaults to in-memory H2 in PostgreSQL mode |
| `SSUAI_DB_USERNAME` / `SSUAI_DB_PASSWORD` | Spring Data JPA | from Task 14 |
| `SSUAI_REDIS_URL`      | Cache           | future — current MVP uses in-memory `ConcurrentMap` |
| `SSUAI_GEMINI_API_KEY`, `SSUAI_GROQ_API_KEY`, `SSUAI_CEREBRAS_API_KEY`, `SSUAI_DEEPINFRA_API_KEY`, `SSUAI_SAMBANOVA_API_KEY`, `SSUAI_NSCALE_API_KEY`, `SSUAI_FIREWORKS_API_KEY`, `SSUAI_HUGGINGFACE_API_KEY`, `SSUAI_MISTRAL_API_KEY`, `SSUAI_OPENROUTER_API_KEY` | 9-provider LLM fallback (`LlmProviderConfig`) | live (chat) — each provider optional; chain skips empty keys |
| `SSUAI_JWT_SECRET`     | `JwtProperties` — HS256 access/refresh signing | from Task 14 — empty default = ephemeral random per restart (dev/test). Prod must set (≥ 32 bytes). |
| `SSUAI_FRONTEND_ORIGIN` | `WebCorsProdConfig` allowlist | live (prod) |
| `SSUAI_SAINT_SSO_URL` / `SSUAI_SAINT_PORTAL_URL` | `SaintSsoProperties` | from Task 14 — defaults already point at saint.ssu.ac.kr |
| `SSUAI_CREDENTIAL_ENCRYPTION_KEY` | (future) AES-GCM library/LMS credential store | when manual paste / LMS login lands |

---

## 9. Logging & observability

What to log on every request:

- HTTP method, route, status, latency.
- `traceId` (same one returned in the response).
- Connector name and `cache hit | cache miss | connector call` per
  external interaction.

What **never** to log, ever:

- Student passwords, u-SAINT/LMS credentials, session cookies, tokens.
- Anything that looks like a student ID, name, or grade.
- Full request bodies that may contain the above.

These rules are repeated in `docs/security.md` (to be drafted) — that doc
is the source of truth; this section is just the architecture-level
reminder.

Liveness check: `/actuator/health` (Spring Boot Actuator). Metrics and
distributed tracing are deferred until there's something worth measuring.

---

## 10. End-to-end data flow — `GET /api/meals/today`

This is the template every future MVP feature copies.

```mermaid
sequenceDiagram
    participant C as Client (web/chatbot)
    participant Ctl as MealController
    participant Svc as MealService
    participant R as Redis
    participant Conn as MealConnector (mock or real)
    participant Site as Cafeteria site

    C->>Ctl: GET /api/meals/today
    Ctl->>Svc: getTodayMeal()
    Svc->>R: GET meal:2026-05-06
    alt cache hit
        R-->>Svc: DailyMeal JSON
    else cache miss
        Svc->>Conn: fetchMeal(2026-05-06)
        alt mock profile
            Conn-->>Svc: fixture DailyMeal
        else real profile
            Conn->>Site: HTTP GET menu page
            Site-->>Conn: HTML
            Conn-->>Svc: parsed DailyMeal
        end
        Svc->>R: SET meal:2026-05-06 TTL=until-midnight
    end
    Svc-->>Ctl: DailyMeal
    Ctl-->>C: ApiResponse<MealResponse>
```

Numbered:

1. Controller receives the request, validates (nothing to validate here),
   calls the service.
2. Service builds the cache key, checks Redis.
3. On hit, return immediately.
4. On miss, call the Connector. The Connector is either the mock or the
   real implementation depending on `ssuai.connector.meal`.
5. Service stores the result in Redis with the appropriate TTL.
6. Service returns the internal DTO.
7. Controller wraps it in `ApiResponse<T>` and returns it.

Every later read endpoint should look like this. If a feature can't fit
the template, that's a signal worth discussing before coding.

---

## 11. MCP integration

The MCP server is a Spring AI feature registered inside the same Spring
Boot app. Each tool is a method that delegates to a domain Service:

```
Claude Desktop / IDE
        │  (MCP protocol)
        ▼
   MCP server (Spring AI)
        │
        ▼
   @Tool methods in domain.mcp.tool
        │
        ▼
   Domain Services  ◄───── REST Controllers also call here
        │
        ▼
   Connectors / Repositories
```

Current tools (23 total — 20 read-only, 3 write):

**Public read-only (no auth)**

| Tool | Domain |
|------|--------|
| `get_today_meal`, `get_meal_by_date` | `MealService` |
| `get_dorm_weekly_meal` | `DormMealService` |
| `search_campus_facilities` | `CampusService` |
| `get_library_seat_status` | `LibrarySeatService` |
| `search_library_book` | `LibraryBookService` |
| `get_recent_notices`, `search_notices`, `list_notice_categories`, `get_notice_detail`, `get_active_notices`, `get_department_notices` | `NoticeService` |

**Auth management (write — session state)**

| Tool | Notes |
|------|-------|
| `get_auth_status` | read-only session check |
| `start_auth` | creates/looks-up MCP session + issues state token |
| `logout_provider`, `logout_all` | destructive — invalidates session |

**Private read-only (mcp_session_id required)**

| Tool | Provider | Delegates to |
|------|----------|-------------|
| `get_my_schedule` | SAINT | `SaintScheduleService` |
| `get_my_grades` | SAINT | `SaintGradesService` |
| `get_my_chapel_info` | SAINT | `SaintExtendedService` |
| `check_graduation_requirements` | SAINT | `SaintExtendedService` |
| `get_my_scholarships` | SAINT | `SaintExtendedService` |
| `get_my_assignments` | LMS | `LmsAssignmentsService` |
| `get_my_library_loans` | LIBRARY | `LibraryLoansService` |

Tool annotations (`McpSchema.ToolAnnotations`) are applied at startup by `McpServerConfig`:
`readOnlyHint=true` for all 20 read-only tools, `destructiveHint=true` for `logout_provider` / `logout_all`.
This lets Claude Desktop group tools visually into "Read-only tools" and "Write/delete tools".

Rules:

- **MCP tools never bypass the Service layer.** No tool reaches into a
  Connector or Repository directly. This keeps caching, validation, and
  error handling consistent across REST and MCP.
- Tool inputs and outputs are explicit DTOs — no opaque maps, no
  free-form strings as outputs.
- Phase 4 write tools (seat reservation, etc.) will follow the
  `prepare_X` + `confirm_action` two-step pattern with audit logging
  (see ADR 0015, `docs/mcp-tools.md` §8).

---

## 12. Frontend architecture (brief)

- **Next.js (App Router) + TypeScript + Tailwind CSS + shadcn/ui** for the
  web dashboard.
- **TanStack Query** for server state (caching, retries, background
  revalidation) so the UI stays simple and the backend can stay stateless.
- Backend URL is read from an env var (`NEXT_PUBLIC_SSUAI_API_BASE`) — no
  hard-coded hosts.
- The MVP frontend is three cards (today's meal, library book search,
  library seat status). No login, no personalization. The frontend's job
  in week 1 is to prove the API contract end-to-end, not to be pretty.

A separate frontend design doc can grow from here when there's enough
surface area to justify it.

---

## 13. Testing topology

Layered tests mirror the layered code:

- **Unit tests** — `*Service` classes with Connectors and Repositories
  mocked. Pure business logic.
- **Slice tests** — `@WebMvcTest` for Controllers; verifies request
  validation, response envelope, and HTTP status mapping.
- **Connector tests** — Jsoup connectors against **fixture HTML** stored
  under `src/test/resources/fixtures/`. HTTP-based connectors against
  Spring's `MockRestServiceServer` (RestClient stack) or OkHttp's
  `MockWebServer` (when raw HTTP / streaming is needed). Tests must be
  deterministic.
- **Integration tests** — `@SpringBootTest` with Testcontainers for
  Postgres and Redis. Added once the data layer becomes non-trivial; not
  required for week 1.

Hard rule: **automated tests never call real u-SAINT, real LMS, or any
authenticated school endpoint.** Manual smoke scripts can, but they live
outside the CI test suite.

---

## 14. Future extension points

Each deferred feature already has a home in this architecture. Knowing
*where* it lands is what lets us defer it without painting into a corner.

<!-- markdownlint-disable MD013 MD060 -->

| Future feature              | Where it lives                                                                 |
|-----------------------------|--------------------------------------------------------------------------------|
| Library read tools          | New `domain.library` package, with `LibraryConnector` (Jsoup or HTTP), service-layer real-time seat cache (TTL ≤ 30s), `@Tool` methods `search_library_book`, `get_library_book_status`, `get_library_seat_status`. |
| User auth for ssuAI itself  | `domain.user` + `global.security` (Spring Security, JWT or session).           |
| Encrypted credential store  | `domain.user.entity.SchoolCredential` + AES-GCM via `SSUAI_CREDENTIAL_ENCRYPTION_KEY`. Library credentials live here too. |
| LMS read-only integration   | New `domain.lms` package, plus `domain.auth.lms` for an `LmsSessionStore` mirror of Task 16's `SaintSessionStore`. Connector defaults to Jsoup; escalate to Playwright only on a concrete blocker. Spec: [`docs/tasks/17-lms-integration.md`](tasks/17-lms-integration.md). |
| u-SAINT read-only           | Currently `domain.auth.saint` (Task 14, identity-only). Realtime academic data tools (성적·시간표·출결) re-issue an SSO flow per call; the package may grow into `domain.usaint` once we settle on a session retention policy. |
| **Library seat agent (flagship)** | `reserve_library_seat` `@Tool` in `domain.mcp.tool` + new `domain.library.agent` for the per-user reservation flow. Uses `LibraryConnector`'s authenticated write path with the user's encrypted credentials. **Action policy infrastructure** below is its prerequisite. |
| Action MCP tools (general)  | New `@Tool` methods in `domain.mcp.tool`, split into `prepare_X` + shared `confirm_action(pending_action_id)`. Backed by `action_audit` table (append-only) + an in-process `ActionLock` interface (Redis SETNX-swappable). Full mechanism in [ADR 0015](adr/0015-action-tool-infrastructure.md). |
| Notifications               | New `domain.notification` package; Redis for delivery state, web push first.   |
| Mobile app                  | Separate Expo project; reuses the existing REST API. No backend changes.       |

<!-- markdownlint-enable MD013 MD060 -->

The architecture's job through the MVP is to make sure none of these
require rewriting the Service or Connector layers — only adding to them.
The **library seat agent is the flagship deliverable** the architecture is
designed to support: every layer above (auth, credential storage, action
policy, audit log, distributed lock) is a prerequisite for it shipping
safely. See [`docs/vision.md`](vision.md) §3.4 for the user-facing flow
and [`docs/security.md`](security.md) §6 for the action policy.

---

## 15. MCP auth session (Task 18)

### 흐름 개요

외부 MCP client (Claude Desktop, Cursor) 가 private tool 을 호출하면 서버가 `AUTH_REQUIRED` 응답과 로그인 URL 을 반환한다. 사용자가 브라우저에서 로그인을 완료하면 서버가 provider session 을 저장하고 이후 tool call 이 데이터를 반환한다.

```
MCP client → get_my_schedule(mcp_session_id)
               │
               ▼
         McpAuthHelper.principalKey()
               │
       ┌───────┴───────┐
  session 있음       session 없음
       │                  │
  SaintScheduleService    McpAuthHelper.buildAuthRequired()
  .fetchSchedule()        → AUTH_REQUIRED + loginUrl
       │
  McpPrivateToolResponse.ok(data)
```

### 패키지

```
domain.auth.mcp
├── McpAuthSessionId      // opaque UUID handle; fingerprint() → SHA-256 prefix (logs only)
├── McpAuthSession        // immutable record: id, createdAt, expiresAt, providers map
├── McpProviderLink       // provider + principalKey + linkedAt
├── McpAuthStateEntry     // one-time login state token: state, mcpSessionId, provider, expiresAt
├── McpAuthSessionStore   // LRU+TTL in-memory store (LinkedHashMap). max 500 sessions, TTL 4h
├── McpAuthStateStore     // one-time state store. max 1000 states, TTL 10min, replay-protected
├── McpAuthService        // interface: find, getOrCreate, generateState, consumeState, linkProvider, unlinkProvider, invalidateSession
├── McpAuthUrlFactory     // buildLoginUrl / buildCallbackUrl per provider
├── McpSaintAuthController // GET /api/mcp/auth/saint/start → SmartID redirect
│                          // GET /api/mcp/auth/saint/callback → SaintSsoService → linkProvider
├── McpLmsAuthController  // GET /api/mcp/auth/lms/start|callback
├── McpLibraryAuthController // GET /api/mcp/auth/library/start → frontend redirect
│                           // POST /api/mcp/auth/library/callback → LibraryCredentialLoginService
└── dto
    ├── McpAuthStatusResponse, McpAuthStartResponse, McpAuthLogoutResponse, McpProviderStatusEntry
    └── McpPrivateToolResponse<T>  // status: OK | AUTH_REQUIRED, data, loginUrl, expiresAt

domain.mcp.tool
├── McpAuthMcpTools  // @Tool get_auth_status, start_auth, logout_provider, logout_all
├── McpAuthHelper    // principalKey() lookup + buildAuthRequired() factory (shared by private tools)
├── SaintScheduleMcpTool   // get_my_schedule(mcp_session_id) → McpPrivateToolResponse<ScheduleResponse>
├── SaintGradesMcpTool     // get_my_grades(mcp_session_id)
├── LmsAssignmentsMcpTool  // get_my_assignments(mcp_session_id)
└── LibraryLoansMcpTool    // get_my_library_loans(mcp_session_id)
```

### principalKey 매핑

| Provider | principalKey | 용도 |
| --- | --- | --- |
| SAINT | studentId | SaintSessionStore 조회 키 |
| LMS | studentId (= sIdno) | LmsSessionStore 조회 키 |
| LIBRARY | 불투명 UUID | LibrarySessionStore 조회 키 (studentId 아님) |

### 웹 챗봇과의 관계

웹 챗봇 (`LlmChatService`) 은 private tool 을 MCP 경로로 호출하지 않는다. `get_my_schedule` 등은 `SaintScheduleService.fetchSchedule(studentId)` 를 직접 호출한다 (lines 526-534). MCP tool 메서드 시그니처 변경이 챗봇에 영향을 주지 않는 이유다.

ThreadLocal (`SaintToolContext`, `LmsToolContext`, `LibraryToolContext`) 은 웹 챗봇 경로에만 남아 있다. MCP private tool 은 ThreadLocal 을 사용하지 않는다 (Task 18 Slice C 이후).

---

## 16. Open questions — resolved

All week-1 open questions are now settled (kept here for the design log):

- **Response envelope shape** → `{ data, error, traceId }` (`ApiResponse<T>`
  in `global.response`). Resolved at Task 01.
- **Trace ID source** → custom `TraceIdFilter` in `global.config`. Resolved
  at Task 01.
- **OpenAPI from day 1** → yes, `springdoc-openapi-starter-webmvc-ui` 3.x.
  Resolved at Task 08; Swagger UI disabled in prod.
- **`domain.common` package** → not created; duplication has stayed low
  enough across `meal`/`dorm`/`library`/`campus` that a shared module
  wasn't worth the indirection.
