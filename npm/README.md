# ssumcp

숭실대학교 캠퍼스 정보를 **MCP(Model Context Protocol) 표준 도구**로 제공하는 공개 서버.

Claude Desktop, Cursor 및 그 외 MCP 클라이언트에서 사용할 수 있습니다.

[![CI](https://github.com/hoeongj/ssuMCP/actions/workflows/ci.yml/badge.svg)](https://github.com/hoeongj/ssuMCP/actions/workflows/ci.yml)

---

## 빠른 시작 (로컬 실행)

Java 21 이상 필요 ([Temurin 다운로드](https://adoptium.net))

```bash
npx ssumcp
```

서버가 `http://localhost:8080`에서 실행됩니다.

---

## MCP 클라이언트 연결

### 원격 서버 (설치 없이 바로 사용)

```json
{
  "mcpServers": {
    "ssuMCP": {
      "url": "https://ssumcp.duckdns.org/mcp"
    }
  }
}
```

### 로컬 서버 (npx로 직접 실행)

```json
{
  "mcpServers": {
    "ssuMCP": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

**Claude Desktop** 설정 파일 위치:
- Windows: `%APPDATA%\Claude\claude_desktop_config.json`
- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`

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

1. `start_auth(provider="SAINT")` 호출 → `loginUrl`과 `mcpSessionId` 수신
2. `loginUrl`을 브라우저에서 열어 숭실대 계정으로 로그인
3. 이후 개인 도구 호출 시 `mcp_session_id` 파라미터 전달

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

---

## 환경 변수

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `PORT` | `8080` | 서버 포트 |

---

## 관련 링크

- [GitHub 저장소](https://github.com/hoeongj/ssuMCP)
- [ssuAI 웹 클라이언트](https://github.com/hoeongj/ssuAI)

---

## 라이선스

MIT
