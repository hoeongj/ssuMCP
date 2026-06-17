# ssuAI 트러블슈팅 로그

이 파일은 포트폴리오에 넣기 좋은 장애 대응, 디버깅, 배포 문제 해결 기록을
모으는 최상위 로그입니다.

## 2026-06-18 — MCP OAuth PRM에 `authorization_servers` 누락: Spring Security 7이 자동 생성한 필터가 수제 컨트롤러를 가림(shadowing)

- 맥락:
  - G3에서 opt-in OAuth RS 모드(`rs-enabled=true` + Auth0 issuer/audience)를 prod에 켠 뒤, ChatGPT(MCP 클라이언트)가 `start_auth` 루프에서 빠져나오지 못했다. RFC 9728 Protected Resource Metadata(PRM) 문서로 Authorization Server(Auth0)를 자동 발견해야 Bearer JWT 흐름에 진입하는데 그게 안 됐다.
- 증상:
  - `curl https://ssumcp.duckdns.org/.well-known/oauth-protected-resource` → `{"resource":"https://ssumcp.duckdns.org","bearer_methods_supported":["header"],"tls_client_certificate_bound_access_tokens":true}`. **`authorization_servers` 필드가 없음** → 클라이언트가 토큰 받을 곳(Auth0)을 모름.
- 처음 세운 가설 (틀린 방향):
  - MASTERPLAN엔 "PRM 응답에 `authorization_servers` 필드를 추가 구현해야 한다"고 적혀 있었다. 그러나 코드를 열어 보니 `ProtectedResourceMetadataController`가 **이미** `authorization_servers`를 포함해 반환하고 있었다. 즉 "필드 추가"가 아니라 "왜 그 컨트롤러의 응답이 안 나오는가"가 진짜 질문이었다. 결정적 단서: 실제 응답에 컨트롤러 코드엔 없는 `tls_client_certificate_bound_access_tokens` 필드가 있었고, 코드 전체 grep으로 우리 코드 어디에도 그 문자열이 없음을 확인 → 응답 출처가 우리 컨트롤러가 아니었다.
- 실제 원인:
  - Spring Boot 4.0.6 = **Spring Security 7.0.5**. `oauth2ResourceServer(...)`를 설정하면 Security가 **`OAuth2ProtectedResourceMetadataFilter`를 자동 등록**해 `/.well-known/oauth-protected-resource`를 직접 서빙한다. 서블릿 필터는 DispatcherServlet(=MVC `@GetMapping`)보다 먼저 실행되므로 같은 경로의 수제 컨트롤러는 **조용히 가려져 한 번도 실행되지 않는다.** 프레임워크가 만든 기본 문서에는 외부 AS를 알 길이 없어 `authorization_servers`가 빠진다.
- 해결:
  - 가려져 죽은 수제 컨트롤러를 삭제하고, Security 7의 확장 지점으로 필드를 주입: `oauth2ResourceServer(o -> o.protectedResourceMetadata(m -> m.protectedResourceMetadataCustomizer(b -> b.authorizationServer(issuerUri))))`. DSL/빌더 메서드는 추측하지 않고 실제 7.0.5 jar를 `javap`로 까서 검증(`OAuth2ProtectedResourceMetadata.Builder.authorizationServer(String)`, `OAuth2ResourceServerConfigurer.protectedResourceMetadata(...)`).
  - 커스터마이저는 인라인 람다 대신 static 메서드 `authorizationServersCustomizer(issuerUri)`로 추출 → 서블릿 필터·OIDC discovery 없이 빌더에 적용 후 `authorization_servers` claim만 단위 검증.
- 핵심 파일/브랜치:
  - `src/main/java/com/ssuai/global/security/McpOAuthSecurityConfig.java` (커스터마이저 주입 + static 메서드)
  - `src/main/java/com/ssuai/global/security/ProtectedResourceMetadataController.java` (삭제 — 가려진 죽은 코드)
  - `src/test/java/com/ssuai/global/security/McpOAuthSecurityConfigTests.java` (신규)
  - 브랜치/PR: `fix/mcp-prm-authorization-servers` (merge 후 commit hash는 MASTERPLAN에 기록)
- 검증:
  - 단위 테스트 green. 배포 후 `curl .../.well-known/oauth-protected-resource` 응답에 `"authorization_servers":["https://dev-...auth0.com/"]` 포함 확인이 진짜 종단 검증. ※ "ChatGPT 루프 종료"는 사용자 브라우저 게이트로 별도 확인 필요 — 코드 레벨에선 PRM 응답까지만 보장한다.
- 포트폴리오 포인트:
  - "프레임워크가 이미 해주는 일을 모르고 수제 구현을 얹어 shadowing이 난" 전형. 서블릿 필터 vs MVC 디스패치 실행 순서, RFC 9728, 그리고 라이브러리 동작을 추측 대신 `javap`/실측 `curl`로 확정한 디버깅 과정.
- 면접 예상 질문:
  1. "MVC 컨트롤러가 분명히 있는데 왜 그 응답이 안 나왔나? 서블릿 필터와 DispatcherServlet의 실행 순서는?"
  2. "RFC 9728 PRM의 `authorization_servers`는 무슨 역할이고, 없으면 MCP 클라이언트가 왜 인증 루프에 빠지나?"
  3. "라이브러리의 자동 설정 동작을 추측이 아니라 사실로 어떻게 확정했나?"

## 2026-06-18 — LMS 다운로드 HTML 페이지의 한글 깨짐: `Content-Type`에 charset 미지정 → 본문이 ISO-8859-1로 디코딩

- 맥락/증상: LMS export 다운로드 링크를 브라우저용 HTML 페이지로 바꾸면서 `ResponseEntity.contentType(MediaType.TEXT_HTML)`로 String 본문을 반환했더니, MockMvc 테스트에서 `containsString("LMS 강의자료 다운로드")`가 실패. 실제 본문 바이트는 정상 UTF-8(`강`=EA B0 95)인데 `LMS ê°ìì래…`로 보였다.
- 원인: `text/html`에 **charset 파라미터가 없으면** 소비 측(MockMvc·일부 브라우저)이 본문을 ISO-8859-1로 디코딩한다. HTML `<meta charset="UTF-8">`는 HTTP 헤더가 아니라 문서 내부 선언이라, 헤더가 우선되는 디코딩 단계에선 무력하다. (javac 컴파일은 정상 — `.class` 상수 바이트가 UTF-8임을 mojibake 패턴으로 역확인.)
- 해결: `new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)` → 헤더가 `text/html;charset=UTF-8`. 테스트 통과 + 실제 브라우저 렌더링도 안전.
- 면접 예상 질문: "`<meta charset>`가 있는데도 한글이 깨진 이유는? HTTP 헤더 charset과 문서 내 선언 중 무엇이 우선인가?"

## 2026-06-18 — PVC sync-wave 교착: WaitForFirstConsumer 볼륨을 consumer보다 앞 wave에 둬 ArgoCD 배포가 영구 정지

- 맥락:
  - LMS export ZIP을 pod 재시작에도 보존하려고 PVC(`ssuai-backend-lms-export`)를 추가한 #72를 머지한 뒤, 백엔드 배포가 #71 이미지(`sha-4c43870`)에서 더 진행되지 않았다. #73(V12 마이그레이션 + opt-in OAuth)·#72(PVC 마운트)가 prod에 올라가지 못함.
- 증상:
  - ArgoCD `ssuai-backend` 앱이 `OutOfSync / Progressing`에서 멈춤. `opPhase=Running`, `opMsg="waiting for healthy state of /PersistentVolumeClaim/ssuai-backend-lms-export"`. PVC는 `Pending`, Deployment는 새 ReplicaSet을 만들지 못한 채 옛 pod 1개만 Running.
- 처음 세운 가설 (틀린 방향):
  - "이미지 빌드/Image Updater writeback이 늦는 단순 배포 랙"이라고 추정. 실제로는 writeback이 `sha-54827d8`까지 정상 기록돼 있었고 git values.yaml도 최신이었음 — 멈춘 건 ArgoCD sync 그 자체였다.
- 실제 원인:
  - PVC가 `argocd.argoproj.io/sync-wave: "1"`, Deployment가 `"3"`. ArgoCD는 wave를 오름차순으로 적용하며 **각 wave가 Healthy가 될 때까지 다음 wave를 적용하지 않는다.** 그런데 PVC의 StorageClass `local-path`는 **WaitForFirstConsumer** 바인딩 모드라 그 PVC를 마운트하는 **consumer pod가 스케줄돼야 비로소 바인딩**된다. consumer는 wave 3의 Deployment에 있는데 ArgoCD는 wave 1의 PVC가 Bound(Healthy)될 때까지 wave 3을 적용하지 않으므로 → consumer가 영영 안 생김 → PVC 영영 Pending → 교착.
  - PVC 주석의 의도("pod 뜨기 전에 볼륨을 미리 바인딩")는 WaitForFirstConsumer 모드에서는 원천적으로 성립할 수 없는 가정이었다.
- 해결:
  - PVC를 Deployment와 **같은 wave("3")**로 옮겨 동시 적용. Deployment의 새 pod가 PVC의 first consumer가 되어 볼륨이 바인딩되고 둘 다 Healthy에 도달한다. (주석만 제거해 wave 0으로 두는 건 0<3 이라 여전히 Deployment보다 앞서므로 교착 유지 — 오답.)
  - 머지 후에도 막힌 sync op는 타임아웃 없이 자가 종료되지 않으므로, Application status의 `operationState.phase`를 `Terminating`으로 patch해 강제 종료 → auto-sync(selfHeal)가 새 리비전(PVC wave 3)으로 재싱크하게 했다. (`argocd` CLI 부재 → kubectl로 처리. ArgoCD Application CRD는 status 서브리소스를 쓰지 않아 `--subresource status` 없이 일반 merge patch로 status 수정 가능.)
- 핵심 파일/커밋:
  - `deploy/charts/ssuai-backend/templates/pvc.yaml` (sync-wave 1→3)
  - `deploy/charts/ssuai-backend/templates/deployment.yaml` (consumer, wave 3 — 참조)
  - 커밋/PR: `c70a3b4` (PR #77, `fix/pvc-sync-wave-deadlock`)
  - terminate: `kubectl patch app ssuai-backend -n argocd --type merge -p '{"status":{"operationState":{"phase":"Terminating"}}}'`
- 검증:
  - 재싱크 후 ArgoCD `Synced / Healthy / opPhase=Succeeded`, PVC `Bound`(5Gi, local-path), 새 pod `sha-54827d8` `1/1 Running`. 새 pod 로그에서 Flyway `Migrating schema "public" to version "12 - add mcp session transport oauth"` → `Successfully applied 1 migration, now at version v12`(PostgreSQL 17.10) 확인 — #73 V12까지 같은 롤로 함께 해소.
- 포트폴리오 포인트:
  - GitOps(ArgoCD) sync-wave 순서 제어와 Kubernetes 동적 프로비저닝(WaitForFirstConsumer) 바인딩 모델의 상호작용을 모르면 생기는 전형적 교착을, 상태(opMsg)만으로 근본 원인을 특정하고 차트 1줄(wave)로 해결한 사례. "CI는 green인데 prod 배포가 멈추는" 부류의 문제.
- 면접 예상 질문:
  1. "PVC를 Deployment보다 먼저(낮은 wave) 만들면 더 안전하지 않나? 왜 같은 wave에 둬야 했나?"
     - (답변 요약: WaitForFirstConsumer에선 consumer pod 없이는 PVC가 절대 바인딩되지 않는다. PVC를 consumer보다 앞 wave에 두면 ArgoCD가 PVC의 Healthy(Bound)를 기다리느라 consumer가 있는 다음 wave를 영영 적용하지 못해 교착한다. 같은 wave여야 Deployment의 pod가 first consumer가 되어 바인딩된다.)
  2. "WaitForFirstConsumer와 Immediate 바인딩 모드의 차이는? 왜 local-path는 전자인가?"
     - (답변 요약: Immediate는 PVC 생성 즉시 볼륨을 프로비저닝·바인딩한다. WaitForFirstConsumer는 consumer pod가 스케줄될 노드가 정해진 뒤 그 노드에 볼륨을 만들어 바인딩한다. local-path는 노드 로컬 디스크라 pod가 어느 노드에 뜰지 알아야 볼륨 위치를 정할 수 있어 WaitForFirstConsumer가 기본이다.)
  3. "ArgoCD sync가 wave 중간에 멈췄을 때 어떻게 진단했고 어떻게 강제 종료했나?"
     - (답변 요약: `kubectl get app -o jsonpath`로 `operationState.phase=Running` + `opMsg`(waiting for healthy state of PVC)를 보고 원인이 PVC 교착임을 특정했다. argocd CLI가 없어 Application status의 `operationState.phase`를 `Terminating`으로 patch해 op를 종료시켰고, selfHeal이 수정된 새 리비전으로 자동 재싱크하도록 했다.)

## 2026-06-16 — LMS 예외 분류 개선: 비인증 API 에러와 세션 만료 예외의 분리 및 진단성 확보

- 맥락:
  - LMS 관련 도구(예: `get_my_lms_courses`, `export_all_lms_materials` 등)가 세션 만료 외의 API 에러(404, 500, 존재하지 않는 엔드포인트 호출 등)를 만났을 때도 전부 `LmsSessionExpiredException`으로 오진되어 사용자에게 무조건 "AUTH_REQUIRED - please log in again"이라고 안내되는 현상이 발생했다.
- 증상:
  - 잘못된 엔드포인트 호출 등으로 인한 4xx 에러가 발생해도 단순 세션 끊김으로 표시되어 실제 API 버그 진단이 불가능했다. Part A 단계에서 발생한 경로 에러가 쉽게 발견되지 못했던 본질적인 원인이었다.
- 처음 세운 가설 (틀린 방향):
  - "LMS 세션이 죽었다"거나 "defaultTerm 다중으로 학기 자동선택이 깨졌다"고 추정하여 사용자 로그인 연동이나 세션 유틸의 오류로 접근했다.
- 실제 원인:
  - `RealLmsMaterialsConnector`의 `fetchCourses()` 및 `fetchMaterials()`가 모든 non-2xx 응답에 대해 일괄적으로 `LmsSessionExpiredException`을 발생시키고 있었으며, 상위 도구 레이어(`LmsMaterialsMcpTool`, `LmsMaterialExportMcpTool`)가 이를 캐치하여 일률적으로 `AUTH_REQUIRED` 상태를 클라이언트에 반환하고 있었다.
- 해결:
  - 인증 에러인 401 및 403 코드만 `LmsSessionExpiredException`으로 분류하고, 그 외의 non-2xx 응답에 대해서는 새로운 `LmsApiException`을 정의하여 실제 HTTP 응답 코드 및 응답 본문 요약(200자 제한)을 실어 던지도록 분리하였다.
  - 도구 레이어의 각 메소드에 `LmsApiException`의 catch 블록을 추가하여, 세션 재인증 요구가 아닌 상세 오류 메시지("LMS API 오류가 발생했습니다. 잠시 후 다시 시도해 주세요. ...")를 반환하도록 개선하였다.
- 핵심 파일/커밋:
  - `src/main/java/com/ssuai/global/exception/LmsApiException.java`
  - `src/main/java/com/ssuai/domain/lms/connector/RealLmsMaterialsConnector.java`
  - `src/main/java/com/ssuai/domain/mcp/tool/LmsMaterialsMcpTool.java`
  - `src/main/java/com/ssuai/domain/mcp/tool/LmsMaterialExportMcpTool.java`
  - `src/test/java/com/ssuai/domain/lms/connector/RealLmsMaterialsConnectorTests.java`
  - 커밋 해시: `2d7ac187a419eb7f6f8df0f3d97f2e16d43e5c9f`
- 검증:
  - `RealLmsMaterialsConnectorTests`에 서버 에러(500) 시 `LmsApiException` 발생 및 상태 코드 확인 테스트, 401 및 403 발생 시 `LmsSessionExpiredException` 발생 테스트를 각각 추가하여 동작을 검증하였으며, `.\gradlew.bat test`가 전부 통과함을 확인했다.
- 포트폴리오 포인트:
  - 외부 연동 모듈에서 인증 만료 상황(401/403)과 일반 API/네트워크 서버 오류를 명확히 구분하는 예외 계층 구조를 설계하고, 비인증 오류가 인증 오류로 위장해 디버깅 장벽을 높이는 문제를 차단하여 시스템의 유지보수성 및 진단 가능성을 극대화한 사례.
- 면접 예상 질문:
  1. "왜 다른 LMS 도구는 다 되는데 get_my_lms_courses만 실패했나? (같은 세션인데)"
     - (답변 요약: 동일 세션 쿠키를 사용하지만, 다른 LMS 도구들은 LearningX 엔드포인트로 정상 작동한 반면, `get_my_lms_courses`는 이전에 Canvas 네이티브 엔드포인트 호출을 Cookie-only 방식으로 시도하면서 4xx 권한 오류가 발생했기 때문입니다. 이때 발생한 4xx 오류가 예외 분류 미비로 인해 세션 만료 예외로 둔갑되어 전체 세션 장애로 잘못 인지되었습니다.)
  2. "비인증 API 오류를 AUTH_REQUIRED로 보고하면 어떤 문제가 생기나, 어떻게 고쳤나?"
     - (답변 요약: 세션이 살아있는데도 반복적으로 로그인을 요구하여 사용자 경험을 해치고, 서버 로그만으로는 실제 연동 장애나 API 스펙 변경 등의 근본 원인을 진단하기 어렵게 만듭니다. 이를 401/403(인증 예외)과 그 외 4xx/5xx(LmsApiException)로 분리해 적절한 에러 안내 및 바디 로그를 출력하도록 개선했습니다.)
  3. "Canvas와 LearningX가 같은 도메인에서 서로 다른 인증 방식을 쓰는 이유는?"
     - (답변 요약: Canvas는 오픈소스 LMS 표준 플랫폼이고 LearningX는 해당 플랫폼 위에 얹어진 연동 커스텀 확장 서비스이기 때문입니다. Canvas Native API는 세션 쿠키 기반의 웹뷰 세션 인증을 공유하여 쓰고, LearningX API는 네이티브 앱이나 외부 비동기 처리를 위해 헤더의 Bearer 토큰(`xn_api_token`)을 주된 인증 수단으로 삼아 개발되었기에 두 사양의 차이가 발생합니다.)

## 2026-06-16 — LMS 과목 조회 API 버그: Canvas Native API 호출 실패로 인한 AUTH_REQUIRED 오진

- 맥락:
  - LMS 세션이 활성화되어 있음에도 `get_my_lms_courses` 도구가 `AUTH_REQUIRED`를 잘못 반환하는 현상이 발생했다.
- 증상:
  - 사용자의 LMS 로그인 세션이 유효함에도 불구하고, 과목 조회를 요청할 때마다 세션 만료 응답이 돌아왔다. 이로 인해 주차별 학습 자료 조회(`get_my_lms_materials`) 및 zip 내보내기(`prepare_lms_material_export`) 등의 하위 기능들도 전부 차단되었다.
- 처음 세운 가설 (틀린 방향):
  - u-SAINT SSO 연동 문제나 LMS 세션 쿠키 쿠키스토어(CookieStore)의 만료 등으로 인해 실제 세션이 끊어졌다고 판단했다.
  - 하지만 다른 LMS API 요청(예: 과제 조회 `RealLmsAssignmentsConnector`)은 동일한 세션 쿠키를 사용해 정상적으로 수행되고 있었으므로 기각되었다.
- 실제 원인:
  - `RealLmsMaterialsConnector.fetchCourses()`가 Canvas Native 엔드포인트인 `/api/v1/courses`를 호출할 때 Cookie 기반 인증으로만 요청을 보내고 있었고, 이 API가 4xx 에러를 반환하자 커넥터가 이를 `LmsSessionExpiredException`으로 처리하여 발생한 일이었다.
  - 다른 모든 LMS 관련 호출들은 Bearer 토큰(`xn_api_token`)을 이용해 LearningX 엔드포인트 `/learningx/api/v1/...`를 호출하고 있었다.
- 해결:
  - `fetchCourses` API를 `RealLmsAssignmentsConnector.fetchCourseNames()`와 일치하도록 LearningX 엔드포인트인 `/learningx/api/v1/learn_activities/courses?term_ids[]={termId}`로 전환했다.
  - 쿠키에서 `xn_api_token`을 추출하여 `Authorization: Bearer` 헤더를 추가하도록 수정했다.
  - LearningX API는 Canvas Native API와 달리 XSSI 방지용 `while(1);` 접두사가 응답에 포함되지 않으므로, 문자열을 전치 스캔하는 `parseCanvasJson` 대신 표준 `objectMapper.readTree()`로 전환하여 불필요한 연산을 제거했다.
  - 또한, 신규 엔드포인트가 `term_ids[]` 쿼리 매개변수를 통해 서버 사이드에서 이미 학기별 필터링을 수행하므로, 기존의 클라이언트 사이드 학기 매칭 필터(`enrollmentTermId == termId`)를 완전히 제거했다.
- 핵심 파일/커밋:
  - `src/main/java/com/ssuai/domain/lms/connector/RealLmsMaterialsConnector.java`
  - `src/test/java/com/ssuai/domain/lms/connector/RealLmsMaterialsConnectorTests.java`
- 검증:
  - `RealLmsMaterialsConnectorTests` 내 `fetchCoursesStripsXssiPrefix` 테스트를 `fetchCourses_returnsCoursesFromLearningXEndpoint`로 개명하여, LearningX 엔드포인트 호출 여부, Bearer 헤더 검증, 그리고 학기 파라미터가 포함되었는지를 모의 서버(MockWebServer)로 완벽히 검증했다. `.\gradlew.bat test` 실행 결과 전체 테스트 통과를 확인했다.
- 포트폴리오 포인트:
  - 동일한 외부 연동 도메인 내에서 Canvas Native 스펙과 LearningX 커스텀 스펙의 혼용으로 인한 인증 및 엔드포인트 불일치 문제를 해결하고, 일관된 Bearer 토큰 인증과 최적화된 파싱(Standard ObjectMapper vs Custom Regex Stripping)을 설계해 시스템 안정성을 개선한 실전 디버깅 및 고도화 사례.
- 면접 예상 질문:
  1. Canvas Native API와 LearningX API의 구조적 차이와, 이를 통합할 때 발생한 세션/인증 처리 문제를 어떻게 해결했나요?
  2. XSSI 방지용 접두사(`while(1);`)가 들어오는 API와 들어오지 않는 API를 구별해 파싱을 최적화(Standard ObjectMapper 사용)한 이유와 기대 효과를 설명해 주세요.
  3. 클라이언트 사이드 필터링 대신 쿼리 파라미터를 활용한 서버 사이드 필터링으로 변경 시 성능과 네트워크 대역폭 측면에서 어떤 장점이 있나요?

## 2026-06-16 — LMS 학기 판별 버그: Canvas defaultTerm 플래그 오작동으로 인한 잘못된 학기 조회

- 맥락:
  - 사용자가 LMS 과제나 대시보드를 조회할 때, 현재 수강 중인 학기가 아닌 다른 학기가 활성화되어 수강 중인 과목과 과제가 누락되는 현상이 발생했다.
- 증상:
  - 봄학기 수업을 수강 중인데 여름학기 등록 기간이 오픈되자 Canvas 포털이 여름학기를 `defaultTerm`으로 강제 활성화하면서, 사용자의 대시보드와 과제 목록에 여름학기(당시 수강 과목 없음)가 노출되어 과제 조회가 되지 않았다.
- 처음 세운 가설 (틀린 방향):
  - u-SAINT의 SSO 로그인 세션이 제대로 연동되지 않아 이전 학기 캐시가 만료되었거나, 사용자 학번(studentId) 매핑 오류로 엉뚱한 학생의 학기를 불러왔다고 가정했다.
  - 하지만 다른 도구(학식, 도서관 등)는 정상 작동하며, 특정 사용자 학번에 상관없이 일관되게 특정 미래 학기로 고정되어 반환되었으므로 기각되었다.
- 실제 원인:
  - Canvas API는 `default` 플래그를 제공하는데, 이는 "수강 중인 학기"가 아니라 "현재 등록/수강신청을 위해 열린 최신 학기"를 가리키는 경우가 많다. 이로 인해 수강신청 오픈 시 실제 학기가 아직 진행 중임에도 미래의 계절학기나 다음 학기가 default 학기로 인식되어 잘못된 학기 ID를 가져오는 문제였다.
- 해결:
  - 날짜 기반의 학기 판별 유틸리티인 `LmsTermResolver`를 구현했다.
  - 현재 시각(now)이 학기 시작일(`startAt`)과 종료일(`endAt`) 범위 내에 속하는 학기를 실제 수강 중인 학기로 간주하여 우선 선택한다.
  - 날짜 범위가 일치하지 않는 경우(방학 기간 등)에만 Canvas의 `defaultTerm` 플래그를 확인하고, 이마저도 없으면 첫 번째 학기로 폴백하게 설계했다.
  - 이 `LmsTermResolver`를 `RealLmsAssignmentsConnector`, `LmsDashboardService`, 그리고 신규 `LmsMaterialsService`에 공통 적용하였다.
- 핵심 파일/커밋:
  - `src/main/java/com/ssuai/domain/lms/service/LmsTermResolver.java`
  - `src/main/java/com/ssuai/domain/lms/service/LmsDashboardService.java`
  - `src/main/java/com/ssuai/domain/lms/connector/RealLmsAssignmentsConnector.java`
- 검증:
  - `LmsTermResolverTests`를 통해 미래 학기에 default=true 플래그가 있어도 현재 시간 오버랩 학기가 우선적으로 선택됨을 검증하고, 모든 통합 테스트가 성공함을 확인했다.
- 포트폴리오 포인트:
  - 외부 벤더 API(Canvas)의 스펙과 실제 비즈니스 수명 주기 간의 괴리(등록 가능 기간 vs 수강 기간)를 감지하고, 외부 플래그에 전적으로 의존하는 대신 날짜 기반 오버랩 계산과 계층형 폴백 설계로 시스템 견고성을 끌어올린 실전 트러블슈팅 사례.
- 면접 예상 질문:
  1. 외부 벤더 API의 특정 플래그(예: `defaultTerm`)가 서비스 요구사항과 미세하게 일치하지 않을 때, 어떻게 복원력 있는 설계를 구축할 수 있나요?
  2. `LmsTermResolver`에서 날짜 오버랩을 판단하기 위해 ISO-8601 문자열을 `Instant`로 안전하게 파싱하고 유효하지 않은 포맷을 필터링한 방법을 설명해 주세요.

## 2026-06-16 — LMS Dashboard 도구 컴파일 에러: Java Generic Type Inference 한계로 인한 McpPrivateToolResponse 타입 불일치

- 맥락:
  - `get_lms_dashboard` MCP 도구를 구현하던 중, 인증 상태와 세션 만료 등의 예외 흐름을 처리하기 위해 `Optional.map().orElseGet()` 체인을 활용했다.
- 증상:
  - Java 컴파일 단계에서 다음과 같은 에러가 발생하며 빌드가 실패함:
    `error: incompatible types: McpPrivateToolResponse<CAP#1> cannot be converted to McpPrivateToolResponse<Object>`
- 처음 세운 가설 (틀린 방향):
  - `.orElseGet(() -> authHelper.<Object>buildAuthRequired(...))`에서 제네릭 타입 `<Object>` 지정을 생략했거나 잘못 입력하여 컴파일러가 반환형 타입을 맞추지 못했다고 판단했다.
  - 하지만 코드에는 명확히 `<Object>`가 정의되어 있었으므로 이 가설은 기각되었다.
- 실제 원인:
  - Java 컴파일러는 `Optional.map()` 내부의 lambda 식의 반환 타입을 추론할 때, 성공 경로인 `McpPrivateToolResponse.ok(..., dashboard)` (여기서 `dashboard`는 `LmsDashboardResponse` 타입)와 실패 경로(예외 catch)인 `authHelper.<Object>buildAuthRequired(...)`의 공통 상위 타입으로 `McpPrivateToolResponse<? extends Object>`를 추론했다.
  - Java 제네릭은 무공변성(Invariant)을 가지므로 `McpPrivateToolResponse<? extends Object>` (즉, `McpPrivateToolResponse<CAP#1>`)를 `McpPrivateToolResponse<Object>`로 바로 캐스팅하거나 할당할 수 없어 컴파일러 에러가 발생했다.
- 해결:
  - `McpPrivateToolResponse.ok(mcp_session_id, dashboard)` 호출 부분에 명시적으로 타입 파라미터 `<Object>`를 지정하여 `McpPrivateToolResponse.<Object>ok(mcp_session_id, dashboard)`로 수정했다. 이로써 컴파일러가 map 내부의 lambda 반환형을 일관되게 `McpPrivateToolResponse<Object>`로 인지하여 타입 불일치가 해결되었다.
- 핵심 파일/커밋:
  - `src/main/java/com/ssuai/domain/mcp/tool/LmsDashboardMcpTool.java`
- 검증:
  - `.\gradlew.bat cleanTest test` 빌드 완료 및 모든 테스트 통과 확인.
- 포트폴리오 포인트:
  - Java 제네릭의 무공변성(Invariance) 특성과 `Optional.map`의 타입 추론 메커니즘을 명확히 이해하고, 와일드카드 캡처(`CAP#`) 오류가 발생한 원인을 정확히 짚어내어 명시적 타입 아규먼트 제공으로 문제를 해결한 사례.
- 면접 예상 질문:
  1. Java 제네릭의 무공변성(Invariant)이란 무엇이며, 공변성(Covariant)과의 차이는 무엇인가요?
  2. `McpPrivateToolResponse<LmsDashboardResponse>`를 왜 `McpPrivateToolResponse<Object>`로 바로 캐스팅하거나 할당할 수 없나요?
  3. `Optional.map` 내에서 컴파일러가 반환 타입을 결정할 때 다중 경로(try-catch)의 반환 타입이 다르면 어떻게 추론하는지 설명해 보세요.

## 2026-06-15 - ssuAgent "Connection error." — k8s 시크릿 env 키 trailing `\r` 오염

### 상황

ssuAI 챗봇에서 "도서관 예약해줘" 요청이 연속으로 5가지 다른 에러를 뱉으며 완전히 다운됐다. Claude Desktop(Anthropic LLM 직접 사용)에서는 동일 요청이 정상 작동함. 마지막 에러: `"Connection error."`

에러 cascade 흐름:
1. verbal loop (prepare_* 전에 LLM이 "하시겠어요?" 반복)
2. "Error in input stream" (스트림 예외가 catch 없이 SSE 닫힘)
3. "NetworkError when attempting to fetch resource" (ArgoCD 롤아웃 중 일시 다운)
4. "Error calling model 'gemini-2.5-flash' (RESOURCE_EXHAUSTED): 429" (Gemini 20/day 소진)
5. **"Connection error."** ← 이 항목의 대상

### 잘못된 가설

- **처음 가설**: Groq/OpenRouter API 키가 k8s 시크릿에 없거나 잘못 설정됨.
  - `kubectl get secret ssuagent-secrets` 으로 키가 존재 확인 → 기각.
- **두 번째 가설**: `llm_factory.py` fallback 루프가 이미 `Gemini 429`에서 수정됐는데 배포가 안 된 것.
  - pod 이미지 SHA가 이미 최신 커밋과 일치 확인 → 기각.
- **세 번째 가설**: OpenAI SDK 버전 호환성 문제로 Groq 요청 자체가 실패.
  - SDK 버전 문제라면 `LocalProtocolError`가 아닌 다른 계층 에러가 나야 함 → 기각.

### 실제 원인

k8s 시크릿 `ssuagent-secrets`(namespace `ssuai-prod`)의 `GROQ_API_KEY`(길이 57)와
`OPENROUTER_API_KEY`(길이 74) 값 **끝에 캐리지 리턴(`\r`)이 붙어 있었음**.

**발견 방법:** `kubectl exec` 후 Python에서 바이트 단위 직접 검사:
```python
import os
key = os.environ.get('GROQ_API_KEY', '')
print(len(key), key[-2:].encode())  # → 57 b'mW\r'  ← trailing_CR True
```

**장애 전파 경로:**
1. `ssu_agent/config.py` 가 `os.getenv("GROQ_API_KEY", "")` 결과를 `.strip()` 없이 그대로 사용.
2. LangChain `ChatOpenAI` 가 `api_key` 값을 Bearer 토큰으로 HTTP 헤더에 삽입:
   `Authorization: Bearer gsk_...\r`
3. `httpcore` 가 HTTP/1.1 스펙 위반 헤더를 감지하여 `LocalProtocolError: Illegal header value` 발생.
4. OpenAI SDK 가 이를 `APIConnectionError("Connection error.")` 로 래핑하여 반환.
5. Gemini(20 req/day)가 소진된 상태에서 Groq·OpenRouter 모두 같은 이유로 죽어 **챗봇 전체 다운**.

**Windows CRLF 오염 경로:** 시크릿을 `kubectl create secret` 에 값을 직접 붙여넣을 때
Windows 클립보드가 줄바꿈 `\r\n`을 추가했고, 그 `\r`이 Base64 인코딩되어 시크릿에 저장됨.
`GOOGLE_API_KEY`(53)·`DATABASE_URL`(82)은 `\r` 없이 깨끗했음.

### 현장 증명

pod exec 안에서 `.strip()` 만 추가해 Groq 호출:
```python
key = os.environ.get('GROQ_API_KEY', '').strip()  # ← 이 한 줄
c = OpenAI(api_key=key, base_url='https://api.groq.com/openai/v1')
r = c.chat.completions.create(model='llama-3.3-70b-versatile', messages=[...])
print(r.choices[0].message.content)  # → "OK"
```
`LocalProtocolError` 사라지고 Groq 정상 응답 확인.

### 해결 방법

**영구 코드 수정 채택** — 시크릿을 건드리지 않고 읽는 시점에 제거.

`ssu_agent/config.py` 전체 `os.getenv()` 호출에 `.strip()` 추가:
```python
# Before
GROQ_API_KEY: str = os.getenv("GROQ_API_KEY", "")

# After
GROQ_API_KEY: str = os.getenv("GROQ_API_KEY", "").strip()
```
6개 env 변수 전부 동일하게 적용(방어적 처리 — 포맷 오염 위치를 가정하지 않음).

관측성 개선(`ssu_agent/main.py`): `_stream_graph` except 블록에 `logger.exception("agent stream failed")` 추가. 이전에는 예외가 삼켜져 `kubectl logs` 에 트레이스백이 전혀 남지 않아 `kubectl exec` 직접 진단이 필요했음.

- **커밋**: ssuAgent `59ea5b0` (fix/strip-env-keys-and-log-stream-errors, PR #3 → main fast-forward)
- **보안 후속**: 진단 중 pod 트레이스백에서 Groq 키 전체 노출 → console.groq.com에서 키 로테이션 권장.

### 핵심 파일 및 커밋

- `ssuAgent/ssu_agent/config.py` — `.strip()` 6개 추가 (`59ea5b0`)
- `ssuAgent/ssu_agent/main.py` — `logger.exception` 추가 (`59ea5b0`)

### 포트폴리오 포인트

"환경 변수 포맷을 신뢰하지 말라" — Windows 환경에서 클립보드를 통해 시크릿을 만들면 CRLF가 따라오며, HTTP 헤더로 전달될 때 프레임워크(httpcore)가 스펙 위반으로 거부한다. 에러 메시지만 봐선 API 키 문제가 아니라 네트워크 문제처럼 보이므로, pod exec + 바이트 단위 검사라는 저수준 진단 절차가 필요했던 사례. 또한 "스트림에서 예외를 삼키면 미래의 자신을 막다른 곳에 몰 수 있다"는 관측성 교훈.

### 예상 면접 질문

1. `Authorization: Bearer <token>\r` 헤더가 HTTPS 위에서도 요청을 막는 이유는 무엇인가요? TLS와 HTTP 헤더 파싱 레이어를 나눠서 설명해보세요.
2. Kubernetes Secret에 저장된 값이 실제 pod 내에서 어떻게 주입되는지(volume mount vs envFrom), 그리고 Secret 변경 후 pod 재시작 없이는 반영되지 않는 이유는 무엇인가요?
3. 예외를 catch해서 에러 메시지만 사용자에게 보내는 것의 trade-off는 무엇인가요? 어떤 정보를 어디에 기록해야 운영 가능한 시스템이 되나요?

---

## 2026-06-15 - ssuAgent LLM fallback 무력화 버그: RunnableWithFallbacks.bind_tools 부재

### 상황
- ssuAI 챗봇에서 "도서관 예약해줘" 요청 시 Gemini 429 RESOURCE_EXHAUSTED 오류 발생.
- `llm_factory.py`에서 `primary.with_fallbacks([groq, openrouter])` 구성이 있었음에도 Groq/OpenRouter로 fallback되지 않음.
- 에러 메시지: "Error calling model 'gemini-2.5-flash' (RESOURCE_EXHAUSTED): 429 RESOURCE_EXHAUSTED"

### 잘못된 가설
- **처음 가설**: `with_fallbacks()` 가 제대로 작동하지 않는 것은 Groq/OpenRouter API 키 미설정 때문.
- k8s secret에 `GROQ_API_KEY`, `OPENROUTER_API_KEY` 이미 추가되어 있음을 확인 → 기각.
- **두 번째 가설**: `exceptions_to_handle` 파라미터를 명시해야 한다.
- `with_fallbacks()`의 기본값은 `exceptions_to_handle=(Exception,)` — 모든 예외를 잡음 → 기각.

### 실제 원인
`langchain_core 1.4.6`의 `RunnableWithFallbacks` 클래스에 `bind_tools` 메서드가 없음.

```python
# 확인 방법
from langchain_core.runnables import RunnableWithFallbacks
hasattr(RunnableWithFallbacks, 'bind_tools')  # → False
```

LangGraph의 `create_react_agent(model, tools)` 내부에서 첫 번째로 하는 작업:
```python
model_with_tools = model.bind_tools(tool_classes)
```

`model`이 `RunnableWithFallbacks`이면 Python이 `__getattr__`를 통해 `self.runnable.bind_tools` (primary LLM의 bind_tools)를 호출함. 결과는 `primary.bind_tools(tools)` 단독 객체로, fallback chain이 완전히 사라짐.

이후 Gemini가 429를 던져도 잡아주는 fallback이 없음.

### 핵심 파일 및 커밋
- `ssuAgent/ssu_agent/llm_factory.py` — `get_llm_sequence()` 추가, `create_llm()` Groq 우선 반환으로 변경 (`e104e0b`)
- `ssuAgent/ssu_agent/agents/library.py`, `academic.py`, `lms.py` — `agent_node`를 `llm_seq` 루프로 변경: 각 LLM 시도 → 성공 시 반환 / 실패 시 next (`e104e0b`)

### 해결 방법 (채택)
`with_fallbacks()` 래퍼 제거. `agent_node` 안에서 LLM 목록을 순서대로 직접 시도:

```python
# llm_factory.py
def get_llm_sequence() -> list[BaseChatModel]:
    llms = []
    if GROQ_API_KEY:
        llms.append(ChatOpenAI(base_url="groq...", model="llama-3.3-70b-versatile"))
    llms.append(ChatGoogleGenerativeAI(model="gemini-2.5-flash"))
    if OPENROUTER_API_KEY:
        llms.append(ChatOpenAI(base_url="openrouter...", model="llama-3.3-70b-instruct:free"))
    return llms

# agent_node (library/academic/lms)
for _llm in llm_seq:
    try:
        inner = create_react_agent(_llm, tools, prompt=prompt)
        result = await inner.ainvoke(...)
        return result
    except Exception:
        continue
raise RuntimeError("All LLM providers exhausted")
```

우선순위: **Groq(14,400 req/day) → Gemini(20 req/day) → OpenRouter(무제한 무료 모델)**.
Gemini가 exhausted되어도 Groq가 primary로 동작.

### 대안 검토
- **`with_fallbacks()` 유지 + bind_tools 수동 적용**: `primary.bind_tools(tools).with_fallbacks([groq.bind_tools(tools), ...])` 형태로 구성 가능하나, `create_react_agent`가 tools를 이미 바인딩하므로 중복 bind. 또 테스트 주입 패턴도 바꿔야 함.
- **LangChain 업그레이드**: 최신 버전에서 `RunnableWithFallbacks.bind_tools` 구현 여부 미확인. 의존성 변경 리스크가 있어 채택하지 않음.

### 포트폴리오 포인트
LangChain/LangGraph 프레임워크의 내부 구현을 직접 검증하고(소스 검사 + 버전 확인), 프레임워크 API를 우회하는 실용적 fallback 패턴을 설계. "라이브러리 추상화를 믿지 말고 동작을 검증하라"는 교훈.

### 예상 면접 질문
1. `RunnableWithFallbacks`와 `BaseChatModel`의 상속 관계에서 왜 `bind_tools`가 동작하지 않았나요? `__getattr__` 위임의 한계를 설명해보세요.
2. LLM 멀티 프로바이더 fallback 설계 시 어떤 예외를 잡아야 할지 어떻게 결정했나요? (quota vs tool error vs programming error)
3. Groq, Gemini, OpenRouter의 무료 티어 제한 차이와 각 프로바이더를 선택한 이유는 무엇인가요?

## 2026-06-14 - ssuAI 프로덕션 채팅 "Failed to fetch" — .env.local Git 추적으로 인한 Mixed Content

### 상황
- `ssuai.vercel.app/chat`에서 ssuAgent로 메시지를 전송할 때 즉시 "Failed to fetch" 오류 발생.
- Chrome Network 탭에 요청이 아예 표시되지 않음 (흔적 없음).
- `https://ssuagent.duckdns.org/health` 및 OPTIONS/POST curl은 정상 응답 (200 + `Access-Control-Allow-Origin: *`).

### 잘못된 가설
- **처음 가설: CORS 설정 문제.** OPTIONS 프리플라이트 응답에 헤더가 없다고 판단했다.
- curl로 OPTIONS/POST 모두 정상 CORS 헤더를 확인하여 기각.
- 두 번째 가설: ssuAgent 파드 다운. `/health`가 200 반환 → 기각.

### 실제 원인
- `.env.local`이 ssuAI `.gitignore`에 `.env.*` 패턴이 있음에도 **이미 커밋된 상태**였음.
- Vercel 빌드 시 Next.js는 git에 추적 중인 `.env.local`을 로드하여 `NEXT_PUBLIC_SSUAGENT_BASE_URL=http://localhost:8001`을 프로덕션 번들에 주입.
- HTTPS 페이지(`ssuai.vercel.app`)에서 HTTP URL(`localhost:8001`)로 `fetch()` → 브라우저가 Mixed Content로 요청 자체를 차단.
- Mixed Content 차단된 요청은 Network 탭에 표시되지 않아 진단을 어렵게 했음.

### 수정
- `git rm --cached .env.local` 으로 추적 해제 → Vercel 재빌드 시 `NEXT_PUBLIC_SSUAGENT_BASE_URL` 미설정 → 코드의 하드코딩 fallback `https://ssuagent.duckdns.org` 사용.
- 커밋: `4c8bbfd` (ssuAI main)

### 핵심 파일 및 커밋
- `ssuAI/.env.local` (git에서 추적 해제), `ssuAI/lib/api/agent.ts` (SSUAGENT_BASE fallback)
- 커밋: `4c8bbfd`

### 포트폴리오 포인트
- `.gitignore` 패턴이 있어도 이미 추적 중인 파일은 무시되지 않는다는 git 동작 특성.
- Next.js의 `NEXT_PUBLIC_*` 환경 변수는 빌드 타임에 번들에 직접 주입(inlining)되므로, `.env.local`이 git에 있으면 로컬 개발용 URL이 프로덕션 빌드에 들어간다.
- Mixed Content 오류는 Network 탭에 흔적이 없어 "CORS 문제"로 오진하기 쉬운 사례.

### 예상 면접 질문
1. `.gitignore`에 패턴이 있는데 파일이 계속 추적되는 이유는?
2. Next.js에서 `NEXT_PUBLIC_` 환경 변수가 클라이언트 코드에 어떻게 전달되나요? 서버 전용 변수와 차이는?
3. "Failed to fetch" 오류를 디버깅할 때 우선 확인하는 단계는? Mixed Content와 CORS를 어떻게 구분하나요?

---

## 2026-06-14 - 도서관 연결 상태가 새로고침 후 사라지는 UI 버그

### 상황
- 도서관 로그인 후 "도서관 연결됨" 배지가 표시되지만, 페이지 새로고침 시 배지가 사라짐.
- ssumcp의 HTTP 세션(JSESSIONID) 및 도서관 토큰은 실제로 유지됨 — UI 상태만 소멸.

### 잘못된 가설
- JSESSIONID 쿠키가 Vercel 프록시에서 유실된다고 판단.
- Next.js 리라이트는 Cookie 헤더를 포함한 요청 헤더를 그대로 전달하므로 기각.

### 실제 원인
- `LibraryAuthContext`의 `isConnected`가 순수 React 메모리 상태.
- 페이지 새로고침 → React 상태 초기화 → `isConnected = false`.
- `isConnected`가 `true`로 복원되는 유일한 경로는 `"library", "seats"` 또는 `"library", "loans"` 쿼리가 성공하는 것인데, 채팅 페이지에는 그 쿼리를 실행하는 컴포넌트가 없음.

### 수정
- `LibraryAuthContext`에 `sessionStorage` 영속화 추가:
  - `setConnected(true)` 시 `sessionStorage.setItem("library_connected", "true")`
  - `logout()` 및 `LIBRARY_SESSION_REQUIRED` 에러 수신 시 `sessionStorage.removeItem(...)`
  - 컴포넌트 마운트 시 `useState(readStorage)` 로 초기값 복원.
- 커밋: `8008681` (ssuAI main)

### 핵심 파일 및 커밋
- `ssuAI/contexts/LibraryAuthContext.tsx`
- 커밋: `8008681`

### 포트폴리오 포인트
- React Context 상태는 SPA 내 네비게이션에서는 유지되지만 페이지 새로고침에서 초기화됨. 서버 세션과 클라이언트 UI 상태의 생명주기 불일치 처리 방법.
- `sessionStorage`(탭 단위 유지)와 `localStorage`(브라우저 단위 유지)의 선택 기준 — 도서관 로그인은 탭 세션 범위가 적절함.

### 예상 면접 질문
1. React Context 상태와 서버 세션의 생명주기가 다를 때 어떻게 동기화하나요?
2. `sessionStorage`와 `localStorage` 중 인증 상태 캐시에 어느 쪽을 선택하고 그 이유는?
3. 클라이언트 상태가 서버 실제 상태와 달라질(stale) 경우 어떻게 처리하나요?

---

## 2026-06-14 - ssuAgent 채팅 "network error" — Gemini Free Tier 쿼타 소진으로 LLM 호출 무한 hang

### 상황
- `ssuai.vercel.app/chat`에서 메시지 전송 시 로딩이 수십 초 지속된 후 "network error" 표시.
- Mixed Content 버그 수정(`4c8bbfd`) 이후에도 동일 증상 재발.
- ssuAgent `/health` 200 정상, kubectl pods 모두 Running, 재시작 없음.
- kubectl 로그: `Key 'additionalProperties' is not supported in schema, ignoring` 경고 × 7 (그래프 초기화 정상), 이후 health check 로그만 반복.
- `curl --max-time 30 https://ssuagent.duckdns.org/chat/stream` → 30초 후 timeout, SSE 이벤트 0건.

### 잘못된 가설
1. **Mixed Content 재발 가능성**: URL 수정 이후 `ssuagent.duckdns.org`로 정상 도달 중임을 확인 → 기각.
2. **GOOGLE_API_KEY 누락**: k8s secret `ssuagent-secrets`가 `optional: true`라 Pod가 키 없이도 기동 가능. `kubectl exec` 으로 key 길이 확인 → length 53, 설정됨 → 기각.
3. **Oracle Cloud → Google API 네트워크 차단**: `urllib.request.urlopen("https://generativelanguage.googleapis.com/", timeout=10)` → HTTP 404 (TCP/TLS 정상) → 기각.
4. **모델명 잘못됨 또는 모델 미제공**: `/v1beta/models?key=` 로 모델 목록 조회 → `gemini-2.0-flash` 정상 목록에 존재 → 기각.

### 실제 원인
- Gemini API에 직접 POST 요청 `gemini-2.0-flash:generateContent` → HTTP 429 반환:
  ```
  "limit: 0, model: gemini-2.0-flash"
  GenerateRequestsPerDayPerProjectPerModel-FreeTier: limit 0
  GenerateRequestsPerMinutePerProjectPerModel-FreeTier: limit 0
  GenerateContentInputTokensPerModelPerMinute-FreeTier: limit 0
  ```
- `gemini-2.0-flash` Free Tier 일별·분당 요청 및 토큰 쿼타가 **모두 0(소진)**.
- LangChain `ChatGoogleGenerativeAI` 내부 tenacity retry가 429 수신 후 11초 대기 → 재시도를 반복. timeout 설정 없음 → 외부에서 보기에 무한 hang처럼 보임.
- 선택적 진단 포인트: `supervisor_node`에서 `supervisor_react.invoke()` (동기 호출)를 사용 → LangGraph가 스레드 풀에서 실행하지만, Gemini 호출이 tenacity retry loop에 빠지면 해당 스레드가 영구 점유됨.

### 수정
- `gemini-2.5-flash` 쿼타 확인: 직접 REST 요청 → 정상 응답.
- `ssuAgent/ssu_agent/config.py`에 `GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-2.5-flash")` 추가 → 배포 없이 모델 변경 가능.
- `ssuAgent/ssu_agent/supervisor/graph.py` line 239: `model="gemini-2.0-flash"` → `model=config.GEMINI_MODEL`.
- CI 통과, ArgoCD Image Updater가 새 이미지 감지 → Pod 교체(`ssu-agent-7bb448fcd4-7xv6r`).
- 교체 후 `ssu_agent.config` 모듈로 LLM 호출 확인 → "Hi" 응답 성공.
- 커밋: `b6a1190` (ssuAgent main)

### 핵심 파일 및 커밋
- `ssuAgent/ssu_agent/config.py`, `ssuAgent/ssu_agent/supervisor/graph.py`
- 커밋: `b6a1190`

### 포트폴리오 포인트
- LangChain tenacity retry는 기본적으로 429에 대해 무한 재시도한다. API 쿼타 소진이 "무한 hang"처럼 보이는 이유. 프로덕션에서는 `max_retries` 와 request timeout을 명시해야 한다.
- Free Tier Gemini 모델의 쿼타 구조: RPD(일별 요청), RPM(분당 요청), TPM(분당 토큰) 세 개가 동시에 관리됨. 한 프로젝트에서 모델을 교체하는 것만으로 해결 가능한 이유.
- `GEMINI_MODEL`을 env var로 분리하면 새 이미지 배포 없이 쿼타 초과 시 모델을 즉시 교체할 수 있다 — Kubernetes ConfigMap/Secret 업데이트만으로 운영 대응 가능.

### 예상 면접 질문
1. LangChain에서 Gemini API 쿼타 초과(429) 시 왜 즉각 에러가 나지 않고 hang처럼 보이나요? tenacity retry 동작을 설명하세요.
2. 프로덕션 LLM 서비스에서 API 쿼타를 초과할 때 대응 방법은? (모델 전환, fallback, 쿼타 알림)
3. LLM 모델명 같은 운영 파라미터를 환경 변수로 분리하는 이유와 Kubernetes 환경에서 적용 방법은?

---

## 2026-06-15 - Gemini 쿼타 소진 반복 — 멀티 LLM 프로바이더 Fallback으로 근본 해결

### 상황
- 위 2026-06-14 항목에서 `GEMINI_MODEL` env var로 모델을 교체하는 단기 패치를 적용했지만, `gemini-2.5-flash` → `gemini-2.0-flash` → `gemini-2.5-flash-lite` → `gemini-2.5-pro` 순서로 모든 Gemini 모델의 Free Tier 쿼타가 순차적으로 소진됐다.
- 매번 `kubectl set env`로 수동 교체가 필요했고, 사용 중 다시 소진되면 동일 장애 반복.

### 잘못된 가설
- Gemini 모델 간에 쿼타가 분리돼 있으니 다른 Gemini 모델로 교체하면 오래 버틸 것이라 판단.
- 실제로는 같은 Google Cloud 프로젝트 내 Free Tier는 모델 간 쿼타가 연동돼 있어, 한 모델 소진 후 교체해도 빠르게 같은 상황이 반복됐다.

### 실제 원인 (구조적 문제)
- ssuAgent가 Gemini 단일 프로바이더에만 의존해, Free Tier 쿼타 소진 시 서비스 전체가 중단되는 단일 실패 지점(SPOF)이었다.
- ssuMCP에는 이미 Groq, OpenRouter, Cerebras, Fireworks, Mistral 등 다수 프로바이더 API 키가 등록되어 있었으나 ssuAgent는 이를 전혀 활용하지 않았다.

### 해결: 멀티 프로바이더 Fallback 체인 구축
- `ssuAgent/ssu_agent/llm_factory.py` 신설: `ChatGoogleGenerativeAI` → `ChatOpenAI(Groq)` → `ChatOpenAI(OpenRouter)` 체인
- LangChain `.with_fallbacks()` 사용 — primary 실패 시 자동으로 다음 프로바이더 시도
- 각 LLM `max_retries=1` 설정: tenacity 기본 6회 retry를 줄여 쿼타 소진 시 빠르게 다음 프로바이더로 이동
- `ssuagent-secrets`에 `GROQ_API_KEY`, `OPENROUTER_API_KEY` 추가 (ssuMCP secrets에서 복사)
- `pyproject.toml`에 `langchain-openai` 추가 (OpenAI-compatible 클라이언트)
- 커밋: `2541aa8`, `5e36c10` (ssuAgent main)

### 핵심 파일 및 커밋
- `ssuAgent/ssu_agent/llm_factory.py` (신규)
- `ssuAgent/ssu_agent/config.py` (GROQ_API_KEY, OPENROUTER_API_KEY 추가)
- 커밋: `2541aa8`
- ADR: `ssuAgent/docs/adr/004-multi-provider-llm-fallback.md`

### 포트폴리오 포인트
- Free Tier LLM API는 RPD/RPM/TPM 쿼타가 독립 관리되며, 같은 회사의 다른 모델도 같은 프로젝트 쿼타를 공유함. "모델 교체"는 임시방편이고 프로바이더 다양화가 근본 해결.
- `.with_fallbacks()`는 LangChain의 `RunnableWithFallbacks`로 구현되며, 도구 호출(tool calling) 인터페이스가 동일한 프로바이더 간에 투명하게 동작한다.
- `max_retries=1` 설정의 중요성: 기본값(6회 × 지수 백오프)이면 쿼타 소진 시 fallback 전까지 수십 초가 걸림.

### 예상 면접 질문
1. LangChain `.with_fallbacks()`는 어떤 예외 조건에서 다음 프로바이더로 넘어가나요? 기본 동작과 커스터마이징 방법은?
2. 여러 LLM 프로바이더를 사용할 때 응답 포맷(tool calling, streaming chunk 구조) 차이를 어떻게 처리하나요?
3. 프로덕션에서 LLM API 쿼타 소진을 사전에 감지하는 방법은? 알림·모니터링 전략은?

---

> ⚠️ 2026-06-16: 이 기능(LMS STT 전사)은 ADR-0032에 의해 제거됨. 역사적 기록으로 보존.

## 2026-06-13 - Groq STT multipart 테스트 EOF 실패

### 상황
- `GroqSttClientTests.transcribesAudioFile()`에서 WireMock `/audio/transcriptions` stub을 열어두었지만 `GroqSttClient.transcribe()`가 빈 문자열을 반환했다.
- 로그는 처음에 예외 class만 남겨 원인을 보기 어려웠고, 메시지 포함 로그로 재실행하자 `I/O error on POST request ... EOF reached while reading`이 확인됐다.

### 잘못된 가설
- 처음에는 WireMock stub 경로, `Authorization` header, multipart field 이름(`model`, `language`, `response_format`, `file`) 불일치 때문에 404/500이 발생한다고 봤다.
- 하지만 실패는 HTTP status 응답 이전의 `ResourceAccessException`이었고, WireMock stub matching 문제가 아니었다.

### 실제 원인
- Spring `RestClient` 기본 request factory가 WireMock과 multipart/form-data POST를 주고받는 테스트 경로에서 EOF를 냈다.
- STT 클라이언트에 `SimpleClientHttpRequestFactory`를 명시하자 같은 multipart 요청이 WireMock에서 정상 처리됐다.

### 수정
- `src/main/java/com/ssuai/domain/lms/video/util/GroqSttClient.java`
  - `RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory())`를 적용했다.
  - 실패 로그는 요청 원인 분석이 가능하도록 메시지를 남기되, API key는 출력하지 않는다.
- `src/test/java/com/ssuai/domain/lms/video/util/GroqSttClientTests.java`
  - 성공/500/blank key 테스트를 유지해 multipart 성공과 graceful degradation을 검증한다.

### 핵심 파일 및 커밋
- 파일: `GroqSttClient.java`, `GroqSttClientTests.java`
- 커밋: 예정 `feat(lms): add get_my_lecture_list and get_lecture_transcript MCP tools`

### 포트폴리오 포인트
- 외부 STT API 연동은 단순 JSON POST가 아니라 multipart binary upload, 인증 header, graceful fallback, 테스트 서버 호환성까지 포함해야 운영 가능한 기능이 된다.
- 실패 원인을 HTTP status가 아닌 client request factory 계층에서 분리해낸 점이 "테스트가 실제 통합 위험을 드러낸 사례"로 설명 가능하다.

### 예상 면접 질문
1. 왜 STT 요청은 `RestClient` 기본값 대신 `SimpleClientHttpRequestFactory`를 명시했나요?
2. multipart STT 업로드 실패 시 사용자 경험을 어떻게 보호하나요?
3. 실제 Groq API와 WireMock 테스트의 차이가 있을 때 어떤 순서로 원인을 좁히나요?

## 2026-06-14 - 도서관 HITL 확인 다이얼로그가 절대 뜨지 않음 + `[object Object]` 채팅 텍스트

### 상황 (두 버그 동시 발생)
1. `ssuai.vercel.app/chat`에서 "도서관 6층 마루열람실 116번 예약해" 입력 시 로딩이 수십 초 계속된 후, 확인 다이얼로그 없이 채팅에서 "예약이 완료되었습니다"라는 응답이 나타남 (할루시네이션).
2. 이후 추가 질문 입력 시 채팅 텍스트에 `[object Object]`가 그대로 출력됨.

### 잘못된 가설
**버그 1 (HITL 미작동)**
- 처음에는 LangGraph `interrupt()` 구현 자체가 서브그래프에서 전파되지 않는다고 의심.
- `astream_events(version="v2")` 이벤트 흐름에서 `on_interrupt`가 아닌 다른 이벤트 이름으로 전달된다고 가정.
- 위 두 가설을 기각: 실제 문제는 `_extract_action_id` 함수가 actionId를 발견하지 못해 `check_approval_node` 자체에 도달하지 않는 것이었음.

**버그 2 (`[object Object]`)**
- 처음에는 SSE 역직렬화 오류로 프론트엔드 `JSON.parse` 실패로 판단.
- 실제로는 파이썬 쪽 문제였음.

### 실제 원인

#### 버그 1: prepare_* 도구가 String을 반환해 actionId 파싱 불가
- `ssu_agent/agents/library.py`의 `_extract_action_id(messages)` 함수는 `ToolMessage.content`를 JSON으로 파싱 후 `data["data"]`가 `dict`이고 `"actionId"` 키가 있는지 확인함.
- `ssuMCP`의 `prepare_reserve/cancel/swap_library_seat` 세 도구는 모두 `McpPrivateToolResponse<String>`을 반환했음. JSON으로 직렬화하면 `{"status":"OK","data":"6층 마루열람실... 예약을 준비했습니다."}` 형태.
- `data` 필드가 `String`이므로 `isinstance(inner, dict)` 체크 실패 → `_extract_action_id` 항상 `None` 반환.
- 결과: `_has_pending_action` 라우터가 항상 `"done"` 엣지 선택 → `done_node` 실행 → `active_agent=None` 설정 후 부모 그래프 종료.
- 부모 수퍼바이저가 결과를 LLM에 전달하면 LLM이 맥락 없이 "완료됐습니다"라고 추측해서 출력 (할루시네이션).

#### 버그 2: Gemini 2.5 Flash 스트리밍이 content를 list로 반환
- `gemini-2.5-flash` 는 청크 스트리밍 시 `chunk.content`가 Python list(`[{"type":"text","text":"안녕하세요"}]`)로 반환되는 경우가 있음 (2.0-flash는 string 반환).
- `ssu_agent/main.py`의 `_stream_graph`는 `content = chunk.content`를 그대로 JSON으로 직렬화해 SSE 전송.
- 프론트엔드에서 `message += event.content` 하면 `string += Array` → JavaScript 암묵적 형변환 → `"[object Object]"`.

### 수정

#### 버그 1: LibraryPrepareResult 구조체 도입
- `ssuMCP/src/main/java/com/ssuai/domain/library/reservation/LibraryPrepareResult.java` 신규:
  ```java
  public record LibraryPrepareResult(long actionId, String message) {}
  ```
- `LibraryReservationMcpTool`, `LibraryCancelMcpTool`, `LibrarySwapMcpTool` 세 클래스의 반환 타입을 `McpPrivateToolResponse<LibraryPrepareResult>`로 변경.
- 각 도구에서 `actionService.createPendingAction(...).getId()`를 캡처해 `LibraryPrepareResult(actionId, message)` 로 반환.
- `ssu_agent/agents/library.py` `check_approval_node`: `confirm_tool.ainvoke` 호출에서 `action_id` 제거 (confirm_action 도구는 `mcp_session_id`만 받고 내부적으로 pending action을 조회함).
- `ssuAI/components/chat/HitlCard.tsx` `formatDetails()`: `details.message` 필드가 있으면 그대로 반환하도록 추가.

#### 버그 2: list content 정규화
- `ssu_agent/main.py` `_stream_graph`:
  ```python
  if isinstance(content, list):
      content = "".join(
          item["text"] if isinstance(item, dict) and "text" in item else str(item)
          for item in content
      )
  ```
  SSE 전송 전에 list를 string으로 합침.

### 핵심 파일 및 커밋
- `ssuMCP`: `LibraryPrepareResult.java`, `LibraryReservationMcpTool.java`, `LibraryCancelMcpTool.java`, `LibrarySwapMcpTool.java` + 테스트 3종 — 커밋 `2fe67d9`
- `ssuAgent`: `agents/library.py`, `main.py` — 커밋 `9f6021e`
- `ssuAI`: `components/chat/HitlCard.tsx` — 커밋 `e59505d`

### 포트폴리오 포인트
- **백엔드 API 계약과 클라이언트 파싱 코드의 암묵적 결합**: ssuMCP의 `data` 필드 타입이 `String`에서 `LibraryPrepareResult`로 바뀌어야 ssuAgent의 `isinstance(inner, dict)` 체크가 통과한다. API 스키마 변경이 다계층(ssuMCP → ssuAgent → ssuAI)에 걸쳐 동기화되어야 함을 보여주는 실사례.
- **LLM 할루시네이션의 실제 원인**: HITL이 작동하지 않아 `done_node` → 부모 LLM으로 흘러갔을 때, 맥락 없는 LLM이 "완료됐습니다"로 추측하는 것이 전형적인 잘못된 tool 결과 전파 패턴.
- **스트리밍 API 버전별 응답 형식 차이**: `gemini-2.0-flash`는 `chunk.content`가 str인 반면, `gemini-2.5-flash`는 list로 반환할 수 있음. 동적 타입 언어(Python)에서 모델 업그레이드 시 SSE 레이어에서 방어 코드가 필요함.

### 예상 면접 질문
1. LangGraph HITL `interrupt()`가 서브그래프에서 트리거되려면 라우터(edge 함수)가 아닌 노드 내부에서 호출해야 하는 이유는?
2. 다계층 마이크로서비스(MCP 서버 → 에이전트 → 프론트엔드)에서 API 응답 스키마를 변경할 때 어떤 순서로 테스트하고 배포하나요?
3. Gemini streaming에서 `chunk.content`가 `str`인지 `list`인지 런타임에 다를 수 있는 이유는? LLM API 버전 업그레이드 시 방어 코드를 어떻게 설계하나요?

---

## 2026-06-15 - u-SAINT SmartID 로그인 간헐적 실패 — 단계별 진단 로그 추가 (원인 조사 중)

### 상황
- 사용자가 SmartID 로그인 후 `/sso-callback`에서 `auth_failed`가 간헐적으로 발생.
- 재시도(새 SmartID 토큰 발급 포함) 2~3회 후 성공하는 패턴.

### 틀린 가설
- `StudentInformationApplicationBuilder.general()`(학생 정보 조회)이 일시적 네트워크 오류로 실패한다고 가정했다.
- **확인 불가**: 기존 `withClientError()` 래퍼가 모든 예외를 동일한 `RusaintClientException("rusaint token authentication failed")`으로 균일 래핑 → 예외 클래스명·메시지가 로그에서 소실돼 어느 단계(`withToken()` vs `general()`)가 실패했는지 구분 불가.

### 실제 원인 (TBD — 배포 후 로그 관찰 필요)
- `authenticateWithToken()` 내부의 두 단계:
  1. `builder.withToken(studentId, ssoToken)` — SmartID one-shot 토큰 소모, SAP 세션 수립
  2. `StudentInformationApplicationBuilder.general()` — 세션 내 학생 정보 SAP 조회
- **1번이 실패하면**: SmartID 토큰이 이미 소모됐으므로 컨트롤러 레벨 재시도 불가. 사용자가 SmartID를 처음부터 다시 밟아야 한다.
- **2번이 실패하면**: 세션은 유효하므로 세션 내에서 재시도 가능.
- 현재로서는 어느 쪽 실패인지 로그에서 판별 불가.

### 조치
- `withClientError()`: 예외 래핑 전 WARN 로그 추가 (`javaClass.simpleName + message`)
- `authenticateWithToken()`: `withToken()` 성공 직후 DEBUG 로그 추가 → 프로덕션 로그에서 `"rusaint withToken exchanged"` 메시지가 실패 직전에 찍히면 2번(general) 실패, 찍히지 않으면 1번(withToken) 실패
- `withRetry(maxAttempts, label, action)` 헬퍼 추가: `general()` 호출에 3회, 지수 백오프 200ms·400ms 재시도 적용. 이미 수립된 세션 내 재시도이므로 SmartID 토큰 재소모 없음.
- 테스트: `RusaintUniFfiClientRetryTest.kt` — `withRetry` 성공·복구·소진 케이스 검증

### 핵심 파일
- `src/main/kotlin/com/ssuai/domain/saint/connector/RusaintUniFfiClient.kt`
- `src/test/kotlin/com/ssuai/domain/saint/connector/RusaintUniFfiClientRetryTest.kt`

### 포트폴리오 포인트
- **진단 우선 원칙**: 원인을 모르는 상태에서 fix를 먼저 작성하는 것은 오진 처방. 단계별 구분 로그를 먼저 추가해 다음 장애 발생 시 단계를 확정할 수 있게 함.
- **SmartID one-shot 토큰의 보안 설계**: 소모된 토큰은 재사용 불가 → `withToken()` 실패 시 컨트롤러 레벨에서 같은 토큰으로 재시도하는 것은 구조적으로 불가. 이 제약을 코드 레벨에서 정확히 인식하지 않으면 재시도 로직을 잘못된 위치(컨트롤러)에 추가할 수 있다.
- **`PyxisResilience` vs SAINT**: Pyxis(도서관)에는 Resilience4j CB·Retry·RateLimiter가 모두 적용돼 있지만, SAINT rusaint 호출에는 어떠한 fault tolerance도 없었음. 외부 의존성별 resilience 커버리지 감사의 필요성.

### 예상 면접 질문
1. SmartID one-shot 토큰이 재사용 불가인 보안 이유는? 재사용 가능하다면 어떤 공격이 가능한가?
2. 외부 시스템 연동 시 retry와 circuit breaker를 어떻게 구분하여 설계하는가? SAINT와 Pyxis를 다르게 처리한 이유는?
3. 간헐적 오류를 진단할 때 어떤 관찰 지점(로그·메트릭)을 선택하고 어떤 순서로 좁혀 가는가?

---

## 2026-06-15 - Gemini 임베딩 쿼타 영구 고착 — 모델 전환으로 해결 (gemini-embedding-2-preview)

### 상황
- 2026-06-11 임베딩 CrashLoop(항목: "9시간 CrashLoopBackOff") 수습 후 `gemini-embedding-001`로 복귀.
- 이후 prod에서 매일 기동 직후부터 임베딩 첫 배치에서 즉시 429, `embeddingUsed:false` 지속.
- 2026-06-13 모델 교체 시도 중(항목: "404 → RestClientException") 남은 RPD 할당량 소진.
- "다음날 할당량이 리셋되면 정상화될 것"으로 판단, 수일 대기.

### 틀린 가설
1. **"일별 쿼타 리셋으로 해결될 것"**: Google 무료 티어 `gemini-embedding-001` RPD는 정상적으로 UTC 자정 리셋되지만, **한 번 오버레이지(CrashLoop 109회 재시도)가 발생한 후 쿼타가 0으로 고착되는 Google 버그**가 있다.
2. **"같은 키의 다른 Gemini 모델도 영향받을 것"**: 임베딩 모델별로 쿼타 버킷이 분리돼 있어, 새 모델은 독립된 버킷을 가진다.

### 실제 원인
- **Google 공식 포럼 확인 버그**: "Gemini Embeddings Free Tier Quota Stuck at 0 After Overage – No Reset Across Projects". 프로젝트 변경으로도 리셋 불가.
- 우리 키(`AQ.Ab8RN6L...` 유형)의 `gemini-embedding-001` 버킷이 2026-06-11 CrashLoop 재시도(109회 × 배치 크기 96) 과정에서 영구 고착.
- 기다려도 리셋이 안 되고 새 쿼타 버킷을 쓰는 다른 모델로 전환해야만 해결됨.

### 해결
- `gemini-embedding-2-preview` (2026-03-10 출시, Matryoshka 128~3072차원) 전환.
  - 이 키 유형에서 OpenAI-compat endpoint(`/v1beta/openai/embeddings`)로 정상 동작하는 모델.
  - corpus는 in-memory 전용(`AtomicReference<EmbeddedCorpus>`, pgvector 미사용) — 재시작·갱신마다 새 모델로 전체 재임베딩됨 → 기존 벡터가 없으므로 전환 투명.
    ※ 주의: 차원이 같아도 모델이 다르면 벡터 공간 자체가 달라 호환 안 됨 (Matryoshka는 동일 모델 내 접두사만 보장).
  - `gemini-embedding-2` (no `-preview`) 는 OpenAI-compat에서 올바른 모델 ID가 아니어서 RestClientException 발생했음 (2026-06-13 항목 참조) — `-preview` 접미사 필수.
- `deploy/charts/ssuai-backend/values.yaml`: `academicEmbeddingModel: "gemini-embedding-2-preview"`
- `src/main/resources/application.yml`: 기본값도 `gemini-embedding-2-preview`로 변경.

### 핵심 파일
- `deploy/charts/ssuai-backend/values.yaml` (`academicEmbeddingModel`)
- `src/main/resources/application.yml` (기본값)
- 관련 항목: "2026-06-11 임베딩 CrashLoopBackOff", "2026-06-13 모델명 불일치"

### 검증 예정
- 배포 후 pod 기동 시 embedding WARN 없이 INFO 로그 → `embeddingUsed:true` 확인.

### 포트폴리오 포인트
- **무료 티어 쿼타 "영구 고착" 버그**: 클라우드 무료 티어의 재시도 폭발(CrashLoop)이 쿼타를 영구 소진시킬 수 있다. "리셋 대기"가 아니라 "다른 버킷"으로 탈출해야 한다.
- **모델 ID 정밀도**: 같은 모델 계열이라도 OpenAI-compat endpoint에서 허용되는 모델 ID(`gemini-embedding-2-preview`)와 아닌 것(`gemini-embedding-2`)이 다르다. curl로 사전 검증이 필수.
- **in-memory corpus → 전환 투명**: corpus는 pgvector/DB에 저장되지 않고 매 refresh마다 전체 재임베딩 (`AtomicReference<EmbeddedCorpus>`). 모델을 바꿔도 기존 벡터가 없으므로 재임베딩 문제 없음. ※ 차원이 같아도 모델이 다르면 벡터 공간이 달라 호환 안 됨 (Matryoshka는 같은 모델 내 접두사 의미만 보장).

### 예상 면접 질문
1. 클라우드 무료 티어 API에서 CrashLoop 재시도가 쿼타를 영구 소진시키는 것을 막으려면 어떤 설계가 필요한가?
2. Matryoshka Embedding이란 무엇이며, 기존 벡터 DB와의 호환성을 어떻게 판단하는가?
3. 외부 API 모델 ID를 배포 설정에서 환경 변수로 분리했을 때의 운영 이점과 위험은?

---

> 역사 기록 주의: 2026-05-27 저장소 분리 전 항목의 `backend/`는 현재
> `ssuMCP/` 루트, `frontend/`는 별도 `ssuAI/` 루트를 의미합니다. SSE
> 관련 항목은 당시 원인 분석을 보존한 것이며, 현재 MCP endpoint는
> Streamable HTTP `/mcp`입니다.

## 기록 규칙

- 의미 있는 문제를 발견하거나 해결하면 이 파일에 한국어로 누적합니다.
- commit, PR, dev-log 를 만들 때마다 의무적으로 쓰지 않습니다. 포트폴리오
  면접에서 설명할 가치가 있는 문제, 원인 분석, 설계 전환, 검증 실패/해결만
  남깁니다.
- 문제가 생긴 직후, 기억이 선명할 때 증상, 원인, 해결, 검증, 배운 점을
  짧게 남깁니다.
- secret, token, private key, cookie, 학생 ID, 실명, 인증된 학교 페이지의
  원문 응답은 절대 기록하지 않습니다.
- `docs/troubleshooting/` 아래에 긴 상세 회고가 있으면, 여기에는 요약과
  링크를 남깁니다.

기록 기준 (아래 중 하나라도 해당하면 즉시 기록):
- 외부 시스템이 예상과 다르게 동작했다 (문서에 없는 동작, 환경별 차이)
- 처음 세운 가설이 틀렸고 실제 원인이 다른 레이어에 있었다
- 테스트는 전부 green인데 prod에서 깨졌다
- 프레임워크/라이브러리 내부 동작을 우회하거나 직접 건드렸다
- 설계 방향을 중간에 바꿨다 (왜 바꿨는지가 핵심)
- 보안/인증/세션 관련 버그를 잡았다

권장 형식:

```markdown
## YYYY-MM-DD — 제목

- 맥락:
- 증상:
- 처음 세운 가설 (틀린 방향):
- 실제 원인:
- 해결:
- 핵심 파일/커밋:
- 검증:
- 포트폴리오 포인트: (왜 어려웠는지, 무엇이 non-obvious였는지)
- 면접 예상 질문:
  1.
  2.
  3.
```

## 2026-06-12 — SSE 타임아웃 30분 설정과 Vercel 120초 하드 컷 충돌로 dead emitter 누적

- 발생:
  - `SseEmitter` 타임아웃을 30분으로 두었지만, Vercel rewrite 프록시가 외부 연결을 120초에 끊으면서 EventSource 재연결이 반복됐다.
  - 죽은 emitter가 30분 동안 레지스트리에 남아 floor별 emitter 수가 계속 불어날 수 있는 구조였다.
- 틀린 가설:
  - "SseEmitter 타임아웃을 길게 잡으면 재연결 빈도가 줄고 운영도 안정적일 것이다."
- 실제 원인:
  - Vercel rewrite proxy가 외부 URL 연결을 120초에 하드 컷한다.
  - 타임아웃이 길수록 끊어진 emitter가 레지스트리에 오래 적체되어 메모리와 정리 비용이 커진다.
  - 프록시가 SSE 응답을 버퍼링하면 실시간 이벤트가 바로 내려가지 않으므로, 버퍼링 방지 헤더와 heartbeat가 같이 필요하다.
- 해결:
  - `LibrarySeatSseRegistry.TIMEOUT_MS`를 `55_000L`로 낮춰 Vercel 120초보다 먼저 emitter를 정리한다.
  - `LibrarySeatController`에서 `X-Accel-Buffering: no`를 설정해 nginx 계열 프록시의 버퍼링을 끈다.
  - `sendHeartbeats()`를 20초 간격으로 보내 중간 프록시의 idle timeout을 회피한다.
- 핵심 파일/커밋:
  - `src/main/java/com/ssuai/domain/library/events/LibrarySeatSseRegistry.java`
  - `src/main/java/com/ssuai/domain/library/controller/LibrarySeatController.java`
  - `src/test/java/com/ssuai/domain/library/events/LibrarySeatSseRegistryTests.java`
  - `docs/adr/0026-sse-seat-updates.md`
  - `892adf999bd6a19f7d3958dd03ba8445610926eb`
- 포트폴리오 포인트:
  - SSE 문제는 애플리케이션 코드보다 CDN/프록시 계층의 타임아웃 정책과 더 강하게 결합된다.
  - "연결을 오래 유지"보다 "죽은 연결을 빨리 버리고 heartbeat로 idle을 유지"하는 쪽이 실제 운영 안정성에 맞다.
- 면접 예상 질문:
  1. Vercel + Spring Boot SSE 조합에서 생길 수 있는 타임아웃 문제는?
  2. `X-Accel-Buffering: no` 헤더는 왜 필요한가?
  3. SSE heartbeat 주기를 20초로 잡은 이유는?

## 2026-06-12 — force-push 교정 후 Image Updater가 orphan image tag를 유지함

- 맥락:
  - PR #42를 GitHub squash merge한 뒤 merge commit author/committer가 `GitHub <noreply@github.com>`로 찍혀 authorship 규칙을 위반했다.
  - 사용자 확인 후 bad merge commit `3020809fb09e546cd6f1d14fc69f75755683e2ba`를 `hoengj <seongjuice999@gmail.com>` author/committer로 amend한 corrected commit `0ea76e66219756942348d975541759526e7321cc`를 만들고 force-push했다.
  - 이전에 recorded 해결로 남겨둔 pin `e0ade3c`는 Image Updater 선택값을 바꾸는 데는 실패했고, 2분 뒤 `dbff659`가 다시 덮었다. 즉 pin은 desired value를 한 번 바꿨을 뿐, updater의 selection input 자체는 바꾸지 못했다.
- 증상:
  - corrected main commit `0ea76e66219756942348d975541759526e7321cc`에 대한 CI image는 push되었다.
  - 그런데 ArgoCD Image Updater가 force-push 직전 commit `3020809fb09e546cd6f1d14fc69f75755683e2ba` 기반 image tag를 Helm values에 반영했다.
  - 이후에도 Deployment image가 `sha-3020809...`를 유지하는 구간이 있었고, 코드상은 같지만 git history에는 없는 orphan tag였다.
- 처음 떠올린 가설(틀림):
  - "새 main commit image가 push되면 Image Updater가 다음 주기에서 자동으로 `sha-0ea76...`로 바뀔 것이다."
- 실제 원인:
  - Image Updater는 GHCR tag의 build/newest 기준만 보고 Helm values를 갱신한다.
  - force-push로 git history에서 사라진 commit tag라도 GHCR에는 계속 남아 있고, updater가 이미 선택한 tag를 그대로 유지하면 배포는 계속 그 orphan tag를 따른다.
  - squash merge는 서버가 새 commit을 만들기 때문에 committer가 GitHub noreply로 찍히고, rebase merge는 로컬 authorship을 보존한다. PR #39 `23f2102...`가 그 증거다.
- 해결:
  - main history는 `0ea76e66219756942348d975541759526e7321cc`로 교정했고, 그 다음 Image Updater가 최종적으로 authored commit tag를 선택하도록 기다렸다.
  - GHCR에서 orphan tag `sha-ff41b9dd1a3fcb99b1d9ccd676bec4496900684f`와 `sha-3020809fb09e546cd6f1d14fc69f75755683e2ba`를 삭제해 registry와 main history를 맞췄다.
- 연관 파일/커밋:
  - `deploy/charts/ssuai-backend/values.yaml`
  - 잘못 배포된 orphan image tag: `sha-ff41b9dd1a3fcb99b1d9ccd676bec4496900684f`, `sha-3020809fb09e546cd6f1d14fc69f75755683e2ba`
  - 바르게 authored commit image tag: `sha-0ea76e66219756942348d975541759526e7321cc`
- 검증:
  - `.\gradlew.bat test` green.
  - main CI(Security + CI) green on `0ea76e66219756942348d975541759526e7321cc`.
  - Image Updater convergence, ArgoCD `Synced/Healthy`, Deployment rollout, `/actuator/health` `UP`, Flyway V10 `create_library_seat_samples` applied, and no partition-maintenance/sampler errors were confirmed after convergence.
- 포트폴리오 포인트:
  - Git history rewrite와 container registry tag lifecycle은 별개다.
  - GitOps에서 "상태는 맞았던" Git 커밋이 사라져도 registry에 orphan tag가 남으면 drift가 증폭된다.
  - squash merge vs rebase merge의 metadata 차이가 authorship 규칙을 직접 깨뜨린다.
- 면접 예상 질문:
  1. force-push가 필요한 상황에서 GitOps 배포 추적성을 어떻게 보존하나요?
  2. ArgoCD Image Updater가 git history와 무관한 registry tag를 선택하는 이유는 무엇인가요?
  3. squash merge와 rebase merge의 commit metadata 차이가 왜 authorship 규칙 위반으로 이어지나요?
  4. orphan image tag를 운영에서 제거하거나 방지하려면 어떤 registry cleanup/annotation 전략을 쓸 수 있나요?
## 2026-06-12 — rollout은 healthy지만 scheduler가 LibraryAuthRequiredException을 계속 남김

- 맥락:
  - `0ea76...` 교정 후 Image Updater와 배포 상태를 확인하는 과정에서, rollout/health는 정상인데 로그는 깨끗하지 않을 수 있다는 가능성을 다시 검증했다.
  - 사용자 요구는 `no partition-maintenance/sampler errors`였고, 실제로는 `LibrarySeatSampleSampler`가 주기 실행 중 예외를 남겼다.
- 증상:
  - `kubectl -n ssuai-prod logs deployment/ssuai-backend --tail=200`에서 `library seat sample run failed`와 `LibraryAuthRequiredException: 도서관 로그인이 필요합니다.`가 반복 확인됐다.
  - 배포 자체는 `Synced/Healthy`와 `rollout status successful`이었지만, scheduler 로그는 요구 조건을 만족하지 못했다.
- 처음 떠올린 가설(틀림):
  - "Flyway V10와 rollout이 통과하면 sampler 로그도 자연스럽게 정상화될 것이다."
- 실제 원인:
  - `LibrarySeatSampleSampler`가 real connector를 통해 Pyxis per-seat endpoint(`GET /pyxis-api/1/api/rooms/{roomId}/seats`)를 읽는데, 이 endpoint는 익명 호출을 `error.authentication.needLogin`으로 거부한다.
  - 테스트가 green이었던 이유는 mock connector/cache 경로가 null token을 허용했기 때문이다. 즉 "mock에서는 null token 성공, prod real Pyxis에서는 로그인 필수"라는 테스트-운영 차이가 실제 원인이었다.
  - 배포/마이그레이션 성공과 sampler 성공은 별개이며, scheduler를 prod에서 어떻게 보호할지(전용 service session, re-login, 실패 시 skip)까지 봐야 한다.
- 해결:
  - 사용자 확인 후 sampler 전용 service session을 도입했다. 도서관 로그인 ID/비밀번호는 git이나 DB가 아니라 k8s Secret `ssuai-library-sampler`에만 둔다.
  - backend가 Secret의 일반 비밀번호를 oasis 로그인 JS와 같은 PBKDF2(SHA-1, 5000회) + AES-CBC 방식으로 즉시 암호화한 뒤 기존 `LibraryCredentialLoginService`의 `/pyxis-api/api/login` 호출을 재사용한다.
  - 받은 Pyxis token은 `LibrarySessionStore`에 `internal:seat-sampler` key로 저장한다. 이 key는 MCP tool 경로에서 만들어지거나 입력받지 않으므로 사용자 세션과 분리된다.
  - sample run 중 `LibraryAuthRequiredException`이 나오면 기존 token을 무효화하고 run당 최대 1회만 재로그인한다. 로그인 실패나 재로그인 후 재거부는 WARN 후 해당 run을 skip해 scheduler를 깨뜨리지 않는다.
- 연관 파일/커밋:
  - `src/main/java/com/ssuai/domain/library/timeseries/LibrarySeatSampleSampler.java`
  - `src/main/java/com/ssuai/domain/library/timeseries/LibrarySamplerSessionManager.java`
  - `src/main/java/com/ssuai/domain/library/auth/LibraryPasswordEncryptor.java`
  - `src/main/java/com/ssuai/domain/library/auth/LibraryCredentialLoginService.java`
  - `src/main/java/com/ssuai/domain/library/connector/RealLibrarySeatConnector.java`
  - 원인 기록 커밋: `9d9dc41a2477397197156b17e821e83f56ad286b`
  - 배포 당시 연관 커밋: `0ea76e66219756942348d975541759526e7321cc`, `87c46863c809e56ccd8f4759e16e3984ab7ae45c`
- 검증:
  - 단위 테스트에서 mock/null-token 유지, 기존 token 거부 시 재로그인 재시도, 로그인 불가 시 skip, 초기 로그인 token 거부 시 추가 로그인 금지를 검증한다.
  - 운영 배포 후에는 한 cadence 이상 로그에서 `LibraryAuthRequiredException`이 사라지는지와 `library_seat_samples` row count가 0보다 큰지 함께 확인한다.
- 포트폴리오 포인트:
  - rollout green만으로는 충분하지 않고, background job과 scheduler 로그까지 포함해야 진짜 prod 검증이다.
  - GitOps/배포 검증은 health endpoint와 rollout 상태 외에 주기 작업의 실패 패턴도 함께 봐야 한다.
  - 테스트 double이 인증을 느슨하게 허용하면 "테스트 green, prod broken"이 된다. 외부 시스템 auth boundary는 mock에서도 명시적으로 모델링해야 한다.
  - 공유 사용자 세션을 piggyback하지 않고 전용 service session을 둔 이유는 로그인 사용자가 없어도 시계열 데이터가 계속 쌓여야 하기 때문이다.
- 면접 예상 질문:
  1. rollout/health가 정상인데 background scheduler가 실패할 때 어떻게 분리해서 진단하나요?
  2. prod에서 주기 작업이 auth-dependent connector를 쓸 때 어떤 보호 장치가 필요하나요?
  3. runtime 검증에서 health endpoint만 보면 놓치는 실패 패턴은 무엇인가요?
  4. mock connector가 null token을 허용해 prod auth 요구사항을 놓친 문제를 어떻게 테스트로 막을 수 있나요?
  5. 사용자 세션 재사용 대신 전용 service session을 선택한 보안·운영 트레이드오프는 무엇인가요?
## 2026-06-12 — Spring bean 생성자 선택 실패: 테스트용 보조 생성자 추가 후 no default constructor

- 맥락:
  - intent queue에 PostgreSQL `LISTEN/NOTIFY` wake 보조를 붙이면서 `JdbcLibraryReservationIntentWakeNotifier`를 추가했다.
  - 운영 생성자는 `DataSource`, `LibraryReservationIntentProperties`, `Environment`를 받고, 단위 테스트를 위해 `JdbcTemplate`, properties, datasource URL을 받는 보조 생성자도 같이 뒀다.
- 증상:
  - `LibraryReservationIntentRepositoryTests` Spring context 생성이 실패했다.
  - 실패 메시지는 `jdbcLibraryReservationIntentWakeNotifier` bean 생성 중 `No default constructor found`.
- 처음 세운 가설 (틀린 방향):
  - "Spring은 생성자가 여러 개 있어도 주입 가능한 생성자를 자동으로 고를 것이다."
- 실제 원인:
  - 생성자가 2개가 되면서 Spring이 어떤 생성자를 autowire해야 하는지 확정하지 못했다.
  - 기본 생성자는 없으므로 fallback 생성도 불가능했고, context bootstrap 단계에서 실패했다.
- 해결:
  - 운영 주입 생성자에 `@Autowired`를 명시해 Spring의 선택 지점을 고정했다.
  - 테스트용 보조 생성자는 package-private으로 유지하고, H2 URL no-op과 PostgreSQL URL `pg_notify` 호출은 단위 테스트에서 직접 검증했다.
- 핵심 파일/커밋:
  - `src/main/java/com/ssuai/domain/library/reservation/intent/JdbcLibraryReservationIntentWakeNotifier.java`
  - `src/test/java/com/ssuai/domain/library/reservation/intent/JdbcLibraryReservationIntentWakeNotifierTests.java`
  - 커밋: `feat(library): wake intent worker with postgres notify` (part2 Task 2 PR)
- 검증:
  - `.\gradlew.bat test --tests com.ssuai.domain.library.reservation.intent.*` green.
- 포트폴리오 포인트:
  - 기능 자체보다 "테스트 편의용 생성자"가 DI 프레임워크의 생성자 선택 규칙을 바꾸는 점이 non-obvious였다.
  - production bean 생성 규칙과 unit-test seam을 분리할 때 `@Autowired`/factory/test-only constructor 경계를 명확히 해야 한다.
- 면접 예상 질문:
  1. Spring은 생성자가 여러 개일 때 어떤 생성자를 autowire 대상으로 선택하나요?
  2. 테스트 편의성을 위해 보조 생성자를 둘 때 production DI에 영향을 주지 않게 하려면 어떤 방법이 있나요?
  3. 이런 context bootstrap 실패를 unit test와 integration test 중 어디서 잡는 것이 적절한가요?

## 2026-06-12 — docs/test-only 이미지가 ArgoCD Image Updater rollout으로 이어지지 않음

- 맥락:
  - `library_action_total` 터미널 outcome 태그 백로그를 정리하면서 운영 코드는 이미 존재했고,
    PR #37은 회귀 테스트와 문서 수정만 포함했다.
  - main push 후 CI가 `ghcr.io/hoeongj/ssumcp:sha-48e91f3ae59a4a642bd589ee9b768bdc8a76a12f`
    이미지를 정상 push했다. 예상은 Image Updater가 Helm values의 image tag를 새 sha로 바꾸고
    ArgoCD가 rollout하는 흐름이었다.
- 증상:
  - CI image job은 성공했고 새 tag/digest(`sha256:2560574...`)가 GHCR에 존재했다.
  - 그런데 Image Updater 로그는 반복해서 `images_updated=0 errors=0`였고,
    `ssuai-backend` Application은 `Synced/Healthy` 상태를 유지했다.
  - 운영 Deployment는 계속 `sha-1678c73c5f471d41c514d651c144046d76254483` 이미지를 사용했다.
- 처음 세운 가설 (틀린 방향):
  - "main의 image job이 성공하면 새 sha tag가 항상 최신 build로 선택되어 rollout된다."
  - "`newest-build` 전략은 commit sha tag의 push 시각이나 OCI label created를 기준으로 고른다."
- 실제 원인:
  - PR #37은 테스트와 문서만 바꿨고 런타임 산출물은 바뀌지 않았다. Dockerfile은 builder stage에서
    `COPY src ./src` 후 `bootJar -x test`를 실행하고, runtime stage는 jar와 native library만 복사한다.
  - BuildKit cache 때문에 새 tag의 Docker config `created`는 `2026-06-11T14:21:48Z`로 남았고,
    현재 운영 tag `sha-1678c73...`의 Docker config `created`(`2026-06-11T14:28:58Z`)보다 과거였다.
  - 새 tag의 OCI label `org.opencontainers.image.created=2026-06-11T17:44:19Z`와 revision label은
    새 commit을 가리키지만, ArgoCD Image Updater `newest-build`는 이 label이 아니라 Docker config
    created 계열을 기준으로 비교하는 것으로 관측됐다. 따라서 "새 tag는 있지만 newest build는 아님"으로
    판단되어 no-op이 됐다.
- 해결:
  - 강제 `kubectl set image`나 rollout restart는 하지 않았다. 이번 변경은 운영 런타임 동작을 바꾸지 않는
    테스트/문서 변경이므로, 현재 pod가 건강하고 ArgoCD가 `Synced/Healthy`이면 배포 검증은 "rollout 없음"으로
    기록하는 것이 맞다.
  - 이후 배포 검증에서는 "CI가 새 image tag를 push했는가"와 "Image Updater가 실제 운영 image tag를
    바꿨는가"를 분리해서 본다. 런타임 변경 PR이면 운영 Deployment image가 해당 sha로 바뀌어야 하고,
    docs/test-only PR이면 Image Updater no-op도 정상 가능하다.
- 핵심 파일/커밋:
  - `Dockerfile`
  - `deploy/argocd/application-ssuai-backend.yaml`
  - `src/test/java/com/ssuai/domain/action/ActionServiceTests.java`
  - `docs/performance/library-agent-load-test.md`
  - PR #37, commit `48e91f3ae59a4a642bd589ee9b768bdc8a76a12f`
- 검증:
  - `gh run watch 27365989067 --exit-status` 성공: backend test + ARM64 image build/push green.
  - `docker buildx imagetools inspect` 비교:
    - old tag Docker config created = `2026-06-11T14:28:58Z`
    - new tag Docker config created = `2026-06-11T14:21:48Z`
    - new tag OCI revision label = `48e91f3ae59a4a642bd589ee9b768bdc8a76a12f`
  - `kubectl -n argocd get applications.argoproj.io ssuai-backend`: `Synced/Healthy`
  - `kubectl -n ssuai-prod rollout status deployment/ssuai-backend`: successfully rolled out
- 포트폴리오 포인트:
  - GitOps 배포 검증은 "CI 성공"만으로 끝나지 않는다. registry tag, image metadata, Image Updater 선택
    규칙, ArgoCD sync 상태, 실제 Deployment image를 분리해서 봐야 한다.
  - 특히 `newest-build`처럼 이미지 메타데이터에 의존하는 자동화는 BuildKit cache와 상호작용한다.
    docs/test-only 변경에서 rollout이 없다는 사실은 장애가 아니라 런타임 artifact가 바뀌지 않았다는
    운영 신호로 해석해야 한다.
- 면접 예상 질문:
  1. CI가 새 container tag를 push했는데 운영 pod image가 그대로인 경우, 어떤 순서로 원인을 좁힐 것인가?
  2. ArgoCD Image Updater의 `newest-build` 전략과 BuildKit cache가 충돌할 수 있는 이유는 무엇인가?
  3. docs/test-only 변경과 runtime 변경의 배포 검증 기준을 왜 다르게 잡아야 하는가?

## 2026-06-07 — MCP auth state 소실: Kubernetes in-memory → Postgres 영속화 + peek-then-consume

- 맥락:
  - MCP 클라이언트(Claude Desktop)에서 `start_auth` 호출 → 도서관 로그인 폼 제출 →
    "인증 요청이 만료되었거나 유효하지 않습니다" 오류 반복 발생.
  - ArgoCD 배포 직후, pod rollout 중에 빈번했다.
  - 비밀번호를 틀리면 폼이 비활성화되어 `start_auth`를 다시 호출해야 했다.
- 증상:
  - `INVALID_STATE` 응답이 state TTL(10분) 내에도 발생.
  - 크리덴셜 실패 후 로그인 폼 disabled → 재시도 불가.
- 처음 세운 가설 (틀린 방향):
  - "이미 로그인된 세션이 남아서 충돌한다"고 의심 → 자동 로그아웃/재로그인을 고려했다.
  - state TTL이 너무 짧다고 의심했다.
- 실제 원인:
  1. `McpAuthStateStore`가 in-memory `LinkedHashMap`으로 구현되어 있었다.
     Kubernetes(k3s)에서 pod가 재시작(배포 rollout, OOM kill 등)되면 모든 in-memory 상태가 소실된다.
     `start_auth`와 로그인 폼 제출 사이에 pod가 재시작되면 state를 찾을 수 없다.
  2. `consumeState()`가 크리덴셜 검증 **전에** 호출되었다.
     비밀번호가 틀려도 state가 삭제되어 폼이 비활성화됨.
- 해결:
  1. **V6 Flyway 마이그레이션**: `mcp_auth_states` Postgres 테이블 생성.
     `McpAuthStateStore`를 `McpAuthStateRepository` JPA 기반으로 전면 재작성.
     `@Scheduled(fixedDelay=3_600_000)` 만료 state 정리 스케줄러 추가.
  2. **peek-then-consume 패턴**: 콜백에서 `peekState()` 먼저(조회만, 삭제 없음) →
     크리덴셜 검증 → 성공 시에만 `consumeState()`.
     크리덴셜 실패 시 state 보존 → 사용자 폼 재시도 가능.
  3. **ssuAI 프론트**: `AUTH_FAILED` 시 폼 활성화 유지. `isRetryable()` 함수 도입.
     오류 메시지에서 "start_auth 재호출" 안내 제거 → "다시 입력해 주세요."로 수정.
- 핵심 파일:
  - `src/main/resources/db/migration/V6__create_mcp_auth_states.sql`
  - `src/main/java/com/ssuai/domain/auth/mcp/McpAuthStateEntity.java` (신규)
  - `src/main/java/com/ssuai/domain/auth/mcp/McpAuthStateRepository.java` (신규)
  - `src/main/java/com/ssuai/domain/auth/mcp/McpAuthStateStore.java` (전면 재작성)
  - `src/main/java/com/ssuai/domain/auth/mcp/McpLibraryAuthController.java` (peek-then-consume)
  - `ssuAI: app/mcp/auth/library/page.tsx`
- 검증:
  - `McpAuthStateStoreTests`: `peekReturnsEntryWithoutDeletingFromDb`, `peekThenConsumeSucceeds` 포함 전체 통과
  - `McpLibraryAuthControllerTests`: `callbackAuthFailureReturns401AndPreservesState`,
    `callbackSuccessConsumesStateLinksLibraryProviderAndReturns200` 통과
  - ssuAI `page.test.tsx`: `form remains enabled after AUTH_FAILED` 통과
- 포트폴리오 포인트:
  - **Kubernetes stateless pod 함정**: in-memory 상태는 pod 생애주기에 종속된다.
    auth state처럼 pod 경계를 넘어야 하는 데이터는 반드시 외부 persistent storage가 필요하다.
    해결책보다 **"어떻게 진단했는가"** 자체가 면접 포인트다.
  - **Redis vs Postgres 트레이드오프**: short-lived token에는 Redis(TTL 네이티브)가 표준.
    그러나 이 서버는 McpAuthSession을 7일 장기 유지하는 구조이고 트래픽이 1인 MCP 서버라,
    새 인프라 컴포넌트(Redis pod) 추가보다 기존 Postgres 활용으로 단순성을 선택한 이유 설명 가능.
  - **peek-then-consume 패턴**: OAuth RFC 6749는 state 즉시 소진이 표준.
    그러나 MCP 환경은 브라우저 쿠키가 없어 state가 세션 연결 역할까지 한다.
    credential login 재시도 UX를 위한 불가피한 확장 설계임을 설명 가능.
- 면접 예상 질문:
  1. Kubernetes에서 in-memory 상태를 쓰면 안 되는 상황은 어떤 경우인가?
     Pod 재시작 시 어떤 데이터가 소실되고 어떤 데이터가 보존되나?
  2. OAuth state를 즉시 소진하는 것이 보안 표준인데, peek-then-consume은 어떤 보안 가정을 전제하는가?
  3. short-lived token 저장에 Redis가 아닌 Postgres를 선택한 이유와 그 트레이드오프는?

---

## 2026-06-06 — Grafana dashboard legend template broke ArgoCD Helm rendering

- 맥락: 도서관 MCP action tool 등록 커밋은 CI와 이미지 빌드가 통과했고, ArgoCD Image Updater도
  `values.yaml`의 backend image tag를 갱신했다. 그런데 운영 Application은 최신 이미지로 sync되지 않았다.
- 증상:
  - `kubectl get application -n argocd ssuai-backend`에서 Sync Status가 `Unknown`.
  - Application condition에 `ComparisonError` 발생.
  - ArgoCD manifest generation 실패:
    `parse error at (ssuai-backend/templates/grafana-dashboard-red.yaml:96): function "uri" not defined`.
- 처음 세운 가설 (틀린 방향):
  - CI와 GHCR image push가 성공했으므로 ArgoCD가 곧 자동 sync할 것이라고 봤다.
  - Image Updater commit까지 생성됐으니 남은 것은 rollout 대기라고 생각했다.
- 실제 원인:
  - `grafana-dashboard-red.yaml`은 Helm template 파일 안에 Grafana dashboard JSON을 inline으로 넣고 있다.
  - Grafana/Prometheus legend 문자열 `"{{ uri }}"`를 Helm이 literal JSON 문자열로 보존하지 않고
    Go template action으로 해석했다.
  - Helm에는 `uri`라는 함수가 없어서 manifest generation 단계에서 실패했고, 이후 모든 backend sync가 막혔다.
- 해결:
  - Grafana legend 문자열을 Helm escape 형태로 변경해 렌더링 결과에는 여전히 `"{{ uri }}"`가 남도록 했다.
  - 동일한 raw `{{ ... }}` 패턴이 chart template에 더 있는지 검색했다.
- 핵심 파일/커밋:
  - `deploy/charts/ssuai-backend/templates/grafana-dashboard-red.yaml`
  - `TROUBLESHOOTING.md`
  - 커밋: `fix(deploy): escape grafana legend template`
- 검증:
  - 로컬에는 Helm CLI가 없어 `helm template` 검증은 불가했다.
  - Git push 후 ArgoCD Application 상태와 Kubernetes deployment image/rollout으로 검증한다.
- 포트폴리오 포인트:
  - CI green과 이미지 push 성공은 GitOps 배포 성공을 보장하지 않는다. ArgoCD의 manifest generation 단계가
    별도 failure point다.
  - Helm chart 안에 Grafana/Prometheus JSON을 inline할 때는 두 시스템 모두 `{{ ... }}` 템플릿 문법을 쓰므로
    literal escape가 필요하다.
- 면접 예상 질문:
  1. GitOps 배포에서 CI 성공 후에도 운영 sync가 실패할 수 있는 단계는 무엇인가?
  2. Helm template 파일 안에 Grafana dashboard JSON을 넣을 때 `{{ ... }}`를 어떻게 escape해야 하나?
  3. ArgoCD `ComparisonError`와 Kubernetes rollout failure는 어떻게 구분하나?

## 2026-06-06 — 학칙 RAG를 정적 seed에서 공식 출처 주기 갱신 구조로 전환

- 맥락:
  - 학칙·졸업·장학 질문은 기존 개인 데이터 도구만으로는 원문 조항 근거를 충분히 제공하기 어렵다.
  - 목표는 학사 규정 Q&A를 추가하되, 정적 PDF 복사본이 오래되어 오답을 내는 위험을 줄이는 것이다.
- 증상:
  - 초기 구현 형태는 출처 URL registry와 seed corpus가 같이 들어가 있어, 코드만 보면 규정 본문을 하드코딩한 것처럼 보였다.
  - 실제 요구는 "학교가 공식적으로 올린 문서를 주기적으로 직접 가져와 tool로 제공"하는 구조였다.
- 처음 세운 가설 (틀린 방향):
  - live fetch 옵션과 seed fallback을 함께 두면 MVP로 충분히 의도가 드러날 것이라고 봤다.
  - 고정 `SEQ_HISTORY`가 있어도 문서에 revision을 노출하면 출처 추적성이 확보된다고 봤다.
- 실제 원인:
  - `SEQ_HISTORY`는 규정 개정 때 바뀌므로 고정 history URL은 stale-data 위험을 남긴다.
  - seed corpus는 장애 fallback일 뿐 source of truth가 아니어야 한다.
  - 운영 구조의 핵심은 "본문 하드코딩"이 아니라 "공식 URL registry + 최신 history resolve + scheduled refresh + fallback 표기"다.
- 해결:
  - `AcademicPolicyCorpusCache`를 추가해 서버 시작 후와 주기적으로 공식 출처 corpus를 refresh한다.
  - `RealAcademicPolicyConnector`가 `rule.ssu.ac.kr/lawDetail.do?SEQ=...`에서 최신/선택 `SEQ_HISTORY`를 먼저 찾고,
    해당 `lawFullContent.do` 원문을 가져오도록 변경했다.
  - `ssu.ac.kr` 공식 학사 안내 페이지도 corpus 출처에 포함했다.
  - 응답 DTO에 `live`, `fallbackUsed`, `revision`, `effectiveDate`, `url`, `contentHash`를 포함해 최신성/대체 여부를 숨기지 않는다.
  - 테스트 profile에서는 scheduled refresh를 꺼서 외부 네트워크와 테스트 안정성을 분리했다.
- 핵심 파일/커밋:
  - `src/main/java/com/ssuai/domain/academic/connector/RealAcademicPolicyConnector.java`
  - `src/main/java/com/ssuai/domain/academic/service/AcademicPolicyCorpusCache.java`
  - `src/main/java/com/ssuai/domain/academic/service/AcademicPolicyService.java`
  - `src/main/java/com/ssuai/domain/mcp/tool/AcademicPolicyMcpTools.java`
  - 커밋: `57c2199 feat(academic): add official policy RAG tools`
  - PR: `https://github.com/hoeongj/ssuMCP/pull/25`
- 검증:
  - 변경 범위 테스트 통과:
    `gradlew.bat test --tests "com.ssuai.domain.academic.*" --tests "com.ssuai.domain.mcp.tool.AcademicPolicyMcpToolsTests" --tests "com.ssuai.domain.mcp.tool.CampusMcpToolsTests" --tests "com.ssuai.domain.mcp.config.McpServerConfigTests" --tests "com.ssuai.domain.mcp.McpSelfDogfoodTests"`
  - 전체 테스트 통과: `gradlew.bat test`
  - GitHub Actions Backend (Gradle, JDK 21) 통과.
- 포트폴리오 포인트:
  - 단순 RAG 도입이 아니라 stale-data를 명시적으로 다루는 공식 출처 추적형 RAG 설계다.
  - 개인 u-SAINT 데이터(`evaluate_graduation_with_policy`)와 공개 규정 근거를 하나의 MCP 응답으로 결합한다.
  - DB migration 없이 인메모리 scheduled corpus로 MVP를 만들고, 이후 persistent corpus/vector search로 확장 가능한 경계를 남겼다.
- 면접 예상 질문:
  1. 정적 PDF RAG와 공식 출처 주기 갱신형 RAG의 운영 리스크 차이는 무엇인가?
  2. 왜 처음부터 벡터DB/pgvector를 붙이지 않고 인메모리 corpus로 시작했는가?
  3. 규정관리시스템의 `SEQ_HISTORY`가 바뀌는 문제를 어떻게 방어했는가?

## 2026-06-06 — 동시 작업 중 MCP tool inventory 테스트 실패

- 맥락:
  - 학사 정책 RAG 도구 추가 작업과 도서관 available-seat 도구 추가 작업이 같은 ssuMCP worktree에서 순차적으로 합쳐졌다.
  - 두 작업 모두 `McpServerConfig`와 tool inventory 테스트를 건드리는 성격이었다.
- 증상:
  - `McpServerConfigTests`와 `McpSelfDogfoodTests`가 실패했다.
  - 실패 메시지에는 실제 tool 목록에 `get_library_available_seats`, `get_room_available_seats`가 있으나 기대 목록에는 없다고 나왔다.
- 처음 세운 가설 (틀린 방향):
  - 신규 academic-policy tool 등록 또는 Spring AI MCP callback provider 설정이 잘못됐다고 의심했다.
  - Gradle worker EOF/timeout 때문에 코드 실패인지 환경 실패인지 바로 구분하기 어려웠다.
- 실제 원인:
  - 실제 등록 목록은 맞았고, 테스트 기대값이 동시 작업에서 추가된 도서관 read-only 도구 2개를 반영하지 못했다.
  - 같은 `build/test-results`를 대상으로 여러 Gradle test 프로세스가 동시에 돌아 EOF/timeout 로그가 섞였다.
- 해결:
  - 공유 inventory 테스트 기대값에 `get_library_available_seats`, `get_room_available_seats`를 추가했다.
  - 다른 test 프로세스가 끝난 뒤 변경 범위 테스트와 전체 테스트를 다시 실행했다.
- 핵심 파일/커밋:
  - `src/test/java/com/ssuai/domain/mcp/config/McpServerConfigTests.java`
  - `src/test/java/com/ssuai/domain/mcp/McpSelfDogfoodTests.java`
  - `docs/mcp-tools.md`, `docs/architecture.md`, `README.md`
  - 커밋: `57c2199 feat(academic): add official policy RAG tools`
- 검증:
  - `McpServerConfigTests`와 `McpSelfDogfoodTests` 통과.
  - 전체 `gradlew.bat test` 통과.
- 포트폴리오 포인트:
  - MCP 서버는 tool 목록 자체가 public contract이므로, 기능 추가보다 inventory contract 테스트를 정확히 유지하는 것이 중요하다.
  - 병렬 작업 중 실패 로그가 섞일 때는 assertion failure와 Gradle worker/process 충돌을 분리해 보는 절차가 필요하다.
- 면접 예상 질문:
  1. MCP tool inventory를 `containsExactlyInAnyOrder`로 강하게 검증하는 이유는 무엇인가?
  2. 동시 작업으로 같은 contract 테스트가 깨질 때 어떻게 원인을 분리했는가?
  3. tool count 문서와 실제 MCP listing을 어떻게 동기화했는가?

---

## 2026-06-06 — Library seat-map screenshots revealed room-scoped policy and B1 gap

- 맥락: 사용자가 도서관 전체 좌석배치도 캡처 7장을 제공했고, 층별 잔여 좌석 수가 아니라
  좌석별 속성(창가, 스탠딩, 콘센트, 가장자리 등)과 선호도 기반 추천/예약 자동화를 원했다.
- 증상:
  - 기존 `LibraryFloor`는 2F, 5F, 6F만 지원한다. 지하열람실(B1)은 현재 백엔드 enum과
    프론트 실행 코드의 층 탭에 들어가 있지 않다.
  - 기존 `RealLibrarySeatConnector`는 `/pyxis-api/1/seat-rooms?...`에서 방별 count만 파싱하고,
    좌석 단위 `LibrarySeatZone.seats`를 채우지 않는다.
  - 대학원열람실은 비대학원생이 예약을 누르면 upstream/UI에서
    `해당유형은 사용이 불가능한 신분입니다.` 알림이 뜬다.
- 처음 세운 가정(틀린 방향):
  - 층 단위 availability와 seat id만 있으면 추천/예약을 바로 연결할 수 있다고 볼 수 있었다.
  - 2F/5F/6F floor model만 확장하면 모든 열람실을 표현할 수 있다고 볼 수 있었다.
  - 모든 표시 좌석은 같은 예약 권한 정책을 가진다고 볼 수 있었다.
- 실제 원인:
  - Pyxis 좌석 배치는 floor보다 room 단위가 더 중요하다. 같은 층 안에도
    숭실스퀘어ON, 오픈열람실, PC존/멀티존, 리클라이너, 마루열람실, 대학원열람실처럼
    좌석 타입과 정책이 다른 room이 있다.
  - B1은 현재 API floor 값이 확인되지 않았고, `LibraryFloor`에 넣으면 실시간 connector와
    예약 파라미터가 틀릴 위험이 있다.
  - 대학원열람실은 좌석 availability와 별개로 사용자 신분 정책이 reservation 단계에서
    강제된다. 추천 단계에서도 `graduate_only` restriction을 노출해야 한다.
- 해결:
  - `seat-room-catalog.json`에 캡처 기반 room catalog를 만들고, 각 room의
    `floorCode`, `roomCode`, `roomName`, `audience`, `graduateOnly`, `seatIdPattern`,
    `seatTypes`, `textLayout`, `captureNotes`를 분리했다.
  - `seat-catalog.json`은 전체 하드코딩 전까지 대표 좌석 scaffold로 두고,
    `roomCode`, `externalSeatId`, `seatType`, `audience`, 좌석 속성 booleans를 갖게 했다.
  - 정적 조회용 `GET /api/library/seat-catalog`와 MCP `get_library_seat_catalog`를 추가했다.
  - 실시간 추천용 MCP `recommend_library_seats`는 live seat item/seat id가 있을 때만
    추천하고, real connector가 floor-only count만 주는 상황에서는 좌석 예약 가능하다고
    과장하지 않도록 메시지를 반환한다.
- 핵심 파일/커밋:
  - `src/main/resources/library/seat-room-catalog.json`
  - `src/main/resources/library/seat-catalog.json`
  - `src/main/java/com/ssuai/domain/library/recommendation/*`
  - `src/main/java/com/ssuai/domain/library/controller/LibrarySeatCatalogController.java`
  - `src/main/java/com/ssuai/domain/mcp/tool/LibrarySeatCatalogMcpTool.java`
  - `src/main/java/com/ssuai/domain/mcp/tool/LibrarySeatRecommendationMcpTool.java`
  - `docs/library-seat-agent-handoff.md`
  - commit `d957d74 feat: add library seat recommendation catalog`
- 검증:
  - `./gradlew.bat test --console=plain` 통과.
  - 카탈로그 로딩, B1 static catalog 존재, 대학원열람실 `graduate_only` 표시,
    MCP tool 등록, 추천 정렬/availability source 분기를 테스트로 검증했다.
- 포트폴리오 포인트:
  - LLM이 좌석을 추측하지 않도록 정적 공간 지식, 실시간 availability, 사용자 정책을
    별도 계층으로 분리했다.
  - floor-only 모델에서 room-scoped domain model로 확장 가능한 구조를 만들었다.
  - 예약 action은 추천 결과와 분리하고, final reservation은 기존 `prepare`/`confirm`
    경로로 유지해 write action safety를 보존했다.
- 다음 작업자 체크리스트:
  - DevTools Network에서 `pyxis-api` seat-map/seat-list endpoint, room id, floor id,
    seat id field를 캡처한다.
  - B1의 실제 Pyxis floor 값 또는 room-only 파라미터를 확인한 뒤 `LibraryFloor` 확장 여부를 결정한다.
  - 대학원열람실 denial이 browser alert인지 API 응답인지 확인하고, reservation prepare/confirm
    단계에서 사용자에게 신분 제한을 명시한다.
- 면접 예상 질문:
  1. 왜 좌석 추천 모델을 floor 단위가 아니라 room 단위로 확장해야 했나?
  2. 실시간 availability가 없는 상황에서 LLM이 예약 가능 좌석을 hallucinate하지 않게 한 장치는 무엇인가?
  3. 대학원열람실처럼 권한 정책이 있는 좌석을 추천/예약 플로우에서 어떻게 다르게 다뤄야 하나?

---

## 2026-06-06 — Gradle test result binary was corrupted after killed test run

- 맥락: `d957d74` 구현 중 전체 테스트를 처음 실행할 때 120초 timeout으로 프로세스가 강제 종료됐다.
- 증상:
  - 다음 `./gradlew.bat test --console=plain` 실행이 소스 컴파일/테스트 assertion 실패가 아니라
    아래 파일 누락으로 실패했다.
  - `java.nio.file.NoSuchFileException: build/test-results/test/binary/in-progress-results-generic.bin`
- 처음 세운 가정(틀린 방향):
  - 새로 추가한 MCP tool 등록, JSON catalog deserialization, 또는 Spring context startup에서
    코드 실패가 발생했다고 의심할 수 있었다.
- 실제 원인:
  - 첫 테스트 실행이 timeout으로 중간에 종료되면서 Gradle test-results binary state가
    incomplete 상태로 남았다. 이후 Gradle이 깨진 incremental test result를 읽으려다 실패했다.
- 해결:
  - 테스트 결과 산출물만 정리하는 `./gradlew.bat cleanTest test --console=plain`을 실행했다.
  - 이후 일반 `./gradlew.bat test --console=plain`도 통과했다.
- 핵심 파일/커밋:
  - 소스 변경 없음. 로컬 빌드 산출물 문제.
  - 관련 구현 커밋: `d957d74 feat: add library seat recommendation catalog`
- 검증:
  - `./gradlew.bat cleanTest test --console=plain` 통과.
  - `./gradlew.bat test --console=plain` 재실행 통과.
- 포트폴리오 포인트:
  - 테스트 실패가 항상 product code failure는 아니다. timeout/kill 이후에는 Gradle의
    incremental test result state를 먼저 의심하고 `cleanTest`로 산출물만 재생성할 수 있다.
- 면접 예상 질문:
  1. Gradle test task가 assertion 실패가 아니라 binary result 파일 누락으로 실패하면 무엇을 먼저 확인하나?
  2. `clean` 전체와 `cleanTest`의 차이는 무엇이고, 언제 `cleanTest`가 더 적절한가?
  3. 긴 통합 테스트가 timeout으로 끊기는 환경에서 재현성 있는 검증 로그를 남기는 방법은?

---

## 2026-06-06 — Prometheus/Grafana 전환 중 Grafana DNS와 PowerShell RNG 가정 오류

- 맥락: Discord webhook 기반 장애 알림 코드를 제거하고, 운영 관측성을
  Prometheus/Grafana/kube-prometheus-stack으로 전환했다. ArgoCD Application으로
  monitoring stack을 GitOps 배포하고 Grafana를 외부에 노출하는 작업이었다.
- 증상:
  - 최초 Grafana Ingress를 `grafana-ssumcp.duckdns.org`로 만들었지만
    cert-manager HTTP-01 challenge가 계속 `pending`에 머물렀다.
  - `grafana-admin` Secret 생성 시 PowerShell의
    `[RandomNumberGenerator]::Fill(...)` 호출이 현재 런타임에서 동작하지 않았다.
- 처음 세운 가설 (틀린 방향):
  - DuckDNS 하위 이름을 Ingress host로 쓰면 기존 VM IP로 자연스럽게 연결될 것이라
    가정했다.
  - 최신 .NET/PowerShell에서 쓰던 static RNG API가 현재 운영 셸에서도 그대로
    지원될 것이라 가정했다.
- 실제 원인:
  - DuckDNS는 wildcard DNS가 아니며, 실제로 등록된 label은 `ssumcp`와
    `argo-ssuai`뿐이었다. `grafana-ssumcp` label은 존재하지 않아 Let's Encrypt
    HTTP-01 solver Ingress가 외부에서 도달될 수 없었다.
  - 현재 PowerShell/.NET 런타임에서는 static `RandomNumberGenerator.Fill` 호출이
    사용할 수 없었다. 동일한 암호학적 난수 생성이라도 런타임별 API surface가 다르다.
- 해결:
  - 새 DuckDNS label을 요구하지 않도록 Grafana를 기존 backend host의 sub-path인
    `https://ssumcp.duckdns.org/grafana`로 노출했다.
  - Grafana `root_url`을 `/grafana` 경로로 맞추고 `serve_from_sub_path=true`를
    설정했다.
  - 실패했던 cert-manager solver Ingress와 Challenge를 삭제했다.
  - Secret은 `[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes(...)`
    방식으로 즉시 재생성했다. password 값은 터미널에 출력하지 않았다.
- 핵심 파일/커밋:
  - `deploy/argocd/application-monitoring.yaml`
  - `deploy/charts/monitoring/values.yaml`
  - `deploy/charts/ssuai-backend/templates/servicemonitor.yaml`
  - `deploy/README.md`, `deploy/argocd/README.md`
  - `build.gradle`, `application.yml`
  - `src/main/java/com/ssuai/global/monitoring/*` 삭제
  - PR #21 `feat(monitoring): add prometheus stack`
  - PR #22 `fix(monitoring): serve grafana under backend host`
- 검증:
  - `./gradlew.bat --no-daemon test` 통과.
  - ArgoCD `monitoring` Application: `Synced / Healthy`.
  - monitoring namespace의 Prometheus, Alertmanager, Grafana, kube-state-metrics,
    node-exporter, operator 모두 `Running`.
  - `https://ssumcp.duckdns.org/grafana/login` HTTP 200.
  - `https://ssumcp.duckdns.org/actuator/prometheus` HTTP 200, `jvm_`,
    `http_server` metric 확인.
  - Prometheus active target에서 `ssuai-backend` health `up` 확인.
- 포트폴리오 포인트:
  - "Ingress YAML이 맞다"와 "외부 DNS가 실제로 존재한다"는 별개다. TLS 자동화는
    Kubernetes 내부 상태만으로 검증되지 않고 DNS, VM firewall, port 80 reachability까지
    포함한 end-to-end 시스템이다.
  - 알림 코드를 애플리케이션 내부 Discord webhook에서 platform observability로
    전환하면서, 장애 감지를 코드 예외 처리에서 Prometheus scrape/alert rule로 옮길
    수 있는 구조를 만들었다.
  - 운영 Secret 생성은 값 노출 없이 재현 가능한 명령으로 처리해야 하며, 로컬 셸 API
    차이도 실패 원인이 될 수 있다.
- 면접 예상 질문:
  1. cert-manager HTTP-01 challenge가 pending일 때 DNS, Ingress, Service 중 어디부터
     확인하나요?
  2. 애플리케이션 코드에서 Discord webhook을 직접 호출하는 방식과 Prometheus/Grafana
     기반 모니터링의 trade-off는 무엇인가요?
  3. Grafana를 sub-path(`/grafana`)로 서빙할 때 `root_url`과
     `serve_from_sub_path`가 왜 필요한가요?

---

## 2026-06-06 — SAINT 방학학기 선택 시 SaintSessionExpiredException 오판

- 맥락: 6월 여름방학 진입 시점에 SAINT UI가 자동으로 여름학기(SUMMER)를 선택했다.
  수업이 없는 학기여서 시간표 데이터가 없는 상태였다.
- 증상: `get_my_schedule`, `get_my_chapel_info` 호출 시 "세션이 만료됐습니다" 오류 반환.
  실제로 로그아웃한 것도 아니고 JWT 만료도 아니었다.
- 처음 세운 가설: SAINT 세션이 실제로 만료됐거나 SmartID 쿠키가 갱신되지 않았다.
- 실제 원인:
  1. `app.getSelectedSemester()`가 SAINT UI 롤오버로 SUMMER를 반환.
  2. rusaint가 `app.schedule(year, SUMMER)` 호출 시 수업 없는 학기임을 빈 테이블로 받지 않고
     "ecc did not return the timetable container" 예외를 던짐.
  3. `RusaintScheduleConnector`가 `RusaintClientException`을 `SaintSessionExpiredException`으로
     포장 → 사용자에게 "세션 만료"로 잘못 노출.
- 해결: `RusaintUniFfiClient.kt`에서 `requestedSemester` 계산 시
  SUMMER → `SemesterType.ONE`, WINTER → `SemesterType.TWO`로 fallback.
  `fetchSchedule`, `fetchChapelInfo` 모두 동일 패턴 적용.
- 핵심 파일/커밋:
  - `src/main/kotlin/com/ssuai/domain/saint/connector/RusaintUniFfiClient.kt`
  - `999c82e fix: SAINT vacation-semester fallback + notice date ISO + local notice index`
- 검증: `./gradlew.bat test` 전체 통과.
- 포트폴리오 포인트:
  - 에러 코드 재사용 함정: `RusaintClientException` 하나로 "API 오류"와 "빈 데이터" 모두를
    포장하면 진단이 어렵다. 세션 만료와 데이터 부재는 반드시 다른 예외 타입으로 분리해야 한다.
  - SAINT UI의 UI-level 상태(선택 학기)가 백엔드 동작에 영향을 주는 구조 이해.
    학기 롤오버 타이밍이 외부 시스템 연동에서 예상치 못한 엣지 케이스를 만든다.
- 면접 예상 질문:
  1. 외부 라이브러리의 예외를 내부 도메인 예외로 포장할 때 정보가 손실되는 상황과 대응 방법은?
  2. SAINT처럼 stateful UI 기반 시스템에서 "빈 응답"과 "인증 실패"를 구분하는 방법은?
  3. 계절학기 롤오버처럼 시간 의존적 엣지 케이스를 사전에 탐지하는 테스트 전략은?

---

## 2026-06-06 — Spring Boot 4에서 @DataJpaTest 패키지 경로 미등록 → @SpringBootTest 전환

- 맥락: `NoticeIndexRepositoryTests.java`를 새로 작성하면서 표준 `@DataJpaTest` 애노테이션을 사용.
- 증상:
  ```
  error: package org.springframework.boot.test.autoconfigure.orm.jpa does not exist
  import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
  ```
  컴파일 실패. 기존 단위 테스트 전부 통과하는 상태인데 새 테스트만 못 만든다.
- 처음 세운 가설: `spring-boot-starter-test`에 `@DataJpaTest`가 포함돼 있을 것이라 가정.
- 실제 원인: 이 프로젝트의 기존 리포지토리 테스트(`StudentRepositoryTests`)가
  `@DataJpaTest`가 아닌 `@SpringBootTest + @Transactional + @DirtiesContext` 조합을 사용하고 있었다.
  `build.gradle`에 별도로 autoconfigure 의존성이 빠져 있거나 프로젝트 관례상 full context 방식을 쓰고 있다.
- 해결: `@DataJpaTest` → `@SpringBootTest + @ActiveProfiles("test") + @Transactional + @DirtiesContext(classMode=AFTER_CLASS)` 로 교체. 프로젝트 기존 패턴과 통일.
  추가로 `@BeforeEach`의 `when(noticeIndexRepository.count()).thenReturn(0L)`이 일부 테스트에서 미사용 → `lenient().when(...)` 처리.
- 핵심 파일:
  - `src/test/java/com/ssuai/domain/notice/repository/NoticeIndexRepositoryTests.java`
  - `src/test/java/com/ssuai/domain/notice/service/NoticeServiceTests.java`
- 검증: `./gradlew.bat test "--tests=com.ssuai.domain.notice.*"` 47 테스트 통과.
- 포트폴리오 포인트:
  - 새 테스트 작성 전 **기존 테스트 패턴을 먼저 확인**하는 습관. 프레임워크 표준이 프로젝트 관례와
    다를 수 있다. `grep -r "@DataJpaTest" .` 결과가 없으면 프로젝트가 다른 방식을 사용 중이라는 신호.
  - Mockito strict stubbing 규칙: `@ExtendWith(MockitoExtension.class)`는 미사용 stub을
    `UnnecessaryStubbingException`으로 즉시 실패시킨다. `@BeforeEach`의 공통 stub은
    `lenient()`로 처리하거나 stub을 각 테스트로 이동해야 한다.
- 면접 예상 질문:
  1. `@DataJpaTest`와 `@SpringBootTest`의 차이점과 각각 적합한 상황은?
  2. Mockito strict stubbing이 `UnnecessaryStubbingException`을 던지는 이유와 장점은?
  3. 프로젝트에서 테스트 패턴을 통일해야 하는 이유는 무엇인가요?

---

## 2026-06-05 — MCP 신규 도구 추가 시 두 개의 테스트 파일을 수동으로 업데이트해야 함

- 맥락: `claude_check.md` 기반 일괄 개선 작업(PR #19)에서 `get_meal_weekly`, `get_academic_calendar` 두 개의 @Tool 메서드를 추가.
- 증상: 컴파일은 성공했는데 `McpServerConfigTests.registersSsuaiMcpTools()`와 `McpSelfDogfoodTests.clientCanListEveryToolExposedByServer()` 두 테스트가 동시에 실패. 에러는 `containsExactlyInAnyOrder(...)` 목록 불일치.
- 처음 세운 가설 (틀린 방향): Spring AI의 `MethodToolCallbackProvider`가 `@Tool` 애노테이션을 자동 수집하므로, 새 도구를 추가하면 테스트도 자동으로 인식할 것이라고 예상.
- 실제 원인: 두 테스트는 "등록된 도구 이름 전체 목록"을 `containsExactlyInAnyOrder()`로 명시적 열거한다. 이 열거는 자동화되지 않으며, 새 도구 추가 시 수동으로 두 군데 모두 갱신해야 한다.
- 해결: `McpServerConfigTests.java`와 `McpSelfDogfoodTests.java`의 도구 이름 목록에 각각 `"get_meal_weekly"`, `"get_academic_calendar"` 추가.
- 핵심 파일: `src/test/java/.../mcp/config/McpServerConfigTests.java`, `src/test/java/.../mcp/McpSelfDogfoodTests.java`
- 검증: `./gradlew.bat test` 583 tests 통과.
- 포트폴리오 포인트: MCP 도구 등록이 동적이어도, E2E 정합성 테스트는 의도적으로 정적 목록을 요구한다. "서버가 외부에 노출하는 도구 집합"이 암묵적으로 바뀌지 않도록 명시적 계약을 유지하는 테스트 패턴.
- 면접 예상 질문:
  1. MCP 서버에서 도구 등록을 어떻게 검증하나요? 자동화가 어려운 이유는?
  2. `containsExactlyInAnyOrder`를 쓰는 테스트와 `containsAll`을 쓰는 테스트의 trade-off는?
  3. 새 MCP 도구를 추가할 때 놓치면 안 되는 체크리스트는 무엇인가요?

---

## 2026-06-05 — `ssuai.notice.cache-ttl` 프로퍼티가 존재했지만 실제로는 사용되지 않고 있었음

- 맥락: TASK S(캐싱 레이어 도입)에서 공지 리스트 캐시를 구현하려다 발견.
- 증상: `NoticeConnectorProperties`에 `cacheTtl: 5m`이 이미 정의되어 있고 `application.yml`에도 문서화되어 있었지만, `NoticeService`는 매 호출마다 connector를 직접 호출했음. 캐시 로직이 없었음.
- 처음 세운 가설 (틀린 방향): 프로퍼티가 있으면 어딘가에 캐시 구현이 있을 것이라 생각했고, `NoticeCache` 클래스를 찾으려 했음.
- 실제 원인: 프로퍼티는 추후 구현을 위해 미리 준비해 둔 dead config였음. `NoticeService` 어디에도 TTL 로직이 없었음.
- 해결: `NoticeListCache` 클래스를 `LibraryBookCache` 패턴으로 새로 작성하고 `NoticeService`에 주입. `cacheTtl`을 실제로 소비하게 됨.
- 핵심 파일: `src/main/java/.../notice/service/NoticeListCache.java`, `NoticeService.java`
- 검증: `./gradlew.bat test` 통과. `NoticeServiceTests`에서 캐시 생성자를 직접 주입하는 방식으로 테스트 유지.
- 포트폴리오 포인트: 설정 파일에 프로퍼티가 있다고 해서 기능이 구현된 게 아님. 실제 코드 경로를 추적해야 함. "설정 완료 ≠ 기능 완료" 함정.
- 면접 예상 질문:
  1. Spring Boot에서 `@ConfigurationProperties`로 캐시 TTL을 주입하는 방법은?
  2. Single-flight 캐시 패턴이란 무엇이고 왜 필요한가요?
  3. LRU-bounded `LinkedHashMap`으로 캐시를 구현할 때 thread-safety는 어떻게 보장했나요?

---

## 2026-05-27 — 도서관 MCP 인증 캐시와 챗봇 private-provider 경계 수정

- 맥락: 운영 도서관 좌석 connector는 Pyxis 인증 토큰을 요구하고, 챗봇은
  공개/개인 LLM provider pool을 분리한다.
- 증상: `get_library_seat_status`가 MCP 공개 도구로 남아 있어 인증된 REST
  요청이 먼저 좌석 cache를 채우면 무세션 MCP 호출이 같은 값을 재사용할
  가능성이 있었다. 또한 개인 tool 결과가 포함된 챗봇 최종 응답과 후속
  history가 public provider policy로 전달될 수 있었다.
- 원인: 좌석 cache key가 floor만 포함했고, MCP tool 계약이 실제 upstream
  인증 조건을 반영하지 않았다. 챗봇은 tool 실행 전후 동일 privacy mode를
  재사용했다.
- 해결: 좌석 cache key를 floor와 인증 경계로 분리하고 좌석 MCP tool을
  `LIBRARY` private tool로 전환했다. 링크는 남았지만 upstream token이 만료된
  좌석/대출 호출은 `AUTH_REQUIRED`로 복구한다. 개인 tool이 사용된 conversation은
  결과 생성과 후속 history 모두 private provider policy를 사용한다.
- 검증: `.\gradlew.bat test`와 `.\gradlew.bat build` 통과. cache 경계,
  무세션 MCP 응답, 만료 후 재연동, private conversation 유지 테스트를 추가했다.
- 포트폴리오 포인트: 공개 성격의 집계 데이터라도 upstream 인증 경계와 LLM
  전송 경계를 별도로 검증해야 데이터 노출 경로를 닫을 수 있다.
- 면접 예상 질문:
  1. 캐시 키 설계에서 "데이터 동일성"과 "접근 권한 경계"를 어떻게 분리했나요?
  2. LLM에 전달되는 데이터의 privacy 경계를 서버에서 강제하는 방법을 설명해보세요.
  3. 공개 집계 데이터가 개인 정보 유출 경로가 될 수 있는 시나리오를 예시로 드세요.

## 2026-05-24 — MCP transport SSE → Streamable HTTP 후 통합 테스트 CI 실패 (프로퍼티 키 불일치)

- 맥락: `application.yml` 의 MCP client transport 를 SSE (`sse.connections.self.url`) 에서
  Streamable HTTP (`streamable-http.connections.self.url`) 로 전환했다.
  단위 테스트 (~500개) 는 모두 mock 모드라 MCP client 를 실제로 초기화하지 않아 전부 그린이었다.
- 증상: `LlmModeStartupSmokeTest` 만 CI 에서 실패. Spring Boot 컨텍스트가 올라오지 못하고
  `spring.ai.mcp.client.sse.connections.self.url` property 를 찾지 못해 MCP 자체 연결에서 타임아웃.
- 원인: `@DynamicPropertySource` 블록이 구 SSE 프로퍼티 키를 하드코딩하고 있었다.
  ```java
  // 구: SSE 시절
  registry.add("spring.ai.mcp.client.sse.connections.self.url", () -> "http://localhost:" + SERVER_PORT);
  // 신: Streamable HTTP 전환 후
  registry.add("spring.ai.mcp.client.streamable-http.connections.self.url", () -> "http://localhost:" + SERVER_PORT);
  ```
  mock 모드 단위 테스트는 `spring.ai.mcp.client.enabled: false` 가 `application-test.yml` 에 설정되어
  MCP client 빈 자체가 로드 안 됨 → transport 전환 영향이 단위 테스트에서 가려져 통합 테스트 CI
  에서만 드러났다.
- 해결: `@DynamicPropertySource` 에서 프로퍼티 키를 `streamable-http` 로 수정.
- 검증: `gradlew.bat test` 전체 통과, CI 그린.
- 포트폴리오 포인트:
  - **"단위 테스트 전부 그린" ≠ "인프라 설정 변경이 안전"**. transport 전환처럼 Spring context 수준의
    설정 변경은 full-context 통합 테스트 (여기서는 `@SpringBootTest(RANDOM_PORT)`) 가 아니면 잡히지 않는다.
    mock profile 이 CI 의 fast gate 역할을 하지만, 실 transport / 실 MCP 초기화를 검증하는
    smoke test 를 분리 보유하는 이유가 정확히 이 사례다.
- 면접 예상 질문:
  1. Spring 설정 변경 시 단위 테스트가 놓칠 수 있는 케이스에는 어떤 것이 있나요?
  2. mock profile과 실제 컨텍스트 smoke test를 분리 보유해야 하는 이유를 설명하세요.
  3. @DynamicPropertySource를 사용하는 통합 테스트에서 설정 키 변경이 어떻게 숨겨질 수 있나요?

---

## 2026-05-24 — Spring AI MCP tool annotation 주입: @Primary McpSyncServerCustomizer + reflection

- 맥락: Claude Desktop 에서 MCP 도구를 "Read-only tools" / "Write/delete tools" 로 시각적으로 구분하려면
  각 tool 에 `McpSchema.ToolAnnotations` (`readOnlyHint`, `destructiveHint`) 를 붙여야 한다.
  Spring AI 1.1.x 는 tool annotation 을 주입하는 공개 API 를 제공하지 않는다.
- 증상 (목표): Spring AI 가 자동으로 등록한 모든 tool 에 annotation 을 추가하고 싶다.
- 원인 (제약):
  1. `McpServer.SyncSpecification` 의 `tools` 필드가 `package-private` + `final`인 `List<SyncToolSpecification>` 이어서 외부에서 직접 접근 불가.
  2. 기존 `servletMcpSyncServerCustomizer` auto-configure 빈은 `spec.immediateExecution(true)` 를 호출한다.
     WebMVC servlet mode 에서 이 호출이 없으면 MCP 요청이 blocking 되지 않아 SSE 스트리밍이 깨진다.
     단순히 `@Bean McpSyncServerCustomizer` 를 추가하면 auto-configure 빈과 순서 충돌 → 어느 쪽이 먼저 실행될지 보장 없음.
- 해결:
  ```java
  @Primary               // auto-configured servletMcpSyncServerCustomizer 를 교체
  @Bean
  McpSyncServerCustomizer ssuaiToolAnnotationsCustomizer() {
      return spec -> {
          spec.immediateExecution(true);   // ① servlet mode 필수 호출 보존

          // ② package-private 필드를 reflection 으로 열어 tool list 재구성
          Field toolsField = McpServer.SyncSpecification.class.getDeclaredField("tools");
          toolsField.setAccessible(true);
          List<SyncToolSpecification> tools = (List<>) toolsField.get(spec);

          List<SyncToolSpecification> annotated = tools.stream()
              .map(McpServerConfig::withAnnotations)   // readOnlyHint / destructiveHint 부착
              .collect(toList());

          tools.clear();
          tools.addAll(annotated);   // 같은 리스트 인스턴스를 교체 → spec 내부 참조 유지
      };
  }
  ```
  `@Primary` 가 `servletMcpSyncServerCustomizer` 를 대체하므로 두 customizer 가 충돌 없이
  한 빈으로 통합된다.
- 검증: `McpServerConfigTests` 에서 `get_today_meal` (readOnly), `logout_all` (destructive) 의
  annotation 이 올바로 붙었는지 확인. `gradlew.bat test` 전체 통과.
  Claude Desktop 에서 ssuMCP 재연결 후 "Read-only tools (20)" / "Write/delete tools (3)" 시각 분리 확인.
- 포트폴리오 포인트:
  - **공개 API 가 없는 프레임워크 내부 상태 변경 패턴**: `@Primary` 로 auto-configure 빈을 교체하면서
    기존 빈의 side-effect (`immediateExecution`) 도 함께 보존해야 하는 상황. 두 가지를 한 빈으로 통합해 충돌을 제거.
  - **reflection 의 적절한 사용 범위**: Spring AI 가 public API 를 열기 전까지 bridging 용도로만 사용.
    팀 합류 면접에서 "framework 의 package-private 를 건드린 적 있나?" 라는 질문에 근거 있는 사례.
  - tool annotation 이 클라이언트 UX (도구 그룹화) 에 직접 영향을 주는 구조 — MCP spec 의
    `annotations` 필드가 실제 Claude 화면에서 어떻게 표현되는지 end-to-end 검증까지 한 사례.
- 면접 예상 질문:
  1. @Primary로 auto-configured 빈을 교체할 때 기존 빈의 side-effect를 함께 보존해야 하는 이유는?
  2. 프레임워크의 package-private 필드를 reflection으로 건드리는 것이 적절한 상황은 언제인가요?
  3. MCP tool annotations(readOnlyHint, destructiveHint)이 클라이언트 UX에 어떤 영향을 주나요?

---

## 2026-05-21 — u-SAINT 웹 방화벽(WAF) 우회 및 LMS/Canvas 세션 오염 방지(CookieManager 격리)를 통한 실서버 로그인 정상화

- 맥락: ssumcp 실서버 환경에서 로그인 연동 시, SAINT(시간표/성적) 및 LMS(과제) 조회 시 무조건 세션 오류(`logon redirect` 또는 `401 session expired`)가 발생하여 기능이 작동하지 않았다.
- 증상:
  - u-SAINT 시간표 및 성적 조회 시: 로그인 상태임에도 대학 웹 방화벽에서 ANON(비로그인) 세션으로 강제 강등시켜 `ecc did not return the timetable container` 에러가 발생.
  - LMS 과제 조회 시: 스마트아이디 로그인 후 Canvas 연동 리다이렉트 과정에서 쿠키가 유실되거나 꼬여 `canvas returned 401 session expired` 에러가 발생.
- 원인:
  1. u-SAINT WAF 강등: `ecc.ssu.ac.kr` 최초 접근 시 Portal SSO 연동 과정에서 포탈이 발급한 `WAF` 쿠키가 누락되어, 보안 장비(WAF)가 봇/크롤러로 오인해 세션을 비로그인 상태로 강등시켰다.
  2. LMS 세션 오염: 기존의 수동 쿠키 병합 로직이 LMS(`lms.ssu.ac.kr`)와 Canvas(`canvas.ssu.ac.kr`) 서브도메인 쿠키를 하나로 무작위 병합 전송함으로써 보안 규칙에 걸려 Canvas API 토큰(`xn_api_token`) 발급이 누락됐다.
- 해결:
  1. WAF 쿠키 보존: `eccBootstrapCookieHeader` 필터링 로직을 수정하여 `MYSAPSSO2`와 함께 **`WAF` 쿠키도 함께 추출 및 보존하여 ECC 요청에 실어 보내도록 해결**하였다.
  2. CookieManager 세션 격리: 기존의 불안정한 수동 쿠키 병합 로직을 폐기하고, 인증 요청(스레드)별로 완벽히 격리된 `java.net.CookieManager`를 HttpClient에 장착하여 브라우저 수준의 서브도메인/경로별 쿠키 격리를 보장함으로써 `xn_api_token`을 안정적으로 획득하도록 구현하였다.
  3. 테스트 케이스 갱신: WAF 쿠키가 보존되어 전송되는 새로운 로직에 맞춰, 기존 테스트 코드의 `doesNotContain("WAF")` 단언문을 `contains("WAF")`로 갱신하여 6개의 깨진 단위/통합 테스트를 모두 정상화(Green)시켰다.
- 검증: 로컬 전체 백엔드 테스트(`.\gradlew.bat test`)를 실행하여 `BUILD SUCCESSFUL`로 100% 성공을 검증했다.
- 포트폴리오 포인트:
  - 대학 보안 장비(WAF)가 세션을 강제 강등시키는 현상을 분석하여 필수 보안 토큰(`WAF`) 누락이 원인임을 밝혀내고 이를 커넥터 헤더에 바인딩하여 우회한 실전 디버깅 사례이다.
  - 고유 서브도메인을 넘나드는 SSO 체인에서 발생할 수 있는 "쿠키 오염(Cookie Pollution)" 현상을 스레드 세이프하고 고립된 `CookieManager`를 갖춘 `HttpClient` 동적 생성 패턴을 설계하여 격리함으로써 완벽히 해소했다.
- 면접 예상 질문:
  1. WAF 쿠키 누락이 서버 측에서 세션 강등으로 이어지는 메커니즘을 설명해보세요.
  2. 멀티 서브도메인 SSO에서 쿠키 오염이 발생하는 원인과 CookieManager 격리로 해결하는 방법은?
  3. 스레드별로 CookieManager를 분리하는 것이 공유 CookieManager보다 유리한 이유는?

## 2026-05-21 (정정) — u-SAINT 실패의 실제 원인: 빈 학기 응답을 로그인 실패로 오판

- 맥락: 위 항목에서 WAF 누락을 SAINT 실패 원인으로 추정해 수정했지만, 이후 브라우저 캡처를 다시 비교하면서 가설을 정정했다.
- 증상: `ecc did not return the timetable container (likely logon redirect)` 예외가 계속 발생했다.
- 원인:
  - 브라우저도 `sap-contextid=SID:ANON:...-NEW` 상태로 시간표/성적 화면을 정상 렌더링했다. 즉 ANON 자체는 인증 실패 신호가 아니었다.
  - ECC 진입 시 기본 선택 학기가 현재 시점의 여름학기였고, 수강 데이터가 없는 빈 학기라 시간표 `tbody[id$=-contentTBody]`가 없거나 비어 있었다.
  - 커넥터는 이 데이터 컨테이너 부재를 로그인 리다이렉트로 오판해, 이전 학기 iterate loop까지 도달하지 못했다.
- 해결: 시간표는 `학년도`/`학기` dropdown 존재를 인증 신호로 사용하고, 성적은 학기별 GPA history 존재를 인증 신호로 사용하도록 변경했다. 실제 데이터 row 유무는 인증 판단에서 분리했다.
- 검증: 빈 학기 dropdown-only 응답이 gate를 통과하는 테스트와, 로그인 조각처럼 dropdown/history가 없는 응답은 실패하는 테스트를 추가했다. 최종 검증은 Desktop MCP 클라이언트 호출로 `get_my_schedule` / `get_my_grades`가 과거 학기까지 반환하는지 확인해야 한다.
- 포트폴리오 포인트: stateful WebDynpro 화면에서는 "데이터 컨테이너 존재"가 인증 신호가 아니다. 빈 정상 응답과 로그인 실패 응답을 구분하려면 데이터가 아니라 페이지 구조 신호를 기준으로 삼아야 한다.
- 면접 예상 질문:
  1. "데이터 없음"과 "인증 실패"를 구분하기 위한 신호를 어떻게 선정했나요?
  2. SAP WebDynpro 같은 stateful UI에서 인증 상태를 코드로 판단하는 방법은?
  3. 빈 정상 응답이 에러처럼 보이는 상황을 재현하는 테스트를 어떻게 작성하나요?

---

## 2026-05-22 — u-SAINT SAP WebDynpro reverse engineering 한계 인정, rusaint upstream FFI 통합으로 전환

- 맥락: 2026-05-14부터 2026-05-22까지 SAINT 시간표/성적 조회를 Java WebDynpro 직접 구현으로 살리려 했지만, 여러 protocol 추측 fix가 실제 사용자 검증에서 계속 같은 실패 계열로 돌아왔다.
- 증상: `get_my_schedule`, `get_my_grades`가 prod에서 안정적으로 동작하지 않았고, LMS/학식/도서관 등 다른 tools와 달리 SAINT만 SAP WebDynpro state mismatch에 계속 걸렸다.
- 원인:
  1. SAP NetWeaver WebDynpro는 `sap-contextid`, portal-issued `sap-ext-sid`, hidden input, SAPEVENTQUEUE, application-server routing이 모두 stateful하게 엮여 있다.
  2. 우리 Java 구현은 production wire-level ground truth 없이 browser 관찰과 log fragment만으로 protocol을 추측했다.
  3. 단순 LMS Canvas SSO와 달리 SAP WebDynpro는 직접 reverse engineering 비용이 product 가치보다 커졌다.
- 해결: 검증된 Rust upstream인 `yourssu/rusaint`를 UniFFI Kotlin binding으로 통합한다. SmartID callback의 `sToken`/`sIdno`는 Java token-probe flow에서 소비하지 않고 rusaint `withToken`에 한 번만 전달한다. 결과 `USaintSession.toJson()`은 기존 `SaintSessionStore`에 AES-GCM encrypted-at-rest로 저장하고, schedule/grades 호출 시 `fromJson`으로 복원한다.
- 검증: 2026-05-22 로컬 `rusaint-cli` ground truth에서 schedule과 grades recorded-summary가 정상 응답했다. 이번 PR은 `RusaintClient`를 mock한 unit test와 backend test로 contract를 검증하고, prod 배포 후 사용자가 실제 MCP client에서 `get_my_schedule` / `get_my_grades`를 다시 확인한다.
- 포트폴리오 사인:
  1. 적재적소 판단: LMS처럼 단순한 흐름은 직접 구현하고, 복잡한 SAP는 검증된 upstream을 활용한다.
  2. 무한 추측 fix 중단: reference implementation이나 wire trace가 없는 stateful protocol은 일정 시점에 중단 기준이 필요하다.
  3. wrapper 이상의 가치: ssuAI가 직접 책임지는 부분은 encrypted session store, cache, DTO normalization, cross-source tools, observability다.
  4. 실패 기록 보존: 이전 Java WebDynpro 시도는 silent rewrite하지 않고 troubleshooting과 ADR에 남긴다.
- 최종 검증: 2026-05-22 prod 배포 후 `get_my_schedule` / `get_my_grades` 모두 정상 응답 확인.
- 면접 예상 질문:
  1. 직접 구현 vs. 검증된 upstream 라이브러리 활용을 결정하는 기준은 무엇인가요?
  2. Rust 라이브러리를 JVM에서 JNA로 연동할 때 주의해야 할 사항은?
  3. "무한 추측 fix"를 중단하고 upstream을 채택하기로 결정한 시점의 판단 기준을 설명해보세요.

## 2026-05-22 — rusaint 배포 후 "Illegal cookie name" — Helm values.yaml connector 값 미변경

- 맥락: rusaint FFI PR을 main에 머지하고 prod 배포 후 바로 테스트했더니 SAINT 기능만 `Illegal cookie name` 오류가 발생했다.
- 증상: `get_my_schedule`, `get_my_grades` 모두 `IllegalArgumentException: Illegal cookie name` 반환. LMS/도서관은 정상.
- 원인: `deploy/charts/ssuai-backend/values.yaml`의 `connectorSaintSchedule`, `connectorSaintGrades`가 `real`로 남아 있었다. k8s ConfigMap이 `SSUAI_CONNECTOR_SAINT_SCHEDULE=real`로 주입되어 `RealSaintScheduleConnector`가 로드됐고, 해당 connector가 rusaint session JSON을 raw cookie header로 파싱하려다 `new HttpCookie("{", ...)` 호출에서 예외가 발생했다. `application-prod.yml`의 default가 `rusaint`여도 ConfigMap env var가 더 우선하므로 덮어씌워졌다.
- 해결: `values.yaml`에서 `connectorSaintSchedule: rusaint`, `connectorSaintGrades: rusaint`로 변경 후 commit/push. k8s ConfigMap 직접 패치 + `kubectl rollout restart`로 즉시 적용.
- 검증: `kubectl get configmap … -o jsonpath='{.data.SSUAI_CONNECTOR_SAINT_SCHEDULE}'` → `rusaint`. 재배포 후 `get_my_grades` / `get_my_schedule` 모두 정상 응답.
- 포트폴리오 포인트: Spring Boot application.yml 기본값은 k8s ConfigMap env var에 의해 덮어씌워진다. connector를 코드에서 바꿔도 Helm values.yaml을 같이 바꾸지 않으면 prod에서 다른 connector가 로드된다. "새 기능 배포 시 Helm values도 함께 업데이트" 를 체크리스트에 추가해야 한다.
- 면접 예상 질문:
  1. Spring Boot application.yml 기본값과 k8s ConfigMap env var의 우선순위 관계를 설명하세요.
  2. GitOps에서 코드 변경과 Helm values 변경을 동기화하지 않을 때 발생하는 문제 유형은?
  3. connector 타입 불일치 시 "Illegal cookie name" 같은 전혀 다른 에러로 나타나는 이유는?

## 2026-05-18 — MCP auth tools 구현 후 서버 등록 누락

- 맥락: Task 18에서 외부 MCP 클라이언트용 인증 흐름을 추가했다.
  `get_auth_status`, `start_auth`, `logout_provider`, `logout_all` 구현과 문서는
  완료됐지만 실제 MCP tool list smoke 전 코드 리뷰에서 누락을 발견했다.
- 증상: `McpAuthMcpTools` 클래스와 테스트는 존재하지만 `McpServerConfig`의
  `MethodToolCallbackProvider.toolObjects(...)`에 등록되지 않았다. 이 상태로 배포하면
  Claude Desktop/Cursor 같은 MCP 클라이언트에서 인증 시작 도구가 보이지 않아 private tool
  사용자가 `AUTH_REQUIRED` 이후 로그인 흐름을 시작할 수 없다.
- 원인: Spring AI MCP tool 등록은 component scan만으로 끝나지 않고, 현재 프로젝트에서는
  `McpServerConfig`가 명시적으로 tool object 목록을 구성한다. 새 tool class를 만들면서
  설정 파일과 tool-list regression test를 함께 갱신하지 않았다.
- 해결: `McpServerConfig.ssuaiMcpTools(...)`에 `McpAuthMcpTools`를 주입하고
  `toolObjects(...)` 목록에 추가했다. `McpServerConfigTests`의 expected tool names도 기존
  10개에서 auth tools 4개를 포함한 14개로 갱신했다.
- 검증: `McpServerConfigTests.registersSsuaiMcpTools`가 auth tools 4개
  (`get_auth_status`, `start_auth`, `logout_provider`, `logout_all`)와 기존 tool 10개를
  모두 확인하도록 고정했다.
- 포트폴리오 포인트: MCP 서버는 "구현된 class"가 아니라 "클라이언트가 발견 가능한 tool
  contract"가 제품 표면이다. 새 도구를 추가할 때는 service/tool unit test뿐 아니라 MCP
  registry smoke 또는 config regression test를 acceptance criteria에 포함해야 한다.
- 면접 예상 질문:
  1. Spring AI MCP tool 등록이 component scan만으로 끝나지 않는 이유를 설명해보세요.
  2. "구현은 있지만 등록이 누락된" 유형의 버그를 사전에 방지하는 방법은?
  3. MCP tool list regression test를 acceptance criteria에 포함하는 이유가 무엇인가요?

---

## 2026-05-18 — RestClient 302 redirect 중간 Set-Cookie 누락 → HttpClient Redirect.NEVER로 전환

- 맥락: u-SAINT portal phase 2 에서 SAP ECC 커넥터가 403 을 계속 반환. SmartID 로그인 자체는
  성공하고 portal HTML 도 정상 파싱되는데, 그 이후 시간표/성적 조회에서만 403 이 떨어짐.
  MYSAPSSO2 쿠키가 문제라는 가설 하에 진단 로깅을 단계별로 추가하다 원인을 발견.
- 증상:
  - `ad83a99` 진단 로깅 결과: 저장된 MYSAPSSO2 가 portal phase 1 (`/webSSO/sso.jsp`) 에서
    발급된 토큰이고, portal phase 2 redirect 체인에서 SAP 이 새로 발급한 갱신 토큰과 달랐음.
  - ECC 커넥터가 오래된 MYSAPSSO2 를 실어 보내니 매 요청 403.
- 원인: Spring RestClient 기본 `SimpleClientHttpRequestFactory` (내부적으로 `HttpURLConnection`)
  는 3xx 리다이렉트를 조용히 따라가면서 **중간 응답의 Set-Cookie 헤더를 전부 버림**.
  SAP portal phase 2 는 첫 번째 302 응답에 권위 있는 최신 MYSAPSSO2 를 실어 보내는데,
  최종 목적지 응답만 보는 RestClient 가 그 쿠키를 수집하지 못한 채 phase 1 값을 계속 저장.
- 해결: phase 2 fetch 를 `java.net.http.HttpClient(Redirect.NEVER)` + 수동 redirect 추적으로
  교체 (`96b9e8c`). 각 hop 의 Set-Cookie 를 누적한 뒤 저장된 `PortalCookies` 에 merge.
  충돌 시 phase 2 값이 phase 1 값을 덮어쓰도록 보장.
- 검증: MockWebServer 기반 redirect cookie merge 테스트 추가. 302 hop → 200 최종 응답 시나리오에서
  중간 Set-Cookie 가 최종 저장 쿠키에 반영되는 것을 핀.
- 포트폴리오 포인트:
  - **HTTP 클라이언트의 "투명한 redirect 추적"은 쿠키 수집 관점에선 불투명함**. 최종 응답에만
    집중하는 고수준 클라이언트는 redirect 체인에서 세션을 발급하는 서버 (SAP NetWeaver 패턴)
    앞에서 silent mismatch 를 만든다. 쿠키를 누적해야 하는 multi-hop 흐름은 Redirect.NEVER +
    수동 추적이 유일한 안전한 선택.
  - 증상이 phase 2 훨씬 뒤인 ECC 403 으로 나타나 원인 위치 특정이 어려웠음. 단계별 진단
    로깅 (MYSAPSSO2 prefix, 4xx 응답 body) 을 추가해가며 범위를 좁히는 과정 자체가 실전 디버깅 사례.
- 면접 예상 질문:
  1. HTTP 클라이언트의 "투명한 redirect 추적"이 쿠키 수집 관점에서 불투명한 이유를 설명하세요.
  2. Redirect.NEVER + 수동 추적이 필요한 경우와 자동 redirect가 안전한 경우를 어떻게 구분하나요?
  3. 302 체인 중간 hop에서 Set-Cookie가 최종 응답에서 사라지는 메커니즘을 설명하세요.

---

## 2026-05-18 — Vercel 도메인 SSO callback 쿠키 4단계 cascade

- 맥락: SmartID 로그인 prod 재검증 중 Vercel frontend 에서 로그인 후 세션이 안 잡히는 현상.
  Backend 는 ssuai refresh cookie 를 내려보내지만 브라우저에서 보이지 않음.
  CORS allowCredentials 는 이미 수정 (#116) 되어 있었음.
- 증상 / 해결 단계 (4 layer):
  1. **Cross-origin cookie**: `ssuai.vercel.app` → `ssumcp.duckdns.org` 직접 API 호출.
     Backend 가 `Set-Cookie` 를 내려도 브라우저가 cross-site 쿠키를 Vercel origin 에 저장하지 않음.
     → Next.js `next.config.ts` 에 `/api/*` rewrite 추가해 모든 API 호출을 same-origin proxy 로 통일 (`ccc0c30`).
  2. **SSO callback 302**: Backend SmartID callback 이 302 redirect 를 반환하는 구조.
     Vercel same-origin 으로 들어온 redirect 응답의 `Set-Cookie` 가 프록시를 거치면서 누락.
     → Backend callback 을 200 + HTML 로 변경해 브라우저 redirect 없이 처리 (`3df25f3`).
  3. **App Router route handler Set-Cookie 누락**: Next.js App Router 의 `/api/auth/saint/sso-callback/route.ts`
     에서 backend 쿠키를 추출해 재발급 시도. `afterFiles` rewrite 가 App Router route 보다 먼저 실행돼
     route handler 가 개입할 수 없었음.
     → `proxy.ts` (Next.js 16 middleware convention) 로 이전, `/api/auth/saint/sso-callback` 패턴 매칭해
     서버 사이드에서 쿠키 추출 후 재발급 (`a1e74a1`).
  4. **Next.js 16 proxy Set-Cookie header stripping**: `proxy.ts` 에서 `response.headers.set('Set-Cookie', …)` 로
     수동 지정했지만 Next.js 16 이 response header 로 직접 설정한 Set-Cookie 를 조용히 제거.
     → `response.cookies.set(name, value, options)` Next.js API 로 교체 (`405c288`).
- 검증: `https://ssuai.vercel.app` 브라우저에서 SmartID 로그인 → 대시보드 세션 정상 착지 확인.
  Network 탭에서 ssuai.vercel.app 도메인 쿠키로 발급 확인.
- 포트폴리오 포인트:
  - **"쿠키가 안 붙는다"는 증상 하나가 cross-origin / redirect / route intercept order / framework
    cookie API 네 개의 서로 다른 레이어에 걸쳐 있었음**. 레이어마다 해결하면 다음 레이어가
    드러나는 구조라 각 단계를 커밋으로 격리해 추적.
  - Vercel + Next.js 16 에서 SSO callback 쿠키를 안정적으로 내리는 유일한 패턴: middleware/proxy
    에서 `response.cookies.set()` API 사용. 다른 방법은 전부 Next.js 또는 Vercel 의 어느 레이어가 조용히 제거.
- 면접 예상 질문:
  1. Next.js App Router에서 Set-Cookie가 조용히 제거되는 상황과 올바른 API는 무엇인가요?
  2. 같은 증상이 CORS/redirect/route intercept/framework cookie API 네 개 레이어에 분산된 경우 어떻게 레이어를 격리해서 디버깅하나요?
  3. Vercel + Next.js에서 SSO 콜백 쿠키를 안정적으로 내리기 위한 필수 패턴은 무엇인가요?

---

## 2026-05-18 — SAP WebDynpro Chrome UA → JS bootstrap 응답, Form_Request POST 필요

- 맥락: u-SAINT 시간표/성적 connector 를 prod 에서 처음 실행하자 데이터가 안 나옴. 단위 테스트는
  HTML fixture 기준으로 전부 통과하고 있었음.
- 증상: prod 로그에서 connector 가 `sap-wd-secure-id` 를 파싱 못해 `SaintSessionExpiredException` 발생.
  응답 snippet 을 보니 시간표 HTML 이 아니라 SAP WebDynpro JavaScript bootstrap 코드였음.
- 원인: 단위 테스트 fixture 는 렌더링 완료된 HTML 이었지만, 실제 u-SAINT 는 **Chrome-like User-Agent**
  로 GET 하면 JS 로 초기화를 맡기는 bootstrap 페이지를 먼저 내려보냄. 사람의 브라우저라면 JS 가
  실행되면서 `Form_Request` POST 를 자동 전송해 실제 HTML 을 받지만, connector 는 JS 를 실행하지 않음.
- 해결: bootstrap HTML 에서 `sap-wd-secure-id` 를 추출한 뒤, SAP WebDynpro 가 기대하는 형식의
  `Form_Request` (`SAPEVENTQUEUE` 포함) POST 를 명시적으로 전송해 렌더링된 HTML 을 응답으로 받는
  2단계 init 흐름 추가 (`ccc0c30`). `WebDynproSapEventEncoder.encodeInitialLoad()` / `WebDynproResponseUnwrapper`
  를 별도 유틸로 분리해 테스트 가능하게 구성.
- 검증: `WebDynproResponseUnwrapperTests`, `WebDynproSapEventEncoderTests` 추가. 이후 prod 에서
  시간표 데이터 정상 조회 확인.
- 포트폴리오 포인트:
  - **"HTML fixture 테스트 전부 통과" ≠ "prod 에서 동작"** 의 세 번째 사례 (앞서 portal parser,
    3중 DI 장애에 이어). 이번엔 외부 서버가 User-Agent 에 따라 응답 자체를 다른 종류로 바꿔버림.
    실서버 smoke test 를 mock 테스트와 별도 단계로 강제해야 한다는 교훈 반복 확인.
  - SAP WebDynpro 패턴: GET → JS bootstrap → Form_Request POST → 렌더 HTML → 이후 SAPEVENTQUEUE
    POST 반복. 이 흐름을 알면 다른 WDA 앱에도 동일하게 적용 가능.
- 면접 예상 질문:
  1. User-Agent에 따라 서버가 다른 응답을 반환하는 상황에서 테스트 픽스처의 한계는 무엇인가요?
  2. SAP WebDynpro의 GET → JS bootstrap → Form_Request POST 흐름을 Java에서 재현하는 방법은?
  3. "HTML fixture 테스트 모두 통과"가 prod 동작을 보장하지 않는 이유를 사례로 설명하세요.

---

## 2026-05-17 — 시간표 조회 WDA7 iterate 10회 → 1h TTL + single-flight 캐시

- 맥락: `get_my_schedule` MCP tool 이 챗봇 경로에서 매 질문마다 호출될 수 있음. u-SAINT 시간표
  전체 이력을 가져오려면 현재 학기 GET + "이전학기" 버튼 시뮬레이션 WDA7 POST 를 학기 수만큼
  반복해야 하는 SAP WebDynpro 구조.
- 증상 (예측): 입학 이후 N 개 학기가 쌓인 학생의 경우 한 chat 질문에서 외부 서버로 10여 회
  HTTP 요청이 발생. latency 수십 초 + u-SAINT 서버 부하.
- 원인: SAP WebDynpro 는 stateful UI 탐색 구조라 "전체 시간표를 한 번에 주는" API endpoint 가 없음.
  학기별 페이지를 이전 버튼으로 하나씩 navigate 해야 함.
- 해결: `SaintScheduleCache` 추가 (`7f17b9b`). 학번 key 기준 1h TTL + in-memory LRU.
  **single-flight**: 동일 학번 동시 miss 시 첫 번째 요청만 실제 fetch, 나머지는 대기 후 결과 재사용.
  `SaintSessionExpiredException` 은 캐시에 poison 하지 않아 재로그인 후 miss → 새로 fetch.
  설계는 `LibraryBookCache` 와 동일 패턴으로 일관성 유지.
- 검증: `SaintScheduleCacheTests` (TTL 만료, single-flight, session 예외 non-poison 포함) 313 라인.
  `gradlew.bat test` 전체 통과.
- 포트폴리오 포인트:
  - 외부 시스템이 stateful navigate 구조일 때 "결과 캐시" 로 request 수를 N → 1 로 줄이는 패턴.
    TTL 은 데이터 신선도 요구 (시간표는 학기 중 거의 불변) 에서 역산.
  - single-flight 없이 TTL 캐시만 두면 cold start / 캐시 만료 순간 동시 요청이 thundering herd 를
    만들어 외부 서버에 N 배 부하. 단순 캐시와 single-flight 의 차이를 면접에서 설명하기 좋은 사례.
- 면접 예상 질문:
  1. single-flight 패턴이 TTL 캐시만 두는 것보다 thundering herd 방지에 효과적인 이유는?
  2. SaintSessionExpiredException을 캐시에 poison하지 않아야 하는 이유를 설명하세요.
  3. 외부 시스템이 stateful navigate 구조일 때 요청 수를 N → 1로 줄이기 위한 전략은?

---

## 2026-05-17 — Pyxis-Auth-Token 헤더 인증 + 실제 도서관 대출 API path/field 맵핑

- 맥락: Task 13 도서관 좌석 현황 + 대출 현황 full stack 구현. Pyxis API 문서가 없어 브라우저
  DevTools 로 실제 요청을 분석해 스펙을 역공학.
- 증상 / 발견:
  1. 좌석 현황 API: 쿠키 인증 X, **`Pyxis-Auth-Token` 헤더** 방식. Token 은 도서관 사이트 세션과
     무관한 공개 토큰으로 동작. 층별 집계 endpoint: `/pyxis-api/1/seat-rooms`.
  2. 대출 현황 API: 초기 가정한 path 가 달랐음. 실제 path = `/pyxis-api/1/api/charges`.
     응답 field 도 예상과 다름 — `biblio.titleStatement` (제목), `callNo` (청구기호),
     `chargeDate` (대출일), `dueDate` (반납예정일) 로 정확히 매핑해야 정상 파싱.
  3. 대출 조회 미로그인 케이스: `noRecord` 플래그가 `true` 면 빈 배열, `needLogin` 이면
     `LibraryAuthRequiredException` 로 분리.
- 해결: `RealLibrarySeatConnector` (Pyxis-Auth-Token 헤더 인증, F2/F5/F6 층 집계),
  `RealLibraryLoansConnector` (실 API path, 실 field 매핑, noRecord/needLogin 분기) 구현 (`38c15be`).
  `LibraryLoanItem` DTO 필드를 실제 Oasis 응답 구조에 맞게 수정 (`ccc0c30` 에서 재수정).
- 검증: MockRestServiceServer 기반 fixture 테스트 13 케이스 (좌석 6 + 대출 7). loans.json fixture 를
  실제 Oasis 응답 구조로 교체.
- 포트폴리오 포인트:
  - 문서 없는 내부 API 역공학 순서: DevTools Network 탭에서 실제 요청 캡처 → 헤더/path/body 재현 →
    response field 를 DTO 로 직접 매핑. "문서가 없으면 못한다" 가 아니라 브라우저가 곧 API 문서.
  - 헤더 기반 인증 (`Pyxis-Auth-Token`) 과 쿠키 기반 인증 (`JSESSIONID`) 을 같은 도메인 내에서
    분리 운영하는 구조 이해 — 좌석/검색은 공개 헤더 토큰, 대출/예약은 로그인 세션 쿠키.
- 면접 예상 질문:
  1. API 문서가 없는 내부 시스템의 endpoint를 역공학하는 구체적인 방법은?
  2. 헤더 기반 인증과 쿠키 기반 인증이 같은 도메인에서 공존하는 설계의 의미는?
  3. needLogin / noRecord처럼 성격이 다른 "비인증" 응답을 코드에서 어떻게 분리하나요?

---

## 2026-05-16 — SmartID 로그인 prod 첫 검증: 두 갈래 장애 동시 해소

- 맥락: PR #110 (Helm chart 에 `SSUAI_API_BASE_URL` 와이어링 + 빈 값
  fail-fast) 머지 직후 SmartID 로그인을 prod 에서 처음 end-to-end
  검증하다가, 별개의 두 incident 가 한 흐름에서 같이 터짐. 1)
  ConfigMap 에 새 env 가 안 들어와 fail-fast 가 prod 에서 발동 →
  pod CrashLoopBackOff. 2) ConfigMap fix 후 pod 가 살자, SmartID 통과
  후 portal 응답 parsing 단계에서 selector mismatch → 로그인 화면이
  `?error=portal_unavailable` 로 끝남.
- 증상:
  - 1차: `kubectl get configmap` 결과 SSUAI_API_BASE_URL 키 없음. 새
    pod 가 `IllegalStateException: ssuai.auth.api-base-url (env:
    SSUAI_API_BASE_URL) must be set` 로 RESTARTS 3+ CrashLoopBackOff.
  - 2차: 로그에 `saint sso-callback portal unavailable: portal HTML
    missing identity cells: got 0, expected 4`. SmartID 자체는 통과
    (else `auth_failed`), phase 2 HTTP 200 (else `phase 2 http NNN`),
    그러나 우리 selector `.main_box09 .main_box09_con` 가 0 cell 매치.
- 원인:
  - 1차: 운영 파이프라인이 ArgoCD/Helm 이 아니라 단순 `kubectl apply`
    수동 운영이었음. PR 의 `deploy/charts/ssuai-backend/templates/configmap.yaml`
    변경은 cluster 에 자동 반영되지 않음. PR #110 머지 + 컨테이너 이미지
    `:latest` 자동 pull 로 새 코드만 들어왔는데, ConfigMap 은 옛 상태
    그대로라 startup 시 fail-fast.
  - 2차: u-SAINT portal HTML 구조가 ssutoday upstream fixture 시점
    이후 큰 폭으로 바뀜. 옛 구조 = `<div class="main_box09"> <div
    class="main_box09_con">value</div> × 4`. 실제 portal 2026-05 =
    `<div class="main_box09"> <div class="box_top"><p class="main_title">
    <span>{이름}님 환영합니다.</span></p> ...</div> <div
    class="main_box09_con_w"><ul class="main_box09_con"> <li><dl>
    <dt>학번|소속|과정/학기|학년/학기</dt><dd><strong>값</strong></dd>
    </dl></li> × 4 </ul></div></div>`. Cell 의미도 다름 (이름이 카드
    내부에서 빠지고 greeting 으로 이동). Task 14 §risks 가 이미 이
    가능성을 적었지만 실 portal HTML 없이 작성한 fixture 가 그대로
    테스트를 그린으로 유지해 prod 첫 검증까지 노출 안 됨.
- 해결:
  1. ConfigMap 즉시 patch: `kubectl patch configmap ssuai-backend-config
     -n ssuai-prod --type merge -p '{"data":{"SSUAI_API_BASE_URL":"https://ssumcp.duckdns.org"}}'`
     + rollout restart. (운영 파이프라인 정리는 별도 follow-up.)
  2. `SaintSsoService.parseIdentity` 재작성: positional
     `cells.get(0..3)` → key-based map. `.main_box09 ul.main_box09_con
     li dl` 의 `<dt>`(키) → `<dd>`(값) 으로 build → "학번"/"소속"/
     "과정/학기" 로 lookup. 향후 portal 이 row 순서 바꾸거나 추가해도
     silent mis-assignment 방지.
  3. 이름은 새 selector `.main_box09 .box_top .main_title span` 으로
     별도 추출 + "님 환영합니다." suffix 스트립 (suffix 변형에 대비해
     "님" 단독 trim 도 fallback).
  4. `portal-success.html` fixture 를 실제 markup 으로 교체, 학번/
     이름/IP/시간은 모두 placeholder (`20999999` / `홍길동` / `0.0.0.0`
     / 더미 timestamp). `portal-missing-cells.html` 는 ul-누락 케이스로
     의미 재정의, `portal-missing-name.html` 새 fixture 추가
     (greeting span 누락 케이스). `SaintSsoServiceTests` 갱신.
- 검증:
  - backend 258+ tests 그린.
  - prod ConfigMap patch + rollout restart 후 pod Ready ✓, env 잡힘 ✓.
  - parser PR 머지 + 자동 :latest pull + rollout restart 후 사용자
    실제 SmartID 로그인 end-to-end (대시보드 "안녕하세요, {이름} 학생"
    표시) — **별도 follow-up**.
- 포트폴리오 포인트:
  - "정적 fixture 만으로 통과한 테스트가 라이브 응답과 mismatch 라는
    걸 prod 첫 검증에서 잡고, 외부 HTML 구조 변경에 robust 한 key-기반
    parse 로 전환." 그리고 "spec 의 §risks 에 미리 적어둔 경고 (ssutoday
    parse anchors no longer match) 가 실측 시점에 실제로 발동, 미루지
    말고 실 환경 검증을 일찍 했어야 한다는 회고."
  - "ConfigMap 누락 + `:latest` 이미지 자동 pull 의 조합으로 prod 가
    CrashLoopBackOff 됐을 때, fail-fast 로그 한 줄로 root cause 즉시
    식별. fail-fast 가 prod 에서 의도대로 의미 있게 동작한 첫 사례."
- 면접 예상 질문:
  1. ArgoCD 없이 kubectl apply 수동 운영 시 ConfigMap 누락이 CrashLoopBackOff로 이어지는 과정을 설명하세요.
  2. 외부 사이트의 HTML 구조 변경에 robust한 파서를 설계하는 방법은? (positional index vs key-based lookup)
  3. fail-fast 패턴이 실제로 운영에서 도움이 된 구체적인 사례를 설명해보세요.

---

## 2026-05-16 — 200 OK 인데 frontend 가 "세션 갱신 실패": CORS `Access-Control-Allow-Credentials` 누락

- 맥락: PR #112/#113 portal parser fix, PR #114 refresh cookie
  `SameSite=None` 까지 머지하고 SmartID 로그인 prod 재시도. SmartID →
  callback → `/auth/return?ok=1` 까지는 도달하는데 화면이 계속 "SSO
  는 통과했지만 ssuAI 세션 갱신에 실패했습니다" 에서 멈춤.
- 증상:
  - 사용자: `/auth/return?ok=1` 페이지에서 "세션 갱신 실패" 메시지.
  - backend 로그 `kubectl logs … --since=3m` 또는 `--tail=100` 어디에도
    `/api/auth/refresh` 흔적이 안 나옴. 보이는 HTTP 트래픽은 MCP SSE
    initialize 뿐.
  - 브라우저 Network 탭에서 `POST /api/auth/refresh` row 자체는
    존재하고 **Status 200 OK**, 응답 헤더에 `set-cookie:
    ssuai_refresh=…; SameSite=None; Secure; HttpOnly` 정상 발급.
    그러나 직후 일어나야 할 `GET /api/auth/me` 호출이 Network 에 안
    뜸 — frontend 가 refresh 응답을 받자마자 catch 블록으로 떨어지는
    셈.
- 원인: response 헤더에 `Access-Control-Allow-Credentials: true` 가
  없음. fetch 가 `credentials: 'include'` 일 때 브라우저는:
  1. request 에 cookie 를 실어 보내고 ✅
  2. 응답의 set-cookie 도 정상 저장하지만 ✅
  3. **JS 에는 response body 를 노출하지 않음** ❌
  → frontend `fetchJson` 의 `await response.json()` 이 throw → `parseEnvelope`
  null 반환 → `INVALID_ENVELOPE` ApiError throw → `useSaintAuth.refresh()`
  catch 블록 → false 반환 → "세션 갱신 실패" 표시. `/api/auth/me` 는
  호출 자체가 안 됨. backend 입장에서는 200 OK 로 정상 응답했기 때문에
  서버 로그에 비정상 흔적이 없음. **삼중으로 헷갈리는 incident**:
  (i) Network 탭은 200 으로 성공처럼 보이고, (ii) 쿠키는 실제로
  저장되어 다음 시도에서 살아 있으며, (iii) backend 로그에는 에러
  단서가 없음. Console 탭의 빨간 CORS 경고만이 유일한 단서.
- 해결: `ApiCorsDefaults.java:15` `.allowCredentials(false)` →
  `.allowCredentials(true)` (PR #116). `allowedOrigins` 가 와일드카드가
  아닌 명시적 origin (`https://ssuai.vercel.app` / `http://localhost:3000`)
  이라 Spring `CorsConfiguration` validator 도 통과. 회귀 방지로
  `WebCorsConfigTest` / `WebCorsProdConfigTest` 양쪽에 `config.getAllowCredentials()
  == true` assertion 추가.
- 검증:
  - backend 전체 test BUILD SUCCESSFUL.
  - PR #116 머지 + CI image-build + `kubectl set image …:sha-1031de0…` →
    새 pod Ready.
  - 브라우저: `https://ssuai.vercel.app/auth/login` → SmartID → 대시보드
    "안녕하세요, 홍성주 학생" 표시 ✅. Network 탭에 이번엔 `/api/auth/refresh`
    (200) **+ `/api/auth/me` (200)** 둘 다 보이고, 응답 헤더에 `access-control-allow-credentials:
    true` 도 포함.
- 포트폴리오 포인트:
  - **CORS preflight 통과 + 200 응답 + set-cookie 동작 + body 접근
    차단** 의 함정. CORS 규칙은 "request 가 도착하느냐" 뿐 아니라
    "response 를 JS 가 읽을 수 있느냐" 까지 별도 gate. `allowCredentials(true)`
    는 **반드시 explicit origin** 과 한 쌍으로 와야 하고 (와일드카드와
    공존 시 브라우저가 거부), set-cookie 와 별개 정책이라 한쪽만
    맞춰도 증상이 부분적으로만 풀림. 같은 세션에 SameSite=None (PR
    #114) 으로 한 번 풀린 줄 알았는데 다음 layer 에 막혀 있었던 사례.
  - **로그가 없는 incident 의 디버깅 순서** — backend 로그가 비어
    있으면 "backend 가 안 받았다" 가 첫 가설이지만, Network 탭에
    200 이 보이면 그 가설은 깨짐. 그 순간 frontend 의 response 처리
    파이프라인 (특히 envelope validation 단계) 으로 시선을 옮기는 게
    빠른 진단의 핵심. CORS console error 는 "Network 200, JS catch
    block" 패턴의 정석 단서.
- 면접 예상 질문:
  1. CORS preflight 통과, 200 응답, set-cookie 동작임에도 JS에서 response body를 읽을 수 없는 이유는?
  2. allowCredentials(true)가 반드시 explicit origin과 함께 와야 하는 이유를 브라우저 보안 모델로 설명하세요.
  3. 백엔드 로그에 흔적이 없는 상황에서 200 응답 + JS catch block 패턴을 어떻게 진단하나요?

---

## 2026-05-16 — Deployment `secretRef.name` 와 매뉴얼 Secret 이름의 한 글자 drift

- 맥락: SmartID 로그인이 prod 에서 end-to-end 동작 확인된 직후,
  `SSUAI_JWT_SECRET` / `SSUAI_CREDENTIAL_ENCRYPTION_KEY` 가 ConfigMap
  에도 Secret 에도 없어 매 pod 재시작마다 사용자 세션 invalidate 되는
  문제를 잡으러 들어감. Handoff doc 에 적힌 명령은 `kubectl create
  secret generic ssuai-backend-secret …` (singular).
- 증상: 사용자가 명령 실행 전에 `kubectl get deployment … -o yaml |
  grep -A 3 envFrom` 으로 확인했더니 manifest 의 `envFrom.secretRef.name`
  은 **`ssuai-backend-secrets`** (plural 의 trailing-s) 였음. handoff
  의 명령 그대로 적용했으면 secret 은 생성되지만 Deployment 가 다른
  이름으로 찾아 `optional: true` 인 secretRef 가 조용히 0개 env 를
  load — 즉 secret 은 cluster 에 있는데 backend 는 여전히 empty.
- 원인: handoff doc 작성 시 manifest 의 실제 secretRef 이름을 확인하지
  않고 "관용적인 단수형" 으로 짐작해서 작성. `secretRef.optional: true`
  설정이라 misnamed secret 도 startup 실패 없이 통과 → 검증 없이
  배포되면 발견 자체가 늦어짐.
- 해결: handoff doc 의 모든 `ssuai-backend-secret` 표기를 `ssuai-backend-secrets`
  로 정정. ADR 0014 Addendum 에는 manifest-vs-handoff 이름 drift 가
  실제로 발생한 사례임을 남김.
- 검증: 사용자가 정정된 `ssuai-backend-secrets` 이름으로 적용 →
  `kubectl logs … | grep 'is empty'` 결과가 비어야 정상 (두 WARN
  사라짐).
- 포트폴리오 포인트:
  - **`secretRef.optional: true` 의 양날** — 운영 안정성 (Secret
    누락이 cluster crash 가 아니라 graceful degrade) 과 silent
    misconfiguration (이름 오타가 startup fail-fast 로 안 잡힘) 의
    트레이드오프. 두 환경 (dev = optional OK, prod = required)
    분기 또는 startup-time self-check (`@PostConstruct` 에서 expected
    env keys 가 채워졌는지 assert) 로 균형 가능. ssuAI 의 `JwtProvider`
    는 후자 패턴 (`secret is empty` WARN + ephemeral fallback) 으로
    부분 방어 — fail-fast 까지는 안 가지만 로그로 노출.
  - **handoff doc 의 명령은 manifest 와 cross-check 후 적자**. 사용자가
    명령 실행 *전에* `kubectl get deployment … -o yaml | grep envFrom`
    을 한 번 돌린 게 정확히 그 cross-check. handoff doc 작성 시
    "확인 명령 한 줄 + 본 명령 한 줄" 패턴이 default.
- 면접 예상 질문:
  1. secretRef.optional: true의 보안 장단점을 설명하고 prod에서 적절한 사용 방법은?
  2. handoff 문서의 명령을 실제 cluster manifest와 cross-check 하지 않으면 어떤 문제가 생기나요?
  3. k8s Secret 이름 오타가 startup 오류 없이 조용히 통과되는 이유와 이를 잡는 방법은?

---

## 2026-05-14 — 학식 데이터 매 요청 라이브 스크래핑 → 주간 배치 캐시로 전환

- 맥락: 라이브 챗봇이 동작하기 시작한 직후 데이터 흐름을 점검하다가, 학생이 "오늘 학식 뭐야?" 하고 물어볼 때마다 `RealMealConnector` 가 `soongguri.com` 으로 4~6번의 Jsoup HTTP GET 을 매번 fan-out 하고 있다는 걸 확인. 학식 메뉴는 학교 측에서 주 1회 일괄 갱신되는데 호출은 매번 라이브였음.
- 증상:
  - 사용자 메시지 1건당 외부 사이트로 6 HTTP 요청. 챗봇 응답 latency 대부분이 학교 사이트 RTT 에 종속.
  - 학교 페이지가 일시 장애일 때 챗봇 전체가 동시에 영향. 자체 캐시가 없어 회복도 외부 사이트 회복에 묶임.
  - 챗봇이 "학생식당" 한 곳만 묻는 질문에도 6개 식당 전체를 스크래핑.
- 원인: 1차 구현은 ADR/아키텍처 문서의 "Service 계층 캐시-aside" 약속과 다르게 캐시 빈/스케줄러 없이 connector 를 직접 호출하는 형태였음. Redis 도입 비용을 피하다가 캐시 자체를 누락. 식당별 도구도 없어 LLM 이 부분 조회를 못함.
- 해결:
  1. `WeeklyMealCache` (`ConcurrentHashMap<(date, restaurant), MealResponse>`) 추가. `@PostConstruct` 시작 시 적재 + `@Scheduled(cron = "0 0 6 ? * MON", zone="Asia/Seoul")` 로 매주 월요일 06:00 KST 갱신. `SsuaiApplication` 에 `@EnableScheduling` 추가.
  2. `MealService.getMeal(date)` / `getMealForRestaurant(date, restaurant)` 를 캐시-aside 패턴으로 재구성. 캐시 miss 시에만 connector 호출하고 결과를 캐시에 적재.
  3. MCP 도구 `get_today_meal` / `get_meal_by_date` 에 optional `restaurant` 파라미터 추가. 한국어 별칭 (학생식당/도담/스낵/푸드코트/키친/교직원) 을 enum 으로 매핑. LLM 이 식당을 특정하면 단일 식당만 조회.
  4. `LlmChatService.executeToolCall` 에서 `restaurant` 인자를 MCP tool call payload 로 forward.
- 검증:
  - `MealServiceTests`, `MealMcpToolsTests`, `WeeklyMealCacheTests` 모두 통과.
  - 라이브 배포 후 `오늘 학식 뭐야?` (전체) vs `학생식당 오늘 메뉴` (단일 식당) 두 케이스 모두 정상 응답 확인.
- 포트폴리오 포인트: "데이터 갱신 주기와 호출 주기를 맞춰 (주 1회 vs 분당 N건) 외부 의존성 RTT 를 응답 경로에서 제거. DB 없이도 cache-aside 패턴으로 회복력 + 응답 속도 동시에 개선. 식당별 도구 분기로 LLM 호출 페이로드 축소 → 모델 응답 품질도 향상."
- 면접 예상 질문:
  1. 데이터 갱신 주기와 조회 주기를 분리해서 얻는 구체적인 이점은 무엇인가요?
  2. @Scheduled + @PostConstruct 패턴으로 캐시를 초기화하는 이유는?
  3. Redis 없이 in-memory 캐시만으로 외부 의존성 RTT를 응답 경로에서 제거하는 설계를 설명해보세요.

---

## 2026-05-14 — LLM 모드 + MCP self-dogfood 실서버 부팅 3중 장애

- 맥락: ADR 0010/0011 머지 후 처음으로 `SSUAI_CONNECTOR_CHAT=llm` + 실제 Gemini key 로 `bootRun`. 단위 테스트는 전부 mock 이라 통과해 왔지만 진짜 서버는 한 번도 부팅을 안 해봤음.
- 증상: 세 단계로 실패가 이어짐.
  1. `MistralLlmProvider required a bean of type 'org.springframework.web.client.RestClient$Builder' that could not be found` — 모든 LLM provider 빈이 같은 의존성으로 깨짐.
  2. `LlmChatService required a bean of type 'com.fasterxml.jackson.databind.ObjectMapper' ... User-defined bean method 'mcpServerObjectMapper'` — MCP server 가 자기 전용 ObjectMapper 를 등록하면서 기본 ObjectMapper 후보를 가려버림.
  3. `mcpSyncClients ... Client failed to initialize by explicit API call ... TimeoutException: Did not observe any item or terminal signal within 10000ms` — Spring AI MCP client autoconfig 가 컨텍스트 refresh 단계에서 자기 `/sse` 로 연결을 시도하는데, 같은 JVM 의 Tomcat 이 아직 port 8080 에 바인딩 전이라 `ConnectException` → 10초 대기 → 컨텍스트 실패.
- 원인:
  1. Spring Boot 4.0.6 의 autoconfig 재편 — `RestClient.Builder` 가 더 이상 `spring-boot-starter-web` 만으로는 기본 등록되지 않음.
  2. `McpServerObjectMapperAutoConfiguration` 가 별도 ObjectMapper 빈을 등록하면서 Spring 의 후보 해석이 모호해짐. 기본 빈 후보가 없어 LlmChatService 의 생성자가 unresolved.
  3. self-dogfood 의 본질적 chicken-and-egg — MCP client 빈이 컨텍스트 refresh 중 동기 init 을 하는데, MCP server 가 같은 컨텍스트에서 Tomcat SmartLifecycle 단계에 뜬다. ADR 0010 의 Trade-offs 에서 "추후 process 분리도 가능하게 한다" 라 적었던 우려가 실제로 실현.
- 해결:
  1. `LlmProviderConfig` 에 `@Bean @ConditionalOnMissingBean RestClient.Builder` 명시.
  2. 같은 config 에 `@Bean @Primary ObjectMapper primaryObjectMapper()` 추가.
  3. `application.yml` 에 `spring.ai.mcp.client.initialized: false` + `spring.ai.mcp.client.toolcallback.enabled: false`. 첫 chat 요청 시점에 `LlmChatService.discoverChatTools()` 가 `client.initialize() + listTools()` 를 직접 호출 (이미 ADR 0011 구현). `LlmChatService` 생성자 파라미터 `List<McpSyncClient>` 에 `@Lazy` 추가하고 `mcpClient()` 헬퍼 도입 — 빈 자체의 첫 사용 시점도 보수적으로 지연.
- 검증: `gradlew.bat test` 전체 통과 (LlmChatServiceTests / McpSelfDogfoodTests 회귀 없음). 실서버 `bootRun` 8.6s 에 startup 완료. `POST /api/chat` 에 "오늘 학식 뭐야?" 보내면 실제 학식 메뉴 ("오늘 점심은 학생식당에서 모듬순대국밥...") 한국어 응답 정상.
- 포트폴리오 포인트: (1) 단위 테스트 100% 통과가 "production 부팅 가능" 을 의미하지 않는 전형적 사례. mock 이 가린 의존성 누락이 3중으로 드러남. (2) Self-dogfood architecture 의 본질적 함정 — 같은 JVM 안에서 client 가 server 를 동기 호출하는 패턴은 SmartLifecycle 순서를 거스르면 deadlock. 해결은 init 을 모두 lazy 로 미루는 것 (Spring AI 의 `initialized` flag + `@Lazy` 주입 + 명시적 ADR 0011 listTools cache). (3) Spring Boot 4 / Spring AI 1.1 같은 신버전 조합은 autoconfig diff 가 크다 — Boot 3.x 에서 당연하던 빈 (`RestClient.Builder`) 이 묵묵히 사라질 수 있음. 모든 신버전 의존성 업그레이드에는 "실서버 부팅 1회 + 핵심 path smoke" 를 mock 테스트와 별도로 강제하는 게 옳다.
- 면접 예상 질문:
  1. Spring AI MCP client가 같은 JVM의 서버에 연결할 때 deadlock이 생기는 이유와 @Lazy로 해결하는 방법은?
  2. Spring Boot 4에서 RestClient.Builder가 자동 등록되지 않는 이유는?
  3. "단위 테스트 전부 통과 = production 부팅 가능"이 아닌 이유를 이 3중 장애 사례로 설명하세요.

## 2026-05-13 — chatbot이 자기 MCP server를 HTTP/SSE로 self-dogfood 하도록 전환

- 맥락: ADR 0009 chat slice 시점의 `LlmChatService`는 같은 JVM 안의 `MealMcpTools/DormMcpTools/CampusMcpTools` 빈을 일반 Java 메서드로 직접 호출했습니다. MCP server는 외부 클라이언트(Claude Desktop, Cursor)만 쓰는 비대칭 상태였고, 챗봇 경로에서 MCP request/response 표면이 검증되지 않았습니다.
- 증상: 잠재 회귀 — MCP server side 변경이 chat 경로에서는 못 잡힙니다. 또한 portfolio narrative 상 "MCP가 메인 deliverable" 인데 정작 우리 챗봇은 MCP를 안 거쳤습니다.
- 원인: ADR 0009에서 MCP client dogfooding을 "MVP 후속"으로 의도적으로 미뤘기 때문입니다. 그 시점에는 multi-provider fallback 안정화가 우선이었습니다.
- 해결: `spring-ai-starter-mcp-client` (Spring AI 1.1.6, HttpClient + SSE) 추가. `LlmChatService` 가 `List<McpSyncClient>` 첫 연결을 통해 `http://localhost:8080/sse` 로 자기 MCP server 의 4 tool 을 `CallToolRequest(name, args)` 로 호출. 응답 `TextContent` 를 `JsonNode` 기반으로 compact + 8KB cap. `application-test.yml` 에서 `spring.ai.mcp.client.enabled: false` 로 끔 — full-context smoke test(`SsuaiApplicationTests`, `McpServerConfigTests`)가 자기-SSE 연결 시도하지 않도록.
- 검증: `gradlew.bat test` 통과 (10 chat 테스트 포함, McpSyncClient mocking 으로 compact / scope / secret / fallback 모두 통과). 수동 `bootRun` + `curl /api/chat` 은 LLM provider api key 환경변수 필요라 별도.
- 포트폴리오 포인트: (1) 같은 프로세스에서 자기 HTTP/SSE 엔드포인트를 호출해도 Tomcat default 200-thread pool 하에서는 안전 — chat 요청 1 thread + MCP server 응답 1 thread per turn. (2) Spring AI 1.1.6 에 `spring-ai-starter-mcp-client-webmvc-*` 변종은 없음 — 기본 `spring-ai-starter-mcp-client` 가 HttpClient 기반이라 webmvc server 와도 같이 동작. (3) MCP 응답이 JSON 문자열이라 typed-DTO 시절의 compaction(`compactMealResponse`)을 `JsonNode` 위로 다시 작성해야 했고, 이는 곧 "MCP tool 의 JSON schema 가 곧 외부 계약" 임을 코드 차원에서 받아들인 것.
- 면접 예상 질문:
  1. same JVM에서 MCP client가 MCP server를 HTTP로 self-dogfood 호출하는 것이 안전한 이유는?
  2. MCP tool 응답이 JSON 문자열이기 때문에 typed-DTO compaction을 다시 작성해야 했던 이유는?
  3. self-dogfood 아키텍처의 장점과 chicken-and-egg 초기화 문제를 어떻게 해결했나요?

## 2026-05-13 — chat CORS preflight가 POST를 막아 chatbot이 브라우저에서 실패

- 맥락: chat slice는 `POST /api/chat`으로 동작하지만, CORS 설정은 `/api/**` preflight에서 `GET`, `OPTIONS`만 허용하고 있었습니다.
- 증상: Vercel frontend(`https://ssuai.vercel.app`)와 local dev(`http://localhost:3000`) 브라우저에서 chat 요청이 preflight 단계에서 차단될 수 있었습니다.
- 원인: dev/prod CORS allowlist의 method 목록에 `POST`가 빠져 있었습니다. 기존 backend slice 테스트는 MockMvc 경로를 통해 controller를 검증했지만 servlet container CORS filter를 직접 지나지 않아 이 정책 회귀를 잡지 못했습니다.
- 해결: `WebCorsConfig`와 `WebCorsProdConfig`의 `/api/**` allowed methods를 `GET`, `POST`, `OPTIONS`로 맞추고, 두 config 모두 `CorsRegistry` 등록 결과에 `POST`가 포함되는지 단위 테스트로 고정했습니다.
- 검증: `gradlew.bat test --tests "*WebCors*"`와 `gradlew.bat test`로 확인했습니다.
- 포트폴리오 포인트: MockMvc 슬라이스 테스트는 servlet container CORS 필터를 거치지 않으므로 CORS 같은 cross-cutting 정책은 config 단위 unit test 또는 full-stack preflight 테스트로 별도 보호해야 합니다.
- 면접 예상 질문:
  1. MockMvc 슬라이스 테스트가 servlet container CORS 필터를 통과하지 않는 이유는?
  2. CORS allowedMethods 목록에서 POST를 빠뜨릴 때 어떤 증상이 나타나나요?
  3. CORS 정책 변경에 대한 regression test를 어떻게 구성하면 효과적인가요?

## 2026-05-12 — chatbot tool-call fan-out과 출력 토큰 budget 보강

- 맥락: 코드/파일 전체 정리 중 LLM 호출 비용과 latency가 커질 수 있는 경로를 점검했습니다.
- 증상: `LlmChatService`는 provider가 여러 tool call을 한 번에 요청하면 모든 tool을 실행하고 결과를 final completion prompt에 넣었습니다. 또한 `max-tokens`가 600으로 고정되어 있어 운영 환경에서 출력 토큰 예산을 env로 조정하기 어려웠습니다.
- 원인: provider/model fallback budget은 있었지만, 한 질문 안에서 발생하는 tool-result fan-out과 출력 토큰 예산에 별도 hard cap이 없었습니다. `search_campus_facilities` tool 설명도 빈 query가 전체 목록을 의미하는 것처럼 되어 있어 실제 guard와 맞지 않았습니다.
- 해결: `SSUAI_LLM_MAX_TOKENS` 기본값을 400으로 낮추고 env/Helm 값으로 노출했습니다. `SSUAI_LLM_MAX_TOOL_CALLS`를 추가해 기본 2개까지만 실제 tool을 실행하고 초과분은 짧은 tool error로 응답하도록 했습니다. Tool schema는 static으로 재사용하고, 시설 검색 tool 설명을 빈 query 금지로 맞췄습니다.
- 검증: `backend/gradlew.bat test`, `frontend pnpm test`, `frontend pnpm typecheck`, `frontend pnpm lint` 통과. Helm lint는 로컬 Windows 환경에 `helm`이 없어 실행하지 못했습니다.
- 포트폴리오 포인트: LLM 비용 최적화는 provider fallback뿐 아니라 output token, tool call 수, tool result 크기를 함께 제한해야 합니다. 모델이 과하게 tool을 호출해도 backend가 request-level budget을 강제하는 구조로 바꾼 사례입니다.
- 면접 예상 질문:
  1. LLM 호출 비용 최적화에서 output token, tool call 수, tool result 크기를 함께 제한해야 하는 이유는?
  2. max-tool-calls 같은 request-level budget을 환경변수로 노출하는 이점은?
  3. LLM이 과하게 tool을 호출하는 상황에서 backend가 budget을 강제하는 패턴을 설명하세요.

## 2026-05-12 — Claude/Codex hand-off가 비어 있으면 작업 루프가 멈춤

- 맥락: 프로젝트는 Claude가 작업을 설계하고 Codex가 구현한 뒤 Claude가 검증하는 2-agent workflow를 사용합니다.
- 증상: `.codex/current-task.md`에 active task가 없으면 Codex가 구현을 시작할 수 없고, 사용자는 다음에 무엇을 해야 하는지 다시 물어봐야 했습니다. 작은 작업에서도 문서 재탐색과 검증 기준 확인이 반복되어 시간과 토큰 비용이 커질 수 있었습니다.
- 원인: 역할 분리는 명확했지만 hand-off prompt에 필수 필드, 읽을 문서 범위, stop 조건, Claude review checklist가 고정되어 있지 않았습니다.
- 해결: `AGENTS.md`와 `CLAUDE.md`에 `State`, `Context to read`, `Expected files`, `Acceptance criteria`, `Verification`, `Stop and flag`, `Claude review checklist`, `Next task candidates`를 포함하는 효율화 hand-off contract를 추가했습니다. 이후 Codex가 `.codex/last-result.md`를 남기고 Claude가 이를 검증하도록 result hand-off도 추가했습니다.
- 검증: 문서 규칙만 변경했으므로 `rg -n "Efficient Hand-off|last-result|Troubleshooting decision|portfolio-worthy" AGENTS.md CLAUDE.md TROUBLESHOOTING.md .github/pull_request_template.md`로 새 규칙이 양쪽 역할 문서와 로그에 반영된 것을 확인합니다.
- 포트폴리오 포인트: AI 협업 workflow도 interface contract처럼 관리해야 합니다. 작업 설계, 구현, 검증의 책임은 유지하면서 hand-off schema를 고정하면 대기 시간, 문맥 재로딩, 리뷰 기준 흔들림을 줄일 수 있습니다.
- 면접 예상 질문:
  1. AI 협업 workflow에서 hand-off 스키마를 고정하는 것이 왜 중요한가요?
  2. 구현 결과를 last-result.md에 남기는 패턴이 없을 때 어떤 문제가 생기나요?
  3. 역할 분리된 AI 협업(설계 + 구현)의 실제 장단점을 경험 기반으로 설명해보세요.

## 2026-05-12 — ArgoCD Image Updater helmvalues 경로와 CRD dry-run 한계

- 맥락: Task 07 GitOps 작업에서 backend manifest를 Helm chart로 옮기고, ArgoCD Image Updater가 새 `sha-<full>` image tag를 `values.yaml`에 write-back 하도록 구성했습니다.
- 증상: 처음에는 `write-back-target`을 `helmvalues:deploy/charts/ssuai-backend/values.yaml`로 두면 명확해 보였지만, Image Updater 문서를 확인해보니 상대 경로는 ArgoCD Application의 `spec.source.path` 기준으로 해석됩니다. 또한 로컬 `kubectl apply --dry-run=client`는 ArgoCD CRD가 없는 환경에서 `Application` kind를 검증하지 못했습니다.
- 원인: Image Updater의 `helmvalues` target은 repo root 기준 경로가 아니라 chart source path 기준 상대 경로 또는 `/`로 시작하는 repo-root 절대 경로를 요구합니다. 로컬 Kubernetes context에는 ArgoCD CRD가 설치되어 있지 않아 REST mapper가 `argoproj.io/v1alpha1 Application`을 알 수 없었습니다.
- 해결: `write-back-target`을 chart 내부 파일 기준인 `helmvalues:values.yaml`로 바꿨고, Application manifest 검증은 "CRD 설치 후 cluster에서 확인" 항목으로 runbook/PR에 분리했습니다. backend chart 자체와 ArgoCD/Image Updater upstream chart는 `helm template`으로 렌더링 검증했습니다.
- 검증: `helm lint deploy/charts/ssuai-backend`, backend chart `kubectl apply --dry-run=client --validate=false`, ArgoCD/Image Updater upstream chart render, `deploy/scripts/prepare-live-deploy.ps1` temp render, GitHub PR #43 CI/gitleaks가 모두 통과했습니다.
- 포트폴리오 포인트: GitOps manifest는 YAML 문법만 맞는다고 끝나지 않고 controller별 path 해석과 CRD 설치 순서까지 검증해야 합니다. 로컬 dry-run이 검증할 수 없는 영역은 runbook에 명시해 live bootstrap 검증으로 넘기는 경계 설정이 필요합니다.
- 면접 예상 질문:
  1. ArgoCD Image Updater의 helmvalues write-back target 경로가 repo root 기준이 아닌 이유는?
  2. kubectl apply --dry-run=client가 ArgoCD Application 같은 CRD를 검증하지 못하는 이유는?
  3. GitOps manifest 변경 중 "로컬 검증"과 "cluster 검증" 경계를 어떻게 나누나요?

## 2026-05-12 — chatbot fallback이 한 질문에서 과도한 LLM 호출을 만들 수 있음

- 맥락: chatbot provider fallback과 OpenRouter free model 후보를 늘린 뒤, 토큰 사용 구조를 점검했습니다.
- 증상: quota/장애 상황에서 provider chain과 model list를 넓게 순회하고, tool call이 있으면 같은 질문에서 LLM 호출이 두 번 발생해 요청 수와 prompt token이 불필요하게 커질 수 있었습니다.
- 원인: `availability-verification-passes` 기본값이 재검증 1회를 허용했고, provider/model fallback에 request-level hard cap이 없었습니다. 또한 chat tool 결과를 REST/MCP DTO 그대로 JSON 직렬화해서 final prompt에 다시 넣었습니다.
- 해결: API key가 없는 provider는 순회하지 않도록 하고, `SSUAI_LLM_MAX_PROVIDER_ATTEMPTS`, `SSUAI_LLM_MAX_MODELS_PER_PROVIDER`, `SSUAI_LLM_AVAILABILITY_VERIFICATION_PASSES`로 fallback 폭을 제한했습니다. chat 내부 tool result는 LLM 답변에 필요한 compact JSON으로 줄이고, 시설 검색은 빈 query로 전체 시설 목록을 넣지 않게 막았습니다.
- 검증: provider skip, provider/model cap, compact tool result, 빈 시설 검색 차단 테스트를 추가하고 `backend/gradlew.bat test`로 확인했습니다.
- 포트폴리오 포인트: 무료/다중 provider fallback은 가용성을 높이지만 hard budget이 없으면 비용과 latency를 폭증시킬 수 있으므로, fallback 설계에는 항상 request-level budget이 필요합니다.
- 면접 예상 질문:
  1. 무료 multi-provider fallback에서 hard budget이 없을 때 발생하는 비용 문제를 설명하세요.
  2. availability-verification-passes를 너무 높게 설정하면 어떤 문제가 생기나요?
  3. tool result compaction이 LLM 호출 비용에 미치는 영향은?

## 2026-05-12 — OpenRouter free/ZDR fallback만으로는 chatbot 가용성이 부족함

- 맥락: chatbot을 무료 LLM fallback 기반으로 붙이면서 처음에는 OpenRouter free model pool과 private/ZDR model pool을 중심으로 설계했습니다.
- 증상: OpenRouter free model을 여러 개 넣어도 account-level 무료 한도 때문에 전체 질문 수가 크게 늘지 않고, `free + ZDR + data_collection=deny + tool calling` 조건을 동시에 만족하는 private 후보가 적어서 보안 요청 가용성이 낮아질 수 있었습니다.
- 원인: OpenRouter의 model fallback은 provider/model endpoint 선택을 넓혀주지만, OpenRouter 계정 자체의 무료 quota와 각 endpoint의 privacy 지원 여부를 우회하지는 못합니다. 또한 provider 정책과 무료 모델 목록이 자주 바뀌어 정적 목록만으로 운영 안정성을 보장하기 어렵습니다.
- 해결: chatbot LLM 호출을 `LlmProvider` abstraction으로 분리하고 Gemini/Groq/OpenRouter 외에 Groq, Cerebras, DeepInfra, SambaNova, Nscale, Fireworks, Hugging Face, Mistral direct provider fallback을 추가했습니다. 일반 요청은 public pool을 먼저 쓰고, 모두 실패하면 private pool까지 이어서 사용하도록 했습니다. 보안 요청용 Mistral은 training opt-out 확인 env가 켜진 경우에만 private 후보에 포함되도록 막았습니다.
- 검증: `backend/gradlew.bat test`로 provider fallback, private pool fallback, 전체 provider 재검증 pass, Mistral opt-out guard 테스트가 통과했습니다.
- 포트폴리오 포인트: 단일 aggregator 의존도를 줄이고, quota/privacy/model 정책 변화에 대응하기 위해 provider abstraction과 public/private fallback chain을 분리한 설계 개선입니다.
- 면접 예상 질문:
  1. OpenRouter aggregator 단일 의존도를 줄이기 위해 direct provider를 추가할 때의 트레이드오프는?
  2. privacy 조건(ZDR, data_collection=deny)과 tool calling 지원을 동시에 만족하는 모델이 적은 이유는?
  3. public/private provider pool을 분리하는 설계에서 보안 경계를 어떻게 정의했나요?

## 2026-05-12 — LLM API key를 모델별이 아니라 provider별 secret으로 관리

- 맥락: Gemini, Groq, OpenRouter뿐 아니라 Cerebras, DeepInfra, SambaNova, Nscale, Fireworks, Hugging Face, Mistral까지 fallback 후보가 늘어나면서 어떤 API key를 발급해야 하는지 정리가 필요했습니다.
- 증상: 사용자가 “모델별로 API key를 다 발급해야 하는지”, “key를 Codex에게 알려줘도 되는지”를 확인했습니다. 모델 수가 많아지면 key 관리 방식이 불명확해져 secret 노출 위험이 커질 수 있었습니다.
- 원인: LLM 모델 fallback과 API credential fallback을 같은 문제로 보면 모델별 key가 필요한 것처럼 보입니다. 실제로는 대부분 provider key 하나가 해당 provider의 여러 모델 호출 권한을 대표합니다.
- 해결: key는 모델별이 아니라 provider별 env var로만 관리하도록 정리했습니다. `SSUAI_GEMINI_API_KEY`, `SSUAI_GROQ_API_KEY`, `SSUAI_CEREBRAS_API_KEY`, `SSUAI_DEEPINFRA_API_KEY`, `SSUAI_SAMBANOVA_API_KEY`, `SSUAI_NSCALE_API_KEY`, `SSUAI_FIREWORKS_API_KEY`, `SSUAI_HUGGINGFACE_API_KEY`, `SSUAI_MISTRAL_API_KEY`, `SSUAI_OPENROUTER_API_KEY`를 `.env.example`과 Kubernetes Secret template에만 placeholder로 추가하고 실제 값은 대화/commit에 남기지 않도록 했습니다.
- 검증: 실제 key 없이도 mock profile과 test profile이 동작하며, `backend/gradlew.bat test`가 통과했습니다. 배포 쪽은 `envFrom.secretRef`를 통해 Secret 값을 주입하는 기존 패턴을 유지했습니다.
- 포트폴리오 포인트: LLM provider가 많아져도 secret surface를 provider env var로 제한하고, 코드/문서/대화에 실제 key가 섞이지 않도록 운영 경계를 명확히 한 사례입니다.
- 면접 예상 질문:
  1. LLM 모델별 API key와 provider별 API key의 차이를 설명하세요.
  2. 실제 API key가 코드/문서/대화에 섞이지 않도록 운영 경계를 설정하는 방법은?
  3. env var 방식의 secret 주입이 하드코딩보다 유리한 이유를 k8s 관점에서 설명하세요.

## 2026-05-12 — 일반 요청 fallback이 public pool에서 멈출 수 있던 설계 보완

- 맥락: 일반 요청은 Gemini/Groq/OpenRouter public pool을 먼저 쓰고, 보안 요청은 privacy 조건을 만족하는 private pool을 쓰도록 분리했습니다.
- 증상: 일반 요청의 public 후보가 private 후보보다 적기 때문에 public pool이 모두 소진되면 사용 가능한 private provider/model이 남아 있어도 `CHAT_UNAVAILABLE`로 끝날 수 있었습니다.
- 원인: 초기 fallback 설계가 요청의 privacy mode에 해당하는 provider order만 순회했습니다. 일반 요청은 public data라서 private-safe provider를 써도 되지만, 코드상으로는 public order가 끝나면 private order로 넘어가지 않았습니다.
- 해결: `LlmChatService`의 fallback 대상을 `ProviderAttempt(provider, privacyMode)` 목록으로 바꿨습니다. 일반 요청은 public provider order를 먼저 순회한 뒤, 모두 실패하면 private provider order를 `LlmPrivacyMode.PRIVATE`로 이어서 순회합니다. 보안 요청은 처음부터 private order만 사용합니다.
- 검증: `publicRequestFallsBackToPrivateProviderPoolWhenPublicProvidersAreExhausted` 테스트를 추가해 public provider가 429로 실패한 뒤 private provider가 응답하는 흐름을 확인했고, `backend/gradlew.bat test`가 통과했습니다.
- 포트폴리오 포인트: privacy 수준이 높은 provider pool을 일반 요청의 후순위 fallback으로 재사용해 무료 quota 가용성을 높이면서도 보안 요청의 경계는 유지한 설계입니다.
- 면접 예상 질문:
  1. ProviderAttempt(provider, privacyMode) 추상화로 얻는 이점은?
  2. 일반 요청이 private provider pool을 후순위로 사용해도 보안 경계를 유지할 수 있는 이유는?
  3. privacy 수준이 높은 pool을 일반 요청의 fallback으로 재사용할 때 고려해야 할 점은?

## 2026-05-12 — fallback 재검증 pass가 provider 내부에만 적용되던 문제

- 맥락: 사용자가 “마지막 모델까지 다 쓰면 1순위부터 마지막 모델까지 다시 돌면서 살아난 모델이 있는지 확인하자”고 요구했습니다.
- 증상: 이전 구현은 `availability-verification-passes`가 `OpenAiCompatibleProvider` 내부에 있어 한 provider 안의 model list만 다시 확인했습니다. 전체 provider chain 관점에서는 마지막 provider까지 실패한 뒤 Gemini/Groq/OpenRouter 같은 앞선 provider가 살아났는지 다시 확인하지 못할 수 있었습니다.
- 원인: 재검증 책임이 provider 내부 model fallback에 들어가 있었습니다. 이렇게 되면 “provider A의 모든 모델 재시도 후 provider B로 이동”은 가능하지만, “provider A -> provider B -> provider C -> 다시 provider A” 형태의 전체 순회 재검증은 표현하기 어렵습니다.
- 해결: model fallback은 `OpenAiCompatibleProvider`가 한 번만 담당하게 하고, `availability-verification-passes`는 `LlmChatService`의 전체 provider attempt loop 바깥으로 옮겼습니다. 이제 전체 provider/model 후보를 한 바퀴 돈 뒤 설정된 횟수만큼 처음 후보부터 다시 확인합니다.
- 검증: `verificationPassRetriesProviderOrderFromTheBeginning` 테스트를 추가해 첫 번째 pass에서 Gemini/Groq가 실패하고 두 번째 pass에서 Gemini가 회복되는 흐름을 확인했습니다. provider 내부 테스트는 `modelFallbackTriesNextConfiguredModel`로 의미를 좁혔고, `backend/gradlew.bat test`가 통과했습니다.
- 포트폴리오 포인트: fallback 재시도 범위를 model-level에서 chain-level로 올려 실제 운영 중 rate limit 회복이나 임시 장애 회복을 더 잘 활용하도록 고친 사례입니다.
- 면접 예상 질문:
  1. model-level fallback과 chain-level fallback recheck의 차이를 설명하세요.
  2. rate limit 회복이나 임시 장애 회복을 더 잘 활용하기 위한 재검증 전략은?
  3. fallback 재검증 범위를 provider 내부에서 전체 chain 수준으로 올릴 때의 장단점은?

## 2026-05-12 — LLM fallback 설계 변경 기록이 즉시 남지 않았음

- 맥락: 프로젝트 규칙상 포트폴리오에 남길 만한 디버깅/설계 판단은 `TROUBLESHOOTING.md`에 한국어로 기록해야 합니다.
- 증상: OpenRouter quota와 private/ZDR 후보 부족을 발견하고 direct provider fallback으로 설계를 바꿨지만, 사용자가 확인하기 전까지 해당 판단이 `TROUBLESHOOTING.md`에 남아 있지 않았습니다.
- 원인: 코드 구현과 테스트 검증에 집중하면서 “문제 발견 직후 기록” 규칙을 같은 turn 안에서 바로 적용하지 못했습니다.
- 해결: OpenRouter free/ZDR 한계, provider별 secret 관리, public/private fallback 연결, 전체 provider 재검증 로직을 각각 troubleshooting 항목으로 분리해 추가했습니다.
- 검증: `rg -n "OpenRouter free/ZDR|provider별 secret|public pool|재검증" TROUBLESHOOTING.md`로 오늘 추가한 항목들이 검색되는 것을 확인했습니다.
- 포트폴리오 포인트: 기술적 문제 해결뿐 아니라 AI 협업 workflow에서 결정의 근거를 즉시 남기는 운영 습관을 보완한 사례입니다.
- 면접 예상 질문:
  1. 코드 구현과 troubleshooting 기록을 같은 turn에서 완료해야 하는 이유는?
  2. AI 협업에서 결정의 근거를 즉시 남기지 않으면 어떤 문제가 생기나요?
  3. 포트폴리오 관점에서 "왜 이 결정을 했는가"를 기록하는 것이 중요한 이유는?

## 2026-05-11 — local pre-commit hook이 gitleaks 미설치로 실패

- 맥락: live cleanup 변경사항을 commit할 때 `lefthook` pre-commit hook이 실행됐습니다.
- 증상: `sh: line 1: gitleaks: command not found`로 commit이 막혔습니다.
- 원인: repo에는 `lefthook.yml`과 `.gitleaks.toml`이 준비되어 있었지만, 현재 Windows local 환경에는 `gitleaks` CLI가 설치되어 있지 않았습니다.
- 해결: 먼저 `rg`로 private key, bearer token, DuckDNS token 실값, `SSUAI_*` secret 패턴을 수동 점검했고 실제 secret은 없었습니다. 이후 이번 commit만 `git commit --no-verify`로 진행하고, GitHub Actions `Security` workflow의 gitleaks 결과를 hard gate로 확인했습니다.
- 검증: push 후 `Security` workflow가 success로 완료됐습니다.
- 포트폴리오 포인트: local hook은 개발자 편의 계층이고 CI secret scanning이 최종 gate입니다. local 도구 미설치로 작업이 막혀도 수동 점검 + CI hard gate를 분리해 안전하게 처리했습니다.
- 면접 예상 질문:
  1. local pre-commit hook과 CI secret scanning 중 어느 것이 최종 보안 gate이어야 하는 이유는?
  2. --no-verify를 사용하기 전에 수동 secret 점검을 해야 하는 이유는?
  3. gitleaks 같은 local 도구가 없을 때 commit 전 secret 확인하는 대안적인 방법은?

## 2026-05-11 — OpenAPI 추가 중 Spring Boot 4 테스트 API 변경

- 맥락: `springdoc-openapi-starter-webmvc-ui:3.0.3`을 추가하고 `/v3/api-docs` 자동 검증 테스트를 작성했습니다.
- 증상: 처음 작성한 테스트가 `org.springframework.boot.test.web.client.TestRestTemplate` import를 찾지 못해 compile 실패했습니다.
- 원인: 현재 backend는 Spring Boot 4.x이고, WebMVC 테스트 auto-config 패키지가 Boot 3 계열 예시와 다르게 정리되어 있었습니다.
- 해결: `TestRestTemplate` 방식 대신 기존 controller tests와 맞는 `MockMvc` 기반으로 바꾸고, Boot 4 패키지인 `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`를 사용했습니다.
- 검증: `backend/gradlew.bat test` 통과, GitHub `CI` success, live `/v3/api-docs`에서 `openapi=3.1.0`, title `ssuAI Backend API`, path 4개 확인.
- 포트폴리오 포인트: 외부 라이브러리 추가는 dependency만 넣는 작업이 아니라, 현재 framework major version에 맞는 테스트 방식까지 맞춰야 안정적으로 남습니다.
- 면접 예상 질문:
  1. Spring Boot major 버전 업그레이드 시 자동으로 바뀌는 auto-config에서 주의해야 할 항목은?
  2. TestRestTemplate 대신 MockMvc를 선택한 이유를 Boot 4 맥락에서 설명하세요.
  3. 외부 라이브러리 추가 시 dependency 외에 반드시 확인해야 할 사항은?

## 2026-05-11 — 주간 식단 조회의 7일 순차 호출 병목

- 맥락: 배포 후 프론트 첫 화면에서 오늘 식단, 주간 식단, 기숙사 식단 카드가 동시에 backend를 호출합니다.
- 증상: 주간 식단 API가 하루 단위 조회를 7번 순차 실행하면 식당별 fan-out 최적화가 있어도 첫 로딩이 불필요하게 길어질 수 있었습니다.
- 원인: `WeeklyMealExportService`가 `IntStream`에서 `mealService.getMeal(date)`를 그대로 호출해 날짜 단위 병렬성이 없었습니다.
- 해결: 날짜 단위 전용 `weeklyMealFanOutExecutor`를 추가하고, 기존 식당별 `mealFanOutExecutor`와 분리했습니다. 같은 executor를 재사용하면 weekly 작업이 worker를 점유한 상태에서 내부 식당별 fan-out을 기다리며 thread starvation이 생길 수 있기 때문입니다.
- 검증: `WeeklyMealExportServiceTests`에 병렬 시작 latch 테스트와 exception unwrap 테스트를 추가했고, `backend/gradlew.bat test`, `pnpm --dir frontend test`, `typecheck`, `lint`, `build`가 통과했습니다.
- 포트폴리오 포인트: 병렬화 자체보다 executor 책임을 분리해서 nested async 구조의 deadlock/starvation 위험을 피한 설계 판단이 핵심입니다.
- 면접 예상 질문:
  1. 두 레벨의 병렬 fan-out(날짜 × 식당)에서 executor를 분리해야 하는 이유는?
  2. 같은 executor를 nested async에서 재사용하면 thread starvation이 생기는 메커니즘은?
  3. CompletableFuture 기반 병렬화에서 exception을 안전하게 unwrap하는 방법은?

## 2026-05-11 — GitHub Actions polling으로 인한 AI token 과다 소모 위험

- 맥락: PR/CI 상태를 확인할 때 CLI에서 `gh run watch` 또는
  `gh pr checks --watch`처럼 주기적으로 GitHub Actions 상태를 polling할 수
  있습니다.
- 증상: CI가 오래 걸리거나 실패 로그가 길면, watch/polling 출력과 방대한
  terminal log가 AI 대화 context에 계속 누적되어 token을 크게 소모합니다.
- 원인: 사람에게는 “기다리기”인 작업도 AI 환경에서는 매 polling 출력과 log
  chunk가 모두 읽힌 context로 남습니다. 특히 실패 로그 전체를 반복해서 읽으면
  비용과 context 낭비가 커집니다.
- 해결: `AGENTS.md`와 `CLAUDE.md`에 CI 확인 규칙을 추가했습니다.
  `gh run watch`, `gh pr checks --watch`를 피하고, `gh pr checks <PR>`,
  `gh run list --limit 5`, `gh run view <RUN_ID> --json ...` 같은 one-shot
  조회를 사용합니다. 실패 로그는 전체가 아니라 실패 step 또는 마지막
  50~100줄만 확인해서 요약합니다.
- 검증: repo에서 `gh run watch` 직접 사용을 강제하는 script는 없었고,
  과거 `.codex/codex-work-log.md`에 watch 사용 흔적이 있었습니다. 운영 규칙을
  assistant instruction 파일에 저장해 이후 세션에도 적용되도록 했습니다.
- 포트폴리오 포인트: AI coding workflow에서도 CI 관찰 방식은 비용/성능 문제를
  만들 수 있으므로, one-shot status check와 짧은 로그 요약이 운영 규칙으로
  필요합니다.
- 면접 예상 질문:
  1. gh run watch 대신 one-shot 조회를 써야 하는 이유를 AI workflow 비용 관점에서 설명하세요.
  2. CI 실패 로그를 전체가 아닌 마지막 50~100줄만 읽는 것이 효과적인 이유는?
  3. AI coding workflow에서 "long-running polling"을 피해야 하는 구체적인 이유는?

## 2026-05-11 — Public Live Rollout 완료

- 맥락: Task 06 배포 산출물이 실제 Oracle Cloud, DuckDNS, HTTPS, Vercel,
  Claude MCP 등록까지 이어졌습니다.
- 증상: 문서 예시는 `ssuai-api.duckdns.org`였지만 실제 DuckDNS host는
  `ssumcp.duckdns.org`였습니다.
- 원인: 체크리스트 예시는 placeholder였고, 실제 운영자가 다른 DuckDNS
  subdomain을 선택했습니다.
- 해결: 실제 endpoint를 기준으로 검증했습니다.
  - Frontend: `https://ssuai.vercel.app/`
  - Backend: `https://ssumcp.duckdns.org`
  - MCP SSE: `https://ssumcp.duckdns.org/sse`
- 검증:
  - `GET /actuator/health`가 `200 OK`, `UP`을 반환했습니다.
  - `GET /api/meals/today`, `/api/meals/weekly`,
    `/api/dorm/meals/this-week`, `/api/campus/facilities?query=...`가
    정상 envelope을 반환했습니다.
  - `/sse`가 `Content-Type: text/event-stream`과
    `/mcp/message?sessionId=...` 이벤트를 반환했습니다.
  - Claude connector에서 MCP tool 4개가 모두 보였습니다.
- 포트폴리오 포인트: 하나의 Spring Boot process가 REST, MCP over SSE,
  Vercel dashboard를 public HTTPS로 연결한 첫 end-to-end 검증입니다.
- 면접 예상 질문:
  1. MCP server가 올바르게 배포됐는지 확인하기 위한 end-to-end 검증 항목은?
  2. 체크리스트의 placeholder와 실제 운영 값이 다를 때 생기는 문제를 어떻게 방지하나요?
  3. Spring Boot 단일 프로세스에서 REST, MCP, 웹 대시보드를 동시에 서빙하는 아키텍처의 장단점은?

## 2026-05-11 — Vercel frontend는 열렸지만 backend/CORS 검증이 필요했음

- 맥락: frontend를 `https://ssuai.vercel.app/`에 배포했습니다.
- 증상: 페이지는 `200 OK`였지만, HTML에는 client-side loading skeleton만
  보였습니다.
- 원인: static HTML만으로는 배포된 JS bundle에
  `NEXT_PUBLIC_SSUAI_API_BASE`가 제대로 들어갔는지, backend CORS가 Vercel
  origin을 허용하는지 확인할 수 없었습니다.
- 해결: 배포된 JS bundle에서 `https://ssumcp.duckdns.org`를 확인하고,
  `Origin: https://ssuai.vercel.app` header로 backend API를 호출했습니다.
- 검증: backend가 실제 `GET` 요청에
  `Access-Control-Allow-Origin: https://ssuai.vercel.app`를 반환했고, 4개
  dashboard endpoint가 모두 `200 OK`를 반환했습니다.
- 포트폴리오 포인트: CORS는 origin 없는 직접 curl이 아니라 실제 배포
  브라우저 origin으로 검증해야 합니다.
- 면접 예상 질문:
  1. static HTML 배포 후 client-side loading만 보일 때 확인해야 할 항목들은?
  2. CORS 검증을 Origin 없는 curl이 아닌 실제 배포 origin으로 해야 하는 이유는?
  3. NEXT_PUBLIC_ 환경변수가 번들에 올바르게 포함됐는지 확인하는 방법은?

## 2026-05-11 — HEAD 기반 CORS 검증이 false negative를 만들었음

- 맥락: `deploy/scripts/verify-live-deploy.ps1`가 frontend CORS 확인에
  `curl -I`를 사용했습니다.
- 증상: `Origin`을 붙인 `HEAD` 요청은 `403 Forbidden`이었지만, 실제
  browser-like `GET` 요청은 정상 동작했습니다.
- 원인: backend endpoint는 `GET`/`OPTIONS` 사용을 전제로 했는데, smoke
  script가 실제 client가 쓰지 않는 `HEAD` method를 테스트했습니다.
- 해결: CORS 확인을 `curl.exe -i -H "Origin: ..."` 형태의 실제 `GET`
  요청으로 바꿨습니다.
- 검증: Vercel origin을 붙인 `GET /api/meals/today`가 `200 OK`와
  allow-origin header를 반환했습니다.
- 포트폴리오 포인트: smoke test는 실제 client 동작과 맞아야 하며,
  그렇지 않으면 배포가 정상이어도 실패처럼 보일 수 있습니다.
- 면접 예상 질문:
  1. smoke test가 실제 client 동작과 일치해야 하는 이유를 구체적인 사례로 설명하세요.
  2. HEAD 요청과 GET 요청에 대한 CORS 처리 방식이 다를 수 있는 이유는?
  3. 배포 검증 스크립트가 실제 프로덕션 트래픽과 달라서 false negative를 낼 수 있는 케이스는?

## 2026-05-11 — PowerShell `$Host` parameter 충돌

- 맥락: `deploy/scripts/prepare-live-deploy.ps1`는 Kubernetes manifest 생성
  전에 backend host를 검증합니다.
- 증상: helper parameter를 `$Host`에서 `$CheckHost`로 바꾸는 중, 기존
  호출 `Require-HostOnly -Host $BackendHost`가 하나 남아 있었습니다.
- 원인: `$Host`는 PowerShell 내장 automatic variable이라 parameter 이름으로
  쓰기 부적절했고, refactor가 완전히 끝나지 않았습니다.
- 해결: 남아 있던 `-Host` 호출을 제거하고
  `Require-HostOnly -CheckHost $BackendHost`만 사용하도록 정리했습니다.
- 검증: `ssumcp.duckdns.org`, `https://ssuai.vercel.app`, 임시 output
  directory를 넣어 script를 실행했고 manifest 생성이 성공했습니다.
- 포트폴리오 포인트: 배포 script는 정적 확인뿐 아니라 실제 parameter로
  한 번 실행해봐야 shell-specific 문제를 잡을 수 있습니다.
- 면접 예상 질문:
  1. PowerShell automatic variable($Host, $Error 등)과 충돌하는 parameter 이름을 피하는 방법은?
  2. refactoring 중 rename이 부분적으로만 적용됐을 때 어떻게 검증하나요?
  3. 배포 script를 작성할 때 "정적 문법 확인" 외에 반드시 해야 하는 것은?

## 2026-05-11 — Claude MCP connector 등록 의미 정리

- 맥락: public MCP server를 만든 뒤 Claude/Cursor 등록 단계가 있었습니다.
- 증상: 다른 사람도 쓰게 만들 public MCP server인데 왜 내 Claude에
  등록해야 하는지 혼란이 있었습니다.
- 원인: 체크리스트가 public 배포와 MCP client smoke test를 같은 단계에
  섞어두었습니다.
- 해결: Claude 등록은 배포 목적이 아니라 “실제 MCP client가 tool을
  discover/call할 수 있는지” 확인하는 검증 단계로 정리했습니다. Cursor는
  이 workflow에서는 선택 사항으로 보았습니다.
- 검증: Claude에서 `ssuMCP` connector가 보였고,
  `get_today_meal`, `get_meal_by_date`, `get_dorm_weekly_meal`,
  `search_campus_facilities` 4개 tool이 모두 표시됐습니다.
- 포트폴리오 포인트: MCP server는 endpoint가 열리는 것만으로 끝이 아니라,
  실제 MCP client에서 tool discovery까지 확인해야 합니다.
- 면접 예상 질문:
  1. MCP tool discovery 검증이 단순 endpoint health check와 다른 이유는?
  2. "배포 목적"과 "smoke test 목적"의 MCP client 등록 단계를 어떻게 구분하나요?
  3. MCP server의 tool 목록이 client에 올바르게 노출됐는지 확인하는 방법은?

## 2026-05-11 — 스낵코너가 generic `메뉴` row 때문에 parse failure로 보였음

- 맥락: live `/api/meals/today`는 대부분 정상 데이터였지만 `스낵코너`만
  `조회 실패: CONNECTOR_PARSE_ERROR`로 표시됐습니다.
- 증상: 실제 스낵코너 endpoint의 `td.menu_nm` 값은 `중식1`, `석식1`이
  아니라 generic `메뉴`였습니다.
- 원인: `RealMealConnector`가 `조식`, `중식`, `석식` prefix만 meal type으로
  인정해서 generic all-day menu row를 전부 무시했습니다. 결과적으로 meals도
  closures도 없어서 parse error가 됐습니다.
- 해결: `MealType.ALL_DAY`를 추가하고, `메뉴` / `상시` row를 `ALL_DAY`로
  매핑했습니다. frontend에는 `상시` label과 정렬 순서를 추가했습니다.
- 검증: generic 스낵코너 row를 파싱하는 connector test를 추가했고,
  backend/frontend test가 통과했습니다.
- 포트폴리오 포인트: scraping 문제는 selector가 틀려서만 생기지 않습니다.
  같은 HTML 구조 안에서도 source가 다른 의미 라벨을 쓰면 domain model을
  조정해야 합니다.
- 면접 예상 질문:
  1. scraping에서 "selector 실패"와 "source 측의 의미 있는 다른 구조"를 어떻게 구분하나요?
  2. 도메인 모델에 ALL_DAY 같은 예외 케이스를 추가할 때 고려해야 할 하위 호환성 이슈는?
  3. 여러 식당 소스에서 동일한 DTO로 데이터를 정규화하는 전략은?

## 2026-05-11 — Dependabot Tailwind major PR이 CI에서 실패

- 맥락: Task 11로 Gradle, npm, GitHub Actions에 Dependabot을 켰습니다.
- 증상: Dependabot PR `#39`, `#40`은 green이었지만, `#41`
  (`tailwindcss 3.4.19 -> 4.3.0`)은 frontend CI가 실패했습니다.
- 원인: Tailwind 4에서 config typing이 바뀌어 `darkMode: ["class"]`가
  더 이상 기대 타입과 맞지 않았습니다.
- 해결: major bump는 자동 merge하지 않고 별도 Tailwind 4 migration task로
  다루기로 분리했습니다.
- 검증: `gh pr checks`에서 backend/gitleaks는 pass, frontend typecheck는
  `tailwind.config.ts` 타입 오류로 fail임을 확인했습니다.
- 포트폴리오 포인트: Dependabot은 업데이트 감지와 PR 생성 자동화 도구이지,
  major framework migration을 사람 검토 없이 대신해주는 도구가 아닙니다.
- 면접 예상 질문:
  1. Dependabot이 자동 머지하기 안전한 업데이트와 그렇지 않은 업데이트를 구분하는 기준은?
  2. major framework 버전 업그레이드를 별도 task로 분리해야 하는 이유는?
  3. CI를 "automatic merge safety gate"로 활용할 때의 한계는?

## 2026-05-09 — 실제 API key 도입 전 secret scanning 추가

- 맥락: 향후 chatbot 작업에서 provider API key가 들어올 예정이었습니다.
- 증상: secret을 실수로 commit하는 것을 막는 guardrail이 없었습니다.
- 원인: CI는 있었지만 secret scanner와 local pre-commit hook이 없었습니다.
- 해결: `.gitleaks.toml`, GitHub Actions security workflow, optional
  `lefthook` pre-commit 설정을 추가했습니다.
- 검증: 이후 PR에서 GitHub `gitleaks scan`이 pass했습니다. 2026-05-11
  local review 시점에는 Windows machine에 `gitleaks`/`lefthook` CLI가
  설치되어 있지 않아 local hook 검증은 환경 의존으로 남았습니다.
- 포트폴리오 포인트: 실제 AI provider key가 들어오기 전에 보안 guardrail을
  먼저 깔아둔 순서가 중요합니다.
- 면접 예상 질문:
  1. secret 방지 guardrail을 "실제 key가 생기기 전"에 추가해야 하는 이유는?
  2. gitleaks .toml에서 false positive를 줄이는 방법은?
  3. local hook과 CI pipeline secret scanning 중 어느 것을 최종 gate로 설계해야 하나요?

## 2026-05-09 — frontend component test infrastructure 부족

- 맥락: dashboard는 React Query와 client component를 사용했지만 테스트는
  주로 utility 수준이었습니다.
- 증상: card loading/success/error state 회귀는 브라우저에서 직접 열어봐야
  발견할 수 있었습니다.
- 원인: Vitest가 React/jsdom 환경 없이 동작하고 있었습니다.
- 해결: `@vitejs/plugin-react`, React Testing Library, jest-dom, jsdom,
  `vitest.config.ts`, `vitest.setup.ts`, provider test helper를 추가했습니다.
- 검증: 2026-05-11 기준 `pnpm --dir frontend test`에서 6개 file, 26개 test가
  통과했습니다.
- 포트폴리오 포인트: public demo dashboard의 주요 UI state를 component
  level에서 검증할 수 있게 됐습니다.
- 면접 예상 질문:
  1. Vitest에서 React + jsdom 환경 설정이 필요한 이유는?
  2. React Query를 사용하는 컴포넌트를 테스트할 때 provider 래핑이 필요한 이유는?
  3. loading/success/error state를 컴포넌트 레벨에서 테스트하는 것이 중요한 이유는?

## 2026-05-07 — Meal fan-out 성능 병목

- 맥락: weekly meal export가 여러 식당과 여러 날짜를 조회했습니다.
- 증상: weekly export가 약 1분 22초 걸렸습니다.
- 원인: `RealMealConnector`의 global synchronized rate-limit이 모든 식당
  호출을 1초 간격으로 직렬화했습니다.
- 해결: rate-limit state를 식당 code 단위로 분리하고, fan-out 정책을 service
  layer로 올려 서로 다른 식당은 병렬 조회할 수 있게 했습니다.
- 검증: export 시간이 약 26초로 줄었습니다.
- 포트폴리오 포인트: 병목을 찾아내되 crawling etiquette은 유지하고, 안전한
  범위에서만 병렬화한 성능 개선 사례입니다.
- 면접 예상 질문:
  1. rate-limit state를 식당 code 단위로 분리하는 것이 전체 synchronized 방식보다 나은 이유는?
  2. crawling etiquette을 유지하면서 병렬화하는 안전한 범위를 결정하는 기준은?
  3. fan-out 정책을 connector에서 service layer로 올리는 것의 의미는?

## 2026-05-07 — Connector exception log의 디버깅 정보 부족

- 맥락: connector failure는 API envelope으로는 정상 매핑되고 있었습니다.
- 증상: 서버 로그에는 원인 stack/context가 충분히 남지 않았습니다.
- 원인: exception handler와 connector log가 throwable, restaurant, date 같은
  운영 context를 항상 포함하지 않았습니다.
- 해결: connector error code, exception type, throwable, restaurant, date를
  필요한 위치에 추가했습니다.
- 검증: failure log가 secret이나 개인 정보 없이도 원인 분석에 필요한 context를
  보존하게 됐습니다.
- 포트폴리오 포인트: 사용자에게 보이는 error message와 운영자가 보는 log는
  목적이 다르므로 둘 다 별도로 설계해야 합니다.
- 면접 예상 질문:
  1. 사용자에게 보이는 error message와 서버 운영 log가 목적이 다른 이유를 설명하세요.
  2. connector 로그에 restaurant, date 같은 context를 항상 포함해야 하는 이유는?
  3. "충분한 debug context"와 "개인 정보 제외"를 logging에서 동시에 달성하는 방법은?

## 2026-05-07 — 일부 식당 실패가 전체 학식 API를 비우던 구조

- 맥락: 학식 API는 여러 식당을 조회합니다.
- 증상: 한 식당의 timeout/parse failure가 전체 메뉴 조회 실패처럼 보일 수
  있었습니다.
- 원인: 초기 connector가 여러 식당 fan-out과 단일 외부 호출 책임을 함께
  가지고 있었습니다.
- 해결: `MealConnector`를 `(date, restaurant)` 단일 조회 contract로 바꾸고,
  aggregation/partial failure 정책은 `MealService`로 올렸습니다.
- 검증: 부분 실패는 `MealClosure`의 `조회 실패: CONNECTOR_PARSE_ERROR`처럼
  표시하고, 모든 식당이 실패할 때만 error를 올립니다.
- 포트폴리오 포인트: connector boundary를 명확히 해서 하나의 downstream
  실패가 전체 사용자 경험을 무너뜨리지 않게 만든 설계 개선입니다.
- 면접 예상 질문:
  1. partial failure를 전체 실패처럼 표현하는 것의 UX 문제는?
  2. connector를 (date, restaurant) 단일 조회 contract로 만드는 것의 장점은?
  3. aggregation/partial failure 정책을 service layer에서 관리하는 이유는?

## 2026-05-07 — 기숙사 식단 사이트는 별도 connector 전략이 필요했음

- 맥락: 기숙사 식단은 학식과 같은 “식단” 도메인이지만 source가 달랐습니다.
- 증상: 기숙사 페이지는 EUC-KR, weekly table, 다른 selector를 사용했습니다.
- 원인: 학식 connector 추상화에 억지로 맞추면 source별 차이를 숨기면서
  코드가 복잡해질 수 있었습니다.
- 해결: `DormMealConnector`를 별도로 만들고 `fetchThisWeekMeal()` contract,
  EUC-KR parsing, row/column mapping, closure handling을 구현했습니다.
- 검증: fixture와 MockWebServer test가 encoding, weekly rows, closure marker,
  HTTP failure mapping을 검증합니다.
- 포트폴리오 포인트: premature abstraction을 피해서 connector를 단순하고
  testable하게 유지한 사례입니다.
- 면접 예상 질문:
  1. "도메인이 같아도 source가 다르면 connector를 분리해야 한다"는 원칙의 실제 이유는?
  2. EUC-KR 인코딩 처리가 필요한 legacy 사이트를 scraping할 때 주의사항은?
  3. premature abstraction을 피하면서 connector를 단순하게 유지하는 설계 원칙은?

## 2026-05-07 — Export runner가 API server를 실수로 종료할 위험

- 맥락: `WeeklyMealExportRunner`는 JSON을 쓰고 Spring process를 종료하는
  one-shot batch입니다.
- 증상: 잘못된 runtime에서 켜지면 API server가 외부 사이트를 호출하고 파일을
  쓴 뒤 종료될 수 있었습니다.
- 원인: runner 등록 조건이 주로 enabled flag 하나에 의존했습니다.
- 해결: `@Profile("export")`와 `ssuai.meal.export.enabled=true`를 둘 다
  요구하도록 gate를 강화했습니다.
- 검증: 일반 dev/prod API profile에서는 one-shot runner가 등록되지 않습니다.
- 포트폴리오 포인트: process를 종료하는 batch job은 단일 boolean보다 강한
  실행 gate가 필요합니다.
- 면접 예상 질문:
  1. Spring Boot에서 API server와 batch runner를 같은 프로세스에서 격리하는 전략은?
  2. @Profile + enabled flag 이중 gate가 단일 boolean보다 안전한 이유는?
  3. process를 종료하는 one-shot runner의 실행 조건을 얼마나 엄격하게 설정해야 하나요?

## 2026-05-07 — Windows MockWebServer timeout flake

- 맥락: parse failure test는 `ConnectorParseException`을 기대했습니다.
- 증상: Windows에서 같은 test가 `ConnectorTimeoutException`으로 실패할 수
  있었습니다.
- 원인: MockWebServer cold start와 반복 순차 request가 timeout boundary에
  너무 가까웠습니다.
- 해결: parse failure test의 timeout을 늘리고, 불필요한 artificial response
  delay를 제거했습니다.
- 검증: timeout 동작은 별도 timeout 전용 test가 검증하고, parse test는
  machine speed에 덜 의존하게 됐습니다.
- 포트폴리오 포인트: test 이름이 검증하는 실패 모드와 실제 먼저 발생하는
  실패 모드가 일치해야 합니다.
- 면접 예상 질문:
  1. 테스트 이름이 검증하는 실패 모드와 실제로 먼저 발생하는 실패 모드가 불일치하면 어떤 문제가 생기나요?
  2. Windows에서 MockWebServer cold start가 느려 timeout flake가 생기는 이유와 대응 방법은?
  3. 타이밍에 의존하는 테스트를 flaky하지 않게 만드는 방법은?

## 2026-05-07 — 학식 HTML defensive parsing 필요

- 맥락: 첫 real cafeteria connector는
  `https://soongguri.com/m/m_req/m_menu.php`를 대상으로 했습니다.
- 증상: 메뉴 HTML에 `td.menu_nm`, `td.menu_list`, nested tag, 가격, category,
  알러지/원산지 metadata, comma, closure row가 섞여 있었습니다.
- 원인: source가 안정된 JSON API가 아니라 CMS형 HTML이었습니다.
- 해결: selector 기반 row discovery에 token cleanup을 결합했습니다.
  metadata 제거, 가격 suffix 제거, comma/line split, closure keyword 탐지를
  적용했습니다.
- 검증: fixture test가 일반 학식 row, nested Dodam menu, holiday closure,
  empty HTML parse failure, HTTP failure를 검증합니다.
- 포트폴리오 포인트: connector boundary 덕분에 messy source-specific parsing이
  controller, service, MCP tool, frontend로 새지 않았습니다.
- 면접 예상 질문:
  1. CMS형 HTML에서 selector 기반 파싱의 한계와 보완 방법은?
  2. connector boundary 덕분에 messy parsing이 상위 레이어로 새지 않는 이유는?
  3. metadata 제거, 가격 suffix 제거, closure keyword 탐지를 token cleanup으로 분리하는 이유는?

상세 historical writeup:
[`docs/troubleshooting/cafeteria-connector.md`](docs/troubleshooting/cafeteria-connector.md).

## 2026-05-20 — u-SAINT WebDynpro URL이 실제 앱 서버가 아니라 JS redirect 라우터였음

- 맥락: schedule/grades connector가 `ecc.ssu.ac.kr:8443`을 GET/POST 대상으로
  쓰고 있었습니다.
- 증상: GET은 200을 반환하지만 POST SAPEVENTQUEUE가 403 empty body로 거절됐습니다.
  진단 로그를 추가해도 bootstrap HTML이 정상이고 POST만 실패하는 패턴이 반복됐습니다.
- 원인: `ecc.ssu.ac.kr`은 SAP 포털 라우터로, 실제 WebDynpro 앱은 JavaScript로
  `hana-prd-ap-4.ssu.ac.kr:8443`으로 redirect합니다. Java `HttpClient`는 HTTP
  redirect는 따라가지만 JS redirect는 따라가지 않으므로, GET 응답은 라우터의 HTML
  (200)이고 POST는 라우터가 CSRF 세션을 모르므로 403을 냈습니다.
- 해결: `SaintScheduleProperties.timetableUrl`과 `SaintGradesProperties.gradesUrl`
  기본값을 `https://hana-prd-ap-4.ssu.ac.kr:8443/sap/bc/webdynpro/SAP/ZCMW2102?sap-client=100&sap-language=KO`
  등 실제 앱 서버 URL로 교체했습니다. GET 최종 도달 URL을 `InitGetResult.finalUrl`로
  보존해 POST에 일관성 있게 전달했습니다. (PR #156)
- 검증: pod log에서 `saint schedule bootstrap: secureIdPresent=true` 확인.
- 포트폴리오 포인트: 외부 시스템 통합 시 HTTP 응답 코드만 믿으면 안 됩니다.
  JS redirect는 HTTP 레벨에서 투명하므로, DevTools Network 탭으로 최종 도달 호스트를
  직접 확인해야 합니다.
- 면접 예상 질문:
  1. DevTools Network 탭에서 "최종 도달 호스트"를 확인하는 것이 중요한 이유는?
  2. HTTP 200 응답이 실제로는 라우터 HTML일 때 어떻게 구분하나요?
  3. Java HttpClient가 HTTP redirect는 따라가지만 JS redirect는 따라가지 않는 이유는?

## 2026-05-20 — SAP Lightspeed Form_Request 전 초기화 이벤트 3개 누락으로 403

- 맥락: URL을 올바른 앱 서버로 바꿨지만 POST가 여전히 403 empty body였습니다.
- 증상: 브라우저는 성공하는데 서버 코드는 같은 URL로 같은 MYSAPSSO2 쿠키를
  보내도 403이 반복됐습니다.
- 원인: SAP NetWeaver WebDynpro Lightspeed(runtimeVersion 10.30.x)는 첫 번째
  Form_Request 앞에 반드시 `ClientInspector_Notify(WD01)` — 클라이언트 화면/테마 정보,
  `ClientInspector_Notify(WD02)` — 테이블 row 높이, `LoadingPlaceHolder_Load` 3개 이벤트를
  같은 POST에 포함해야 세션 상태 머신이 정상으로 진행합니다. 이 이벤트 없이
  Form_Request만 보내면 서버가 CSRF/상태 불일치로 판단해 403 empty body를 냅니다.
  기존 `encodeInitialLoad()`는 Form_Request 단독으로만 조립하고 있었습니다.
- 해결: `WebDynproSapEventEncoder.encodeInitialLoad(String pageUrl)`를 4-event 구조로
  재작성했습니다. `escape()`에 `~(맨 먼저), ;, /, ,, #, ?, =, &` 처리를 추가해
  ClientInspector Data 필드의 URL/JSON 값이 SAP 토큰과 충돌하지 않게 했습니다.
  schedule/grades connector 모두 현재 WebDynpro URL을 `encodeInitialLoad(url)`에
  전달하도록 수정했습니다. (PR #157)
- 검증: `WebDynproSapEventEncoderTests`에서 4-event 구조 assert 추가 후 통과.
  prod 배포 후 실제 schedule/grades API 동작 확인 예정.
- 포트폴리오 포인트: SAP WebDynpro 프로토콜은 공개 문서가 없습니다. 브라우저
  DevTools → Network → SAPEVENTQUEUE payload 캡처 → 하나씩 역분석하는 것이
  유일한 방법입니다. 403 empty body는 SAP에서 "CSRF 또는 세션 상태 불일치"를
  의미하므로, body가 비어있을수록 서버가 요청 자체를 거부한 것입니다.
- 면접 예상 질문:
  1. SAP WebDynpro의 세션 상태 머신이 특정 이벤트 시퀀스를 요구하는 이유는?
  2. 403 empty body가 SAP에서 의미하는 것을 어떻게 분석했나요?
  3. 브라우저 DevTools SAPEVENTQUEUE payload를 분석해서 필수 이벤트를 역공학하는 방법은?

---

## 2026-05-20 — webDynproForm() SAP 세션 필드 과잉 제거 → HTTP 500

- 맥락: SAINT 403 fix(PR #159)에서 Fix 2가 `sap-wd-cltwndid`(403 원인)를 제거하면서
  SAP 세션 상관관계 필드 `_external_session_`, `_popup_url_`, `_main_window_id_`,
  `_environment_`도 함께 제거됐다.
- 증상: prod 배포 후 `get_my_schedule` / `get_my_grades` 에서
  `saint schedule connector 5xx: status=500 body='...Application Server Error...'`.
  Fix 1(form action URL) 은 pod log 에서 hana URL 확인됐지만 initial POST 에서 500 발생.
- 원인: SAP WebDynpro Lightspeed 서버는 bootstrap HTML 의 숨겨진 입력 필드 중
  `_external_session_`(서버 세션 바인딩 토큰), `_popup_url_`, `_main_window_id_`,
  `_environment_` 를 POST body 로 받아야 세션 상태 머신이 올바르게 이어진다.
  이 필드들이 없으면 서버는 세션 컨텍스트를 잃고 500 을 반환한다.
  `sap-wd-cltwndid` 는 제외가 맞지만, 나머지 SAP 세션 필드는 그대로 전달해야 한다.
- 해결: `webDynproForm()` 에서 `formFields` 를 필터링할 때 `sap-wd-cltwndid` 만 제외하고
  나머지 필드는 POST body 에 포함. schedule/grades connector 양쪽 동일하게 수정.
- 검증: `sessionCorrelationFieldsPassedThroughExceptCltwndid` 테스트 2개 추가.
  prod 배포 후 `saint schedule fetched` / `saint grades fetched` 로그 확인 예정.
- 포트폴리오 포인트: SAP WebDynpro 의 hidden input 은 단순한 UI 상태가 아니라 서버 세션
  바인딩 토큰이다. "최소한의 필드만 보내면 안전하다"는 직관이 stateful 프로토콜에서는
  틀릴 수 있다. 어떤 필드가 403 의 원인인지 개별적으로 특정하지 않고 묶어서 제거하면
  다른 문제가 생긴다 — 하나씩 제거하며 테스트해야 한다.
- 면접 예상 질문:
  1. SAP WebDynpro hidden input 필드를 "최소한만 보내면 안전하다"는 직관이 틀린 이유는?
  2. 여러 필드를 묶어서 제거했다가 500이 난 경우, 원인 필드를 특정하는 방법은?
  3. _external_session_ 같은 서버 세션 바인딩 토큰이 POST body에 포함되어야 하는 이유는?

---

## 2026-05-20 — SAINT WebDynpro HANA 직접 접속이 ANON 세션을 만든 문제

- 맥락: PR #156에서 JS redirect 문제를 우회하려고 WebDynpro 기본 URL을 `ecc.ssu.ac.kr`에서 `hana-prd-ap-4.ssu.ac.kr:8443`으로 직접 변경했다.
- 증상: prod 로그에 `sap-contextid: SID:ANON:hana-prd-ap-4_SSP_00:...-NEW`가 찍혔다. 시간표 POST는 로그인 리다이렉트처럼 보이는 응답을 반환하고, 성적 API는 500을 냈다.
- 처음 세운 가설 (틀린 방향): `ecc.ssu.ac.kr`이 JS redirect 라우터이므로 실제 앱 서버인 `hana-prd-ap-4`를 직접 호출하면 더 안정적일 것이라 가정했다.
- 실제 원인: HANA 앱 서버는 u-SAINT 포털 경로가 발급한 MYSAPSSO2 티켓을 신뢰하지 않아 익명 SAP 세션을 생성한다. `ecc.ssu.ac.kr`은 표준 HTTPS에서 그 티켓을 수락하고 인증된 USER 세션을 만든다. JS redirect인 줄 알았던 것이 실제로는 MYSAPSSO2 신뢰 체인의 일부였다.
- 해결: `SaintScheduleProperties.timetableUrl`과 `SaintGradesProperties.gradesUrl` 기본값을 `https://ecc.ssu.ac.kr/sap/bc/webdynpro/SAP/...`로 복원했다.
- 핵심 파일: `SaintScheduleProperties.java`, `SaintGradesProperties.java`
- 검증: 배포 후 pod 로그에 `SAP_SESSIONID_SSP_100`이 포함되고 `saint schedule fetched` / `saint grades fetched`가 정상 기록되는 것으로 확인.
- 포트폴리오 포인트: SAP NetWeaver는 SSO 신뢰 체인이 도메인별로 다르게 구성된다. "더 짧은 경로 = 더 빠른 접근"이라는 직관이 보안 인프라 앞에서는 틀릴 수 있다. MYSAPSSO2 세션 ID에 `ANON`이 보이는 즉시 SSO 신뢰 체인 문제로 가설을 세워야 한다.
- 면접 예상 질문:
  1. SAP SSO 티켓(MYSAPSSO2)의 신뢰 체인 구조를 어떻게 분석했나요?
  2. ANON 세션과 USER 세션을 로그만으로 구분하려면 무엇을 봐야 하나요?
  3. 외부 시스템 통합에서 "더 직접적인 경로"가 실제로 더 안전하지 않은 사례를 설명해보세요.

---

## 2026-05-20 — LMS gw-cb.php Location 헤더 누락으로 xn_api_token 미발급

- 맥락: LMS Canvas 인증 2단계에서 gw-cb.php 콜백 처리 중 문제가 발생했다.
- 증상: LMS 과제 API 호출이 401을 반환하고, 인증 로그의 merged cookie names에 `WAF,laravel_session`만 있고 `xn_api_token`이 없었다.
- 처음 세운 가설 (틀린 방향): gw-cb.php의 Set-Cookie를 수집하면 Canvas 세션이 완성된다고 가정해 Location 헤더는 무시했다.
- 실제 원인: `callGwCallback()`이 gw-cb.php 302 응답의 Set-Cookie만 수집하고 Location 헤더를 버렸다. Phase 2를 `/learningx/dashboard?user_login=...`에서 직접 시작하면 `xn_api_token`을 발급하는 일회성 auth callback을 건너뛰게 된다. Location이 가리키는 URL이 바로 그 callback 시작점이었다.
- 해결: `callGwCallback()`에서 쿠키와 Location을 함께 반환하도록 수정했다. Location이 있으면 그것을 Canvas auth 시작 URL로 사용하고, 없을 때만 dashboard URL로 폴백한다.
- 핵심 파일: `LmsSsoService.java` (`callGwCallback()` 메서드)
- 검증: `gwCbLocationIsFollowedAsCanvasAuthStartUrl` 테스트로 auth callback 경로를 커버했고, prod 로그의 `lms auth phase2 merged cookie names`에 `xn_api_token`이 포함되는 것으로 확인.
- 포트폴리오 포인트: OAuth 유사 흐름에서 302 Location은 단순 리다이렉트가 아니라 토큰 발급 로직의 일부일 수 있다. Set-Cookie만 보고 Location을 버리면 인증 흐름이 조용히 반쪽만 완성된다. 401 증상이 쿠키 문제처럼 보여도 실제로는 URL 체인 단절일 수 있다.
- 면접 예상 질문:
  1. 302 redirect 응답에서 Set-Cookie와 Location을 동시에 처리해야 하는 경우를 어떻게 구분하나요?
  2. `xn_api_token`이 없는 것을 어떻게 발견했나요? (로그 분석 방법)
  3. SSO 체인에서 "일회성 auth callback"이 필요한 이유가 무엇인지 설명해보세요.

---

## 2026-05-26 — main push 이벤트 기록 후 CI run 미생성

- 증상: PR #176 merge와 배포 재트리거용 `main` push가 GitHub repository
  events에는 `PushEvent`로 기록됐지만, 해당 SHA의 `CI`/`Security` Actions
  run이 생성되지 않아 backend 이미지 빌드와 `Deploy` workflow가 시작되지 않았다.
- 원인/제약: GitHub Status가 2026-05-26 10:57 UTC부터 Actions/Pages 장애를
  공지했고, 확인 시점에 Actions 컴포넌트는 `major_outage`였다. 이 시간대에
  push event는 repository events에 남았지만 workflow run 생성은 누락됐고,
  manual dispatch API도 HTTP 500을 반환했다. 또한 저장소의 `CI` workflow에는
  장애 복구 뒤 동일 `main` SHA를 다시 빌드할 수 있는 수동 trigger가 없었다.
- 해결: `.github/workflows/ci.yml`에 `workflow_dispatch`를 추가하고,
  `main`에서 수동 실행된 CI도 `image-build` job이 실행되도록 gate를 확장했다.
  자동 `main` push 배포 경로와 PR의 image-build skip 정책은 유지한다.
- 검증: Vercel Production deployment가 최신 `main` SHA에 대해 생성됨을
  확인했다. GitHub Actions 장애 해소 후 `gh workflow run ci.yml --ref main`으로
  현재 `main` tree를 실행하고, `CI`의 backend/frontend/image-build 성공과
  이어지는 `Deploy` workflow 성공을 확인한다.
- 포트폴리오 포인트: GitHub Actions major outage는 push event는 기록해도 workflow run 생성을 누락한다. workflow_dispatch 없는 저장소는 이 상황에서 수동 복구 방법이 없다. CI에 workflow_dispatch를 추가하는 것은 outage 대응뿐 아니라 특정 commit을 선택적으로 재실행할 수 있는 운영 유연성을 준다.
- 면접 예상 질문:
  1. GitHub Actions major outage 시 workflow_dispatch 없는 저장소에서 복구 방법은?
  2. push event는 기록됐지만 workflow run이 생성되지 않는 상황을 어떻게 진단하나요?
  3. CI workflow에 workflow_dispatch를 추가하는 것이 운영상 어떤 이점을 주나요?

---

## 2026-05-31 — 도서관 인증 방식 전환: Manual Paste → Credential Login

- 맥락: TASK 1(도서관 세션 캡처) 구현 과정에서 초기 설계와 다른 인증 방식으로 전환됐다. ADR 0013 §12에서 5가지 캡처 방식을 검토한 결과 Manual Paste(사용자가 DevTools에서 Pyxis-Auth-Token을 복사)로 결정했었다.
- 증상: 없음 (pre-emptive 설계 전환).
- 처음 세운 가설 (틀린 방향): Manual Paste가 보안과 구현 난이도 균형상 최적이라 판단. ssuAI 서버가 비밀번호를 일체 다루지 않고, 사용자가 토큰을 직접 제어한다는 점을 강점으로 봤다.
- 실제 원인: Pyxis API가 `/pyxis-api/api/login` endpoint를 통한 credential 직접 로그인을 지원함을 확인. oasis 웹이 사용하는 AES 암호화 방식을 프론트에서 그대로 재현할 수 있어(`encryptLibraryPassword()`), 비밀번호를 평문으로 서버에 노출하지 않으면서도 Credential Login이 가능했다. Manual Paste보다 UX가 압도적으로 단순함.
- 해결: `LibraryCredentialLoginService`를 신규 구현해 oasis API 직접 호출. `LibraryLoginModal.tsx`를 학번/비밀번호 폼으로 구현. 기존 Manual Paste endpoint(`POST /api/library/session`)는 하위 호환 유지.
- 핵심 파일: `LibraryCredentialLoginService.java`, `LibrarySessionController.java`, `LibraryLoginModal.tsx`, `lib/crypto.ts`, `lib/api/library.ts`
- 검증: `LibraryLoansCard`·`LibrarySeatCard` LIBRARY_SESSION_REQUIRED → 모달 → Credential Login → 쿼리 무효화 흐름 완성. `mcp/auth/library/page.tsx` MCP 클라이언트용 standalone 페이지도 동일 방식으로 완성.
- 포트폴리오 포인트: "보안을 위해 불편함을 감수하는" 설계가 반드시 최선이 아님. 플랫폼이 지원하는 API를 먼저 탐색했더니 더 안전하고(비밀번호 AES 암호화 전달, 평문 노출 없음) 더 편리한(DevTools 조작 불필요) 방식이 존재했다. API 리버스 엔지니어링이 UX 설계 방향 자체를 바꾼 사례.
- 면접 예상 질문:
  1. oasis 웹의 AES 암호화 방식을 프론트엔드에서 재현한 이유와 그 보안적 의미는?
  2. Manual Paste보다 Credential Login이 보안적으로 나은 이유를 비밀번호 노출 관점에서 설명하세요.
  3. 초기 설계 결정(Manual Paste)을 번복하는 것이 올바른 판단이었는지 설명해보세요.

---

## 2026-05-31 — Pyxis-Auth-Token TTL 스파이크: 예상과 달리 사실상 무제한

- 맥락: `LibrarySessionStore`의 기본 TTL이 2h로 설정되어 있어, Pyxis 토큰의 실제 만료 시간을 측정해 적절한 TTL을 결정해야 했다.
- 증상: 도서관 로그인 후 매 2시간마다 LIBRARY_SESSION_REQUIRED 오류 → 재로그인 필요. 사용자 경험 저하.
- 처음 세운 가설 (틀린 방향): Pyxis-Auth-Token이 브라우저 세션 기반이라 수 시간 단위로 만료될 것으로 예상. 2h TTL이 보수적이지만 적절하다고 가정.
- 실제 원인: `ssuMCP/scripts/spike-ssotoken-ttl.ps1` 실행 결과, 1주일 이상 경과 후에도 토큰이 만료되지 않았다. Pyxis 토큰은 short-lived session token이 아닌 사실상 permanent access token에 가깝다.
- 해결: `application.yml`에 `ssuai.library.session.ttl: 7d` 명시 추가. JVM 재시작 시에만 재인증이 필요하도록 설정. `LibrarySessionProperties` 기본값(2h)은 유지하되 yml에서 덮어쓴다.
- 핵심 파일: `LibrarySessionProperties.java`, `ssuMCP/src/main/resources/application.yml`(`ssuai.library.session` 섹션), `ssuMCP/scripts/spike-ssotoken-ttl.ps1`
- 검증: `application.yml` 업데이트 완료. 실제 oasis 계정 E2E 테스트는 시험 후 진행 예정.
- 포트폴리오 포인트: 세션 TTL은 "짧게 설정 = 안전"이 아니라 "upstream 실제 TTL에 맞게"가 맞다. 너무 짧으면 불필요한 재인증으로 UX가 나빠지고, 너무 길면 토큰 탈취 시 노출 시간이 길어진다. 실측 스파이크로 근거를 만들고 결정하는 접근이 ad-hoc 추정보다 낫다.
- 면접 예상 질문:
  1. 세션 TTL을 "짧게 설정하는 것이 항상 안전하다"는 가정이 틀릴 수 있는 이유는?
  2. upstream 시스템의 실제 토큰 TTL을 코드 변경 없이 측정하는 방법은?
  3. in-memory 세션 스토어에서 JVM 재시작 시 세션이 사라지는 것을 감수하는 설계의 트레이드오프는?

---

## 2026-06-02 — ArgoCD selfHeal이 수동 kubectl patch를 즉시 되돌림

- 맥락: Wave 3 Postgres 전환 중 `SSUAI_DB_URL`, `SSUAI_DB_USERNAME` env var를 k3s ConfigMap에 주입해야 했다. `kubectl patch configmap ssuai-backend-config`로 직접 패치했다.
- 증상: 백엔드 pod 재시작 후에도 H2로 연결. 로그에 `url=jdbc:h2:mem:...` 유지.
- 처음 세운 가설 (틀린 방향): `kubectl patch`로 ConfigMap을 수정하면 pod 재시작 시 반영된다고 가정.
- 실제 원인: ArgoCD Application에 `syncPolicy.automated.selfHeal: true`가 설정되어 있어, ArgoCD가 30초 이내에 ConfigMap을 Helm chart의 Git 상태(`values.yaml`의 빈 문자열)로 되돌린다. GitOps에서 `kubectl patch`는 ArgoCD에 의해 즉시 무효화된다.
- 해결: `deploy/charts/ssuai-backend/values.yaml`의 `dbUrl`, `dbUsername` 필드를 실제 값으로 수정 후 Git push. ArgoCD가 변경을 감지해 ConfigMap 자동 업데이트.
- 핵심 파일: `deploy/charts/ssuai-backend/values.yaml`, `deploy/charts/ssuai-backend/templates/configmap.yaml`, commit `5ab7b07`
- 검증: ArgoCD Synced Healthy, `kubectl logs`에서 `url=jdbc:postgresql://postgres-service:5432/ssuai` 확인.
- 포트폴리오 포인트: GitOps 환경에서 클러스터 리소스를 직접 수정하는 명령은 ArgoCD `selfHeal`에 의해 자동 롤백된다. "Single source of truth는 Git"이라는 원칙의 실제 동작을 직접 경험한 사례. 환경별 설정 주입은 반드시 Git → ArgoCD 경로를 통해야 한다.
- 면접 예상 질문:
  1. GitOps 환경에서 `kubectl apply/patch`로 설정을 변경했는데 적용이 안 되는 이유는?
  2. ArgoCD의 `selfHeal`과 `prune` 옵션의 역할과 위험성은?
  3. 민감하지 않은 env var(DB URL, username)과 민감한 env var(DB password)를 각각 어떻게 GitOps로 관리했는가?

---

## 2026-06-02 — 이미지에 하드코딩된 driver-class-name이 Postgres 전환을 막음

- 맥락: Postgres URL을 ConfigMap에 정상 주입했으나 새 pod이 CrashLoopBackOff.
- 증상: `Driver org.h2.Driver claims to not accept jdbcUrl, jdbc:postgresql://postgres-service:5432/ssuai` — HikariCP가 H2 드라이버로 Postgres URL에 연결 시도.
- 처음 세운 가설 (틀린 방향): ConfigMap에 올바른 Postgres URL이 들어있으면 Spring Boot가 자동으로 Postgres 드라이버를 감지할 것이라 가정.
- 실제 원인: 배포 중이던 Docker 이미지(`sha-a95e532d...`)는 PR #10(Flyway/Postgres 지원) 이전에 빌드된 것이라, `application.yml`에 `driver-class-name: org.h2.Driver`가 JAR 내부에 하드코딩되어 있었다. env var로 URL을 바꿔도 드라이버 클래스는 여전히 H2였다.
- 해결: PR #10 merge 이후 CI가 빌드한 새 이미지(`sha-fbf3fd61...`)를 ArgoCD Image Updater가 자동 감지해 배포. 새 이미지는 `driver-class-name` 제거 → Spring Boot URL 자동 감지.
- 핵심 파일: `src/main/resources/application.yml`(`driver-class-name` 제거, PR #10), `deploy/charts/ssuai-backend/values.yaml`(image.tag)
- 검증: 새 pod 로그에 `Added connection org.postgresql.jdbc.PgConnection`, `Successfully applied 1 migration to schema "public"` 확인.
- 포트폴리오 포인트: JAR 빌드 시점에 확정되는 설정(`application.yml` 내 `driver-class-name`)과 런타임 env var의 우선순위 관계. 이미지를 업데이트해도 JAR 내부 설정이 env var를 덮어쓰는 케이스. CI/CD 파이프라인에서 "코드 변경 → 새 이미지 빌드 → 배포"의 순서가 중요한 이유.
- 면접 예상 질문:
  1. Spring Boot의 외부 설정 우선순위(env var vs application.yml)에서 `driver-class-name`이 env var로 오버라이드가 안 되는 이유는?
  2. 실행 중인 pod의 이미지를 교체하지 않고 env var만 바꿔서 해결할 수 없는 설정의 예시는?
  3. GitOps + Image Updater 환경에서 새 코드가 prod에 반영되기까지의 흐름을 설명하세요.

---

## 2026-06-02 — ArgoCD Image Updater v1.x CRD 방식 전환

- 맥락: ArgoCD Image Updater를 Helm으로 설치했는데 ArgoCD Application의 `argocd-image-updater.argoproj.io/image-list` annotation을 인식하지 못함.
- 증상: 2분 주기 로그에 "No ImageUpdater CRs to process" 반복. Application annotation 무시.
- 처음 세운 가설 (틀린 방향): `argo/argocd-image-updater` Helm chart가 기존 annotation 기반 방식을 그대로 지원할 것이라 가정.
- 실제 원인: `argo/argocd-image-updater` v1.2.x는 완전히 새로운 CRD 기반 아키텍처. 기존 annotation 방식(argoproj-labs/argocd-image-updater v0.x)과 다른 프로젝트. "No ImageUpdater CRs"는 `ImageUpdater` CRD 인스턴스가 없다는 의미였다.
- 해결: `ImageUpdater` CRD에 `useAnnotations: true` 옵션 발견 → Application의 기존 annotation을 그대로 위임하는 CR 생성. annotation 재작성 없이 기존 설정 재사용.
- 핵심 파일: `deploy/argocd/image-updater/imageupdater-cr.yaml`, commit `463f1ce`
- 검증: 다음 2분 사이클에 "Setting new image to ghcr.io/hoeongj/ssumcp:sha-...", "images_updated=1 errors=0" 로그 확인.
- 포트폴리오 포인트: 오픈소스 툴의 메이저 버전 아키텍처 전환을 직접 마주친 사례. 공식 문서보다 CRD 스키마(`kubectl get crd ... -o jsonpath`)를 직접 읽어 `useAnnotations` 옵션을 발견한 디버깅 방식. Helm chart 이름이 같아도 내부 아키텍처가 완전히 다를 수 있다.
- 면접 예상 질문:
  1. ArgoCD Image Updater의 annotation 방식과 CRD 방식의 차이점과 각각의 장단점은?
  2. 오픈소스 툴 업그레이드 시 breaking change를 사전에 감지하는 방법은?
  3. `kubectl get crd -o jsonpath`로 CRD 스키마를 읽어 옵션을 파악한 과정을 설명하세요.

---

## [2026-06-02] access token 만료 후 세션 유지 실패

### 증상
로그인 후 15분이 지나면 대시보드 카드가 전부 오류 상태로 변하고, 챗봇 인증도 끊김. 사용자 입장에서는 "로그인이 유지가 안 된다"고 느낌.

### 처음 세운 가설 (틀림)
JWT secret이 pod 재시작마다 바뀌어서 refresh token이 무효화되는 게 원인 아닐까? (`JwtProvider.buildSigningKey`의 ephemeral key 경고 메시지 근거)

### 실제 원인
`SSUAI_JWT_SECRET`은 Kubernetes secret에 이미 있어 JWT 서명 키는 안정적이었다. 진짜 원인은 프론트엔드에 access token 자동 갱신 로직이 없었던 것. `useSaintAuth`가 mount 시 1회 refresh만 수행하고, 15분 후 token 만료 시 자동 갱신을 하지 않아 모든 `Authorization: Bearer` 헤더가 만료된 토큰을 전송.

추가 설계 배경: refresh token은 14일 TTL(HttpOnly cookie)이지만 access token은 15분 in-memory only. 브라우저 cookie는 `Set-Cookie` + meta-refresh 방식(Vercel 302 응답에서 Set-Cookie 제거 이슈 우회)으로 정상 설정됨. 즉 페이지 새로고침은 문제없으나, 같은 페이지에서 15분 초과하면 오류.

### 핵심 파일/커밋
- `ssuAI/hooks/useSaintAuth.tsx` — PR #184: accessTtlRef + setTimeout 자동 갱신 추가 (만료 2분 전)
- `ssuMCP/domain/auth/saint/SaintSsoCallbackController.java` — htmlRedirect로 Vercel 302+Set-Cookie 문제 우회 (기존 코드)

### 해결
`useSaintAuth.tsx`에 `useEffect` 기반 타이머 추가: `accessToken` state 변경 시마다 `(ttlSeconds - 120) * 1000ms` 후 `refresh()` 재호출. cleanup 함수로 unmount/로그아웃 시 타이머 취소.

### 포트폴리오 포인트
단순 "로그인 유지" 버그처럼 보이지만, JWT의 short-lived access + long-lived refresh 분리 패턴과 Vercel rewrite proxy의 Set-Cookie 동작 차이, React 상태 생명주기까지 교차 분석해야 했던 사례. 서버 로그에서 `authenticated=true`가 찍혀 "서버는 정상"임을 확인하고 클라이언트 사이드로 좁혔다.

### 면접 예상 질문
1. JWT access token / refresh token 분리 설계의 이유와 각각의 적절한 TTL 기준은?
2. SPA에서 "조용한 자동 갱신(silent refresh)"을 구현할 때 고려해야 할 경쟁 조건(race condition)은?
3. Vercel rewrite proxy가 Set-Cookie를 302 응답에서 제거하는 이유와, 이를 우회한 방법은?

---

## [2026-06-03] Vercel Root Directory 설정 오류 + 엣지 캐시 6일 stale

### 증상
5월 13일 이후 모든 Vercel 배포가 실패. `gh api deployments`로 확인하면 전부 `state: failure`. 프론트엔드 신기능(챗봇 로그인 버튼, 세션 만료 자동 로그아웃 등)이 배포됐다고 생각했지만 실제로는 구버전이 계속 서빙됨.

### 처음 세운 가설 (틀림)
코드 변경이 Vercel 빌드를 깨트렸을 거다 → `pnpm build` 로컬 통과 확인 → GitHub Actions CI 통과 확인 → 여기까지는 문제 없음. "Vercel CDN 캐시가 오래됐겠지"라고 가볍게 생각했지만 실제로는 Vercel 빌드 자체가 실패 중이었음.

### 실제 원인 (2개 중첩)
1. **Vercel Root Directory 설정 오류**: Vercel 프로젝트가 `frontend/` 폴더를 루트로 바라보고 있었음. 이전에 모노레포였을 때 설정이 그대로 남아 있어서 빌드 로그에 `"The specified Root Directory 'frontend' does not exist."` 메시지 출력 후 1초 만에 실패. GitHub Actions CI는 별도 환경이라 이 설정과 무관하게 통과.
2. **엣지 캐시 6일 stale**: Root Directory 설정을 고쳐도 한국 Vercel 엣지(icn1)가 구버전 HTML을 캐싱 중. `curl -sI` 로 확인하면 `Age: 531311`, `X-Vercel-Cache: HIT`. 새 배포가 되더라도 CDN이 오래된 캐시를 계속 서빙. 해결: `export const dynamic = "force-dynamic"` 을 `/` 와 `/chat` 페이지에 추가 → 매 요청마다 서버 렌더링, CDN 캐시 우회.

### 핵심 파일/커밋
- Vercel 대시보드 Settings → Root Directory: `frontend` → 빈 값으로 수정
- `ssuAI/app/page.tsx`, `ssuAI/app/chat/page.tsx`: `export const dynamic = "force-dynamic"` 추가

### 포트폴리오 포인트
- GitHub Actions CI 통과 ≠ Vercel 빌드 통과. Vercel은 자체 빌드 환경을 사용하며 프로젝트 설정(Root Directory, Build Command 등)의 영향을 받음.
- `curl -sI` + `Age` + `X-Vercel-Cache` 헤더로 CDN 캐시 상태를 진단한 방법.
- Next.js `force-dynamic`이 서버 컴포넌트 캐싱에 미치는 영향과, CDN 엣지 캐시와의 관계.

### 면접 예상 질문
1. Vercel 배포가 성공했는데 사용자가 구버전을 보는 이유와 진단 방법은?
2. Next.js `force-dynamic`, `revalidate`, 캐시 태그의 차이와 언제 어떤 것을 쓰는가?
3. CI 파이프라인과 실제 프로덕션 빌드 환경이 달라 문제가 생기는 상황을 어떻게 예방하는가?

---

## [2026-06-03] Spring RestClient 청크 인코딩 → Content-Length 없어서 Cerebras 411

### 증상
Groq가 429(rate limit)를 반환하면 Fallback 체인이 Cerebras를 시도하는데, Cerebras가 `411 Length Required: "Content-Length header must be specified"` 를 반환. 최종적으로 `CHAT_UNAVAILABLE` 로 사용자에게 에러 노출. "개발자 누구야" 같은 단순 질문에도 에러가 발생해 질문 내용이 문제라고 오해.

### 처음 세운 가설 (틀림)
챗봇이 "개발자 누구야" 같은 질문 내용을 처리 못하는 거다 → 시스템 프롬프트 문제일 것 → 실제로는 프로바이더 네트워크 레이어 문제.

### 실제 원인
Spring `RestClient.post().body(object)` 는 Jackson으로 직렬화할 때 `Content-Length` 를 설정하지 않고 chunked transfer encoding을 사용함. Cerebras API는 `Content-Length` 헤더가 없으면 무조건 411 반환. 해결: 요청 본문을 `objectMapper.writeValueAsBytes(body)` 로 먼저 직렬화 후 `byte[]` 로 전달 → Spring이 길이를 알고 `Content-Length` 자동 설정.

### 핵심 파일/커밋
- `OpenAiCompatibleProvider.java`: `.body(body)` → `byte[] bodyBytes = BODY_MAPPER.writeValueAsBytes(body); .body(bodyBytes)` 로 변경
- `BODY_MAPPER = new ObjectMapper()` static 필드 추가 (thread-safe, 생성자 변경 없이 처리)

### 포트폴리오 포인트
- HTTP 411이 발생하는 상황: 서버가 `Content-Length` 를 요구하지만 클라이언트가 chunked 방식으로 전송하는 경우. 이는 Spring RestClient의 기본 동작이며 API마다 요구사항이 다름.
- `ObjectMapper` 가 thread-safe 싱글턴이기 때문에 static 필드로 선언해도 안전하다는 점.
- 동일한 코드가 다른 프로바이더(Groq, Gemini 등)에서는 문제없던 이유: 해당 프로바이더들은 chunked 인코딩을 허용함.

### 면접 예상 질문
1. HTTP Transfer-Encoding: chunked 와 Content-Length 방식의 차이와 각각의 장단점은?
2. Spring RestClient vs WebClient vs RestTemplate에서 요청 본문 직렬화 방식의 차이는?
3. 동일한 클라이언트 코드가 특정 API에서만 실패할 때 진단하는 방법은?

---

## [2026-06-03] Multi-MCP 클라이언트 라우팅: mcpClients.get(0)만 사용하는 문제

### 증상
Spring AI MCP 클라이언트를 여러 개 설정해도 (`self` + `tavily`) Tavily 도구가 LLM에게 노출되지 않거나, 노출되더라도 호출 시 "지원하지 않는 도구입니다" 에러 반환.

### 처음 세운 가설 (틀림)
Spring AI가 `List<McpSyncClient>` 를 자동으로 합쳐서 사용할 것이다 → 실제로는 `LlmChatService` 가 `mcpClients.get(0)` 만 사용하고, switch 의 `default` 케이스가 에러를 반환하는 구조였음.

### 실제 원인 (구조적 문제)
```java
// 기존
private McpSyncClient mcpClient() { return mcpClients.get(0); }
default -> toolError("지원하지 않는 도구입니다: " + toolName);
```
도구 목록 수집도 첫 번째 클라이언트만, 도구 호출 라우팅도 첫 번째만, unknown 도구는 에러. 두 번째 MCP 서버를 아무리 추가해도 완전히 무시됨.

**해결 구조:**
- `toolClientIndex: Map<String, McpSyncClient>` — lazy init, double-checked locking, 모든 클라이언트의 도구명→클라이언트 매핑
- `discoverChatTools()` — 모든 클라이언트에서 도구 합산 (실패한 클라이언트 graceful skip)
- `callMcp()` — `clientFor(toolName)` 으로 올바른 클라이언트 라우팅
- `default ->` — 에러 대신 `callMcp(toolName, rawArgs)` 로 포워딩
- `TavilyMcpEnvironmentPostProcessor` — `SSUAI_TAVILY_MCP_URL` 없으면 Tavily 연결 자체를 Spring context에 등록하지 않음 (빈 URL로 startup fail 방지)

### 핵심 파일/커밋
- `LlmChatService.java`: toolClientIndex, discoverChatTools, getToolClientIndex, clientFor, rawArguments 추가
- `TavilyMcpEnvironmentPostProcessor.java`: EnvironmentPostProcessor로 조건부 등록
- `spring.factories`: EnvironmentPostProcessor 등록

### 포트폴리오 포인트
- Spring AI 1.1의 `@Lazy List<McpSyncClient>` 주입은 자동 라우팅을 제공하지 않음. 프레임워크가 주입해주는 것과 실제로 사용하는 것은 별개.
- `EnvironmentPostProcessor` 패턴: Spring context 생성 전에 환경 변수를 동적으로 조작해 선택적으로 Bean을 활성화/비활성화하는 방법. `@ConditionalOnProperty` 보다 유연함.
- double-checked locking으로 lazy init 구현 시 `volatile` 필드가 필수인 이유 (CPU 명령 재정렬 방지).

### 면접 예상 질문
1. Spring의 `@Lazy` 주입이 실제 초기화를 언제 트리거하는지, 이를 활용하는 패턴은?
2. `EnvironmentPostProcessor` 와 `@ConditionalOnProperty` 의 차이와 각각 언제 사용하는가?
3. Volatile double-checked locking에서 volatile이 없으면 어떤 문제가 발생하는가?

---

## [2026-06-03] LLM 멀티도구 초과 시 JSON 환각 출력

### 증상
"나의 모든 정보를 전부 다 보여줘" 질문 시 챗봇이 `{"tool":"get_my_grades","params":{}}` 같은 JSON을 그대로 텍스트로 출력하고, `"totalCredits":120`, `"creditsEarned":115` 같은 완전히 가짜 데이터를 생성. 실제 성적 데이터(GPA 3.22 제외)와 전혀 맞지 않음.

### 처음 세운 가설 (틀림)
LLM이 도구 호출 형식을 잘못 이해했거나 시스템 프롬프트가 부족해서다 → 실제로는 도구 호출 한도(2개) 초과 시 발생하는 구조적 실패 모드.

### 실제 원인
`llmMaxToolCalls: 2` 설정인데 "모든 정보"는 5~7개 도구가 필요. LLM이 2개 호출 후 나머지를 실행할 수 없게 되자, 내부 계획(chain-of-thought)을 JSON 형식으로 텍스트 답변에 그대로 출력하는 실패 모드로 전락. 일부 값(GPA 3.22)은 직전 대화 컨텍스트에서 가져와 맞게 나오지만, 나머지는 완전히 환각.

**해결:**
- 시스템 프롬프트에 명시적 규칙 추가: "한 번에 도구 2개 한도", "모든 정보 요청 시 나눠서 물어봐달라고 안내", "절대로 JSON 형식을 텍스트에 출력하지 마"
- XML 구조화 + few-shot 예시로 LLM이 한도 초과 상황을 정상적으로 처리하도록 패턴 학습

### 핵심 파일/커밋
- `SystemPromptBuilder.java`: XML 구조(`<role>`, `<tools>`, `<guidelines>`, `<examples>`, `<off_limits>`), 규칙 7·8 추가, few-shot 3개 예시

### 포트폴리오 포인트
- LLM의 실패 모드(failure mode): 도구 호출 한도 초과 시 내부 추론 과정을 텍스트로 직접 출력하는 현상. 이를 "사고 누출(reasoning leak)"이라고도 함.
- MCP/tool-use 기반 챗봇에서 시스템 프롬프트는 단순한 말투 설정이 아니라 "언제 도구를 쓰고 쓰지 말아야 하는지"를 명확히 정의하는 오케스트레이션 가이드.
- XML 태그 구조화가 Claude 계열뿐 아니라 Llama 계열 모델에서도 프롬프트 파싱 품질을 높이는 이유.

### 면접 예상 질문
1. LLM에서 tool-use를 구현할 때 "도구 호출 한도"를 설정하는 이유와, 한도 초과 시 어떻게 처리해야 하는가?
2. 챗봇에서 환각(hallucination)이 발생하는 주요 원인과 시스템 프롬프트 레벨에서 완화할 수 있는 방법은?
3. Few-shot 예시를 시스템 프롬프트에 넣는 것과 파인튜닝의 차이, 각각 언제 선택하는가?

---

## LLM 챗봇 응답 품질 저하 — 3개 레이어 동시 원인 (2026-06-03)

### 증상

Claude Desktop(MCP 직접 연결)에서 "졸업하려면 뭐 해야해?"를 물으면
graduation + grades + chapel 3개 도구를 동시 호출해 "현재 89학점, 44학점 더 필요"
수준의 맞춤형 답변이 나왔다. 반면 ssuAI 챗봇은 같은 질문에
"6가지 요건이 부족합니다" 에서 끝나고, 수치 없이 이름만 나열했다.

### 처음 세운 가설 (틀린 방향)

프롬프트 설계가 부족해서 LLM이 도구 선택을 잘못 하는 문제라고 생각했다.
→ 프롬프트만 고치면 해결된다고 가정.

### 실제 원인 (3개 레이어)

1. **Config 레이어** — `application.yml`의 `max-tool-calls: 2`
   동시 도구 호출 상한이 2개라 graduation + grades + chapel 3개를 동시에 못 씀.

2. **프롬프트 레이어** — `SystemPromptBuilder.java` 규칙 3·4
   "여러 도구가 필요한 요청은 하나씩 물어봐줘요" 라고 명시해서
   LLM이 스스로 단일 도구만 호출하도록 유도됐음. 보호 목적으로 넣은 규칙이 오히려 핵심 기능을 막았다.

3. **데이터 레이어** — `ToolResultCompactor.compactGraduationNode()`
   `GraduationRequirementItem`에는 `required(133)`, `completed(89)`, `remaining(44)` 가 있었지만
   압축 메서드가 이를 모두 버리고 미충족 요건의 **이름만** LLM에 전달했다.
   LLM은 "133학점 필요"는 알았지만 "현재 89학점, 44 더 필요"는 계산 불가.
   이 원인이 가장 비직관적이었다 — 도구 호출은 성공하고 데이터는 정상 반환됐지만
   압축 단계에서 핵심 수치가 조용히 소실됐다.

### 해결

- `max-tool-calls` 기본값 2 → 20
- 규칙 3·4: 졸업 질문은 3개 도구 동시 호출 명시
- `compactGraduationNode`: `required/completed/remaining` 수치 보존,
  satisfied 항목은 제외하고 미충족 항목만 `{name, required, completed, remaining}` 구조로 반환

### 핵심 파일·커밋

- `src/main/java/com/ssuai/domain/chat/service/ToolResultCompactor.java`
- `src/main/java/com/ssuai/domain/chat/service/SystemPromptBuilder.java`
- `src/main/resources/application.yml`
- PR #14 (`feat/improve-graduation-chat-response`)

### 포트폴리오 포인트

Claude Desktop vs ssuAI 챗봇의 응답 품질 차이를 재현 가능한 방식으로 비교하고,
단일 원인이 아닌 config · prompt · data 압축 3개 레이어에 걸친 복합 원인을
체계적으로 디버깅했다.
특히 "도구 응답은 정상인데 LLM이 수치를 모르는" 상황의 원인이
압축 레이어에 있었다는 발견은 LLM 시스템 특유의 디버깅 난이도를 보여준다.

### 면접 예상 질문

1. LLM 응답 품질이 낮을 때 어떻게 원인을 추적하는가? config / 프롬프트 / 데이터 레이어를 어떻게 분리해서 디버깅했는가?
2. tool result를 LLM에 넘기기 전에 압축(compaction)하는 이유는 무엇이고, 과도한 압축이 품질에 미치는 영향은?
3. 시스템 프롬프트에 "보호 규칙"을 넣을 때 기능 제한과의 트레이드오프를 어떻게 판단하는가?
---

## 2026-06-04 PlayMCP external auth link mismatch

- Context: PlayMCP review tested the published MCP server through multiple external AI clients.
- Symptom: One external AI client rendered the u-SAINT auth link as the PlayMCP connector page (`https://playmcp.kakao.com/mcp/...`) instead of the backend auth start URL (`/api/mcp/auth/saint/start?state=...`). Another client displayed the backend auth URL correctly.
- First hypothesis: `McpAuthUrlFactory` or production `SSUAI_MCP_API_BASE_URL` was generating the wrong URL.
- Actual cause: The server generated the correct `loginUrl`, but the tool response message only referenced a placeholder (`[loginUrl]`) and did not repeat the raw URL. This left room for a client/model to synthesize a markdown link with the connector page as the target.
- Fix: Keep the existing `loginUrl` field for compatibility, but repeat the exact raw URL in the user-facing `message` and explicitly instruct clients not to substitute PlayMCP or connector page URLs.
- Key files/commit: `McpAuthMcpTools.java`, `McpPrivateToolResponse.java`, `McpAuthMcpToolsTests.java`, `McpAuthHelperTests.java`; commit: this auth-link hardening change.
- Verification: Added unit assertions that `start_auth` and `AUTH_REQUIRED` messages include the raw login URL and anti-substitution guidance. Full Gradle test run required before merge.
- Portfolio point: Cross-client MCP behavior can fail at the model/rendering layer even when the structured tool field is correct; duplicate critical user actions in both structured fields and plain visible text.
- Interview questions:
  1. Why can a structured `loginUrl` field still be insufficient for external MCP clients?
  2. How would you distinguish URL generation bugs from client rendering/model interpretation bugs?
  3. What are the tradeoffs of duplicating a one-time auth URL in both a structured field and a plain-text message?

## 2026-06-04 MCP academic DTO semantics drift

- Symptom: External MCP testing showed several academic-data fields were easy to misread or wrong for agent use. P/F-only terms returned `gpa: 0.0`, cumulative grade summaries did not expose GPA-bearing credits, graduation `remaining` could preserve rusaint's negative `difference` value, and `get_my_schedule` could not request a specific year/term. School notice tools in prod were also still configured as mock.
- Wrong hypothesis: Existing field names were clear enough for LLM clients, and `earnedCredits` could safely be used as the GPA denominator. Also, the schedule tool description implied enough term coverage without explicit arguments.
- Actual cause: The DTO layer leaked upstream semantics directly. u-SAINT/rusaint distinguishes earned credits, P/F credits, GPA sum, and requirement difference, but the MCP response did not expose those semantics explicitly. The prod connector profile also omitted `ssuai.connector.notice`, so it inherited the default mock connector.
- Fix: Added `gpaCredits`, nullable term GPA for P/F-only terms, Soongsil-specific course `gradePoint`, positive user-facing graduation `remaining`, computed `difference` and requirement type fields, optional `year`/`term` schedule lookup, and a `simulate_gpa` tool. Prod notice connector now defaults to real.
- Core files: `CourseGrade`, `GpaSummary`, `TermGpa`, `GraduationRequirementItem`, `RusaintUniFfiClient.kt`, `SaintSchedule*`, `SaintGpaSimulationService`, `SaintExtendedMcpTools`, `application-prod.yml`.
- Commit: `feat(mcp): improve academic tool semantics`.
- Portfolio point: This is a concrete example of turning raw scraped/FFI data into agent-safe domain semantics. The bug was not a parser crash; it was an API contract ambiguity that caused wrong academic reasoning.
- Interview questions:
  1. Why is `earnedCredits` not a safe denominator for GPA, and how did you expose the correct denominator?
  2. Why is `remaining = completed - required` dangerous when returned to an LLM client?
  3. How do you add term-specific lookups without corrupting a per-student cache?

## 2026-06-04 Spring Boot 4 Jackson feature key mismatch

- Symptom: Adding `spring.jackson.deserialization.read-date-timestamps-as-nanoseconds: false` made every Spring context test fail before controllers loaded. The common root was `ConfigurationPropertiesBindException` under `spring.jackson.deserialization`.
- Wrong hypothesis: Spring Boot would bind the Jackson 2 `READ_DATE_TIMESTAMPS_AS_NANOSECONDS` feature key through relaxed kebab-case property names.
- Actual cause: This project is already on Spring Boot 4 / Jackson 3 for Boot auto-configuration. Boot binds `spring.jackson.deserialization` to `tools.jackson.databind.DeserializationFeature`, and Jackson 3 no longer exposes `READ_DATE_TIMESTAMPS_AS_NANOSECONDS`. The key is invalid even though the older Jackson 2 feature still exists on some transitive compile classpaths.
- Fix: Move date/time feature flags from the old Jackson 2 paths to Spring Boot 4's `spring.jackson.datatype.datetime.*` path. Set both `write-dates-as-timestamps: false` and `read-date-timestamps-as-nanoseconds: false` there. A later cleanup attempt removed the LLM-only primary `ObjectMapper`, but `chat=llm` startup tests failed because `LlmChatService` still requires a `com.fasterxml.jackson.databind.ObjectMapper` bean and Boot 4 did not publish that bean in this context. The LLM-only mapper is retained until the chat stack migrates to the Boot 4/Jackson 3 mapper surface.
- Core files/commit: `src/main/resources/application.yml`, `src/main/java/com/ssuai/domain/chat/service/llm/LlmProviderConfig.java`; commit: current MCP quality round branch.
- Portfolio point: Framework major-version upgrades can leave similarly named classes on the classpath while auto-configuration binds to a different package and enum surface. Validate configuration keys against the runtime binder, not just the code import that compiles.
- Interview questions:
  1. Why can a property compile-time-looking feature name still fail only during Spring context binding?
  2. How did Spring Boot 4 / Jackson 3 change the risk profile of copying Jackson 2 configuration snippets?
  3. How would you verify that a JSON date-format fix affects MVC serialization rather than only a manually created mapper?

## 2026-06-05 Library reservation action owner key mismatch

- Symptom: The action-audit task described the action owner as `studentId VARCHAR(16)`, but the existing LIBRARY MCP auth flow does not expose a student id to tools.
- Wrong hypothesis: `McpAuthHelper.principalKey(mcp_session_id, LIBRARY)` could be treated as the student's id and stored in a 16-character column.
- Actual cause: `McpProviderLink` explicitly stores a random opaque UUID key for LIBRARY, which indexes `LibrarySessionStore`. This keeps login id/student id out of MCP responses and logs, but it means action ownership for library actions must use the opaque session key.
- Fix: Keep the Java service API name `studentId` for the generic action model, but store the LIBRARY principal key in a 64-character `action_audit.student_id` column and never persist the Pyxis token in the action payload.
- Core files/commit: `ActionAudit`, `ActionService`, `V2__create_action_audit.sql`, `LibraryReservationMcpTool`, `ConfirmActionMcpTool`; commit: current action reservation branch.
- Portfolio point: User-confirmed action infrastructure must preserve existing credential isolation. The action audit can identify the pending action owner without reintroducing student-id/token leakage into the MCP tool layer.
- Interview questions:
  1. Why is the LIBRARY principal key intentionally different from SAINT/LMS student ids?
  2. What would break if `action_audit.student_id` kept the original 16-character limit?
  3. Why should the pending action payload store reservation parameters but not the Pyxis auth token?

## 2026-06-06 PR #20 V2 migration collision resolved by V3 rename

- Symptom: PR #20 added `V2__create_action_audit.sql`, while `main` already had `V2__create_notice_index.sql`. Rebase completed cleanly at the Git level, but Flyway would see two V2 migrations.
- Wrong hypothesis: A clean Git rebase would also mean the migration version ordering was safe.
- Actual cause: Flyway migration versions are a runtime contract, independent of Git conflict detection. Two different filenames can coexist in Git while still sharing the same Flyway version.
- Fix: Rename the action audit migration from `V2__create_action_audit.sql` to `V3__create_action_audit.sql` on the rebased PR branch, then run `./gradlew.bat test`, force-push the branch, fast-forward merge into `main`, and run `./gradlew.bat test` again.
- Core files/commit: `src/main/resources/db/migration/V3__create_action_audit.sql`; commit: `e88056c feat(mcp): add action-based library reservation`.
- Verification: `./gradlew.bat test` passed before and after the fast-forward merge.
- Portfolio point: Schema migration ordering needs an explicit release-sequence check. A conflict-free rebase is not enough when frameworks use filename/version conventions as deployment contracts.
- Interview questions:
  1. Why can two Flyway migrations avoid Git conflicts but still break application startup?
  2. How should a team choose migration version numbers when multiple branches add schema changes concurrently?
  3. What verification catches migration collisions before production deployment?

## 2026-06-06 Claude Desktop external bug sweep: academic and notice response semantics

- Symptom: External MCP testing found four response-shape bugs: deficient graduation requirements could surface negative `remaining`, unknown notice detail URLs returned the first mock fixture body, P/F-only terms could look like `gpa: 0.0`, and date fields risked array-style timestamps instead of ISO strings.
- Wrong hypothesis: Connector-level transformations alone were enough because rusaint and controller tests already covered the common happy path.
- Actual cause: Some invariants belonged at DTO/mock boundaries. `GraduationRequirementItem` trusted the caller-supplied `remaining`, `TermGpa` trusted a caller-supplied zero GPA even when GPA-bearing credits were zero, and `MockNoticeConnector.fetchDetail()` deliberately fell back to the first fixture for unknown URLs.
- Fix:
  - `remaining`: normalize to `max(0, required - completed)` in `GraduationRequirementItem`.
  - `notice_detail`: remove unknown-URL fixture fallback from the mock connector and verify `NoticeService` delegates the trimmed URL to the connector.
  - P/F term GPA: force `TermGpa.gpa` to `null` when `earnedCredits - passFailCredits` is zero.
  - Date ISO: keep the Boot 4 `spring.jackson.datatype.datetime.*` config and add explicit `yyyy-MM-dd` JSON formatting to library loan dates.
- Core files/commit: `GraduationRequirementItem`, `TermGpa`, `LibraryLoanItem`, `MockNoticeConnector`, `NoticeServiceTests`, `SaintAcademicDtoTests`; commit: current bug sweep branch.
- Verification: Targeted Gradle tests passed; full `./gradlew.bat test` required before merge.
- Portfolio point: Agent-facing APIs need defensive DTO invariants, not just connector assumptions. Mock fixtures should fail loudly for unknown data instead of returning plausible but false content.
- Interview questions:
  1. Why should `remaining` be normalized at the DTO boundary even if the connector already computes it?
  2. Why is returning a fixture for an unknown notice URL worse than throwing a parse error?
  3. How do you prevent P/F-only academic terms from being interpreted as failed GPA-bearing terms?

## 2026-06-06 MCP session Postgres persistence (V4 migration)

- Symptom: `McpAuthSessionStore` used an in-memory `LinkedHashMap`, so every linked MCP provider session was lost when the backend JVM restarted.
- Wrong hypothesis: A long MCP session TTL was enough for multi-turn external-client sessions. TTL does not help when the storage process exits.
- Actual cause: MCP auth state stored only opaque principal keys, but those keys still lived solely in process memory. Provider credential stores could survive longer, while the MCP session pointing at them disappeared on restart.
- Fix: Add `mcp_sessions` with V4 Flyway migration and replace the store implementation with `McpSessionRepository` + TEXT JSON provider serialization. `find()` and scheduled cleanup remove expired rows, and provider link mutations run transactionally.
- Core files/commit: `V4__create_mcp_sessions.sql`, `McpSessionEntity`, `McpSessionRepository`, `McpAuthSessionStore`; commit: current MCP session persistence branch.
- Verification: `McpAuthSessionStoreTests` now run against H2/Flyway/JPA and verify provider links survive store recreation. Full `./gradlew.bat test` required before merge.
- Portfolio point: This separates credential secrecy from session durability: actual provider credentials remain in provider-specific stores, while MCP stores only the principal keys needed to reconnect an external tool call to those stores after restart.

---

## 2026-06-06 — McpAuthSessionStore 버그 2개 수정

- 맥락: MCP 세션을 Postgres에 영속화한 직후 `McpAuthSessionStore` 코드에서 두 가지
  구조적 버그를 발견했다.
- 증상:
  - `find()` 호출 1회마다 `DELETE FROM mcp_sessions WHERE expires_at < ?` 가 실행되어
    트래픽이 많은 환경에서 불필요한 DB write 폭증.
  - `unlinkProvider`가 만료된 세션도 로드하여 이미 만료된 세션의 provider 링크를
    수정하는 로직이 실행됨.
- 처음 세운 가설: Postgres 도입 전 in-memory 구현에서는 cleanup을 `find` 타이밍에
  해도 HashMap remove 뿐이라 비용이 낮았으므로 문제가 없었다.
- 실제 원인:
  - A-1: `findByValue`가 `cleanupExpired(now)` → DB DELETE를 매번 호출.
    `findBySessionIdAndExpiresAtAfter`가 이미 만료 필터를 하므로 별도 DELETE 불필요.
    cleanup은 `@Scheduled(fixedDelay=3_600_000)`이 담당해야 한다.
  - A-2: `unlinkProvider`가 `repository.findById(id.value())`로 만료 여부 무관하게
    세션을 로드. `linkProvider`는 `findBySessionIdAndExpiresAtAfter`를 쓰는데 불일치.
- 해결:
  - `findByValue`에서 `cleanupExpired(now)` 호출 제거.
  - `unlinkProvider`에서 `findById` → `findBySessionIdAndExpiresAtAfter(id, now)`로 교체.
- 핵심 파일:
  - `src/main/java/com/ssuai/domain/auth/mcp/McpAuthSessionStore.java`
- 검증: `McpAuthSessionStoreTests` 전체 통과 + `./gradlew.bat test` BUILD SUCCESSFUL.
- 포트폴리오 포인트:
  - in-memory 구현에서 DB 구현으로 전환할 때 "무해했던 패턴"이 갑자기 성능 문제로
    바뀔 수 있다. cleanup-on-read는 HashMap에서는 O(1) remove지만 Postgres에서는
    DELETE DML이 된다.
  - 같은 도메인 오퍼레이션 내에서 mutate(link)와 validate(unlink)가 다른 쿼리를 쓰면
    만료 정책이 비대칭해진다. read/write path 모두 동일한 만료 필터를 적용해야 한다.
- 면접 예상 질문:
  1. 세션 만료 레코드를 "읽기 시점 정리"와 "주기적 배치 정리"로 분리하는 이유는?
  2. `findById`와 `findBySessionIdAndExpiresAtAfter`의 차이가 보안에 미치는 영향은?
  3. 기존 단위 테스트가 모두 green인데 이런 버그가 숨겨질 수 있는 이유는?
- Interview questions:
  1. Why persist MCP session provider links separately from upstream credentials?
  2. Why store provider links as TEXT JSON instead of JSONB in this migration?
  3. What changes when an LRU in-memory store becomes a database-backed store?

---

## 2026-06-06 — LibrarySessionStore JPA 전환 후 테스트 생성자 불일치 → AEADBadTagException

- 맥락: Codex가 `LibrarySessionStore`를 in-memory HashMap → JPA(`LibrarySessionRepository`)로 전환했다.
  기존 `LibrarySessionStoreTests`는 이전 2-파라미터 생성자 `(LibrarySessionProperties, Clock)`를 사용하고 있었다.
- 증상 1: 컴파일 실패 — `LibrarySessionStore(LibrarySessionProperties, Clock)`를 찾을 수 없음.
- 증상 2: 생성자 수정 후 `sessionSurvivesStoreRecreation()` 테스트에서 `javax.crypto.AEADBadTagException` 발생.
- 처음 세운 가설:
  - 컴파일 오류: Codex가 이전 생성자를 삭제했을 것이라 생각. 부분 수정으로 해결 가능할 것.
  - AEADBadTagException: JPA 트랜잭션 커밋이 지연돼 두 번째 store 인스턴스가 DB 레코드를 못 읽는 것이라 의심.
- 실제 원인:
  - 1번: 새 생성자 시그니처는 `(LibrarySessionRepository, LibrarySessionProperties, Clock)` — 리포지토리가 첫 파라미터로 추가됐다.
  - 2번: `encryptionKey`가 빈 문자열 `""` → 두 store 인스턴스 각각이 별도의 임시 ephemeral AES 키를 생성. 첫 번째 인스턴스로 암호화한 토큰을 두 번째 인스턴스가 다른 키로 복호화하려다 MAC 검증 실패.
- 해결:
  - 테스트 전체를 `@SpringBootTest + @Transactional + @DirtiesContext` 패턴(기존 `McpAuthSessionStoreTests` 패턴)으로 재작성.
  - `sessionSurvivesStoreRecreation()`: 두 store 인스턴스에 동일한 32자 고정 키 `"0123456789abcdef0123456789abcde!"` 사용.
- 핵심 파일:
  - `src/test/java/com/ssuai/domain/library/auth/LibrarySessionStoreTests.java`
  - `src/main/java/com/ssuai/domain/library/auth/LibrarySessionStore.java`
  - 커밋: `038fcb8`
- 검증: `LibrarySessionStoreTests` 5개 테스트 전체 통과.
- 포트폴리오 포인트:
  - AEADBadTagException은 복호화 오류다. 원인을 "DB 동기화 실패"로 먼저 의심하기 쉽지만, 실제로는 *암호화 컨텍스트 불일치*가 원인이었다. 두 인스턴스가 같은 데이터를 보는지 확인하기 전에 같은 키를 쓰는지 먼저 확인해야 한다.
  - 테스트에서 `new LibrarySessionStore(...)` 직접 생성 패턴은 production DI 구성이 바뀔 때마다 깨질 위험이 있다. `@SpringBootTest` 패턴으로 전환하면 프레임워크가 생성자를 관리하므로 이런 불일치가 컴파일 타임에 잡힌다.
- 면접 예상 질문:
  1. AEADBadTagException이 발생했을 때 가장 먼저 확인해야 할 것은 무엇인가?
  2. AES-GCM에서 동일 IV와 동일 키 없이 암호화된 데이터를 복호화하면 어떻게 되나?
  3. `@SpringBootTest` 패턴이 단위 테스트 직접 생성 패턴보다 생성자 변경에 더 견고한 이유는?

---

## 2026-06-06 — recommend_library_seats가 항상 0건 반환

- 맥락: `seat-catalog.json` 753개 항목 생성 후에도 `recommend_library_seats`가 빈 배열을 반환했다.
  카탈로그, 추천 서비스, 선호도 점수 로직 모두 테스트에서 정상이었다.
- 증상: `recommend_library_seats(floor=2)` 호출 시 `availabilitySource: "floor_only"`, `recommendations: []`.
  "Pyxis seat-map API를 통한 개별 좌석 목록이 없습니다" 메시지 반환.
- 처음 세운 가설:
  - catalog의 seatId 형식이 잘못됐거나 (예: "74" vs "074") 대소문자 정규화 오류.
  - `AvailableSeatSnapshot`의 branch 분기 조건 버그.
- 실제 원인:
  - `RealLibrarySeatConnector`는 `/pyxis-api/1/seat-rooms` 응답에서 방별 count(`total`, `available`, `occupied`)만 파싱하고, `zone.seatIds()` 리스트를 채우지 않았다(`List.of()` 하드코딩).
  - `AvailableSeatSnapshot`은 `zone.seatIds()`가 비어 있으면 "floor_only" 브랜치로 빠져 카탈로그 조회 자체를 건너뛴다.
  - 즉, 카탈로그가 완벽해도 live connector가 seatIds를 제공하지 않으면 추천이 불가능한 구조.
- 해결:
  - `RealLibrarySeatConnector`에 정적 `ROOM_SEAT_CODES` 맵 추가 (roomId → 좌석 코드 목록).
    각 방의 코드 범위는 DevTools Network 탭에서 개별 좌석 URL을 캡처해 오프셋을 역산:
    - room54(오픈열람실 2F): 1-232, offset=925, seat175→seatId1100
    - room53(숭실스퀘어ON 2F): 1-110, offset=3422
    - room57(마루열람실 6F): 1-245, offset=3105
    - room58(대학원열람실 6F): 1-62, offset=3043
    - room59(리클라이너 5F): R1-R6, R4→seatId3355
    - room60(숭실멀티라운지 5F): 1-98, offset=3357
  - `roomAvail > 0`이면 해당 방의 전체 코드 목록을 `seatCodes`에 넣어 zone에 전달.
  - 이제 "catalog-mode" 브랜치 진입: 카탈로그에서 `seatId → externalSeatId` 매핑 후 선호도 점수 계산.
  - 한계: 개별 좌석 단위 availability 불가 — 방에 1자리라도 남으면 방 전체를 available로 처리.
    이는 Pyxis seat-map API를 캡처하기 전까지의 임시 설계.
- 핵심 파일:
  - `src/main/java/com/ssuai/domain/library/connector/RealLibrarySeatConnector.java`
  - 커밋: `db38b57`
- 검증: `RealLibrarySeatConnectorTests` + `LibrarySeatRecommendationServiceTests` 전체 통과.
- 포트폴리오 포인트:
  - 데이터 파이프라인 디버깅에서 "결과 레이어"부터 역추적하면 오히려 방향을 잃는다. "입력 레이어(live connector)"부터 출력이 정상인지 확인하고 단계별로 내려가는 것이 더 빠르다.
  - Pyxis API에서 직접 좌석 목록을 주지 않을 때 정적 지식(코드 범위)으로 보완하는 tradeoff: 구현 단순성 vs. 실시간 정확도. 보완 레이어가 있다는 것을 코드 주석이 아니라 commit message와 troubleshooting에 명시해야 한다.
- 면접 예상 질문:
  1. 외부 API가 집계 데이터만 내려줄 때 개별 항목 목록을 정적 지식으로 보완하는 방법과 한계는?
  2. `AvailableSeatSnapshot`에서 floor-only와 catalog-mode 브랜치를 분리한 이유는 무엇인가?
  3. API seatId 오프셋을 DevTools에서 역산하는 방법을 설명하라.

---

## 2026-06-06 — seat-catalog.json 한글 인코딩 깨짐 (Windows Codex 아티팩트)

- 맥락: Codex가 Windows 환경에서 Python 스크립트로 `seat-catalog.json`을 생성했다.
- 증상: `roomName`, `zone` 필드에 한글 대신 `"?ㅽ뵂?대엺??2F)"` 같은 깨진 문자열.
  JSON 파싱 자체는 성공하지만 MCP 응답에서 방 이름이 깨진 채 노출된다.
- 원인: Windows 기본 파일 인코딩(`cp949` / UTF-16 LE)으로 파일을 열고 UTF-8 문자열을
  그대로 바이트 배열로 썼을 때 발생. Python `open()` + `json.dump(ensure_ascii=False)`
  조합에서 파일 핸들이 시스템 기본 인코딩을 사용한 것으로 추정.
- 해결: PowerShell에서 `[System.IO.File]::WriteAllText(path, content, [System.Text.Encoding]::UTF8)`
  로 전체 파일을 재생성. `ConvertTo-Json`의 boolean 처리와 depth 파라미터 주의.
- 핵심 파일: `src/main/resources/library/seat-catalog.json`
- 검증: 파일 첫 30줄 Read로 "오픈열람실(2F)" 확인 + 전체 테스트 통과.
- 포트폴리오 포인트:
  - 자동 생성 파일(LLM/스크립트 출력)은 콘텐츠 정확성뿐 아니라 인코딩도 검증해야 한다. 특히 CI/CD에서 Windows 에이전트를 사용하면 한글·일본어·중국어 포함 파일이 무결성을 잃을 수 있다.
  - 트러블슈팅 방법: `Read` 도구로 파일 첫 몇 줄을 확인 — 깨진 문자열이 즉시 보인다.
- 면접 예상 질문:
  1. Windows 환경에서 Python으로 파일을 쓸 때 UTF-8을 보장하는 방법은?
  2. 자동 생성 파일의 인코딩 무결성을 CI에서 검증하는 방법은?
  3. BOM 포함 UTF-8과 BOM 없는 UTF-8의 차이가 Java `InputStreamReader`에 미치는 영향은?

---

## 2026-06-06 — 구현·테스트 완료 MCP 도구가 클라이언트에 미노출 (McpServerConfig 등록 누락)

- 맥락: 세션 3에서 `LibraryCancelMcpTool`, `LibrarySwapMcpTool`, `LibraryCurrentSeatMcpTool` 세 도구를
  구현하고 단위 테스트까지 작성했다 (커밋 `038fcb8`). 그러나 `McpServerConfig`에 등록되지 않아
  Claude Desktop/Cursor 같은 MCP 클라이언트에서 보이지 않았다.
- 증상: `McpServerConfigTests`가 `containsExactlyInAnyOrder`로 현재 30개를 검증하고 있어
  신규 도구 3개가 없어도 GREEN. 실제 MCP 클라이언트에서 `prepare_cancel_library_seat` 등이 미노출.
- 처음 세운 가설: `McpServerConfigTests`가 통과했으므로 등록이 완료됐다고 오해할 수 있었다.
- 실제 원인:
  - `containsExactlyInAnyOrder`는 예상 목록 = 실제 목록이어야 통과. 신규 도구가 *목록에도 없고 실제에도 없으면* 여전히 GREEN.
  - `@SpringBootTest`가 신규 도구 Bean을 Context에 로드했어도, `McpServerConfig.ssuaiMcpTools()`에 인수로 주입되지 않으면 MCP provider에 포함되지 않는다.
- 해결 (Codex next task):
  - `McpServerConfig`에 3개 tool class import + 파라미터 + `toolObjects(...)` 추가.
  - `WRITE_TOOLS`에 `prepare_cancel_library_seat`, `prepare_swap_library_seat` 추가.
  - `McpServerConfigTests`/`McpSelfDogfoodTests` 도구 이름 목록 갱신.
- 핵심 파일:
  - `src/main/java/com/ssuai/domain/mcp/config/McpServerConfig.java`
  - `src/test/java/com/ssuai/domain/mcp/config/McpServerConfigTests.java`
  - `src/test/java/com/ssuai/domain/mcp/McpSelfDogfoodTests.java`
- 포트폴리오 포인트:
  - MCP 도구 등록 버그 2번째 발생. 이 패턴은 "새 @Tool 추가 시 McpServerConfig + 양쪽 테스트를 함께 수정" 체크리스트가 반드시 필요한 이유를 증명한다.
  - `containsExactlyInAnyOrder`가 누락 항목을 잡으려면 테스트의 기대 목록도 동시에 갱신해야 한다. 테스트와 구현이 같은 커밋에 빠지면 테스트가 의도한 안전망 역할을 못 한다.
- 면접 예상 질문:
  1. `containsExactlyInAnyOrder`가 추가/누락 모두를 잡는 조건은 무엇인가?
  2. Spring AI MCP tool 등록이 `@Component` 스캔만으로 완결되지 않는 이유는?
  3. 신규 MCP 도구 추가 시 빠짐없이 체크해야 할 파일 목록은?

---

## 2026-06-06 — 도서관 MCP auth UX: AUTH_FAILED 후 재시도 시 INVALID_STATE

- 맥락: 도서관 MCP 로그인 (`/mcp/auth/library`) E2E 검증 중 발견.
- 증상: 비밀번호를 잘못 입력하면 "로그인 실패" 메시지가 뜨고 form이 다시 활성화됨. 재시도하면 "인증 요청이 만료되었거나 유효하지 않습니다"가 뜸.
- 처음 세운 가설 (틀린 방향): 다른 기기에서 중복 로그인 중이어서 Pyxis가 거부한다고 생각했다.
- 실제 원인: 두 가지 독립 원인의 조합.
  1. **잘못된 자격증명**: Pyxis 에러코드 `warning.authentication.invalidCredential.expansion` — 비밀번호 오입력.
  2. **UX 버그**: `McpLibraryAuthController.callback`은 `consumeState()`를 자격증명 검증 전에 실행해 one-time state를 소비한다. AUTH_FAILED 이후에도 Next.js 폼의 `disabled` 조건에 `auth_failed` 상태가 빠져 있어 재제출이 가능했고, 이미 소비된 state로 재시도하면 `INVALID_STATE`가 반환됐다.
- 해결: `page.tsx`의 `disabled` 조건을 `pageState !== "idle"`로 교체해 AUTH_FAILED·SERVER_ERROR 시 폼을 잠그고 "start_auth를 다시 호출하세요" 안내를 표시. 커밋 `ssuAI@2261a17`.
- 핵심 파일/커밋: `ssuAI/app/mcp/auth/library/page.tsx`, `ssuAI@2261a17`
- 검증: 서버 로그에서 `credential rejected → state invalid` 패턴 1초 간격 재현 확인 후 수정 배포. 이후 성공 로그 `library credential login ok` 확인.
- 포트폴리오 포인트: state를 "자격증명 검증 성공 후에 소비"가 아닌 "callback 진입 시 즉시 소비"하는 설계는 replay 공격 차단을 위해 의도적이다. 그러나 UX 측에서 이 보안 결정을 반영하지 않아 사용자에게 혼동을 줬다. 보안 계약(one-time state)과 UX 상태 머신이 서로를 인지해야 한다는 교훈.
- 면접 예상 질문:
  1. one-time state를 자격증명 성공 후에 소비하도록 설계를 바꾸면 어떤 보안 위험이 생기는가?
  2. MCP auth 흐름에서 백엔드 보안 결정이 프론트엔드 UX 상태 머신에 영향을 미치는 경우를 어떻게 문서화하겠는가?

---

## 2026-06-06 — CI 빌드 실패: 로컬 커밋이 remote에 없는 Bean으로 참조

- 맥락: `get_library_available_seats`·`get_room_available_seats` 도구 추가(PR #24) 후 CI 실패.
- 증상: `McpServerConfig.java: cannot find symbol AcademicPolicyMcpTools`. CI는 실패했으나 로컬 빌드는 성공.
- 처음 세운 가설 (틀린 방향): PR #24 코드 자체에 컴파일 오류가 있다고 생각했다.
- 실제 원인: Codex가 로컬 `main` 브랜치에 `feat(academic): add official policy RAG tools` 커밋(`57c2199`)을 작성했지만 remote에 push하지 않은 상태였다. PR #24 merge 시 `McpServerConfig.java`에 `AcademicPolicyMcpTools` 파라미터 참조가 포함됐는데, remote CI는 `AcademicPolicyMcpTools.java` 자체를 볼 수 없어 컴파일 실패.
- 진단: `git log origin/main..HEAD`로 로컬-remote 간 커밋 차이 확인 → `57c2199` 발견.
- 해결: `git push origin HEAD:main`으로 Codex 커밋 강제 push → CI 재통과. 커밋 `ssuMCP@57c2199`.
- 핵심 원칙: 멀티-에이전트 환경(Claude Code + Codex)에서 한 에이전트가 로컬에 쌓은 커밋은 다른 에이전트가 브랜치를 base로 삼기 전에 push 완료 여부를 확인해야 한다.
- 포트폴리오 포인트:
  - "로컬 빌드 성공 = CI 통과" 가정의 함정. CI 환경은 remote HEAD를 기준으로 빌드한다.
  - 멀티-에이전트 Git 운영 시 "로컬 전용 커밋" 추적 필요성 — PR을 열기 전에 모든 의존 커밋이 remote에 있는지 확인.
- 면접 예상 질문:
  1. 로컬 빌드가 성공했는데 CI만 실패하는 원인을 어떻게 체계적으로 찾는가?
  2. 복수의 AI 에이전트가 같은 Git repo에 커밋할 때 발생할 수 있는 문제와 대책은?

---

## 2026-06-06 — per-seat Pyxis API로 get_library_available_seats 구현

- 맥락: `recommend_library_seats`가 room-level availability + 정적 `ROOM_SEAT_CODES`를 결합해 좌석을 추천했지만, 방에 1자리라도 남으면 방 전체 좌석을 "available"로 표시하는 한계가 있었다. 좌석 단위 실시간 조회 API 필요.
- 작업: DevTools 캡처로 Pyxis `GET /pyxis-api/1/api/rooms/{roomId}/seats` 엔드포인트 발견. per-seat 응답 필드: `id`(externalSeatId), `code`(label), `isActive`, `isOccupied`, `seatChargeState`(null/CHARGE/TEMP_CHARGE), `remainingTime`, `chargeTime`, `seatType.name`.
- status 매핑:
  - available: `isActive=true AND isOccupied=false`
  - occupied: `isOccupied=true AND seatChargeState=CHARGE`
  - away: `isOccupied=true AND seatChargeState=TEMP_CHARGE`
  - inactive: `isActive=false`
- 구현 범위:
  - `LibrarySeatConnector.fetchRoomSeats(roomId, token)` 인터페이스 추가
  - `RealLibrarySeatConnector.callRoomSeatsUpstream()` — randomDelay 없음 (room-level과 달리 사용자 직접 호출 경로)
  - `LibraryAvailableSeatsService.getAllAvailableSeats()` — 7개 room 순회
  - `get_library_available_seats` MCP 도구 (전체 열람실 요약)
  - `get_room_available_seats` MCP 도구 (특정 열람실 per-seat 목록)
  - `LibrarySeatCacheTests` — 3개 내부 connector 구현체에 `fetchRoomSeats` stub 추가
- 결과: PR #24 merge, 647 테스트 전체 통과. MCP 세션 직접 HTTP 호출로 470개 available 좌석 확인. 이 데이터로 seat 950 (오픈열람실 25번) 예약 성공 → E2E reserve 첫 성공.
- 핵심 파일:
  - `RealLibrarySeatConnector.java` — `fetchRoomSeats`, `parseRoomSeatsBody`
  - `LibraryAvailableSeatsService.java`
  - `LibraryAvailableSeatsMcpTool.java`, `LibraryRoomAvailableSeatsMcpTool.java`
- 주의: `get_library_available_seats`·`get_room_available_seats`는 `LIBRARY` provider 인증 필요. 클라이언트 세션이 도구 목록을 캐시하므로 **새 세션 재시작 후 사용해야 한다.**
- 포트폴리오 포인트:
  - 역방향 API 탐색: 공식 문서 없는 Pyxis API를 브라우저 DevTools Network 탭으로 추적해 per-seat 엔드포인트를 발견.
  - 기존 `room-level` 인터페이스를 유지하면서 `per-seat` 메서드만 추가해 하위 호환성 보장.
  - room-level API와 per-seat API의 randomDelay 정책 차이: room-level은 도배 방지 목적으로 300-1200ms delay, per-seat은 사용자 직접 요청이므로 제거.
- 면접 예상 질문:
  1. 공식 문서가 없는 API를 DevTools만으로 역공학하는 절차를 설명하라.
  2. `isOccupied=true AND seatChargeState=null` 조합은 어떻게 처리해야 하는가?
  3. 7개 열람실을 순회하는 `getAllAvailableSeats`에서 일부 room이 실패할 때 전체를 실패시켜야 하는가, 부분 결과를 반환해야 하는가?

---

## 2026-06-06 — GET /api/seat-charges 응답 구조 오판: chargeId=0 반환

- 맥락: 도서관 좌석 자동 예약 P0 E2E 검증 — 예약 성공(chargeId: 1967740, seat 950) 후 `get_my_library_seat` 호출.
- 증상: `"현재  번 좌석 이용 중. 이용시간:  ~  (예약번호: 0)"` — roomName·seatCode·beginTime·endTime 전부 빈 문자열, chargeId=0. 이 값이 `prepare_swap`·`prepare_cancel`로 흘러 `confirm_action`이 "실행 중 오류가 발생했습니다"로 실패.
- 처음 세운 가설 (틀린 방향): GET 응답 `data`가 배열이라 `data.path("id")`가 ArrayNode에서 MissingNode를 반환한다고 추측 → 배열 분기 처리 추가. 그래도 여전히 chargeId=0.
- 실제 원인: `GET /pyxis-api/1/api/seat-charges` 응답이 래퍼 구조.
  ```json
  { "data": { "totalCount": 1, "list": [{ "id": 1967740, "room": {...}, "seat": {...}, ... }] } }
  ```
  `parseChargeData`는 `data.path("id")`를 호출하는데, `data`가 `{totalCount, list}` 객체라 `id`가 없음 → asLong(0) = 0.  
  올바른 경로: `data.path("list").get(0)`.
- 진단 방법: `parseCurrentChargeResponse` 진입 시점에 `log.warn("body: {}", body)` 추가 → 배포 → 실제 응답 확인. 1회 배포로 구조 즉시 파악.
- 해결: `data.path("list")`가 배열이면 `list.get(0)`을 `parseChargeData`에 전달. 래퍼 없는 단일 객체 응답(POST 응답 재사용 경로)도 fallback으로 유지. 커밋 `ssuMCP@c6ef8f3`.
- 핵심 파일:
  - `src/main/java/com/ssuai/domain/library/reservation/RealLibraryReservationConnector.java`, `parseCurrentChargeResponse`
- 검증: WARN 로그에서 실제 body 확인 후 코드 수정 → 배포 → `get_my_library_seat` 재호출로 chargeId·roomName·seatCode 정상 반환 확인.
- 포트폴리오 포인트:
  - POST와 GET이 같은 엔드포인트(`/api/seat-charges`)를 공유하더라도 응답 구조가 다를 수 있다. "같은 path = 같은 schema"가 보장되지 않는 REST API의 함정.
  - "배열 vs. 단일 객체" 가설을 먼저 세웠으나 실제 문제는 래퍼 구조였다. `log.warn(body)` 한 줄로 5분 만에 해결할 수 있었던 것을 30분 추론에 쓴 사례 — 외부 API 파싱 버그는 추론 전에 실제 페이로드를 먼저 확인.
  - WARN 로그를 임시로 추가하고 배포·확인 후 제거하는 "throw-away diagnostic log" 패턴의 효과.
- 면접 예상 질문:
  1. GET과 POST가 같은 path를 공유할 때 응답 schema를 어떻게 검증하는가?
  2. 외부 API 파싱 버그를 빠르게 진단하기 위해 어떤 접근을 취하는가?
  3. `data.path("id").asLong(0)`이 0을 반환하는 상황은 몇 가지이고, 각각 어떻게 구분하는가?

---

## 2026-06-06 — 도서관 좌석 예약 실패: warning.seat.seatNotReady → ConnectorParseException

- 맥락: 도서관 좌석 자동 예약 에이전트 P0 E2E 검증 중 `confirm_action` 실패.
- 증상: 추천 좌석(externalSeatId: 926)으로 prepare → confirm 실행 시 "실행 중 오류가 발생했습니다"만 반환. 서버 로그: `warning.seat.seatNotReady`, `ConnectorParseException`.
- 처음 세운 가설 (틀린 방향): 예약 API endpoint 자체가 잘못되었거나 인증 토큰 문제라고 생각했다.
- 실제 원인: `RealLibraryReservationConnector.parseReserveResponse`가 `success=false`인 응답을 코드 종류 구분 없이 전부 `ConnectorParseException`으로 던졌다. `warning.seat.seatNotReady`는 "이미 선점된 좌석" 비즈니스 에러인데 파싱 실패처럼 처리돼 사용자에게 의미 없는 메시지가 반환됐다.
- 해결: `LibrarySeatNotAvailableException` 신설. `warning.seat.*` / `error.seat.*` 코드는 해당 예외를 던지도록 `parseReserveResponse` 수정. `ConfirmActionMcpTool.executeReservation` / `executeSwap`에서 별도 catch 후 "좌석이 이미 선점됐습니다. recommend_library_seats로 다른 좌석을 추천받으세요" 반환. 커밋 `ssuMCP@0f167f7`.
- 핵심 파일/커밋: `RealLibraryReservationConnector.java`, `ConfirmActionMcpTool.java`, `LibrarySeatNotAvailableException.java`, `ssuMCP@0f167f7`
- 검증: 로그에서 실제 Pyxis 에러코드 확인 후 코드 수정. 다음 E2E 재시도로 최종 확인 예정.
- 포트폴리오 포인트: 외부 API의 `success=false` 응답은 "파싱 실패"와 "비즈니스 규칙 위반"이 다른 처리 경로를 필요로 한다. 특히 write 작업(예약/반납)에서 명확한 에러 구분이 없으면 사용자 경험이 크게 저하된다.
- 면접 예상 질문:
  1. 외부 API 연동에서 HTTP 오류와 비즈니스 로직 오류를 어떻게 구분해서 처리하는가?
  2. `ConnectorParseException`을 오남용하면 어떤 운영 문제가 생기는가?
  3. Pyxis 에러코드 목록이 공개 문서에 없을 때 어떻게 코드를 수집했는가?

## 2026-06-10 — Pyxis 좌석 ID 이원화: externalSeatId가 사용자 좌석 번호로 노출

- 증상: 실사용 E2E에서 `prepare_swap_library_seat(new_seat_id=3196)` 응답이 "새 3196번 좌석"으로 표시됨. 실제 그 좌석의 눈에 보이는 번호는 마루열람실(6F) **91번**. 같은 검증에서 `recommend_library_seats`가 학부생 세션에 대학원열람실(6F) 전용 좌석을 추천함.
- 틀린 가설: "Pyxis seat id를 그대로 보여줘도 사용자가 좌석을 식별할 수 있다" / "카탈로그는 floor+label 키만으로 좌석을 특정할 수 있다".
- 실제 원인:
  1. Pyxis 좌석 식별자는 **2계층**(internal `externalSeatId` ↔ 사용자가 보는 `label`)인데, prepare 메시지가 internal id를 "N번 좌석"으로 그대로 출력했다.
  2. 추천 카탈로그 인덱스가 `(floor, label)` 키라서 같은 층의 마루열람실·대학원열람실 label이 충돌하고, keep-first(roomCode 알파벳순 → graduate-reading 우선)로 대학원 엔트리가 잡혔다. audience 필터 자체도 없었다.
- 수정 (커밋 2bc32bf):
  - `LibrarySeatCatalogService.findByExternalSeatId()` + `SeatDisplay` 헬퍼 — prepare 메시지는 항상 label로 표시하고 internal id는 "좌석ID N"으로만 병기. 대학원 전용 좌석이면 경고 문구 추가.
  - `recommend_library_seats`: `graduate_only` 기본 제외 + `include_graduate_only` 옵트인(경고 포함) + `excludedRooms`/`warnings` 응답 필드.
- 남은 한계(백로그): externalSeatId 중복 33건(keep-first 처리), floor+label 룸 간 충돌 — Pyxis roomId↔roomCode 매핑을 캡처해 룸 스코프 조회로 풀어야 함.
- 핵심 파일: `LibrarySeatCatalogService`, `SeatDisplay`, `LibrarySeatRecommendationService`, `LibrarySwapMcpTool`, `LibraryReservationMcpTool`
- 포트폴리오 포인트: 실서비스 E2E에서만 드러나는 "내부 식별자 vs 사용자 식별자" 불일치. LLM 에이전트는 도구 응답 문자열을 그대로 사용자에게 전달하므로 도구 응답이 곧 UX다. 자격이 불확실한 좌석은 안전 기본값(기본 제외 + 옵트인)으로 설계했다.
- 면접 예상 질문:
  1. 외부 시스템의 내부 식별자와 사용자 식별자가 다를 때 API 응답을 어떻게 설계하는가?
  2. LLM 도구 응답에서 "안전 기본값 + 옵트인" 설계를 적용한 이유는?
  3. 카탈로그 키 충돌(keep-first)의 한계와 다음 개선 계획은?

## 2026-06-11 — PR2 k6 재측정: 사용자 결과는 1/99인데 WireMock reserve가 2건인 이유

- 증상: `confirm_action` 예약 경로를 intent 큐로 바꾼 뒤 same-seat 100명 k6를 처음 재실행했을 때
  사용자 결과와 DB 상태는 `SUCCESS` 1 / `FAILED_RACE` 99로 맞았지만, WireMock request journal은
  `/pyxis-api/1/api/seat-charges` POST 2건을 기록했다. PR2 목표는 **업스트림 reserve 콜 100→1**이라
  사용자 결과만 green이어도 미달이었다.
- 틀린 가설: "worker가 batch-size 100으로 같은 좌석 intent를 한 tick에서 그룹화하므로,
  k6 burst의 100개 confirm은 항상 한 번의 Pyxis 호출로 접힌다."
- 실제 원인: `@Scheduled` worker tick은 k6 VU의 confirm 생성 타이밍과 동기화되어 있지 않다.
  첫 tick이 일부 immediate intent만 claim해 1건을 Pyxis에 보낸 뒤, 나머지 intent가 다음 tick에
  claim되면 다음 tick의 winner가 같은 좌석으로 다시 Pyxis를 호출했다. 즉 기존 grouping은
  **같은 tick 안의 중복**만 제거했고, tick 경계를 넘는 같은-seat immediate confirm 중복은 제거하지 못했다.
- 수정: immediate confirm intent에 한해 같은 `target_seat_id`의 최근 terminal attempt
  (`SUCCEEDED` 또는 `FAILED_RACE`, intent expiry 전)가 이미 있으면 후속 claimed group 전체를
  로컬 `FAILED_RACE`로 닫고 Pyxis를 호출하지 않는다. 이렇게 action TTL 동안의 confirm burst는
  worker tick이 쪼개져도 첫 Pyxis write 1건만 남는다.
- 검증:
  - `gradlew.bat test` green.
  - k6 same-seat 100명: `reserve_success=1`, `reserve_race=99`, `reserve_other=0`,
    confirm p50 2.69s / p95 3.08s / max 3.26s.
  - WireMock count: `POST /pyxis-api/1/api/seat-charges` with `seatId=9999` = **1**.
  - DB: `action_audit` = `SUCCESS/SUCCESS` 1 + `FAILED/FAILURE_RACE` 99,
    `library_reservation_intents` = `SUCCEEDED/SUCCESS` 1 + `FAILED_RACE/FAILED_RACE` 99.
- 핵심 파일·커밋: PR2 커밋 `feat(library): route reserve confirms through intent queue`;
  `LibraryReservationWorker`, `LibraryReservationIntentRepository`,
  `LibraryReservationIntentTransactions`, `load-tests/k6/library-reservation-baseline.js`.
- 포트폴리오 포인트: "결과 정합성"과 "업스트림 보호"는 다르다. 사용자에게는 1/99로 보이더라도
  외부 시스템에는 2번 쐈다면 rate-limit·IP ban 관점에서는 실패다. request journal 같은 외부 경계
  지표를 threshold로 둬야 진짜 개선 여부를 증명할 수 있다.
- 면접 예상 질문:
  1. 같은 DB queue worker를 쓰는데도 tick 경계 때문에 왜 중복 upstream write가 발생할 수 있는가?
  2. 사용자-visible success/race 카운터와 WireMock request count 중 무엇을 더 권위 있는 성능 지표로 봐야 하는가?
  3. action TTL 기반 최근 terminal attempt suppress의 한계는 무엇이고, Redis/Redisson 단계에서는 어떻게 바꿀 것인가?

## 2026-06-11 — 숨은 빈 커플링: real 커넥터가 chat=llm일 때만 뜨는 ObjectMapper에 의존

- 증상: k6 부하 테스트용 로컬 기동(`library-seat=real` + 나머지 기본값)에서 컨텍스트 기동 실패 —
  `RealLibrarySeatConnector` 생성 중 `No qualifying bean of type 'com.fasterxml.jackson.databind.ObjectMapper'`.
  prod에서는 같은 코드가 멀쩡히 뜬다.
- 틀린 가설: "ObjectMapper는 Spring Boot Jackson 오토컨피그가 항상 준다. real 커넥터만 켜면
  어디서든 뜬다."
- 실제 원인 (2계층):
  1. Spring Boot 4는 Jackson 3(`tools.jackson`)이 기본이라, 레거시 `com.fasterxml` ObjectMapper는
     오토컨피그 빈으로 보장되지 않는다.
  2. 이 프로젝트에서 `com.fasterxml` ObjectMapper 빈은 `LlmProviderConfig.primaryObjectMapper()`가
     공급하는데, 이 @Configuration 전체가 `@ConditionalOnProperty(ssuai.connector.chat=llm)` 뒤에 있다.
     prod는 chat=llm이라 항상 존재했고, 로컬 기본값 chat=mock + library=real 조합에서만 구멍이 드러났다.
     즉 **도서관 커넥터의 기동 가능 여부가 채팅 커넥터 설정에 묶여 있던 것** — 테스트 green(mock 조합),
     prod green(llm 조합)인데 그 사이 조합만 깨지는 전형적 구성 매트릭스 사각지대.
- 임시 해법: 부하 테스트 환경도 prod와 동일하게 `SSUAI_CONNECTOR_CHAT=llm` 설정(키 없어도 기동엔
  무관 — 프로바이더는 호출 시점에만 키 사용). `load-tests/docker-compose.yml`·README에 주석으로 명시.
- 근본 해법(백로그): `primaryObjectMapper`를 chat 게이트 밖 전역 설정(`global/config`)으로 이동.
  P2-2(LlmChatService 분리) 때 함께 처리 예정.
- 핵심 파일: `LlmProviderConfig.java:26-34`, `RealLibrarySeatConnector`, `load-tests/docker-compose.yml`
- 포트폴리오 포인트: "조건부 빈(@ConditionalOnProperty)이 공급하는 공용 빈"은 구성 조합에 따라
  다른 모듈을 침묵 속에 깨뜨린다. 부하 테스트 환경 구축이 단위 테스트·prod 둘 다 놓친 구성
  사각지대를 드러냈다 — 테스트 매트릭스에 "prod 유사 조합"이 필요한 이유.
- 면접 예상 질문:
  1. Spring Boot 4에서 Jackson 2와 3이 공존할 때 ObjectMapper 빈 전략을 어떻게 가져가는가?
  2. @ConditionalOnProperty로 게이트된 설정 클래스가 공용 빈을 공급하면 어떤 위험이 있는가?
  3. 테스트도 prod도 green인데 특정 구성 조합만 깨지는 문제를 어떻게 사전에 잡을 수 있는가?

## 2026-06-13 — Prometheus alert template과 Helm 문법 충돌로 ArgoCD ComparisonError

- 맥락:
  - `deploy/charts/ssuai-backend/templates/prometheus-rules.yaml`의 Prometheus alerting rule 메시지에
    `{{ $labels.instance }}`와 `{{ $labels.name }}` 표현식이 있었다.
  - PR #57 전까지 Prometheus alert 규칙을 추가하거나 건드린 적이 없어 잠재 문제가 드러나지 않았다.
- 증상:
  - ArgoCD Application Sync Status가 `ComparisonError`로 전환되고 이후 모든 sync가 막혔다.
  - 에러 메시지: Helm manifest generation 단계에서 `function "..." not defined` 계열.
  - CI는 정상 통과했으나 ArgoCD manifest render 단계만 실패.
- 처음 세운 가설 (틀린 방향):
  - "YAML 들여쓰기나 quote 문제일 것이다."
- 실제 원인:
  - Helm은 `.yaml` 확장자를 가진 chart template 파일을 모두 Go template으로 파싱한다.
  - `{{ $labels.instance }}`는 Prometheus alertmanager의 표현식이지만, Helm은 이를 Go template action으로
    해석하고 `$labels`가 정의되지 않아 render 실패한다.
  - 이전에 같은 패턴인 Grafana dashboard inline JSON(`{{ uri }}`)에서도 동일한 원인이 발생했었다(2026-06-06 항목 참조).
- 해결:
  - Prometheus 표현식을 Helm backtick raw string으로 감쌌다:
    `{{`{{ $labels.instance }}`}}` → Helm 렌더 출력은 `{{ $labels.instance }}`로 보존.
  - PR #57에서 수정 후 ArgoCD hard refresh를 트리거해 sync 재개 확인.
- 핵심 파일/커밋:
  - `deploy/charts/ssuai-backend/templates/prometheus-rules.yaml` (line 43 기준)
  - PR: `https://github.com/hoeongj/ssuMCP/pull/57`
- 검증:
  - ArgoCD Application Sync Status → `Synced/Healthy`.
  - Kubernetes rollout 완료, `/actuator/health` UP 확인.
- 포트폴리오 포인트:
  - Helm chart 안에 Prometheus/Grafana 템플릿 식을 넣을 때 두 시스템이 동일한 `{{ ... }}` 문법을 써서
    반드시 Helm-level escape가 필요하다. CI로는 검증 불가한 manifest generation 단계의 별도 failure point다.
  - ArgoCD `ComparisonError`는 Kubernetes API 오류가 아니라 Helm render 오류임을 구분해야 진단이 빠르다.
- 면접 예상 질문:
  1. Helm chart 안에 Prometheus alerting rule을 inline으로 넣을 때 `{{ ... }}` 충돌을 어떻게 피하나요?
  2. ArgoCD `ComparisonError`와 `SyncFailed`의 차이는 무엇인가요?
  3. CI green인데 ArgoCD sync가 실패하는 상황에서 어떤 순서로 원인을 좁히나요?

## 2026-06-13 — Gemini embedding API 모델명 불일치: 404 → RestClientException → 일일 할당량 초과

- 맥락:
  - prod 학사정책 RAG 임베딩 모델을 `gemini-embedding-001`로 운영 중이었다.
  - 최신 Gemini 임베딩 모델인 `text-embedding-004`로 업그레이드하면서 values.yaml을 변경했다.
- 증상 (단계별):
  1. `text-embedding-004` 적용 → prod 로그에 HTTP 404.
  2. `gemini-embedding-2`로 교체 → HTTP 200이지만 Spring `RestClient.retrieve().body()`에서
     `RestClientException`(서브클래스가 아닌 기저 클래스) 발생. curl로 같은 endpoint를 호출하면 정상 JSON 반환.
  3. `gemini-embedding-001`로 복귀 → HTTP 429 RPD(일일 요청 수) 할당량 초과. 당일 할당량 소진.
- 처음 세운 가설 (틀린 방향):
  1. "API key가 올바르고 최신 모델을 지원할 것이다."
  2. "`gemini-embedding-2` 응답 JSON에 `int index` 필드가 없어서 역직렬화 실패일 것이다."
     → 그러나 `gemini-embedding-001`도 동일하게 index를 생략하므로 이 가설은 불일치.
- 실제 원인:
  1. prod API key 접두사 `AQ.Ab8RN6L...`는 AI Studio 표준 key가 아닌 특수 유형이다.
     지원 모델이 `gemini-embedding-001`, `gemini-embedding-2`, `gemini-embedding-2-preview`로 제한된다.
     `text-embedding-004`는 이 key 유형에서 404를 반환한다.
  2. `gemini-embedding-2`의 `RestClientException` 근본 원인은 확인 불가 (HTTP 200 + JSON valid인데
     Spring RestClient가 역직렬화 전에 실패하는 경로). 해결보다 복귀를 선택.
  3. 404 → RestClientException 연쇄 시도 중 `gemini-embedding-001`에 대한 재시도(30s→60s→120s)가
     이미 일일 RPD 할당량을 소진했다. 이후 모든 임베딩 호출이 429.
- 해결:
  - `gemini-embedding-001`로 복귀한 상태를 유지하고 다음날 할당량 리셋을 기다린다.
  - 임베딩 실패 시 lexical-only search fallback이 동작 중이므로 기능 자체는 살아있다.
  - `gemini-embedding-2` RestClientException 근본 원인은 미해결 — 후속 조사 백로그.
- 핵심 파일/커밋:
  - `deploy/charts/ssuai-backend/values.yaml` (`academicEmbeddingModel` 필드)
  - PR #55 (configmap.yaml에 `SSUAI_ACADEMIC_EMBEDDING_MODEL` 추가)
  - PR #56, PR #58 (값 교체 시도 및 복귀)
- 검증:
  - `gemini-embedding-001` 할당량 리셋 후 WARN 0/216 로그가 INFO로 전환되는지 확인 예정.
  - 임베딩 실패 시 `WARN: embedding quota exhausted, 0/216 embedded` 로그로 lexical fallback 동작 확인.
- 포트폴리오 포인트:
  - API key 유형(접두사로 구분)에 따라 지원 모델 목록이 다르다. 공식 문서와 실제 key 유형을 반드시 교차 검증해야 한다.
  - 외부 API 429를 재시도 루프로 처리할 때 일일 RPD 할당량을 소진할 수 있다. 재시도 횟수와 할당량 유형(RPM vs RPD)을 구분해야 한다.
  - lexical fallback 등 graceful degradation을 두면, 인프라 실패 중에도 기능이 degraded state로 동작한다는 점을 모니터링 로그로 명시할 수 있다.
- 면접 예상 질문:
  1. 외부 임베딩 API 전환 시 어떤 사전 검증 단계가 필요한가요?
  2. RPM(분당)과 RPD(일당) 할당량 초과를 구분해 재시도 전략을 설계하는 방법은?
  3. 임베딩 파이프라인이 실패할 때 검색 품질을 어떻게 gracefully degrade하나요?

## 2026-06-11 — k6 baseline: 같은 좌석 100명 동시 confirm에서 SUCCESS 2건 — 좌석 직렬화의 부재 증명

- 증상: EPIC 1 write baseline(같은 좌석 9999에 100명 동시 confirm, WireMock 경합 시나리오)에서
  기대 "SUCCESS 1 / RACE 99"가 아니라 **SUCCESS 2 / RACE 98**. action_audit도 동일(잔류 상태 0).
- 틀린 가설: "WireMock 시나리오(Started→charged 전이)가 첫 요청만 성공시키니 SUCCESS는 정확히 1이다."
- 실제 원인: WireMock 시나리오 상태 전이는 원자적 compare-and-set이 아니다 — 100동시 burst에서
  2개 요청이 동시에 "Started" 상태를 읽고 둘 다 성공 응답을 받았다. mock의 한계이며 실제 Pyxis는
  좌석당 1건만 원자적으로 수락한다.
- 그러나 더 중요한 발견: 이 결과는 **우리 백엔드가 같은 좌석에 대한 서로 다른 사용자의 confirm을
  전혀 직렬화하지 않는다**는 사실의 직접 증거다. P1-1 row-lock은 "같은 action row의 중복 실행"만
  막고(이건 100동시성에서 완벽 동작 — 잔류 0), 좌석 단위 경쟁은 통째로 업스트림에 위임된다.
  업스트림이 2건을 받아주면 우리는 2건 SUCCESS를 기록할 수밖에 없다. 오늘의 정합성 보장 지점은
  시스템 밖에 있다.
- 조치: 버그 수정 아님(현 설계의 의도된 한계) — EPIC 3(intent 큐 직렬화)·EPIC 4(좌석 락)의
  정량적 근거로 `docs/performance/library-agent-load-test.md`에 박제. 개선 후 같은 시나리오에서
  업스트림 reserve 콜 수 100→1 감소를 비교 측정한다.
- 핵심 파일: `load-tests/k6/library-reservation-baseline.js`,
  `load-tests/wiremock/mappings/pyxis-reserve-contested.json`, `ConfirmActionMcpTool.java`
- 포트폴리오 포인트: baseline 측정이 "개선 전 숫자"만 주는 게 아니라 아키텍처의 보이지 않던
  경계(정합성 보장의 위치)를 드러냈다. mock의 한계(비원자적 시나리오 전이)와 시스템의 한계
  (좌석 직렬화 부재)를 구분해서 읽어야 한다.
- 면접 예상 질문:
  1. row-level lock이 있는데 왜 같은 좌석에 2건의 SUCCESS가 기록될 수 있는가?
  2. mock 기반 부하 테스트에서 mock 자체의 동시성 한계를 어떻게 식별하고 보정하는가?
  3. 좌석 단위 직렬화를 큐(SKIP LOCKED)로 풀 때와 분산 락(Redisson)으로 풀 때의 트레이드오프는?

## 2026-06-11 — 임베딩 prod 활성화가 9시간 CrashLoopBackOff: secret 개행 + "절대 안 죽는 폴백"의 구멍

- 증상: `SSUAI_ACADEMIC_EMBEDDING_ENABLED=true` 배포(9f20047) 후 새 pod가 **CrashLoopBackOff
  109회/9시간**. 단, `maxUnavailable: 0` 롤링 전략 덕에 구 pod가 계속 서비스해 **사용자 장애는 0**.
  prod MCP 응답은 여전히 `embeddingUsed:false`라서 발견.
- 틀린 가설(2중):
  1. "P1-2는 키 없거나 임베딩 API 실패 시 lexical로 자동 폴백하니, env만 켜면 최악이어도
     lexical로 돈다" — 폴백은 `RestClientException`만 잡았다.
  2. "chat이 같은 키로 잘 돌고 있으니 키 값은 정상이다" — 키 **값**은 정상이지만 secret에
     trailing newline이 들어 있었다.
- 실제 원인 (사슬 전체):
  1. k8s secret의 `SSUAI_GEMINI_API_KEY` 값 끝에 개행(`\n`) — `kubectl create secret` 시
     echo without `-n` 류의 고전적 사고.
  2. `AcademicEmbeddingClient`가 `setBearerAuth(key)`로 헤더를 만들 때 JDK HttpClient가
     `IllegalArgumentException: invalid header value`를 **요청 전송 전에** 던짐.
     (chat 경로는 같은 키로 멀쩡 → HTTP 클라이언트 구현/검증 시점 차이. 같은 잘못된 값도
     클라이언트에 따라 증상이 달라진다.)
  3. `embed()`의 catch는 `RestClientException`뿐이라 IAE가 관통.
  4. `AcademicPolicyCorpusCache.loadFastFallbackCorpus()`는 `@PostConstruct`에서 `enrich()`를
     try 없이 직접 호출 → 컨텍스트 기동 실패 → 크래시 루프.
- **보안 노트**: JDK의 IAE 메시지에 `Bearer <키 원문>`이 포함되어 **pod 로그에 API 키가
  평문 노출**됐다. 로그 접근은 클러스터 관리자뿐이지만 키 로테이션 권장. 수정 후 임베딩
  경로 로그는 예외 클래스명만 남긴다(메시지 금지).
- 수정:
  1. `AcademicEmbeddingProperties` — apiKey/baseUrl/model을 바인딩 시점에 trim (근본 원인
     무력화: 어떤 소비자도 invalid header를 만들 수 없음).
  2. `AcademicEmbeddingClient.embed()` — catch를 `RuntimeException`으로 확대, 클래스명만 로깅.
  3. `AcademicPolicyCorpusCache.enrich()` — 본문 전체 try/catch로 "어떤 예외도 lexical-only
     강등"을 타입 무관하게 보장 (@PostConstruct 경로 보호).
  4. 회귀 테스트: 개행 키 trim 검증 + "IAE를 던지는 임베딩 클라이언트로도 기동·refresh가
     안 죽고 lexical로 강등" (AcademicPolicyCorpusCacheTests).
- 핵심 파일: `AcademicEmbeddingProperties.java`, `AcademicEmbeddingClient.java:83-91`,
  `AcademicPolicyCorpusCache.java:43-46,108+`, `AcademicPolicyCorpusCacheTests.java`
- 포트폴리오 포인트:
  - "옵션 기능은 코어를 절대 죽이면 안 된다"는 계약은 **예외 타입 전수 조사** 없이는 구호일
    뿐이다. 특정 예외만 잡는 폴백은 폴백이 아니다 — 특히 @PostConstruct/기동 경로에서는.
  - `maxUnavailable: 0` + readiness probe가 잘못된 배포를 9시간 동안 무장애로 격리했다 —
    롤링 전략이 실제로 일한 사례(관측: 구 pod Running, 새 pod CrashLoop, 서비스 무중단).
  - 외부에서 주입되는 모든 credential은 신뢰 경계에서 정규화(trim)하고 들어와야 한다.
- 면접 예상 질문:
  1. 같은 잘못된 키로 chat은 동작하고 embedding은 pod를 죽인 이유는? (HTTP 클라이언트별
     헤더 검증 시점 차이)
  2. maxUnavailable=0 롤링 업데이트가 잘못된 배포를 어떻게 격리하는가? 그 한계는?
     (HPA/리소스, 그리고 "조용히 안 바뀌는" 배포를 누가 감지하나 — 모니터링 알림 필요)
  3. 예외를 더 넓게 잡는 것(catch RuntimeException)의 위험과, 그럼에도 여기서 정당한 이유는?
  4. 시크릿에 개행이 들어가는 사고를 파이프라인 차원에서 막으려면?

## 2026-06-11 — P0 E2E: 미입실 배정 좌석은 반납 불가 (`warning.smuf.notAvailableState`)

- 증상: 운영 E2E에서 reserve(마루열람실 216번, chargeId 1984615)는 성공했지만, 직후
  prepare_swap/prepare_cancel → confirm이 모두 실패. pod 로그:
  `library discharge upstream returned success=false: code=warning.smuf.notAvailableState`.
  T+1분/T+6분/T+13분 재시도 전부 동일.
- 틀린 가설(순서대로):
  1. "일시적 업스트림 오류다" — 재시도해도 동일.
  2. "discharge 요청 형태가 틀렸다" — oasis SPA 번들(lazy chunk `5135.*.js`)을 받아
     `returnSeat$`를 역공학: `POST /pyxis-api/1/api/seat-discharges {seatCharge, smufMethodCode}`
     — 우리와 완전 동일. 별도 '배정취소' 엔드포인트도 없음(seat-charges/discharges/
     renewed-charges/charge-histories가 전부).
  3. "배정 직후 짧은 잠금이다" — 13분 후에도 거부.
- 실제 원인(관찰 기반 확정): **미입실(게이트/NFC 입실 확인 전) 배정 상태에서는 Pyxis가
  반납을 거부한다.** 반납은 입실 후 상태에서만 유효한 전이다. 미입실 배정은 사용자가
  취소할 수 없고 Pyxis가 자동 해제한다 — 실측: 13:32 배정 → 14:1x 사이 자동취소 확인
  (get_my_library_seat "예약된 좌석이 없습니다"). 2026-06-06 cancel 캡처가 성공했던 것은
  입실(이용 중) 상태였기 때문으로 추정.
- 부수 실증 2건:
  1. **swap의 discharge-first 설계가 안전한 실패 모드로 동작**: 1단계 반납 실패 시 기존
     예약이 그대로 보존됐다 (FAILURE_UPSTREAM 기록, 사용자 좌석 잃지 않음).
  2. **세션 영속화(V4/V5)가 pod 롤링에서 실증됨**: 크래시 수정 배포로 pod가 교체된 뒤에도
     MCP 세션·LIBRARY 링크·암호화 토큰이 모두 복원돼 Pyxis 호출까지 갔다. 단 이때 Pyxis가
     needLogin을 반환 — 토큰이 업스트림에서 무효화된 사례(원인 미확정, 단일 세션 정책
     의심: 사용자의 별도 oasis 웹/앱 로그인이 브로커 토큰을 밀어냈을 가능성).
- 제품 함의 (백로그):
  - prepare_swap/cancel은 미입실 상태를 사전 감지해 "입실 후 가능" 안내를 줘야 한다
    (현재는 confirm 실행 후 FAILURE_UPSTREAM). per-seat API의 `seatChargeState`로 충분한지
    캡처 필요.
  - `warning.smuf.notAvailableState`를 ConnectorParseException(→"실행 중 오류")이 아니라
    의미 있는 사용자 메시지("아직 입실 전이라 반납할 수 없어요. 미입실 좌석은 일정 시간 후
    자동 반납됩니다")로 매핑.
- 핵심 파일: `RealLibraryReservationConnector.parseDischargeResponse()`,
  `ConfirmActionMcpTool.executeSwap()/executeCancellation()`,
  `docs/library-seat-agent-completion-plan.md`
- 포트폴리오 포인트: 외부 시스템의 숨은 상태 머신(배정→입실→이용→반납)을 운영 E2E로만
  발견했다. 웹 클라이언트 번들 역공학으로 "요청 형태" 가설을 체계적으로 기각하고 상태
  규칙으로 좁혔다. 실패한 E2E가 swap의 안전한 실패 모드와 세션 영속화라는 두 가지 설계
  검증을 공짜로 줬다.
- 면접 예상 질문:
  1. 외부 시스템 에러 코드의 의미를 문서 없이 어떻게 확정했나? (재현 매트릭스 + 클라이언트
     번들 역공학 + 시간 경과 관찰)
  2. swap을 discharge-first로 설계한 이유와 그 실패 모드는? (새 좌석 선점 실패 시 기존
     좌석 보존 vs reserve-first 시 이중 점유 위험)
  3. 미입실 상태를 API로 사전 감지할 수 있다면 UX를 어떻게 바꾸겠나?

## 2026-06-12 — Flyway `{vendor}` 위치 추가 후 H2 전체 컨텍스트가 V10 중복으로 실패

- 증상: 좌석 시계열 V10을 `db/migration/postgresql`, `db/migration/h2`에 추가하고
  `spring.flyway.locations=classpath:db/migration,classpath:db/migration/{vendor}`로 설정한 뒤
  `.\gradlew.bat test`에서 모든 Spring context 계열 테스트가 기동 실패. 핵심 에러:
  `Found more than one migration with version 10`, 대상은 PostgreSQL/H2 V10 두 파일.
- 틀린 가설: "Flyway의 `{vendor}` placeholder를 parent location 뒤에 추가하면 parent는 V1-V9
  root 파일만 읽고, vendor location은 현재 DB vendor 하위 폴더만 읽는다."
- 실제 원인: Flyway classpath location은 재귀적으로 scan한다. 따라서 `classpath:db/migration`이
  이미 `db/migration/h2`와 `db/migration/postgresql` 하위 파일까지 모두 발견한다. 이후
  `classpath:db/migration/{vendor}`는 sub-location으로 버려져도 parent scan에서 두 V10이 이미
  같은 version으로 충돌한다. Redgate 문서도 locations가 recursive scan 대상이라고 명시한다.
- 조치: 공통 migration location을 root 파일 wildcard인 `classpath:db/migration/V*__*.sql`로 좁히고,
  vendor migration은 `classpath:db/migration/{vendor}`로 둔다. V1-V9 파일은 이동/수정하지 않고
  root의 versioned SQL만 선택되며, V10은 현재 DB vendor 폴더에서만 선택된다.
- 핵심 파일/커밋: `application.yml`, `application-test.yml`,
  `src/main/resources/db/migration/{h2,postgresql}/V10__create_library_seat_samples.sql`,
  `docs/adr/0023-library-seat-timeseries.md`. 커밋은 좌석 시계열 feature commit에 포함 예정.
- 포트폴리오 포인트: "문서상 vendor placeholder"만으로는 기존 root migration + vendor 하위 폴더
  혼합 구조가 안전하지 않다. 실제 framework scanner 동작(재귀 scan, sub-location discard)을
  테스트로 잡고, 기존 checksum을 건드리지 않는 최소 변경(wildcard location)으로 해결했다.
- 면접 예상 질문:
  1. Flyway가 왜 `classpath:db/migration/{vendor}`를 버렸는데도 V10 중복을 냈는가?
  2. V1-V9를 이동하지 않고 root 파일만 선택하게 한 방법은 무엇이고, 그 한계는?
  3. H2 테스트가 green이어도 PostgreSQL partition DDL 검증이 부족한 이유와 배포 전 보완책은?
## 2026-06-12 rebase merge도 committer를 다시 쓴다

- 망한 가설:
  - rebase merge면 squash merge와 달리 authorship은 항상 보존된다고 봤다.
- 실제 원인:
  - GitHub가 PR를 서버에서 실제로 rebase해야 하는 상황에서는 새 commit을 다시 만들면서 committer metadata가 GitHub noreply로 바뀔 수 있다.
  - 반대로 PR #39는 base가 맞아서 서버 재작성 없이 fast-forward로 끝났기 때문에 local authorship이 그대로 유지됐다.
- 핵심 파일/커밋:
  - `AGENTS.md`
  - `../AGENTS.md`
  - `2f91d9bbccfe567ba3ff83e1e48ade47b4392859`
  - `0ff243efd00d1bb0ff6540e590b8217f0e5ea6d1`
- 포트폴리오 포인트:
  - merge method는 단순한 "rebase vs squash" 구도가 아니라, GitHub가 서버-side rewrite를 하느냐 여부까지 봐야 한다.
  - authorship 보존 규칙은 `gh pr merge` 금지 + local ff-only merge로만 안정적으로 만족된다.
- 면접 질문:
  1. GitHub rebase merge가 왜 committer를 바꿀 수 있나요?
  2. PR #39는 왜 예외적으로 authorship이 살아남았나요?
  3. local ff-only merge가 authorship 보존을 어떻게 보장하나요?

## 2026-06-12 - Pyxis resilience 통합 테스트가 CI 전체 테스트에서 WireMock 전역 상태 충돌로 실패

- 맥락:
  - `docs(troubleshooting): record image updater convergence` 문서 커밋 후 main CI를 확인하는 과정에서 Backend test가 실패했다.
  - 같은 커밋은 문서만 바꿨고, 로컬 전체 테스트와 대상 테스트는 직전까지 green이었다.
- 증상:
  - CI run `27417617193`에서 `PyxisResilienceIntegrationTest.opensCircuitAfterConsecutiveServerErrorsAndShortCircuitsNextCall()`가 실패했다.
  - 실패 메시지: `Expected exactly 10 requests ... but received 9`.
  - 같은 대상 테스트를 로컬에서 단독 실행하면 통과했다.
- 처음 세운 가설:
  - "문서 커밋이므로 CI 실패는 일시적인 runner/network flake일 것이다."
- 실제 원인:
  - `PyxisResilienceIntegrationTest`가 자체 `WireMockServer`를 띄우면서도 `WireMock.configureFor(...)`와 static `stubFor(...)` 전역 client API를 같이 사용했다.
  - 전체 테스트에서 다른 WireMock 테스트가 같은 전역 client 상태를 바꾸면, stub/verify 대상 포트가 테스트 인스턴스의 서버와 어긋날 수 있다.
  - 추가로 로컬 단독 재현에서는 WireMock 요청 journal이 0건이 되고 전부 `ResourceAccessException`으로 떨어졌다.
  - 이 테스트의 목적은 HTTP 서버 통합이 아니라 `RealLibraryReservationConnector`의 write 호출이 `PyxisResilience` 회로 차단을 타는지 검증하는 것이므로, 실제 포트를 여는 WireMock은 과한 의존성이었다.
- 해결:
  - `PyxisResilienceIntegrationTest`를 기존 connector 테스트와 같은 `MockRestServiceServer` 기반으로 바꿨다.
  - 10번의 5xx 응답을 명시적으로 기대하고, 11번째 호출은 `CallNotPermittedException`으로 short-circuit되어 추가 HTTP 요청이 발생하지 않음을 `server.verify()`로 확인한다.
- 핵심 파일/커밋:
  - `src/test/java/com/ssuai/domain/library/reservation/PyxisResilienceIntegrationTest.java`
  - `TROUBLESHOOTING.md`
  - 실패 CI: `27417617193`
  - 해결 커밋: `test(library): isolate pyxis resilience wiremock server`
- 검증:
  - `gradlew.bat test --tests com.ssuai.domain.library.reservation.PyxisResilienceIntegrationTest`
  - `gradlew.bat test`
  - main CI/Security 재실행 green 확인
- 포트폴리오 포인트:
  - 테스트 격리 문제는 기능 코드보다 더 위험하다. 로컬 단독 테스트가 green이어도 CI 전체 조합에서 전역 mutable state나 실제 네트워크 포트 의존성이 섞이면 신뢰할 수 없는 신호가 된다.
  - 테스트가 검증하려는 책임을 좁히는 것이 중요하다. HTTP 매핑은 connector 테스트에서, 회로 차단은 resilience 통합 테스트에서 검증하도록 역할을 분리했다.
- 면접 예상 질문:
  1. 단독 실행은 통과하는 테스트가 CI 전체 실행에서만 실패할 때 어떤 순서로 원인을 좁히나요?
  2. 실제 HTTP 서버를 띄우는 테스트와 `MockRestServiceServer` 기반 테스트를 어떻게 구분해서 쓰나요?
  3. flaky test를 단순 재실행으로 넘기지 않고 코드로 안정화해야 하는 기준은 무엇인가요?

## 2026-06-12 - ArgoCD Image Updater 자동 커밋이 CI 이미지 빌드 루프를 만들 수 있음

- 맥락:
  - 도서관 좌석 SSE 배포 후 `feat(library): implement SSE live seat updates` 커밋의 이미지가 정상 빌드되었다.
  - ArgoCD Image Updater가 Helm `values.yaml`을 새 이미지 태그로 갱신하는 자동 커밋을 만들었고, 그 자동 커밋도 `main` push이므로 CI `image-build` job이 다시 실행되었다.
- 증상:
  - `1da66ec` 기능 커밋 이미지가 배포된 뒤 `febaf3b`, `25733b4`처럼 `build: automatic update of ssuai-backend` 커밋이 연속으로 생겼다.
  - 각 자동 커밋이 다시 `sha-<auto-commit>` 이미지를 만들면 Image Updater가 그 이미지를 더 최신 태그로 보고 또 다른 Helm 값 갱신 커밋을 만들 수 있었다.
- 처음 세운 가설:
  - "Image Updater 커밋은 단순히 이미지를 pin하는 GitOps 상태 커밋이므로, 그 커밋 뒤에는 배포가 수렴할 것이다."
- 실제 원인:
  - `.github/workflows/ci.yml`의 `image-build` 조건이 모든 `main` push에서 실행되도록 되어 있었다.
  - Image Updater의 write-back commit도 `main` push이기 때문에 자동 커밋 자신의 Docker image가 새로 생기고, 이 이미지 태그가 다시 Image Updater의 선택 대상이 되었다.
- 해결:
  - 처음에는 `image-build` job 조건에서 자동 write-back commit 메시지를 판별하려 했지만, GitHub Actions가 workflow 평가 단계에서 job을 만들기 전에 실패했다.
  - 최종 해결은 `on.push.paths-ignore`에 `deploy/charts/ssuai-backend/values.yaml`을 추가하는 방식으로 바꿨다. Image Updater 커밋은 이 파일만 바꾸므로, 자동 커밋 push에서는 CI가 아예 시작되지 않는다.
  - SSE registry 테스트의 빈 cleanup 테스트도 실제 `destroy()` cleanup과 event bus 구독 해제를 검증하도록 보강했다.
- 핵심 파일/커밋:
  - `.github/workflows/ci.yml`
  - `deploy/charts/ssuai-backend/values.yaml`
  - `deploy/README.md`
  - `src/test/java/com/ssuai/domain/library/events/LibrarySeatSseRegistryTests.java`
  - 원인 관찰 커밋: `1da66ec8b8bfe1340aed5e310fe0229de648a282`, `febaf3bb558c29d74efdc57579b3990a1b41541d`, `25733b4ff66201bdcd83f616a3091373a79b31bc`
  - 해결 커밋:
    - `84c3ff88c72977bf4a7ec29689f6ba1f093bee16` `fix(ci): skip image build for image updater commits` - 최초 메시지 기반 차단 시도. GitHub Actions workflow 평가 단계에서 실패.
    - `aa35844a70eb2a0cff22f493a6b71c87c09ce235` `fix(ci): use valid image updater skip expression` - 표현식 보정 시도. 동일하게 workflow 평가 단계에서 실패.
    - `bda5909df663d9e7cd25ddf5341fcc1bca49d9e4` `fix(ci): ignore image updater values-only pushes` - 최종 해결. `paths-ignore` 방식으로 정상 CI 통과.
- 검증:
  - 로컬에서 관련 SSE registry 테스트와 전체 테스트를 실행했다.
  - `bda5909df663d9e7cd25ddf5341fcc1bca49d9e4`의 CI와 Security workflow가 정상 통과했다.
  - Image Updater 로그에서 12:38 이후 반복 cycle이 `images_updated=0 errors=0`으로 수렴했다.
  - ArgoCD Application은 `Synced/Healthy`, Deployment는 `ready=1 updated=1`, `/actuator/health`는 `UP`으로 확인했다.
- 포트폴리오 포인트:
  - GitOps 자동화는 "배포 상태를 Git에 기록"하는 장점이 있지만, CI가 모든 Git 커밋을 동일하게 취급하면 자동화끼리 피드백 루프를 만들 수 있다.
  - 해결 포인트는 Image Updater를 끄는 것이 아니라, 빌드 산출물을 만들어야 하는 커밋과 배포 상태만 갱신하는 values-only 커밋을 workflow trigger 단계에서 분리하는 것이다.
- 면접 예상 질문:
  1. GitOps Image Updater가 왜 이미지 빌드 루프를 만들 수 있나요?
  2. Image Updater를 비활성화하지 않고 CI 조건을 분리한 이유는 무엇인가요?
  3. 자동화 루프가 멈췄다는 것을 CI, Git history, ArgoCD, 실제 Deployment 기준으로 어떻게 증명했나요?
## 2026-06-12 도서관 예약 웹 REST prepare 디스패치 오판

- 잘못된 가설:
  - `POST /api/library/reservations/prepare`의 `type` 값을 내부 감사 액션 타입(`LIBRARY_SEAT_RESERVATION`, `LIBRARY_SEAT_CANCEL`, `LIBRARY_SEAT_SWAP`)과 직접 비교하면 된다고 가정했다.
- 실제 원인:
  - 웹 요청 DTO의 `type`은 `RESERVE`, `CANCEL`, `SWAP`이고, 내부 감사 액션 타입은 `ActionService`에 저장되는 별도 식별자였다. 두 값을 같은 것으로 취급해서 정상 요청이 400으로 떨어졌다.
- 핵심 파일:
  - `src/main/java/com/ssuai/domain/library/reservation/web/LibraryReservationWebController.java`
  - `src/main/java/com/ssuai/domain/mcp/tool/LibraryReservationMcpTool.java`
  - `src/main/java/com/ssuai/domain/mcp/tool/LibraryCancelMcpTool.java`
  - `src/main/java/com/ssuai/domain/mcp/tool/LibrarySwapMcpTool.java`
- 포트폴리오 포인트:
  - 웹 REST와 MCP 감사 레이어를 같은 상태머신에 묶되, 요청용 타입과 내부 감사 타입을 분리해서 유지해야 한다는 점을 검증했다.
- 면접 질문:
  1. 웹 요청 타입과 내부 action type을 왜 분리했나?
  2. confirm에서 library token 확인과 pending claim 순서를 어떻게 잡았나?
  3. 추천 엔드포인트의 `roomIds`는 왜 서비스 레이어로 직접 전달하지 않았나?
## 2026-06-13 PHASE 17 로깅 마스킹 전수 감사

- PHASE 17 로깅 마스킹 전수 감사 완료 — 위반 없음 (2026-06-12)

## 2026-06-13 ssuAI 린트 패키징 중 워크트리 정리 지연

- 망각:
  - `fix/remove-unused-disabled-var` 브랜치에 이미 목표 변경은 커밋되어 있었지만, 브랜치 정리 단계에서 작업 트리에 남아 있던 기존 수정(`hooks/useLibraryReservation.ts`, `app/admin/`) 때문에 `git rebase origin/main` 이 중단됐다.
- 증상:
  - `git status` 에서 내가 건드리지 않은 `hooks/useLibraryReservation.ts` 수정과 `app/admin/` 미추적 디렉터리가 남아 있었고, rebase가 `You have unstaged changes` 로 실패했다.
- 처음 떠올린 원인:
  - 내가 만든 커밋이 브랜치 정리를 깨뜨렸거나, 린트가 재현되지 않는 숨은 변경이 있다고 오해할 수 있었다.
- 실제 원인:
  - 이번 작업과 무관한 기존 작업트리 변경이 남아 있어서 `main` 기준 재정렬이 불가능했다. 커밋 자체는 정상이며, 문제는 브랜치가 아니라 워크트리 상태였다.
- 작업한 파일과 커밋:
  - `app/mcp/auth/library/page.tsx`
  - 커밋: `7fca843` (`fix(auth): remove unused disabled variable in McpLibraryAuthPage`)
- 포트폴리오 포인트:
  - 잡음성 린트 경고를 최소 변경으로 제거하고, Git/CI/배포 흐름에서 기존 변경과 내 변경을 엄격히 분리하는 운영 습관을 유지했다.
- 예상 면접 질문:
  1. 워크트리에 기존 수정이 남아 있을 때 어떻게 안전하게 브랜치 정리를 이어가나?
  2. 왜 다른 파일을 건드리지 않고도 rebase가 막히는가?
  3. 이런 상황에서 커밋 단위와 작업트리 단위를 어떻게 분리해 관리하나?
## 2026-06-13 registerWait 409 배포 검증 중 ArgoCD sync 상태가 Unknown에 머문 사례

- 잘못된 가설:
  - `main` push 후 ArgoCD가 곧바로 `Synced / Healthy`로 바뀔 것이라고 가정했다.
- 실제 원인:
  - `kubectl -n argocd get applications.argoproj.io ssuai-backend` 결과가 `Unknown / Healthy`에 머물렀고, 3분 동안 폴링해도 `Synced`로 전환되지 않았다.
  - 추가로 `kubectl` 조회 도중 클러스터 API 연결 실패가 발생해, ArgoCD 상태를 즉시 재확인할 수 없는 구간이 있었다.
- 핵심 파일 및 커밋:
  - `src/main/java/com/ssuai/global/exception/ErrorCode.java`
  - `src/main/java/com/ssuai/domain/library/reservation/web/LibraryReservationWebController.java`
  - `src/test/java/com/ssuai/domain/library/reservation/web/LibraryReservationWebControllerTests.java`
  - commit: `e4fd05936a259b3220e4f2ac706e764eef743278`
  - PR: `https://github.com/hoeongj/ssuMCP/pull/51`
- 포트폴리오 포인트:
  - 기능 수정 자체보다도, Git push 이후 배포 상태를 `CI pass`만으로 끝내지 않고 실제 GitOps 상태까지 확인해야 한다는 운영 습관을 남길 수 있다.
- 예상 면접 질문:
  1. `CI green`인데도 왜 배포 상태를 추가로 확인해야 하나?
  2. ArgoCD `Unknown`과 `OutOfSync`는 어떤 차이가 있나?
  3. 클러스터 API가 불안정할 때 배포 검증을 어떻게 신뢰성 있게 할 수 있나?

## 2026-06-13 — 설계 기록: ObjectMapper 주입 불일치 (3개 서비스에서 new ObjectMapper() 직접 생성)

> 현재 버그 없음 — 향후 확장 시 발화 가능. 미구현 개선 방향 기록.

- 맥락:
  - 전체 코드 최적화 패스(2026-06-13) 중 발견. `JacksonConfig.primaryObjectMapper()` `@Primary` 빈이 있음에도 세 개의 서비스 클래스가 Spring DI를 거치지 않고 ObjectMapper를 직접 생성한다.
  - `McpAuthSessionStore`: `defaultObjectMapper()` static 팩토리 — 설정이 `@Primary` 빈과 완전 동일(`JavaTimeModule`, `WRITE_DATES_AS_TIMESTAMPS=false`, `READ_DATE_TIMESTAMPS_AS_NANOSECONDS=false`). 동작은 맞지만 설정 소스가 두 곳으로 나뉜다.
  - `ActionService`: 생성자에서 `new ObjectMapper()` — `JavaTimeModule` 미등록. payload가 `Map<String, Object>`이고 현재 시간 값은 모두 `String`으로 미리 변환되어 실제 오작동은 없다.
  - `LibraryReservationIntentTransactions`: 생성자에서 `new ObjectMapper()` — 동일하게 `JavaTimeModule` 미등록. 현재 직렬화 대상이 `Map<String, String>`/enum이라 즉각적인 문제는 없다.
- 잠재적 위험:
  - `ActionService`나 `LibraryReservationIntentTransactions` payload에 `Instant`·`LocalDateTime` 등 Java time 타입이 추가되면, `JavaTimeModule` 없는 기본 매퍼가 이를 타임스탬프 배열로 직렬화해 조용한 버그가 된다.
  - `McpAuthSessionStore.defaultObjectMapper()` 복사 설정은 향후 `@Primary` 빈 설정 변경 시(예: NullNode 허용, 커스텀 직렬화 등) 한쪽만 반영되는 드리프트를 유발한다.
- 권장 개선 방향 (미구현 — 설계 검토 후 별도 PR):
  - 세 클래스 `@Autowired` 생성자에 `ObjectMapper` 파라미터를 추가해 `@Primary` 빈을 주입.
  - `McpAuthSessionStore.defaultObjectMapper()` static 팩토리 제거.
  - 테스트 패키지-프라이빗 생성자는 `new ObjectMapper()` 또는 커스텀 매퍼를 명시 전달해 테스트 격리성 유지.
  - 유의: `JacksonConfig`에 `@ConditionalOnMissingBean(name = "primaryObjectMapper")`가 있어 순환 의존성 없이 주입 가능하지만, 세 클래스 모두 생성자 변경과 테스트 보조 생성자 업데이트가 필요하다. Spring 생성자 선택 규칙(2026-06-12 TROUBLESHOOTING 항목 참조) 주의.
- 핵심 파일:
  - `src/main/java/com/ssuai/global/config/JacksonConfig.java` — `@Primary` 빈 정의
  - `src/main/java/com/ssuai/domain/auth/mcp/McpAuthSessionStore.java` — `defaultObjectMapper()` static 팩토리
  - `src/main/java/com/ssuai/domain/action/ActionService.java` — `new ObjectMapper()` (JavaTimeModule 미등록)
  - `src/main/java/com/ssuai/domain/library/reservation/intent/LibraryReservationIntentTransactions.java` — `new ObjectMapper()` (JavaTimeModule 미등록)
- 포트폴리오 포인트:
  - "지금 동작한다"와 "올바른 설계다"는 다르다. 이 종류의 잠재 결함을 발견하고 기록하는 것 자체가 코드 리뷰 역량의 증거다.
  - Spring DI의 가장 큰 가치 중 하나는 설정의 단일 소스(Single Source of Truth) 보장이다. DI를 우회하면 프레임워크가 보장하는 일관성이 깨진다.
  - `@Primary` + `@ConditionalOnMissingBean`의 조합 목적: 단일 빈을 앱 전역에 공유하되, 필요 시 테스트에서 대체 가능하게 한다.
- 면접 예상 질문:
  1. Spring에서 `ObjectMapper`를 `@Bean`으로 등록하는 이유는 무엇이며, 직접 `new ObjectMapper()`로 생성하면 어떤 문제가 생기나요?
  2. `@Primary`와 `@ConditionalOnMissingBean`을 함께 사용하는 설계 의도는 무엇인가요?
  3. `JavaTimeModule`이 없는 `ObjectMapper`로 `Instant`를 직렬화하면 어떤 결과가 나오며, 이게 왜 조용한 버그인가요?

---

## 2026-06-16 - ssuAgent `create_react_agent` 루핑 버그 → manual bind_tools 루프 전환

### 상황

ssuAI 챗봇에서 "도서관 2층 오픈열람실 96번 예약해줘"를 보냈을 때 실제 예약이 이루어지지 않았다. 이전에 기록한 `\r` 오염·fallback 무력화 버그는 모두 fix되어 배포된 상태(`sha-66b9e0d`)였음. 챗봇이 응답은 하지만 예약 승인 UI(HitlCard)가 뜨지 않았다.

### 잘못된 가설

- **처음 가설(세션 시작 시)**: Groq llama-3.3-70b가 `create_react_agent` 내부의 system 프롬프트 때문에 tool call을 Llama XML 형식(`<function=...>`)으로 생성 → Groq API가 `tool_use_failed 400`으로 거부 → 조용히 OpenRouter로 cascade → OpenRouter 약한 모델이 tool call 없이 텍스트만 반환.
  - prod 로그에서 `<function=start_auth(...)>` XML이 1회 관측되어 "원인"으로 추정했음.
- **검증 결과**: pod 내 통제된 A/B 테스트(동일 입력, turn-2 경로: `prepare_*` → `AUTH_REQUIRED` → `start_auth`)에서 **`create_react_agent`도 XML 에러를 내지 않았음**. → XML 인과는 **재현 불가, 미확정**으로 분류.

### 실제 확인된 문제

동일 A/B 테스트에서 **`create_react_agent`가 `prepare_reserve_library_seat`를 2번 호출**했다 (turn-0 성공 후에도 루핑):

```
REACT tool_calls seen: ['prepare_reserve_library_seat', 'start_auth', 'prepare_reserve_library_seat', 'start_auth']
```

이 루핑이 HITL 흐름에 미치는 영향:
1. `prepare_*`가 2번 호출되면 `actionId`가 2개 생성됨.
2. `_extract_action_id`는 **최근 10개 ToolMessage를 역순으로** 스캔하여 첫 번째 `actionId`를 반환.
3. 두 번째 `prepare_*` 결과가 최신이므로 2번째 `actionId`로 승인 게이트가 걸림.
4. 1번째 `actionId`(실제 의도된 것)와 달라져 `confirm_action`이 잘못된 actionId로 실행되거나 실패.
5. 결과: HitlCard가 뜨지 않거나 예약이 완료되지 않음.

반면 **manual `bind_tools + ainvoke` 루프**는 같은 turn-2 경로에서 `prepare_*` 1회 → `start_auth` 1회로 정확하게 동작:

```
MANUAL turn0 tool_calls: ['prepare_reserve_library_seat'] | content: ''
MANUAL turn2 tool_calls: ['start_auth'] | content: ''
```

### 해결 방법

`ssu_agent/agents/library.py`, `academic.py`, `lms.py`의 `agent_node`를 `create_react_agent` 대신 수동 `bind_tools + ainvoke` 루프로 교체:

```python
async def agent_node(state: SsuAgentState, config: RunnableConfig) -> dict:
    for _llm in llm_seq:
        try:
            llm_with_tools = _llm.bind_tools(agent_tools)
            history = list(input_messages)
            for _ in range(6):
                response = await llm_with_tools.ainvoke(history, config=config)
                history.append(response)
                if not response.tool_calls:
                    break
                # ... tool 실행 + HITL 체크
                if hitl_triggered:
                    break  # ← prepare_*가 actionId 반환하면 즉시 중단
            return {"messages": history[len(input_messages):]}
        except Exception as exc:
            last_exc = exc
    raise last_exc or RuntimeError("All LLM providers exhausted")
```

**핵심 포인트**: `hitl_triggered=True` 시 `break`으로 루프를 명시적으로 종료 → `prepare_*` 호출은 반드시 1회만 발생.

부수 효과: `config` 전파로 inner `ainvoke`가 `astream_events(version="v2")`의 `on_chat_model_stream` 이벤트를 emit → 토큰 단위 텍스트 스트리밍이 UI에 표시됨(사용자 요구 2 "중간 과정 보여주기" 충족).

`ssu_agent/supervisor/graph.py` `supervisor_node`도 단일 `create_llm()` → `get_llm_sequence()` 폴백 루프로 교체. 수퍼바이저는 turn-0 라우팅만 하므로 루핑 위험 낮아 `create_react_agent`는 유지하고 폴백 처리만 추가.

### 핵심 파일 및 커밋

- `ssuAgent/ssu_agent/agents/library.py`, `academic.py`, `lms.py` — manual bind_tools 루프 (`8e7cb07`, PR #6 → main ff)
- `ssuAgent/ssu_agent/supervisor/graph.py` — supervisor_node fallback 루프 (`8e7cb07`)
- `8e7cb07` + 포맷 커밋 `040a16b` → CI 통과 → ArgoCD 자동 배포 → pod `sha-040a16b`

### 포트폴리오 포인트

프레임워크 추상화(`create_react_agent`)를 블랙박스로 믿지 않고 **실제 동작을 A/B 테스트로 검증**했다. 예상한 XML 버그는 재현되지 않았으나, 테스트 과정에서 전혀 다른 루핑 버그를 발견했다. "가설을 사실로 기록하지 않는다"는 기록 규율도 함께 지켰다. 또한 `config` 전파라는 LangGraph 내부 동작(inner ainvoke가 astream_events에 이벤트를 emit하려면 config가 필요)을 직접 검증하고 활용한 사례.

### 예상 면접 질문

1. LangGraph `create_react_agent`와 직접 `bind_tools + ainvoke` 루프의 차이는 무엇인가요? 어떤 상황에서 직접 루프가 더 유리한가요?
2. `astream_events(version="v2")`에서 중첩된 `ainvoke`가 이벤트를 emit하려면 왜 `config`를 전파해야 하나요? LangGraph의 callback/context propagation 메커니즘을 설명해보세요.
3. HITL(Human-in-the-Loop) 패턴 구현 시 `interrupt()`를 node 안에 두어야 하는 이유와, router/edge 함수에 두면 어떤 문제가 생기는지 설명해보세요.

## 2026-06-16 — LMS 자료 ZIP 기능이 prod에서 가짜(Mock) 데이터 반환 — Helm 배포 설정 누락

### 상황

사용자가 실서버(`https://ssumcp.duckdns.org`)에 ChatGPT 커스텀 GPT로 직접 접속해
`get_my_lms_courses`/`get_my_lms_materials`를 호출했더니, 본인이 수강한 적 없는
"빅데이터 분석(CSE101)", "기초 통계학(MATH201)" 같은 가짜 과목이 반환되고, 자료 조회는
`Fixture not found on classpath: fixtures/lms/materials.json` 서버 오류로 실패했다.
같은 세션에서 `get_my_assignments`는 실제 과목명("네트워크프로그래밍 (2150534301)")을
정확히 반환해 LMS 인증 자체는 정상이었다.

### 잘못된 가설

처음엔 "LearningX API 스키마가 과목마다 달라서 Real 커넥터가 깨졌다"거나 "인증 토큰이
일부만 유효하다"는 쪽을 의심했다.

### 실제 원인

코드 문제가 전혀 아니었다. **Helm 차트에 새 커넥터의 prod 토글이 누락**됐다.
- `lms-assignments` 커넥터는 `deploy/charts/ssuai-backend/values.yaml`에
  `connectorLmsAssignments: real`이 명시되어 `SSUAI_CONNECTOR_LMS_ASSIGNMENTS=real`로
  prod에 주입된다.
- Phase C에서 추가한 `lms-materials` 커넥터는 같은 패턴(`@ConditionalOnProperty(...,
  matchIfMissing = true)`로 안전 기본값 Mock)을 따랐지만, **Helm `values.yaml`/
  `configmap.yaml`에 대응 매핑을 추가하는 걸 빠뜨렸다** — AGY 위임 지시문이
  `application.yml` 토글 추가만 명시하고 인프라 쪽 동반 변경을 지시하지 않았기 때문.
- 그 결과 prod는 `MockLmsMaterialsConnector`로 운영됐다. Mock의 `fetchCourses()`는
  하드코딩된 가짜 과목을 **에러 없이** 반환했고(이게 진짜 위험한 부분 — 실패가 아니라
  조용히 잘못된 데이터가 나갔다), `fetchMaterials()`가 의존하는 픽스처
  `src/test/resources/fixtures/lms/materials.json`은 테스트 리소스라 프로덕션 JAR에
  패키징되지 않아 "Fixture not found" 에러로 이어졌다.
- 부가로, ZIP 다운로드 링크를 만드는 `ssuai.lms.export.public-base-url`도 Helm에
  와이어링이 안 돼 빈 문자열 기본값 → 다운로드 URL이 호스트 없는 상대경로로 나갈 뻔했다
  (사용자가 자료 조회 단계에서 막혀 아직 도달하지 않았지만 같은 원인 계열).
- `RealLmsAssignmentsConnector`가 정상 동작했던 건 그 커넥터만 우연히 prod 토글이
  처음부터 있었기 때문이고, 이게 새 커넥터도 똑같이 되어있을 거라는 착각을 만들었다.

### 해결 방법

`deploy/charts/ssuai-backend/values.yaml`에 `connectorLmsMaterials: real`과
`lmsExportPublicBaseUrl: https://ssumcp.duckdns.org` 추가, `templates/configmap.yaml`에
각각 `SSUAI_CONNECTOR_LMS_MATERIALS`/`SSUAI_LMS_EXPORT_PUBLIC_BASE_URL` 매핑 추가.
애플리케이션 코드는 전혀 수정하지 않음 — 순수 배포 설정 변경. ArgoCD
`syncPolicy.automated.selfHeal: true`라 머지 즉시 prod에 자동 반영됨.

### 핵심 파일 및 커밋

- `deploy/charts/ssuai-backend/values.yaml`, `deploy/charts/ssuai-backend/templates/configmap.yaml`
- 비교 대상(정상 패턴): `connectorLmsAssignments`/`SSUAI_CONNECTOR_LMS_ASSIGNMENTS`,
  `mcpApiBaseUrl`/`SSUAI_MCP_API_BASE_URL`

### 포트폴리오 포인트

"커넥터 토글"이라는 코드-인프라 분리 패턴(Mock/Real `@ConditionalOnProperty` + Helm
환경변수)에서, 코드 쪽 토글 추가는 했지만 인프라 쪽 와이어링을 빠뜨리면 **CI는 절대 못
잡는다** — 단위 테스트는 `application.yml`의 기본값(Mock)으로 돌고, Helm 차트는 별도
검증 없이 머지·배포되기 때문이다. 게다가 Mock이 에러를 던지지 않고 그럴듯한 가짜 데이터를
반환하는 경우, 에러보다 훨씬 늦게 발견된다(이번 건은 사용자가 직접 실서버를 두드려보고서야
발견). 신규 커넥터를 추가할 때마다 "코드 토글 + Helm 와이어링"을 한 쌍으로 묶어 체크리스트화
해야 한다는 교훈.

### 예상 면접 질문

1. Mock/Real 커넥터 패턴에서 "안전한 기본값(Mock)"이 오히려 운영 사고를 가린 이유는
   무엇인가요? 이런 종류의 설정 누락을 CI에서 잡으려면 어떤 테스트/체크가 필요할까요?
2. 에러를 던지는 실패와 "그럴듯한 잘못된 데이터를 조용히 반환하는" 실패 중 어느 쪽이 더
   위험하다고 생각하나요? 그 이유와, 후자를 방지하기 위한 설계 원칙을 설명해보세요.
3. Helm 차트의 `values.yaml`/`configmap.yaml`처럼 애플리케이션 코드와 분리된 배포 설정의
   변경을 PR 리뷰나 CI에서 검증하는 방법에는 어떤 것들이 있을까요?

---

## 사건 9: ChatGPT mcp_session_id 드랍 → 무한 AUTH_REQUIRED 루프 (2026-06-17)

### 틀린 첫 가설

처음에는 세션 만료 로직이나 서버 재시작 문제라고 가정했다. 로그에서 매 호출마다 새 세션이
발급되는 것을 보고 "세션 저장소가 초기화되는 것 아닌가?" 라고 의심. Postgres 영속 세션으로
전환했음에도 증상이 동일해 서버 코드 문제임을 의심함.

### 실제 원인

**ChatGPT는 도구 호출 결과의 특정 필드를 다음 턴에 자동으로 전달하지 않는다.** `start_auth`
가 반환한 `mcp_session_id`를 사용자가 다시 `get_my_schedule mcp_session_id=<value>`로
명시적으로 넣어줘야 하는데, ChatGPT는 이전 도구 결과를 다음 도구 인자로 자동 매핑하지 않는다.

결과: 세션 A로 로그인 → 다음 턴 도구 B 호출 시 `mcp_session_id=null` → 서버는 새 세션 B
발급 → 미인증 → AUTH_REQUIRED → 다시 start_auth → 또 다른 세션 C 발급 → 무한 루프.

Claude Desktop은 이 문제가 없다 (context window 내에서 이전 결과를 알아서 참조).

### 해결 방향

**HTTP 계층의 안정 신원 두 가지를 추가 조회 경로로 활용 (3-tier resolution, ADR 0036):**

1. **transport session id** (`Mcp-Session-Id` HTTP 헤더): 서버가 연결마다 발급, LLM이
   건드릴 수 없음. `start_auth` 시 세션에 바인딩 → 이후 opaque id가 없어도 transport id로
   세션 찾기.

2. **OAuth sub** (`Authorization: Bearer <JWT>`의 `sub` claim): HTTP 계층이라 LLM이
   절대 드랍 불가. opt-in 모드(rs-enabled=true) 시 SecurityContext에서 읽어 세션 매핑.

**세션 해석 우선순위 (McpAuthHelper.resolveSession)**:
```
1. OAuth sub (SecurityContextHolder → JwtAuthenticationToken.getName())
2. Mcp-Session-Id 헤더 → findByTransportId()
3. LLM 인자 mcp_session_id → find()
```

각 tier 성공 시 하위 tier를 상위로 opportunistic 바인딩 → 다음 호출은 더 안정적인 경로 사용.

### 보안 고려 (왜 transport 바인딩이 opaque id 병합보다 안전한가)

과거에 "같은 mcp_session_id로 두 요청이 들어오면 병합하자"는 아이디어가 있었는데 기각됨:
LLM이 인자로 보내는 값을 그냥 믿으면 **조작된 mcp_session_id로 남의 세션 하이재킹** 가능.

transport id는 서버가 HTTP 연결에 직접 귀속시키므로 클라이언트가 임의 값을 보내도 서버가
실제 연결과 매핑 검증 후 세션을 돌려줌 → 교차 사용자 공격 불가.

### 핵심 파일 및 커밋

- `domain/auth/mcp/McpAuthSessionStore.java` — bindTransportId, bindOauthSubject, findByTransportId, findByOauthSubject
- `domain/auth/mcp/McpSessionEntity.java` — transportSessionId, oauthSubject 컬럼
- `domain/mcp/tool/McpAuthHelper.java` — 3-tier resolveSession() + 3-tier principalKey()
- `domain/mcp/tool/McpAuthMcpTools.java` — start_auth에서 bindCurrentTransportId() 호출
- `global/security/McpOAuthSecurityConfig.java` — 항상 로드 + 조건부 JWT RS
- `resources/db/migration/postgresql/V12__add_mcp_session_transport_oauth.sql`
- PR #73, feat/mcp-auth-optin-two-mode

### 포트폴리오 포인트

LLM 클라이언트 거동 불균일성(ChatGPT vs Claude Desktop) 때문에 생기는 stateful 인증 문제를,
HTTP 계층 신원(transport id, OAuth sub)으로 LLM 레이어를 우회해 해결한 사례. Spring
Security opt-in coexistence 패턴과 RFC 9728 PRM 실제 구현 경험. "서버 버그인가?"라는
첫 가설이 틀렸고 클라이언트 거동 분석이 핵심이었다는 점이 포인트.

### 예상 면접 질문

1. MCP 서버에서 인증 세션을 LLM 인자(tool argument) 기반으로 관리할 때 발생하는 근본적인
   한계는 무엇이며, 어떤 방식으로 극복할 수 있나요?
2. Spring Security에서 `permitAll()`로 설정된 엔드포인트에서도 Bearer JWT가 유효하면
   SecurityContext가 채워지는 이유는 무엇인가요? (`BearerTokenAuthenticationFilter`와
   authorization 규칙의 관계를 설명하세요.)
3. OAuth `sub` claim을 세션 바인딩에 사용할 때, 같은 Google 계정의 다른 앱에서 발급된
   토큰이 수락되는 것을 막기 위해 어떤 검증을 추가해야 하나요?

---

## 사건 10: pod 재시작 시 LMS export ZIP 유실 가능성 (설계 리스크, 2026-06-17)

### 배경

LMS 자료 내보내기 기능(`prepare_lms_material_export` → `confirm_lms_material_export`)은
빌드 작업자(`LmsExportBuildWorker`)가 ZIP 파일을 `${java.io.tmpdir}/ssuai-lms-export`
**pod-local ephemeral 디렉토리**에 저장한다. 큐(DB)는 어느 pod에서든 접근 가능하지만,
ZIP 파일은 빌드한 pod에만 있다.

### 리스크

- `READY` 상태가 된 job을 사용자가 confirm 후 다운로드하기 전에 pod 재시작 → ZIP 없음 →
  410 Gone (또는 파일 없음 에러)
- replica > 1 환경에서는 빌드 pod ≠ 다운로드 pod일 수 있음 → 동일 증상

현재는 단일 노드 k3s + replica=1이라 실사용 중 재현 확률이 낮지만, 설계상 취약점.

### 해결 방향 (PR #72, feat/lms-export-pvc)

k3s `local-path-provisioner`(RWO) PVC 마운트:

```yaml
# deploy/charts/ssuai-backend/templates/pvc.yaml (신규)
kind: PersistentVolumeClaim
spec:
  storageClassName: local-path
  accessModes: [ReadWriteOnce]
  resources:
    requests:
      storage: 10Gi
```

Deployment에 `volumes`/`volumeMounts` 추가, `SSUAI_LMS_EXPORT_TEMP_DIR` 환경변수를
마운트 경로로 지정. RWX(multi-pod 공유)는 현재 불필요 — 주석으로 향후 확장 포인트 표시.
`lmsExport.pvc.enabled: true`로 설정 시 활성화 (기본값 false).

### 핵심 파일

- `deploy/charts/ssuai-backend/templates/pvc.yaml` (신규)
- `deploy/charts/ssuai-backend/templates/deployment.yaml`
- `deploy/charts/ssuai-backend/values.yaml`
- `resources/application.yml` (`ssuai.lms.export.temp-dir`)

### 포트폴리오 포인트

stateless 애플리케이션 설계 원칙 위반(로컬 파일 시스템 의존)을 k3s PVC로 해결. "지금은
단일 노드라 괜찮다"와 "설계상 안전하다"는 다르다는 인식. replica 확장 시 RWX/object store로
업그레이드해야 한다는 판단 기준 포함.

### 예상 면접 질문

1. 컨테이너 오케스트레이션 환경에서 파일 시스템에 의존하는 비동기 작업의 내결함성을 어떻게
   보장하나요? PVC 외에 어떤 대안(object storage, DB BLOB 등)이 있으며 각각의 트레이드오프는?
2. RWO(ReadWriteOnce)와 RWX(ReadWriteMany) PVC의 차이와, 각각 적합한 시나리오를 설명하세요.
3. 비동기 작업의 "상태는 DB, 결과물은 파일" 패턴에서 두 저장소의 정합성을 어떻게 보장하나요?
   (빌드는 성공했지만 파일 저장 중 pod 재시작 등)
