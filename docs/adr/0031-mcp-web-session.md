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
- **채택: JWT 사용자 정보가 있으면 SAINT/LMS를 즉시 바인딩하고 HTTP 세션이 유효할 경우 LIBRARY까지 연계 바인딩하는 웹 브릿지 컨트롤러 추가**
  - 채택 이유: 이미 인증 완료된 JWT(`@AuthUser(required = false) String studentId`)의 아이덴티티 정보가 있으면 즉시 MCP 세션에 SAINT 및 LMS 프로바이더를 링크할 수 있다. 또한, HTTP 세션에 도서관 세션이 남아있을 경우 라이브러리 프로바이더도 즉시 연동하여 사용자 편의성을 높일 수 있다. 도서관은 독립 인증 제공자이므로 SAINT JWT가 없는 경우에도 도서관 세션만으로 MCP 세션을 발급할 수 있어야 한다.

## 결정 (Decision)

- **`POST /api/mcp/auth/web-session` 엔드포인트 신설**
  - JWT 인증 정보(`studentId`)가 있으면 SAINT 및 LMS 프로바이더를 MCP 세션에 즉시 링크한다.
  - HTTP 세션(`request.getSession(false)`)이 유효하고 `LibrarySessionStore`에 해당 세션 키가 등록되어 있을 경우, LIBRARY 프로바이더도 연계 링크한다.
  - JWT가 없어도 활성 도서관 세션이 있으면 LIBRARY만 링크한 MCP 세션을 발급한다.
  - JWT와 활성 도서관 세션이 모두 없으면 401 Unauthorized를 반환하며 MCP 세션을 생성하지 않는다.
  - 발급된 `mcpSessionId`와 만료시각 `expiresAt`을 DTO 형태로 반환한다.
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
6. `studentId`가 있으면 발급된 세션 ID에 SAINT 및 LMS 프로바이더를 `studentId`를 principalKey로 설정해 링크한다 (`mcpAuthService.linkProvider`).
7. 도서관 세션 키가 있으면 LIBRARY 프로바이더를 HTTP session id를 principalKey로 설정해 링크한다.
8. 최종 생성된 `mcpSessionId` 및 `expiresAt` 시각을 `McpWebSessionResponse` DTO에 담아 `ApiResponse.success`로 감싸서 반환(201 Created)한다.

## 검증 (Validation)

- `McpWebSessionControllerTests`:
  - `create_withJwtAndNoLibrarySession_linksSaintAndLmsOnly`: JWT 인증 성공 시 SAINT/LMS 바인딩 및 201 응답 검증
  - `create_withJwtAndLibrarySession_linksAllProviders`: HTTP 세션 및 도서관 세션 유효 시 LIBRARY 바인딩 검증
  - `create_withoutJwtAndWithLibrarySession_linksLibraryOnly`: JWT 없이 도서관 세션만 있을 때 LIBRARY 바인딩 및 201 응답 검증
  - `create_withoutJwtAndWithoutLibrarySession_returnsUnauthorized`: 모든 인증 정보 누락 시 401 Unauthorized 및 세션 미생성 검증

## 추가 보안 메모 (2026-07-11)

- `web-session`이 도서관 `JSESSIONID` 쿠키 신원을 받아 MCP 세션을 발급할 수 있게 되면서 `CsrfOriginGuardFilter` 예외에서 제거했다.
- 예외 유지 대안은 기각했다. 운영 환경의 도서관 세션 쿠키는 `SameSite=None`이라 cross-site POST로 발급 시도를 만들 수 있고, 정상 브라우저 트래픽은 허용된 `Origin`을 싣거나 비브라우저 클라이언트처럼 `Origin`/`Referer`가 없어 영향이 없다.
