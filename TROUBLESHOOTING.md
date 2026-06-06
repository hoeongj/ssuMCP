# ssuAI 트러블슈팅 로그

이 파일은 포트폴리오에 넣기 좋은 장애 대응, 디버깅, 배포 문제 해결 기록을
모으는 최상위 로그입니다.

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
