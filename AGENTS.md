# AGENTS.md — ssuMCP

Soongsil University MCP server (Spring Boot, Kotlin/Java 21). Exposes cafeteria meals, library, campus facilities, u-SAINT, and LMS data as standard MCP tools.

> **Workflow source of truth: `../AGENTS.md` (mp root).** Claude = design / spec / review; Codex = ALL git & deploy execution (commit/push/PR/merge/deploy verification). Core rules are inlined below for executors that read only this file.

## Docs

- `docs/architecture.md` — package structure, layers, Connector pattern, MCP tool list
- `docs/security.md` — auth flow, credential storage, log-prohibited items
- `docs/mcp-tools.md` — MCP tool I/O reference
- `docs/adr/` — architecture decision records
- `TROUBLESHOOTING.md` — cumulative troubleshooting log (portfolio)

## Core Rules (source: ../AGENTS.md)

1. **Authorship** — commit author/committer = hoengj. AI attribution ABSOLUTELY FORBIDDEN: no `Co-Authored-By: Claude`, no `🤖 Generated with…`, no "Claude" / "Anthropic" / "Codex" / "Gemini" anywhere. Post-commit check: `git log -1 --format='%an <%ae> | %cn <%ce>'`.
2. **Decisions** (spec / method / logic / framework) — web search FIRST → evaluate ① portfolio value (top priority) ② trend fit ③ completion & provability → report to the user and finalize TOGETHER. NO improvised decisions. Target: top-tier, job-winning portfolio — IF a better direction exists (incl. teardown), report it as an option.
3. **User confirmation REQUIRED** — DB migration apply, prod env-var change, major dependency bump, server restart, force-push, write actions on real accounts (`confirm_action` family). Everything else is autonomous: tests, commit, push, PR, merge (tests pass + no runtime impact), CI checks.
4. **Troubleshooting** — on ANY trigger (external system misbehaves / first hypothesis wrong / tests green but prod broken / framework-internals workaround / design pivot / security-auth bug) record IMMEDIATELY in `TROUBLESHOOTING.md`. REQUIRED fields: wrong hypothesis / actual cause / key files & commits / portfolio point / 2–3 expected interview questions. Records are human-facing → write in KOREAN.
5. **Docs sync** — after each major unit, update `../MASTERPLAN.md` + affected `docs/` in the same flow. Study-grade records in KOREAN: background / alternatives + rejection reasons / rationale (incl. sources) / how it works. NO summarizing away.
6. **Solo mode** — when the user reports one AI's usage-limit outage, the remaining AI runs design → implement → test → commit → push → deploy verification end-to-end. Work basis: `../MASTERPLAN.md` "다음 작업"; update status on completion. Codex solo: docs / simple work via `-p git` (mini) to save tokens.

## Project Structure

```
src/main/
├── java/com/ssuai/           # package name com.ssuai (no refactor planned)
│   ├── global/               # auth, config, exception, response
│   └── domain/
│       ├── auth/             # MCP session auth (McpAuthSession, McpAuthStore)
│       ├── campus/           # facility search
│       ├── chat/             # chatbot LLM integration (LlmChatService, ToolResultCompactor)
│       ├── dorm/             # residence-hall menu
│       ├── library/          # library seats/books/loans + actions (reserve/move/return)
│       ├── lms/              # LMS assignments
│       ├── mcp/              # McpServerConfig + @Tool classes
│       ├── meal/             # cafeteria meals
│       ├── notice/           # notices
│       ├── academic/         # official regulations/graduation/scholarship source RAG
│       └── saint/            # u-SAINT (timetable, grades, chapel, graduation, scholarships)
└── kotlin/
    ├── com/ssuai/domain/saint/connector/
    │   └── RusaintUniFfiClient.kt    # rusaint JNA call adapter
    └── dev/eatsteak/rusaint/         # rusaint Kotlin FFI binding (JNA) — NEVER MODIFY
deploy/                 # k8s Helm chart + Docker
```

## Key Patterns

- **Connector**: external-system call → internal DTO. Mock / Real implementations split; selected per profile via `@ConditionalOnProperty`. New tool = new Connector.
- **MCP tool registration MANDATORY**: every new `@Tool` class MUST be registered in `McpServerConfig.java` `toolObjects(...)` — otherwise it is invisible to clients.
- **Private tool auth**: `mcp_session_id` param + `McpAuthHelper.principalKey()`. No session → return `AUTH_REQUIRED` + loginUrl.

## Dev Rules

- Tests: `.\gradlew.bat test` (Windows) / `./gradlew test` (Linux)
- DO NOT read in routine work: `build/`, `.gradle/`, `.idea/`, `src/main/kotlin/dev/eatsteak/rusaint/` (generated binding)
- Branch: `feat/` `fix/` `refactor/` `chore/` `docs/` + kebab-case. One feature = one PR. Branches must carry exactly one squashed commit authored locally; merge PRs with the rebase method only (`gh pr merge --rebase`); GitHub squash and merge-commit methods are forbidden because they stamp committer `GitHub <noreply@github.com>`; verify authorship on `origin/main` immediately after every merge.
- Commit: Conventional Commits (`feat(mcp): ...`)

## Deploy

main push → GitHub Actions (ARM64 image → `ghcr.io/hoeongj/ssumcp`) → ArgoCD Image Updater → k3s auto deploy

- Local MCP: `http://localhost:8080/mcp` (Streamable HTTP)
- Prod: `https://ssumcp.duckdns.org/mcp` / Grafana: `https://ssumcp.duckdns.org/grafana`

## Credentials

1. `C:/Users/akftj/mp/myInfo.txt` — student ID, password, server IP, etc.
2. `C:/Users/akftj/mp/ssuMCP/.env` — backend env vars (variable names: see `.env.example`)
3. k8s secret: `kubectl get secret` (when the server is reachable)
4. ONLY if absent above → ask the user
