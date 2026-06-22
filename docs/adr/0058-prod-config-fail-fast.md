# ADR 0058 — 프로덕션 설정 fail-fast (ProdConfigValidator)

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-21 |
| 상태 | Accepted — 구현·배포(`60403c4` #117) |
| 범위 | `ProdConfigValidator`(`global.config`) |
| 연관 ADR | [0014](0014-saint-sso-redirect-callback.md), [0036](0036-mcp-auth-optin-two-mode.md) |
| 연관 사건 | TROUBLESHOOTING 사건 17 |

---

## 배경 — 무슨 문제

prod-critical 값 여러 개가 `${ENV:default}` 패턴으로 **dev 친화적 안전치 못한 기본값**에 폴백한다:

- `SSUAI_DB_URL` 미설정 → 인메모리 H2로 폴백(데이터 유실).
- `SSUAI_DB_PASSWORD` → 빈 값.
- `SSUAI_JWT_SECRET` / `SSUAI_CREDENTIAL_ENCRYPTION_KEY` → 재시작마다 임시 랜덤(prod 토큰·저장 세션 전부 무효화).
- k8s `secretRef`가 `optional: true`(fail-open 설계).

즉 시크릿이 빠지거나 잘못 설정돼도 앱이 **크래시하지 않고 H2/임시키로 조용히 부팅**해 데이터를 망가뜨리거나 모두의 세션을 무효화한다. "silent insecure fallback"을 "refuse to start"로 바꿔야 한다.

## 결정

`@Configuration @Profile("prod")` 빈 `ProdConfigValidator`를 추가한다. 생성자에서 검증 실패 시 `IllegalStateException`을 던져 **context refresh 중**(Tomcat 포트 바인딩 전) startup을 실패시킨다. `WebCorsProdConfig`와 같은 패턴이다. dev/test는 이 빈이 로드되지 않아 H2/임시키 기본값을 그대로 쓴다.

검증 대상은 **애플리케이션이 실제로 소비하는 값** — bound `@ConfigurationProperties` 빈(`JwtProperties`·`SaintSessionProperties`·`LlmChatProperties`) + `Environment`의 `spring.datasource.url`·`ssuai.mcp.oauth.*` — 을 직접 읽는다. env-var 이름을 재나열하지 않으므로 실제 사용과 드리프트하지 않는다.

검증 항목:
1. `spring.datasource.url`이 비었거나 `jdbc:h2`/`h2:mem`이면 실패(미설정 → H2 폴백 케이스 동시 포착).
2. JWT secret 비면 실패.
3. credential encryption key 비면 실패.
4. `ssuai.mcp.oauth.rs-enabled=true`인데 `issuer-uri` 또는 `audience`가 비면 실패.
5. 10개 LLM provider 중 API 키가 하나도 없으면 실패(bound provider 빈을 순회 → 드리프트 방지).

## 대안과 기각 이유

- **env-var 이름 목록을 하드코딩해 검증**: bound 프로퍼티 빈과 드리프트(이름 변경·프로퍼티 추가 시 누락). 실제 소비 빈을 검증해 기각.
- **`@Validated` Bean Validation 어노테이션**: prod에서만 강제하고 dev는 H2 허용해야 하는 프로파일 분기를 어노테이션으로 표현하기 번거롭고, "H2 아님" 같은 의미 검증은 커스텀이 필요. 생성자 검증으로 기각.
- **런타임 헬스체크로 경고만**: 이미 부팅된 뒤라 데이터가 H2에 쓰이기 시작한 후. startup 차단이 유일하게 안전. 기각.
- **CHECK·강제 없이 문서로만 안내**: 사람이 빠뜨리는 게 원인이라 무효. 기각.

## 동작 방식 / 검증 (검증-후-적용 규율)

- 비가역 위험(잘못 켜면 prod crash-loop)이라 **배포 전 `kubectl -n ssuai-prod`로 시크릿 10종 + `SSUAI_OAUTH_RS_ENABLED=true`(Auth0 issuer/audience 설정됨)가 전부 present임을 선확인** → crash-loop 없음을 보장한 뒤 적용(advisor "먼저 확인" 반영).
- 배포 후 백엔드 pod **1/1 Running 0 restarts** 확인 = prod에서 ProdConfigValidator 통과 확정. health UP, `/mcp` 0.18.3 정상.
- 11개 테스트(각 항목 누락 시 startup 실패, 정상 prod 설정 시 통과).

## 예상 면접 질문

1. "시크릿이 빠져도 안 죽고 H2로 부팅"이 왜 단순 버그가 아니라 보안/데이터 사고인가? fail-open과 fail-fast의 트레이드오프는?
2. env-var 이름을 재나열하지 않고 bound `@ConfigurationProperties` 빈을 검증한 이유는? 무엇이 드리프트하지 않게 되나?
3. fail-fast를 켜면 prod가 crash-loop에 빠질 위험이 있다. 비가역 변경을 자율 배포하기 전에 어떻게 안전을 보장했나?
4. 왜 생성자 throw(`@Profile("prod")`)인가? 런타임 헬스체크 경고로는 왜 부족한가?
