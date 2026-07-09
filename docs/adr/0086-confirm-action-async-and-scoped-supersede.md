# ADR 0086 — confirm_action 비차단화(C1) + supersede 액션 스코프화(G2)

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-07-10 |
| 상태 | Accepted — 구현 완료, 커밋 대기 |
| 범위 | `ConfirmActionMcpTool` / `LibraryWaitMcpTool` / `ActionService` / `ActionAudit`·`ActionAuditRepository`·`ActionStatus` / `LibraryReservationMcpTool`·`LibraryCancelMcpTool`·`LibrarySwapMcpTool` (prepare 호출부) / Flyway V16 |
| 연관 ADR | [0015](0015-action-tool-infrastructure.md)(action 감사 인프라), [0022](0022-library-reservation-intent-queue.md)(예약 intent 큐), [0048](0048-epic5-intent-sse.md)(intent SSE), [0055](0055-confirm-action-supersede-ownership.md)(supersede+action_id 도입 — 본 ADR이 supersede 스코프 결정을 개정), [0059](0059-reservation-audit-single-source-of-truth.md)(audit 단일 진실원천, 본 ADR과 병행 유지) |
| 의존성 | ADR 번호 0081은 병행 진행 중인 다른 P1 유닛이 선점. 병합 전 `docs/adr/` 번호 충돌 여부 재확인 필요(작업 착수 시점에 origin/main은 0080 다음 0085까지 이미 점유돼 있어 0086을 채택함). |

## 배경 — 무슨 문제 (C1 + G2)

**C1.** `ConfirmActionMcpTool.confirmAction`이 좌석 예약(`prepare_reserve_library_seat` → `confirm_action`)을 처리할 때, 예약 intent를 큐에 넣은 뒤 최대 8초(`reservationIntentWait`) 동안 200ms 간격으로 `Thread.sleep` 폴링하며 비동기 worker의 결과를 기다렸다. 이 sleep은 **MCP 도구 호출을 처리 중인 서블릿(Tomcat) 스레드 안에서** 실행된다. 기본 Tomcat 스레드풀은 200개이므로, 산수는:

```
200 threads / 8s(최악 대기) = 초당 최대 25건의 confirm_action(RESERVE)만 처리 가능
```

동시 좌석팟(수강신청 유사 burst) 상황에서 26번째 이후 confirm 요청은 스레드풀 고갈로 커넥션 자체가 큐잉되거나 타임아웃된다 — MCP 서버 전체(다른 도구 포함)가 응답 불가 상태에 빠질 수 있다.

**G2.** `confirm_action`은 원래 `action_id`를 받지 않고 "가장 최근 PENDING 액션"만 확정했고, prepare 시 supersede는 owner 전체(모든 actionType)를 대상으로 했다(ADR 0055). 이 owner-wide supersede 덕분에 owner당 PENDING은 항상 최대 1건이었지만, 그 대가로 **서로 다른 두 액션(예: 서로 다른 좌석의 예약 두 건)을 동시에 준비해 둘 수 없었다** — 두 번째 prepare가 첫 번째를 무조건 무효화했다. 감사 항목 G2가 요구하는 것은 이 제약을 풀어 여러 액션이 동시에 대기할 수 있게 하되, 그 결과로 생기는 모호함(어느 걸 confirm할지)을 `action_id`로 명시적으로 풀 수 있게 하는 것이다.

## 탐색 결과 — 이미 되어 있던 부분

구현 착수 전 `ConfirmActionMcpTool`/`ActionService`/ADR 0055·0059를 읽어보니, G2가 요구하는 항목 중 다음은 **이미 구현되어 있었다**:

- `confirm_action(mcp_session_id, action_id?)` — `action_id` 파라미터 이미 존재.
- 생략 시 0/1/N 분기: 0건 → "대기 액션 없음", N건(>1) → 거부 메시지, 1건 → 그 액션 확정.
- `claimPendingActionById`의 소유권+PENDING+TTL 재검증(행 락).

**남아 있던 진짜 차이**는 두 가지뿐이었다: (1) supersede가 여전히 owner 전체 스코프였고, (2) N건 거부 메시지가 후보 `action_id` 목록을 나열하지 않았다(이번에 추가). C1은 손대지 않은 상태였다(8초 폴링 그대로).

## C1 — 대안 분석과 선택

### MCP 스레딩 모델 실측

