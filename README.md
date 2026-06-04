# ssuMCP — 숭실대학교 MCP 서버

[![CI](https://github.com/hoeongj/ssuMCP/actions/workflows/ci.yml/badge.svg)](https://github.com/hoeongj/ssuMCP/actions/workflows/ci.yml)

숭실대학교 캠퍼스 정보를 **MCP(Model Context Protocol) 표준 도구**로 제공하는 공개 서버.  
동시에 [ssuAI](https://github.com/hoeongj/ssuAI) 웹 클라이언트를 위한 REST API 서버다.

> **MCP 엔드포인트**: `https://ssumcp.duckdns.org/mcp`

---

## 왜 만들었나

학식 메뉴, 도서관 좌석, 성적, 시간표처럼 매일 확인하는 정보를 매번 포털에서 찾는 게 번거로웠다. 단순히 크롤러를 만드는 대신, LLM이 직접 학교 데이터를 도구로 호출할 수 있는 MCP 서버를 만들면 챗봇·IDE·자동화 파이프라인 어디서든 재사용할 수 있겠다고 판단했다.

Claude Desktop에 연결하면 *"오늘 학식 뭐야"*, *"이번 학기 성적 알려줘"* 같은 질문에 LLM이 직접 데이터를 가져와 답한다.

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

---

## 도구 목록

### 공개 도구 (인증 불필요)

| 도구 | 설명 |
|------|------|
| `get_today_meal` | 오늘 학식 메뉴 |
| `get_meal_by_date` | 날짜별 학식 |
| `get_dorm_weekly_meal` | 레지던스홀 주간 식단 |
| `search_campus_facilities` | 교내 시설 검색 |
| `search_library_book` | 중앙도서관 도서 검색 |
| `get_recent_notices` | 학교 공지사항 최신 목록 |
| `search_notices` | 공지사항 키워드 검색 |
| `list_notice_categories` | 공지 카테고리 목록 |
| `get_notice_detail` | 공지 본문 전체 조회 |
| `get_active_notices` | 진행 중(마감 전) 공지 |
| `get_department_notices` | 학과/부서별 공지 |

### 개인 도구 (인증 필요)

인증 흐름:

1. `start_auth(provider="SAINT")` → `loginUrl` + `mcpSessionId` 수신
2. `loginUrl`을 브라우저에서 열어 숭실대 계정 로그인
3. 이후 개인 도구 호출 시 `mcp_session_id` 파라미터 전달

| 도구 | provider | 설명 |
|------|----------|------|
| `start_auth` | — | 로그인 URL 발급 |
| `get_auth_status` | — | 세션 상태 확인 |
| `logout_provider` | — | 특정 provider 해제 |
| `logout_all` | — | 세션 전체 해제 |
| `get_my_schedule` | SAINT | 시간표 (학기 지정 가능) |
| `get_my_grades` | SAINT | 성적 |
| `get_my_chapel_info` | SAINT | 채플 출석 현황 |
| `check_graduation_requirements` | SAINT | 졸업 요건 |
| `get_my_scholarships` | SAINT | 장학금 수혜 내역 |
| `simulate_gpa` | SAINT | GPA 시뮬레이션 (이번 학기 예상 성적 → 누적 GPA 예측) |
| `get_my_assignments` | LMS | 과제 및 퀴즈 목록 |
| `get_library_seat_status` | LIBRARY | 층별 좌석 현황 (2·5·6층) |
| `get_my_library_loans` | LIBRARY | 도서관 대출 현황 |

---

## 아키텍처

하나의 Spring Boot 프로세스가 REST API와 MCP 서버를 동시에 제공한다.

```
ssuAI (웹)          Claude Desktop / Cursor / 그 외 MCP 클라이언트
    │  REST /api/*         │  MCP Streamable HTTP
    ▼                      ▼
┌──────────────────────────────────────┐
│   ssuMCP (Spring Boot 4, 단일 JVM)   │
│                                      │
│   REST Controllers   MCP Server      │
│          └──────────────┘            │
│             Service Layer            │
│         Connectors   Repositories    │
└──────────────────────────────────────┘
       │         │         │        │
    학식 사이트  도서관    LMS    u-SAINT
               (Pyxis)  (Canvas) (rusaint)
```

REST와 MCP 두 경로는 동일한 Service 레이어를 공유한다. MCP 도구가 별도 비즈니스 로직을 갖지 않는다.

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

`LlmChatService`는 OpenAI 호환 API 포맷으로 10개 프로바이더에 순차 Fallback한다.

```
사용자 메시지
    → LlmChatService
    → LlmProvider 체인 (OpenRouter → Mistral → Groq → ...)
        Rate limit / 서버 오류 시 자동으로 다음 프로바이더로 전환
    → tool_call 발생 시
        공개 도구: McpSyncClient → 자체 /mcp (self-dogfood)
        개인 도구: 웹 세션 컨텍스트로 Service 직접 호출 (ThreadLocal)
    → 최종 응답
```

---

## 엔지니어링 노트

구현 중 부딪힌 문제와 판단 근거를 기록한다. 상세 내용은 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)에 있다.

### u-SAINT: SAP WebDynpro 역공학 → rusaint FFI로 전환

u-SAINT가 SAP NetWeaver WebDynpro 기반이라 `sap-contextid`, `sap-ext-sid`, `SAPEVENTQUEUE` 등이 stateful하게 얽혀 있다. 직접 HTTP 역공학을 8일간 시도했지만 wire-level ground truth 없이 protocol을 추측하는 방식은 한계가 명확했다.

검증된 Rust 구현체 [rusaint](https://github.com/EATSTEAK/rusaint)를 JNA(Java Native Access)로 JVM에 FFI 연동하는 방향으로 전환했다. SmartID 콜백의 `sToken`을 rusaint `withToken`에 한 번만 전달하고, 결과 세션은 AES-256-GCM으로 암호화해 저장한다.

### WAF 쿠키 + CookieManager 격리

대학 보안 장비(WAF)가 특정 쿠키 누락 시 세션을 익명(ANON)으로 강등한다는 사실을 브라우저 DevTools 패킷 캡처로 특정했다. 단순히 `MYSAPSSO2`만 전달하던 방식에서 `WAF` 쿠키를 함께 보존하도록 수정했다.

LMS Canvas의 5-hop SSO 체인에서는 수동 쿠키 병합 방식이 서브도메인 간 쿠키를 오염시키는 문제가 있었다. 요청마다 격리된 `java.net.CookieManager`를 장착한 `HttpClient`를 생성해 브라우저 수준의 도메인별 쿠키 격리를 구현했다.

### Single-flight 캐시

SAP WebDynpro는 학기별 페이지를 이전 버튼으로 하나씩 이동해야 하는 stateful 구조다. 1시간 TTL 캐시를 두되, cold start 시 동시에 몰리는 중복 요청을 첫 요청만 upstream에 보내고 나머지는 결과를 공유하는 single-flight 패턴으로 처리했다.

### Spring AI reflection workaround

`McpServer.SyncSpecification.tools`가 `package-private final`이라 tool annotation(`readOnlyHint`, `destructiveHint`) 주입 공개 API가 없었다. `@Primary McpSyncServerCustomizer`로 auto-configure 빈을 교체하고 reflection으로 tool list를 재구성했다. Spring AI가 공개 API를 열면 제거할 임시 bridge다.

---

## 보안 원칙

- **비밀 값 비로깅**: 비밀번호, 세션 쿠키, JWT, API 키는 로그에 기록하지 않는다.
- **인증 경계 분리**: 좌석 현황처럼 집계 데이터라도 캐시 키에 인증 여부를 포함해 익명 호출자가 인증 결과를 받지 못하게 한다.
- **읽기 전용 MCP**: 21개 도구에 `readOnlyHint=true`, `logout_*`에 `destructiveHint=true`를 표시한다.
- **Write 도구 설계 원칙**: 예약·취소 같은 상태 변경 도구는 `prepare_*` + `confirm_action` 2단계 확인 패턴으로만 구현한다. (ADR 0015)

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| 언어 / 프레임워크 | Java 21 + Kotlin 2.3, Spring Boot 4.0 |
| MCP 구현 | Spring AI 1.1 (Streamable HTTP) |
| 크롤링 | Jsoup 1.22 |
| u-SAINT 연동 | rusaint — JNA 5.18로 Rust 라이브러리를 JVM에 연결 |
| 인증 | JJWT 0.13 (HS256), AES-256-GCM |
| 테스트 | JUnit 5, MockWebServer, WireMock 3 |
| 인프라 | Oracle Cloud ARM64 · k3s · Traefik · ArgoCD · Helm · GHCR |
| DB | PostgreSQL 17 + Flyway |

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
./gradlew test
./gradlew build
```

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

## 문서

- [아키텍처](docs/architecture.md)
- [MCP 도구와 인증 흐름](docs/mcp-tools.md)
- [보안 정책](docs/security.md)
- [트러블슈팅 로그](TROUBLESHOOTING.md)
- [배포 runbook](deploy/README.md)

---

## 관련 프로젝트

**[ssuAI](https://github.com/hoeongj/ssuAI)** — 이 서버를 소비하는 Next.js 웹 클라이언트

---

## 라이선스

MIT — [LICENSE](LICENSE)
