# ADR 0031: MCP Web Session Endpoint 추가

## 배경 (Background)

- 기존 MCP 인증 흐름(Task 18 Slice B)은 외부 MCP 클라이언트를 대상으로 설계되어, 브라우저 리디렉션을 통한 로그인 과정을 필수로 요구했다.
- 하지만 ssuAI 웹 프론트엔드 내의 에이전트 대화(ssuAgent)는 이미 웹 인증을 마친 사용자가 사용하므로, 별도의 브라우저 로그인 단계를 추가로 요구하면 UX가 저하된다.
- 본 작업의 목적은 SAINT JWT 또는 HTTP 세션(도서관 인증용)을 활용하여 즉시 사용 가능한 `mcp_session_id`를 발급받을 수 있는 브라우저 무관 브릿지 엔드포인트를 추가하는 것이다.

## 검토한 대안 (Alternatives considered)

- **기존 OAuth/SSO 콜백 엔드포인트 재사용**
  - 탈락: 기존 콜백은 외부 브라우저 인증 성공 후 리디렉션을 받아 처리하도록 설계되어 있어, 내부 JWT 세션 정보만으로 즉시 연동하기에 프로토콜 흐름이 어긋나며 보안 컨텍스트 구성이 복잡해진다.
- **별도 프론트엔드 전용 인증 토큰 발급 및 별개 저장소 설계**
  - 탈락: MCP 서버 및 도구들이 바라보는 세션 저장소(`McpAuthSessionStore`)가 이미 존재하므로, 신규 토큰 저장소를 설계하는 것은 중복이고 데이터 정합성 관리를 어렵게 만든다.
- **채택: 웹 identity가 가진 실제 provider credential을 격리 복사하고 HTTP 세션이 유효할 경우 LIBRARY까지 연계하는 웹 브릿지 컨트롤러 추가**
  - 채택 이유: JWT의 `studentId`는 웹 사용자 신원을 확인하는 데 사용하고, SAINT/LMS는 별도 canonical credential이 실제로 존재할 때만 새 opaque namespace로 복사해 MCP 세션에 링크한다. HTTP 세션에 도서관 credential이 남아있으면 같은 방식으로 LIBRARY도 연동한다. 도서관은 독립 인증 제공자이므로 SAINT JWT가 없는 경우에도 도서관 세션만으로 MCP 세션을 발급할 수 있어야 한다.

## 결정 (Decision)

- **`POST /api/mcp/auth/web-session` 엔드포인트 신설**
  - JWT 인증 정보(`studentId`)가 있으면 유효한 SAINT 및 LMS credential을 독립 owner key로 복사한 뒤 성공한 프로바이더만 MCP 세션에 링크한다.
  - HTTP 세션(`request.getSession(false)`)이 유효하고 `LibrarySessionStore`에 해당 세션 키가 등록되어 있을 경우, LIBRARY credential도 독립 owner key로 복사해 링크한다.
  - JWT가 없어도 활성 도서관 세션이 있으면 LIBRARY만 링크한 MCP 세션을 발급한다.
  - JWT와 활성 도서관 세션이 모두 없으면 401 Unauthorized를 반환하며 MCP 세션을 생성하지 않는다.
  - 발급된 `mcpSessionId`, 만료시각 `expiresAt`, 실제 복사에 성공한 `linkedProviders`를 DTO 형태로 반환한다.
- **테스트 커버리지 확보**
  - MockMvc를 활용한 `@WebMvcTest(McpWebSessionController.class)`를 작성하여 다음 시나리오를 검증한다.
    1. JWT가 유효하고 도서관 세션이 없을 때 SAINT, LMS 프로바이더만 바인딩되는지 검증
    2. JWT와 도서관 세션이 모두 존재할 때 LIBRARY 프로바이더까지 추가로 바인딩되는지 검증
    3. JWT가 없고 도서관 세션만 존재할 때 LIBRARY 프로바이더만 바인딩되는지 검증
    4. JWT와 도서관 세션이 모두 비어있을 때 401 Unauthorized 응답을 반환하고 세션을 만들지 않는지 검증

## 작동 방식 (How it works)

1. 사용자가 `POST /api/mcp/auth/web-session`을 호출한다. JWT Bearer 인증은 선택 사항이다.
2. `AuthUserArgumentResolver`에 의해 JWT 토큰으로부터 추출된 `studentId`가 있으면 `@AuthUser(required = false)` 파라미터로 주입되고, 없으면 `null`이 주입된다.
3. HTTP 요청의 HttpSession이 존재하고, `LibrarySessionStore.has(sessionKey)`가 `true`이면 도서관 세션 키를 확보한다.
4. `studentId`와 도서관 세션 키가 모두 없으면 401 Unauthorized를 반환한다.
5. `McpAuthService.createSession()`을 호출하여 새로운 MCP 인증 세션을 생성한다.
6. `studentId`가 있으면 SAINT/LMS canonical credential을 각각 새 opaque owner key로 재암호화한다. 원본 만료 시각과 provider health를 보존하고 `EXPIRED` credential은 복사하지 않는다.
7. 도서관 세션 키가 있으면 LIBRARY credential도 새 owner key로 복사하며 원본 만료 시각을 보존한다.
8. 복사와 provider link 중 예외가 발생하면 생성한 MCP 세션과 owner credential을 보상 정리한다.
9. 최종 생성된 `mcpSessionId`, `expiresAt`, `linkedProviders`를 `McpWebSessionResponse` DTO에 담아 `ApiResponse.success`로 감싸서 반환(201 Created)한다.

