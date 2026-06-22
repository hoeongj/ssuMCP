# ADR 0061 — per-IP rate limiting + 입력 길이 상한

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-21 |
| 상태 | Accepted — 구현·배포(`ed1f108` #121) |
| 범위 | `RateLimitFilter`/`RateLimitProperties`/`IpRateLimiter`/`ClientIpResolver`(`global.security`) + 요청 DTO `@Size` |
| 연관 ADR | [0029](0029-p1-3-ratelimiter-bulkhead.md)(upstream Pyxis RateLimiter — 별개 계층) |
| 연관 사건 | TROUBLESHOOTING 사건 17 |

---

## 배경 — 무슨 문제

abuse 노출 엔드포인트에 횟수 제한이 없었다:

- `POST /api/library/login` — oasis.ssu.ac.kr에 대한 비밀번호 brute-force, 그리고 우리의 공유 egress IP가 비정상 로그인 볼륨으로 학교 WAF에 차단될 위험.
- `POST /api/chat` — LLM 비용 소진(매 요청이 유료 provider로 fan-out 가능).

(주의: ADR 0029의 Resilience4j RateLimiter는 **우리→Pyxis upstream** 호출 상한이고, 본 ADR은 **클라이언트→우리** inbound 상한이라 계층이 다르다.)

## 결정

inbound abuse-prone 엔드포인트에 **per-IP fixed-window rate limit**을 servlet filter(`RateLimitFilter`)로 건다.

- 정확 경로 매칭만: `/api/library/login`·`/api/chat` (나머지 미적용 — 정상 사용자 클릭을 막지 않기 위해).
- 기본 한도: login 10/min, chat 30/min (`ssuai.ratelimit.*`로 환경별 튜닝). **관대하게 설계** — abuse를 막되 정상 사용자를 잠그지 않는다.
- 초과 시 `429` + `Retry-After` 헤더 + `ApiResponse.error("RATE_LIMITED", ...)` JSON.
- 클라이언트 IP는 `ClientIpResolver`가 `X-Forwarded-For` **좌측 엔트리**(k3s ingress가 prepend, `client, proxy1, ...`)로 해석, 없으면 `getRemoteAddr()`.
- 함께: 요청 DTO에 `@Size` 길이 상한 추가(과대 페이로드 차단).

## 대안과 기각 이유

- **`@RestControllerAdvice`/인터셉터로 제한**: filter가 MVC dispatcher 밖에서 동작해 예외가 advice에 안 닿으므로 429를 inline 작성. CSRF 가드(ADR 0057)와 동일 패턴으로 통일. filter 채택.
- **모든 `/api/**`에 일괄 적용**: 정상 대시보드 폴링/SSE까지 막아 회귀. 정확 경로 2개만. 기각.
- **token bucket(burst 허용)**: fixed-window가 구현·검증이 단순하고 abuse 차단 목적엔 충분. 기각(필요 시 후속).
- **분산 카운터(Redis) 선도입**: 현재 replica=1이라 per-pod로 충분. 멀티포드 전환 시 shared store 필요(주석·security-followups.md에 명시). 기각.
- **`X-Forwarded-For`를 신뢰 안 함**: ingress 뒤라 `getRemoteAddr()`는 ingress IP뿐이라 per-IP 버킷팅이 무의미. XFF는 **버킷팅 전용**(스푸핑 가능성을 인지하고 인증 결정엔 안 씀)으로 한정 사용. 채택.

## 동작 방식

- `shouldNotFilter`: 비-POST 또는 룰 경로 미매칭이면 통과.
- 매칭 시 `IpRateLimiter.tryAcquire(clientIp)` → allowed면 진행, 아니면 429 + `Retry-After`.
- caveat(주석에 명시): per-pod라 멀티 replica에선 shared store 필요. XFF는 버킷팅 전용.
- 13개 테스트 green.

## 예상 면접 질문

1. inbound rate-limit(이 ADR)과 outbound upstream RateLimiter(ADR 0029)는 무엇이 다른가? 왜 둘 다 필요한가?
2. ingress 뒤에서 per-IP를 어떻게 식별했나? `X-Forwarded-For` 좌측을 쓰면서 스푸핑 위험을 어떻게 한정했나(버킷팅 전용)?
3. servlet filter로 429를 inline 작성한 이유는? `@RestControllerAdvice`가 왜 못 잡나?
4. per-pod fixed-window의 한계와, 멀티 replica로 가면 무엇을 바꿔야 하나?
