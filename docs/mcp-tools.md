# ssuMCP MCP Tools

## 1. 개요
ssuMCP server 는 숭실대학교 학생을 위한 캠퍼스 정보 조회 기능을 MCP(Model Context Protocol) tool 로 노출한다. Claude Desktop, Cursor, MCP inspector 같은 MCP client 는 이 서버에 붙어서 학식, 기숙사 식단, 캠퍼스 시설 정보를 대화 중에 조회할 수 있다.

외부 MCP client (Claude Desktop, Cursor 등) 도 `mcp_session_id` 기반 인증 세션을 통해 `get_my_schedule`, `get_my_grades`, `simulate_gpa`, `get_my_assignments`, `get_library_seat_status`, `recommend_library_seats`, `prepare_reserve_library_seat`, `wait_for_library_seat`, `get_library_wait_status`, `cancel_library_wait`, `get_my_library_seat`, `prepare_swap_library_seat`, `prepare_cancel_library_seat`, `get_my_library_loans`, `get_lms_dashboard` 를 직접 호출할 수 있다. 인증이 없으면 `AUTH_REQUIRED` 응답과 로그인 URL 을 반환한다.

MCP server 는 REST API 와 같은 Spring Boot 프로세스 안에서 실행된다. endpoint: `http://localhost:8080/mcp` (Streamable HTTP).

현재 transport 는 **Streamable HTTP** (MCP spec 2025-03-26, 단일 POST `/mcp` endpoint) 이며 기본 endpoint 는 `http://localhost:8080/mcp` 이다.

## compact 파라미터 vs ToolResultCompactor

| | compact 파라미터 | ToolResultCompactor |
|---|---|---|
| 활성화 | 호출자가 명시적으로 compact=true 전달 | 응답이 컨텍스트 한계 초과 시 자동 |
| 동작 방식 | 사전 정의된 요약 필드만 반환 (구조화) | 텍스트 길이로 맹목적 절단 |
| 적용 도구 | get_library_seat_status, get_recent_notices, search_notices, get_my_assignments | 모든 도구 |
| 사용 시점 | 요약 정보만 필요할 때 또는 컨텍스트 절약 목적 | 응답이 너무 클 때 자동 안전망 |

## 2. 노출 tool 목록

### 2a. 공개 tool (인증 불필요)

**학식·시설·도서관**

| tool name | 설명 | 주요 인자 | 응답 DTO |
| --- | --- | --- | --- |
| `get_today_meal` | 오늘 숭실대 캠퍼스 식당 메뉴 | `restaurant` (선택) | `MealResponse` |
| `get_meal_by_date` | 지정 날짜 캠퍼스 식당 메뉴 | `date` (yyyy-MM-dd), `restaurant` (선택) | `MealResponse` |
| `get_meal_weekly` | 캠퍼스 식당 주간 메뉴 | `restaurant` (선택) | `WeeklyMealResponse` |
| `get_dorm_weekly_meal` | 레지던스홀 기숙사 이번 주 메뉴 | 없음 | `WeeklyMealResponse` |
| `search_campus_facilities` | 캠퍼스 시설 검색 | `query` (선택) | `CampusFacilityListResponse` |
| `get_library_seat_catalog` | 도서관 정적 좌석·열람실 카탈로그. 내부 수집 노트(captureNotes)는 `debug=true`일 때만 포함 | `floor_code`, `room_code`, `include_layout`, `debug` (선택) | `LibrarySeatRoomCatalogResponse` |
| `search_library_book` | 도서관 소장 도서 검색 | `query`, `page`, `size` (선택) | `LibraryBookSearchResponse` |

**학사**

