# ssuAI MCP Tools

## 1. 개요
ssuAI MCP server 는 숭실대학교 학생을 위한 캠퍼스 정보 조회 기능을 MCP(Model Context Protocol) tool 로 노출한다. Claude Desktop, Cursor, MCP inspector 같은 MCP client 는 이 서버에 붙어서 학식, 기숙사 식단, 캠퍼스 시설 정보를 대화 중에 조회할 수 있다.

**Task 18 이후**: 외부 MCP client (Claude Desktop, Cursor 등) 도 `mcp_session_id` 기반 인증 세션을 통해 `get_my_schedule`, `get_my_grades`, `get_my_assignments`, `get_my_library_loans` 를 직접 호출할 수 있다. 인증이 없으면 `AUTH_REQUIRED` 응답과 로그인 URL 을 반환한다.

MCP server 는 별도 프로세스가 아니라 기존 ssuAI Spring Boot backend 안에서 REST API 와 함께 실행된다. backend 를 띄우면 REST endpoint 와 MCP endpoint 가 같은 JVM 안에서 같이 살아난다.

현재 transport 는 **Streamable HTTP** (MCP spec 2025-03-26, 단일 POST `/mcp` endpoint) 이며 기본 endpoint 는 `http://localhost:8080/mcp` 이다.

## 2. 노출 tool 목록

### 2a. 공개 tool (인증 불필요)

**학식·시설·도서관**

| tool name | 설명 | 주요 인자 | 응답 DTO |
| --- | --- | --- | --- |
| `get_today_meal` | 오늘 숭실대 캠퍼스 식당 메뉴 | `restaurant` (선택) | `MealResponse` |
| `get_meal_by_date` | 지정 날짜 캠퍼스 식당 메뉴 | `date` (yyyy-MM-dd), `restaurant` (선택) | `MealResponse` |
| `get_dorm_weekly_meal` | 레지던스홀 기숙사 이번 주 메뉴 | 없음 | `WeeklyMealResponse` |
| `search_campus_facilities` | 캠퍼스 시설 검색 | `query` (선택) | `CampusFacilityListResponse` |
| `get_library_seat_status` | 도서관 층별 좌석 현황 | `floor` (정수: -1~6) | `LibrarySeatStatusResponse` |
| `search_library_book` | 도서관 소장 도서 검색 | `query`, `page`, `size` (선택) | `LibraryBookSearchResponse` |

**공지사항**

| tool name | 설명 | 주요 인자 | 응답 DTO |
| --- | --- | --- | --- |
| `get_recent_notices` | 학교 공지사항 최신 목록 | `category` (선택), `page` (선택) | `NoticeListResponse` |
| `search_notices` | 공지사항 키워드 검색 | `keyword`, `category` (선택), `page` (선택) | `NoticeListResponse` |
| `list_notice_categories` | 공지 카테고리 목록 반환 | 없음 | `NoticeCategoriesResponse` |
| `get_notice_detail` | 공지 URL 로 본문 전체 조회 | `url` | `NoticeDetailResponse` |
| `get_active_notices` | 진행중(마감 전) 공지 | `category` (선택) | `NoticeListResponse` |
| `get_department_notices` | 학과/부서 공지 | `department`, `page` (선택) | `NoticeListResponse` |

`category` 허용 값: `학사`, `장학`, `국제교류`, `외국인유학생`, `채용`, `비교과·행사`, `교원채용`, `교직`, `봉사`, `기타`

### 2b. MCP 인증 세션 관리 tool

인증 흐름 제어용 tool. `mcp_session_id` 를 발급받고, provider 별 로그인 URL 을 얻고, 세션을 정리한다.

| tool name | 설명 | 주요 인자 |
| --- | --- | --- |
| `get_auth_status` | 현재 MCP 세션의 provider 연결 상태 조회 | `mcp_session_id` (선택) |
| `start_auth` | 지정 provider 로그인 URL 생성. 없으면 새 세션 발급 | `provider` (SAINT/LMS/LIBRARY), `mcp_session_id` (선택) |
| `logout_provider` | 특정 provider 연결 해제 | `provider`, `mcp_session_id` |
| `logout_all` | MCP 세션 전체 삭제 | `mcp_session_id` |

`start_auth` 응답 예시:
```json
{
  "status": "LOGIN_STARTED",
  "provider": "SAINT",
  "mcpSessionId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "loginUrl": "https://api.ssuai.example/api/mcp/auth/saint/start?state=...",
  "expiresAt": "2026-05-18T12:10:00Z",
  "message": "Open loginUrl in a browser to complete login, then call private tools with mcpSessionId."
}
```