## 검증 (Validation)

- `McpWebSessionControllerTests`:
  - `create_withJwtAndNoLibrarySession_linksSaintAndLmsOnly`: JWT 인증 성공 시 SAINT/LMS 바인딩 및 201 응답 검증
  - `create_withJwtAndLibrarySession_linksAllProviders`: HTTP 세션 및 도서관 세션 유효 시 LIBRARY 바인딩 검증
  - `create_withoutJwtAndWithLibrarySession_linksLibraryOnly`: JWT 없이 도서관 세션만 있을 때 LIBRARY 바인딩 및 201 응답 검증
  - `create_withoutJwtAndWithoutLibrarySession_returnsUnauthorized`: 모든 인증 정보 누락 시 401 Unauthorized 및 세션 미생성 검증

## 추가 보안 메모 (2026-07-11)

- `web-session`이 도서관 `JSESSIONID` 쿠키 신원을 받아 MCP 세션을 발급할 수 있게 되면서 `CsrfOriginGuardFilter` 예외에서 제거했다.
- 예외 유지 대안은 기각했다. 운영 환경의 도서관 세션 쿠키는 `SameSite=None`이라 cross-site POST로 발급 시도를 만들 수 있고, 정상 브라우저 트래픽은 허용된 `Origin`을 싣거나 비브라우저 클라이언트처럼 `Origin`/`Referer`가 없어 영향이 없다.

## 후속 결정 (2026-07-16) - 실제 credential grant를 응답한다

- JWT의 `studentId`는 웹 사용자의 신원이지 SAINT/LMS credential 보유 증명이 아니다. 따라서 웹 세션 발급 시 각 provider의 canonical credential을 새 opaque owner key로 복사하고, 복사에 성공한 provider만 MCP 세션에 링크한다.
- 영속 저장소의 복사는 읽기와 `findForUpdate` 쓰기를 하나의 외부 트랜잭션 경계에서 수행한다. `copyForSession()`이 트랜잭션 메서드를 self-invocation하던 구조는 Spring 프록시를 우회해 운영 PostgreSQL에서 잠금 쿼리가 비트랜잭션으로 실행될 수 있었다.
- 응답에 `linkedProviders`를 추가한다. 브라우저는 JWT 존재나 요청한 provider 목록으로 연결 상태를 추정하지 않고 서버가 실제로 복사한 grant만 사용한다. credential이 만료되거나 배포 전 메모리 저장소에서 이관되지 않아 복사할 수 없는 provider는 목록에 포함하지 않는다.
- `POST /api/mcp/auth/web-session/status`는 기존 세션 ID를 회전하지 않고 현재 link와 credential 가용성을 다시 읽는다. JWT 사용자는 세션에 바인딩된 OAuth subject가 일치해야 하고, library-only 사용자는 활성 도서관 웹 세션이 있어야 한다. provider callback·logout·만료 뒤 브라우저가 7일짜리 발급 스냅샷을 계속 표시하지 않게 한다.
- provider별 독립 credential namespace는 유지한다. `studentId`를 principal key로 직접 연결하는 방식은 서로 다른 MCP 세션이 같은 mutable credential을 공유하므로 기각했다.
- 복사는 원본 credential의 `capturedAt`, `expiresAt`, provider health를 보존한다. 브릿지 발급이 upstream 로그인 수명을 연장하거나 `EXPIRED` 상태를 `UNKNOWN`으로 되돌려서는 안 된다.
- OAuth subject 바인딩 거절·예외와 provider 복사를 포함한 발급 중 실패가 나면 이미 만든 MCP 세션과 provider별 owner namespace를 보상 삭제한다. 저장소별 독립 트랜잭션은 유지하되 클라이언트가 받지 못한 세션이나 credential 복사본을 남기지 않는다.
- 부분 연결은 허용한다. 예를 들어 도서관 credential만 유효하면 도서관 채팅을 계속 사용할 수 있고, SAINT/LMS는 `linkedProviders` 부재를 근거로 재인증을 안내한다.

추가 검증은 실제 Spring proxy와 영속 저장소를 사용해 외부 트랜잭션 없이 `copyForSession()`을 호출하는 통합 테스트, 만료·health 보존, 빈/부분 grant 응답, 예외 보상 정리, live-status subject 검증과 만료 credential 제외 테스트로 구성한다. 상세 장애 기록은 [MCP 웹 세션 credential 복사 트랜잭션 회귀](../troubleshooting/mcp-web-session-credential-copy.md)에 남겼다.
