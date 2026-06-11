# Library Seat Agent Completion Plan

## 현재 상태 (2026-06-06 기준)

도서관 좌석 자동 에이전트의 backend MCP action path는 배포되어 있고 P0 E2E의 reserve 단계가 검증됐다.

### 완료된 기능

- `get_library_available_seats`: 7개 열람실 전체 live per-seat 상태 요약 (Pyxis `/rooms/{roomId}/seats` API)
- `get_room_available_seats`: 특정 열람실 per-seat 상태 목록 (status: available/occupied/away/inactive)
- `get_library_seat_status`: 도서관 층별 room-level 좌석 현황 (2·5·6층)
- `get_library_seat_catalog`: 정적 좌석 카탈로그 조회
- `recommend_library_seats`: 선호도 기반 좌석 추천
- `prepare_reserve_library_seat` → `confirm_action`: 좌석 예약 (**E2E 검증 완료**)
- `get_my_library_seat`: 현재 예약 좌석 조회 (**버그 수정 완료** — chargeId=0 → data.list[0] 파싱)
- `prepare_swap_library_seat` → `confirm_action`: 이석
- `prepare_cancel_library_seat` → `confirm_action`: 반납
- `LibrarySessionStore`: Postgres + AES-GCM 기반 도서관 세션 영속화
- `ActionService`: pending action TTL, audit row, confirm 단계 분리
- 운영 배포: `https://ssumcp.duckdns.org/mcp`, ArgoCD `Synced / Healthy`

### P0 E2E 검증 진행 상황

| 단계 | 상태 | 비고 |
|------|------|------|
| start_auth(LIBRARY) + 로그인 | ✅ 완료 | |
| get_library_available_seats | ✅ 완료 | 470개 available 좌석 확인 |
| prepare_reserve → confirm_action | ✅ 완료 | seat 950 (오픈열람실 25번) 예약 성공, chargeId: 1967740 / 재검증 2026-06-11: seat 3321 (마루열람실 216번), chargeId 1984615 |
| 점유 좌석 예약 시 실패 메시지 | ✅ 완료 | "좌석이 이미 선점됐습니다" 정상 반환 |
| get_my_library_seat | ✅ 완료 (2026-06-11) | 예약 표시·반납 후 "없음" 표시 모두 정상 |
| recommend_library_seats | ✅ 완료 (2026-06-11) | 만석 층(2F, 0/354) 안내, 대학원열람실 기본 제외, label↔externalSeatId 매핑 정상 |
| prepare_swap → confirm_action | ⚠️ 조건부 검증 (2026-06-11) | **미입실 배정 상태에서는 Pyxis가 discharge를 `warning.smuf.notAvailableState`로 거부** → swap 1단계(기존 좌석 반납)에서 안전하게 중단, 기존 예약 유지 확인. happy-path는 입실(게이트/NFC) 상태에서만 가능 — 사용자가 도서관에 있을 때 재검증 |
| prepare_cancel → confirm_action | ⚠️ 조건부 검증 (2026-06-11) | 동일 사유로 미입실 상태 반납 불가 (T+1m/T+6m/T+13m 모두 거부). 미입실 좌석은 Pyxis가 자동취소(13:32 배정 → 14:1x 해제 확인) |

2026-06-11 발견 (상세: `TROUBLESHOOTING.md`):
- oasis 웹 클라이언트(`returnSeat$`)도 동일 endpoint/body를 쓰므로 요청 형태 문제 아님 — Pyxis 상태 규칙.
- swap의 discharge-first 설계가 실패 시 기존 예약을 보존하는 안전한 실패 모드로 동작함을 실증.
- pod 롤링 후에도 MCP 세션·provider 링크·도서관 토큰(V4/V5 Postgres 영속화)이 살아남는 것 실증. 단 Pyxis 토큰이 업스트림에서 needLogin으로 무효화되는 사례 관찰(원인 미확정 — 단일 세션 정책 의심).

2026-06-12 개선:
- `warning.smuf.notAvailableState`는 더 이상 generic "실행 중 오류"로 노출하지 않는다.
  connector가 typed business exception으로 올리고, `confirm_action`이 반납/이석별 안내로 변환한다.