| tool name | 설명 | 주요 인자 | 응답 DTO |
| --- | --- | --- | --- |
| `get_academic_calendar` | 학사 일정 조회 | `year` (선택) | `AcademicCalendarResponse` |
| `find_academic_calendar_events` | 학사일정 필터 검색 | `year`, `month`, `keyword`, `limit` (선택) | `List<AcademicCalendarEvent>` |
| `classify_academic_question` | 학사 질문 의도 분류 및 추천 도구 반환 | `query` | `AcademicQuestionClassificationResponse` |
| `search_academic_policy_sources` | 공식 학칙·졸업·장학 출처 근거 검색. lexical + 임베딩 코사인을 RRF로 융합(하이브리드, 기본 OFF→env). 응답에 `liveRequested`·`liveExecuted`·`corpusType`(live/mixed/seed)·`embeddingUsed`·`fusionMethod`(rrf/lexical) 메타데이터 포함 | `query`, `category`, `limit`, `live` | `AcademicPolicySearchResponse` |
| `get_academic_policy_brief` | 공식 출처 근거 요약과 evidence 반환 | `query`, `category`, `limit`, `live` | `AcademicPolicyBriefResponse` |
| `check_scholarship_policy` | 장학 질문과 GPA·취득학점·입학연도·TOPIK 조건을 공식 근거와 대조하고 조건별 판정(`OK`/`FAIL`/`UNKNOWN`)과 전체 `decision` 반환 | `query`, `gpa`, `earnedCredits`, `admissionYear`, `topikLevel`, `internationalStudent`, `live`, `limit` | `ScholarshipPolicyCheckResponse` |
| `list_academic_policy_sources` | 학사 RAG 출처 URL·개정 이력·검증일 목록 | `category`, `live` (선택) | `List<AcademicPolicySource>` |

학사일정(`get_academic_calendar`/`find_academic_calendar_events`)은 메인 `ssu.ac.kr/학사/학사일정/`을
스크래핑한다(뉴스 포털 scatch에는 학사일정이 없다). `?years={year}`로 연도를 선택하며 게시 범위는
2019–2027이다. 실제 페이지는 일정마다 카테고리 라벨이 없으므로 real 데이터의 `category`는 항상 빈
문자열이다. 기간 일정("MM.DD ~ MM.DD" 행)은 `date`(시작)와 `endDate`(포함 종료)를 함께 제공하고,
하루짜리 일정은 `endDate=null`이다(연말을 넘는 범위는 종료 연도가 +1로 보정된다 — ADR 0075).
prod 활성화는 `ssuai.connector.academic-calendar=real`이며 미설정 시 mock 표본을 반환한다.
자세한 근거는 ADR 0054·0075.

학칙·졸업·장학 RAG는 정적 PDF 복사본을 source of truth로 두지 않는다. 서버는 시작 후와
주기 갱신 시 `rule.ssu.ac.kr` 및 `ssu.ac.kr` 공식 URL에서 원문을 가져와 인메모리
corpus를 갱신한다. 도구 응답에는 `live`, `fallbackUsed`, `revision`, `effectiveDate`,
`url`이 포함되며, 공식 사이트 장애 시에만 seed corpus로 degrade된다.

#### `check_scholarship_policy` 응답 구조

`check_scholarship_policy`는 근거만 반환하지 않고, 근거에서 명시 조건을 추출해 구조화 판정을 함께 반환한다. 필요한 학생 입력값이나 공식 기준이 없으면 추측하지 않고 `UNKNOWN` 조건과 `INSUFFICIENT_EVIDENCE` 전체 판정을 반환한다.

