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
- **포인트**: "쿠키가 안 붙는다"는 단일 증상이 cross-origin / redirect / route intercept order / framework cookie API 네 개의 서로 다른 레이어에 분산돼 있었다. Vercel + Next.js에서 SSO 콜백 쿠키를 안정적으로 내리는 유일한 패턴은 미들웨어에서 `response.cookies.set()`을 쓰는 것이고, 다른 방법은 전부 어느 레이어에선가 조용히 제거된다.
- **예상 면접 질문**:
  - Next.js App Router에서 Set-Cookie가 조용히 제거되는 상황과 올바른 API는?
  - 같은 증상이 네 개 레이어에 분산됐을 때 어떻게 레이어를 격리해 디버깅하나?
  - cross-site 쿠키를 same-origin proxy로 우회할 때의 트레이드오프는?

---

## 3. "최우선 P0" 보안 제보를 코드로 추적해 오진으로 판정 (MCP 3-tier 세션)

- **증상 / 배경**: 전체 코드를 여러 각도로 교차 검증하는 보안 점검 과정에서, "인증 우회 P0"가 보고됐다. 실제 로그인 세션으로 테스트한 뒤, 가짜 `mcp_session_id="invalid-session-after-login-test"`로 `get_my_schedule`·`get_my_lms_terms`·`prepare/confirm_lms_material_export`를 호출했더니 전부 `status:OK` + 실데이터/다운로드 URL을 반환했고, 응답이 `{"status":"OK","provider":null,"mcpSessionId":"invalid-..."}` 형태였다는 것이다.
- **틀린 가설**: "private tool이 입력 `mcp_session_id`를 검증하지 않고 런타임에 남은 ambient 로그인 상태를 사용한다 → 임의 문자열로 타인 데이터에 접근 가능." 제시된 처방은 `requireProviderSession`(인자가 DB 세션에 없으면 거부)이었다.
- **실제 원인**: `McpAuthHelper.resolveSession()`은 ADR 0036의 **3-tier 전략**이다 — Tier1 OAuth `sub`(검증된 Bearer JWT) → Tier2 transport id(`Mcp-Session-Id` 헤더) → Tier3 opaque `mcp_session_id`(LLM 인자). prod는 `rs-enabled=true`라 **Tier1에서 테스터 자신의 세션이 먼저 해소**되고, 가짜 Tier3 인자에는 도달조차 하지 않는다. 즉 반환된 데이터는 테스터 자기 계정 것이다. 여기에 `McpPrivateToolResponse.ok()`가 입력 인자를 그대로 **echo**하고 `provider`를 **null로 하드코딩**해, "가짜 세션이 먹혔다"는 착시를 만들었다. 미인증 + 가짜 인자뿐이면 Tier3 `find()`가 empty → 정상적으로 `AUTH_REQUIRED`. 우회는 존재하지 않았다.
- **해결**: 신뢰경계를 1차 자료(코드)로 검증했다 — `McpAuthHelper`(3-tier 해소), `SaintScheduleMcpTool`(OK 경로가 입력 인자 echo), `McpPrivateToolResponse.ok()`(provider=null) 3곳을 직접 읽어 착시의 출처를 특정하고, prod가 `rs-enabled=true`임을 확인해 Tier1 라이브를 입증했다. 처방대로 `requireProviderSession`을 적용했다면 정당한 Tier1/2 해소를 깨 **인증 기능이 회귀**할 것이라 적용을 거부했다. 다만 *오진을 유발한* 경미버그(echo·provider:null)는 OK 응답이 canonical 세션 id + provider를 반환하도록 바꿔 정리했다.
- **포인트**: "심각한 P0"라는 보고를 그대로 수용하지 않고, 전송계층 OAuth 3-tier 설계를 코드로 추적해 오진으로 판정했다. 동시에 그 오진을 *유발한* 진짜 경미버그(응답 echo)는 따로 잡았다. 자가보고를 맹신하지 않고 신뢰경계를 코드로 검증하며, 잘못된 처방이 만들 회귀까지 평가하는 판단이 핵심이다.
- **예상 면접 질문**:
  - MCP 서버에서 세션을 3-tier로 해소하는 이유와, 가짜 opaque 인자가 왜 우회가 되지 않는가?
  - "가짜 세션 id로 실데이터가 조회됐다"는 제보가 실제 우회인지 인증 아티팩트인지 어떻게 가르는가?
  - 응답에 입력 세션 id를 echo한 것이 왜 분석을 오도했고, canonical id 반환이 왜 더 안전한가?

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
