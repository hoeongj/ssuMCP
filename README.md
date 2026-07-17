# ssuMCP — 숭실대학교 MCP 서버

[![CI](https://github.com/ghdtjdwn/ssuMCP/actions/workflows/ci.yml/badge.svg)](https://github.com/ghdtjdwn/ssuMCP/actions/workflows/ci.yml)

> 🇺🇸 English version: [README.en.md](README.en.md)

> 🧩 **숭실대 캠퍼스 AI 플랫폼** (4-서비스 중 하나) · **ssuMCP** · [ssuAI](https://github.com/ghdtjdwn/ssuAI) · [ssuAgent](https://github.com/ghdtjdwn/ssuAgent) · [ssu-ai-service](https://github.com/ghdtjdwn/ssu-ai-service) · 🟢 [Live](https://ssuai.vercel.app)

숭실대학교 캠퍼스 정보를 **MCP(Model Context Protocol) 표준 도구**로 제공하는 공개 서버.  
동시에 [ssuAI](https://github.com/ghdtjdwn/ssuAI) 웹 클라이언트를 위한 REST API 서버다.

> **MCP 엔드포인트**: `https://ssumcp.duckdns.org/mcp`  
> **Grafana**: `https://ssumcp.duckdns.org/grafana`

---

## 왜 만들었나

학식 메뉴, 도서관 좌석, 성적, 시간표처럼 매일 확인하는 정보를 매번 포털에서 찾는 게 번거로웠다. 단순히 크롤러를 만드는 대신, LLM이 직접 학교 데이터를 도구로 호출할 수 있는 MCP 서버를 만들면 챗봇·IDE·자동화 파이프라인 어디서든 재사용할 수 있겠다고 판단했다.

Claude Desktop에 연결하면 *"오늘 학식 뭐야"*, *"이번 학기 성적 알려줘"*, *"도서관 빈 자리 예약해줘"* 같은 요청에 LLM이 직접 데이터를 가져와 답하거나 행동한다.

---

## ChatGPT + ssuMCP 실제 연동

ChatGPT에 ssuMCP를 연결한 실제 세션에서 개인 학사 데이터를 조회하고, 확인이 필요한 도서관 좌석
예약과 LMS 강의자료 내보내기까지 실행했다. 아래 화면은 성공한 연동 사례이며, 개인 수치나 세션
정보는 문서 본문에 재기재하지 않는다.

**u-SAINT 졸업사정 조회 — 남은 이수 조건을 자연어로 정리**

![ChatGPT가 ssuMCP의 u-SAINT 졸업사정 데이터를 조회해 남은 졸업요건을 설명하는 화면](docs/assets/chatgpt-graduation-guidance.png)

**도서관 좌석 예약 — 빈 좌석 확인과 클라이언트 확인 후 예약 결과 반환**

![ChatGPT가 ssuMCP 도구로 도서관 빈 좌석을 확인하고 모서리 좌석 예약 결과를 반환하는 화면](docs/assets/chatgpt-library-seat-reservation.png)

**LMS 강의자료 내보내기 — 전체 과목의 지원 파일을 ZIP으로 준비하고 다운로드**

![ChatGPT가 ssuMCP로 전체 수강 과목의 비영상 강의자료 ZIP을 준비하고 다운로드 링크를 반환한 화면](docs/assets/chatgpt-lms-export-ready.png)

![ssuMCP가 준비한 LMS 강의자료 ZIP을 브라우저에서 다운로드하는 화면](docs/assets/chatgpt-lms-download.png)

이 흐름의 OAuth 세션 경계와 ChatGPT MCP 연결 결정은 [ADR 0038](docs/adr/0038-chatgpt-mcp-oauth-auth0-dcr.md),
write 도구의 2단계 확인 계약은 [ADR 0086](docs/adr/0086-confirm-action-async-and-scoped-supersede.md)에
기록했다. LMS 내보내기는 영상·오디오를 제외한 지원 파일만 묶으며, 비동기 ZIP 생성과 다운로드
권한은 [ADR 0033](docs/adr/0033-lms-material-zip-export.md), 전체 과목 수집 흐름은
[ADR 0035](docs/adr/0035-lms-export-all-materials.md), 다운로드 토큰의 1회성 소비는
[ADR 0067](docs/adr/0067-lms-single-use-download-token.md)을 따른다.

---

## 연결하기

### Claude Desktop

`%APPDATA%\Claude\claude_desktop_config.json` (Windows) /  
`~/Library/Application Support/Claude/claude_desktop_config.json` (macOS):

```json
{
  "mcpServers": {
    "ssuMCP": {
      "url": "https://ssumcp.duckdns.org/mcp"
    }
  }
}
```

재시작 후 채팅창 도구 아이콘에서 도구 목록을 확인한다.

### Cursor / 그 외 MCP 클라이언트

`.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "ssuMCP": {
      "url": "https://ssumcp.duckdns.org/mcp"
    }
  }
}
```

### 직접 실행 (self-host)

호스팅 서버 대신 자체 인프라에서 띄우려면 퍼블리시된 런처로 한 줄 실행한다(Java 21+ 필요 — GitHub 릴리스에서 JAR을 받아 구동):

```bash
npx ssumcp
```

### n8n 운영 자동화 연동

`n8n/`에 ssuMCP 공개 API를 소스로 하는 워크플로우 예제가 있다 — 새 공지 → Discord 알림(5분 폴링), 주간 공지·학식 복합 리포트(월 09:00 cron). 예약 상태머신·auth 같은 핵심 로직은 서버가 소유하고, n8n은 "완성된 서비스를 연결하는 운영 자동화"만 담당한다.

---

## 도구 목록 (52개)

### 공개 도구 — 인증 불필요

**학식·시설·도서관**

| 도구 | 설명 |
|------|------|
| `get_today_meal` | 오늘 학식 메뉴 |
| `get_meal_by_date` | 날짜별 학식 |
| `get_meal_weekly` | 주간 학식 |
| `get_dorm_weekly_meal` | 레지던스홀 주간 식단 |
| `search_campus_facilities` | 교내 시설 검색 |
| `get_library_seat_catalog` | 도서관 정적 좌석·열람실 카탈로그 |
| `search_library_book` | 중앙도서관 도서 검색 |

**학사 (Academic Policy RAG)**

| 도구 | 설명 |
|------|------|
| `get_academic_calendar` | 학사 일정 조회 |
| `find_academic_calendar_events` | 학사일정 월/키워드 필터 검색 |
| `classify_academic_question` | 학사 질문 의도 분류 |
| `search_academic_policy_sources` | 공식 학칙·졸업·장학 근거 검색 |
| `get_academic_policy_brief` | 공식 출처 기반 학사 규정 요약 |
| `check_scholarship_policy` | 장학 기준과 입력 조건 근거 대조 |
| `list_academic_policy_sources` | 학사 RAG 공식 출처 목록 |

학칙·졸업·장학 질문은 서버 시작 후 `rule.ssu.ac.kr` 및 `ssu.ac.kr` 원문을 가져와 corpus를 갱신하고 lexical+임베딩 **RRF 하이브리드**로 검색한다(ADR 0020, 아래 아키텍처 참조). 도구 응답에는 `url`, `revision`, `effectiveDate`, `live`, `fallbackUsed`, `embeddingUsed`를 포함한다. `get_academic_calendar`·`find_academic_calendar_events`는 메인 사이트 `ssu.ac.kr/학사/학사일정/?years={year}`의 서버 렌더링 월 블록을 스크래핑한다(ADR 0054, real 데이터는 카테고리 라벨 없음).

**공지사항**

| 도구 | 설명 |
|------|------|
| `get_recent_notices` | 학교 공지사항 최신 목록 |
| `search_notices` | 공지사항 키워드 검색 |
| `list_notice_categories` | 공지 카테고리 목록 |
| `get_notice_detail` | 공지 본문 전체 조회 |
| `get_active_notices` | 진행 중(마감 전) 공지 |
| `get_department_notices` | 학과/부서별 공지 |

### 개인 도구 — 인증 필요

인증 흐름:

1. `start_auth(provider="SAINT")` → `loginUrl` + `mcpSessionId` 수신
2. `loginUrl`을 브라우저에서 열어 숭실대 계정 로그인
3. 이후 개인 도구 호출 시 `mcp_session_id` 파라미터 전달

**세션 관리**

| 도구 | 설명 |
|------|------|
| `start_auth` | 로그인 URL 발급 (provider: SAINT / LMS / LIBRARY) |
| `get_auth_status` | 세션 상태 확인 |
| `logout_provider` | 특정 provider 해제 |
| `logout_all` | 세션 전체 해제 |

**u-SAINT (provider: SAINT)**

| 도구 | 설명 |
|------|------|
| `get_my_schedule` | 시간표 (학기 지정 가능) |
| `get_my_grades` | 성적 및 누적 GPA |
| `get_my_chapel_info` | 채플 출석 현황 |
| `check_graduation_requirements` | 졸업 요건 충족 여부 |
| `evaluate_graduation_with_policy` | 졸업 요건 + 공식 학칙 근거 함께 조회 |
| `get_my_scholarships` | 장학금 수혜 내역 |
| `simulate_gpa` | 이번 학기 예상 성적 → 누적 GPA 예측 |

**LMS (provider: LMS)**

| 도구 | 설명 |
|------|------|
| `get_my_assignments` | 현재 학기 미제출 과제·퀴즈 목록 |
| `get_my_lms_terms` | 사용자의 LMS 등록 학기 목록 조회 |
| `get_lms_dashboard` | 미제출 과제·학사일정·공지사항을 모아보는 대시보드 |
| `get_my_lms_courses` | 사용자의 LMS 수강 과목 목록 조회 |
| `get_my_lms_materials` | 비영상 주차학습 자료(PDF, PPT 등) 목록 조회 (비디오/오디오 제외) |
| `prepare_lms_material_export` | 선택 자료 내보내기 준비 (용량/개수 제한 검증 및 ActionAudit 생성) |
| `confirm_lms_material_export` | 내보내기 최종 승인 및 20분 유효 다운로드 링크 발급 |
| `export_all_lms_materials` | 전체 자료를 자동 수집해 미리보기 반환 후 `confirm_lms_material_export` 필요 |

**도서관 (provider: LIBRARY)**

| 도구 | 설명 |
|------|------|
| `get_library_seat_status` | 층별 좌석 현황 (2·5·6층) |
| `get_library_available_seats` | 전체 열람실 live per-seat 가용 좌석 요약 |
| `get_room_available_seats` | 특정 열람실 per-seat 상태 목록 (available/occupied/away/inactive) |
| `recommend_library_seats` | 선호도 기반 좌석 추천 |
| `prepare_reserve_library_seat` | 좌석 예약 준비 → `confirm_action` 필요 |
| `wait_for_library_seat` | 좌석 대기 등록 — 빈자리가 나면 백그라운드 워커가 자동 예약 (호출 자체가 동의, `confirm_action` 불필요) |
| `get_library_wait_status` | 좌석 대기 최신 상태 조회 |
| `cancel_library_wait` | 좌석 대기 취소 (예약 시작 전까지만 가능) |
| `get_my_library_seat` | 현재 예약 좌석 조회 |
| `prepare_swap_library_seat` | 이석 준비 → `confirm_action` 필요 |
| `prepare_cancel_library_seat` | 반납 준비 → `confirm_action` 필요 |
| `confirm_action` | write 작업 최종 실행 (2단계 확인 패턴) |
| `get_my_library_loans` | 도서관 대출 현황 (반납 기한 포함) |

---

## 아키텍처

전체 시스템은 3개의 서비스로 구성된다:

![ssuMCP 서비스·운영 아키텍처 — 공유 Service 계층, 상태 저장소, 학교 커넥터, GitOps와 관측성](docs/assets/architecture.svg)

`ssuMCP`는 MCP tool server다 — 원자적 도메인 도구, 학교 시스템 직접 연동, fault tolerance, action 감사를 담당한다. `ssuAgent`는 LangGraph orchestrator로 자연어 의도 파악, 도구 조합, HITL 인터럽트를 담당한다. 두 서비스는 MCP 프로토콜로만 연결되어 독립 배포가 가능하다.

REST와 MCP 두 경로는 동일한 Service 레이어를 공유한다. MCP 도구가 별도 비즈니스 로직을 갖지 않는다.

런타임·데이터·배포 경계는 [상세 아키텍처 문서](docs/architecture.md)에 정리했다. 이미지의 [PNG 버전](docs/assets/architecture.png)도 함께 제공한다.

학칙·졸업·장학 질문은 공식 출처 추적형 **하이브리드 검색**으로 처리한다 — 키워드 lexical 스코어와 임베딩 코사인 유사도를 **RRF(Reciprocal Rank Fusion)**로 융합한다. 임베딩은 `(chunk_hash, model)` 키로 `academic_embeddings` 테이블에 영속화(base64 float32)되어, pod 재시작·주기 갱신이 무료 티어 일일 임베딩 쿼터를 재소진하지 않는다(미임베딩 청크만 새로 임베딩). 임베딩이 비활성/실패하면 lexical 전용으로 자동 강등하며, 응답의 `embeddingUsed`(rrf/lexical) 필드로 노출한다(ADR 0020). 서버 시작 후와 주기 갱신 시
`rule.ssu.ac.kr` 및 `ssu.ac.kr` 원문을 가져와 corpus를 갱신하고, 도구 응답에는
`url`, `revision`, `effectiveDate`, `live`, `fallbackUsed`를 포함한다. 개인 졸업 판정은
u-SAINT 데이터와 이 공식 근거를 함께 반환한다.

### Connector 패턴

모든 외부 시스템 호출은 인터페이스로 추상화된다.

```
MealConnector (interface)
├── MockMealConnector   — 고정 fixture 반환 (기본값)
└── RealMealConnector   — 실제 학식 사이트 Jsoup 파싱
```

`@ConditionalOnProperty`로 `ssuai.connector.meal=mock|real`에 따라 주입된다.  
기본값이 `mock`이므로 외부 네트워크 없이 빌드·테스트·로컬 실행이 가능하다.

### LLM 채팅 엔진

`LlmChatService`는 OpenAI 호환 API 포맷으로 10개 프로바이더에 순차 Fallback한다. 죽은 프로바이더는 프로바이더별 Resilience4j Circuit Breaker(`llm-{provider}`)로 cooldown해서 매 요청마다 재시도하지 않고 바로 다음으로 넘어간다(ADR 0025).

```
사용자 메시지
    → LlmChatService
    → LlmProvider 체인 (Gemini → Groq → OpenRouter → Cerebras → ... 총 10개)
        Rate limit / 서버 오류 / CB OPEN 시 자동으로 다음 프로바이더로 전환
    → tool_call 발생 시
        공개 도구: McpSyncClient → 자체 /mcp (self-dogfood)
        개인 도구: 웹 세션 컨텍스트로 Service 직접 호출 (ThreadLocal)
    → 최종 응답
```

### 도서관 좌석 자동 예약 (Write 도구)

도서관 예약·이석·반납은 `prepare_* → confirm_action` 2단계 확인 패턴을 사용한다.

```
get_library_available_seats
    → 빈 좌석 목록 확인 (externalSeatId, label, status)
    → prepare_reserve_library_seat(seat_id)
    → 확인 메시지 표시 ("오픈열람실 25번 예약합니다. 확인하시겠습니까?")
    → confirm_action(pending_action_id)
    → 실제 Pyxis 예약 실행
```

좌석이 없을 때는 `wait_for_library_seat`로 비동기 예약 intent를 등록한다. 백그라운드 워커가 조건에 맞는 좌석을 폴링하다 빈자리가 나면 자동 예약하고, 대기 상태는 SSE로 실시간 전달한다(아래 "비동기 예약 동시성" 참조).

---

## 엔지니어링 노트

구현 중 부딪힌 문제와 판단 근거를 기록한다. 상세 내용은 [트러블슈팅 하이라이트](docs/troubleshooting-highlights.md)에 있다.

### u-SAINT: SAP WebDynpro 역공학 → rusaint FFI로 전환

u-SAINT가 SAP NetWeaver WebDynpro 기반이라 `sap-contextid`, `sap-ext-sid`, `SAPEVENTQUEUE` 등이 stateful하게 얽혀 있다. 직접 HTTP 역공학을 8일간 시도했지만 wire-level ground truth 없이 protocol을 추측하는 방식은 한계가 명확했다.

검증된 Rust 구현체 [rusaint](https://github.com/EATSTEAK/rusaint)를 JNA(Java Native Access)로 JVM에 FFI 연동하는 방향으로 전환했다. SmartID 콜백의 `sToken`을 rusaint `withToken`에 한 번만 전달하고, 결과 세션은 AES-256-GCM으로 암호화해 저장한다. 웹 세션 쿠키는 콜백 응답이 아니라 1회용 code를 교환하는 `POST /api/auth/exchange` 200 응답에서만 발급한다(ADR 0095).

### WAF 쿠키 + CookieManager 격리

대학 보안 장비(WAF)가 특정 쿠키 누락 시 세션을 익명(ANON)으로 강등한다는 사실을 브라우저 DevTools 패킷 캡처로 특정했다. 단순히 `MYSAPSSO2`만 전달하던 방식에서 `WAF` 쿠키를 함께 보존하도록 수정했다.

LMS Canvas의 5-hop SSO 체인에서는 수동 쿠키 병합 방식이 서브도메인 간 쿠키를 오염시키는 문제가 있었다. 요청마다 격리된 `java.net.CookieManager`를 장착한 `HttpClient`를 생성해 브라우저 수준의 도메인별 쿠키 격리를 구현했다.

### Single-flight 캐시

SAP WebDynpro는 학기별 페이지를 이전 버튼으로 하나씩 이동해야 하는 stateful 구조다. 1시간 TTL 캐시를 두되, cold start 시 동시에 몰리는 중복 요청을 첫 요청만 upstream에 보내고 나머지는 결과를 공유하는 single-flight 패턴으로 처리했다.

### Pyxis per-seat API 역공학

공식 문서가 없는 Pyxis 도서관 API를 브라우저 DevTools Network 탭으로 역추적해 `GET /pyxis-api/1/api/rooms/{roomId}/seats` 엔드포인트를 발견했다. 응답에서 좌석별 `isActive`·`isOccupied`·`seatChargeState` 조합으로 available/occupied/away/inactive 4가지 상태를 매핑해 실시간 per-seat 조회 도구를 구현했다. 이 데이터를 통해 이전까지 불가능했던 정확한 좌석 단위 예약이 가능해졌다.

### 비동기 예약 동시성 — intent 큐 · per-seat 분산 락 · SSE (EPIC 4·5)

좌석 예약을 동기 호출로 두면 같은 좌석에 동시 요청이 몰릴 때 업스트림(Pyxis)에 중복 write가 가고 LLM 호출도 길게 막힌다. `wait_for_library_seat`를 비동기 intent로 큐잉하고 백그라운드 워커가 처리하는 구조로 분리했다. 동시성은 좌석 단위 **Redisson 분산 락**(`LibraryDistributedLockClient`)으로 직렬화하되, 정합성의 source of truth는 Postgres `SELECT … FOR UPDATE`가 잡고 락은 효율용으로만 쓴다(락이 죽어도 DB가 중복 예약을 막음). 대기/결과 상태는 **Redis RTopic fan-out 기반 SSE**(`GET /api/library/reservations/wait/events/{intentId}`)로 실시간 푸시한다 — 멀티 포드에서 어느 포드가 intent를 처리하든 전 구독자에게 전파된다. 근거·대안은 ADR 0047(분산 락)·0048(SSE).

### Spring AI reflection workaround

`McpServer.SyncSpecification.tools`가 `package-private final`이라 tool annotation(`readOnlyHint`, `destructiveHint`) 주입 공개 API가 없었다. `@Primary McpSyncServerCustomizer`로 auto-configure 빈을 교체하고 reflection으로 tool list를 재구성했다. Spring AI가 공개 API를 열면 제거할 임시 bridge다.

### 관측성 3-pillars (메트릭 · 분산추적 · 중앙로그)

**메트릭**: Spring Boot Actuator + Micrometer Prometheus registry. 운영 클러스터에 ArgoCD `monitoring` Application으로 kube-prometheus-stack을 배포하고, `ssuai-backend` ServiceMonitor가 `/actuator/prometheus`를 scrape한다 — RED 13패널 대시보드 + 3개 PrometheusRule(5xx>5%, p99>3s, 서킷브레이커 OPEN). Grafana는 `https://ssumcp.duckdns.org/grafana`.

**분산추적 + 중앙로그** (ADR 0069): Micrometer Observation 브리지 → OTel → OTLP → **Tempo**, Logback JSON → Promtail → **Loki**. provider 시도마다 `llm.provider.call` 스팬을 찍어 **10-provider 폴백 시퀀스가 추적 타임라인에** 그대로 보인다. javaagent(제로코드) 대신 코드레벨 브리지를 써서 도메인 커스텀 스팬을 표현했다. **prod 라이브** — Tempo/Loki/Promtail을 ArgoCD로 배포하고(single-binary, 단일 ARM 노드 예산 내) 백엔드 emit(`json-logs` + 샘플링 0.1)을 켜 TraceQL `service.name=ssuai` trace·Loki 로그를 Grafana에서 조회한다. 활성화 중 만난 함정(Boot 4가 OTLP autoconfig를 `spring-boot-starter-opentelemetry`로 이관 → 저수준 deps만으론 exporter 미생성, `gradle dependencies`로 규명 / Loki `reject_old_samples` / k3s promtail 심링크 마운트 / distroless probe)은 모두 해소·기록. 코드 기본값은 샘플링 0·프로파일 게이트라 켜기 전엔 무영향. 로컬 데모: `load-tests/docker-compose.yml --profile full`. (MDC `traceId` 키 충돌은 기존 필터를 `requestId`로 리네임해 해소 — 클라이언트 계약 불변.)

---

## 보안 원칙

- **비밀 값 비로깅**: 비밀번호, 세션 쿠키, JWT, API 키는 로그에 기록하지 않는다.
- **좌석 aggregate 캐시 경계**: 층별 전역 집계는 `floor + upstream token 존재 여부`로 캐시하며 token 원문·사용자 식별자·개인 좌석 정보는 key와 값에 포함하지 않는다. production 공개 조회는 내부 sampler token을 사용한다.
- **읽기 전용 MCP**: 대부분의 도구에 `readOnlyHint=true`, `logout_*`에 `destructiveHint=true`를 표시한다.
- **Write 도구 설계 원칙**: 예약·취소 같은 상태 변경 도구는 `prepare_*` + `confirm_action` 2단계 확인 패턴으로만 구현한다. (ADR 0015)

### 보안 하드닝 (2026-06 remediation)

여러 독립적인 보안 점검에서 모인 지적을 **코드 대조로 진짜/오진/이미고침을 가른 뒤** 배포했다. 2026-07-14 live-tool audit는 명시 MCP 세션이 transport로 fallback하던 P0를 발견해 단일 authoritative resolver로 차단했다(ADR 0098): 명시 ID는 정확히 해소하고 invalid는 `INVALID_SESSION`, transport 불일치는 `SESSION_MISMATCH`, ID 생략 때만 binding을 사용한다. 핵심 제어: SSO callback code exchange(ADR 0095), 도서관 세션 영구 쿠키 키(ADR 0096), OAuth-sub 소유권 가드(ADR 0056), CSRF Origin/Referer 검증 필터(ADR 0057), prod 설정 fail-fast(ADR 0058), 예약 audit 단일 진실원천 + fail-closed 좌석락(ADR 0059), `/api/chat` read-only(ADR 0060), per-IP rate limit + 입력 상한(ADR 0061), 공급망 SHA 핀 + k8s pod-security(ADR 0062)다.

- 배포 완료 제어: [`docs/security.md`](docs/security.md) §14-1
- 보류·후속 항목: [`docs/security-followups.md`](docs/security-followups.md)

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| 언어 / 프레임워크 | Java 21 + Kotlin 2.3, Spring Boot 4.0 |
| MCP 구현 | Spring AI 1.1 (Streamable HTTP, SDK 0.18.3 — 4개 아티팩트 일괄 핀) |
| 동시성 / 분산 | Redisson 4.5 (per-seat 분산락 · RTopic 멀티포드 fan-out · 리더선출), PostgreSQL `SELECT FOR UPDATE SKIP LOCKED` |
| 회복탄력성 | Resilience4j 2.3 core (provider별 서킷브레이커 · retry · rate limiter · bulkhead) |
| u-SAINT 연동 | rusaint — JNA 5.18로 Rust/UniFFI 라이브러리를 JVM에 연결 |
| 학사 정책 검색 | 공식 출처 추적형 **하이브리드 RAG** — lexical + 임베딩 코사인 RRF(k=60) 융합, `academic_embeddings` 영속 캐시 (옵션 pgvector HNSW 프로파일로 ANN 역량 증명, ADR 0070) |
| 인증 | JJWT 0.13 (HS256, **gson serializer로 Jackson 디커플**) · OAuth2.1 resource server · AES-256-GCM |
| 관측성 | Micrometer/Prometheus 메트릭 + OTel/Tempo 분산추적 + Logback JSON/Loki 중앙로그 (ADR 0069) |
| 테스트 | JUnit 5 · **Testcontainers**(실 PG16/Redis7 통합테스트) · **JaCoCo 커버리지 래칫** · WireMock 3 · k6 (ADR 0068) |
| 인프라 | Oracle Cloud ARM64 · k3s · Traefik · ArgoCD(Image Updater GitOps) · Helm · GHCR(AMD64/ARM64) · Prometheus/Grafana |
| DB | PostgreSQL 17 + Flyway (vendor-split 마이그레이션) |
| 크롤링 | Jsoup 1.22 |

---

## 로컬 개발

### 실행 (mock 모드 — 외부 네트워크 불필요)

```bash
./gradlew bootRun
# http://localhost:8080
```

MCP Inspector로 도구 확인:

```bash
npx @modelcontextprotocol/inspector
# Transport: Streamable HTTP  URL: http://localhost:8080/mcp
```

### 실행 (실제 데이터)

```bash
cp .env.example .env
SSUAI_CONNECTOR_MEAL=real ./gradlew bootRun
```

### 검증

```bash
./gradlew test                                                  # 단위 + (Docker 있으면) Testcontainers 실 PG/Redis 통합테스트
./gradlew test jacocoTestReport jacocoTestCoverageVerification  # 커버리지 래칫 게이트(LINE 0.70 floor)
./gradlew build
```

> ~1,030 테스트(0 실패). 컨테이너 통합테스트는 Docker 없으면 자동 skip(`disabledWithoutDocker`)해 오프라인 빌드는 그린, CI가 권위 게이트.

---

## 환경 변수

주요 변수는 `.env.example` 참조. 실제 값은 저장소에 커밋하지 않는다.

| 변수 | 설명 |
|------|------|
| `SSUAI_JWT_SECRET` | HS256 서명 키 (32바이트 이상). 미설정 시 재시작마다 갱신 |
| `SSUAI_CREDENTIAL_ENCRYPTION_KEY` | AES-GCM 세션 암호화 키 |
| `SSUAI_CONNECTOR_MEAL` | `mock` (기본) 또는 `real` |
| `SSUAI_OPENROUTER_API_KEY`, ... | LLM 프로바이더 키 (각 선택적) |

---

## Backend / AI 포트폴리오 하이라이트

단순 CRUD를 넘어 실제 엔지니어링 문제를 다룬 핵심 결정들:

### 1. Pyxis 외부 API Fault Tolerance (EPIC 2)

학교 도서관 API(`oasis.ssu.ac.kr`)가 시험 기간에 불안정해지는 상황을 위해 Resilience4j로 4-layer 보호를 구현했다.

- **핵심 결정**: read/write 비대칭. read만 retry(3회 지수 백오프) — write는 멱등하지 않아 재시도 시 이중 예약 위험.
- **측정**: 공유 CircuitBreaker "pyxis" — slidingWindow 20, failureThreshold 50%, 30s wait, 3 probe half-open.
- **Grafana**: `resilience4j_circuitbreaker_state{name="pyxis"}` · failure rate · slow call rate 패널.

### 2. Write 도구 안전 설계 — action 감사 상태 머신 (EPIC 3)

`prepare_* → confirm_action` 2단계 확인 패턴. 사용자가 명시적으로 확인하지 않으면 실제 Pyxis 호출이 발생하지 않는다.

- **Duplicate confirm 방지**: `action_audit` 행의 PREPARED 상태를 `SELECT FOR UPDATE`로 원자적 전환. 두 번째 confirm은 행을 찾지 못해 에러 반환.
- **Write timeout 복구**: timeout 후 `getCurrentCharge`(GET, idempotent)로 Pyxis 실제 상태 확인 → action_audit 업데이트.
- **k6 실험 결과**: 같은 좌석 100 동시 burst → SUCCESS 2 · FAILURE_RACE 98 · ghost reservation 0.

### 3. k6 부하 실험 (EPIC 1)

실측 수치를 먼저 박제한 후 EPIC 2·3·4 개선 작업을 시작했다.

| 시나리오 | 결과 |
|----------|------|
| `get_library_seat_status` 50 RPS · 5분 | p95 **19.7ms** · 에러 0% · 캐시 히트율 ~99% |
| write burst 100 동시 (같은 좌석) | SUCCESS 2 · RACE 98 · ghost 0 |
| write burst 100 동시 (다른 좌석) | SUCCESS **100** · 처리율 100% |

> 전체 실험 설계·결과·해석: [`docs/performance/library-agent-load-test.md`](docs/performance/library-agent-load-test.md)

### 4. rusaint JNA FFI — u-SAINT SAP WebDynpro 연동

u-SAINT가 SAP NetWeaver WebDynpro 기반이라 표준 HTTP 역공학이 8일 만에 실패했다. 검증된 Rust 구현체 rusaint를 JNA로 JVM에 연결하는 방향으로 전환했다.

- SmartID SSO 콜백 `sToken`·`sIdno`만 수신 → `RusaintUniFfiClient`에 전달 → 1회용 exchange code 발급 → `POST /api/auth/exchange`가 웹 세션 쿠키 발급.
- 비밀번호는 절대 서버에 닿지 않음 (SmartID가 처리).

### 5. LangGraph 멀티에이전트 시스템 — ssuAgent (EPIC 6)

ssuMCP와 별도 Python 서비스로 분리. MCP 프로토콜로만 연결.

- **Supervisor → Library·Academic·LMS 에이전트** 3-way 라우팅.
- **HITL 인터럽트**: `prepare_reserve_library_seat` 결과에 `actionId` 포함 시 graph interrupt → 사용자 확인 → `confirm_action` 재개.
- **LLM 다중 fallback**: Groq llama-3.3-70b → Gemini 2.5 Flash → OpenRouter llama-3.3-70b (Groq가 무료 일일 쿼터 14,400으로 1순위).

### 6. MCP 세션 격리 (ADR 0098)

학교 시스템별 독립 세션(SAINT·LMS·도서관)을 단일 `mcp_session_id`로 관리한다. 명시 ID가 있으면
그 세션만 정확히 해석하며, invalid/mismatch ID를 transport·OAuth 세션으로 fallback하지 않는다.
ID를 생략한 경우에만 현재 MCP transport binding을 사용한다.

```
explicit mcp_session_id → McpAuthSession (7d Postgres 영속)
                │
                ├─ 프로바이더별 링크 (SAINT / LMS / LIBRARY)
                └─ ID 생략 시에만 transport binding으로 안전하게 복원
```

sToken·LMS 쿠키·Pyxis-Auth-Token은 AES-256-GCM으로 암호화 저장.

---

## 문서

- [아키텍처](docs/architecture.md)
- [장애 시나리오](docs/failure-scenarios.md)
- [면접 Q&A](docs/interview-qa.md)
- [MCP 도구와 인증 흐름](docs/mcp-tools.md)
- [보안 정책](docs/security.md)
- [성능 리포트 (EPIC 1 k6)](docs/performance/library-agent-load-test.md)
- [트러블슈팅 하이라이트](docs/troubleshooting-highlights.md)
- [52-tool live audit remediation](docs/audits/2026-07-14-live-tool-hardening.md)
- [배포 runbook](deploy/README.md)

---

## 관련 프로젝트

**[ssuAI](https://github.com/ghdtjdwn/ssuAI)** — Next.js 웹 클라이언트 (챗봇 UI + 대시보드)  
**[ssuAgent](https://github.com/ghdtjdwn/ssuAgent)** — Python LangGraph 멀티에이전트 오케스트레이터

---

## 라이선스

MIT — [LICENSE](LICENSE)
