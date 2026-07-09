# ADR 0087 — 공개 REST/SSE 직접 origin 호출과 no-credentials CORS

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-07-10 |
| 상태 | Accepted — 구현 |
| 범위 | 공개 REST CORS, 도서관 좌석 공개 REST/SSE, ssuAI 공개 조회 직접 호출 |
| 연관 ADR | [0012](0012-library-seat-read-only-tool.md), [0023](0023-library-seat-timeseries.md), [0026](0026-sse-seat-updates.md), ssuAI ADR 0087 |

---

## 배경 — C2 문제

ssuAI 브라우저 요청은 대부분 `/api/*` 같은 same-origin 경로로 들어오고, Next.js
rewrite 또는 route handler가 ssuMCP/ssuAgent로 전달했다. 이 구조는 쿠키·JWT·서버
전용 키를 숨기는 데는 맞지만, 로그인 없는 공개 조회까지 Vercel Function 경로를 타게
만들었다.

특히 홈 화면의 도서관 좌석 SSE는 기본으로 열리고, 같은 브라우저 안에서 floor별
연결을 유지한다. 사용자가 늘면 공개 SSE 연결이 Vercel Function 동시성·duration을
계속 점유한다. 챗봇 stream route도 `maxDuration = 60`이라 긴 응답은 Hobby 한도에서
절단될 수 있지만, 이 경로는 서버 전용 agent key와 MCP 세션 경계를 포함하므로 이번
유닛에서는 프록시에 남긴다.

공개 조회는 브라우저 자격증명이 필요 없다. 따라서 공개 REST와 공개 좌석 SSE만
backend origin(`https://ssumcp.duckdns.org`)으로 직접 보내고, 인증·변경·세션 경로는
same-origin 프록시를 유지한다.

참고한 공식 문서:

- Vercel Functions duration은 플랫폼이 invocation을 종료할 수 있는 경계이므로 긴
  stream을 함수 경로에 오래 묶어두는 구조 자체가 비용·절단 리스크가 된다:
  <https://vercel.com/docs/functions/configuring-functions/duration>
- Spring MVC CORS 문서는 `allowCredentials`가 민감한 쿠키/CSRF 정보를 노출하는 신뢰
  수준이므로 필요한 곳에만 켜야 한다고 설명한다:
  <https://docs.spring.io/spring-framework/reference/web/webmvc-cors.html>
- EventSource의 `withCredentials` 기본값은 `false`다. 공개 SSE는 이 기본 경계에 맞춰
  credentials를 보내지 않는다:
  <https://developer.mozilla.org/en-US/docs/Web/API/EventSource/withCredentials>

## 검토한 대안

### 1. 전부 프록시 유지

인증·공개 경계가 단순하고 기존 CORS를 건드리지 않아도 된다. 하지만 공개 SSE와 공개
GET이 계속 Vercel Function 자원을 점유한다. 문제의 원인(C2)을 그대로 남기므로
기각했다.

### 2. 공개 REST + 공개 좌석 SSE만 직접 origin 호출

로그인 없는 읽기만 backend origin으로 보내고, 쿠키/JWT/agent key가 필요한 경로는
same-origin 프록시를 유지한다. CORS는 공개 GET/SSE endpoint에만 열고
`allowCredentials(false)`로 고정한다. 문제 지점을 직접 줄이면서 보안 경계도 작다.
채택했다.

### 3. 전부 직접 origin 호출

Vercel proxy 부하는 가장 크게 줄지만, refresh cookie, library `JSESSIONID`, SmartID/LMS
Bearer, agent key 주입 경계까지 전부 브라우저 cross-origin 정책으로 다시 설계해야 한다.
CSRF·쿠키 속성·프리플라이트 범위가 커지고, 사용자 세션 경로가 공개 CORS 설정과 섞인다.
이번 C2의 범위를 넘어 기각했다.

## 선택

공개 read endpoint에만 CORS를 연다. 허용 endpoint는 ssuAI가 직접 호출하는 공개 표면에
한정한다.

- `GET /api/meals/today`
- `GET /api/meals/weekly`
- `GET /api/dorm/meals/this-week`
- `GET /api/notices`
- `GET /api/library/seats`
- `GET /api/library/seats/events` (`text/event-stream`)
- `GET /api/library/books`
- `GET /api/campus/facilities`
- `GET /api/academic-calendar`