`ssuMCP`는 `spring-ai-starter-mcp-server-webmvc`(spring-ai-bom 1.1.7, `mcp-spring-webmvc:0.18.3`)를 쓰고, `application.yml`에서 `spring.ai.mcp.server.type: SYNC`, `protocol: STREAMABLE`로 설정돼 있다. 즉 WebFlux/Netty가 아니라 **서블릿(Tomcat) 기반** MCP 서버다.

`mcp-spring-webmvc-0.18.3.jar`의 `WebMvcStreamableServerTransportProvider.handlePost(...)`를 바이트코드로 확인한 결과(`javap -c`), 내부적으로 Reactor `Mono`를 쓰지만 **호출 스레드에서 `Mono.block()`을 8회 호출**한다:

```
invokevirtual  Mono.block:()Ljava/lang/Object;   (handlePost 안에서 다회 호출)
```

`McpSyncServer`(`type: SYNC`)의 도구 핸들러도 등록된 `@Tool` 콜백을 같은 호출 스레드에서 직접(동기) 실행하도록 구성된다 — 이것이 "SYNC" 타입의 정의 자체다. 즉 `WebMvcStreamableServerTransportProvider`가 `/mcp` POST 요청을 처리하는 Tomcat 워커 스레드 위에서 `.block()`으로 세션 처리를 기다리고, 그 안에서 `ConfirmActionMcpTool.confirmAction(...)`이 **그 동일 스레드**에서 실행된다. 이 SDK 레이어에는 `DeferredResult`/`WebAsyncTask` 같은 서블릿 비동기 디스패치로 스레드를 반환하는 훅이 없다 — 애플리케이션 코드가 어떤 논-블로킹 기교(CompletableFuture 등)를 쓰더라도, 메서드가 return하기 전까지는 호출 스레드가 풀려나지 않는다.

### 대안 (a) — 동일 UX(최대 8초 대기) 유지, 논블로킹 대기로 전환

이론상 서블릿 스레드를 붙잡지 않고 8초 대기를 흉내내려면 다음 중 하나가 필요하다:
- MCP 서버를 `type: ASYNC`(Reactor `McpAsyncServer`) + WebFlux 트랜스포트로 전환 — MCP 요청 처리 자체를 리액티브 파이프라인으로 바꿔야 한다.
- 또는 서블릿 3+ 비동기 디스패치(`request.startAsync()`)를 SDK 레이어에 끼워 넣어야 한다 — `mcp-spring-webmvc` 0.18.3은 이를 지원하지 않는다(핸들러가 `ServerResponse`를 동기 반환하는 라우터 함수 모델).

두 방법 다 **MCP 트랜스포트/서버 계층 전체의 아키텍처 전환**이며, 단일 유닛(P1-4)의 범위를 크게 초과하고 `global/resilience` 영역 등 병렬 유닛이 소유한 인프라와 충돌할 위험이 크다. 정직하게 말하면: **이 SDK/설정 조합에서는 대안 (a)가 통하지 않는다.** 대기 시간을 8초에서 예컨대 1초로 줄이는 절충안도 검토했으나, 스레드 점유 시간을 낮출 뿐 "N 동시 confirm이 스레드풀을 고갈시킨다"는 근본 실패 모드는 그대로 남는다(200 threads / 1s = 초당 200건으로 완화될 뿐 여전히 유한 상한).

### 대안 (b) — 즉시 반환 + 명시적 후속 조회 (채택)

`confirm_action`이 예약 intent를 생성한 직후 **즉시 반환**한다("접수됨" + `intentId`). 서블릿 스레드는 intent insert 트랜잭션 커밋 시간만 점유하고 즉시 반환되므로, 이론상 처리량 상한이 사실상 사라진다(8초 임계값이 없어짐).

- LLM/사용자는 응답 메시지에 포함된 `intentId`로 `get_library_wait_status(mcp_session_id, intent_id)`를 호출해 결정론적으로 결과를 확인한다.
- 웹(ssuAI) 프론트엔드는 기존 SSE 레지스트리(`LibraryIntentSseRegistry`)로 결과를 계속 실시간으로 받는다 — 이번 변경은 `LibraryReservationWebController`를 건드리지 않았다(스코프 분리, 아래 "트레이드오프" 참고).
- 실제 예약은 여전히 `LibraryReservationWorker`가 커밋 직후 `notifyReadyAfterCommit`로 깨어나 처리하므로(poll-interval 1초, 대부분 수백 ms~수 초 내 완료), 사용자 체감 지연은 "즉시 답을 못 받는다"는 것뿐 — 완료 자체가 느려지는 것은 아니다.

## G2 — supersede 스코프 결정

### 새 스코프: `(student_id, action_type, target_key)`

