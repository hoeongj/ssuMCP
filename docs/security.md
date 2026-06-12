# ssuMCP 보안 정책

## 이 문서의 목적

ssuMCP 사용자인 숭실대 학생과, 연동된 프로바이더를 통해 시스템이 접근하는 민감한 학사 데이터를 보호하는 규칙을 정의한다. 이 문서는 다음 사항의 **단일 기준 문서(source of truth)**다:

- 어떤 데이터가 민감하고 어떻게 다뤄야 하는가.
- 로그에 남겨도 되는 것과 절대 안 되는 것.
- 학교 시스템 자격증명을 어떻게 저장하고 사용하는가.
- 학교 상태를 바꾸는 "action" 기능의 실행 조건.

`docs/architecture.md`는 로깅과 자격증명 규칙에 대해 이 문서를 참조한다. 두 문서가 충돌하면 이 문서가 우선한다.

## 비목표

- 침투 테스트 방법론.
- 컴플라이언스 인증(KISA / ISMS 등) — 학생 포트폴리오 프로젝트 범위 밖.
- 프로덕션 인시던트 대응 runbook — 이 프로젝트 규모에 맞는 경량 버전만 포함.

---

## 1. 위협 모델 (경량)

이 프로젝트가 진지하게 다뤄야 할 현실적인 위협:

| 위협 | 왜 중요한가 |
|------|-------------|
| 시크릿·쿠키·픽스처의 실수에 의한 커밋 | 학생 프로젝트에서 가장 발생하기 쉬운 실제 인시던트. `.env` 하나 유출되면 끝. |
| 로그를 통한 자격증명 유출 | `log.info("login attempt: " + password)` 한 줄이 치명적. |
| XSS 또는 토큰 유출로 인한 세션·자격증명 탈취 | 사용자 계정과 JWT·세션이 생기면 실제 위협이 된다. |
| 허가되지 않은 "action" 실행 (예: 잘못된 좌석 예약) | action 기능은 실세계에 영향을 미친다. 명시적 확인으로 차단해야 한다. |
| 의존성 취약점 (예: Log4Shell급) | Java·Node 생태계는 정기적으로 CVE가 발생한다. |
| 학교 사이트에 대한 크롤링 남용 | 과도한 부하는 차단이나 그 이상의 결과를 초래할 수 있다. |

이 문서에서 상세히 모델링하지 않는 위협:

- 이 배포 환경에 적합한 경량 제어를 넘어서는 정교한 인프라 타깃 공격. 연동된 개인 데이터는 이미 범위 안에 있으며 아래 규칙을 따라야 한다.
- 숭실대 IT팀 내부자 위협.
- JDK/Node 런타임 자체에 대한 공급망 공격.

---

## 2. 데이터 분류

이 프로젝트가 다루는 모든 데이터는 네 가지 클래스 중 하나에 해당한다. 클래스에 따라 저장, 전송, 로깅, 보존 규칙이 결정된다.

| 클래스 | 예시 | 저장 | 로깅 | 비고 |
|--------|------|------|------|------|
| **공개(Public)** | 오늘 학식, 도서관 도서 목록, 좌석 집계 수치 | 캐시/DB 자유롭게 | 내용 로깅 가능 | 좌석 수는 비개인 데이터이지만, 현재 upstream이 도서관 연동 세션을 요구한다. |
| **개인(Personal)** | ssuAI 계정 이메일, 표시 이름, ssuAI 사용자 ID | DB (평문) | 사용자 ID만 로깅, 이메일·이름 절대 안 됨 | 표준 PII 처리. |
| **민감(Sensitive)** | 수업 시간표, 성적, 졸업요건, LMS 과제, LMS 제출물 | DB, 소유자만 접근 | **내용 절대 로깅 안 됨**, "N건 조회" 수치만 가능 | 의료 기록과 동일한 수준으로 취급. |
| **시크릿(Secret)** | u-SAINT·LMS 비밀번호, 장기 세션 쿠키, API 키, 서명 키, 암호화 키 | 환경 변수, KMS, 또는 암호화된 DB 컬럼 | **절대 로깅 안 됨** | 유출 시 즉시 교체. |

