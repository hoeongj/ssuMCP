# ADR 0042 — MCP 세션 transport fallback 다중 매칭 무해화

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-18 |
| 상태 | Accepted |
| 연관 ADR | [ADR 0036](0036-mcp-auth-optin-two-mode.md) (3-tier 세션 해석), [ADR 0039](0039-mcp-session-isolation-review.md) (세션 격리 검증) |
| 트리거 | 한 MCP 연결에서 `start_auth`를 2회 이상 호출한 뒤 private 도구와 `get_auth_status`가 `NonUniqueResultException`으로 전부 실패 |

---

## 배경

ADR 0036 이후 private 도구는 `McpAuthHelper.resolveSession()`에서 3-tier 순서로 세션을 해석한다.

1. OAuth `sub`
2. HTTP transport id (`Mcp-Session-Id`)
3. opaque `mcp_session_id`

이 순서는 의도적이다. 일부 MCP 클라이언트는 opaque `mcp_session_id`를 턴 사이에 안정적으로 다시 넘기지 않으므로, 서버가 HTTP 계층에서 관리하는 transport id가 그 결손을 메우는 fallback 역할을 한다.

문제는 Tier 2의 조회 키인 `transport_session_id`가 비유니크 컬럼이라는 점이다. 한 연결에서 `start_auth`가 여러 번 호출되면 매번 새 auth session이 생기고 같은 `Mcp-Session-Id`에 바인딩된다. 기존 `bindTransportId`는 이미 그 transport를 잡고 있던 다른 세션의 바인딩을 회수하지 않았기 때문에, 한 transport id가 여러 `mcp_sessions` row에 누적될 수 있었다.

그 상태에서 기존 Spring Data 메서드 `findByTransportSessionIdAndExpiresAtAfter(...)`는 반환 타입이 `Optional<McpSessionEntity>`였지만 실제 SQL 결과가 2행이면 단건 조회로 처리되어 `NonUniqueResultException`을 던진다. Tier 2가 Tier 3보다 먼저 실행되므로, 호출자가 정확한 opaque `mcp_session_id`를 전달해도 Tier 2 예외가 먼저 터져 복구되지 않는다.

OAuth `sub`도 안정적인 사용자 identity라서 여러 세션에 남을 수 있는 비유니크 컬럼이다. transport와 달리 "한 연결에 한 세션"으로 훔쳐오는 키가 아니므로 바인딩 회수 대상은 아니지만, fallback 조회가 단건 예외를 내면 같은 계열 문제가 된다.

근거:

- `McpAuthHelper.resolveSession()`의 Tier 1 → Tier 2 → Tier 3 순서
- `McpSessionRepository`의 기존 비유니크 컬럼 단건 파생 쿼리
- `McpAuthSessionStore.bindTransportId()`의 기존 null-only 바인딩 정책
- ADR 0036의 transport fallback 목적: unreliable opaque id 보완

---

## 결정

비유니크 fallback 키는 "최신 세션 1개"로 해석한다.

1. `McpSessionRepository`의 비유니크 단건 조회를 `findFirst...OrderByCreatedAtDesc` 형태로 변경한다.
   - `findByTransportSessionIdAndExpiresAtAfter` → `findFirstByTransportSessionIdAndExpiresAtAfterOrderByCreatedAtDesc`
   - `findByOauthSubjectAndExpiresAtAfter` → `findFirstByOauthSubjectAndExpiresAtAfterOrderByCreatedAtDesc`
2. `McpAuthSessionStore.findByTransportId`와 `findByOauthSubject`는 위 메서드를 사용한다.
3. `bindTransportId(sessionId, transportId)`는 대상 세션에 transport를 설정하기 전에 같은 transport를 가진 다른 세션의 `transportSessionId`를 `null`로 해제한다.
4. OAuth `sub`에는 같은 회수 정책을 적용하지 않는다. OAuth subject는 per-connection key가 아니라 stable user identity이며, 같은 사용자의 과거 세션이 남아 있을 수 있다. 최신 1개 선택만으로 충분하다.
5. DB 마이그레이션은 만들지 않는다. 기존 중복 row는 `findFirst...OrderByCreatedAtDesc`가 최신 1개만 읽으므로 즉시 무해화된다.

---

## 기각한 대안

### 대안 1: Tier 순서를 바꿔 opaque PK를 먼저 조회

`mcp_session_id`는 PK라 안전하므로 Tier 3을 Tier 1로 올리면 이번 예외를 피할 수 있다.

기각 이유:

- ADR 0036의 핵심 목적을 약화한다. transport fallback은 opaque id를 잃어버리는 클라이언트를 구제하기 위해 opaque id보다 앞에 있다.
- opaque id가 stale이거나 잘못 들어와도 같은 연결의 transport로 복구되는 현재 UX가 깨진다.
- 원인은 Tier 우선순위가 아니라 비유니크 컬럼을 단건 조회한 것이다. 안전한 단건 조회로 고치는 편이 결함에 직접 대응한다.

### 대안 2: `transport_session_id`에 UNIQUE 제약 추가

DB가 한 transport id를 한 row에만 허용하면 중복 누적은 사라진다.

기각 이유:

