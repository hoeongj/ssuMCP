# ADR 0039 — 외부 리뷰 P0("임의 세션 ID로 LMS 데이터 조회") 조사: 교차연결 누수 부재 증명 + 세션 격리 회귀 테스트

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-18 |
| 상태 | Accepted |
| 연관 ADR | [ADR 0036](0036-mcp-auth-optin-two-mode.md) (3-tier 세션 해석), [ADR 0038](0038-chatgpt-mcp-oauth-auth0-dcr.md) (OAuth DCR) |
| 트리거 | ChatGPT·Claude Desktop 두 외부 MCP 클라이언트로 ssuMCP 도구를 전수 호출하며 진행한 점검 |

---

## 배경 — 무엇이 보고되었나

외부 MCP 클라이언트 두 개(ChatGPT, Claude Desktop)로 ssuMCP 도구를 전수 호출하던 중, 한 클라이언트의 응답이 **P0 보안 취약점**으로 의심되는 패턴을 보였다:

> "`mcp_session_id`에 임의 UUID를 넣었는데도 `get_my_assignments`가 `AUTH_REQUIRED`/`INVALID_SESSION`이 아니라 **OK + 실제 LMS 과제 데이터**를 반환했다. 도구 인자의 세션 ID를 검증하지 않고 **현재 컨텍스트 / 전역 세션 / 마지막 활성 세션 / 쿠키 세션으로 fallback**하는 구조로 보인다."

이 관측은 "private tool이 전역/최근 세션으로 폴백한다"는 **교차 사용자 데이터 누수**로 해석될 여지가 있었다.

### 결정적 단서: 두 클라이언트의 관측이 서로 모순됐다

같은 도구(`get_auth_status`)를 두고 두 클라이언트의 관측이 **엇갈렸다**:

- **ChatGPT**: 위조/임의 세션 ID → `OK` 반환 (취약 신호로 해석)
- **Claude Desktop**: 위조 세션 ID → `INVALID_SESSION` 정상 반환 (격리 정상으로 관측)

동일 코드·동일 prod에서 관측이 갈린다는 것은 "코드가 폴백한다"는 단순 가설로는 설명되지 않는다. **두 클라이언트의 transport 거동 차이**가 원인일 가능성이 크다 → 보고를 액면 그대로 믿지 말고 해석 코드를 직접 읽어 진짜 속성을 확정해야 한다.

---

## 조사 — 코드가 실제로 하는 일

`McpAuthHelper.resolveSession(mcpSessionId)`는 ADR 0036의 **3-tier 해석**을 수행한다(우선순위 순):

1. **OAuth `sub`** — 검증된 Bearer JWT(HTTP 계층, LLM 불가침). `rs-enabled=false`(현행)면 항상 null.
2. **Transport id** — `Mcp-Session-Id` 요청 헤더. MCP 서버가 **연결마다** 발급, `start_auth`가 세션에 바인딩. ChatGPT가 턴 경계에서 opaque id를 흘려도 같은 연결 안에서 세션을 복원하는 장치(ADR 0036).
3. **Opaque `mcp_session_id`** — LLM 도구 인자(흘릴 수 있어 최후 수단).

서비스 계층(`McpAuthServiceImpl`)을 끝까지 읽어 확인한 사실:

- `find(idValue)` → `sessionStore.find(idValue)`: **정확 일치 조회.**
- `findByTransportId(transportId)`: **정확 일치 조회.**
- `findByOauthSubject(sub)`: **정확 일치 조회.**
- `McpAuthService` 인터페이스에 `findLatest`/`findCurrent`/`findAny` 같은 **앰비언트 세션 접근자는 존재하지 않는다.**

### 진짜 원인: 보고된 증상은 Tier 2(연결 범위)다

리뷰어가 "임의 UUID인데 내 데이터가 나왔다"고 본 것은, **그 리뷰어의 연결에 이미 자기 세션이 transport로 바인딩돼 있었기** 때문이다. `resolveSession`은 Tier 2(transport)에서 **자기 자신의 세션**을 먼저 찾고, 인자로 준 엉터리 opaque id(Tier 3)는 **쳐다보지도 않는다**. 즉 반환된 데이터는 **공격자의 데이터가 아니라 호출자 본인의 데이터**다. 두 리뷰가 갈린 이유도 이것으로 설명된다 — Claude Desktop과 ChatGPT의 `Mcp-Session-Id` 유지 방식이 달라, 한쪽은 transport 바인딩이 살아 OK가 나오고 다른 쪽은 없어서 `INVALID_SESSION`이 나왔다.

`get_auth_status`는 이미 PR#81에서 `INVALID_SESSION`(인자는 줬으나 미해석) / `NO_SESSION`(인자 없음) / `OK`를 구분한다. transport가 없는 호출자가 위조 id를 주면 정확히 `INVALID_SESSION`이 나온다.

**결론: 보고된 "교차 연결/전역 폴백" 누수는 코드에 존재하지 않는다.** 해석은 호출자가 보유한 비밀(JWT sub / 서버 발급 transport id / opaque id) 중 하나가 정확히 일치해야만 성공하는 **bearer-only**이며, 셋 다 없으면 `Optional.empty()` → `AUTH_REQUIRED`다.

---

## 대안 비교 — "고치라"는 보고를 그대로 따랐다면

