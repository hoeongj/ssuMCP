# MCP 웹 세션 credential 복사 트랜잭션 회귀

| 항목 | 내용 |
| --- | --- |
| 관찰일 | 2026-07-16 |
| 영향 | ssuAI가 SAINT/LMS 연결을 표시해도 학사·LMS 에이전트는 미연결 안내를 반복하거나, `/api/mcp/auth/web-session`이 HTTP 500을 반환함 |
| 범위 | MCP 웹 세션 브릿지와 SAINT/LMS 영속 credential 저장소 |

## 기대 동작과 실제 동작

웹에 로그인한 사용자가 채팅을 보내면 ssuAI는 `/api/mcp/auth/web-session`에서 임시 MCP 세션을 발급받고, 실제로 보유한 SAINT/LMS/도서관 credential만 그 세션에 격리해 연결해야 한다.

운영에서는 두 가지 실패 형태가 관찰됐다.

1. canonical credential이 있는 요청은 `/api/mcp/auth/web-session`에서 HTTP 500이 발생했다.
2. canonical credential이 없는 요청은 provider가 하나도 없는 세션을 201로 발급했지만, 응답에는 실제 grant가 없어 클라이언트가 JWT 존재를 SAINT/LMS 연결로 오인했다. 이후 학사와 LMS 에이전트는 각각 정확히 fail-closed하여 로그인 안내를 반환했다.

## 재현과 증거

- 운영 Prometheus의 HTTP 서버 메트릭에서 실제 `POST /api/mcp/auth/web-session` 500 요청을 확인했다.
- 영속 저장소를 사용하는 Spring/H2 통합 재현에서 SAINT와 LMS의 `copyForSession()`이 모두 `TransactionRequiredException` 계열 오류로 실패했다.
- 기존 단위 테스트는 저장소를 mock 처리하거나 인메모리 구현만 사용해 잠금 쿼리를 실행하지 않았고, controller fixture도 두 복사를 항상 성공으로 고정해 이 경로를 발견하지 못했다.
- V17은 프로세스 메모리에만 있던 기존 credential을 이관할 수 없으므로 배포 직후 DB 테이블은 비어 있었다. 웹 refresh JWT의 수명은 provider credential보다 길 수 있어 웹 로그인 표시와 실제 provider grant가 어긋날 수 있었다.

## 원인

`SaintSessionStore.copyForSession()`과 `LmsSessionStore.copyForSession()`은 같은 객체의 `putForSession()`을 내부 호출했다. `putForSession()`에 트랜잭션 애노테이션이 있어도 self-invocation은 Spring 프록시를 통과하지 않는다. 그 결과 JPA `findForUpdate()`가 활성 트랜잭션 없이 실행됐다.

별개로 `McpWebSessionResponse`가 세션 ID와 만료 시각만 반환해, credential 복사가 `false`인 정상적인 만료/미이관 상태와 실제 연결 성공을 클라이언트가 구분할 수 없었다.

## 해결

- 두 저장소의 `copyForSession()` 자체에 영속 저장소 정책과 일치하는 트랜잭션 경계를 둬 읽기와 재암호화 쓰기를 한 트랜잭션으로 실행한다.
- SAINT/LMS/도서관 복사본은 원본의 capture·expiry와 provider health를 보존하고 `EXPIRED` credential은 거부한다. 웹 세션 발급이 upstream 로그인 TTL을 연장하지 않는다.
- controller는 복사에 성공한 provider만 링크하고 `linkedProviders`로 반환한다.
- 기존 MCP 세션은 subject 검증된 live-status 요청에서 provider callback·logout·credential 만료를 다시 계산한다. 상태 확인 때문에 session ID를 회전하지 않는다.
- opaque owner key로 credential을 다시 암호화하는 세션 격리는 유지한다.
- 중간 복사/link 예외에서는 MCP 세션과 이미 생성한 owner credential을 보상 삭제한다.
- 실제 Spring proxy를 거치는 persistent copy 통합 테스트와 만료·health 보존, 빈/부분 grant, 예외 보상 테스트를 추가한다.

## 검토한 대안

- `studentId`를 MCP provider principal로 직접 재사용: 복사 오류는 피하지만 독립 MCP 세션들이 같은 mutable credential namespace를 공유해 격리 보장을 깨므로 기각했다.
- JWT가 있으면 SAINT/LMS가 연결됐다고 응답: 만료되거나 배포에서 유실된 credential을 숨겨 같은 반복 로그인 증상을 만들므로 기각했다.
- credential이 하나라도 없으면 전체 발급을 401로 실패: 도서관처럼 독립적으로 유효한 provider까지 차단하므로 기각했다.

## 검증과 회귀 방지

- `McpWebSessionControllerTests`: 전체·부분·도서관 단독 grant가 응답과 실제 링크에 일치하는지 검증한다.
- live-status는 동일 JWT subject 또는 활성 library-only 웹 세션에만 허용하고, 실제 가용 credential만 반환한다.
- `SaintPersistentSessionStoreIntegrationTests`, `LmsPersistentSessionStoreIntegrationTests`: 호출자 트랜잭션 없이 source credential을 독립 target namespace로 복사한다.
- ssuAI는 `linkedProviders`를 연결 상태의 단일 근거로 사용하고, private 요청 직전에 세션 발급 완료를 기다려야 한다.

남은 운영 위험은 기존 JWT가 살아 있어도 provider credential이 이미 없을 수 있다는 점이다. 이 경우 서버는 거짓 연결을 표시하지 않고 해당 provider 재인증을 요구한다. 배포 후에는 201 응답과 `linkedProviders` 조합, 5xx 소멸을 운영 메트릭으로 재검증한다.

## 예상 면접 질문

- 메서드에 `@Transactional`이 있는데도 `TransactionRequiredException`이 발생한 이유는 무엇인가?
- 사용자 신원과 외부 provider credential grant를 분리해야 하는 이유는 무엇인가?
- 부분 연결을 허용하면서 세션 격리와 fail-closed 동작을 어떻게 유지했는가?