경험칙: 확신이 없으면 데이터를 한 클래스 더 높게 취급한다.

---

## 3. 자격증명 및 시크릿 관리

### 저장소에 절대 올려서는 안 되는 것

- 실제 값이 담긴 `.env` 파일.
- 쿠키, 세션 ID, JWT, API 키.
- 로그인된 학교 세션에서 캡처했고 개인 데이터가 포함된 HTML·JSON 픽스처 — 먼저 제거해야 한다.
- 데이터베이스 덤프.
- 학번, 이메일, 성적이 포함된 스크린샷.

`.gitignore`에 명시적으로 포함해야 할 항목: `.env`, `.env.*`, `*.pem`, `*.key`, `secrets/`, `**/application-prod.yml` (실제 값이 아닌 `${ENV_VAR}` 참조만 담긴 경우가 아니라면), `*.dump`, `*.sql.gz`.

### 시크릿이 실제로 존재하는 곳

- **로컬 개발:** 미추적 `.env` 파일 + 환경 변수를 참조하는 `application-dev.yml`. `.env.example` (커밋됨)에 변수명과 빈 값을 적어 새로운 클론이 무엇을 설정해야 하는지 알 수 있게 한다.
- **CI:** GitHub Actions의 저장소 시크릿. 절대 로그에 출력되지 않는다.
- **프로덕션:** 호스트/컨테이너 오케스트레이터의 실제 환경 변수. 프로덕션이 실제가 되면 적절한 시크릿 매니저(AWS Secrets Manager, GCP Secret Manager, 또는 HashiCorp Vault)를 선택한다 — 그 전에는 불필요.

### 필수 환경 변수 (MVP 이후)

| 환경 변수 | 클래스 | 도입 시점 |
|-----------|--------|-----------|
| `SSUAI_DB_URL` / `SSUAI_DB_USERNAME` / `SSUAI_DB_PASSWORD` | Secret | 라이브: prod Postgres 연결. dev/test 기본값은 인메모리 H2 |
| `SSUAI_REDIS_URL` | Secret | 향후 (현재 캐시는 인프로세스 `ConcurrentMap`) |
| 프로바이더별 LLM 키 — `SSUAI_GEMINI_API_KEY`, `SSUAI_GROQ_API_KEY`, ... (9개 프로바이더) | Secret | 라이브 (채팅). 각 선택적; 키 없으면 해당 프로바이더 건너뜀 |
| `SSUAI_JWT_SECRET` | Secret | 라이브: HS256 32바이트 이상; 빈 기본값 = 재시작마다 임시 랜덤이라 prod 토큰도 재시작 시 만료 |
| `SSUAI_CREDENTIAL_ENCRYPTION_KEY` | Secret | 라이브: 프로덕션에서 연동된 SAINT/LMS/도서관 세션 자료 보호 |

### 커밋 전 보안 장치

gitleaks가 CI(`.github/workflows/security.yml`)에서 모든 PR과 `main` 푸시에 실행된다. 신뢰도 높은 시크릿 패턴이 발견되면 빌드가 실패한다. 설정은 `.gitleaks.toml`에 있다 (gitleaks 기본 규칙에 문서 파일용 allowlist 추가).

로컬 커밋 전 방어를 위해 lefthook을 설치(`lefthook install`)하면, pre-commit 훅이 `gitleaks protect --staged`를 실행해 시크릿이 커밋에 포함되기 전에 차단한다. CI가 하드 게이트이고, 로컬 훅은 편의 기능이다.

---

## 4. 로깅 규칙 (기준 문서)

`docs/architecture.md` §9은 이 섹션을 따른다. 이 규칙은 모든 레이어(Controller, Service, Connector, MCP tool)에 적용된다.

### 항상 로깅해야 하는 것

