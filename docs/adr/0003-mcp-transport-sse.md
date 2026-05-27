# ADR 0003 — MCP transport 로 SSE 선택

- **Status**: ~~Accepted~~ **Superseded by [ADR 0018](0018-mcp-transport-streamable-http.md)**
  (2026-05-24: SSE endpoint 를 Streamable HTTP 단일 POST `/mcp` 로 전환)
- **Date**: 2026-05-07
- **Scope**: `com.ssuai.domain.mcp.config.McpServerConfig`, `backend/build.gradle`, `backend/src/main/resources/application.yml`

## Context

Spring AI 기반 MCP server 를 ssuAI backend 에 통합하면서 transport 를 먼저
결정해야 했습니다. 후보는 크게 STDIO 와 SSE / Streamable HTTP 였습니다.
둘 다 MCP 생태계에서 실제로 쓰이지만 운영 모양이 다릅니다.

MCP 를 단순 demo script 로만 쓸 생각이라면 STDIO 가 가장 빠른 길입니다.
하지만 ssuAI 의 목표는 web dashboard, chatbot, IDE / Claude client 가 같은
backend capability 를 공유하는 것입니다. 따라서 transport 선택도 "로컬에서
한 client 가 붙는가" 보다 "하나의 backend surface 로 계속 운영할 수 있는가"
를 기준으로 봐야 했습니다.

ssuAI 의 MVP runtime topology 는 하나의 Spring Boot process 에 REST API 와
MCP server 를 함께 띄우는 구조입니다. architecture §2 에서도 하나의
process 로 시작해 배포 단위와 business logic 중복을 줄이는 방향을 명시하고
있습니다. STDIO 는 stdout 을 transport 로 점유하는 방식이라 REST server 와
같은 process 에 자연스럽게 공존하기 어렵습니다.

반대로 SSE / Streamable HTTP 는 일반 웹 서버처럼 동작합니다. 다중 client
동시 접속, reverse proxy, TLS, logging, health check 같은 기존 웹 인프라를
그대로 활용할 수 있습니다. 현업 self-hosted MCP server 도 이 흐름으로
옮겨가는 추세라 portfolio 설명에도 더 적합했습니다.

## Decision

MCP transport 는 SSE / Streamable HTTP 로 결정합니다. 구현은 Spring AI 의
`spring-ai-starter-mcp-server-webmvc` 를 사용합니다.

profile 은 분리하지 않습니다. REST + MCP 가 같은 Spring Boot process 에
항상 같이 살아있도록 두고, `dev` / `prod` profile 의 connector 설정만
기존 방식으로 유지합니다. 별도의 MCP 전용 jar 나 process 는 만들지
않습니다.

Claude Desktop 처럼 STDIO-only 로 사용하는 client 는 `mcp-proxy` 어댑터를
통해 붙입니다. 이 사용법은 `docs/mcp-tools.md` §5.2 에 명시했습니다.

endpoint 는 starter 기본값을 그대로 따릅니다. SSE 연결은 `/sse`, message
전송은 `/mcp/message` 를 사용합니다.

tool 등록은 `McpServerConfig` 에서 Spring AI 의 callback provider 로 묶습니다.
tool method 들은 `domain.mcp.tool` 아래에 두고, REST controller 와 마찬가지로
도메인 Service 만 호출합니다. transport 결정이 business logic 위치를 바꾸지
않게 하는 것이 중요했습니다.

## Consequences

**좋은 점**
- REST 와 같은 Spring Boot process 에 자연스럽게 공존합니다.
- 별도 jar, 별도 process, 별도 profile 없이 MVP 운영 표면을 작게 유지합니다.
- 다중 MCP client 동시 접속이 가능합니다.
- TLS, reverse proxy, observability 같은 일반 웹 인프라를 그대로 활용할 수 있습니다.
- MCP transport 선택에 대해 "현업 self-hosted MCP 흐름" 이라는 설명 가능한 근거가 생겼습니다.

**대가**
- Claude Desktop 직결에는 `mcp-proxy` 같은 어댑터가 한 단계 더 필요합니다.
- 데스크탑 단일 사용자 기준으로는 STDIO 보다 초기 설정이 조금 무겁습니다.
- SSE / Streamable HTTP 쪽 ecosystem 이 빠르게 변하는 중이라 client 별 지원 방식이 달라질 수 있습니다.
- HTTP endpoint 가 생기므로 운영 환경에서는 CORS, TLS, reverse proxy 설정을 함께 점검해야 합니다.

## Alternatives considered

- **STDIO** — 데스크탑 client 직결은 가장 단순하지만, stdout 점유로 REST server 와 같은 process 에 두기 어렵고 profile 분리가 필요합니다.
- **별도 Spring Boot module / process 로 STDIO 담당** — 기술적으로 가능하지만 1인 학생 프로젝트와 MVP 단계에서는 운영 비용이 과합니다.
- **MCP server 를 나중으로 미루기** — REST 만으로도 MVP 일부는 가능하지만, ssuAI 의 핵심 검증 중 하나가 Claude / IDE 에서 campus data 를 tool 로 쓰는 것이어서 채택하지 않았습니다.