```json
{
  "query": "백마성적우수장학금 GPA 취득학점 gpa=3.2 earnedCredits=15",
  "inputFacts": ["gpa=3.2", "earnedCredits=15"],
  "decision": "NOT_ELIGIBLE",
  "matchedRequirements": [
    {
      "requirement": "GPA/평점 기준",
      "required": "GPA >= 3.5",
      "userValue": 3.2,
      "result": "FAIL"
    },
    {
      "requirement": "취득학점 기준",
      "required": "earnedCredits >= 15",
      "userValue": 15,
      "result": "OK"
    }
  ],
  "summary": "하나 이상의 장학 조건이 입력값과 맞지 않습니다.",
  "caveats": [
    "장학금은 학기별 공지, 등록 상태, 국가장학금 신청 여부, 중복 수혜 제한에 따라 달라질 수 있습니다."
  ],
  "evidence": [
    {
      "sourceId": "undergraduate-scholarship-guide",
      "title": "교내장학금 안내",
      "category": "scholarship",
      "url": "https://ssu.ac.kr/...",
      "revision": "official-page",
      "effectiveDate": null,
      "live": true,
      "fallbackUsed": false,
      "heading": "교내장학금 안내 #1",
      "snippet": "공식 근거 발췌...",
      "matchedTerms": ["백마성적우수장학금", "GPA", "취득학점"]
    }
  ]
}
```

판정 enum:

| 필드 | 값 | 의미 |
|---|---|---|
| `decision` | `ELIGIBLE` | 공식 근거에서 추출한 모든 조건이 입력값으로 충족됨 |
| `decision` | `NOT_ELIGIBLE` | 하나 이상의 조건이 실패함 |
| `decision` | `INSUFFICIENT_EVIDENCE` | 필요한 학생 입력값 또는 공식 기준이 부족해 판단 보류 |
| `matchedRequirements[].result` | `OK` | 해당 조건 충족 |
| `matchedRequirements[].result` | `FAIL` | 해당 조건 미충족 |
| `matchedRequirements[].result` | `UNKNOWN` | 학생값 또는 정책 기준 부족 |

**공지사항**

| tool name | 설명 | 주요 인자 | 응답 DTO |
| --- | --- | --- | --- |
| `get_recent_notices` | 학교 공지사항 최신 목록 | `category` (선택), `page` (선택), `compact` (선택) | `NoticeListResponse` 또는 `NoticeCompactListResponse` |
| `search_notices` | 공지사항 키워드 검색 | `keyword`, `category` (선택), `page` (선택), `compact` (선택) | `NoticeListResponse` 또는 `NoticeCompactListResponse` |
| `list_notice_categories` | 공지 카테고리 목록 반환 | 없음 | `NoticeCategoriesResponse` |
| `get_notice_detail` | 공지 URL 로 본문 전체 조회 | `url` | `NoticeDetailResponse` |
| `get_active_notices` | 진행중(마감 전) 공지 | `category` (선택) | `NoticeListResponse` |
| `get_department_notices` | 학과/부서 공지 | `department`, `page` (선택) | `NoticeListResponse` |

`category` 허용 값: `학사`, `장학`, `국제교류`, `외국인유학생`, `채용`, `비교과·행사`, `교원채용`, `교직`, `봉사`, `기타`

#### 공개 목록 wrapper의 빈 결과 신호

다음 공개 wrapper 응답은 기존 필드 뒤에 `empty`, `note`를 추가로 반환한다. `empty`는 주 목록이 null 또는 빈 배열이면 `true`이고, `note`는 빈 결과일 때만 짧은 한국어 안내를 제공한다. 결과가 있으면 `empty:false`, `note:null`이다.

| 응답 | 주 목록 | 빈 결과 `note` |
| --- | --- | --- |
| `NoticeListResponse` | `items` | `조건에 맞는 공지가 없어요.` |
| `AcademicPolicySearchResponse` | `evidence` | `관련 공식 출처를 찾지 못했어요.` |
| `CampusFacilityListResponse` | `facilities` | `검색 조건에 맞는 시설이 없어요.` |

호환성을 위해 기존 필드 이름·타입·순서는 유지한다. `NoticeCompactListResponse`와 `list_academic_policy_sources`, `get_academic_calendar`, `find_academic_calendar_events`처럼 raw `List`를 반환하는 도구에는 이 필드를 추가하지 않는다.

### 2b. MCP 인증 세션 관리 tool

인증 흐름 제어용 tool. `mcp_session_id` 를 발급받고, provider 별 로그인 URL 을 얻고, 세션을 정리한다.

