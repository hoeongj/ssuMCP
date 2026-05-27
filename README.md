# ssuMCP - 숭실대학교 MCP 서버

[![CI](https://github.com/hoeongj/ssuMCP/actions/workflows/ci.yml/badge.svg)](https://github.com/hoeongj/ssuMCP/actions/workflows/ci.yml)

숭실대학교 캠퍼스 정보를 **MCP(Model Context Protocol) 표준 도구**로 제공하는 공개 서버.

Claude Desktop, Cursor 및 그 외 MCP 클라이언트에서 아래 URL 하나로 연결할 수 있습니다.

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

Claude Desktop 재시작 후 채팅창 도구 아이콘에서 도구 목록을 확인합니다.

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
| `get_library_seat_status` | 중앙도서관 층별 좌석 현황 |
| `search_library_book` | 중앙도서관 도서 검색 |
| `get_recent_notices` | 학교 공지사항 최신 목록 |
| `search_notices` | 공지사항 키워드 검색 |
| `list_notice_categories` | 공지 카테고리 목록 |
| `get_notice_detail` | 공지 본문 전체 조회 |
| `get_active_notices` | 진행중(마감 전) 공지 |
| `get_department_notices` | 학과/부서별 공지 |

---

## 개인 도구 (u-SAINT / LMS / 도서관 인증 필요)

**인증 흐름:**

1. `start_auth(provider="SAINT")` 호출 후 `loginUrl`과 `mcpSessionId`를 받습니다.
2. `loginUrl`을 브라우저에서 열어 숭실대 계정으로 로그인합니다.
3. 이후 개인 도구 호출 시 `mcp_session_id` 파라미터를 전달합니다.

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
| `get_my_library_loans` | LIBRARY | 도서관 대출 현황 |

---

## 기술 스택

- **언어/프레임워크**: Kotlin + Java 21, Spring Boot 4.x
- **MCP 구현**: Spring AI `spring-ai-starter-mcp-server-webmvc` (Streamable HTTP)
- **크롤링**: Jsoup
- **u-SAINT 연동**: [rusaint](https://github.com/EATSTEAK/rusaint) FFI (Rust to JVM via JNA)
- **인프라**: Oracle Cloud ARM64 + k3s + Traefik + GitHub Container Registry

---

## 로컬 개발

### 필요 도구

- JDK 21 (Temurin 권장)

### 실행 (mock 모드 - 외부 사이트 요청 없음)

```bash
./gradlew bootRun
```

`http://localhost:8080`

MCP Inspector로 도구 확인:

```bash
npx @modelcontextprotocol/inspector
# Transport: Streamable HTTP, URL: http://localhost:8080/mcp
```

### 실행 (real 데이터)

```bash
cp .env.example .env
# .env에 API 키 입력
SSUAI_CONNECTOR_MEAL=real ./gradlew bootRun
```

---

## 라이선스

MIT - [LICENSE](LICENSE)

---

## 관련 프로젝트

- **[ssuAI](https://github.com/hoeongj/ssuAI)** - 이 MCP 서버를 소비하는 Next.js 웹/앱 클라이언트
