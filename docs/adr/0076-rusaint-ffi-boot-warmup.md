# ADR 0076 — rusaint FFI 부팅 워밍업 (로그인 첫-시도 실패 근본 수정)

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-07-04 |
| 상태 | Accepted — 구현·머지 |
| 범위 | `RusaintClient.warmUp()` + `RusaintUniFfiClient` 구현 + `RusaintFfiWarmup` ApplicationRunner |
| 연관 문서 | ADR 0017(rusaint FFI 통합), ADR 0014(SSO redirect-callback), TROUBLESHOOTING 2026-07-04 |

---

## 배경 — 증상

사용자 보고: u-SAINT 로그인이 **첫 시도만 실패하고 곧바로 재로그인하면 성공**한다. 재현·로그 상관: 콜드 pod에서 로그인하면 `saint sso-callback`이 실패해 `/auth/return?error=...`로 튕기고, 같은 화면에서 다시 로그인하면 성공한다. prod 로그에는 성공한 `saint rusaint session stored`가 pod당 처음엔 1회만 찍히고, 그 직전 실패 시도는 세션 저장 전에 죽는다.

## 원인

로그인 콜백은 `SaintSsoService.authenticate` → `RusaintUniFfiClient.authenticateWithToken`을 호출하고, 여기서 `USaintSessionBuilder().withToken(sIdno, sToken)`으로 **일회성 SmartID 토큰을 소비**하며 SAP WebDynpro 세션을 맺는다. `withToken()` 이후의 학생정보 조회(`general()`)는 이미 `withRetry(3, backoff)`로 감싸져 있지만, **`withToken()` 자체는 재시도가 없다 — 그리고 일회성 토큰이라 같은 토큰으로는 재시도가 불가능하다.**

`withToken()`이 실패하는 지점: **콜드 JVM에서의 첫 FFI 호출 비용**. `dev.eatsteak.rusaint.ffi.UniffiLib` object가 처음 초기화될 때 ① `librusaint_ffi.so`를 dlopen하고 ② UniFFI contract-version + 수백 개 메서드 checksum을 검증한다(`Native.register` + integrity check, `rusaint_ffi.kt:722~903`). 이 일회성 네이티브-로드 지연이 **콜드 SAP 핸드셰이크 위에 스택되면서** 간헐적으로 핸드셰이크/토큰 유효 창을 넘겨 첫 로그인이 실패했다. 두 번째 로그인은 네이티브가 이미 웜이라 성공한다 — 사용자 증상과 정확히 일치.

## 검토한 대안

### `withToken()`을 재시도로 감싼다 ❌
`general()`처럼 backoff 재시도. 기각 — sToken/sIdno는 **일회성**이라 첫 호출에서 소비되면 같은 토큰 재시도는 무조건 실패한다. 실패가 토큰 소비 이후 어느 단계든 재시도가 무의미하고, 소비 이전이라도 어느 지점에서 소비되는지 rusaint 내부에 의존해 불안정하다.

### 프론트에서 콜백 실패 시 SSO를 1회 자동 재시작 ❌
`/auth/return`이 에러면 새 토큰으로 SmartID를 한 번 더 자동 왕복. 기각(단독으로는) — 사용자에겐 매끄러워 보이지만 **근본 원인(콜드 비용)을 숨길 뿐**이고, 실패 원인이 콜드가 아닌 진짜 인증 실패(비번 오류 등)일 때 무한 왕복 위험이 있다. 근본 수정 후 남는 잔여 플레이크에 대한 UX 안전망으로는 고려 가능하나 이번 범위에서는 제외.

### 요청 경로에서 콜드 비용 제거 = 부팅 워밍업 ✅ (채택)
첫 FFI 호출 비용을 **로그인 요청이 아니라 pod 부팅 시점**에 지불한다. 부팅 직후 백그라운드로 `USaintSessionBuilder()`를 한 번 생성·해제해 `UniffiLib` 초기화(네이티브 로드 + checksum 검증)를 강제한다 — 네트워크 I/O 없이 네이티브 핸들만 할당·반납. 실제 로그인이 도착할 즈음(부팅 후 수 분~수 시간)엔 항상 웜이라 `withToken()`은 핸드셰이크 비용만 낸다.

## 핵심 결정

- **비동기 데몬 스레드**: 워밍업이 readiness를 지연시키지 않도록 `ApplicationRunner`가 데몬 스레드로 실행. 로그인은 부팅 몇 분 뒤에 오므로 비동기로도 제때 웜이 된다. 워밍업이 startup을 블록하지 않고, 네이티브 로드가 느리거나 실패해도 서비스 기동에 무해.
- **fail-safe**: `warmUp()`은 `runCatching`으로 예외를 삼키고 warn 로그만 — 네이티브 계층이 깨져도 startup은 진행되고, 로그인은 (콜드 비용을 lazy하게 내며) 여전히 동작한다.
- **인터페이스 default no-op**: `RusaintClient.warmUp()`을 default no-op으로 두어 mock 커넥터·테스트 더블은 무영향. 실 FFI 클라이언트만 override.
- **`@Profile("!test")`**: 단위/통합 테스트가 네이티브를 eager 로드하지 않도록 러너는 test 프로파일에서 비활성. 러너 동작은 mock 클라이언트로 `RusaintFfiWarmupTests`가 검증(비동기 호출 + 실패 비전파).

## 동작 방식

`RusaintFfiWarmup implements ApplicationRunner`가 컨텍스트 준비 후 데몬 스레드에서 `rusaintClient.warmUp()`을 호출하고 소요 ms를 로깅. `RusaintUniFfiClient.warmUp()`은 `USaintSessionBuilder().useAuto { }`로 FFI를 터치해 네이티브 로드·checksum 검증을 완료시킨다.

## 검증

- `RusaintFfiWarmupTests` 2건: ① 러너가 `warmUp()`을 (데몬 스레드에서) 호출한다(`timeout(2000)` verify) ② `warmUp()`이 던져도 `run()`이 예외를 전파하지 않는다.
- prod: 배포 후 로그에 `rusaint FFI warmup finished in {}ms`가 부팅 직후 찍히고, 이후 첫 실계정 로그인이 1회에 성공하는지 확인(사용자 사인오프).

## 예상 면접 질문

1. **일회성 토큰이라 재시도가 안 되는데 어떻게 안정화했나?** 실패가 "인증 로직"이 아니라 "콜드 네이티브-로드 지연"이라는 걸 로그 상관으로 규명하고, 재시도 대신 **비용을 요청 경로 밖(부팅)으로 옮겼다**. 재시도할 수 없는 실패는 재시도로 못 고치고, 원인이 되는 비용을 사전 지불하는 게 정답이다.
2. **왜 startup을 블록하지 않고 비동기로?** readiness 지연 방지 + 워밍업 실패가 기동을 막지 않게 하기 위해. 로그인은 부팅 몇 분 뒤 오므로 비동기 웜업으로 충분하고, 최악의 경우에도 첫 로그인이 콜드 비용을 lazy하게 내는 현행과 같아 회귀가 없다.
3. **FFI 첫 호출이 왜 비싼가?** `.so` dlopen + UniFFI가 라이브러리 contract 버전과 수백 개 익스포트 함수의 checksum을 런타임 검증한다(ABI 불일치 조기 차단). 이 일회성 비용이 콜드 SAP 핸드셰이크와 겹쳐 토큰 유효 창을 넘겼다.
