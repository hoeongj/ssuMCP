# ssuMCP — 숭실대학교 MCP 서버

[![CI](https://github.com/hoeongj/ssuMCP/actions/workflows/ci.yml/badge.svg)](https://github.com/hoeongj/ssuMCP/actions/workflows/ci.yml)

숭실대학교 캠퍼스 정보를 **MCP(Model Context Protocol) 표준 도구**로 제공하는 공개 서버.  
동시에 ssuAI 웹 클라이언트를 위한 REST API 서버이기도 하다.

Claude Desktop, Cursor 및 그 외 MCP 클라이언트에서 아래 URL 하나로 연결할 수 있다.

> **MCP 엔드포인트**: `https://ssumcp.duckdns.org/mcp`

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

Claude Desktop 재시작 후 채팅창 도구 아이콘에서 도구 목록을 확인한다.

### Cursor

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

## 공개 도구 (인증 불필요)

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

---

## 개인 도구 (u-SAINT / LMS / 도서관 인증 필요)

**인증 흐름:**

1. `start_auth(provider="SAINT")` 호출 후 `loginUrl`과 `mcpSessionId`를 받는다.
2. `loginUrl`을 브라우저에서 열어 숭실대 계정으로 로그인한다.
3. 이후 개인 도구 호출 시 `mcp_session_id` 파라미터를 전달한다.

| 도구 | provider | 설명 |
|------|----------|------|
| `start_auth` | - | 로그인 URL 발급 |
| `get_auth_status` | - | 세션 상태 확인 |
| `logout_provider` | - | 특정 provider 해제 |
| `logout_all` | - | 세션 전체 해제 |
| `get_my_schedule` | SAINT | 시간표 |
| `get_my_grades` | SAINT | 성적 |
| `get_my_chapel_info` | SAINT | 채플 출석 현황 |
| `check_graduation_requirements` | SAINT | 졸업 요건 |
| `get_my_scholarships` | SAINT | 장학금 수혜 내역 |
| `get_my_assignments` | LMS | 과제 및 퀴즈 목록 |
| `get_library_seat_status` | LIBRARY | 중앙도서관 층별 좌석 현황 |
| `get_my_library_loans` | LIBRARY | 도서관 대출 현황 |

좌석 현황은 집계 데이터이지만 실제 도서관 API가 인증 토큰을 요구하므로 `LIBRARY` 연동 후에만 조회된다. `floor`는 `2`, `5`, `6`만 지원한다.

---

## 아키텍처

하나의 Spring Boot 프로세스가 REST API와 MCP 서버를 동시에 제공한다.

```
ssuAI (웹)          Claude Desktop / Cursor
    │  REST /api/*         │  MCP Streamable HTTP
    ▼                      ▼
┌──────────────────────────────────────┐
│   ssuMCP (Spring Boot 4, 단일 JVM)   │
│                                      │
│   REST Controllers   MCP Server      │
│          └──────────────┘            │
│             Service Layer            │
│       Connectors    Repositories     │
└──────────────────────────────────────┘
     │          │         │        │
  학식 사이트  도서관    LMS    u-SAINT
             (Pyxis)  (Canvas) (rusaint)
```

REST와 MCP 두 경로는 동일한 Service 레이어를 공유한다. MCP 도구가 별도 비즈니스 로직을 갖지 않는다.

### Connector 패턴

모든 외부 시스템 호출은 인터페이스로 추상화되어 있다.

```
MealConnector (interface)
├── MockMealConnector   — 고정 fixture 반환 (기본값)
└── RealMealConnector   — 실제 학식 사이트 Jsoup 파싱
```

`@ConditionalOnProperty`로 `ssuai.connector.meal=mock|real`에 따라 주입된다.  
기본값이 `mock`이므로 **외부 네트워크 없이** 빌드·테스트·로컬 실행이 가능하다.  
`application-prod.yml`이 프로덕션에서 `real`로 전환한다.

### LLM 채팅 엔진

`LlmChatService`는 OpenAI 호환 API 포맷으로 10개 프로바이더에 순차 Fallback한다.

```
사용자 메시지
    → LlmChatService
    → LlmProvider 체인 (OpenRouter → Mistral → Groq → ...)
        Rate limit / 오류 시 자동으로 다음 프로바이더로 전환
    → tool_call 발생 시
        공개 도구: McpSyncClient → 자체 /mcp 엔드포인트 (self-dogfood)
        개인 도구: 웹 세션 컨텍스트로 Service 직접 호출 (ThreadLocal)
    → 최종 응답
```

`LlmProvider` 인터페이스와 `OpenAiCompatibleProvider` 추상 클래스로 프로바이더를 추가·교체할 수 있다.

---

## 기술 스택

| 분류 | 기술 |
|------|------|
| 언어 / 프레임워크 | Java 21 + Kotlin 2.3, Spring Boot 4.0 |
| MCP 구현 | Spring AI 1.1 `spring-ai-starter-mcp-server-webmvc` (Streamable HTTP) |
| 외부 크롤링 | Jsoup 1.22 |
| u-SAINT 연동 | [rusaint](https://github.com/EATSTEAK/rusaint) — JNA 5.18로 Rust 라이브러리를 JVM에 연결 |
| 인증 | JJWT 0.13 (HS256), AES-256-GCM 세션 암호화 |
| 테스트 | JUnit 5, MockWebServer (OkHttp), WireMock 3 |
| API 문서 | springdoc-openapi 3 (dev 전용) |
| 인프라 | Oracle Cloud ARM64 + k3s + Traefik + ArgoCD + GitHub Container Registry |

---

## 보안 원칙

- **비밀 값 비로깅**: 비밀번호, upstream 쿠키, JWT, `mcp_session_id`, API 키는 로그에 기록하지 않는다.
- **인증 경계 분리**: 개인 데이터는 익명 캐시로 노출되지 않는다. 좌석 현황 캐시 키가 인증 여부를 포함한다.
- **읽기 전용 MCP 도구**: 20개 도구에 `readOnlyHint=true`, `logout_*` 2개에 `destructiveHint=true`로 표시한다.
- **상태 변경 없음**: 현재 서버는 조회 전용이다. 예약·취소 같은 write 도구는 `prepare` + `confirm` 2단계 확인 패턴으로 별도 구현된다 (ADR 0015).

---

## 로컬 개발

### 필요 도구

- JDK 21 (Temurin 권장)

### 실행 (mock 모드 — 외부 사이트 요청 없음)

```bash
./gradlew bootRun
```

`http://localhost:8080`

MCP Inspector로 도구 확인:

```bash
npx @modelcontextprotocol/inspector
# Transport: Streamable HTTP, URL: http://localhost:8080/mcp
```

### 실행 (실제 데이터)

```bash
cp .env.example .env
# .env에 API 키 및 커넥터 설정 입력
SSUAI_CONNECTOR_MEAL=real ./gradlew bootRun
```

### 검증

```bash
./gradlew test
./gradlew build
```

---

## 환경 변수

주요 변수는 `.env.example` 참조. 실제 값은 절대 저장소에 커밋하지 않는다.

| 변수 | 설명 |
|------|------|
| `SSUAI_JWT_SECRET` | HS256 서명 키 (32바이트 이상). 미설정 시 재시작마다 갱신 (dev 전용) |
| `SSUAI_CREDENTIAL_ENCRYPTION_KEY` | AES-GCM 세션 암호화 키 |
| `SSUAI_OPENROUTER_API_KEY`, `SSUAI_MISTRAL_API_KEY`, ... | LLM 프로바이더 키 (각 선택적) |
| `SSUAI_CONNECTOR_MEAL` | `mock` (기본) 또는 `real` |

---

## 문서

- [아키텍처](docs/architecture.md)
- [MCP 도구와 인증 흐름](docs/mcp-tools.md)
- [보안 정책](docs/security.md)
- [배포 runbook](deploy/README.md)

---

## 관련 프로젝트

**[ssuAI](https://github.com/hoeongj/ssuAI)** — 이 서버를 소비하는 Next.js 웹/앱 클라이언트

---

## 라이선스

MIT — [LICENSE](LICENSE)