- HTTP 메서드, 라우트, 상태 코드, 지연 시간.
- `traceId` (크로스 시스템 상관관계용).
- 커넥터 이름 + 결과: `cache hit | cache miss | connector ok | connector failed`.
- 내부 사용자 ID (ssuAI 계정의 대리 ID) — 학번·이메일·이름은 **절대 안 됨**.
- 수량과 형태: "12건의 과제 조회"는 가능하지만, 과제 내용은 안 됨.
- 예외 타입과 스택 트레이스 — 단, 아래 카테고리는 제거해야 함.

### 절대 로깅하지 말아야 하는 것

- 비밀번호 (어떤 시스템이든, 어떤 맥락이든, 로그인 실패 시도 포함).
- u-SAINT·LMS·도서관 쿠키, 세션 ID, CSRF 토큰.
- API 키, JWT, 서명 키, 암호화 키.
- 학번, 실명, 전화번호, 주민등록번호.
- 성적이 함께 있는 과목명, GPA, 졸업 상태, 성적 증명 내용.
- LMS 과제 텍스트 (문제, 학생 답안, 제출 파일).
- 자격증명이나 민감 데이터를 처리하는 엔드포인트의 전체 요청 본문 — 안전한 필드의 엔드포인트별 허용 목록만 사용한다.
- 인증된 학교 페이지의 전체 HTML 응답 (위의 모든 내용이 포함되어 있다).
- **MCP 인증 세션**: `mcp_session_id`, `state` (일회용 로그인 토큰), `sToken`, `sIdno`, `loginId`, 프로바이더 `principalKey` (학번 또는 도서관 키). `McpAuthSessionId.fingerprint()` (SHA-256 앞 8자리 hex)만 로깅한다.
- **MCP 인증 실패**: upstream 예외의 `.getMessage()`가 사용자 대상 HTML·JSON 에러 메시지에 포함되어서는 안 된다. 정적 안전 문자열을 사용한다.

### 실용적인 구현 방법

- **파라미터화된 로깅** (`log.info("user {} fetched {} items", userId, count)`)을 사용한다. 문자열 연결은 의도치 않은 객체가 슬그머니 들어갈 수 있다.
- 민감한 DTO 필드에 커스텀 `@SensitiveField` 어노테이션을 붙이고, Logback·Jackson 마스커를 연결해 직렬화된 출력에서 `***`로 표시한다.
- 인증된 학교 응답에는 본문 대신 체크섬과 크기만 로깅한다.
- 테스트 픽스처에서는 **커밋 전에 개인 정보를 제거한다**. 실제 세션에서 캡처한 경우 `library-search-success.html`에 실제 학생 이름이 포함되어서는 안 된다.

---

## 5. 학교 자격증명 (LMS·u-SAINT)

이 규칙은 구현된 SAINT, LMS, 도서관 프로바이더 연동과 향후 추가될 프로바이더에 적용된다.

### 저장

- 학교 비밀번호를 평문으로 **절대** 저장하지 않는다. 비밀번호를 저장해야 한다면 `SSUAI_CREDENTIAL_ENCRYPTION_KEY`로 AES-GCM 암호화한다. 키는 환경 변수(또는 시크릿 매니저)에 존재하며, 절대 데이터베이스에 저장하지 않는다.
- 학교 시스템이 장기 세션을 허용하는 경우, 비밀번호보다 **세션 쿠키**를 저장하는 것을 선호한다 — 서버 측에서 무효화할 수 있는 쿠키는 재사용 가능한 비밀번호보다 피해 범위가 작다.
- 쿠키와 세션 토큰도 같은 키로 저장 시 암호화한다. 도서관 `Pyxis-Auth-Token`은 `LibrarySessionStore`에서 AES-GCM으로 암호화해 보관하며, 현재 `application.yml`의 도서관 세션 TTL은 7일이다.
- 저장된 자격증명은 소유 ssuAI 사용자에게 범위가 한정된다. 암호화 키는 환경별로 동일할 수 있지만, **레코드별 IV**는 반드시 유일해야 한다.

