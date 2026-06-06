# Server Documentation Map

`ssuMCP/docs/` is the source of truth for the Spring Boot REST/MCP server.

## Active Documents

| Document | Purpose |
| --- | --- |
| [architecture.md](architecture.md) | Current runtime boundaries and package responsibilities |
| [mcp-tools.md](mcp-tools.md) | Tool inventory, auth flow, and client setup |
| [library-seat-agent-completion-plan.md](library-seat-agent-completion-plan.md) | Completion checklist for the library seat automation agent |
| [security.md](security.md) | Data classification, secret handling, and action policy |
| [../deploy/README.md](../deploy/README.md) | Production deployment runbook |

## Historical Records

`adr/`, `deploy/pipeline-diagnosis-2026-05-14.md`, and troubleshooting notes
record decisions made before or during the repository split. They may mention
the former monorepo `backend/` and `frontend/` paths, or the superseded SSE
transport. Current commands use the `ssuMCP` repository root and current MCP
transport is Streamable HTTP `/mcp`.

Product vision, frontend decisions, and completed task specs are maintained in
the separate [ssuAI documentation](https://github.com/hoeongj/ssuAI/tree/main/docs).
