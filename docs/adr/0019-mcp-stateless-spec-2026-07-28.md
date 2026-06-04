# ADR 0019 - MCP 2026-07-28 Stateless Spec RC 대응

- **Status**: Accepted (관찰 및 준비 단계)
- **Date**: 2026-06-04

## Context

MCP 2026-07-28 RC 주요 변경:
- `Mcp-Session-Id` transport header 제거, stateless 방식 표준화
- Handle pattern 공식화: 서버가 identifier를 tool output으로 발행,
  모델이 이후 호출에 파라미터로 전달
- OAuth 2.0 / OIDC authorization hardening
- `initialize` handshake 제거

PlayMCP 담당자가 이 스펙을 참고하도록 권고하며,
"인증 필요 툴 호출 시 자동 인증" 방식 공식 지원 예정.

## 영향 분석

**직접 영향 없음:**
ssuMCP의 `mcp_session_id` 는 tool parameter (application layer) 이며,
`Mcp-Session-Id` transport header 와 별개다.
transport 변경은 spring-ai-mcp 라이브러리가 처리한다.

**설계 정합성 확인됨:**
ssuMCP의 인증 패턴이 신규 스펙의 handle pattern 과 완전히 일치한다.
`start_auth` -> `mcp_session_id` 반환 -> private tool 파라미터로 전달.

**향후 대응 필요:**
PlayMCP가 공식 지원하는 OAuth 기반 "자동 인증" 방식이 확정되면,
`McpAuthHelper` 레이어에서 tool parameter 대신
`Authorization: Bearer <token>` 헤더를 읽도록 전환이 필요하다.

## Decision

지금은 관찰 단계. 코드 변경 없음.

- spring-ai-mcp 라이브러리 버전 업데이트 주시
- PlayMCP의 공식 OAuth 지원 공지 후 McpAuthHelper 수정 진행
- `Mcp-Session-Id` 헤더를 ssuMCP 코드가 직접 사용하는 곳이 없으므로 제거 작업 불필요

## Consequences

- 단기: 현행 유지, 운영 안정성에 영향 없음
- 중기: PlayMCP OAuth 지원 시 Bearer token 경로 추가
  (`mcp_session_id` 파라미터는 하위 호환용으로 유지)
- 장기: spring-ai-mcp가 2026-07-28 스펙을 구현하면
  transport 레이어 업그레이드 (라이브러리 버전 범프)
