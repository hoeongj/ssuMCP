# ADR 0096 — 도서관 세션 키를 서블릿 세션 대신 영구 쿠키로 교체

- 상태: 채택 (2026-07-12)
- 관련: 0088(HA 2-replica/HPA/PDB), 0092(MCP 세션 어피니티 Traefik sticky 쿠키)
- 번호 메모: 0095는 별도 브랜치(`fix/sso-code-exchange`)에서 먼저 점유했다. 충돌을 피하기 위해 이 문서는 0096을 사용한다.

## 배경 (문제)

도서관(Pyxis/oasis) 로그인은 정상 동작하고, 발급받은 Pyxis 액세스 토큰은 Postgres에 암호화되어
영구 저장된다(`LibrarySessionStore`, `library_sessions` 테이블, TTL 7일). 문제는 그 저장소의
**키**였다: Tomcat 서블릿 세션 id(JSESSIONID)를 그대로 키로 썼다. 서블릿 세션은 파드 로컬 인메모리
객체이므로, 백엔드가 롤링 재배포되거나(ADR 0088로 2 replica HA 운영 중) 어피니티가 어긋나 요청이
다른 파드로 가면 다음이 일어난다:

1. 클라이언트는 예전 JSESSIONID 쿠키를 그대로 들고 온다.
2. 새 파드에는 그 세션 id에 대응하는 서블릿 세션이 없다 — Spring이 조용히 새 세션을 발급하거나
   (`getSession()`), `getSession(false)`가 null을 반환한다.
3. `LibrarySessionStore` 조회가 (새 세션 id 또는 null 키로) 미스한다.
4. `/api/library/loans` 등은 401 `LIBRARY_SESSION_REQUIRED`를 반환하고, 프론트는 사이드바를
   "연동 안 됨"으로 되돌린다 — 도서관 토큰은 Postgres에 멀쩡히 살아있는데도.

이 특성은 기존에 TROUBLESHOOTING.md에 "기존 특성, 이번 수정과 무관"으로 관찰만 되어 있었다.
2-replica + 정기 롤링 배포 환경에서는 배포할 때마다 실사용자가 도서관 로그인을 다시 해야 하는
사실상의 회귀로 누적되어, 이번에 근본 수정한다.

## 고려한 대안과 기각 사유

1. **Spring Session + Redis로 서블릿 세션 전체를 외부화한다.** 기각 — 이 저장소 하나의 키 문제를
   해결하려고 애플리케이션의 모든 서블릿 세션(MCP 인증 경로 포함)을 Redis로 옮기는 것은 새 인프라
   의존성과 넓은 blast radius를 요구한다. ADR 0092에서 이미 확인했듯, MCP 쪽 세션 관련 상태는
   이미 자체적으로(Postgres, McpAuthService) 외부화되어 있어 서블릿 세션 자체를 건드릴 이유가
   없다.
2. **Traefik sticky 쿠키(ADR 0092)로 어피니티를 보장하고 재배포를 하지 않는다.** 기각 — sticky
   쿠키는 "같은 파드가 살아있는 동안" 어피니티를 지킬 뿐, 파드가 재시작(배포)되면 아무 의미가
   없다. 재배포는 일상적인 운영 행위이므로 "재배포를 안 한다"는 전제 자체가 성립하지 않는다.
3. **JSESSIONID는 그대로 두고 서블릿 세션 자체를 어딘가에 영속화한다.** 기각 — 1번과 사실상 동일한
   해법(외부 세션 스토어)이고 같은 이유로 기각된다.
4. **도서관 로그인 시 서버가 직접 생성한 영구 쿠키를 저장소 키로 쓴다. (채택)** `LibrarySessionStore`는
   이미 Postgres에 내구성 있게 저장되고 있었다 — 휘발성이었던 것은 오직 "키"였다. 서버가 발급하는
   랜덤 키를 쿠키에 담아 저장소 키로 쓰면, 그 키는 어느 파드에서 검증하든 동일한 DB row를
   찾아낸다. 스키마/테이블 변경 없음(같은 테이블, 같은 문자열 PK), 마이그레이션 불필요.

## 결정

`POST /api/library/login`에서 `UUID.randomUUID()`로 세션 키를 새로 발급하고, 그 값을
`ssuai_library_session` HttpOnly 쿠키로 내려준다. 이 쿠키 값이 `LibrarySessionStore`의 저장소
키가 된다.

- **쿠키 속성**: `AuthProperties.RefreshCookie` 패턴을 그대로 따라 `LibrarySessionProperties.SessionCookie`를
  추가했다. 이름 기본값 `ssuai_library_session`, path `/`, `httpOnly`는 설정 불가(항상 true —
  브라우저 JS가 이 값을 읽을 이유가 없다), `secure`/`same-site`는 프로필별로 다르다.
  - 기본(dev/test) yml: `same-site: Lax`, `secure: false` (로컬은 평문 HTTP).
  - `application-prod.yml`: `secure: true`, `same-site: None`. 프론트(Vercel)와 백엔드(duckdns)가
    서로 다른 사이트이기 때문에 크로스사이트로 쿠키가 전달되어야 하고, 이는 기존 `refresh-cookie`
    (ADR 관련 없음, `AuthProperties`)와 동일한 근거다 — 쿠키가 이미 HttpOnly + Secure이고
    프론트는 same-origin 프록시 경로로 자격 증명 fetch를 보낸다.
