# ADR 0038 — ChatGPT MCP OAuth 연결: Auth0를 외부 AS로 (DCR / third-party)

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-18 |
| 상태 | Accepted (부분 — 아래 "한계" 참조) |
| 연관 ADR | [0036](0036-mcp-auth-optin-two-mode.md) (opt-in OAuth RS), [0037](0037-mcp-prm-authorization-servers.md) (PRM authorization_servers) |
| 디버깅 서사 | TROUBLESHOOTING.md 2026-06-18 "Auth0 DCR 5단 관문" |

---

## 배경

ADR 0036(opt-in OAuth RS) + 0037(PRM `authorization_servers`)로 **MCP 클라이언트가 Auth0를 발견**할 수 있게 됐다. 하지만 ChatGPT가 실제로 OAuth를 완료해 안정 세션(`oauth_subject`)을 얻으려면 Auth0(외부 AS) 쪽 설정이 더 필요했다. ChatGPT의 MCP 커넥터는 **DCR(동적 클라이언트 등록)** 로 Auth0에 클라이언트를 만드는데, **DCR 클라이언트는 전부 Auth0 "third-party 앱"** 이라 first-party 앱엔 없는 제약이 줄줄이 따라온다.

> 목표: ChatGPT가 매 `/mcp` 요청에 Bearer JWT를 실어 보내 → 서버가 `sub`로 세션을 식별 → LLM이 opaque id를 흘려도 세션이 턴을 넘어 유지(= 2번 로그인 루프 해소, ADR 0036의 Tier 1).

## 핵심 결정과 근거

### 1. resource(=audience) 식별자를 `https://ssumcp.duckdns.org/mcp` 로
- ChatGPT는 RFC 8707에 따라 `resource=<커넥터 URL>` = `.../mcp` 로 토큰을 요청한다. Auth0는 이 `resource`를 등록된 API와 매칭하므로, **API 식별자도 `.../mcp` 여야** 한다(base URL이면 `access_denied: Service not found`).
- 따라서 Auth0 API를 `.../mcp` 식별자로 신규 생성(식별자는 불변)하고, 서버의 `SSUAI_OAUTH_AUDIENCE`도 `.../mcp`로 정렬해 발급 토큰의 `aud`와 RS 검증을 일치시켰다.
- 대안(기각): base URL 유지 → ChatGPT의 resource와 불일치로 실패. ChatGPT가 보내는 resource를 바꿀 수는 없음(커넥터 URL 고정).

### 2. third-party 전체에 대한 default client grant
- third-party(DCR) 클라이언트는 **명시적 client grant** 없이는 API 접근 불가. 게다가 **ChatGPT는 연결 시도마다 새 클라이언트를 생성**(client_id가 매번 다름)하므로 per-client grant는 무의미.
- 해결: `POST /api/v2/client-grants` `{"default_for":"third_party_clients","audience":".../mcp","scope":[],"subject_type":"user"}`. 모든 DCR 클라이언트에 자동 적용. (`scope`는 resource server에 정의된 스코프의 부분집합이어야 하므로 커스텀 스코프 없는 API엔 `[]`.)

### 3. google-oauth2를 domain connection으로 승격
- **DCR 클라이언트는 domain-level connection만** 로그인에 쓸 수 있다(없으면 `no connections enabled for the client`).
- 해결: `PATCH /api/v2/connections/{id}` `{"is_domain_connection":true}`.

### 4. `/mcp`는 계속 `permitAll` — 강제 401로 OAuth를 발동시키지 않음
- OAuth를 강제로 트리거하려고 `/mcp`에 토큰 없으면 401을 주면, **공개 도구(학식·도서관 검색·공지 등)가 전부 인증 필요**해져 ADR 0036의 zero-auth 공개 도구 설계가 깨진다. 필터 레벨에서 공개/사적 도구를 구분할 수도 없다(모두 `/mcp` POST).
- 대신 **클라이언트 측 OAuth 커넥터 opt-in**으로 흐름을 시작한다(사용자가 ChatGPT 커넥터를 OAuth로 추가 → 클라이언트가 PRM 보고 능동 발견).

## 작동 방식 (전체 흐름)
1. 사용자가 ChatGPT 커넥터를 OAuth 방식으로 추가 → ChatGPT가 Auth0에 DCR로 클라이언트 등록.
2. PRM(`/.well-known/oauth-protected-resource`)의 `authorization_servers`로 Auth0 발견(ADR 0037).
3. `/authorize?resource=.../mcp` → (default grant로 인가) → 동의 화면 → google domain connection 로그인.
4. code → token(`aud=.../mcp`, `sub`) 교환 → `/mcp`에 Bearer JWT.
5. RS가 iss+aud 검증, SecurityContext의 `sub`를 `McpAuthHelper.resolveSession`이 세션에 바인딩(`oauth_subject`) → 이후 sub로 안정 해석.

## 한계 (Accepted 부분)
- DB로 `oauth_subject` 바인딩은 확인됐으나, **ChatGPT가 모든 호출에 토큰을 일관되게 싣지 않는** 현상 관측(일부 호출이 opaque로 빠짐). 커넥터의 "not all requested permissions were granted"(요청 스코프 미충족) 경고와 연관 의심. 완전 일관성 확보엔 API에 스코프를 더 정의·부여하는 검토가 필요(미결).
- 서버는 Auth0 관리 자격증명이 불필요(공개 JWKS로 JWT만 검증). 위 Auth0 변경(API·grant·connection)은 운영자가 Management API로 1회 수행.

## 예상 면접 질문
1. RFC 8707 `resource`와 토큰 `aud`, RS audience 검증의 3자 정합. 하나라도 어긋나면?
2. Auth0 DCR 클라이언트가 "third-party"라 생기는 제약 3가지(client grant / domain connection / entity limit)와 각 해결책.
3. OAuth를 강제하려 `/mcp`를 401로 만들지 않은 이유는? (zero-auth 공개 도구 보존)
4. LLM이 opaque session id를 흘려도 세션이 유지되는 이유(3-tier 중 OAuth sub)와, 그게 안정적인 근거(HTTP 계층·LLM 불가침).
