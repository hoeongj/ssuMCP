# Library Seat Agent Completion Plan

## 현재 상태

도서관 좌석 자동 에이전트의 backend MCP action path는 배포되어 있다.

이미 가능한 범위:

- `get_library_seat_status`: 도서관 층별 좌석 현황 조회
- `get_library_seat_catalog`: 정적 좌석 카탈로그 조회
- `recommend_library_seats`: 선호도 기반 좌석 추천
- `prepare_reserve_library_seat` -> `confirm_action`: 좌석 예약
- `get_my_library_seat`: 현재 예약 좌석 조회
- `prepare_swap_library_seat` -> `confirm_action`: 이석
- `prepare_cancel_library_seat` -> `confirm_action`: 반납
- `LibrarySessionStore`: Postgres + AES-GCM 기반 도서관 세션 영속화
- `ActionService`: pending action TTL, audit row, confirm 단계 분리
- 운영 배포: `https://ssumcp.duckdns.org/mcp`, ArgoCD `Synced / Healthy`

완성이라고 부르기 전 남은 것은 "코드가 있다"가 아니라 "실제 사용자 흐름에서 실패 없이
반복 검증된다"는 증거를 만드는 일이다.

## 완성 판정 기준

아래 항목이 모두 끝나면 도서관 자동 에이전트를 제품 관점에서 완성으로 본다.

### P0. 운영 실사용 E2E 검증

실제 도서관 계정과 운영 MCP endpoint로 아래 흐름을 한 번에 검증한다.

1. MCP client에서 `start_auth(provider=LIBRARY)` 호출
2. 브라우저 로그인 완료
3. `get_auth_status`로 LIBRARY linked 확인
4. `get_library_seat_status(floor=2|5|6)` 호출
5. `recommend_library_seats`로 후보 좌석 확인
6. `prepare_reserve_library_seat` 호출
7. `confirm_action`으로 실제 예약
8. `get_my_library_seat`로 예약 결과 확인
9. `prepare_swap_library_seat` -> `confirm_action`으로 이석
10. `prepare_cancel_library_seat` -> `confirm_action`으로 반납
11. 다시 `get_my_library_seat`로 예약 없음 확인

기록 방식:

- 원문 토큰, 학번, 이름, 좌석 소유자 정보는 저장하지 않는다.
- `mcp_session_id`, Pyxis token, password는 절대 로그/문서에 남기지 않는다.
- 성공/실패 transcript는 민감값을 마스킹해서 `TROUBLESHOOTING.md` 또는 별도 검증 문서에 남긴다.

### P0. 실패 케이스 매트릭스

다음 실패를 실제 응답으로 확인하고 사용자 메시지가 명확한지 검증한다.

| 케이스 | 기대 동작 |
| --- | --- |
| 도서관 세션 없음 | `AUTH_REQUIRED` + loginUrl |
| 도서관 세션 만료 | 저장 세션 정리 + 재로그인 안내 |
| 이미 좌석 예약 중 예약 시도 | 예약 차단, 이석/반납 안내 |
| 현재 예약 없음 상태에서 이석/반납 | 예약 없음 안내 |
| 이미 점유된 좌석 예약 | upstream 실패를 경쟁 실패로 안내 |
| 대학원열람실 권한 없음 | 신분 제한 안내 |
| 운영 시간 외 예약 | 운영 시간/예약 불가 안내 |
| Pyxis 4xx/5xx/timeout | 외부 시스템 오류 안내, action audit 실패 기록 |
| `confirm_action` 중복 호출 | 같은 action 재실행 방지 |

### P0. 문서와 MCP client 계약 최신화

- `docs/mcp-tools.md`의 tool count와 도서관 action tool 목록을 현재 구현과 일치시킨다.
- Claude Desktop, Cursor, Codex plugin/local MCP 설정 예시를 `https://ssumcp.duckdns.org/mcp` 기준으로 갱신한다.
- write tool 정책을 "향후"가 아니라 현재 shipped backend contract로 표현한다.

### P1. 좌석 단위 정확도 개선

