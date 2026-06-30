# ADR 0063 — admin 오너 allowlist + rate-limit 확장 + CSRF exact-path

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-23 |
| 상태 | Accepted — 구현(브랜치 `fix/security-admin-ratelimit-csrf`) |
| 범위 | `global.admin`(`AdminResilienceController`/신규 `AdminAccessProperties`) · `global.security`(`RateLimitFilter`/`RateLimitProperties`/`RateLimitFilterConfig`/`CsrfOriginGuardFilter`) · `global.exception`(신규 `ForbiddenException`+`ErrorCode.FORBIDDEN`) · `application.yml`(`ssuai.admin`) |
| 연관 ADR | [0061](0061-per-ip-rate-limit-input-caps.md)(per-IP rate limit 도입 — 본 ADR이 경로 확장) · [0036](0036-mcp-oauth-resource-server.md)(인증 모델) |
| 연관 분석 | 여러 독립 보안 리뷰 통합 triage (S2·E) |

---

## 배경 — 무슨 문제

2026-06-23 여러 독립 보안 리뷰를 통합한 분석에서 ssuMCP 코어는 견고하나 **운영 엔드포인트·엣지 통제에 갭**이 드러났다:

1. **S2 — `/api/admin/resilience` 무인증**: 서킷브레이커 상태/실패율을 반환하는데 `@AuthUser`·토큰·역할 체크가 **전혀 없었다**. 사설 컨트롤러 전수대조 결과 이 컨트롤러만 유일하게 무게이트 → 누구나 인프라 운영 시그널(어떤 LLM provider가 죽었는지 등)을 읽을 수 있었다. 데이터 민감도는 낮지만 **구조적 약점**: 인가가 "컨트롤러마다 `@AuthUser`를 붙이는" 규약 기반(allow-by-default, `McpOAuthSecurityConfig`의 `.anyRequest().permitAll()`)이라 **하나만 빠뜨리면 조용히 열린다**. 이게 바로 그 사례.
2. **E(rate-limit) — 보호 경로 부족**: ADR 0061의 per-IP 제한이 `login`·`chat`에만. 실좌석을 reserve/cancel/swap하는 **write 경로(`/api/library/reservations/confirm`)** 와 토큰 `refresh`는 무제한이었다.
3. **E(CSRF) — prefix 과다 면제**: `CsrfOriginGuardFilter`가 `/api/mcp/auth/` **prefix 전체**를 CSRF 예외 처리. 지금은 의도된 SSO 콜백뿐이라 무해하나, **그 prefix 아래 write endpoint가 추가되면 자동으로 CSRF 우회**가 된다.

## 결정

**① admin 오너 allowlist** — `AdminResilienceController.getResilience`에 `@AuthUser String studentId` + `AdminAccessProperties.isAllowed()` 대조. 불일치 시 `ForbiddenException`(403). allowlist는 `ssuai.admin.student-ids`(쉼표목록), **빈 목록 = 전원 거부(deny-by-default)**. prod는 공개 레포라 학번을 커밋하지 않고 `SSUAI_ADMIN_STUDENT_IDS` **secret**으로 주입.

**② rate-limit 확장** — `RateLimitFilter.forRules`에 두 규칙 추가: `/api/library/reservations/confirm`(20/min — write·실좌석 abuse 타깃), `/api/auth/refresh`(60/min — 관대). **SSE 스트림(`wait/events`)·OAuth 콜백은 제외**(장수명/유저주도 — 율제한 시 정상 흐름이 깨진다).

**③ CSRF exact-path** — `/api/mcp/auth/` prefix 체크를 **정확 경로 집합**으로 교체: `{/api/mcp/auth/library/callback, /api/mcp/auth/web-session}`. 향후 prefix 아래 추가될 write endpoint는 자동 면제되지 않는다.

## 대안과 기각 이유

- **admin: 로그인 학생 전체 허용(`@AuthUser`만)** — 가장 단순하나 아무 학생이나 운영 상태를 봄. 최소권한 위배 → 기각. **역할(RBAC) 도입** — 이 시스템엔 학생 OAuth 세션만 있고 admin 역할 개념이 없어 RBAC는 과설계(서킷브레이커 상태 1개 엔드포인트에). → **오너 allowlist**가 최소 변경으로 deny-by-default + 최소권한을 만족(사용자 확정).
- **admin: 공개 유지 + 민감정보만 제거** — 인가 갭의 구조적 교훈을 못 살림 → 기각.
- **CSRF: web-session도 면제 해제(가드 적용)** — Bearer 인증이라 CSRF 자체가 N/A(쿠키 아님)고, 기존 동작/테스트를 깨며 미지의 흐름 리스크 → **현행 동작 보존 위해 면제 유지**. 핵심 목표(미래 endpoint 자동면제 차단)는 exact-path만으로 달성.
- **rate-limit에 OAuth 콜백/SSE 포함** — 콜백 율제한은 SSO 로그인을, SSE 율제한은 실시간 좌석 스트림을 깨뜨림 → 제외.

## 동작 방식 / 검증

- 인가 흐름: `JwtAuthFilter`가 STUDENT_ID를 request attribute에 세팅 → `AuthUserArgumentResolver`가 `@AuthUser`로 주입(없으면 401) → 컨트롤러가 allowlist 대조(불일치 403). `GlobalExceptionHandler.handleApiException`이 `ErrorCode.FORBIDDEN.getStatus()` → 403 매핑.
- 단위테스트: `AdminResilienceControllerTests`에 allowlist 통과/비allowlist 403/빈 allowlist 403 추가. `RateLimitFilterTests`에 confirm 경로 한도 초과 429. `CsrfOriginGuardFilterTests`에 `/api/mcp/auth/` 하위 임의 경로는 **미면제** 확인.
- 배포 후: 미인증 `/api/admin/resilience` → 403, 오너 세션 → 200.

## 예상 면접 질문

1. **"allow-by-default 인가의 위험을 실제 사례로 설명해보라."** — Spring Security를 `permitAll`로 열고 컨트롤러별 `@AuthUser`로 게이팅하면(공개 도구 zero-auth를 위해) 새 컨트롤러에서 `@AuthUser`를 한 번 빠뜨리는 순간 조용히 열린다. AdminResilienceController가 그 사례였고, 전수대조로 발견했다. 근본 교정은 deny-by-default(빈 allowlist=전원거부)와 코드리뷰 체크리스트.
2. **"왜 admin에 RBAC를 안 쓰고 allowlist로 했나?"** — 도메인에 admin 역할 개념이 없고(학생 세션만) 보호 대상이 운영 시그널 1개 엔드포인트라 RBAC는 과설계. 포트폴리오 관점에서도 "최소권한을 최소 변경으로"가 더 방어 가능한 결정.
3. **"CSRF를 prefix가 아니라 exact-path로 면제한 이유는?"** — prefix 면제는 미래에 그 prefix 아래 추가되는 write endpoint를 자동으로 CSRF 우회 대상으로 만든다(시한폭탄). exact-path는 면제를 현재 실제 필요한 콜백으로 한정한다. defense-in-depth + 변경 안전성.
