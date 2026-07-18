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

## 2026-07-18 후속: 3/3 연결 표시와 실제 provider 장애가 어긋남

### 기대 동작과 영향

연결 패널이 `3/3`을 표시하면 학사와 LMS 도구가 현재 사용할 수 있어야 한다. 실제 운영에서는 세 provider가 모두 연결된 것으로 보였지만 졸업요건 조회는 일반적인 시스템 오류 안내로 끝났고, LMS 자료 요청은 연결 상태를 확인하지 못했다는 안내를 반환했다. 사용자는 성공한 웹 로그인과 `3/3` 표시 때문에 인증이 정상이라고 판단할 수밖에 없었다.

### 재현과 증거

1. ssuAI의 채팅 배지는 `linkedProviders`가 하나만 있어도 `MCP 연결됨`을 표시했고, 연결 패널도 같은 배열만으로 provider별 상태를 계산했다.
2. `McpProviderCredentialService.isAvailable()`은 `EXPIRED`만 제외하고 `ERROR` credential은 계속 사용 가능하다고 반환했다.
3. 반면 ssuAgent의 auth guard는 같은 `ERROR` health를 `UNAVAILABLE`로 처리했다. 브라우저와 에이전트가 같은 credential을 서로 다른 의미로 해석했다.
4. 학사 스트림의 `get_auth_status`와 `check_graduation_requirements` 시작 이벤트는 provider preflight 이후 실제 도구 호출까지 진행됐음을 보여줬다. 이후 예외는 에이전트 루프에서 일반 tool error로 마스킹돼 LLM이 근거 없는 일반 졸업요건 안내를 생성했다.
5. 조사 시점 운영 health/readiness는 정상이었지만 Prometheus 프로세스 누적치에는 `/api/saint/graduation`의 502와 `/api/lms/assignments`의 500이 각각 존재했다. 전역 서비스 다운이 아니라 credential health 또는 upstream 호출 단계의 실패라는 증거다.

### 검토한 가설과 원인

- 전체 MCP 장애: deep health와 `/mcp` 성공 지표가 정상이라 기각했다.
- 단순 미로그인: 웹 세션 발급·상태 요청은 성공했고 provider link도 존재해 기각했다.
- LMS 라우팅 오류: 요청은 LMS 에이전트와 auth preflight로 정확히 진입해 기각했다.
- 확인된 계약 결함은 credential grant와 operational health의 의미를 한 `linkedProviders` 배열로 축약한 점, retryable 상태 갱신 실패에서 오래된 스냅샷을 현재 상태처럼 표시한 프론트 캐시, 도구 예외를 LLM 입력으로 되돌린 에이전트 오류 처리의 결합이다. 다만 요청 trace나 사용자별 운영 로그가 없어 최초 upstream 5xx의 정확한 원인이 credential, 네트워크, 포털 응답 변경 중 무엇이었는지는 확정하지 않는다.

### 해결과 대안

- web-session create/status 응답에 개인정보가 없는 `availableProviders`와 `providerHealth`를 추가한다. `linkedProviders`의 grant 의미는 보존하고, operational set에서는 `ERROR`와 `EXPIRED`를 제외한다. `UNKNOWN`은 새 credential의 첫 호출을 허용한다.
- 한 provider의 grant, availability, health는 단일 `McpProviderCredentialStatus` snapshot으로 계산해 동시 tool call과 status refresh가 겹쳐도 한 응답 안에서 모순되지 않게 한다.
- ssuAI는 provider 수와 degraded/stale 상태를 분리해 표시한다. 갱신 실패 시 session id는 복구를 위해 유지하지만 이전 `3/3`을 새로 확인된 상태처럼 보여주지 않는다.
- ssuAgent는 upstream tool failure를 결정적 서비스 오류로 반환해 LLM이 일반 정보로 메우지 못하게 한다.
- LMS 목록·대시보드·내보내기 도구가 `LmsApiException`을 `status=OK` 문자열로 숨기던 경로를 공통 privacy-safe outcome으로 바꾼다. retryable 전송·5xx는 `UPSTREAM_UNAVAILABLE`, 비재시도 protocol 실패는 `UPSTREAM_PROTOCOL_CHANGED`이며 원래 예외 메시지는 응답에 넣지 않는다.
- `ERROR` 발생 즉시 link를 삭제하는 방식은 일시적 네트워크 장애와 인증 만료를 구분하지 못하므로 기각했다. 에이전트는 명시적인 다음 사용자 요청에서 bounded tool invocation을 허용해 성공 시 저장 health가 `VALID`로 회복된다. 기존 transport wrapper의 단일 retry는 유지하지만 최종 실패 뒤 모델 주도 재호출은 막는다. health를 숨기고 재로그인만 반복시키는 방식도 운영 원인을 가려 기각했다.

### 검증과 회귀 방지

- 백엔드 focused 검증: `McpProviderCredentialServiceTests`, `McpWebSessionControllerTests` 20개 통과.
- 백엔드 전체 `test`, JaCoCo coverage verification과 `build` 통과.
- LMS 목록·대시보드·내보내기의 structured upstream outcome과 예외 상세 비노출 테스트 통과.
- 프론트는 3/3, 부분 연결, `ERROR`, live-status 갱신 실패를 각각 고정 fixture로 검증한다.
- 에이전트는 academic preflight 통과 후 MCP 도구 예외가 결정적 오류 안내로 끝나고 임의의 졸업요건 텍스트를 만들지 않는지 검증한다.
- PR CI, 배포 및 실계정 후속 검증 결과는 전달 단계에서 추가한다.

### 남은 위험과 예상 면접 질문

운영 upstream을 매 상태 조회마다 probe하지 않으므로 `UNKNOWN`과 마지막 성공 이후의 짧은 stale window는 남는다. 상태 조회는 credential을 노출하지 않는 저장 health 스냅샷을 사용하고, 실제 도구 호출이 성공·실패 시 이를 갱신한다. 최초 upstream 오류의 정확한 유형은 배포 뒤 request/trace 상관 근거로 별도 확정해야 한다.

- identity, grant, health를 하나의 boolean으로 합치면 어떤 장애가 생기는가?
- `ERROR` link를 삭제하지 않으면서도 UI를 fail-closed하게 만든 이유는 무엇인가?
- LLM 에이전트에서 tool exception을 모델에게 다시 맡기면 왜 허위 fallback이 생기는가?