현재 추천은 static `ROOM_SEAT_CODES`와 room-level availability를 결합한다. 방에 빈 좌석이 있으면
해당 방의 모든 정적 좌석 후보가 추천 대상이 될 수 있다. 이 방식은 제품 데모에는 충분하지만
"완벽한 자동 예약"에는 부족하다.

필요 작업:

- DevTools Network에서 Pyxis seat-map 또는 seat-list endpoint를 캡처한다.
- 좌석별 `seatId`, 표시 번호, 점유 상태, room id, floor id를 파싱한다.
- `RealLibrarySeatConnector`가 `LibrarySeatZone.seats`를 실제 좌석 단위로 채우게 한다.
- `recommend_library_seats`가 실제 available 좌석만 추천하도록 바꾼다.
- static catalog는 좌석 속성/선호도 데이터로만 사용하고, availability는 live data를 source of truth로 둔다.

### P1. B1/특수 열람실 확장

현재 안정적으로 다루는 범위는 2F, 5F, 6F 중심이다. B1과 특수 열람실은 실제 Pyxis 파라미터가
확정된 뒤 확장한다.

필요 작업:

- B1 floor id 또는 room-only 호출 방식을 확인한다.
- 대학원열람실, PC존, 리클라이너처럼 room policy가 다른 좌석을 별도 policy로 모델링한다.
- `graduateOnly`, `pcRequired`, `timeLimit`, `reservationBlockedReason` 같은 정책 필드를 추천/prepare 응답에 노출한다.

### P1. Frontend 제품 UX

MCP backend는 동작하지만, 웹 제품에서 "도서관 자동 에이전트"라고 부르려면 사용자가 브라우저에서도
같은 흐름을 안전하게 수행할 수 있어야 한다.

필요 작업:

- 좌석 추천 카드 또는 챗봇 action panel 추가
- 추천 후보 선택 -> 예약 전 확인 모달 -> 결과 표시
- 현재 예약 좌석 표시, 이석/반납 버튼
- confirm 전 좌석 번호, 열람실, 제한 정책, 만료 시간을 명확히 표시
- 실패 시 재추천/재시도 동선 제공

### P1. 감사/운영 관측 강화

필요 작업:

- action audit 상태별 metric 추가: prepared, confirmed, succeeded, failed, expired
- `prepare_*`와 `confirm_action` 실패 reason code 표준화
- Grafana 패널에 도서관 action 성공률/실패율 추가
- Alertmanager contact point 설정 후 action failure spike 알림 추가

### P2. 안전성 강화

필요 작업:

- `confirm_action` lock이 단일 인스턴스 기준이면 DB row lock 또는 unique transition으로 확장한다.
- action payload schema version을 명시한다.
- 예약/이석/반납 action별 idempotency contract를 테스트로 고정한다.
- Pyxis rate limit/circuit breaker/backoff를 reservation connector에도 적용한다.

### P2. 문서/포트폴리오 정리

필요 작업:

- 실제 E2E 성공 transcript를 민감값 제거 후 문서화한다.
- "Zero Trust 도서관 세션 -> MCP 인증 -> prepare/confirm action -> 운영 배포" 흐름을 아키텍처 그림으로 정리한다.
- 면접용 핵심 문장과 장애 대응 사례를 `MASTERPLAN.md`와 `TROUBLESHOOTING.md`에 최신화한다.

## 다음 작업 추천 순서

1. 운영 MCP E2E 검증 스크립트/절차 작성
2. 실제 계정으로 reserve -> current -> swap -> cancel 검증
3. `docs/mcp-tools.md` 현행화
4. 좌석 단위 live seat-map endpoint 캡처
5. live seat-map 파서 구현
6. 프론트 추천/예약 UX 구현
7. action metrics/Grafana 패널 추가

가장 먼저 할 일은 새 기능 구현이 아니라 운영 E2E 검증이다. 이미 배포된 backend action path가
실제 학교 시스템에서 안전하게 반복 동작하는지 증명해야 이후 작업의 우선순위가 정확해진다.
