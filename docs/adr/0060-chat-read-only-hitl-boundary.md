# ADR 0060 — `/api/chat` 챗봇 read-only화 + write 실행은 ssuAgent HITL로

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-22 |
| 상태 | Accepted — 구현·배포(`5f25479` #124) |
| 범위 | `LlmChatService`(`CHAT_EXCLUDED_TOOLS`, `executeToolCall` 방어) |
| 연관 ADR | [0015](0015-action-tool-infrastructure.md)(2단계 확인), [0051](0051-extract-chat-private-tool-dispatcher.md) |
| 연관 사건 | TROUBLESHOOTING 사건 17(신규 발견) |

---

## 배경 — 무슨 문제

분석에 없던 결함을 직접 발견했다. ssuMCP `/api/chat`(`LlmChatService`)이 auth 도구 4종만 제외하고 **쓰기/confirm 도구를 챗 LLM에 노출**하고 있었다. 챗 엔드포인트에는 HITL(human-in-the-loop) 확인 단계가 없으므로, LLM이 한 배치에서 `prepare_reserve_library_seat` + `confirm_action`을 함께 emit하면 **사람 확인 없이 실제 좌석 예약/LMS export가 자동 실행**될 수 있었다(ADR 0015의 2단계 확인 게이트를 우회).

핵심 검증: 플래그십 예약 흐름("이 자리 예약해줘")은 `/api/chat`이 아니라 **ssuAgent**(`/agent/stream` + `/agent/resume`) 경로이고, 그쪽은 `prepare_*` 결과의 `actionId`로 graph interrupt → 사용자 확인 → `confirm_action` 재개하는 HITL이 있다. `/api/chat`은 예약 surface가 아니라 read-only Q&A surface다(write 도구는 discovery로만 도달, 코드 미참조).

## 결정

`/api/chat`을 read-only Q&A surface로 고정한다. write 실행은 ssuAgent HITL 흐름에만 속한다.

`CHAT_EXCLUDED_TOOLS`에서 두 부류를 도구 discovery에서 제외 + `executeToolCall`에서 방어적으로 거부(defense-in-depth):

- **auth 4종** (챗에서 무용): `start_auth`, `get_auth_status`, `logout_provider`, `logout_all`.
- **write/confirm 9종**: `confirm_action`, `wait_for_library_seat`, `cancel_library_wait`, `prepare_reserve_library_seat`, `prepare_cancel_library_seat`, `prepare_swap_library_seat`, `prepare_lms_material_export`, `confirm_lms_material_export`, `export_all_lms_materials`.

= 총 13종 제외. READ 도구(학식·기숙사·도서관 좌석현황/검색/카탈로그/추천, 공지, 학사일정/정책, 시간표/성적/채플/장학/졸업/GPA, LMS 과목/자료/과제/대시보드, 캠퍼스)는 그대로 사용 가능.

## 대안과 기각 이유

- **`/api/chat`에 자체 HITL 확인 단계 추가**: 챗봇은 단발 Q&A surface라 인터럽트/재개 상태머신을 새로 만들 가치가 낮고, 플래그십 HITL은 이미 ssuAgent에 구현됨. 경계를 명확히 나누는 편이 단순·안전. 기각.
- **discovery에서만 제외(실행 거부 없음)**: LLM이 환각으로 도구명을 직접 호출할 여지. `executeToolCall` 방어 거부를 더해 이중 방어. 채택.
- **프롬프트로 "예약하지 마"라고 지시**: LLM이 무시할 수 있어 서버 강제가 필수(ADR 0015 정신). 기각.

## 동작 방식

- 도구 목록 구성 시 `CHAT_EXCLUDED_TOOLS`에 든 도구를 필터로 제거 → LLM에 노출 안 됨.
- 만약 도구명이 그래도 호출되면 `executeToolCall`이 제외 집합 확인 후 거부.
- 검증: `/api/chat`은 예약 surface가 아님을 확인(플래그십=ssuAgent HITL, write 도구는 discovery로만 도달·코드 미참조) → confirmation-bypass 갭 해소, 플래그십 무영향.

## 예상 면접 질문

1. 어떤 분석도 짚지 않은 이 결함을 어떻게 발견했나? "prepare+confirm 한 배치 emit"이 왜 HITL 우회인가?
2. `/api/chat`에 HITL을 붙이지 않고 read-only로 막은 이유는? write 실행 경로(ssuAgent)와의 경계를 어떻게 나눴나?
3. discovery 제외만으로 충분하지 않아 `executeToolCall` 방어 거부까지 둔 이유는?
4. 제외 13종(auth 4 + write/confirm 9)의 분류 기준은? READ 도구는 왜 남겼나?
