# ADR 0057 — CSRF 방어: Origin/Referer 검증 필터 (SameSite=None 유지)

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-21 |
| 상태 | Accepted — 구현·배포(`63a32d7` #118) |
| 범위 | `CsrfOriginGuardFilter`(`global.security`) / `McpOAuthSecurityConfig` 필터 등록 |
| 연관 ADR | [0014](0014-saint-sso-redirect-callback.md)(쿠키 인증 설계), [0036](0036-mcp-auth-optin-two-mode.md) |
| 연관 사건 | TROUBLESHOOTING 사건 17 |

---

## 배경 — 무슨 문제

보안 분석에서 두 가지가 함께 지적됐다: ① Spring CSRF 토큰 보호가 전역 disable + `/api/**` permitAll ② refresh 쿠키 `SameSite=None`. 외부 리뷰(P1-P)는 "SameSite를 Lax로 바꾸면 CSRF가 자동 완화된다"고 처방했다.

**그 처방은 오진이다.** prod 프론트(`ssuai.vercel.app`)와 백엔드(`ssumcp.duckdns.org`)는 **등록 가능한 도메인이 다른 cross-site**다. 프론트의 `fetchJson`은 same-origin rewrite가 아니라 `getApiBaseUrl()`(cross-site 백엔드 URL)로 직접 호출하고, `application-prod.yml`은 refresh 쿠키를 **의도적으로 `SameSite=None`으로 설정**(주석에 명시)한다. `SameSite=Lax`로 바꾸면 `POST /api/auth/refresh`에서 브라우저가 쿠키를 드랍 → 401 "세션 갱신 실패" → **auth refresh 전면 장애**. 따라서 SameSite는 None을 유지해야 한다.

그러면 쿠키 인증 + cross-site 흐름에서 CSRF를 어떻게 막을 것인가가 진짜 문제다.

## 결정

쿠키 인증 + 상태변경 요청을 **Origin/Referer 검증 필터**(`CsrfOriginGuardFilter`, `/api/*` `OncePerRequestFilter`)로 방어한다. SameSite는 변경하지 않는다.

판정 로직(상태변경 메서드 `POST`/`PUT`/`PATCH`/`DELETE`에만):

1. `Origin` 헤더 있음 → 허용 origin 집합에 없으면 **403**.
2. `Origin` 없고 `Referer` 있음 → Referer를 `scheme://host[:port]` origin으로 정규화 후 집합 대조, 불일치 **403**.
3. 둘 다 없음 → **허용**. 실제 CSRF는 브라우저 주도이고 브라우저는 cross-site 상태변경 fetch에 항상 Origin/Referer를 붙인다. 비브라우저/서버 클라이언트는 정당하게 생략하므로 막지 않는다.

허용 origin 집합 = `ssuai.frontend.origin`(`SSUAI_FRONTEND_ORIGIN`, prod=`https://ssuai.vercel.app`, dev=`localhost:3000`) — CORS allowlist를 재사용한다. prod에서 이 값이 비면 `WebCorsProdConfig`가 이미 startup 실패시키므로 빈 집합으로 모두 허용되는 사고가 구조적으로 없다.

제외: `/mcp/**`(Bearer, `/api/` 하위 아님 → 이 필터에 도달 안 함) · `/actuator/**` · SSO 콜백 prefix `/api/mcp/auth/**`(provider redirect/top-level navigation/SSO form post를 받음 — Origin 검사 시 로그인이 깨짐). u-SAINT/LMS `sso-callback`은 `GET`이라 메서드로 자동 제외.

## 대안과 기각 이유

- **SameSite=None → Lax (외부 리뷰 P1-P 처방)**: 오진. cross-site refresh 쿠키 드랍 → auth 장애. 기각(사건 17).
- **Spring 토큰 CSRF(synchronizer token)**: cross-site 프론트는 토큰 쿠키를 same-site로 읽지 못하거나 모든 요청에 토큰을 심어야 해 SPA + cross-site 구조와 충돌. Double-submit도 cross-site 쿠키 가독성 문제 동일. 기각.
- **상태변경을 전부 Bearer로 전환**: refresh 흐름은 본질적으로 쿠키 기반이라 전환 불가. 기각.
- **Origin 없을 때 거부(deny by default)**: 정당한 비브라우저 클라이언트·일부 SSO redirect를 막아 회귀. CSRF는 브라우저에서만 성립하므로 헤더 부재 = CSRF 불가로 보고 허용. 채택.

## 동작 방식

- `shouldNotFilter`: 비-상태변경 메서드 또는 `/api/mcp/auth/` prefix면 통과.
- `doFilterInternal`: 위 1~3 판정. 403 시 `ApiResponse.error("CSRF_ORIGIN_NOT_ALLOWED", ...)` JSON 본문 + WARN 로그(헤더 원문은 로그하지 않음 — 로그 인젝션 방지, 경로 + 실패 헤더명만).
- `toOrigin`: Referer URL을 `scheme://host[:port]`로 축약(명시 포트 없으면 포트 생략 → `https://ssuai.vercel.app/dashboard`가 `https://ssuai.vercel.app`로 정규화). 스킴/호스트 없으면 null.
- 고위험 필터체인이라 머지 전 하드리뷰 + 13개 테스트. 기존 HttpOnly+Secure 쿠키 + CORS origin pin 위에 적층(defense-in-depth).

## 예상 면접 질문

1. SameSite=None을 유지하면서 CSRF를 막아야 했던 이유는? "Lax로 바꾸면 된다"는 처방이 왜 이 시스템에선 auth 장애를 일으키나?
2. 토큰 CSRF 대신 Origin/Referer 검증을 택한 이유는? cross-site SPA에서 double-submit 토큰의 한계는?
3. Origin·Referer가 둘 다 없을 때 거부가 아니라 허용하는 결정의 근거와 트레이드오프는?
4. SSO 콜백 prefix를 통째로 제외한 이유는? Origin 검사가 로그인 흐름을 어떻게 깨뜨리나?