| tool name | 설명 | 주요 인자 |
| --- | --- | --- |
| `get_auth_status` | 현재 MCP 세션의 provider 연결 상태 조회. `mcp_session_id`가 없어도 transport session으로 자동 탐색. `status` 필드: `OK`(세션 유효) / `INVALID_SESSION`(id가 있으나 만료·미해석) / `NO_SESSION`(id 없음) — 클라이언트가 "세션 만료"와 "미로그인"을 구분 | `mcp_session_id` (선택) |
| `start_auth` | 지정 provider 로그인 URL 생성. 없으면 새 세션 발급. 실행 시 현재 HTTP 연결의 transport session ID를 세션에 바인딩 | `provider` (SAINT/LMS/LIBRARY), `mcp_session_id` (선택) |
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

#### 세션 해석 — 3-tier 우선순위

서버는 모든 private 도구 호출에서 다음 순서로 인증 세션을 탐색한다:

1. **OAuth sub** (`Authorization: Bearer <JWT>` 헤더, OAuth RS 모드 활성 시): JWT의 `sub` 클레임 → DB에서 같은 sub로 바인딩된 세션 조회. LLM이 드랍할 수 없어 대화 간 신원 안정.
2. **Transport session ID** (`Mcp-Session-Id` 헤더): HTTP 연결 수준 식별자 → `start_auth` 시 바인딩됨. ChatGPT처럼 `mcp_session_id`를 드랍하는 클라이언트에서 재로그인 없이 세션 복원.
3. **Opaque mcp_session_id** (tool 인자): 기존 방식 fallback.