허용 origin은 dev에서 `http://localhost:3000`, `http://127.0.0.1:3000`, prod에서
`SSUAI_FRONTEND_ORIGIN`의 exact origin과 `https://ssuai-*.vercel.app` preview pattern이다.
모든 mapping은 `GET, OPTIONS`, `allowedHeaders("*")`, `allowCredentials(false)`로 고정한다.
`/api/auth/**`, `/api/saint/**`, `/api/lms/**`, `/api/library/loans`,
`/api/library/reservations/**`, `/api/agent/**`, mutation endpoint에는 CORS mapping을 만들지
않는다.

## 구현 선택

- **`ApiCorsDefaults.PUBLIC_READ_MAPPINGS` 배열화**: `/api/**` broad mapping을 제거하고,
  ssuAI가 실제 direct-origin으로 호출하는 endpoint만 코드에 열거했다. 테스트도 같은 목록을
  기준으로 mapping 수와 음성 사례를 검증한다.
- **no credentials 고정**: Spring CORS mapping에서 `allowCredentials(false)`를 명시했다.
  공개 fetch/EventSource는 쿠키가 필요 없고, 쿠키가 필요한 경로는 계속 프록시를 탄다.
- **도서관 좌석 REST는 caller HTTP session을 만들지 않음**: 기존 controller는
  `request.getSession().getId()`를 호출해 공개 조회에서도 `JSESSIONID`를 만들 수 있었다. 새
  REST 공개 경로는 `LibrarySeatService.getPublicSeatStatus()`를 호출한다.
- **real Pyxis 모드의 공개 좌석 조회는 sampler session 재사용**: prod의 aggregate seat
  endpoint가 Pyxis token을 요구할 때, 사용자 세션 대신 ADR 0023의 `internal:seat-sampler`
  service session을 사용한다. 토큰 만료 시 한 번 invalidate + relogin 후 재시도한다. 이
  값은 응답에 노출되지 않고, 예약/대출/현재 좌석 같은 사용자별 경로에는 사용하지 않는다.

## 트레이드오프

- 공개 endpoint의 CORS 표면은 생긴다. 대신 데이터는 로그인 없는 조회 결과이고, credentials가
  꺼져 있어 쿠키 기반 세션과 분리된다.
- Vercel preview를 pattern으로 허용한다. `https://ssuai-*.vercel.app`에만 적용하고,
  공개 read endpoint에만 적용하므로 credentialed preview CORS를 여는 것과 다르다.
- 좌석 공개 REST가 sampler session에 의존한다. sampler credentials가 없거나 login이 실패하면
  real Pyxis 공개 조회도 실패할 수 있다. 하지만 사용자별 library session을 CORS로 열지 않는
  것이 더 중요한 경계다.

## 검증

- `WebCorsConfigTest` / `WebCorsProdConfigTest`
  - 공개 GET preflight가 허용 origin에서 통과하는지 확인
  - `/api/auth/refresh`, `/api/library/loans`, `/api/library/reservations/prepare`에 CORS mapping이
    없는지 확인
  - 공개 mapping의 `allowCredentials`가 false인지 확인
- `LibrarySeatServiceTests`
  - public seat status가 real mode에서 sampler token을 쓰는지 확인
  - sampler token reject 시 invalidate 후 한 번 relogin하는지 확인
- `LibrarySeatControllerTests`
  - REST seat status가 public service method를 호출하는지 확인

## 예상 면접 질문

1. **왜 CORS를 `/api/**`가 아니라 공개 endpoint 목록으로만 열었나?**  
   인증·변경 endpoint는 browser credential이 필요하거나 CSRF/세션 경계가 있으므로 proxy에 남긴다. CORS는 공개 GET/SSE만 해결하면 충분하다.
2. **`allowCredentials(false)`가 왜 중요한가?**  
   공개 CORS 응답에 쿠키 신뢰를 붙이지 않으면, cross-origin 호출이 사용자 세션을 사용하거나 세션 응답을 읽는 경로로 확장되지 않는다.
3. **도서관 좌석 공개 REST에서 사용자 library session 대신 sampler session을 쓰는 이유는?**  
   좌석 집계는 사용자별 데이터가 아니고, prod Pyxis가 token을 요구할 수 있다. 사용자 세션을 CORS로 열지 않고 내부 service session으로 aggregate read만 수행하면 공개 UX와 세션 경계를 동시에 만족한다.
