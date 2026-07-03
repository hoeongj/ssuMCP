# ADR 0074 — MCP OAuth 리소스 서버 체인을 MCP 표면으로 스코프 (웹 로그인 prod 장애 수정)

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-07-03 |
| 상태 | Accepted — 구현·머지 |
| 범위 | `McpOAuthSecurityConfig` 필터 체인 분리 (`/mcp`·`/.well-known` vs 나머지) |
| 연관 문서 | ADR 0036(opt-in 2-mode), ADR 0037(PRM authorization_servers), ADR 0038(ChatGPT OAuth), `docs/security.md` |

---

## 배경 — 장애

u-SAINT SmartID SSO 웹 로그인이 prod에서 전면 실패했다. 증상 경로: SmartID 로그인·콜백·refresh 쿠키 발급까지 전부 성공 → 프론트가 `GET /api/auth/me`를 웹 access JWT(HS256)로 호출 → **401 + `WWW-Authenticate: Bearer realm="ssuMCP", resource_metadata=…`** → `useSaintAuth.refresh()`가 false → 사용자에게 "세션을 만들지 못했어요". `/api/saint/**`·`/api/lms/**` 등 Bearer를 쓰는 로그인 후 API 전체가 같은 방식으로 죽어 있었다.

근본 원인: MCP OAuth 리소스 서버(ADR 0036~0038)의 `SecurityFilterChain`이 **경로 스코프 없이 전체 요청**에 걸려 있었다. `BearerTokenAuthenticationFilter`는 *인증* 필터라서 `permitAll()`(인가 규칙)과 무관하게 — Bearer 헤더가 보이면 무조건 configured decoder(Auth0 RS256)로 검증한다. ssuAI 웹 세션 JWT는 자체 HS256이므로 "invalid token" 401. 서버 코드 javadoc조차 "REST API — existing auth handled elsewhere"라고 쓰며 `permitAll()`로 충분하다고 믿었던 것이 오판이었다.

**테스트는 전부 green이었다**: 전 테스트가 `rs-enabled=false`(기본값)로 돌아 BearerTokenAuthenticationFilter 자체가 등록되지 않았고, prod만 `SSUAI_OAUTH_RS_ENABLED=true`(ChatGPT 연동)라 prod에서만 재현되는 구성 의존 장애였다.

## 검토한 대안

### AuthenticationManagerResolver로 토큰별 분기 ❌
요청/토큰 모양을 보고 Auth0 검증기 vs no-op을 고르는 방식. 기각 — 토큰 포맷 스니핑은 휴리스틱이라 경계가 흐리고(웹 JWT도 3-세그먼트 JWS), "어떤 경로가 어떤 인증을 받는가"가 코드에서 안 보인다. 보안 경계는 선언적이어야 한다.

### 웹 JWT를 Spring Security로 통합 (멀티 디코더) ❌
웹 세션 인증(`JwtAuthFilter`)을 Spring Security 인증 공급자로 승격해 한 체인에서 두 토큰을 다 검증. 기각 — 동작 중인 웹 인증 스택(Task 14 스펙: request-attribute 기반, Security 비사용 명시) 전면 재작성이고, 장애 수정에 필요한 변경 최소화 원칙에 반한다.

### securityMatcher로 체인 분리 ✅ (채택)
OAuth RS 체인을 `securityMatcher("/mcp", "/mcp/**", "/.well-known/**")`로 MCP 표면에만 묶고, 나머지 경로는 두 번째 permissive 체인(@Order(2))이 커버. Spring Security 표준 멀티-체인 패턴. "Auth0 검증은 MCP 표면에서만"이 코드에 선언적으로 드러나고, 웹 스택은 그대로 둔다.

## 핵심 결정

- **`/.well-known/**`는 OAuth 체인에 남긴다**: RFC 9728 PRM 문서를 서빙하는 `OAuth2ProtectedResourceMetadataFilter`가 이 체인 안에 등록되므로, 체인이 그 경로를 매칭하지 않으면 문서가 안 나간다(ChatGPT/Claude의 AS 디스커버리 파괴 — TROUBLESHOOTING 2026-06-18과 동일 급 회귀).
- **2번 체인은 항상 로드**: 원래 이 config가 "항상 로드"였던 이유(클래스패스에 resource-server 스타터가 있으면 Boot 기본 lock-down이 걸리는 것 방지)를 2번 체인이 이어받는다.
- **회귀 테스트는 prod와 같은 모드로 부팅**: 이 장애의 교훈은 "기능 플래그의 prod 값 조합으로 도는 테스트가 하나는 있어야 한다"는 것. WireMock으로 OIDC 디스커버리+JWKS를 서빙해 `rs-enabled=true`로 컨텍스트를 띄우는 통합 테스트를 추가했다.

## 동작 방식

체인 1(@Order(1)) — `securityMatcher("/mcp", "/mcp/**", "/.well-known/**")` + 조건부 `oauth2ResourceServer`(Auth0 issuer/audience 검증, 401 시 RFC 9728 challenge). 체인 2(@Order(2)) — 그 외 전부, `anyRequest().permitAll()`(웹 인증은 서블릿 레벨 `JwtAuthFilter`가 계속 담당, CSRF-origin·rate-limit 가드도 기존 서블릿 필터 그대로).

## 검증

- `McpOAuthChainScopingTests` 4건 (**rs-enabled=true, WireMock OIDC**): ① 유효한 웹 JWT로 `/api/auth/me` 200 (이번 장애를 정확히 잡는 테스트) ② 웹 API에 쓰레기 Bearer → 웹 401 envelope(MCP challenge 아님, `WWW-Authenticate` 없음) ③ `/mcp`에 쓰레기 Bearer → 401 + `resource_metadata` challenge 유지 ④ PRM 문서 `authorization_servers` 광고 유지.
- prod 배포 후: 실계정 SmartID 로그인 → `/api/auth/me` 200, `/api/saint/schedule` 실데이터, ChatGPT-경로(`/mcp` challenge + PRM) 회귀 없음 확인.

## 예상 면접 질문

1. **permitAll인데 왜 401이 났나?** `permitAll()`은 *인가(authorization)* 규칙이고, `BearerTokenAuthenticationFilter`는 *인증(authentication)* 필터다. 인증 필터는 체인이 매칭한 모든 요청에서 Bearer 헤더가 있으면 검증을 시도하고, 실패 시 인가 단계에 도달하기 전에 401을 던진다. 인가 규칙으로 인증 필터를 우회할 수 없다는 것이 Spring Security의 계층 구조다.
2. **테스트가 다 green인데 prod가 죽은 이유는?** 장애 조건이 기능 플래그(prod에서만 `rs-enabled=true`)에 있었고, 모든 테스트가 기본값(off)으로 돌았다. 수정 후 prod 구성과 동일한 플래그 조합으로 부팅하는 통합 테스트를 추가해 이 클래스의 회귀를 구조적으로 막았다.
3. **왜 토큰 분기(resolver)가 아니라 경로 분리인가?** 보안 경계는 선언적·가시적이어야 한다. `securityMatcher`는 "Auth0 검증은 /mcp에서만"을 코드 한 줄로 보여주지만, 토큰 모양 스니핑은 휴리스틱이라 새 토큰 타입이 생길 때마다 경계가 흔들린다.