> **보안 참고:** `mcp_session_id` 는 secret 취급. 로그에 남기지 말고 공유하지 말 것. Transport 바인딩은 서버가 연결에 귀속시키므로 클라이언트가 임의 값으로 타인 세션에 접근 불가. 세션은 DB에 영속 저장되며 서버 재시작 후에도 유지된다 (7일 TTL).

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
  "provider": "SAINT",
  "mcpSessionId": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
  "loginUrl": null,
  "expiresAt": null,
  "message": null,
  "data": { ... }
}
```

> **응답 식별 필드 (ADR 0056, 보안 remediation)** — private tool OK 응답의 `provider`는 응답을 서빙한 provider(`SAINT`/`LMS`/`LIBRARY`)를 담고, `mcpSessionId`는 입력 인자를 그대로 echo하지 않고 **실제로 해소된 canonical 세션 id**를 담는다. 이전에는 `provider:null` 하드코딩 + 입력 인자 echo였는데, 이것이 "가짜 mcp_session_id가 먹혔다"는 착시(ChatGPT 오진)를 유발해 함께 고쳤다. 디버깅·감사·프론트 분기에 신뢰 가능한 값이다.

> **메시지 필드 (ADR 0045)** — 모든 private tool 응답은 두 청중용 메시지를 함께 제공한다.
> - `userMessage` — 사용자 노출용 짧은 한국어 (예: AUTH_REQUIRED 시 "로그인이 필요해요. 아래 링크를 브라우저에서 열어 …: {loginUrl}").
> - `developerMessage` — 에이전트/LLM용 verbose 절차·코드.
> - `message` — `developerMessage`의 하위호환 별칭(바이트 불변, 기존 ChatGPT/Claude Desktop 클라이언트용). 신규 UI는 `userMessage` 표시 권장.

| tool name | 설명 | 필요 provider | 인자 |
| --- | --- | --- | --- |
| `get_my_schedule` | 시간표 조회. 과목별 meeting 슬롯(요일·교시·강의실) 그룹핑. year·term 파라미터로 특정 학기 지정 가능 | SAINT | `year` (선택), `term` 1=봄·2=여름·3=가을·4=겨울 (선택), `mcp_session_id` |
| `get_my_grades` | 누적 GPA + 학기별 과목 수 | SAINT | `mcp_session_id` |
| `get_my_chapel_info` | 채플 출석 현황 | SAINT | `year` (선택), `semester` (선택), `mcp_session_id` |
| `check_graduation_requirements` | 졸업 요건 충족 여부 및 잔여 학점 | SAINT | `mcp_session_id` |
| `evaluate_graduation_with_policy` | u-SAINT 졸업요건 상태와 공식 학칙·졸업 근거를 함께 반환. 응답 `mismatchWarnings`는 사정표와 정책 근거의 숫자가 다를 때(총 이수학점·채플) 확인 안내를 담는다(없으면 빈 리스트, ADR 0073) | SAINT | `question`, `live` (선택), `mcp_session_id` |
| `get_my_scholarships` | 장학금 수혜 내역 | SAINT | `year` (선택), `mcp_session_id` |
| `simulate_gpa` | 누적 GPA 시뮬레이션. 예상 학점·목표 GPA 입력 시 필요 평균 또는 예상 GPA 반환 | SAINT | `plannedCredits`, `plannedGradePointAverage` (선택), `targetGpa` (선택), `mcp_session_id` |
| `get_my_assignments` | 현재 학기 미제출 과제·퀴즈 목록. 비어 있으면 `message`로 안내 | LMS | `mcp_session_id`, `compact` (선택) |
| `get_my_lms_terms` | 사용자의 LMS 등록 학기 목록 조회 | LMS | `mcp_session_id` |
| `get_lms_dashboard` | 미제출 과제·학사일정·공지사항을 모아보는 대시보드 | LMS | `mcp_session_id`, `term_id` (선택) |
| `get_my_lms_courses` | 수강 과목을 **각 과목의 비영상 자료(파일 수·용량·확장자별 그룹·content_id)와 함께** 한 번에 조회 (전 과목 materials를 묶어 반환 → 로그인 직후 과목+파일을 한 번에 표시, content_id로 바로 prepare 가능). 전 과목 자료 fetch와 비신뢰 크기의 HEAD 보정 때문에 응답이 다소 느림 | LMS | `mcp_session_id`, `term_id` (선택) |
| `get_my_lms_materials` | 특정 과목들의 비영상 주차학습 자료 목록 조회 (course_ids 지정). `get_my_lms_courses`가 전 과목을 이미 반환하므로 보통 불필요, 특정 과목 재조회용. 비신뢰 크기는 HEAD로 보정 | LMS | `mcp_session_id`, `course_ids`, `term_id` (선택) |
| `prepare_lms_material_export` | 선택 자료 내보내기 준비 (용량/개수 제한 검증 및 ActionAudit 생성). content_id는 `get_my_lms_courses` 응답에 포함됨 | LMS | `mcp_session_id`, `content_ids`, `term_id` (선택) |
| `confirm_lms_material_export` | 내보내기 최종 승인 및 다운로드 링크 발급 (기본 20분, `SSUAI_LMS_EXPORT_DOWNLOAD_TTL` 설정 가능) | LMS | `mcp_session_id` |
| `get_library_seat_status` | 도서관 층별 좌석 현황 (room-level) | LIBRARY | `floor` (2/5/6), `mcp_session_id`, `compact` (선택) |
| `get_library_available_seats` | 전체 7개 열람실 live per-seat 가용 좌석 요약. externalSeatId 목록 포함 | LIBRARY | `mcp_session_id` |
| `get_room_available_seats` | 특정 열람실 per-seat 상태 목록 (available/occupied/away/inactive, remainingTime) | LIBRARY | `room_id`, `mcp_session_id` |
| `recommend_library_seats` | 선호도 기반 좌석 추천. live availability와 정적 좌석 카탈로그를 결합. 대학원 전용 열람실은 기본 제외(`excludedRooms`로 보고), `include_graduate_only=true`로 포함 가능(경고 동반) | LIBRARY | `floor`, `window`, `outlet`, `standing`, `edge`, `quiet`, `near_entrance`, `include_graduate_only`, `limit`, `mcp_session_id` |
| `prepare_reserve_library_seat` | 좌석 예약 준비. 실제 예약은 `confirm_action`이 immediate intent를 만들어 worker가 실행 | LIBRARY | `mcp_session_id`, `seat_id` |
| `wait_for_library_seat` | 조건에 맞는 좌석이 열릴 때까지 intent queue에 등록. 등록 호출 자체가 동의이며, worker가 이후 자율 예약 가능 | LIBRARY | `mcp_session_id`, `floor`, `room_ids`, `seat_attributes`, `target_seat_id`, `expires_in_minutes` (선택) |
| `get_library_wait_status` | 최신 좌석 대기 intent 상태 조회 | LIBRARY | `mcp_session_id` |
| `cancel_library_wait` | 아직 예약 실행 전인 활성 좌석 대기 intent 취소 | LIBRARY | `mcp_session_id` |
| `get_my_library_seat` | 현재 예약 좌석 조회 (chargeId·roomName·seatCode·시간 반환) | LIBRARY | `mcp_session_id` |
| `prepare_swap_library_seat` | 기존 예약에서 새 좌석으로 이석 준비. 실제 이석은 `confirm_action` 필요 | LIBRARY | `mcp_session_id`, `seat_id` |
| `prepare_cancel_library_seat` | 현재 예약 반납 준비. 실제 반납은 `confirm_action` 필요 | LIBRARY | `mcp_session_id` |
| `get_my_library_loans` | 도서관 대출 현황 (반납 기한 포함). 대출이 없으면 `message`로 안내 | LIBRARY | `mcp_session_id` |

### LMS 자료 크기 의미

- `sizeBytes`: 양수 PDF는 LearningX 값을 사용하고, 비-PDF 또는 null/0 값은 인증된 다운로드 URL의 HEAD `Content-Length`로 보정한다.
- HEAD 실패·비-2xx·`Content-Length` 부재 시 `sizeBytes`는 `null`이다. 원래 API의 `0` 또는 센티널 값을 노출하지 않는다.
- 과목 `totalBytes`와 내보내기 예상 용량은 보정 후 알려진(non-null) 크기만 합산한다. 따라서 unknown 파일이 있으면 실제 총량보다 작을 수 있다.
- 상세 결정과 운영 증거(ZIP 4개가 모두 `64238`)는 [ADR 0044](adr/0044-lms-file-size-head-correction.md)를 참고한다.

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
4. get_library_seat_status(floor=2, mcpSessionId), recommend_library_seats(...), 또는
   get_my_library_loans(mcpSessionId) 재호출 → status: "OK", data: {...}
```