### 도서관 좌석 샘플러 service session

- 좌석 시계열 샘플러는 사용자 로그인 여부와 무관하게 운영 데이터를 쌓아야 하므로, 사용자 도서관 세션을 재사용하지 않고 sampler 전용 service session을 사용한다.
- 프로덕션 자격증명은 k8s Secret `ssuai-library-sampler`에만 저장한다. Secret key/env var 이름은 `SSUAI_LIBRARY_SAMPLER_LOGIN_ID`, `SSUAI_LIBRARY_SAMPLER_PASSWORD`다. 실제 값은 git, Helm values, 문서, 로그에 남기지 않는다.
- `SSUAI_LIBRARY_SAMPLER_PASSWORD`는 일반 도서관 비밀번호다. backend는 Pyxis login 호출 직전에 oasis 웹 클라이언트와 같은 PBKDF2(SHA-1, 5000회) + AES-CBC 방식으로 암호화하고, 기존 `LibraryCredentialLoginService`로 `/pyxis-api/api/login`을 호출한다. 이 암호화는 upstream 호환용 인코딩이지 저장 암호화가 아니다.
- 로그인 결과 Pyxis token은 `LibrarySessionStore`에 `internal:seat-sampler` key로 저장된다. 값은 기존 도서관 세션과 동일하게 `SSUAI_CREDENTIAL_ENCRYPTION_KEY` 기반 AES-GCM으로 DB에 저장된다.
- `internal:seat-sampler`는 내부 상수이며 MCP 도구 입력, MCP auth link, HTTP session path에서 생성되거나 전달되지 않는다. 사용자 도구는 계속 사용자의 opaque library session key만 조회한다.
- 로그에는 Secret 값, raw password, AES-CBC ciphertext, Pyxis token을 남기지 않는다. 필요한 경우 `LibrarySessionStore.fingerprint(...)` 형식의 8자리 fingerprint만 사용한다.
- Helm chart는 dedicated Secret을 `envFrom.secretRef`로 주입한다. Kubernetes 문서 기준 Secret env var는 pod 시작 시 환경에 반영되므로 Secret 값을 교체하면 pod replacement/rollout으로 새 값을 읽혀야 한다. 참고: https://kubernetes.io/docs/tasks/inject-data-application/distribute-credentials-secure/

### 조회

- 자격증명이 필요한 커넥터 내부에서만, 그리고 외부 호출 기간 동안만 복호화한다.
- DEBUG 레벨이라도 복호화 결과를 로깅하지 않는다.
- 기능 출시 첫날부터 "내 학교 자격증명 삭제" 엔드포인트를 제공한다. 사용자는 언제든 연동을 해제할 수 있어야 한다.

### 교체 및 유실

- 절차를 문서화한다: `SSUAI_CREDENTIAL_ENCRYPTION_KEY` 교체 → 저장된 자격증명 전체 재암호화 → 캐시된 세션 무효화.
- 키 유출이 의심되면 즉시 인시던트로 취급한다: 즉시 교체, 저장된 자격증명 전부 무효화, 사용자에게 학교 계정 재연동 요청.

### 하지 말아야 할 것

- "편의를 위해" 사용자의 학교 로그인을 ssuAI를 통해 프록시하지 않는다 — 매 로그인은 비밀번호를 처리하는 것을 의미한다. 공식 인증 흐름(OAuth 유사, SSO, IP 바인딩 등)이 있다면, 더 많은 작업이 필요하더라도 그것을 사용한다.
- "데모 목적"으로 ssuAI 사용자 여러 명이 하나의 학교 계정을 공유하지 않는다. 데모는 mock 커넥터로 실행한다.

---

## 6. 사용자 동의 및 action 확인

