# ADR 0045 — private tool 응답 메시지를 userMessage / developerMessage로 분리

- 상태: 채택 (2026-06-19)
- 관련: `McpPrivateToolResponse`, 외부 MCP 클라이언트(ChatGPT·Claude Desktop) 전수 호출 점검 후속(③)

## 배경 (무엇이 문제였나)

모든 private MCP 도구가 반환하는 `McpPrivateToolResponse`에는 메시지 필드가 `message` 하나뿐이었다. 이 단일 필드가 **두 청중을 겸했다**:

- **사용자(UI)**: 챗 화면에 그대로 노출되는 텍스트
- **에이전트(LLM)**: 다음에 무엇을 할지 알려주는 절차/지시문

특히 `AUTH_REQUIRED` 메시지는 *"이 raw loginUrl을 브라우저에서 열어라 / URL을 PlayMCP 페이지로 치환하지 마라 / mcp_session_id로 재시도하라"* 같은 **장황한 영어 절차문**이다. 이는 에이전트에겐 필요하지만 사용자에게 그대로 보이면 UX가 나쁘다. 전수 호출 점검에서 "응답 스키마 일관화 + 사용자/개발자 메시지 분리" 필요가 드러났다.

## 결정

`McpPrivateToolResponse`에 두 필드를 **추가**한다:

- `userMessage` — 짧고 친절한 **한국어** 한 줄 (사용자 노출용)
- `developerMessage` — verbose **에이전트/LLM용** 상세(절차·코드·다음 단계)

그리고 기존 `message`는 **바이트 그대로 유지**하며 `developerMessage`의 하위호환 별칭으로 둔다.

## 검토한 대안과 기각 이유

1. **`message` 자체를 짧은 userMessage로 교체** (스펙 초안의 "alias of userMessage")
   → **기각.** `message`는 지금 외부 MCP 클라이언트(ChatGPT·Claude Desktop)가 실시간으로 읽고 있고, 이번 작업 직전까지 OAuth/auth 플로우를 위해 바로 그 `AUTH_REQUIRED` 텍스트를 튜닝했다. 이 바이트를 바꾸면 우리가 통제·검증할 수 없는 외부 소비자의 인증 동작에 **silent 회귀**가 날 수 있다. 부가 개선(메시지 분리)을 위해 어렵게 안정화한 핵심 플로우를 위험에 빠뜨릴 이유가 없다.
2. **모든 도구의 모든 응답을 도구별 커스텀 메시지로 일일이 분리**
   → **기각(범위·비용).** 호출부 44곳을 손대야 하고 회귀 면이 넓다. 모든 도구가 팩토리(`ok`/`authRequired`/`invalidSession`)만 사용하므로, **팩토리 레벨에서** 가장 시끄러운 `AUTH_REQUIRED`/`INVALID_SESSION`을 분리하면 적은 변경으로 대부분의 가치를 얻는다.
3. **단일 `message` 유지(현행)**
   → **기각.** 두 청중이 계속 섞여 UX 개선과 일관 스키마 목표를 달성할 수 없다.

## 어떻게 작동하나

- `McpPrivateToolResponse` record에 `userMessage`, `developerMessage` 컴포넌트를 추가(순수 추가).
- 팩토리 3개만 수정:
  - `ok` → 세 메시지 모두 null(데이터가 정보를 전달).
  - `authRequired` → `developerMessage` = 기존 verbose 절차문, `userMessage` = 짧은 한국어(loginUrl 포함, 에이전트도 행동 가능), `message` = developerMessage(바이트 불변).
  - `invalidSession` → 동일 패턴.
- 모든 도구는 팩토리만 호출하므로 **도구 코드 변경 0**으로 전 도구에 적용된다.
- Jackson 직렬화상 JSON에 `userMessage`/`developerMessage`가 **추가**될 뿐 `message`는 그대로라, 기존 클라이언트 무영향.

## 후속

- UX 단축(사용자가 짧은 문구를 보게)은 **ssuai 프론트엔드가 `userMessage`를 우선 표시**하도록 바꾸는 별도 작업으로 실현한다. 백엔드는 두 필드를 모두 제공하므로 프론트 전환은 무위험.

## 근거 출처

- RFC 7807 → 9457 *Problem Details for HTTP APIs*: 사람이 읽는 `title`/`detail` 분리 관례.
- MCP `content`(모델용) vs `structuredContent`(클라이언트용) 분리 관례(futuresearch).