## 3. 서버 띄우기
Windows:

```powershell
.\gradlew.bat bootRun
```

macOS / Linux / WSL:

```bash
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
먼저 `ssuMCP` 저장소 루트에서 서버를 켠다.

```powershell
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
5. tool 52개가 보이는지 확인한다. (공개 20개 + 인증 관리 4개 + 개인 28개)

보여야 하는 tool 이름:
```
공개(학식·시설·도서관):
  get_today_meal, get_meal_by_date, get_dorm_weekly_meal,
  get_meal_weekly, search_campus_facilities, get_library_seat_catalog,
  search_library_book

공개(학사):
  get_academic_calendar, find_academic_calendar_events,
  classify_academic_question, search_academic_policy_sources,
  get_academic_policy_brief, check_scholarship_policy,
  list_academic_policy_sources

공개(공지사항):
  get_recent_notices, search_notices, list_notice_categories,
  get_notice_detail, get_active_notices, get_department_notices

인증 관리:
  get_auth_status, start_auth, logout_provider, logout_all

개인(SAINT):
  get_my_schedule, get_my_grades, get_my_chapel_info,
  check_graduation_requirements, evaluate_graduation_with_policy,
  get_my_scholarships,
  simulate_gpa

개인(LMS):
  get_my_assignments, get_my_lms_terms, get_lms_dashboard,
  get_my_lms_courses, get_my_lms_materials,
  prepare_lms_material_export, confirm_lms_material_export,
  export_all_lms_materials
개인(LIBRARY):
  get_library_seat_status, get_library_available_seats, get_room_available_seats,
  recommend_library_seats, get_my_library_loans,
  prepare_reserve_library_seat, prepare_cancel_library_seat,
  get_my_library_seat, prepare_swap_library_seat,
  wait_for_library_seat, get_library_wait_status, cancel_library_wait,
  confirm_action
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

서버가 떠 있지 않은 상태. `curl http://localhost:8080/actuator/health` 에서 `UP` 이 나오지 않으면 ssuMCP repository root에서 `./gradlew bootRun` (`.\gradlew.bat bootRun` on Windows)을 다시 실행한다.