- transport id는 설계상 per-connection key이며, 연결 재사용과 재로그인 흐름에서 새 세션으로 이동할 수 있다. "항상 전역 유니크"로 모델링하면 재바인딩 시점마다 충돌을 먼저 해결해야 한다.
- 이미 프로덕션에 누적된 중복 row가 있으면 UNIQUE 제약 추가 자체가 실패한다.
- 이번 요구는 live auth 복구가 우선이며, 마이그레이션 없이 기존 중복까지 무해화해야 한다.

### 대안 3: DB cleanup migration으로 중복 row 정리

마이그레이션에서 transport별 최신 row만 남기고 나머지 `transport_session_id`를 null 처리할 수 있다.

기각 이유:

- `findFirst...OrderByCreatedAtDesc`만으로 기존 중복 데이터가 즉시 무해해진다.
- cleanup은 과거 데이터만 정리할 뿐, `bindTransportId`가 회수하지 않으면 같은 패턴으로 다시 누적될 수 있다.
- auth 장애 복구에 운영 DB write migration을 추가할 이유가 없다. 쿼리 안전화와 bind 회수가 더 작고 검증 가능하다.

---

## 동작 방식

### fallback 조회

transport와 OAuth subject 조회는 모두 `createdAt DESC`로 정렬한 첫 row만 반환한다.

```java
Optional<McpSessionEntity> findFirstByTransportSessionIdAndExpiresAtAfterOrderByCreatedAtDesc(
        String transportSessionId,
        Instant now
);

Optional<McpSessionEntity> findFirstByOauthSubjectAndExpiresAtAfterOrderByCreatedAtDesc(
        String oauthSubject,
        Instant now
);
```

Spring Data JPA의 `findFirst...OrderBy...` 파생 쿼리는 SQL 레벨에서 결과를 1개로 제한한다. 따라서 비유니크 컬럼에 2행 이상이 있어도 `Optional` 단건 변환 단계에서 예외가 나지 않는다.

### transport rebinding

`bindTransportId(id, transportId)`는 대상 세션이 유효할 때만 동작한다.

1. `session_id` PK와 `expires_at`으로 대상 세션을 찾는다.
2. 같은 `transport_session_id`를 가진 다른 세션을 JPQL bulk update로 해제한다.
3. 대상 세션의 `transportSessionId`를 현재 transport id로 설정한다.

```java
update McpSessionEntity e
set e.transportSessionId = null
where e.transportSessionId = :transportId
  and e.sessionId <> :keepSessionId
```

bulk update는 JPA persistence context를 우회하므로 `@Modifying(clearAutomatically = true, flushAutomatically = true)`를 붙인다. 그래야 같은 트랜잭션 안에서 release 이후 상태를 다시 읽어도 stale entity가 보이지 않는다.

### 기존 데이터 영향

마이그레이션이 없으므로 배포 시 DB schema와 row를 건드리지 않는다. 이미 한 transport에 여러 세션이 쌓여 있어도 읽기는 최신 1개로 제한되어 예외가 나지 않는다. 이후 새 `bindTransportId` 호출부터는 같은 transport를 가진 다른 row가 해제되어 transport↔session 관계가 다시 1:1로 수렴한다.

### 검증

- store regression: 한 transport id를 공유하는 unexpired 세션 2개를 직접 넣고 `findByTransportId`가 최신 `createdAt` 세션을 반환한다.
- store regression: session A가 transport T를 가진 상태에서 session B에 T를 bind하면 A는 null, B는 T가 된다.
- helper integration: 실제 service/store/repository를 사용해 한 transport에 세션 2개가 있어도 `McpAuthHelper.resolveSession()`과 `principalKey()`가 최신 세션으로 해석한다.

---

## 한계와 후속 판단

- `createdAt`이 같은 중복 row가 있으면 DB 정렬이 완전히 결정적이지 않을 수 있다. 실제 `create()`는 시간 차를 두고 호출되고, 이번 테스트도 다른 `createdAt`으로 회귀를 고정한다. 더 엄격한 결정성이 필요하면 `createdAt DESC, sessionId DESC` 같은 보조 정렬을 추가할 수 있다.
- 동시 `start_auth` 경쟁에서 두 요청이 같은 transport를 서로 다른 세션에 거의 동시에 bind하면 마지막 커밋이 이긴다. transport는 per-connection recovery key라 마지막 명시 바인딩이 승리하는 것이 자연스럽다. provider link 자체는 각 auth session에 남으므로 credential 데이터는 삭제되지 않는다.
- OAuth subject는 일부러 회수하지 않는다. 같은 사용자 identity가 여러 세션에 남아 있을 수 있고, 최신 세션 선택으로 충분하다.

## 예상 면접 질문

1. Spring Data JPA에서 `Optional<T>` 반환 파생 쿼리가 비유니크 컬럼 2행을 만나면 어떤 예외가 나며, 왜 `findFirst...OrderBy...`가 안전한가?
2. transport fallback이 opaque id보다 먼저 실행되는 UX상의 이유는 무엇이며, 그 순서가 이번 장애를 어떻게 증폭했나?
3. DB migration 없이 기존 중복 row를 무해화한 이유와, JPQL bulk update에 `clearAutomatically`가 필요한 이유는?