### 2c. Private tool (인증 필요)

`mcp_session_id` 를 인자로 받는다. 해당 provider 가 연결되어 있으면 `status: "OK"` 와 데이터를 반환하고, 아니면 `status: "AUTH_REQUIRED"` 와 로그인 URL 을 반환한다.

**AUTH_REQUIRED 응답 예시:**
```json
{
  "status": "AUTH_REQUIRED",
  "provider": "SAINT",
  "mcpSessionId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "loginUrl": "https://api.ssuai.example/api/mcp/auth/saint/start?state=...",
  "expiresAt": "2026-05-18T12:10:00Z",
  "message": "Authentication required. Open loginUrl in a browser, then retry with the same mcpSessionId.",
  "data": null
}
```

**OK 응답 예시 (get_my_schedule):**
```json
{
  "status": "OK",
  "provider": null,
  "mcpSessionId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "loginUrl": null,
  "expiresAt": null,
  "message": null,
  "data": { ... }
}
```

| tool name | 설명 | 필요 provider | 인자 |
| --- | --- | --- | --- |
| `get_my_schedule` | 전 학기 시간표 (과목·요일·교시·강의실) | SAINT | `mcp_session_id` |
| `get_my_grades` | 누적 GPA + 학기별 과목 수 | SAINT | `mcp_session_id` |
| `get_my_chapel_info` | 채플 출석 현황 | SAINT | `year` (선택), `semester` (선택), `mcp_session_id` |
| `check_graduation_requirements` | 졸업 요건 충족 여부 및 잔여 학점 | SAINT | `mcp_session_id` |
| `get_my_scholarships` | 장학금 수혜 내역 | SAINT | `year` (선택), `mcp_session_id` |
| `get_my_assignments` | 현재 학기 미제출 과제·퀴즈 목록 | LMS | `mcp_session_id` |
| `get_my_library_loans` | 도서관 대출 현황 (반납 기한 포함) | LIBRARY | `mcp_session_id` |

**보안 참고:** `mcp_session_id` 는 secret 취급. 로그에 남기지 말고 공유하지 말 것. 응답 JSON 에 studentId/loginId/principalKey 가 포함되지 않는다.

### SAINT 로그인 흐름 (SAINT / LMS 공통)

```
1. start_auth("SAINT", null)
   → mcpSessionId 발급 + loginUrl 반환
2. 브라우저에서 loginUrl 열기 → SmartID 로그인
3. 로그인 완료 페이지 확인
4. get_my_schedule(mcpSessionId) 재호출 → status: "OK", data: {...}
```

SAINT 로그인 성공 시 LMS 도 best-effort 로 자동 연결된다.

### 도서관 로그인 흐름

```
1. start_auth("LIBRARY", null)
   → mcpSessionId 발급 + loginUrl 반환 (프론트 도서관 로그인 페이지)
2. 브라우저에서 loginUrl 열기 → 학번/비밀번호 입력
3. "로그인이 완료되었습니다." 확인
4. get_my_library_loans(mcpSessionId) 재호출 → status: "OK", data: {...}
```

## 3. 서버 띄우기
Windows:

```powershell
cd backend
.\gradlew.bat bootRun
```

macOS / Linux / WSL:

```bash
cd backend
./gradlew bootRun
```

기본 profile 은 `dev` 이고, `dev` 기본 connector 는 `mock` 이다.

```yaml
ssuai:
  connector:
    meal: mock
    dorm-meal: mock
    library-seat: mock
    library-book: mock
    library-loans: mock
    saint-schedule: mock # mock | real | rusaint
    saint-grades: mock # mock | real | rusaint
    lms-assignments: mock
```

서버가 정상 기동하면 다음 URL 이 살아 있어야 한다.

```text
REST health: http://localhost:8080/actuator/health
MCP:         http://localhost:8080/mcp
```

## 4. MCP inspector 로 검증
먼저 backend 를 켠다.

```powershell
cd backend
.\gradlew.bat bootRun
```

다른 터미널에서 inspector 를 실행한다.

```bash
npx @modelcontextprotocol/inspector
```

브라우저 UI 에서:

