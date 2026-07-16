# Server Documentation Map

`ssuMCP/docs/` is the source of truth for the Spring Boot REST/MCP server.

## Active Documents

| Document | Purpose |
| --- | --- |
| [architecture.md](architecture.md) | Current runtime boundaries and package responsibilities |
| [mcp-tools.md](mcp-tools.md) | Tool inventory, auth flow, client setup, and official-source academic policy tools |
| [security.md](security.md) | Data classification, secret handling, and action policy |
| [playmcp-submission.md](playmcp-submission.md) | Agentic Player 10 contest profile, KC settings, and pre-submission gate |
| [../deploy/README.md](../deploy/README.md) | Production deployment runbook |
| [runbooks/node-capacity.md](runbooks/node-capacity.md) | Node disk emergency cleanup and boot volume expansion (49G→150G) operator runbook |
| [troubleshooting-highlights.md](troubleshooting-highlights.md) | Incident and design-correction log, including the 2026-06-06 academic policy RAG refresh decision |
| [troubleshooting/lms-material-metadata-regression.md](troubleshooting/lms-material-metadata-regression.md) | Fixture-reproduced LMS listing/export regression in optional Commons metadata parsing; production cause pending verification |
| [troubleshooting/lms-general-attachment-export.md](troubleshooting/lms-general-attachment-export.md) | Production A/B isolation and fail-soft archive handling for general LMS attachments |
| [audits/2026-07-14-live-tool-hardening.md](audits/2026-07-14-live-tool-hardening.md) | 52-tool live-audit remediation record: scope, fixed contracts, regression evidence, limits, and safe smoke checklist |

## Historical Records

`adr/` and troubleshooting notes
record decisions made before or during the repository split. They may mention
the former monorepo `backend/` and `frontend/` paths, or the superseded SSE
transport. Current commands use the `ssuMCP` repository root and current MCP
transport is Streamable HTTP `/mcp`.

Product vision, frontend decisions, and completed task specs are maintained in
the separate [ssuAI documentation](https://github.com/ghdtjdwn/ssuAI/tree/main/docs).

## Verification ownership

The executable inventory is `McpToolContractInventoryTests`: it requires all 52 registered
tools to declare authentication, inputs, empty/error behavior, owner scope, side effects,
idempotency, and response-size behavior. `McpSelfDogfoodTests` exercises the private-tool
surface through MCP HTTP, including explicit-session isolation. Documentation describes the
contract; these tests are the release gate for drift.