- 반납: "아직 입실 전이라 좌석을 반납할 수 없음", "입실 후 재시도", "미입실 배정 좌석은 일정 시간 후 자동 취소 가능"을 안내한다.
- 이석: 기존 좌석 반납 단계에서 실패하므로 "기존 예약은 그대로 유지"를 명시한다.

## 완성 판정 기준

아래 항목이 모두 끝나면 도서관 자동 에이전트를 제품 관점에서 완성으로 본다.

### P0. 운영 실사용 E2E 검증

실제 도서관 계정과 운영 MCP endpoint로 아래 흐름을 한 번에 검증한다.

1. MCP client에서 `start_auth(provider=LIBRARY)` 호출
2. 브라우저 로그인 완료
3. `get_auth_status`로 LIBRARY linked 확인
4. `get_library_available_seats`로 실시간 가용 좌석 확인
5. `prepare_reserve_library_seat` 호출
6. `confirm_action`으로 실제 예약
7. `get_my_library_seat`로 예약 결과 확인
8. `prepare_swap_library_seat` → `confirm_action`으로 이석
9. `prepare_cancel_library_seat` → `confirm_action`으로 반납
10. 다시 `get_my_library_seat`로 예약 없음 확인

기록 방식:
- 원문 토큰, 학번, 이름, 좌석 소유자 정보는 저장하지 않는다.
- `mcp_session_id`, Pyxis token, password는 절대 로그/문서에 남기지 않는다.
- 성공/실패 transcript는 민감값을 마스킹해서 `TROUBLESHOOTING.md`에 남긴다.

### P0. 실패 케이스 매트릭스

| 케이스 | 기대 동작 | 검증 상태 |
| --- | --- | --- |
| 도서관 세션 없음 | `AUTH_REQUIRED` + loginUrl | ✅ |
| 도서관 세션 만료 | 저장 세션 정리 + 재로그인 안내 | ✅ |
| 이미 점유된 좌석 예약 | "좌석이 이미 선점됐습니다" | ✅ |
| 현재 예약 없음 상태에서 이석/반납 | 예약 없음 안내 | ⏳ |
| 운영 시간 외 예약 | 운영 시간/예약 불가 안내 | ⏳ |
| Pyxis 4xx/5xx/timeout | 외부 시스템 오류 안내 | ⏳ |
| `confirm_action` 중복 호출 | 같은 action 재실행 방지 | ⏳ |

### P0. 문서와 MCP client 계약 최신화

- [x] `docs/mcp-tools.md`: `get_library_available_seats`, `get_room_available_seats` 추가
- [x] `README.md`: 도구 목록 최신화
- [ ] swap·cancel E2E 완료 후 이 문서 완성 표시

### P1. 좌석 단위 정확도 개선

현재 `recommend_library_seats`는 room-level availability + 정적 `ROOM_SEAT_CODES`를 결합한다.
`get_library_available_seats`·`get_room_available_seats`가 이미 per-seat 데이터를 제공하므로,
recommend도 이 데이터를 source of truth로 사용하도록 개선할 수 있다.

필요 작업:
- `LibraryAvailableSeatsService.getRoomAvailableSeats()`를 `RecommendLibrarySeatsService`에서 호출하도록 변경
- 정적 `ROOM_SEAT_CODES` fallback을 제거하거나 live data가 없을 때만 사용
- `recommend_library_seats`가 실제 available 좌석 ID만 추천하도록 수정

### P1. B1/특수 열람실 확장

현재 roomId: 15(B1F), 53(숭실스퀘어ON), 54(오픈열람실), 57(마루열람실), 58(대학원열람실), 59(리클라이너), 60(숭실멀티라운지) 7개를 지원.

### P1. Frontend 제품 UX

- 좌석 추천 카드 또는 챗봇 action panel 추가
- 현재 예약 좌석 표시, 이석/반납 버튼

### P2. 감사/운영 관측 강화

- action audit 상태별 metric: prepared, confirmed, succeeded, failed, expired
- Grafana 패널에 도서관 action 성공률/실패율 추가

## 다음 작업 추천 순서

1. `get_my_library_seat` 수정 배포 완료 후 swap → cancel E2E 검증 (P0 완료 목표)
2. `recommend_library_seats` per-seat live data 전환 (P1)
3. E2E 성공 transcript를 TROUBLESHOOTING.md에 추가
4. Frontend 추천/예약 UX 구현 (P1)
5. action metrics / Grafana 패널 추가 (P2)