`ActionAudit`에 `target_key` 컬럼을 추가(Flyway V16, nullable — 배포 시점에 남아있을 수 있는 구버전 PENDING 행은 TTL 5분 내 자연 소멸)하고, MCP 도서관 prepare 도구(`prepare_reserve/cancel/swap_library_seat`) 3종은 새 `ActionService.createPendingAction(studentId, actionType, targetKey, payload)`로 전환했다. supersede는 `markPendingSupersededForAction`이 `(student_id, action_type, target_key)` 3중 일치 행만 SUPERSEDED로 바꾼다:

| 액션 | target_key | 근거 |
|---|---|---|
| RESERVE | `seatId` | 같은 좌석 재예약만 이전 건을 대체; 다른 좌석은 별개 동시 액션 |
| CANCEL | 현재 예약의 `chargeId` | 사용자는 활성 예약이 항상 1건이므로 재-prepare는 항상 같은 charge를 가리킴(기존 동작과 동일) |
| SWAP | 스왑 대상(기존 보유 좌석)의 `chargeId` | "이 좌석에서 나가기"가 액션의 정체성; 새 목적지가 바뀌어도 같은 액션 |

`confirm_action`의 0/1/N 분기(ADR 0055)는 그대로 두되, N건 응답에 후보 `action_id` 목록을 나열하도록 개선했다(`action_id=101(LIBRARY_SEAT_CANCEL), action_id=102(LIBRARY_SEAT_RESERVATION)`).

### 레거시 호출부는 owner-wide 유지 (의도적 비대칭)

`LibraryReservationWebController`(ssuAI 웹)와 `LmsMaterialExportService.confirm`은 `action_id`가 전혀 없이 "가장 최근 PENDING"만 집는다(`claimPendingAction(studentId)`, `findPendingAction`). 이 두 호출부는 ADR 0055가 막으려던 "누적된 서로 다른 PENDING 중 오래된 것이 되살아나는" 구멍에 여전히 무방비다. 따라서 `ActionService.createPendingAction(studentId, actionType, payload)`(3-arg, target_key=actionType 기본값) 오버로드는 **owner-wide supersede를 그대로 유지**하도록 남겨뒀다 — 웹 컨트롤러의 3개 prepare 메서드와 LMS export는 이 오버로드를 계속 호출하므로 코드 변경이 전혀 없다. 액션 스코프 supersede(4-arg 오버로드)는 오직 `action_id` 명시 지정이 가능한 MCP 도서관 prepare 도구만 사용한다.

이 비대칭이 안전한 근거: LIBRARY의 principalKey는 `UUID.randomUUID()`(로그인 시 발급), LMS의 principalKey는 실제 학번 문자열이다(`McpLmsAuthController`/`McpWebSessionController` 확인) — 두 값 공간은 포맷이 달라 **구조적으로 충돌 불가능**하다. ADR 0055가 "혹시 몰라" 언급했던 "한 학생이 충돌하더라도"라는 방어 논리는 애초에 발동 조건이 존재하지 않았다.

## 대안과 기각 이유 (G2)

- **`(student_id, action_type)`만으로 스코프(target_key 없이)**: RESERVE(seat101) → RESERVE(seat102) prepare 시 여전히 자동 supersede되어, "두 개의 동시 대기 예약"이라는 G2가 겨냥한 상황 자체가 재현되지 않는다. `ActionAuditRepositoryIntegrationTests.prepareSupersedesOnlyTheSameOwnersPendingActions`가 정확히 이 케이스(같은 owner, 같은 type, 다른 seatId)로 "이전 건이 superseded된다"를 증명하던 기존 테스트였는데, 이번 변경으로 그 기대가 뒤집혔다(재작성) — 이게 바로 G2가 요구한 동작 변경이다.
- **모든 호출부(웹·LMS 포함)를 액션 스코프로 통일**: 웹 컨트롤러와 LMS confirm에는 `action_id`가 없어 "여러 건 동시 PENDING"을 명시적으로 골라낼 방법이 없다. 통일하면 두 번째로 준비한 액션이 첫 번째를 지우지 않게 되어, 오래된 PENDING이 다음 "묻지마 confirm" 때 조용히 실행되는 ADR 0055의 원래 구멍이 그대로 재현된다. 웹/LMS 쪽 UX·API 계약 변경("여러 건이면 거부") 없이는 통일이 위험하므로, 이번 유닛 범위 밖으로 명시적으로 남겼다.
- **target_key용 Flyway CHECK 제약/NOT NULL 강제**: ADR 0055가 SUPERSEDED enum에 대해 세운 것과 같은 논리 — `ddl-auto: validate`는 문자열 컬럼 내용까지 검증하지 않고, NOT NULL을 걸면 배포 순간 존재할 수 있는 구버전 PENDING 행(짧은 TTL 윈도)의 저장을 막아 오히려 예외를 유발한다. nullable로 두고 애플리케이션 레벨에서 항상 채워 넣는다.