| 대안 | 설명 | 채택 |
|---|---|---|
| A. 명시적 `mcp_session_id`를 transport보다 우선시 / 불일치 시 `INVALID_SESSION` | 리뷰어 권고를 문자 그대로 구현 | ✗ **ChatGPT를 다시 부순다.** ChatGPT는 턴 경계에서 stale/누락 id를 보내고 transport 복원에 의존한다(ADR 0036). 이걸 막으면 무한 `AUTH_REQUIRED` 루프(사건 9)가 재발 |
| B. 3-tier에서 transport 계층 제거 | "인자 세션만 신뢰" | ✗ PR#73의 존재 이유 자체를 삭제 → ChatGPT 사용 불가 |
| C. (채택) 코드 거동 유지 + 진짜 속성을 회귀 테스트로 고정 + 문서화 | 누수 부재를 **증명**하고, 미래 회귀를 막고, 보고를 강점 서사로 전환 | ✓ 보안 속성을 깨지 않으면서 검증 가능하게 만든다 |

리뷰어가 위험하다고 본 "fallback"은 사실 **연결 범위 transport 계층**이고, 그것을 없애는 "수정"은 보안을 개선하는 게 아니라 핵심 기능(ChatGPT 지원)을 파괴한다. 보고를 액면대로 따르는 것이 오히려 잘못된 결정이었다.

---

## 결정

**대안 C.** 세션 해석 로직은 변경하지 않는다(이미 올바르다). 대신 리뷰어의 *진짜 두려움*(추측한 세션 id로 남의 세션 접근)이 불가능함을 **회귀 테스트로 못박는다**.

신규: `McpSessionIsolationTests`(순수 Mockito 단위 테스트, `McpAuthHelper`).

증명하는 속성:
- `forgedOpaqueId_noTransport_noOauth_resolvesEmpty` — 비밀 없는 호출자 + 위조 id → empty.
- `foreignOpaqueId_looksUpExactIdOnly_andNothingElse` — opaque 해석은 그 id **정확 조회만**, transport/oauth 계층을 임의 값으로 건드리지 않음(앰비언트 조회 없음).
- `unboundTransport_plusForgedOpaqueId_resolvesEmpty` — 공격자 자신의 연결(바인딩 없는 transport) + 위조 id → empty(남의 "현재" 세션으로 새지 않음).
- `staleOpaqueId_withOwnTransportBinding_resolvesOwnSessionNotAnotherPrincipal` — 보고된 증상 재현: transport가 있으면 stale opaque 인자를 무시하고 **본인** 세션을 반환(Tier 2 > Tier 3). 교차 접근이 아니라 연결 범위 복원임을 명시.
- `fullyAnonymousCaller_resolvesEmpty` — id·transport·oauth 전무 → empty("기본 세션" 거동 없음 가드).

세션 해석 코드, `get_auth_status` 계약 모두 **변경 없음**. 순수 test + docs.

### 결정 근거

- **정직한 프레이밍**(advisor 반영): "취약점 없음"이라는 평면적 단정 대신, *정확한 참 속성*을 못박는다 — 전역/현재/최근 세션 폴백 부재, 해석은 bearer 비밀(JWT sub / 서버 발급 transport id / opaque id)을 요구, 비밀 없으면 `AUTH_REQUIRED`. 테스트가 검증하는 것도 **리뷰어의 실제 두려움(교차 연결 접근)**이지, "같은 세션 거동" 재확인이 아니다.
- **보고를 강점으로 전환**: "외부 보안 리뷰에서 P0 의심 신고 → 3-tier 해석·서비스 계층을 끝까지 추적 → 교차연결 누수 부재와 연결범위 transport 복원을 분리 규명 → 회귀 테스트로 격리 고정"은 그 자체로 면접 서사다.

---

## 작동 방식 / 검증

- 단위 테스트(5종) green. transport 미바인딩 + 위조/외부 id → empty → 도구는 `AUTH_REQUIRED` 반환(데이터 아님)을 보장.
- 한계(정직): transport id와 opaque id는 둘 다 **bearer 비밀**이다. 둘 중 하나를 탈취/관측하면 그 연결/세션 권한을 얻는다(모든 bearer 토큰의 공통 성질). 이는 표준 위협모델 내이며, 본 ADR이 부정하는 것은 "**비밀 없이** 임의 id로 남의 데이터를 얻는다"는 보고된 시나리오다.
- transport id 자체는 서버 발급 고엔트로피 값(추측 불가). opaque id는 응답으로 LLM에 노출되므로 그 자체가 capability — 알면 접근 가능한 게 설계(ADR 0036).

---

## 예상 면접 질문

1. "임의 세션 ID로 데이터가 나온다"는 보고를 받았다. 코드를 어떻게 추적해 그것이 교차연결 누수가 아니라 연결 범위 transport 복원임을 확정했나?
2. 두 리뷰어가 `get_auth_status`에서 상반된 결과를 봤다(OK vs INVALID_SESSION). 동일 코드에서 왜 갈렸고, 그 모순이 진단에 어떤 단서였나?
3. 리뷰어 권고대로 "명시 세션 id를 transport보다 우선"하면 무엇이 깨지나? 왜 그 "수정"이 보안이 아니라 회귀인가?
4. transport id와 opaque mcp_session_id는 bearer 토큰이다. 그렇다면 이 시스템에서 진짜로 막아야 하는 위협과, 본질적으로 감수하는 위협을 어떻게 구분하나?