### Tool 이 안 보임

MCP inspector 의 `Tools` 탭에서 `List Tools` 를 다시 클릭한다. Claude Desktop 이나 Cursor 는 설정 저장 후 client 재시작이 필요할 수 있다.

### Private tool 이 `AUTH_REQUIRED` 를 반환함

`mcp_session_id` 가 없거나 해당 provider 가 연결되지 않은 경우다.

```
1. start_auth("SAINT") 호출 → loginUrl 확인
2. 브라우저에서 loginUrl 열어 로그인
3. 반환된 mcpSessionId 로 private tool 재호출
```

MCP login URL의 외부 origin을 별도로 지정하려면
`ssuai.auth.mcp-api-base-url` (= `SSUAI_MCP_API_BASE_URL`)을 설정한다.
비어 있으면 `ssuai.auth.api-base-url` (= `SSUAI_API_BASE_URL`, 로컬 기본값
`http://localhost:8080`)을 사용한다.

## 8. 위험·write tool 정책
현재 노출된 52개 MCP tool 중 읽기 전용 40개, 쓰기/상태 변경 12개(그중 destructiveHint 3개: `logout_provider`, `logout_all`, `cancel_library_wait`)다. 세션 상태를 변경하는 도구는 `start_auth`, `logout_provider`, `logout_all`이고, 학교 시스템 상태를 바꾸는 도서관
좌석 즉시 action은 반드시 `prepare_*` + `confirm_action` 2단계로만 실행된다.
장기 대기형 `wait_for_library_seat`는 예외적으로 등록 호출 자체가 동의이며,
응답과 tool description에 "좌석이 열리면 worker가 자율 예약할 수 있음"을 명시한다.

### Phase 4 flagship — 도서관 좌석 자동 예약

ssuAI 의 가장 중요한 write tool 범위는 도서관 좌석 예약/이석/반납이다.
현재 backend MCP contract는 아래 도구로 배포되어 있다.

| 단계 | 도구 |
| --- | --- |
| 추천 | `recommend_library_seats` |
| 예약 준비 | `prepare_reserve_library_seat` |
| 대기 등록 | `wait_for_library_seat` |
| 대기 상태 조회 | `get_library_wait_status` |
| 대기 취소 | `cancel_library_wait` |
| 현재 예약 확인 | `get_my_library_seat` |
| 이석 준비 | `prepare_swap_library_seat` |
| 반납 준비 | `prepare_cancel_library_seat` |
| 최종 실행 | `confirm_action` |

좌석 예약 흐름의 설계·결정 근거는 ADR 0047(per-seat 분산 락)·0048(intent SSE)·0055(confirm 상태머신)·0064(swap 보상)를 참조한다.

### Write tool 공통 정책

