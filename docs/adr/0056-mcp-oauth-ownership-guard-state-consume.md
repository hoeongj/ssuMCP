# ADR 0056 — 세션 해소 시 OAuth-sub 소유권 가드(bind-or-verify) + OAuth state 원자적 1회 소비

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-21 |
| 상태 | Accepted — 구현·배포(`8000e32` #104, `8000e32`/`4b08d55` #106) |
| 범위 | `McpAuthHelper.resolveSession` / `McpAuthService.bindOrVerifyOauthSubject` / `McpAuthStateStore` / `McpPrivateToolResponse.ok` |
| 연관 ADR | [0036](0036-mcp-auth-optin-two-mode.md)(3-tier 해석), [0039](0039-mcp-session-isolation-review.md)(교차연결 누수 부재 증명), [0042](0042-mcp-session-transport-lookup-multiplicity.md)(transport 다중매칭) |
| 연관 사건 | TROUBLESHOOTING 사건 14, 사건 17 |

---

## 배경 — 무슨 문제

여러 독립적인 보안 리뷰를 통합·검증하는 과정에서 `McpAuthHelper`(MCP private 도구 세션 해소 지점)를 두고 **서로 다른 세 가지 지적**이 나왔다(이하 리뷰 A/B/C). 이를 코드로 대조해 진짜 결함만 추렸다.

### (a) 리뷰 A "최우선 P0: 가짜 mcp_session_id 우회" — 오진

리뷰 A는 로그인 세션으로 테스트한 뒤, 가짜 `mcp_session_id="invalid-..."`로 `get_my_schedule`·`get_my_lms_terms`·`prepare/confirm_lms_material_export`가 전부 `status:OK` + 실데이터/다운로드 URL을 반환했다며 "인증 우회"로 단정했다. 응답 형태는 `{"status":"OK","provider":null,"mcpSessionId":"invalid-...","data":{실값}}`였다.

코드 3곳 추적 결과 **우회가 아니었다**:

1. `McpAuthHelper.resolveSession()`은 ADR 0036의 3-tier 전략 — Tier1 OAuth `sub`(검증된 Bearer JWT) → Tier2 transport id(`Mcp-Session-Id` 헤더) → Tier3 opaque 인자. prod는 `rs-enabled=true`(ChatGPT의 OAuth/PRM 디스커버리가 실제로 동작함을 `kubectl`로 시크릿 present 확인하여 입증)라, ChatGPT 커넥터는 **Tier1에서 자기 세션이 먼저 풀린다**. 가짜 Tier3 인자는 도달조차 안 한다.
2. `McpPrivateToolResponse.ok(mcpSessionId, data)`가 **입력 인자를 그대로 echo**하고 있었다 → 응답의 `mcpSessionId`가 "가짜 id가 먹혔다"는 착시를 만들었다.
3. `ok()`가 `provider`를 **null로 하드코딩**하고 있었다 → 어느 provider가 응답했는지 알 수 없어 착시를 강화했다.

미인증(JWT·transport 모두 없음) + 가짜 인자뿐이면 Tier3 `find()`가 empty → `AUTH_REQUIRED`. 반환된 데이터는 리뷰어 본인 계정 것이다. ADR 0039가 이미 같은 계열의 "임의 세션 id로 남의 데이터" 보고를 교차연결 누수 부재로 규명했고, 이번에도 동일하다.

리뷰 A가 제안한 `requireProviderSession`(인자가 DB 세션에 없으면 무조건 거부)은 정당한 Tier1/2 해소를 깨뜨려 ChatGPT 무한 `AUTH_REQUIRED` 루프(사건 9 재발) + 인증 회귀를 일으킨다 → **적용 거부.**

### (b) 리뷰 B ① "bind 무검증으로 세션 고정/권한 상승" — 전제는 부분 오류, 결함은 실재

리뷰 B는 "`bindOauthSubject`가 기존 바인딩 일치 검사 없이 수행된다"고 했으나, `bindOauthSubject`는 **이미 "stored sub == null일 때만 bind"**였다(transport 다중매칭 무해화 ADR 0042 이후 코드). 전제는 틀렸다.

그러나 **진짜 결함은 같은 파일의 다른 지점**에 실재했다: `resolveSession`의 Tier2/3는 transport id 또는 opaque id만 일치하면 세션을 반환하면서, **그 세션에 이미 바인딩된 oauth_subject와 현재 호출자의 JWT `sub`가 다른지 검사하지 않았다.** 공격자가 피해자의 transport id(이전엔 SDK CORS GHSA-hv2w-8mjj-jw22로 유출 가능, #107에서 bump) 또는 opaque id(응답 echo로 노출되는 capability)를 얻고 **자기 JWT를 실으면**, 피해자 세션이 Tier2/3로 해소되어 데이터가 반환된다. → 권한 경계 우회(진짜 P0).

### (c) OAuth state 1회용 토큰의 find-then-delete 레이스 (리뷰 C #11)

`McpAuthStateStore`가 state를 "조회 후 삭제"하는 비원자 패턴이라, 동시 콜백 두 건이 같은 state를 각각 소비할 수 있는 TOCTOU 윈도가 있었다.

---

## 결정

### 1. 세션 해소 시 OAuth-sub 소유권 가드 (bind-or-verify) — 핵심

`resolveSession`의 Tier2·Tier3에서 세션을 찾은 뒤, **현재 호출자에게 JWT `sub`가 있으면** `bindOrVerifyOauthSubject(found.id, oauthSub)`를 호출한다:

- 세션의 `oauth_subject`가 null → 현재 sub로 bind하고 `true`(첫 호출 정상 경로, 기존 opportunistic bind 보존).
- 일치 → `true`(정당한 소유자).
- 불일치 → `false`. 이때 세션을 **반환하지 않고** 다음 tier로 fall-through(WARN 로깅, 결과적으로 `Optional.empty()` → `AUTH_REQUIRED`).

Tier3 불일치 시에는 추가로 **transport id를 그 세션에 opportunistic bind 하지 않는다**(호출자가 소유하지 않은 세션에 자기 연결을 묶지 않기 위해).

JWT `sub`가 없는 호출자(classic 모드, `rs-enabled=false`)는 가드를 거치지 않고 기존 Tier2/3 거동을 그대로 유지한다 → 비-OAuth 클라이언트(Claude Desktop 등) 무영향.

### 2. OAuth state 원자적 1회 소비

state 소비를 "조회 후 삭제" 대신 **단일 `@Modifying` JPQL `deleteIfActive`의 영향 행수로 claim**한다(영향 행수 1 = 이 호출이 소비 권한 획득, 0 = 이미 소비/만료 → 거부). 동시 콜백 중 정확히 한 건만 진행한다.

### 3. 오진을 *유발한* 경미 버그 수정 (echo·provider:null)

OK 응답이 입력 인자가 아니라 **해소된 canonical 세션 id**를 싣도록 `McpAuthHelper.resolvePrincipal`(principalKey + canonical sessionId 동시 반환)을 추가하고, `McpPrivateToolResponse.ok(mcpSessionId, provider, data)`에 **실제 provider(SAINT/LMS/LIBRARY)**를 채운다. 19개 private 도구 전부 적용(#104).

## 대안과 기각 이유

- **`requireProviderSession`(인자 미발견 시 거부, 리뷰 A 처방)**: 정당한 Tier1/2 해소를 깨 ChatGPT 무한 루프(사건 9) + 인증 회귀. 기각.
- **명시 `mcp_session_id`를 transport보다 우선**: ADR 0039 대안 A — ChatGPT 턴경계 드랍을 transport로 복원하는 설계를 깨므로 기각.
- **Tier2/3 자체 제거(인자 세션만 신뢰)**: PR#73의 ChatGPT 지원 존재 이유 삭제. 기각.
- **state를 in-memory Set으로 dedup**: 멀티포드·재시작에 취약. DB 행수 claim이 단일 진실원천. 기각.
- **`bindOauthSubject`를 그대로 두고 별도 verify 메서드 추가**: 호출지점이 두 군데(Tier2·Tier3)라 bind와 verify를 한 메서드(`bindOrVerifyOauthSubject`)로 합치는 편이 누락·드리프트 위험이 작다. 채택.

## 동작 방식

```
resolveSession(opaqueId):
  oauthSub  = currentOauthSub()      # JWT sub, classic 모드면 null
  transport = currentTransportId()   # Mcp-Session-Id 헤더

  Tier1: oauthSub != null → findByOauthSubject(oauthSub) 있으면 반환
  Tier2: transport != null → findByTransportId(transport):
           found 시 oauthSub 있으면 bindOrVerify(found, oauthSub)
             false(불일치) → fall-through (반환 안 함)
             true/oauthSub 없음 → 반환
  Tier3: opaqueId 유효 → find(opaqueId):
           found 시 oauthSub 있으면 bindOrVerify(found, oauthSub)
             false(불일치) → fall-through
             true/oauthSub 없음 → (transport opportunistic bind) → 반환
  → 어느 tier도 못 찾으면 Optional.empty() → AUTH_REQUIRED
```

### 검증

- 회귀 테스트: 미인증+가짜 id → `INVALID_SESSION`(data/loginUrl null); Tier2/3 sub 불일치 → deny(다른 sub로는 세션 미반환); state race-loser 거부. 920+ 테스트 green, 하드리뷰 완료.
- prod `/mcp` initialize 스모크 통과(서버 alive·프로토콜 정상).
- 정상 경로(Tier1·classic·첫 호출 null→bind) 전부 보존 확인.

## 예상 면접 질문

1. "가짜 mcp_session_id로 실데이터가 조회됐다"는 P0 제보를, 전송계층 3-tier 설계를 코드로 추적해 오진으로 판정한 과정은? 그 오진을 *유발한* 진짜 버그(응답 echo·provider:null)는 무엇이었나?
2. 한 리뷰는 "무조건 bind"가 결함이라 했지만 실제 결함은 다른 지점(해소 시점 sub 불일치 미거부)이었다. 둘을 어떻게 분리해 올바른 수정만 했나?
3. `bindOrVerifyOauthSubject`의 세 분기(null→bind / 일치→true / 불일치→false)가 각각 어떤 정당/공격 시나리오에 대응하나? classic 모드(JWT 없음)에서 가드를 건너뛰어도 안전한 이유는?
4. OAuth state 1회 소비를 "조회 후 삭제" 대신 영향 행수 claim으로 바꾼 이유와, 그 TOCTOU 윈도가 무엇을 막는가?