현재 서버는 조회 쿼리와 인증 세션 수명 도구만 노출한다. 아직 학교 상태를 변경하는 action을 노출하지 않으므로, 이 섹션은 향후 추가될 action을 위한 필수 게이트를 정의한다. 이 전체 정책을 이끄는 **플래그십 action 도구**는 `reserve_library_seat`이다 ([ssuAI vision](https://github.com/hoeongj/ssuAI/blob/main/docs/vision.md) 참조). 아래 규칙이 전부 충족되기 전까지 그 도구는 출시할 수 없다.

### 읽기 접근에 대한 동의

- ssuAI가 처음으로 사용자를 대신해 학교 시스템에 접근할 때(LMS 동기화, u-SAINT 동기화), 사용자는 명시적 동의 화면을 봐야 한다. 화면에는 *무슨* 데이터가 조회되는지, *왜* 조회하는지, *어떻게 해제하는지*가 명시되어야 한다.
- 동의는 타임스탬프·범위와 함께 기록되며, 설정 페이지에서 취소할 수 있다.

### action에 대한 확인

학교 상태를 변경하는 도구(`reserve_library_seat`, LMS write, u-SAINT write)는 반드시:

1. **드라이런**을 먼저 보여준다: "도서관 3층 217번 좌석을 14:00–17:00으로 예약하겠습니다. 확인하시겠습니까?"
2. 명시적인 사용자 "확인" 입력을 요구한다 — 자동 확인이나 기본값 예스는 안 된다.
3. 실행 **전에** 감사 로그 행을 기록하고(요청 페이로드, 사용자 ID, 타임스탬프), 실행 **후에** 또 기록한다(결과, 오류 발생 시).
4. 최종 결과를 명확하게 노출한다: 성공, 부분 성공, 또는 실패. 오류를 조용히 삼키지 않는다.

### 감사 로그

action 기능이 추가될 때 공유 `action_audit` 테이블을 함께 출시한다. 전체 스키마, `PREPARED → EXECUTING → SUCCESS/FAILURE_*` 상태 머신, `prepare_X` + `confirm_action` MCP 형태는 [ADR 0015](adr/0015-action-tool-infrastructure.md)에 고정되어 있다. 이 문서가 보안 기준 문서로 남을 수 있도록 간략하게 요약하면:

- action당 하나의 행이 `PREPARED → EXECUTING →` 종료 상태까지 원위치에서 변경된다. `dry_run_preview`는 사용자에게 보여준 정확한 텍스트를 기록한다.
- DB 권한에 의해 추가 전용 — 애플리케이션 사용자는 이 테이블에 `DELETE` 권한이 없다. 사용자는 API로 자신의 행만 읽을 수 있고, API를 통해 삭제하는 사람은 없다.
- 입력 페이로드의 민감 필드는 쓰기 시점에 마스킹된다 (§4 로깅이 요구하는 것과 동일한 `@SensitiveField` 메커니즘). 쿠키, 토큰, 학교 비밀번호, upstream HTML은 행에 포함되지 않는다.

---

## 7. 인증 및 세션

Task 14 기준 구현 내용:

- **JWT를 불투명 세션 대신 선택.** 이 문서의 초안에서는 취소 가능성 때문에 불투명 Redis 기반 세션을 선호했지만, Task 14의 u-SAINT SSO 흐름이 Redis 도입 전에 출시해야 했고, ssuAI는 재사용 가능한 학교 비밀번호를 저장하지 않는다 (즉시 폐기하는 일회용 SmartID 토큰만 수신). 따라서 탈취된 access JWT의 피해 범위는 15분 TTL로 제한되어 있어, 대부분의 격차가 해소된다. refresh JWT는 `jti` 기반 denylist로 재사용을 막는다.
- **토큰 형태** — `JwtProvider`는 HS256 access JWT (15분 TTL)와 refresh JWT (14일 TTL)를 발급한다. 시크릿은 `SSUAI_JWT_SECRET` (32바이트 이상)에서 가져온다. 값이 비어 있으면 dev/test 편의를 위해 임시 랜덤 시크릿으로 폴백하므로, prod에서도 재시작 시 기존 토큰이 전부 무효화된다. `src/main/java/com/ssuai/global/auth/` 참조.
- **클라이언트에서 토큰이 존재하는 곳** — refresh JWT는 `HttpOnly`, `Secure`, `Path=/api/auth` 쿠키. Access JWT는 프론트엔드 메모리에만 존재 (localStorage·sessionStorage 절대 안 됨). 모바일은 동일한 엔드포인트에 동일한 `Authorization: Bearer` 형태를 사용한다.

  | 환경 | `SameSite` | 이유 |
  |------|------------|------|
  | dev/test (`localhost:3000` ↔ `localhost:8080`) | `Lax` | 퍼블릭 서픽스 규칙 하에서 동일 사이트; Lax는 개발 도구에서 크로스 사이트 유출을 방지한다. |
  | prod (`ssuai.vercel.app` ↔ `ssumcp.duckdns.org`) | `None` | 등록 가능한 도메인이 다름 → 크로스 사이트. `/api/auth/refresh`에서 쿠키가 흐르려면 `None`이 필수; `Secure`는 `None`의 요구사항. |

  오버라이드는 `application-prod.yml`에 있다 (`ssuai.auth.refresh-cookie.same-site: None`). 전체 크로스 사이트 쿠키 인증 설계는 ADR 0014 Addendum 참조.
- **ssuAI 측 비밀번호 없음.** ssuAI는 사용자가 선택한 비밀번호를 저장하지 않는다. u-SAINT SmartID가 자체 로그인 페이지에서 사용자의 학교 비밀번호를 처리한다. 우리는 SSO 콜백을 통해 일회용 `sToken`·`sIdno` 쿼리 파라미터만 받아, `SaintSsoService.authenticate` 내부에서 신원으로 교환하고 즉시 폐기한다. 롤링할 `BCryptPasswordEncoder`는 없다.
- **로그인 횟수 제한** — 운영 보안 강화 항목으로 남는다. 사용자 수 또는 악용 노출이 늘기 전에 명시적인 횟수 제한을 추가한다.
- **Refresh rotation** — refresh JWT는 각 `/api/auth/refresh` 호출 시 교체된다. 성공한 refresh 요청은 사용된 기존 refresh JWT의 `jti`를 denylist에 넣고, 새 refresh JWT를 HttpOnly 쿠키로 내려준다. `/api/auth/logout`도 유효한 refresh 쿠키가 있으면 해당 `jti`를 denylist에 넣는다.
- **Refresh denylist 한계** — 현재 `RefreshTokenDenylist`는 인메모리 구현이라 단일 JVM 인스턴스와 재시작 전까지만 재사용 방지를 보장한다. Postgres 도입 후에는 만료 시각을 가진 DB-backed denylist로 바꿔 다중 인스턴스와 재시작 후에도 같은 보장을 유지한다.

---

## 8. 전송 및 네트워크

- 인터넷에서 접근 가능한 모든 환경에서 HTTPS만 사용한다. 로컬 개발은 평문 HTTP를 사용할 수 있다.
- 프로덕션 응답에 HSTS 헤더를 포함한다.
- CORS allowlist는 명시적(배포된 프론트엔드 origin)이며, 절대 `*`가 아니다.
- **`Access-Control-Allow-Credentials: true`**는 크로스 사이트 프론트엔드가 `credentials: 'include'`로 호출하는 모든 엔드포인트(현재 `/api/**` 전체)에 필요하다. 이것이 없으면 200 OK 응답도 프론트엔드 JavaScript에 숨겨져 사용자는 백엔드 에러 없이 일반적인 실패 메시지를 보게 된다. `ApiCorsDefaults`가 이를 설정하며, 이를 되돌리는 회귀는 `WebCorsConfigTest`·`WebCorsProdConfigTest`로 잡힌다. 이 조합은 `allowedOrigins`가 명시적 목록(`*` 아님)일 때만 유효하며, 현재 그렇게 되어 있다.
- 학교 시스템으로의 아웃바운드 연결은 학교 사이트가 지원하는 경우 HTTPS를 사용한다. 학교 엔드포인트가 HTTP 전용이라면 문서화하고 응답을 신뢰하지 않는 것으로 취급한다 (방어적으로 파싱한다).

---

## 9. 입력 검증 및 출력 처리

- 모든 요청 DTO는 Jakarta Bean Validation(`@NotBlank`, `@Size`, `@Pattern` 등)을 사용한다. Controller 레이어에서 이를 강제하며, 하위 레이어는 검증된 입력을 가정할 수 있다.
- 데이터베이스 접근은 JPA·파라미터화된 쿼리만 사용한다 — 문자열 연결 SQL은 절대 안 된다.
- 프론트엔드는 React의 기본 이스케이핑으로 사용자 제공 콘텐츠를 렌더링한다. `dangerouslySetInnerHTML` 사용은 PR에 명시적 보안 리뷰 코멘트가 필요하다.
- 아웃바운드 HTML 파싱 (Jsoup, Playwright)은 학교 HTML을 신뢰하지 않는 것으로 취급한다: 저장 전에 정제하고, 사용자에게 원시 HTML을 다시 렌더링하지 않는다.

---

## 10. 의존성 및 공급망

- Gradle과 npm/pnpm 의존성 버전을 고정하고, 락파일(`gradle.lockfile`, `pnpm-lock.yaml`)을 커밋한다.
- 이 저장소에서 Gradle과 GitHub Actions에 대해, 그리고 별도 `ssuAI` 저장소에서 pnpm 의존성에 대해 Dependabot을 활성화 상태로 유지한다.
- `./gradlew dependencyCheckAnalyze` (OWASP Dependency-Check) 또는 동등한 SCA 도구를 월별로, 그리고 모든 릴리즈 브랜치에서 실행한다.
- 헬퍼 함수 하나를 위해 의존성을 추가하지 않는다 — 공급망 위험이 그 값어치를 하지 않는다.

---

## 11. 크롤링 예절

ssuAI는 숭실대 자체 시스템을 크롤링한다. 정중하게 처리한다:

- 클라이언트를 정직하게 식별한다: `ssuAI/0.1 (+연락처 이메일)` 같은 `User-Agent`면 충분하다.
- 공개 페이지의 `robots.txt`를 준수한다.
- 적극적으로 캐시한다 (`architecture.md` §7 참조) — 캐시 히트 하나가 학교 서버로의 요청 하나를 줄인다.
- 커넥터별로 아웃바운드 호출을 횟수 제한한다 (예: 학식 사이트에 최대 1 req/sec). 커넥터 안에 간단한 토큰 버킷으로 구현한다.
- `429` 또는 `503` 시 지수 백오프한다. 반복 요청하지 않는다.
- 사용자를 보호하기 위해 설계된 자동화 방지 수단(CAPTCHA, IP 바인딩)을 우회하지 않는다. 보호 장치가 있다면 멈추고 재고하라는 신호이지, 회피하라는 신호가 아니다.

---

## 12. 기능별 보안 체크리스트

새 기능을 머지하기 전에 작성자(및 리뷰어)가 확인:

- [ ] 이 기능이 **공개(Public)** 클래스보다 높은 데이터를 다루는가? 그렇다면 무엇인지 나열한다.
- [ ] 새로운 시크릿이 도입되는가? 그렇다면 환경 변수로 구동되고 `.env.example`에 있는가?
- [ ] 새로 로깅되는 것이 있는가? §4 "절대 로깅하지 말아야 하는 것"과 대조했는가?
- [ ] 학교 데이터 기능이라면: 명시적 사용자 동의가 있는가?
- [ ] action 기능이라면: 드라이런 + 확인 + 감사 로그가 있는가?
- [ ] Controller 경계에서 입력이 검증되는가?
- [ ] 테스트에 실제 개인 데이터가 없는가 (픽스처에 실제 학번·성적·쿠키 없음)?
- [ ] 의존성이 추가됐는가? 버전이 고정됐는가? 신뢰할 수 있는 관리자인가?

이 체크리스트는 프로젝트에 PR 템플릿이 생기면 복사해서 사용할 수 있도록 여기에 둔다.

---

## 13. 인시던트 대응 (경량)

실제 또는 의심되는 유출이 발생하면:

1. **피해 확산 차단.** 유출된 시크릿을 즉시 교체한다(`SSUAI_CREDENTIAL_ENCRYPTION_KEY`, DB 비밀번호, API 키 등).
2. **세션 무효화.** 사용자 세션이나 학교 쿠키가 영향을 받을 수 있다면, 영향받은 모든 사용자의 재로그인을 강제한다.
3. **히스토리에서 제거.** 커밋된 시크릿은 git 히스토리에 영구적으로 남는다 — 교체한다. 히스토리 단독 재작성은 시도하지 않는다.
4. **사용자에게 알림.** 개인 데이터나 학교 데이터가 노출됐을 수 있다면 사용자에게 알린다. PR보다 정직함이 우선이다.
5. **간단한 사후 분석 작성.** `docs/incidents/YYYY-MM-DD.md`에: 무슨 일이 있었는지, 어떻게 발견했는지, 무엇이 변경됐는지, 앞으로 다르게 할 것이 무엇인지. 사후 분석은 학습을 위한 것이지 비난을 위한 것이 아니다.

---

## 14. 현재 보안 상태

이 서버는 이제 연동된 프로바이더 세션과 민감한 학사 데이터를 처리한다. 따라서:

- 프로바이더 세션 자료는 시크릿이며 암호화되거나 임시적으로 유지되어야 한다. 로깅되거나 도구 응답을 통해 반환되어서는 안 된다.
- MCP private 도구는 해당 프로바이더가 연동될 때까지 `AUTH_REQUIRED`를 반환한다. `get_library_seat_status`는 실제 upstream이 도서관 토큰을 요구하므로 이 규칙을 따른다.
- 채팅 턴에서 private 도구를 호출하면, 결과 합성과 이후 대화 히스토리는 공개 프로바이더 풀 대신 설정된 private LLM 프로바이더 정책을 사용한다.
- 도서관 좌석 캐시는 인증된 요청과 익명 요청의 경계를 분리해, 인증된 응답이 비인증 MCP 요청을 만족시키지 못하도록 한다.
- 아직 학교 상태를 변경하는 action 도구가 노출되지 않았다. 예약·제출 기능이 출시되기 전에 확인 및 감사 요구사항이 적용되어야 한다.

---

## 15. 남은 보안 결정

- 프로바이더 로그인과 MCP 인증 엔드포인트에 대한 구체적인 횟수 제한과 모니터링 임계값을 정의한다.
- 예약 도구가 구현되기 전에 action 감사 페이로드 저장과 마스킹을 결정한다.
- 배포가 멀티 인스턴스가 되거나 지속적인 실사용자 트래픽을 지원하게 되면 세션 스토어 내구성과 취소를 재검토한다.
## 로그 금지 항목

| 항목 | 금지 패턴 | 허용 패턴 |
|------|----------|---------|
| `sessionKey` | `log.info("... sessionKey={}", sessionKey)` | `log.info("... sessionKey={}", LibrarySessionStore.fingerprint(sessionKey))` |
| `accessToken` / `token` | 값 직접 로그 | `LibrarySessionStore.fingerprint(token)` 또는 로그 생략 |
| `password` | 어떤 형태로도 금지 | - |
| `Authorization` 헤더값 | 직접 로그 금지 | - |
| 쿠키값 | 값 직접 로그 금지 | 쿠키 이름만 로그 허용 |

`LibrarySessionStore.fingerprint(value)`는 SHA-256 해시의 앞 8자 hex를 반환한다. 로그 상에서 같은 값을 추적할 수 있으면서, 원문을 복원할 수 없도록 만든 디버깅용 식별자다.