1. Transport 로 `Streamable HTTP` 를 선택한다.
2. URL 에 `http://localhost:8080/mcp` 를 입력한다.
3. Connect 를 누른다.
4. `Tools` 탭에서 `List Tools` 를 누른다.
5. tool 23개가 보이는지 확인한다.

보여야 하는 tool 이름:
```
공개(학식·시설·도서관):
  get_today_meal, get_meal_by_date, get_dorm_weekly_meal,
  search_campus_facilities, get_library_seat_status, search_library_book

공개(공지사항):
  get_recent_notices, search_notices, list_notice_categories,
  get_notice_detail, get_active_notices, get_department_notices

인증 관리:
  get_auth_status, start_auth, logout_provider, logout_all

개인(SAINT):
  get_my_schedule, get_my_grades, get_my_chapel_info,
  check_graduation_requirements, get_my_scholarships

개인(LMS): get_my_assignments
개인(LIBRARY): get_my_library_loans
```

`start_auth` 호출 예시:
```json
{"provider": "SAINT"}
```

`get_my_schedule` 호출 예시 (mcp_session_id 없으면 AUTH_REQUIRED):
```json
{"mcp_session_id": null}
```

## 5. Claude Desktop 등록
설정 파일 위치:

| OS | 설정 파일 |
| --- | --- |
| Windows | `%APPDATA%\Claude\claude_desktop_config.json` |
| macOS | `~/Library/Application Support/Claude/claude_desktop_config.json` |

```json
{
  "mcpServers": {
    "ssuai": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

설정 파일을 수정한 뒤에는 Claude Desktop 을 완전히 종료했다가 다시 시작한다.

`mcp-proxy` 어댑터가 필요한 경우:
```json
{
  "mcpServers": {
    "ssuai": {
      "command": "mcp-proxy",
      "args": ["http://localhost:8080/mcp"]
    }
  }
}
```

## 6. Cursor 등록

```json
{
  "mcpServers": {
    "ssuai": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

## 7. 트러블슈팅

### 포트 8080 이 이미 점유됨

```powershell
.\gradlew.bat bootRun --args="--server.port=8081"
```

이 경우 MCP URL 도 `http://localhost:8081/mcp` 로 바꾼다.

### `Connection refused`

backend 가 떠 있지 않은 상태. `curl http://localhost:8080/actuator/health` 에서 `UP` 이 나오지 않으면 `backend/` 에서 `bootRun` 을 다시 실행한다.

### Tool 이 안 보임

MCP inspector 의 `Tools` 탭에서 `List Tools` 를 다시 클릭한다. Claude Desktop 이나 Cursor 는 설정 저장 후 client 재시작이 필요할 수 있다.

### Private tool 이 `AUTH_REQUIRED` 를 반환함

`mcp_session_id` 가 없거나 해당 provider 가 연결되지 않은 경우다.

```
1. start_auth("SAINT") 호출 → loginUrl 확인
2. 브라우저에서 loginUrl 열어 로그인
3. 반환된 mcpSessionId 로 private tool 재호출
```

`ssuai.auth.api-base-url` (= `SSUAI_AUTH_API_BASE_URL` 환경 변수) 가 설정되지 않으면 loginUrl 이 생성되지 않는다. 로컬 개발 시 `http://localhost:8080` 으로 설정한다.

## 8. 위험·write tool 정책 (향후)
현재 노출된 23개 MCP tool 중 20개는 read-only, 3개 (`start_auth`, `logout_provider`, `logout_all`) 는 세션 상태를 변경하는 write tool 이다. 학교 시스템 상태 (예약·제출 등) 를 직접 변경하는 tool 은 아직 없다.

### Phase 4 flagship — 도서관 좌석 자동 예약

ssuAI 의 가장 중요한 write tool 은 **`reserve_library_seat`** 이다. 자세한 사용자 시나리오는 [`docs/vision.md`](vision.md) §3.4 참고.

### Write tool 공통 정책

- 모든 write tool 은 **`prepare_X` + 공용 `confirm_action(pending_action_id)`** 두 개의 MCP tool 로 노출한다.
- `prepare_X` 가 정확한 dry-run 문구를 반환하고 `action_audit` 에 `PREPARED` row 를 기록한다.
- `confirm_action` 은 lookup → in-process lock → 실제 upstream 호출 → audit 상태 전이만 담당한다.
- `pending_action_id` TTL 은 5분.
- 비밀번호, session cookie, token, 학생 개인정보, upstream HTML 은 audit row 와 log 어디에도 포함하지 않는다 (`docs/security.md` §4 / §5).
