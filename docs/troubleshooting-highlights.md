# 트러블슈팅 하이라이트

숭실대 캠퍼스 어시스턴트(ssuAI)를 만들며 가장 까다로웠던 디버깅 사례를 추려 정리했다. 스택은 Spring Boot 기반 MCP 서버 + Next.js 프론트엔드 + 에이전트이고, 외부 시스템(u-SAINT SAP 포털, 도서관 Pyxis, LMS)을 역공학해 붙인 구조라 "테스트는 다 통과하는데 prod에서만 깨지는" 종류의 문제가 많았다. 각 사례는 **처음 세운 (그리고 틀린) 가설 → 실제 원인 → 해결** 순서로 적었다. 틀린 가설을 일부러 남긴 이유는, 무엇을 고쳤느냐보다 *어떻게 가설을 세우고 반증해 진짜 원인을 좁혔는지*가 더 중요하다고 보기 때문이다.

---

## 1. 로그인 장애의 진짜 원인 추적 — refresh-token reuse-denylist가 정상 사용자를 401시킴

- **증상 / 배경**: u-SAINT SSO 인증은 성공하는데, 직후 `POST /api/auth/refresh`가 401을 반환해 사용자가 곧바로 로그인 화면으로 튕겼다. 여러 차례 손을 댔지만 prod 로그인이 복구되지 않은, 가장 오래 끈 장애였다.
- **틀린 가설 (3겹)**:
  1. 직전 의존성 bump(Spring Boot 4.1.0)가 Jackson을 2→3(`com.fasterxml` → `tools.jackson`)으로 옮겼고, JWT 라이브러리 `jjwt-jackson`은 Jackson 2에 묶여 있어 `parseSignedClaims()` 역직렬화가 깨졌다 → Boot 4.0.6으로 revert. **그래도 안 됨.**
  2. 그럼 4.0.6에서도 jjwt가 Jackson과 충돌하나 → `jjwt-jackson`을 `jjwt-gson`으로 교체해 JWT를 Jackson과 완전히 디커플(PR #147). **그래도 안 됨.**
  3. "엔드포인트가 깨끗한 401 JSON을 반환하고 로그도 클린"한 것을 복구로 단정 → 실제 브라우저 E2E를 안 한 게 화근.
- **실제 원인**: 사용자 devtools에서 쿠키의 *실제* refresh 토큰을 받아 소거법으로 좁혔다.
  1. 토큰을 prod 시크릿으로 **오프라인 HMAC-SHA256 검증** → 서명이 `base64decode(secret.strip())` 키와 정확히 일치. 즉 서명·키·jjwt는 정상이고, 가설 1·2가 틀렸음을 수학적으로 증명. (부수 발견: prod 시크릿 끝에 Windows artifact `\r`(0x0d)가 있었으나, `.strip()` 하드닝 덕에 서명은 일관되게 통과 중이었다.)
  2. claim도 정상(iss=ssuai, typ=REFRESH, jti 존재, 미만료), `students` 테이블에 학생 row도 존재.
  3. 남은 건 `AuthController.refresh`의 **denylist 검사**뿐 → Redis에 `ssuai:auth:refresh-denylist:<jti>` 키가 수십 개 누적돼 있었다. 원인은 refresh-token rotation의 **reuse-denylist**였다. 토큰을 1회 소비하면 새 토큰을 발급하고 이전 jti를 denylist에 올리는데, (a) rotated 쿠키가 cross-site(Vercel rewrite proxy)에서 기존 쿠키를 안정적으로 대체하지 못하고 (b) `/auth/return` 페이지가 refresh를 1회 이상 호출할 수 있어, 브라우저가 **이미 denied된 옛 토큰을 재전송** → denylist hit → 401.
- **해결**: `AuthController.refresh`에서 reuse-denylist의 검사·등록을 모두 제거(`AuthController.java`, 테스트 `AuthControllerTests.java`는 `refreshAcceptsAReusedRefreshToken`로 전환). rotation 쿠키 set은 유지(붙으면 sliding, 안 붙어도 기존 토큰이 동작). 진단 과정에서 나온 우회 수정(revert `95834be`, PR #147 `1d9f98e`)은 해롭지 않아 남겼지만 진짜 원인은 아니었다.
- **포인트**: 그럴듯한 첫 가설(공급망/프레임워크 회귀)에 매몰되지 않고, 실제 토큰을 받아 **서명검증 → claim검증 → Redis/DB 상태검증** 순으로 소거해 진짜 원인을 좁혔다. "서명이 키와 일치한다"는 한 줄이 두 개의 큰 가설을 동시에 반증했다. 또한 refresh rotation + reuse-detection은 보안엔 좋지만, cross-site 쿠키 rotation이 불안정하면 정상 사용자를 잠근다는 **보안 vs 가용성 트레이드오프** 사례다.
- **예상 면접 질문**:
  - 브라우저가 쿠키를 정상 전송하는데도 refresh가 401이면 어떤 순서로 원인을 좁히나? (오프라인 HMAC으로 서명·키·파서를 먼저 배제)
  - refresh-token rotation의 reuse-denylist가 왜 정상 사용자를 401시킬 수 있나?
  - rotation을 유지하면서 이 문제를 풀려면? (denied 직후 짧은 grace window, 또는 쿠키 rotation 신뢰성 확보)

---

## 2. SSO 콜백 쿠키가 안 붙는 버그 — 네 개 레이어에 걸친 cascade

- **증상 / 배경**: Vercel 프론트(`ssuai.vercel.app`)에서 SmartID 로그인 후 세션이 안 잡혔다. 백엔드는 refresh 쿠키를 내려보내는데 브라우저에 저장되지 않았다. CORS `allowCredentials`는 이미 고쳐둔 상태였다.
- **틀린 가설**: "쿠키가 안 붙는다 = 한 곳의 버그." 실제로는 한 레이어를 고치면 그 아래 레이어가 드러나는 구조라, 단일 원인 가정 자체가 틀렸다.
- **실제 원인 (4 layer)**:
  1. **Cross-origin cookie**: `ssuai.vercel.app` → `ssumcp.duckdns.org` 직접 호출이라 백엔드가 `Set-Cookie`를 내려도 브라우저가 cross-site 쿠키를 Vercel origin에 저장하지 않음.
  2. **SSO callback 302**: 백엔드 콜백이 302 redirect를 반환하는데, same-origin proxy를 거치면서 302 응답의 `Set-Cookie`가 누락됨.
  3. **App Router intercept 순서**: route handler에서 쿠키를 재발급하려 했지만, `afterFiles` rewrite가 App Router route보다 먼저 실행돼 handler가 개입할 수 없었음.
  4. **Next.js silent Set-Cookie strip**: 미들웨어에서 `response.headers.set('Set-Cookie', …)`로 직접 지정한 헤더를 Next.js가 조용히 제거함.
- **해결**: 레이어별로 커밋을 격리해 추적했다 — (1) `next.config.ts`에 `/api/*` rewrite로 모든 호출을 same-origin proxy로 통일, (2) 백엔드 콜백을 302 대신 200 + HTML로 변경, (3) route handler를 `proxy.ts` 미들웨어로 옮겨 서버 사이드에서 쿠키 추출·재발급, (4) `response.headers.set` 대신 `response.cookies.set()` API 사용.
- **포인트**: "쿠키가 안 붙는다"는 단일 증상이 cross-origin / redirect / route intercept order / framework cookie API 네 개의 서로 다른 레이어에 분산돼 있었다. 당시의 1차 패턴은 미들웨어에서 `response.cookies.set()`을 쓰는 것이었지만, 이후 Traefik sticky 쿠키가 섞인 다중 `Set-Cookie` 조건에서 #13의 authorization-code 교환으로 폐기했다.
- **예상 면접 질문**:
  - Next.js App Router에서 Set-Cookie가 조용히 제거되는 상황과 올바른 API는?
  - 같은 증상이 네 개 레이어에 분산됐을 때 어떻게 레이어를 격리해 디버깅하나?
  - cross-site 쿠키를 same-origin proxy로 우회할 때의 트레이드오프는?

---

## 3. 명시 세션을 transport로 덮어쓰던 P0 격리 결함 — 전 도구 HTTP 회귀로 발견·차단

- **증상 / 영향**: 유효한 transport 바인딩이 남아 있을 때, 임의·만료·무효화된 `mcp_session_id`와 다른 유효 세션 ID가 일부 개인 도구에서 transport 세션으로 대체됐다. 그 결과 읽기 응답뿐 아니라 LMS 내보내기 확인처럼 세션 소유 상태를 바꾸는 요청도 다른 세션의 대기 작업에 닿을 수 있었다.
- **근본 원인**: 해소 경로가 도구/도움말마다 달랐고, `McpAuthHelper.buildAuthRequired()`가 일반 도구의 불일치 결과를 인증 재바인딩 문맥으로 다시 해소했다. 즉 명시 인자를 검증한 뒤에도 transport 세션으로 fallback하는 경로가 있었다.
- **해결**: ADR 0098의 단일 `McpSessionResolver`를 권위 있는 진입점으로 만들었다. 명시 ID는 존재·활성·만료 여부를 정확히 검증하고, transport와 다르면 `SESSION_MISMATCH`로 종료한다. 명시 ID가 없을 때만 현재 transport binding을 쓴다. 일반 도구는 재바인딩하지 않으며, 인증 콜백/`start_auth`만 제한적으로 binding을 갱신할 수 있다. 거부 응답은 세션 ID·로그인 URL·개인 데이터를 반환하지 않는다.
- **검증**: 28개 개인 도구를 MCP HTTP 경로로 순회해 random/invalidated/valid-but-different 명시 ID와 transport binding 조합을 회귀 테스트했다. LMS export, 라이브러리 액션·wait intent, capability는 정확한 MCP 세션 소유권을 확인하며, 전체 테스트와 빌드에서 통과했다.
- **포인트**: transport 인증이 존재해도 명시 보안 인자를 무시할 수 없다. 세션 해소는 편의 fallback이 아니라 데이터·액션 소유권 경계이며, 단일 resolver와 전 도구 회귀 검증이 필요하다.

---

## 4. 하이브리드 RAG가 prod에서 조용히 죽어 있던 문제 — quota 오진 → TPM → 잠복 디코딩 버그

- **증상 / 배경**: 헤드라인 기능인 하이브리드 RAG(`search_academic_policy_sources`)가 prod에서 항상 `embeddingUsed:false, fusionMethod:lexical`로 동작했다. 로그는 `216 missing chunk(s); embeddingActive=false`, `academic_embeddings` 테이블은 0행. 벡터 검색이 통째로 휴면 상태였다. 플래그·API 키는 정상이었다.
- **틀린 가설 (3단계에 걸친 오진)**:
  1. "플래그가 꺼졌다" / "모델이 OpenAI-호환 엔드포인트에서 안 뜬다" / "prod egress 차단" → 셋 다 라이브로 배제(configmap `true` 확인, pod에서 직접 curl 단건 임베딩 HTTP 200).
  2. 그래서 처음엔 **"무료 tier의 일일 quota 소진"**으로 진단하고, 모델 교체 + 배치 페이싱(72개씩 65초 간격)으로 "고쳤다"고 기록. 그러나 6일 뒤 실측하니 자율 워밍이 **한 번도 성공하지 않았다.**
- **실제 원인 (2개가 직렬로 잠복)**:
  1. **한계는 요청 수가 아니라 분당 토큰(TPM ≈ 30k)이었다.** 같은 키로 배치 크기·텍스트 압축률을 변수로 분리해 재현했다 — 잘 압축되는 반복문자 96배치는 200, 실제 한글 규정문(700자) 16배치=200 / 48배치=429 / 96배치=429. 즉 개수가 아니라 한글 토큰 총량이 변수였다. "분당 요청 수" 모델로 짠 65초 간격은 무의미했다(요청 1개라도 토큰이 넘으면 429). 게다가 임베딩 클라이언트가 예외를 **클래스명만**(`RestClientException`) 로깅해 진짜 원인(429 quota 본문)이 가려져 있었다(미진단의 직접 원인).
  2. TPM을 풀어 batch=8로 줄이자 처음으로 200을 받았는데, 그러자 **한 번도 실행된 적 없던 성공-경로의 디코딩 버그**가 드러났다. 외부 임베딩 API가 응답 배열 첫 item(index 0)의 `index` 필드를 생략하는데, DTO의 `int index`(primitive)에 null이 매핑되면서 **Jackson 3의 `FAIL_ON_NULL_FOR_PRIMITIVES`가 200 응답을 통째로 폐기**했다. 상류 버그(TPM 429)가 하류 버그(디코딩)를 6주간 가린 셈이다.
- **해결**:
  - 관측성: `AcademicEmbeddingClient`가 `RestClientResponseException` 시 HTTP status + 응답 본문을 로깅하도록 수정.
  - 페이싱: batch-size를 8(≈6k 토큰/요청), interval 15s로 줄여 TPM 아래로. application.yml 기본값과 chart `values.yaml`(prod 오버라이드)을 **둘 다** 수정.
  - 증분 영속: `embed()`가 배치 실패 시 전체 폐기 대신 성공한 prefix를 반환하고 store가 즉시 영속(`PersistentAcademicEmbeddingStore`). misses는 결정적 순서라 다음 refresh가 정확히 이어받음.
  - 디코딩: `record Item(List<Double> embedding, int index)`에서 미사용 `index` 필드를 제거 + 회귀 테스트. 같은 부류 버그를 채팅 레이어 `OpenAiChatCompletionResponse.Choice`에서도 grep으로 선제 발견·수정.
  - **결과(검증 완료)**: 새 pod가 단일 패스로 `academic_embeddings` 0→217 완납, `embeddingUsed:true, fusionMethod:rrf` 관측. 검색 품질도 일반 안내페이지 → 학칙 시행세칙 제48조(복수전공 이수학점) 등 실제 조항 반환으로 개선.
- **포인트**: "tests green but prod broken"의 교과서 사례. 단위 테스트는 모두 통과했지만 prod의 성공-경로가 한 번도 실행된 적 없어 디코딩 버그가 잠복했다. rate-limit을 막연히 "요청 수"로 가정하지 말고 **RPM/TPM/RPD를 실측으로 구분**해야 하며(텍스트 압축률을 변수로 분리), 외부 API DTO는 스펙 문서가 아니라 **실제 응답**으로 검증해야 한다는 점, 그리고 "이미 고쳤다"고 기록된 진단조차 1차 증거로 재검증해야 한다는 교훈을 담았다.
- **예상 면접 질문**:
  - 429를 만났을 때 RPM·TPM·RPD를 어떻게 구분해 특정하나? (단건 200 + 배치 크기·텍스트 압축률을 변수로 분리)
  - 무료 tier에서 대형 코퍼스를 어떻게 끝까지 임베딩하나? (작은 배치 + 증분 영속 + 결정적 순서로 누적)
  - Jackson 3 / Spring Boot 4에서 record DTO의 primitive 필드가 왜 위험한가?

---

## 5. 챗봇이 자기 MCP 서버를 dogfood하게 만들자 터진 부팅 3중 장애

- **증상 / 배경**: 초기엔 챗봇(`LlmChatService`)이 같은 JVM 안의 도구 빈을 일반 Java 메서드로 직접 호출했다. 그러면 "MCP가 메인 deliverable"인데 정작 우리 챗봇은 MCP 표면을 한 번도 안 거치는 비대칭이 생기고, MCP 서버 측 변경이 챗 경로에서 안 잡힌다. 그래서 챗봇이 자기 MCP 서버를 HTTP/SSE로 **self-dogfood**하도록 바꿨다 — MCP 클라이언트 자체를 테스트 클라이언트로 쓰는 결정이었다. 그런데 `chat=llm` + 실제 키로 처음 실서버를 부팅하자 (단위 테스트는 전부 mock이라 통과해왔던 터라) 부팅이 3중으로 깨졌다.
- **틀린 가설**: "단위 테스트 100% 통과 = production 부팅 가능." mock이 가린 의존성 누락이 한 번에 셋이나 드러났다.
- **실제 원인**:
  1. `RestClient.Builder` 빈을 못 찾음 — Spring Boot 4의 autoconfig 재편으로 `spring-boot-starter-web`만으로는 더 이상 기본 등록되지 않음.
  2. `ObjectMapper` 후보 모호 — MCP server가 자기 전용 ObjectMapper를 등록하면서 기본 후보를 가려 `LlmChatService` 생성자가 unresolved.
  3. **chicken-and-egg deadlock** — MCP client 빈이 컨텍스트 refresh 중 동기 init으로 자기 `/sse`에 연결을 시도하는데, 같은 JVM의 Tomcat이 아직 8080에 바인딩 전이라 `ConnectException` → 10초 타임아웃 → 컨텍스트 실패.
- **해결**: (1) `@Bean @ConditionalOnMissingBean RestClient.Builder` 명시, (2) `@Bean @Primary ObjectMapper` 추가, (3) MCP client init을 전부 lazy로 — `spring.ai.mcp.client.initialized: false` + `toolcallback.enabled: false`로 끄고, 첫 chat 요청 시점에 `discoverChatTools()`가 `initialize() + listTools()`를 직접 호출, 생성자의 `List<McpSyncClient>`에 `@Lazy` 주입. 결과 `bootRun` 8.6초에 startup 완료, `POST /api/chat`에 "오늘 학식 뭐야?" → 실제 학식 메뉴 한국어 응답 정상.
- **포인트**: MCP 클라이언트를 dogfood 테스트 클라이언트로 삼은 것은 "MCP tool의 JSON schema가 곧 외부 계약"임을 코드 차원에서 받아들인 결정이다. 동시에 같은 프로세스에서 client가 server를 동기 호출하는 self-dogfood 패턴은 SmartLifecycle 순서를 거스르면 deadlock이 나며, 해법은 init을 모두 lazy로 미루는 것이다. 신버전 의존성 조합(Boot 4 / Spring AI 1.1)은 autoconfig diff가 커서, mock 테스트와 별도로 "실서버 부팅 1회 + 핵심 path smoke"를 강제해야 한다.
- **예상 면접 질문**:
  - Spring AI MCP client가 같은 JVM의 서버에 연결할 때 deadlock이 나는 이유와 `@Lazy`로 푸는 방법은?
  - 챗봇이 자기 MCP 서버를 self-dogfood하게 만든 설계적 이유는? (회귀 조기 검출 + 계약 일치)
  - "단위 테스트 전부 통과 = 부팅 가능"이 아닌 이유를 이 3중 장애로 설명하라.

---

## 6. 문서 없는 캠퍼스 시스템 역공학 — 도서관 Pyxis API와 SAP WebDynpro(u-SAINT)

- **증상 / 배경**: 도서관(Pyxis)과 학사포털(u-SAINT)에는 공개 API 문서가 없었다. 좌석·대출·시간표·성적을 붙이려면 브라우저 DevTools로 실제 wire를 캡처해 스펙을 역공학해야 했다.
- **틀린 가설**: ① "문서가 없으면 못 한다"가 아니라 브라우저가 곧 API 문서. ② SAP 시간표 connector의 단위 테스트(렌더된 HTML fixture)는 전부 통과했으니 prod에서도 동작할 것이다 → 틀림. ③ u-SAINT가 "데이터 없음"을 반환하면 로그인 실패다 → 틀림.
- **실제 원인 / 발견**:
  - **Pyxis (도서관)**: 좌석 현황은 쿠키가 아니라 **`Pyxis-Auth-Token` 헤더** 인증(세션 무관 공개 토큰)이고, 대출 현황은 로그인 세션 쿠키 기반으로, 같은 도메인에서 **두 인증 방식이 공존**했다. 실제 path(`/pyxis-api/1/api/charges`)와 응답 필드(`biblio.titleStatement`, `callNo`, `chargeDate`, `dueDate`)를 캡처로 맞춰야 정상 파싱됐고, 미로그인은 `noRecord`(빈 배열) vs `needLogin`(`LibraryAuthRequiredException`)으로 분기해야 했다.
  - **SAP WebDynpro (u-SAINT)**: Chrome-like User-Agent로 GET하면 렌더된 HTML이 아니라 **JS bootstrap 페이지**가 먼저 내려온다. 사람 브라우저는 JS가 `Form_Request` POST를 자동 전송해 실제 HTML을 받지만 connector는 JS를 실행하지 않아 `sap-wd-secure-id` 파싱에 실패했다. 즉 **외부 서버가 User-Agent에 따라 응답 종류 자체를 바꾸므로** HTML fixture 테스트는 prod를 보장하지 못했다. 또 stateful UI라 "데이터 컨테이너 부재"가 인증 실패 신호가 아니었고(빈 학기일 뿐), `학년도/학기` dropdown 존재를 인증 신호로 삼아야 했다.
- **해결**:
  - Pyxis: DevTools에서 요청 캡처 → 헤더/path/body 재현 → 응답 필드를 DTO로 직접 매핑(`RealLibrarySeatConnector`, `RealLibraryLoansConnector`, MockRestServiceServer fixture 13케이스).
  - SAP: bootstrap HTML에서 `sap-wd-secure-id`를 추출해 `Form_Request`(`SAPEVENTQUEUE`) POST를 명시 전송하는 2단계 init 추가(`WebDynproSapEventEncoder`/`WebDynproResponseUnwrapper`로 분리해 테스트 가능화). 인증 신호는 데이터 row가 아니라 dropdown/GPA history 구조로 분리.
  - **중단 기준과 피벗**: SAP WebDynpro를 Java로 직접 살리려 했지만, wire-level ground truth 없이 protocol을 추측한 fix가 같은 실패 계열로 반복됐다. LMS 같은 단순 SSO는 직접 구현하되, `sap-contextid`/`sap-ext-sid`/SAPEVENTQUEUE가 stateful하게 엮인 SAP는 역공학 비용이 제품 가치를 넘었다고 판단해, 검증된 Rust upstream `EATSTEAK/rusaint`를 UniFFI Kotlin binding으로 통합하는 쪽으로 피벗했다. SmartID callback의 `sToken`/`sIdno`는 rusaint `withToken`에 한 번만 넘기고, 결과 세션은 기존 `SaintSessionStore`에 AES-GCM으로 암호화 저장했다.
- **포인트**: 문서 없는 내부 API를 역공학하는 표준 절차(DevTools 캡처 → 재현 → DTO 매핑)와, 헤더 인증 vs 쿠키 인증이 한 도메인에서 공존하는 구조를 이해한 사례다. 무엇보다 "무한 추측 fix"를 일정 시점에 끊고 **직접 구현 vs 검증된 upstream**을 적재적소로 선택한 엔지니어링 판단이 핵심이다 — 그리고 실패한 Java 시도를 silent rewrite하지 않고 기록으로 남겼다.
- **예상 면접 질문**:
  - 문서 없는 내부 시스템의 endpoint를 역공학하는 구체적 방법은?
  - 직접 구현 vs 검증된 upstream 라이브러리 활용을 결정하는 기준은? ("무한 추측 fix" 중단 기준)
  - stateful WebDynpro에서 "데이터 없음"과 "인증 실패"를 어떤 신호로 구분하나?

---

## 7. 타임아웃을 "실패"로 단정해 감사 로그가 영구히 어긋난 이중 상태 사고

- **증상 / 배경**: 좌석 예약(intent 큐 경로)에서 confirm 컨트롤러는 worker 결과를 8초만 동기 대기한 뒤, 타임아웃이면 `ActionAudit`를 **종단(FAILED/TIMEOUT)** 으로 마킹했다. 그런데 예약 worker는 계속 돌아 실제로 좌석을 잡을 수 있었고(성공), 아무도 그 audit를 SUCCESS로 갱신하지 않았다. 결과: **API는 "실패"라 응답하는데 좌석은 실제로 예약되고, 감사 로그는 영구히 틀린** 금전 거래급 이중 상태(double-state) 사고.
- **틀린 가설**: "동기 대기 타임아웃은 곧 비즈니스 실패다." 실제로 타임아웃은 단지 *응답 상태*일 뿐이고, 비동기 worker가 종단 결과의 단일 진실원천이어야 한다.
- **실제 원인**: 종단 outcome을 쓰는 주체가 **둘**이었다 — (1) 동기 confirm 경로가 타임아웃 시점에 `completeAction()` 호출, (2) worker가 intent를 종단. 둘 사이에 동기화가 없어, 동기 경로가 먼저 FAILED로 닫은 뒤 worker가 성공해도 audit는 FAILED로 굳었다.
- **해결** (surgical + additive):
  - **타임아웃은 종단이 아니다**: confirm 경로를 observe-only로 전환 — 예약 경로에서는 audit를 절대 쓰지 않고, 타임아웃 시 audit를 `EXECUTING`에 남긴 채 비종단 status `PROCESSING`만 반환.
  - **worker가 단일 진실원천**: `LibraryReservationIntentTransactions`의 모든 종단 전이(`succeed`/`failRace`/`failAuth`/`failUpstream`)에서 intent 락과 **같은 트랜잭션** 안에 `ActionService.finalizeFromIntent(...)`를 호출해 좌석 상태와 audit 완료를 원자적으로 커밋.
  - **멱등성**: `finalizeFromIntent`는 row 없음·이미 종단·아직 PENDING이면 no-op, 오직 `EXECUTING`만 1회 완료 → 두 번째 finalize와 동기 경로가 먼저 닫은 경우 모두 안전.
  - **누락 전이 보강**: `expireWaiting`/`cancelActive`가 in-flight 즉시예약 intent를 EXPIRED/CANCELLED시킬 때 연결 audit가 stranded되지 않도록 전수 점검. (신규 `ActionService.finalizeFromIntent`.)
- **포인트**: "타임아웃 = 실패"라는 흔한 단정이 비동기 워커 환경에서 어떻게 이중 상태 사고로 이어지는지 짚고, **단일 진실원천 + 동일 트랜잭션 원자 finalize + 멱등성**으로 푼 분산-일관성 설계다. 수정이 만들 수 있는 회귀(cancel/expire 경로의 audit stranding)까지 종단 전이 전수 점검으로 선제 차단했다.
- **예상 면접 질문**:
  - 동기 응답과 비동기 워커가 같은 상태를 쓸 때 이중 기록을 어떻게 구조적으로 제거하나? 왜 "단일 진실원천 + 동일 트랜잭션 finalize"인가?
  - `finalizeFromIntent`의 멱등성은 어떤 상태에서 no-op이며, 그 가드가 없으면 어떤 레이스로 audit가 뒤집히나?
  - 타임아웃 audit를 EXECUTING에 남기는 선택의 트레이드오프와, 그 누수를 어떻게 닫나?

---

## 8. 고수준 HTTP 클라이언트의 "투명한 redirect"가 SAP 세션 쿠키를 삼킨 문제

- **증상 / 배경**: u-SAINT portal phase 2에서 SAP ECC connector가 시간표/성적 조회 때만 계속 403을 반환했다. SmartID 로그인 자체는 성공하고 portal HTML도 정상 파싱되는데, 그 이후 단계에서만 403이 떨어져 원인 위치 특정이 어려웠다.
- **틀린 가설**: 처음엔 단순히 "MYSAPSSO2 쿠키 자체가 잘못됐다"고 봤다. 단계별 진단 로깅을 붙이고 나서야 *어떤* MYSAPSSO2가 문제인지가 드러났다.
- **실제 원인**: 저장된 MYSAPSSO2는 portal phase 1에서 발급된 옛 토큰이고, phase 2 redirect 체인에서 SAP이 새로 발급한 갱신 토큰과 달랐다. 원인은 Spring `RestClient`의 기본 `SimpleClientHttpRequestFactory`(내부적으로 `HttpURLConnection`)가 3xx redirect를 조용히 따라가면서 **중간 응답의 Set-Cookie 헤더를 전부 버린다**는 것. SAP portal phase 2는 첫 302 응답에 권위 있는 최신 MYSAPSSO2를 실어 보내는데, 최종 목적지 응답만 보는 RestClient가 그 쿠키를 수집하지 못해 phase 1 값을 계속 들고 다녔고, ECC가 오래된 토큰을 실어 보내니 매 요청 403이었다.
- **해결**: phase 2 fetch를 `java.net.http.HttpClient(Redirect.NEVER)` + 수동 redirect 추적으로 교체. 각 hop의 Set-Cookie를 누적해 저장된 `PortalCookies`에 merge하고, 충돌 시 phase 2 값이 phase 1 값을 덮어쓰도록 보장. MockWebServer 기반 "302 hop → 200 최종" 시나리오에서 중간 Set-Cookie가 최종 저장 쿠키에 반영되는지 테스트로 고정했다.
- **포인트**: HTTP 클라이언트의 "투명한 redirect 추적"은 *쿠키 수집* 관점에선 오히려 불투명하다. 최종 응답에만 집중하는 고수준 클라이언트는 redirect 체인 중간에서 세션을 발급하는 서버(SAP NetWeaver 패턴) 앞에서 silent mismatch를 만든다. 쿠키를 누적해야 하는 multi-hop 흐름에서는 `Redirect.NEVER` + 수동 추적이 사실상 유일한 안전 선택이다.
- **예상 면접 질문**:
  - HTTP 클라이언트의 "투명한 redirect 추적"이 쿠키 수집 관점에서 불투명한 이유는?
  - `Redirect.NEVER` + 수동 추적이 필요한 경우와 자동 redirect가 안전한 경우를 어떻게 구분하나?
  - 증상(ECC 403)이 실제 원인(phase 2 쿠키 누락)과 멀리 떨어져 있을 때 어떻게 범위를 좁혔나?

---

## 9. 로컬에선 되던 관측성 스택이 prod k3s에서 침묵 — Boot 4 OTLP autoconfig 이관 + 배포 함정

- **증상 / 배경**: 3-pillars(메트릭·트레이스·로그)를 로컬 docker compose에선 전부 증명해 뒀는데, prod k3s에 켜니 **메트릭만 되고 트레이스·로그가 안 떴다**. 특히 트레이스는 Tempo span 0인데 백엔드 로그에 exporter 오류·초기화 흔적이 **하나도** 없어(오류조차 없는 침묵) 원인 위치 특정이 가장 어려웠다.
- **틀린 가설 (3겹, 트레이스)**:
  1. 백엔드(ns `ssuai-prod`)와 Tempo(ns `monitoring`)가 갈라져 있으니 **크로스-네임스페이스 DNS 실패**일 것. → 틀림. chart가 이미 FQDN(`tempo.monitoring.svc.cluster.local`)을 주입했고 pod env에서도 확인됨.
  2. **의존성 누락**(`micrometer-tracing-bridge-otel`/`opentelemetry-exporter-otlp`). → 틀림. 둘 다 존재.
  3. exporter가 **연결 실패로 조용히 재시도** 중. → 틀림. 그러면 반복 오류 로그가 남아야 하는데 흔적 0 = exporter가 애초에 생성 안 됨.
- **실제 원인 (2단계)**:
  1. **프로퍼티 키 rename** — Spring Boot 4가 OTLP 트레이스 키를 바꿨다. Boot 3 `management.otlp.tracing.endpoint`는 Boot 4에서 **조용히 무시**(바인딩 오류도 없음)되고 정식 키는 `management.opentelemetry.tracing.export.otlp.endpoint`. 고쳤지만 여전히 span 0.
  2. **autoconfig 모듈 이관(진짜 원인)** — Boot 4가 OTLP tracing auto-configuration을 새 **`spring-boot-starter-opentelemetry`**(내부 `spring-boot-opentelemetry` 모듈)로 옮겼다. 우리가 쓰던 Boot 3식 저수준 의존성만으론 **autoconfig glue가 없어 exporter/tracer가 생성조차 안 됐다**(span 0 + 로그 0의 정체). `gradle dependencies`로 런타임 클래스패스를 확인해 `spring-boot-opentelemetry` 모듈 부재를 입증 → 스타터로 교체해 해결. (부작용: 스타터가 데려온 `micrometer-registry-otlp`가 OTLP 메트릭을 localhost로 자동 push하려 함 → `management.otlp.metrics.export.enabled=false`로 차단.)
- **로그 함정 (별도 2건)**: Loki `reject_old_samples`가 기본 on이라 첫 배포 백로그 로그를 400으로 거부(→ `false`) / k3s는 컨테이너 로그를 `/var/lib/rancher/k3s`에 두고 `/var/log/pods`는 그 심링크라, 그 트리를 promtail에 마운트 안 하면 링크가 파일시스템 밖으로 해석돼 아무것도 못 읽음(→ hostPath 마운트).
- **검증 함정**: loki/tempo/promtail은 **distroless라 `wget`/`sh`가 없어** `kubectl exec ... wget` probe가 항상 빈 결과(false-negative). 노드에서 `curl`→ClusterIP로 쳐야 실제 상태가 보였다. 이 함정 때문에 한동안 "로그도 안 된다"고 오판했으나, 노드 curl로 보니 Loki엔 이미 수천 라인이 쌓여 있었다.
- **해결·검증**: application.yml 프로퍼티 + build.gradle 스타터 교체 + 로그 파이프라인 2건 수정. 배포 후 트래픽을 흘려 Tempo `tempo_distributor_push_duration_seconds_count>0`, TraceQL `service.name=ssuai`로 실제 trace(root span `http get /api/meals/today` 등) 확인. **3-pillars 전부 prod 라이브.**
- **포인트**: "로컬 OK, prod NG"는 거의 항상 런타임 환경 차이다. 프레임워크 메이저 업그레이드(Boot 3→4)에서 **설정 키 rename + autoconfig 모듈 이관**이라는 두 겹의 조용한 회귀를, 로그·설정으로 안 잡히자 **의존성 그래프까지 내려가** 규명한 게 핵심. "전송 실패(반복 오류 로그)"와 "미생성(흔적 0)"을 구분한 것이 방향을 갈랐다.
- **예상 면접 질문**:
  - 트레이스 span이 0인데 오류 로그조차 없다. "전송 실패"와 "exporter 미생성"을 어떻게 구분하고, 후자면 어디를 보나? (→ `gradle dependencies`로 autoconfig 모듈 존재 확인)
  - Spring Boot 메이저 업그레이드에서 기능이 조용히 사라질 때 방어법은? (properties-migrator, 릴리스 노트 마이그레이션 표, 스타터 vs 저수준 라이브러리, span export 스모크 테스트)
  - 왜 저수준 라이브러리 대신 Boot 스타터를 써야 했나? (스타터 = 라이브러리 + autoconfiguration + 기본값; Boot 4에서 OTLP autoconfig가 스타터로 이관)

---

## 10. permitAll인데 401 — MCP OAuth 필터가 웹 세션 JWT를 가로챈 prod 전용 로그인 장애

- **증상 / 배경**: 웹 프론트에서 u-SAINT SmartID SSO 로그인이 전면 실패했다. SmartID 인증·콜백·refresh 쿠키 발급·`POST /api/auth/refresh`(200)까지 전부 정상인데, 직후 `GET /api/auth/me`가 401을 반환해 세션 수립이 끝내 실패했다. 테스트 스위트는 전부 그린이었고 prod에서만 재현됐다.
- **틀린 가설**: ① 학교 SmartID 페이지 측 변경 ② 직전 UI 리디자인의 프론트 회귀. 실계정으로 SSO 체인을 curl로 단계별 재현(로그인 POST → sToken → 콜백 → 쿠키 → refresh)해 모두 정상임을 확인, 두 가설을 소거했다.
- **실제 원인**: 401 응답의 `WWW-Authenticate: Bearer realm="ssuMCP", resource_metadata="…"` 헤더가 결정적 단서였다 — 이 challenge는 웹 인증이 아니라 **MCP OAuth 리소스 서버**의 것이다. OAuth용 `SecurityFilterChain`이 경로 스코프 없이 전체 요청을 매칭하고 있었고, prod에서만 리소스 서버 플래그가 켜져(`rs-enabled=true`, ChatGPT 연동용) `BearerTokenAuthenticationFilter`가 등록됐다. 이 필터는 *인증* 필터라서 `permitAll()`(*인가* 규칙)과 무관하게 Bearer 헤더가 보이면 무조건 Auth0 디코더로 검증한다 — 자체 HS256 웹 세션 JWT는 당연히 "invalid token" 401. 모든 테스트는 플래그 기본값(off)으로 부팅되어 이 필터 자체가 존재하지 않았다.
- **해결**: OAuth 체인을 `securityMatcher("/mcp", "/mcp/**", "/.well-known/**")`로 MCP 표면에만 스코프하고, 나머지 경로는 permissive 체인으로 분리(ADR 0074). `/.well-known`을 OAuth 체인에 남긴 것이 함정 포인트 — RFC 9728 PRM 문서를 서빙하는 필터가 그 체인 안에 등록되므로, 빼면 MCP 클라이언트의 AS 디스커버리가 깨진다. 회귀 가드로 WireMock OIDC를 띄워 **prod와 동일한 플래그 조합(rs-enabled=true)으로 부팅하는 통합 테스트**를 추가했다.
- **포인트**: "permitAll인데 401"은 Spring Security의 인증/인가 계층 분리를 정확히 보여주는 사례 — 인가 규칙으로 인증 필터를 우회할 수 없다. 그리고 기능 플래그 시스템의 사각지대: 테스트가 아무리 많아도 전부 기본값으로 돌면 prod 전용 조합은 0% 커버다. 최소 1개는 prod-equivalent 구성으로 부팅해야 한다.
- **예상 면접 질문**:
  - `permitAll()`을 걸었는데 401이 나는 게 어떻게 가능한가? (인증 필터 vs 인가 규칙의 실행 계층)
  - 테스트는 전부 통과하는데 prod만 깨지는 구성 의존 장애를 구조적으로 막으려면?
  - OAuth 리소스 서버와 자체 세션 JWT가 한 앱에 공존할 때 경계 설계는? (securityMatcher 멀티체인 vs 토큰 스니핑 resolver)

---

## 11. 로그인이 첫 시도만 실패 — 콜드 FFI 네이티브 로드가 일회성 토큰을 태웠다

- **증상 / 배경**: u-SAINT 로그인이 콜드 pod에서 첫 시도만 실패하고, 같은 화면에서 곧바로 재로그인하면 성공했다. prod 로그에는 pod당 성공한 세션 저장이 처음엔 딱 1회(재시도분)만 남고, 첫 시도는 그 전에 죽었다.
- **틀린 가설**: 프론트 `/auth/return`의 이중 refresh 레이스, 또는 refresh reuse-denylist가 재사용 토큰을 401시키는 것. 둘 다 반증 — denylist는 이미 제거됐고 이중 refresh는 둘 다 성공하며, 실패 지점이 프론트 refresh가 아니라 **백엔드 SSO 콜백 자체**(세션 저장 이전)였다.
- **실제 원인**: 콜백은 `USaintSessionBuilder().withToken(sIdno, sToken)`으로 **일회성 SmartID 토큰을 소비**해 SAP WebDynpro 세션을 맺는다. 이후 학생정보 조회는 이미 3회 재시도로 감싸져 있었지만 `withToken()` 자체는 재시도가 없고 — 일회성 토큰이라 **재시도가 원천적으로 불가능**하다. 첫 호출이 실패한 진짜 이유는 인증 로직이 아니라 **콜드 JVM의 첫 FFI 호출 비용**이었다: UniFFI 네이티브 라이브러리(`librusaint_ffi.so`)를 dlopen하고 라이브러리 contract 버전과 수백 개 익스포트 함수의 checksum을 런타임 검증하는 일회성 작업이, 콜드 SAP 핸드셰이크 위에 스택되면서 간헐적으로 토큰 유효 창을 넘겼다. 두 번째 로그인은 네이티브가 이미 웜이라 성공했다.
- **해결**: 재시도할 수 없는 실패는 재시도로 못 고친다 — 대신 **원인이 되는 콜드 비용을 요청 경로에서 제거**했다. 부팅 직후 백그라운드 데몬 스레드에서 세션 빌더를 한 번 생성·해제해(네트워크 없이 네이티브 핸들만 할당·반납) FFI 로드와 checksum 검증을 미리 끝낸다. 실제 로그인이 도착할 즈음엔 항상 웜이라 토큰 소비 단계가 핸드셰이크 비용만 낸다. 비동기·fail-safe·`@Profile("!test")`로 설계해 readiness를 지연시키지 않고 워밍업 실패가 기동을 막지도 않는다(ADR 0076).
- **포인트**: "재시도 불가능한 실패(일회성 토큰)를 어떻게 안정화하나"에 대한 답 — 실패를 유발하는 **비용의 시점을 요청에서 부팅으로 옮긴다**. 그럴듯한 프론트 레이스 가설을 로그 상관으로 반증하고 저수준 FFI 첫-호출 비용까지 원인을 좁힌 진단 과정이 핵심.
- **예상 면접 질문**:
  - 일회성 토큰이라 재시도가 불가능한 로그인 실패를 어떻게 근본 수정했나?
  - 워밍업을 동기 startup-블록이 아니라 비동기 데몬 스레드로 둔 이유는? (readiness·기동 안전)
  - UniFFI/JNA 첫 호출이 왜 비싼가, 그게 왜 로그인 실패로 이어졌나?

## 12. Kafka 이벤트 버스로 graduate하며 겪은 "무중단 crash-loop" — 슬라이스 테스트가 feature flag의 ON 경로를 구조적으로 못 잡았다

- **증상 / 배경**: 예약 알림(intent-status)의 크로스-포드 fan-out을 Redis RTopic에서 Kafka로 승격했다. 라이브 예약 경로를 건드리는 위험을 줄이려 **별도 feature flag(기본 off)** 뒤에 넣었고, 코드는 CI·전체 테스트 스위트 green으로 머지·배포됐다(플래그 off라 런타임 영향 0). 그런데 프로드에서 플래그를 켜는 순간 새 파드가 `APPLICATION FAILED TO START`로 CrashLoopBackOff에 빠졌다.
- **틀린 가설**: 이번엔 없었다 — 파드 로그가 즉시 정답(`UnsatisfiedDependencyException: expected single matching bean but found 2`)을 줬다. 진짜 질문은 "왜 테스트가 이걸 통과시켰나"였다.
- **실제 원인**: 플래그가 켜지면 버스 인터페이스 타입의 빈이 **두 개** 생긴다 — 정식 팩토리 빈과 그 delegate인 Kafka 구현체. 단일 인자로 이 타입을 주입받는 소비자(SSE 레지스트리)가 모호성으로 주입 실패 → 컨텍스트 기동 실패. 핵심은 **테스트가 이걸 구조적으로 못 잡은 이유**다: 이 기능의 회귀 통합테스트를 `@SpringBootTest(classes = {KafkaConfig, ...})`처럼 **명시적 슬라이스**로 짰는데, 두 번째 빈을 만드는 config를 그 슬라이스에 안 넣었다 — 즉 두 빈 생산자가 **한 컨텍스트에 공존한 적이 없어** 모호성이 발현될 수 없었다. 전체 앱 컨텍스트 로드 테스트는 플래그 기본값(off)이라 Kafka 빈 자체가 안 생겼다. 결국 **"플래그 ON × 전체 컨텍스트" 조합을 태운 테스트가 0개**였다.
- **해결**: ① 정식 빈에 `@Primary`를 붙여 소비자가 항상 그걸 고르게 함(Kafka 구현체는 그 delegate). ② **전체 애플리케이션 컨텍스트를 플래그 ON + 임베디드 브로커로 부팅하는 회귀 테스트**를 추가하고, `@Primary`를 뺐을 때 그 테스트가 **동일한 예외로 실제 FAIL함을 확인한 뒤** 되돌려 "항상 통과하는 무의미한 가드"가 아님을 보장했다. ③ 사고 대응은 무중단: 롤링 전략이 `maxUnavailable:0`이라 새 파드가 Ready 되기 전엔 구 파드가 안 죽어, crash-loop 내내 트래픽은 구 파드가 200으로 서빙했다. 플래그를 off로 되돌려(코드 재배포 없이) 구 상태로 복귀한 뒤, 수정 이미지가 라이브가 된 것을 확인하고 재-cutover → 파드당 유일 consumer group·fail-open 드릴(브로커 kill 중 health 200 유지)까지 실측.
- **포인트**: "CI green = 안전"이 아니라 **"테스트가 태우는 컨텍스트가 prod 배선과 같아야" 유효**하다는 사례. 빈 모호성은 두 config가 같은 컨텍스트에 있을 때만 터지는데, 속도를 위해 좁힌 슬라이스가 바로 그 조건을 갈라놨다. 또 feature flag는 **기본값(off)만이 아니라 ON 경로도 CI에서 태워야** 한다. 무중단 배포 전략(`maxUnavailable:0`)과 fail-open 설계가 prod 사고를 사용자 영향 0으로 흡수한 것도 방어의 성과다. (같은 "기능 플래그 사각지대" 계열인 10번과 짝을 이루지만, 여기선 원인이 "플래그 조합 미검증"을 넘어 **테스트 슬라이싱이 결함의 발현 조건 자체를 제거**했다는 점이 다르다.)
- **예상 면접 질문**:
  - 전체 스위트가 green인데 왜 prod가 죽었나? (슬라이스 `@SpringBootTest(classes=...)`가 두 빈 생산자를 한 컨텍스트에 안 올림 + 전체 컨텍스트 테스트는 플래그 off)
  - `@Primary`와 `@Qualifier` 중 왜 Primary인가? (소비자가 여럿이고 모두 정식 빈을 원함 — Primary 하나로 전부 해소, Qualifier는 소비자마다 수정)
  - 회귀 테스트가 진짜 그 버그를 잡는지 어떻게 보장했나? (`@Primary`를 뺐을 때 동일 예외로 FAIL함을 실증한 뒤 되돌림 — "항상 통과하는 테스트" 배제)
  - crash-loop인데 어떻게 무중단이었나? (`maxUnavailable:0` — 새 파드가 Ready 전엔 구 파드 유지, 트래픽은 계속 구 파드가 서빙)

---

## 13. SSO 콜백 쿠키가 브라우저에 안 심긴다

- **증상 / 배경**: SmartID SSO 콜백은 성공하고 서버도 refresh 세션을 만들었는데, 브라우저에는 refresh 쿠키가 계속 남지 않았다. 앞선 Fix A(콜백 200+JS + 쿠키 재발급)로도 필드 실패가 반복됐다.
- **틀린 가설 (2개)**:
  1. CDN rewrite 프록시가 redirect/캐시 응답에서 `Set-Cookie`를 strip한다 → 모든 콜백을 200+JS로 통일해도 실패해 소거.
  2. 브라우저 3rd-party 쿠키 차단이다 → same-origin `/api/*` POST 쿠키는 정상 저장되고, 실패 응답에 Traefik affinity 쿠키가 중복 생성되는 지문이 보여 소거.
- **실제 원인**: 프론트의 콜백 가로채기 미들웨어가 진범이었다. `Headers.get("set-cookie")`는 다중 `Set-Cookie`를 `", "`로 join하는데, 인그레스(Traefik)가 sticky 쿠키를 항상 덧붙였다. 미들웨어의 naive `parts[0]` 파싱이 refresh가 아니라 엉뚱한 쿠키를 재발급했고, 브라우저에 affinity 쿠키가 2개 생기는 현상으로 확정했다.
- **해결**: 쿠키 릴레이 자체를 설계에서 제거했다. 콜백은 Redis 1회용 code만 URL로 넘긴다(TTL 120s, 단일 사용, fail-closed, 모든 콜백 응답 200+JS 통일). 실제 쿠키 전달은 same-origin `POST /api/auth/exchange`의 비리다이렉트 200 응답에서만 한다(ADR 0095).
- **포인트**: 다중 `Set-Cookie`는 comma split 대상이 아니다(`getSetCookie()`가 필요한 이유). "프록시가 범인"까지 맞아도 **어느 프록시인지** 실측해야 한다. authorization-code 교환은 콜백과 쿠키 전달을 분리해, 중간자 rewrite/redirect/cache/header 변환에 로그인 성공 여부를 걸지 않는다.
- **예상 면접 질문**:
  - `Headers.get("set-cookie")`로 다중 쿠키를 읽으면 왜 깨지고, `getSetCookie()`가 필요한 이유는?
  - 프록시/CDN 쿠키 문제에서 "어느 프록시인지"를 어떻게 실측으로 가르나?
  - SSO 콜백 직접 쿠키 발급보다 authorization-code 교환이 프록시 뒤에서 더 안정적인 이유는?

---

## 14. 승인 버튼을 눌러도 아무 일도 안 일어난다

- **증상 / 배경**: HITL 예약 카드에서 승인 버튼을 눌러도 로그·DB·화면이 모두 무음이었다. 카드 표시까지는 정상이라 처음엔 승인 API나 예약 worker만 의심하기 쉬운 형태였다.
- **틀린 가설**: "승인 버튼 이후 한 곳만 막혔다." 실제로는 interrupt 생성, resume, SSE 표시가 각각 다른 이유로 끊긴 3중 잠복 버그였다.
- **실제 원인 (3겹)**:
  1. MCP 어댑터가 툴 결과를 content-block 리스트로 반환했는데, interrupt 조건은 dict shape만 검사했다. 조건이 영원히 False라 interrupt가 발화하지 않았다.
  2. resume 직전 `update_state`가 체크포인트를 포크해 pending interrupt를 무효화했다. `Command(resume=..., update=...)` 원자 재개로 바꿔 LangGraph 내부의 fork write와 `NULL_TASK_ID` write 차이를 제거했다.
  3. 승인 노드의 응답을 SSE로 흘리지 않는 스트림 필터가 마지막 화면 갱신을 막고 있었다.
- **해결**: 픽스처를 실제 wire shape(content-block 리스트)으로 바꾸고, interrupt 스레드에서는 `update_state`를 금지했다. 승인에 필요한 상태는 `Command(resume=..., update=...)` 또는 resume payload로 원자 전달하고, 승인 노드 출력도 SSE 이벤트로 내보냈다(ssuAgent ADR 0016/0017).
- **포인트**: 셋 다 "저비용 모델은 그 경로에 도달 못 함"이라는 같은 이유로 숨어 있다가, 더 강한 추론 경로가 들어오자 층층이 노출됐다. HITL E2E는 카드 표시가 아니라 **승인 이후 실제 실행과 스트림 표시까지** 검증해야 한다.
- **예상 면접 질문**:
  - LangGraph interrupt 테스트에서 왜 실제 MCP wire shape fixture가 중요한가?
  - resume 직전 `update_state`가 pending interrupt를 무효화하는 이유는?
  - HITL E2E의 완료 기준을 "카드 표시"가 아니라 "승인 이후"로 잡아야 하는 이유는?

---

## 15. 재배포할 때마다 도서관 로그인이 풀린다

- **증상 / 배경**: 도서관 로그인 직후에는 대출·예약 기능이 정상인데, 롤링 재배포나 파드 전환 뒤에는 다시 401이 났다. Pyxis 토큰 자체는 Postgres에 7일 TTL로 남아 있었다.
- **틀린 가설**: ① Pyxis 토큰이 짧게 만료된다 ② Traefik sticky만 있으면 충분하다. 토큰 row가 살아 있고, sticky는 파드가 재시작되면 지킬 대상이 사라지므로 둘 다 원인이 아니었다.
- **실제 원인**: 저장소 값은 영속이었지만 **키**가 Tomcat 인메모리 서블릿 세션 id였다. 새 파드에는 예전 `JSESSIONID`에 해당하는 세션 객체가 없고, `getSession()`은 조용히 새 id를 만들었다. 결과적으로 DB row는 살아 있는데 조회 키만 증발해 401이 났다.
- **해결**: 로그인 시 서버가 생성한 UUID를 `LibrarySessionStore` 키로 쓰고, 그 값을 `ssuai_library_session` 영속 쿠키로 내려준다(`HttpOnly`, prod `Secure`·`SameSite=None`, maxAge=저장소 TTL). 모든 소비 경로는 `LibrarySessionKeyResolver`로 통일했다. 세션고정 방어는 `changeSessionId()` rotate가 아니라 "키가 클라이언트 입력이 아님"으로 대체했다(ADR 0096).
- **포인트**: 영속화해야 하는 것은 값뿐 아니라 **그 값을 찾는 키**다. Spring Session Redis는 전 서블릿 세션 외부화라 과하고, sticky-only는 재배포에 무력하다. 이 문제는 같은 테이블과 같은 문자열 PK를 유지하면서 키 발급 위치만 바꿔 해결했다.
- **예상 면접 질문**:
  - 토큰은 DB에 남아 있는데 재배포 후 401이 나는 원인을 어떻게 좁히나?
  - 서버 생성 UUID 쿠키가 왜 세션고정 방어에서 `changeSessionId()`를 대체할 수 있나?
  - Spring Session Redis와 sticky-only를 왜 기각했나?

---

## 16. 정상적인 "아무자리나" 좌석 요청이 자기 자신을 rate-limit했다 — 레이트 리밋의 "단위 작업"을 잘못 잡은 문제 (ADR 0097)

- **증상 / 배경**: 사용자가 "도서관 아무자리나 예약해줘"라고 한 번 요청했는데, 첫 턴에서 간헐적으로 `RequestNotPermitted`가 났다. 사용자는 요청 1건만 보냈지만 내부적으로는 `LibrarySeatRecommendationService`가 최대 6개 열람실을 순차 조회했다.
- **틀린 가설**: "sampler가 cluster read budget을 먹었다." sampler는 5분마다 6개 room을 읽는 정도라 20/s 기준에서는 미미하고, 사용자 token과 다른 principal이라 per-user self-throttle의 직접 원인이 아니었다.
- **실제 원인 (2겹)**:
  1. rate cap을 "HTTP 호출 수"에 물렸는데 실제 사용자 의도 1건은 최대 6-room fan-out이었다. 기존 per-user read 2/s에서는 같은 principal 아래 정상 스캔의 세 번째 read부터 막힐 수 있었다.
  2. 코드 재감사(self-review)에서 Redisson `RRateLimiter.trySetRate` 함정도 같이 잡았다. 이 메서드는 set-if-absent라 이미 만들어진 cluster key가 있으면 5/s → 20/s 상향이 운영 Redis에서 no-op이 될 수 있었다. 특히 기존 cluster key는 TTL도 없어서 수동 reset 전까지 오래된 rate가 붙어 있을 수 있었다.
- **해결**: read cap을 fan-out에 맞춰 per-user 2/s → 8/s, cluster 5/s → 20/s로 올렸다(write는 cluster 2/s, per-user 1/s 유지). 20/s는 인증 사용자 주도 통합의 15~25 req/s politeness ceiling 안에 있고, 상류 429는 ADR 0093의 `Retry-After` 게이트가 계속 우선한다. Redis limiter key에는 `:r<n>`을 인코딩하고(`...:user:{principal}:r8`, `...:cluster:r20`) TTL을 붙여 rate config 변경이 배포만으로 새 key에 적용되게 했다.
- **포인트**: 레이트 리밋 예산은 "요청 수"가 아니라 **사용자 의도 1건이 실제로 소비하는 작업량**을 기준으로 잡아야 한다. 분산 리미터는 `trySetRate` 같은 set-if-absent API 때문에 설정값이 key에 실려야 안전하다 — config-carries-in-key 패턴이다.
- **예상 면접 질문**:
  - 6-room fan-out 요청에서 per-user 2/s가 왜 정상 사용자를 막나?
  - cluster cap을 20/s로 올려도 학교 시스템 보호 원칙이 유지되는 근거는?
  - Redisson `trySetRate`가 운영 설정 반영을 막는 이유와 `:r<n>` key가 해결하는 방식은?

---

## 17. tempo가 메모리를 올려도 계속 OOMKill — Go GOMEMLIMIT + non-Ready StatefulSet 함정

- **증상 / 배경**: Tempo가 384Mi에서 시작 직후 block-flush 중 OOM으로 죽었고, 재시작이 741회까지 쌓였다. 메모리를 768Mi로 올리고 `GOMEMLIMIT`도 넣었는데도 같은 OOMKill이 계속 보였다.
- **틀린 가설**: "768Mi도 부족하다." 실제로는 새 리소스 템플릿이 실행 중인 pod에 아직 적용되지 않았다.
- **실제 원인**: Tempo는 Go 프로세스라 cgroup memory limit을 고려한 GC 상한이 없으면 limit 근처에서 늦게 수거하다 OOM을 밟기 쉽다. 그래서 `GOMEMLIMIT=690MiB`(768Mi의 약 90%)가 맞는 1차 처방이었다. 그런데 pod가 CrashLoopBackOff로 non-Ready 상태였기 때문에 StatefulSet 컨트롤러가 새 템플릿으로 정상 교체하지 않았고, 기존 pod가 계속 구 384Mi limit으로 재시작 중이었다.
- **해결**: Tempo memory를 768Mi로 올리고 `GOMEMLIMIT=690MiB`를 지정한 뒤, non-Ready pod를 `kubectl delete pod`로 강제 재생성했다. 새 pod가 실제 768Mi limit과 Go GC 상한을 들고 뜨면서 startup block-flush 구간을 넘겼다.
- **포인트**: Go 컨테이너 OOM은 단순 memory 증설만 보지 말고 `GOMEMLIMIT`으로 GC가 cgroup 예산을 알게 해야 한다. 그리고 StatefulSet에서 non-Ready pod는 template 변경을 자동으로 반영하지 않을 수 있으므로, 리소스가 실제 pod spec에 적용됐는지 `kubectl describe pod`로 확인해야 한다.
- **예상 면접 질문**:
  - Go 컨테이너에서 `GOMEMLIMIT`을 memory limit의 약 90%로 잡는 이유는?
  - StatefulSet template을 바꿨는데 CrashLoopBackOff pod가 계속 예전 limit으로 뜨는 이유는?
  - "메모리를 올렸는데도 OOM"을 pod spec 실측으로 어떻게 확인하나?

---

## 18. 대시보드는 고쳤는데 알림 룰은 놓쳤다 — 존재하지 않는 라벨로 잠들어 있던 Prometheus 알림

- **증상 / 배경**: HighErrorRate/HighLatency 알림이 배포 이후 한 번도 발화할 수 없는 상태였다. 백엔드 메트릭은 정상 수집 중이었고, 대시보드 PromQL은 이미 한 차례 같은 라벨 문제를 고친 뒤였다.
- **틀린 가설**: "알림은 정의되어 있으니 Prometheus가 보고 있다." 실제로는 expr selector가 prod series를 0개 반환하면 알림은 정의만 된 채 영원히 잠든다.
- **실제 원인**: 알림 expr이 백엔드가 방출하지 않는 `application="ssuai"` 라벨을 selector로 쓰고 있었다. 같은 메트릭을 쓰는 대시보드 파일에서는 앞선 수정으로 `job="ssuai-backend"`를 쓰게 됐지만, 별도 PrometheusRule 파일의 알림 룰은 남아 있었다.
- **해결**: prod Prometheus API에서 `count()`로 `application="ssuai"` series 0개와 `job="ssuai-backend"` series 69개를 실측한 뒤 selector를 `job="ssuai-backend"`로 고쳤다. 동시에 예약/EDA 파이프라인 알림 4종(`IntentBusPublishFailures`, `McpToolCallEventDrops`, `IntentSseConsumerLag`, `PyxisReadBudgetSaturated`)을 추가했다.
- **포인트**: 알림 검증의 완료 기준은 "YAML이 있다"가 아니라 **selector가 prod에서 series를 반환한다**다. 대시보드와 알림 룰은 파일이 다르므로, 같은 메트릭 버그를 한쪽만 고쳐도 운영 감시는 여전히 비어 있을 수 있다.
- **예상 면접 질문**:
  - Prometheus 알림이 정의되어 있는데도 절대 발화하지 않는 상태를 어떻게 찾나?
  - 대시보드 PromQL 수정과 PrometheusRule 수정이 별도로 필요한 이유는?
  - alert selector에서 `application`이 아니라 `job="ssuai-backend"`를 기준으로 삼은 근거는?

---

## 19. 정확한 파일 크기를 구하려다 LMS 전체 자료 목록을 잃었다

- **증상 / 배경**: LMS 로그인과 현재 학기 선택은 성공했지만 `get_my_lms_courses`와 전체 자료 ZIP 준비가 모두 `외부 서비스 응답 파싱 오류`로 끝났다. 두 기능은 과목별 자료를 읽은 뒤 비-PDF 파일 크기를 Commons metadata + HEAD로 보정한다.
- **열린 운영 가설**: 재로그인/세션 문제와 term 46의 courses/modules 스키마 변경은 운영 stack trace가 없어 아직 배제하지 않았다. 학기 목록 확인은 뒤따르는 과목·모듈·Commons 응답을 검증하지 않는다. 별개로 Commons가 HTTP 200 HTML을 반환하는 fixture는 실제 코드의 독립적인 회귀를 재현했다.
- **fixture로 재현된 회귀**: 공통 `LmsHttpSession` 리팩터링이 Commons HTML을 `ConnectorParseException`으로 엄격히 분류했는데, 선택 사항인 크기 보정 경로가 그 예외를 전체 자료 조회 실패로 전파했다. 핵심 결과인 자료 목록과 enrichment 결과인 정확한 byte 크기의 실패 경계가 섞였다. 운영 오류의 정확한 호출 단계는 배포 후 실계정 재검증으로 확정한다.
- **해결**: 크기 보정 안에서만 Commons metadata GET의 typed connector·LMS protocol 오류를 격리해 크기를 `null`로 두고 목록을 보존하며, 요청 단위 첫 예외 뒤 남은 과목의 metadata 호출도 중단한다. HEAD 실패는 기존대로 파일별 `null` 처리 후 계속한다. 실제 ZIP worker는 HTML·malformed XML protocol 예외에 실패하고, 유효 XML의 명시적인 capability 부재 항목만 제외한 부분 ZIP을 만든다. 모든 Canvas 쿠키를 Commons에 강제 재전송하는 우회는 cookie scope를 약화시켜 기각했다. 상세 기록은 [LMS 전체 강의자료 조회의 보조 metadata 파싱 회귀](troubleshooting/lms-material-metadata-regression.md)에 남겼다.
- **포인트**: 외부 enrichment는 본 결과보다 약한 일관성·가용성 경계를 가져야 한다. 단, 실제 파일 생성처럼 결과 완전성이 필요한 단계까지 예외를 삼키면 안 된다.
- **예상 면접 질문**:
  - 목록은 fail-soft인데 실제 다운로드는 fail-closed여야 하는 이유는?
  - `RuntimeException` 전체가 아니라 외부 connector 예외 계층만 잡은 이유는?
  - 기능 복구를 위해 쿠키 scope를 약화시키지 않은 이유는?

### 2026-07-16 종단 비교 후속

목록/준비 수정 배포 뒤 PDF 70개는 성공했지만 `contentType=file` 일반 ZIP 4개만 실패했고, 이를 포함한 74개 전체 작업도 함께 실패했다. 이에 따라 총용량·파일 수·압축기 가설을 배제했다. 절대 `content_download_uri`에도 Commons base를 무조건 접두하던 resolver를 URI 표준 해석으로 고치고, typed 개별 항목 오류는 나머지 자료와 안전한 누락 보고서를 담은 부분 ZIP으로 격리했다. 인증 만료·429·한도·내부 오류와 전 항목 실패는 계속 fail-closed한다. 상세 기록은 [LMS 일반 첨부파일이 전체 ZIP 내보내기를 실패시킨 문제](troubleshooting/lms-general-attachment-export.md)에 남겼다.

---

## 20. 로그인은 되어 있는데 학사·LMS 에이전트가 계속 재로그인을 요구했다

- **증상 / 배경**: ssuAI 상단에는 provider가 연결된 것으로 보였지만 학사와 LMS 에이전트는 각각 미연결 안내를 반복했고, 실제 `/api/mcp/auth/web-session` 요청에서는 HTTP 500도 관찰됐다.
- **실제 원인**: 영속 SAINT/LMS credential 복사는 같은 bean의 트랜잭션 메서드를 self-invocation했다. Spring proxy를 우회한 상태에서 JPA 잠금 쿼리가 실행돼 canonical credential이 있으면 500이 발생했다. 반대로 credential이 없으면 provider가 없는 세션을 201로 반환했는데, 응답에 실제 grant가 없어 프론트엔드가 JWT 존재를 연결 성공으로 오인했다.
- **해결**: `copyForSession()` 전체에 트랜잭션 경계를 두고, 각 credential을 새 opaque owner key로 재암호화한 뒤 실제 복사에 성공한 provider만 `linkedProviders`로 반환한다. persistent Spring 통합 테스트로 호출자 트랜잭션 없이 복사가 성공하는지 검증한다. 상세 기록은 [MCP 웹 세션 credential 복사 트랜잭션 회귀](troubleshooting/mcp-web-session-credential-copy.md)에 남겼다.
- **포인트**: 웹 신원, 외부 provider credential, MCP 세션 grant는 서로 다른 상태다. 하나의 로그인 표시로 세 상태를 추론하면 만료·배포·부분 장애에서 거짓 연결 상태가 된다.
- **예상 면접 질문**:
  - `@Transactional` self-invocation이 JPA 잠금 쿼리에 어떤 문제를 만드는가?
  - 부분 provider 연결 상태를 API 계약으로 노출해야 하는 이유는?
  - 기존 credential namespace를 직접 공유하지 않고 복사하는 이유는?

### 2026-07-18 provider health 의미 불일치 후속

credential 복사는 정상화됐지만 `ERROR` health도 `linkedProviders`에 남아 브라우저는 `3/3 연결됨`, 에이전트는 `UNAVAILABLE`로 판단하는 두 번째 불일치가 확인됐다. 기존 grant 계약은 유지하고 `availableProviders`와 `providerHealth`를 추가했으며, 프론트의 stale 상태와 에이전트의 tool error도 결정적으로 처리했다. 핵심은 identity, credential grant, 현재 availability, 마지막 operational health를 같은 boolean으로 축약하지 않는 것이다. 상세 재현·대안·검증은 [MCP 웹 세션 credential 복사 트랜잭션 회귀](troubleshooting/mcp-web-session-credential-copy.md#2026-07-18-후속-33-연결-표시와-실제-provider-장애가-어긋남)에 이어 기록했다.