- 모든 write tool 은 **`prepare_X` + 공용 `confirm_action(mcp_session_id, action_id?)`** 두 개의 MCP tool 로 노출한다. `action_id` 는 선택값이며 `prepare_X` 응답의 `actionId` 를 넘기면 특정 액션을 지정 확정한다(하위 호환: 생략 가능).
- `prepare_X` 가 정확한 dry-run 문구를 반환하고 `action_audit` 에 `PENDING` row 를 기록한다. 같은 owner의 직전 PENDING row 들은 이때 `SUPERSEDED` 로 전이되어 owner당 활성 PENDING은 최대 1건이 된다(ADR 0055, stale action 방지).
- `confirm_action` 은 (지정 시) `action_id` 의 소유권·PENDING·미만료를 행 락 WHERE 절(`id AND student_id AND status=PENDING`)로 재검증해 그 액션만 실행하고, 타 owner·미존재·이미 처리/만료는 거부하며 다른 액션으로 폴백하지 않는다. 생략 시 활성 PENDING 0건→안내, 다수→action_id 요구 거부, 1건→그 액션 확정. 이후 row-lock claim → 실행 → audit 상태 전이를 담당한다. 예약 action은 PR2부터 직접 Pyxis를 호출하지 않고 immediate `library_reservation_intents` row를 만든 뒤 worker 결과를 최대 약 8초 폴링한다. 반납/이석 action은 아직 직접 upstream 호출 경로다.
- Pyxis가 반납 단계에서 `warning.smuf.notAvailableState`를 반환하면 미입실 배정 상태로 해석한다.
  `confirm_action`은 generic 오류 대신 입실 후 재시도/자동취소 가능성/기존 예약 유지(이석)를 안내한다.
- 예외: `wait_for_library_seat`는 오래 기다리는 intent 등록 도구라서 prepare gate를 두지 않는다. 등록 호출이 동의이고, 실행 단위는 `library_reservation_intents` row다. 세부 설계는 [ADR 0022](adr/0022-library-reservation-intent-queue.md)를 따른다.
- `pending_action_id` TTL 은 5분.
- 비밀번호, session cookie, token, 학생 개인정보, upstream HTML 은 audit row 와 log 어디에도 포함하지 않는다 (`docs/security.md` §4 / §5).
- **`/api/chat` 챗봇은 read-only (ADR 0060)** — 백엔드 `/api/chat`(`LlmChatService`)은 read-only Q&A surface다. 챗 LLM에는 아래 13종(`CHAT_EXCLUDED_TOOLS`)이 도구 discovery에서 제외되고 `executeToolCall`에서도 거부되므로, 챗 LLM은 좌석 write/LMS export/confirm을 실행할 수 없다. write 실행은 HITL 확인이 있는 ssuAgent 흐름에만 속한다. (외부 MCP 클라이언트가 `mcp_session_id`로 직접 호출하는 경로에는 이 제외가 적용되지 않으며, 그 경로의 write는 `prepare_*` + `confirm_action` 2단계 게이트로 보호된다.)
  - auth 4종: `start_auth`, `get_auth_status`, `logout_provider`, `logout_all`
  - write/confirm 9종: `confirm_action`, `wait_for_library_seat`, `cancel_library_wait`, `prepare_reserve_library_seat`, `prepare_cancel_library_seat`, `prepare_swap_library_seat`, `prepare_lms_material_export`, `confirm_lms_material_export`, `export_all_lms_materials`

### export_all_lms_materials
**Type**: Write (creates pending action) | **Auth**: LMS  
**Description**: Collects all course materials for the current/specified term automatically and returns a preview.
No need to call `get_my_lms_courses` or `get_my_lms_materials` first.
After reviewing the preview, call `confirm_lms_material_export` to receive the download link.

**Input**:
| Parameter | Type | Required | Description |
|---|---|---|---|
| mcp_session_id | string | yes | MCP session with LMS linked |
| term_id | number | no | Term ID; defaults to current active term |

**Output**: `LmsExportPrepareResponse`
- `courseCount`: number of courses included
- `totalFileCount`: total accepted file count
- `totalBytes`: total accepted bytes
- `courseMaterials`: per-course file list grouped by extension
- `exclusions`: files excluded (over-limit or unsupported format)
- `message`: human-readable summary including term name

**Notes**: Videos and audio files are always excluded. File count and byte limits apply (same as prepare_lms_material_export).