- **TTL 정합**: 쿠키 `maxAge`는 `ssuai.library.session.ttl`(현재 7일)을 그대로 사용한다 — 쿠키와
  DB row가 같은 시점에 만료되도록 맞췄다.
- **세션 고정(fixation) 입장**: 기존 코드는 로그인 성공 후 `httpRequest.getSession()` +
  `changeSessionId()`로 서블릿 세션 id를 회전시켜 고정 공격을 방어했다. 새 키는 서버가 이 요청에서
  막 생성해 클라이언트로부터 절대 받아들이지 않으므로, 애초에 고정시킬 대상이 없다 —
  `changeSessionId()` 호출 자체가 불필요해졌다.
- **읽기 경로 통일**: `LibrarySessionKeyResolver`(신규, `domain.library.auth` 패키지)가
  `Optional<String> resolve(HttpServletRequest)`를 제공한다. 쿠키 값이 있으면 그것을, 없으면
  기존 서블릿 세션(`getSession(false)`)을 폴백으로 반환하고, 아무것도 없으면 empty를 반환한다.
  **레거시 폴백은 정책적으로 한 배포 세대만 유지**한다 — 이 배포 전에 로그인해 서블릿 세션에
  바인딩된 사용자가 즉시 로그아웃되지 않도록 하기 위함이며, 그 세대의 사용자가 모두 재로그인하거나
  세션이 자연 만료되면 폴백 분기를 제거해도 된다.
  - 리졸버는 `getSession(false)`만 호출한다 — 인증 여부를 확인하는 행위 자체가 새 서블릿 세션을
    만들어서는 안 된다는 게 원칙이다(기존 `LibraryLoansController`/`LibraryReservationWebController`의
    `httpRequest.getSession().getId()` 호출은 이 원칙을 어기고 있었다 — 매 미인증 요청마다 새
    세션을 만드는 부작용이 있었다).
- **로그아웃/연동 해제**: 기존 `DELETE /api/library/session`을 리졸버로 갱신 — 리졸브된 키로 store
  row를 지우고, 쿠키를 `Max-Age=0`으로 덮어써 지운다(값이 없어도 항상 클리어 응답을 반환해
  `AuthController.logout`과 동일하게 멱등적으로 동작).

### 변경한 소비처

서블릿 세션 id를 도서관 세션 키로 쓰던 모든 지점을 리졸버로 교체했다:
- `LibrarySessionController` (로그인이 키를 발급 + 쿠키 세팅, 로그아웃이 리졸브 + 무효화 + 쿠키
  클리어)
- `LibraryLoansController` (`GET /api/library/loans`)
- `LibraryReservationWebController` (`recommend`/`prepare`/`confirm`/`wait`류 전부가 쓰는
  `requireLibrarySession`)
- `McpWebSessionController.activeLibrarySessionKey` (도서관 세션이 있을 때만 MCP 세션에 LIBRARY
  provider를 링크)
- `ChatController` — 챗 프라이빗 툴(`get_my_library_loans`, `get_library_seat_status`)이
  `LibraryToolContext` 스레드로컬로 읽는 세션 키도 여기서 채워진다. 이 컨트롤러는 "도서관"
  컨트롤러가 아니지만, 서블릿 세션 id를 도서관 스토어 키로 흘려보내고 있었으므로 리졸버로 갈아
  끼우지 않으면 챗을 통한 도서관 조회가 이번 변경으로 오히려 깨졌을 것이다. 익명 대화 소유자
  isolation(비로그인 사용자의 `conversationId` 범위 격리)은 이 변경과 무관한 별개 관심사라 서블릿
  세션 id를 그대로 둔다.

의도적으로 건드리지 않은 지점: `McpLibraryAuthController`(MCP 클라이언트 전용 콜백 경로)는 이미
자체 `UUID.randomUUID()` 오페이크 키를 발급해 쓰고 있어 서블릿 세션과 무관했다 — 이 ADR의 문제와
관계없다.

## 결과 / 검증

- `./gradlew test` 전체 통과 (신규 리졸버 단위 테스트 + 각 컨트롤러의 쿠키/레거시-세션/무-세션
  3갈래 테스트 포함).
- 리스크: 낮음 — 같은 테이블, 같은 문자열 PK, 스키마 변경 없음. 쿠키를 못 받는 클라이언트가 있어도
  레거시 서블릿 세션 폴백이 한 세대 동안 흡수한다.
- 한계 / 후속 관찰: 레거시 폴백은 무기한이 아니다 — 서블릿 세션에 바인딩된 오래된 로그인은 결국
  자연 만료(TTL 7일)되거나 사용자가 재로그인하며 쿠키 경로로 넘어간다. 폴백 분기 자체를 제거할
  시점은 별도 판단.
