# ADR 0037 — RFC 9728 PRM에 `authorization_servers` 주입: Spring Security 7 커스터마이저 (수제 컨트롤러 폐기)

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-18 |
| 상태 | Accepted |
| 연관 ADR | [ADR 0036](0036-mcp-auth-optin-two-mode.md) (opt-in OAuth RS 2모드) |
| 브랜치/PR | `fix/mcp-prm-authorization-servers` |

---

## 배경 — 무엇이 문제인가

ADR 0036으로 opt-in OAuth RS 모드를 켠 뒤(G3, prod), ChatGPT가 `start_auth` 루프에서 벗어나지 못했다. MCP OAuth 흐름은 RFC 9728 **Protected Resource Metadata(PRM)** 문서에 의존한다:

1. 클라이언트가 `/mcp`에 토큰 없이 접근 → 401 + `WWW-Authenticate: Bearer resource_metadata="<PRM URL>"`.
2. 클라이언트가 `/.well-known/oauth-protected-resource`를 받아 **`authorization_servers`** 배열에서 AS(Auth0)를 발견.
3. 그 AS로 OAuth 2.1 + PKCE → JWT 발급 → `/mcp` 재시도.

prod 실측 결과 PRM 응답에 `authorization_servers`가 **없었다**:

```
curl https://ssumcp.duckdns.org/.well-known/oauth-protected-resource
→ {"resource":"https://ssumcp.duckdns.org","bearer_methods_supported":["header"],
   "tls_client_certificate_bound_access_tokens":true}
```

→ ChatGPT가 Auth0를 발견하지 못해 Bearer 흐름에 진입조차 못 함.

### 틀린 가설과 그것을 깬 단서

MASTERPLAN에는 "PRM 응답에 `authorization_servers`를 **추가 구현**해야 한다"고 적혀 있었다. 하지만 `ProtectedResourceMetadataController`(수제 `@GetMapping`)는 **이미** 그 필드를 반환하도록 작성돼 있었다. 결정적 단서는 실제 응답의 `tls_client_certificate_bound_access_tokens` 필드 — 이 문자열은 코드 전체 grep으로도 우리 코드 어디에도 없었다. 즉 **응답을 만드는 주체가 우리 컨트롤러가 아니었다.**

### 진짜 원인: Spring Security 7이 같은 경로를 필터로 선점

Spring Boot 4.0.6 = **Spring Security 7.0.5**. `oauth2ResourceServer(...)`를 설정하면 Security가 **`OAuth2ProtectedResourceMetadataFilter`를 자동 등록**해 `/.well-known/oauth-protected-resource`를 직접 서빙한다. 서블릿 필터는 `DispatcherServlet`(MVC `@GetMapping`)보다 **먼저** 실행되므로, 같은 경로의 수제 컨트롤러는 조용히 가려져(shadowing) 한 번도 실행되지 않았다. 프레임워크 기본 문서는 외부 AS를 알 수 없어 `authorization_servers`를 비운다.

---

## 대안 비교

| 대안 | 설명 | 채택 |
|---|---|---|
| A. 수제 컨트롤러 유지 + 필터 비활성화 | Security의 PRM 필터를 끄고 MVC 컨트롤러가 응답하게 함 | ✗ 프레임워크와 싸우는 구조. 비활성화 API가 불명확하고, 향후 Security 업그레이드 때 깨지기 쉬움 |
| B. 필터의 확장 지점으로 필드 주입 (채택) | `protectedResourceMetadataCustomizer`로 `authorization_servers`만 추가, 수제 컨트롤러는 삭제 | ✓ 프레임워크-네이티브. RFC 9728 표준 필드 외엔 손대지 않음 |
| C. 컨트롤러를 다른 경로로 이동 | 표준 well-known 경로를 벗어남 | ✗ RFC 9728/ MCP 클라이언트는 고정 경로를 기대 → 발견 불가 |

---

## 결정

**대안 B.** Security 7 DSL의 확장 지점으로 관리형 AS issuer를 주입하고, 가려져 죽은 수제 컨트롤러는 삭제한다.

```java
http.oauth2ResourceServer(oauth2 -> oauth2
    .jwt(jwt -> jwt.decoder(decoder))
    .protectedResourceMetadata(metadata -> metadata
        .protectedResourceMetadataCustomizer(authorizationServersCustomizer(issuerUri)))
    .authenticationEntryPoint(/* 401 + resource_metadata */));

// 단위 테스트를 위해 람다를 static 메서드로 추출
static Consumer<OAuth2ProtectedResourceMetadata.Builder> authorizationServersCustomizer(String issuerUri) {
    return builder -> builder.authorizationServer(issuerUri);
}
```

### 결정 근거

- **추측 금지, 실측 검증**: DSL/빌더 시그니처는 공식 문서 + 실제 `spring-security-config-7.0.5.jar` / `spring-security-oauth2-resource-server-7.0.5.jar`를 `javap`로 까서 확정(`OAuth2ResourceServerConfigurer.protectedResourceMetadata(Customizer<…>)`, `…Builder.authorizationServer(String)`).
- **`issuerUri` 재사용**: `authorization_servers`의 값은 JWT 검증의 issuer와 **반드시 동일**해야 한다(같은 trailing slash 포함). 같은 프로퍼티(`ssuai.mcp.oauth.issuer-uri`)를 쓰므로 두 곳이 영원히 일치한다.
- **테스트 용이성**: 커스터마이저를 static 메서드로 분리 → 서블릿 필터·OIDC discovery 없이 빌더에 적용 후 claim만 검증하는 빠르고 결정적인 회귀 테스트가 가능(`McpOAuthSecurityConfigTests`).

---

## 작동 방식 / 검증

- 단위 테스트: 커스터마이저 적용 후 `metadata.getAuthorizationServers()`가 issuer를 담는지 검증(green).
- 종단 검증: 배포 후 `curl …/.well-known/oauth-protected-resource` 응답에 `"authorization_servers":["https://dev-…auth0.com/"]` 포함 확인.
- ⚠️ "ChatGPT가 실제로 루프를 벗어나 로그인에 성공" 여부는 사용자 브라우저 게이트 — 코드 레벨에선 PRM 응답 정확성까지만 보장한다.

---

## 예상 면접 질문

1. MVC `@GetMapping`이 분명히 있는데 왜 그 응답이 안 나왔나? 서블릿 필터와 `DispatcherServlet`의 실행 순서를 설명하라.
2. RFC 9728 PRM의 `authorization_servers`는 무슨 역할이고, 비어 있으면 MCP 클라이언트가 왜 인증 루프에 빠지나?
3. 라이브러리의 자동 설정(auto-registered filter) 동작을 추측이 아니라 사실로 어떻게 확정했나?
4. `authorization_servers`의 값과 JWT issuer 검증 값이 달라지면 어떤 일이 생기나? (trailing slash 포함)