## 트레이드오프

- **MCP RESERVE confirm은 더 이상 "즉시 성공/실패"를 말해주지 못한다.** 이전엔 대부분(worker가 몇백 ms~수 초 내 처리)의 경우 동기 대기 안에서 최종 결과를 받았지만, 이제는 항상 "접수됨" 응답 + 후속 조회가 필요하다. LLM 호출 왕복이 하나 늘어나는 대가로 스레드풀 고갈 위험을 구조적으로 없앴다 — 포트폴리오 관점에서 "정확성 있는 처리량 확장"을 "체감 지연 한 스텝"과 바꾼 의도적 트레이드오프.
- **`LibraryReservationWebController`의 동일한 8초 폴링 패턴은 이번에 손대지 않았다.** 웹은 이미 SSE로 결과를 받으므로 이 동기 대기의 실사용 가치가 낮고(SSE가 먼저 도착하는 게 보통), 웹 UI는 한 번에 한 플로우만 진행하는 게 보통이라 C1이 겨냥한 "동시 다발 confirm" 위험 프로파일이 MCP(LLM 다중 세션)만큼 크지 않다고 판단해 범위에서 제외했다. 대칭성을 위한 후속 작업으로 남겨둔다.
- **웹·LMS confirm의 owner-wide supersede를 그대로 둔 것**은 "덜 고친 것"이 아니라 "그 경로가 아직 action_id 타겟팅을 갖추지 못했으므로 강한 불변식이 필요하다"는 의도적 선택이다 — MCP와 웹의 supersede 스코프가 다르다는 비대칭 자체를 앞으로 웹에 action_id를 추가할 때 없앨 수 있는 후속 과제로 명시한다.

## 예상 면접 질문

1. **왜 (a)(대기 시간 유지, 논블로킹화) 대신 (b)(즉시 반환)를 택했나?** — spring-ai MCP `SYNC`/`STREAMABLE` 조합이 `mcp-spring-webmvc`에서 `Mono.block()`으로 도구 호출을 처리 스레드에 동기 바인딩하는 것을 바이트코드로 직접 확인했고, 이 SDK 레이어에는 서블릿 비동기 디스패치 훅이 없어 애플리케이션 코드만으로는 스레드를 반환할 방법이 없었다(WebFlux/`ASYNC` 서버 타입으로 통째로 전환하지 않는 한). 대기 시간을 줄이는 절충안도 상한을 낮출 뿐 "동시성 N에 비례해 스레드가 고갈된다"는 근본 실패 모드를 없애지 못해 기각했다.
2. **supersede 스코프를 `(actionType, targetKey)`로 좁히면서 ADR 0055가 지적했던 "오래된 PENDING이 되살아나는" 보안 구멍이 재현되지 않는다고 어떻게 보장하나?** — `confirm_action`의 no-id 경로는 여전히 0/1/N 분기를 거치므로, 스코프를 좁혀 두 개 이상의 액션이 동시에 PENDING이 되는 순간 자동으로 "action_id를 지정하라"는 거부로 전환된다(추측 실행 없음). 위험은 오직 "여러 건이 PENDING인 상태를 알고도 id 없이 confirm 했을 때 그 중 하나가 우연히 자기 의도와 다를 수 있다"는 잔여 리스크뿐이며, 이는 5분 TTL로 제한되고 액션이 여전히 "사용자가 실제로 prepare했던 유효한 요청"이라는 점에서 ADR 0055가 막던 "완전히 잊혀진 요청의 재실행"과는 성격이 다르다.
3. **웹 컨트롤러와 LMS export의 supersede를 owner-wide로 남긴 게 코드 중복/비일관성 아닌가?** — 의도적 비대칭이다. 두 호출부 모두 `action_id` 타겟팅이 없어 "여러 PENDING 중 최근 것"이라는 암묵적 가정에 의존한다. 액션 스코프로 좁히면 그 가정이 깨져 오래된 PENDING이 조용히 실행될 수 있다. LIBRARY/LMS principalKey가 각각 UUID/실제 학번으로 값 공간이 겹칠 수 없음을 확인해 owner-wide 유지의 실질 비용(교차 도메인 오탐 supersede)이 0에 가깝다는 것도 근거로 확인했다.
