# ADR 0080 — 멀티포드 대비: 공유 인바운드 rate limit + Pyxis dual cap + XFF 우측 hop 신뢰

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-07-09 |
| 상태 | Accepted — 구현 |
| 범위 | `global.security`(`IpRateLimiter`·`SharedIpRateLimiter`·`ClientIpResolver`·`RateLimitFilter`·`RateLimitFilterConfig`·`RateLimitProperties`), `global.resilience`(`PyxisResilience`·`PyxisResilienceProperties`), `RealLibrarySeatConnector`·`RealLibraryReservationConnector`(호출부만) |
| 연관 ADR | [0024](0024-redis-redisson-adoption.md)(Redis/Redisson 도입, graceful-degradation 컨벤션), [0029](0029-p1-3-ratelimiter-bulkhead.md)(Pyxis 단일 per-pod 캡의 최초 결정), [0061](0061-per-ip-rate-limit-input-caps.md)(per-IP inbound rate limit의 최초 결정), [0068](0068-testcontainers-jacoco-coverage.md)(Testcontainers Redis IT 컨벤션) |
| 연관 문서 | `SCALE-ROADMAP.md` Phase 1, 감사 항목 A1·A2 |
| 번호 의존성 | 0079는 병렬 진행 중인 `feat/multipod-claim-lease` 브랜치(PR 대기)가 선점 — 이 ADR은 그 다음 번호인 0080을 쓴다. 두 브랜치가 같은 시점에 병합되면 번호 충돌은 없다(서로 다른 파일 범위). |
| 후속 결정 | [0097](0097-pyxis-read-cap-fanout-sizing.md)에서 seat-scan fan-out에 맞춰 read cap을 cluster 20/s, per-user 8/s로 조정했다. write cap은 그대로다. |

---

## 배경 — 무슨 문제

`SCALE-ROADMAP.md` Phase 1은 백엔드를 단일 pod에서 멀티 pod(k3s replica > 1)로 늘리기 위한 사전 작업이다. 설계 결함 감사(A1·A2)에서 두 가지가 "replica=1을 전제로 조용히 틀리는" 코드로 지목됐다.

**A1 — per-pod 카운터가 멀티 pod에서 N배 누수.** 두 곳이 동일한 병을 앓는다.

1. `IpRateLimiter`(ADR 0061) — 인바운드 abuse 방지 rate limit(`/api/library/login` 10/min, `/api/chat` 30/min 등)이 JVM 로컬 `ConcurrentHashMap`에 카운터를 둔다. replica가 N개면 각 pod가 독립적으로 전체 한도를 허용해 실효 상한이 `한도 × N`이 된다. ADR 0061 자체가 "분산 카운터 선도입은 기각(당시 replica=1이라 불필요)"이라고 명시적으로 유보해 둔 부분이다.
2. `PyxisResilience`(ADR 0029) — 학교 시스템(oasis.ssu.ac.kr) 보호를 위한 Resilience4j `RateLimiter`(read 5/s, write 2/s)도 마찬가지로 pod-local이다. replica가 늘면 우리가 공유하는 학교 시스템에 가하는 실제 부하가 `5 × N`/s로 커진다 — egress IP 차단 리스크를 그대로 키우는 것이라 A1 중에서도 더 심각하다. 여기에 더해 기존 캡은 "전체 트래픽 5/s"만 있을 뿐 "사용자 1명이 그 5/s를 혼자 다 쓰는 것"을 막는 장치가 없었다 — 학교 보호 캡과 사용자 공정성 캡이 하나로 뭉쳐 있었다.

**A2 — XFF 좌측 신뢰로 인한 버킷 회전.** `ClientIpResolver`는 `X-Forwarded-For`의 **좌측** 엔트리를 클라이언트 IP로 신뢰했다. k3s Traefik ingress는 자신이 본 peer 주소를 헤더 **우측**에 append하는 구조라, 좌측은 클라이언트가 원하는 대로 채울 수 있는 완전히 위조 가능한 입력이다. 즉 공격자는 매 요청마다 `X-Forwarded-For: <임의값>`을 바꿔가며 자신의 rate-limit 버킷을 회전시켜 A1의 인바운드 한도를 무력화할 수 있었다.

## 검토한 대안

### 로컬 유지 / sticky session

k3s Ingress(Traefik)에 IP-hash 기반 sticky session을 걸어 같은 클라이언트가 항상 같은 pod로 가게 하면 로컬 카운터로도 정확하다. 기각 — ① 이 프로젝트의 다음 단계(SSE 좌석 구독, 좌석 이벤트 fan-out)가 이미 "어느 pod의 이벤트든 모든 pod의 구독자에 도달"을 전제로 하고 있어 sticky는 그 전제와 충돌하는 별도의 로드밸런싱 정책이다. ② sticky는 배포/재시작/스케일 이벤트 때 재분배가 일어나면 다시 깨진다 — 근본 해결이 아니라 회피다.

### Redis 공유 카운터(채택)

ADR 0024가 이미 "Redis는 correctness가 아니라 efficiency/scale-out 보조 인프라"라는 원칙과 Redisson 4.5.0 의존성, graceful-degradation 컨벤션(WARN + metric + fallback)을 확립해 뒀다. 이번 유닛은 그 컨벤션을 인바운드 rate limit과 Pyxis 업스트림 캡까지 확장한다.

### 토큰버킷 vs 고정윈도우(인바운드 A1)

인바운드 HTTP 스로틀은 **즉시** 허용/거부를 결정해야 한다(429를 내려주지, 요청 스레드를 블로킹하지 않는다) — 그리고 `Retry-After` 헤더를 위해 "윈도우가 언제 리셋되는지" 정확한 초 단위 값이 필요하다. `SharedIpRateLimiter`는 Redis 키에 윈도우 경계를 인코딩(`prefix:rule:ip:windowIndex`)하는 고정윈도우 카운터(`RAtomicLong.incrementAndGet` + 최초 hit에만 TTL 설정)를 택했다 — 이는 기존 `IpRateLimiter`(로컬 fallback)와 동일한 알고리즘이라 "replica=1에서 동일한 실효 한도"를 자연스럽게 만족하고, `Retry-After` 계산도 그대로 재사용할 수 있다.

Redisson `RRateLimiter`(토큰버킷)도 검토했으나 기각 — OVERALL 타입의 `RRateLimiter`는 즉시 accept/reject를 위한 "남은 시간" API가 마땅치 않고, 정확한 `Retry-After` 초를 뽑으려면 별도 계산이 필요해 이 용도엔 고정윈도우가 더 직접적이다.

### 토큰버킷(Pyxis 업스트림 A1)

Pyxis 호출은 정반대 성격이다 — 기존 Resilience4j `RateLimiter`가 이미 "즉시 거부"가 아니라 **최대 timeoutDuration(read 500ms/write 200ms)까지 기다렸다가** 그래도 permit이 없으면 거부하는 방식이었다(호출 스레드가 짧게 대기하는 것이 학교 시스템에 대해 스파이크를 매끄럽게 펴는 의도). 이 "대기 후 거부" 시맨틱을 유지해야 하므로, 인바운드와 달리 여기서는 Redisson `RRateLimiter.tryAcquire(1, timeout)`(bounded wait, 실제 토큰버킷)을 채택했다 — Redis 쪽에서 새 permit이 refill될 때까지 짧게 기다렸다가 판정하는 동작이 기존 Resilience4j RateLimiter의 동작과 의미적으로 가장 가깝다.

### XFF 우측 hop vs Proxy Protocol

Proxy Protocol(TCP 레벨에서 원본 클라이언트 IP를 전달)도 검토했으나 기각 — Traefik ingress 설정과 배포 매니페스트(`deploy/charts`) 변경이 필요하고, 이번 유닛의 범위는 애플리케이션 레이어 수정으로 한정했다(배포 차트는 이번 브랜치의 diff 범위 밖). 대신 **trusted-hop 개수만큼 우측에서 신뢰**하는 방식을 택했다: `X-Forwarded-For`는 각 프록시가 자신이 본 peer 주소를 **우측에 append**하는 구조이므로, 신뢰하는 프록시 개수(`trustedProxyCount`, 기본 1 = Traefik만)를 알면 우측에서 그 개수만큼 뒤로 간 위치가 항상 진짜 클라이언트다. 공격자가 좌측에 몇 개를 덧붙이든(가짜 프록시 체인을 흉내내든) 우측 고정 위치는 오염시킬 수 없다 — 이번 감사가 고치려는 정확히 그 취약점이다. 헤더 엔트리 수가 `trustedProxyCount`보다 적으면(홉이 예상보다 적음 — 헤더 위조 또는 배포 변경) 안전하게 `getRemoteAddr()`로 폴백한다(추측하지 않는다).

## 선택

**A1 — 인바운드(`SharedIpRateLimiter`)**: Redisson `RAtomicLong` 기반 고정윈도우 카운터로 `IpRateLimiter`와 동일한 알고리즘을 Redis에 구현. `ssuai.ratelimit.redis-enabled`(기본 `true`)로 켜져 있으며, replica=1에서는 로컬과 동일하게 동작하고 replica>1부터 실제 공유 효과가 생긴다 — **설정 변경 없이 정답이 되는 기본값**이다.

**A1 — Pyxis dual cap(`PyxisResilience.acquireDistributed`)**: 읽기/쓰기 각각에 대해 두 단계 Redisson `RRateLimiter`를 순서대로 확인한다.

1. **per-user fairness cap(먼저)** — `ssuai:resilience:pyxis:v1:{read|write}:user:{fingerprint}` 키, 기본 read 2/s·write 1/s. `fingerprint`는 `PyxisResilience.principalOf(token)`이 Pyxis 인증 토큰을 SHA-256 해시해 만드는 16-hex 문자열이다(원본 토큰을 Redis key space에 절대 남기지 않음 — ADR 0024 D3, `LibrarySessionStore.fingerprint`와 동일한 프라이버시 원칙을 재적용). write의 per-user 기본값(1/s)은 cluster 한도(2/s)보다 낮게 잡았다 — 그래야 사용자 1명이 write cluster budget 전체를 혼자 다 못 쓰고 최소 2명이 동시에 쓸 여지가 남는다.
2. **cluster cap(나중)** — `ssuai:resilience:pyxis:v1:{read|write}:cluster` 키, ADR 0029의 실효 한도(read 5/s, write 2/s)를 그대로 유지한 채 전체 replica가 이 하나의 budget을 나눠 쓴다.

후속으로 ADR 0097이 read 기본값을 per-user 8/s, cluster 20/s로 조정했다. 위 숫자는 ADR 0080 당시의 역사적 결정이며, write 기본값은 여전히 per-user 1/s, cluster 2/s다.

**확인 순서 자체가 설계 결정이다 — per-user를 반드시 먼저 본다.** 반대로 cluster permit을 먼저 획득하면, fairness cap에 걸려 거부될 사용자의 매 시도가 이미 공유 budget에서 permit 하나를 소모한 뒤다 — 즉 탐욕적인 사용자 1명이 "거부당하면서도" 초당 요청을 퍼붓는 것만으로 학교 보호 예산 전체를 고갈시켜, fairness cap의 존재 이유가 무력화된다. per-user를 먼저 보면 fairness 거부는 그 사용자 자신의 예산만 낭비하고 cluster budget은 다른 사용자를 위해 온전히 남는다. 반대 방향의 낭비(per-user permit을 소모한 뒤 cluster가 거부)는 그 낭비가 해당 사용자 본인에게만 돌아가므로 수용 가능하다 — 두 낭비 방향 중 "공유 자원을 지키는" 쪽을 택한 것이다. 회귀 방지 테스트: `perUserFairnessCapDeniesEvenWhenClusterCapHasRoom`이 fairness 거부 시 cluster 리미터의 `tryAcquire`가 **한 번도 호출되지 않음**을 Mockito verify로 고정한다.

두 캡 모두 `RRateLimiter.tryAcquire(1, timeout)`을 쓰고, `timeout`은 기존 Resilience4j 설정과 동일한 값(read 500ms/write 200ms)이다. 거부되면 기존 로컬 리미터가 던지던 것과 **동일한 클래스**(`RequestNotPermitted.createRequestNotPermitted(...)`)를 던진다 — `GlobalExceptionHandler`에 이 예외 전용 핸들러가 없어 그대로 generic 500 경로로 떨어지는 것도 기존과 동일하다. 요구사항대로 "무엇이 실패했을 때 벌어지는 일"은 손대지 않고 카운터의 범위(scope)만 바꿨다.

**A2 — `ClientIpResolver`**: `trustedProxyCount`(기본 1, `ssuai.ratelimit.trusted-proxy-count`)만큼 우측에서 위치를 계산해 사용. Vercel이 앞단에 추가되는 라우트는 2로 설정.

## Redis 장애 시 폴백 시맨틱

ADR 0024가 확립한 컨벤션(캐시/pub-sub/scheduler lock 모두 "WARN + metric + 즉시 폴백, correctness는 다른 곳이 보장")을 그대로 따른다 — Redis는 여기서도 **correctness가 아니라 efficiency 계층**이다.

- **인바운드(`SharedIpRateLimiter`)**: Redis 호출에서 발생하는 모든 `RuntimeException`을 잡아 WARN 로그 + `ratelimit.redis.fallback` 카운터를 남기고, 내장된 로컬 `IpRateLimiter`(동일 한도/윈도우)로 즉시 위임한다. `redissonClient == null`(기능 비활성 또는 빈 없음)도 완전히 같은 코드 경로로 취급한다 — "비활성"과 "장애"를 구분하지 않고 둘 다 안전한 기본값(로컬 카운팅)으로 수렴시킨다.
- **Pyxis(`PyxisResilience.acquireDistributed`)**: 별도의 폴백 리미터를 새로 만들지 않는다. **기존에 이미 있던 로컬 Resilience4j `RateLimiter`(현재 read 20/s, write 2/s; ADR 0097)를 그대로 재사용**한다 — Redis 체크가 예외를 던지면 WARN + `pyxis.ratelimit.redis.fallback` 카운터만 남기고 조용히 건너뛰어, 실행이 그 아래의 변경되지 않은 로컬 데코레이터 체인(Bulkhead→RateLimiter→CircuitBreaker→Retry)으로 그대로 흘러간다. 즉 Redis 장애 순간에는 오늘의 per-pod 동작으로 정확히 되돌아간다 — 새 코드가 아니라 "원래 있던 안전망"이 그대로 작동하는 구조라 검증 부담도 적다.
- 두 경우 모두 **fail-open**(트래픽을 막지 않음)을 택했다. fail-closed(Redis 장애 = 전체 차단)는 캐시/락 같은 보조 인프라 하나의 blip이 서비스 전체를 세우는 결과라 ADR 0024의 원칙과 정면으로 배치된다.

## 트레이드오프

- **정확도 vs 가용성**: Redis 장애 중에는 다시 "pod별 독립 카운터"로 돌아가므로, 그 짧은 창(window) 동안은 A1이 고치려던 `한도 × N` 문제가 일시적으로 재발한다. 학교 시스템 보호라는 목적에서 보면 이 창이 짧고 드물다는 전제(Redis는 로컬 단일 pod, 영속성 없음 — ADR 0024 D1)로 감수 가능하다고 판단했다.
- **per-user fairness의 우회 가능성**: fairness 키는 Pyxis 인증 토큰 지문이다. 한 사용자가 여러 계정(여러 토큰)으로 동시에 요청하면 fairness cap을 나눠 우회할 수 있다 — 이건 cluster cap이 여전히 전체 상한을 잡아 주므로 "학교 보호"라는 1차 목표는 훼손되지 않고, "공정성"이라는 2차 목표만 완화된다.
- **Redis round-trip 비용**: 인바운드 요청마다 Redis round-trip이 하나 추가된다(트래픽 볼륨이 있는 로그인/챗/예약-확정/리프레시 경로). Pyxis 쪽은 절대 호출량 자체가 초당 5-7건 수준으로 작아 무시할 수 있는 비용이다.
- **per-user Redis 키 누적**: `expire(5분)`으로 각 fairness 키가 자기 유효기간을 갖게 했다 — 활성 사용자가 계속 있으면 갱신되고, 조용해지면 5분 뒤 사라진다. 무한정 쌓이지 않는다.

## 예상 면접 질문

1. **"한도 × N" 문제를 Redis 하나로 옮기면 그 Redis 자체가 새 SPOF(단일 장애점) 아닌가?** — 아니다. ADR 0024의 경계와 동일하게 Redis를 correctness가 아니라 efficiency 계층으로만 쓴다. 장애 시 fail-open으로 기존 per-pod 동작(정확히 오늘의 동작)으로 되돌아가므로, Redis가 죽어도 서비스는 (일시적으로 덜 정확하게) 계속 동작한다 — SPOF는 "Redis가 죽으면 서비스가 죽는" 구조를 말하는데 여기엔 그런 하드 의존이 없다.
2. **Pyxis 캡을 학교 보호용과 사용자 공정성용, 두 개로 나눈 이유는? 하나로는 왜 안 되나?** — 하나(cluster cap)만 있으면 "전체 budget을 넘지 않는다"는 보장은 되지만 "한 사용자가 그 budget을 혼자 다 쓴다"는 걸 막지 못한다. ADR 0097 기준 현재 read cluster budget은 20/s다. 반대로 사용자별 캡만 있고 cluster cap이 없으면 사용자 수가 늘 때 학교 시스템에 걸리는 총 부하가 다시 무한정 커진다. 두 관심사(업스트림 보호 vs 공정한 분배)는 독립적인 축이라 캡도 독립적으로 둬야 한 축을 조정해도 다른 축이 깨지지 않는다.
3. **`X-Forwarded-For` 좌측 대신 우측에서 trustedProxyCount만큼 신뢰하는 게 왜 좌측 신뢰보다 안전한가?** — 헤더는 각 프록시가 자신이 본 peer 주소를 뒤(우측)에 append하는 구조라, 우측 끝에서 N번째(N=신뢰하는 프록시 수)는 항상 "우리 자신의 인프라가 실제로 관측한 값"이다. 좌측은 클라이언트가 최초로 보내는 값부터 시작하므로 100% 클라이언트가 결정할 수 있는 입력이고, 몇 개를 덧붙이든(가짜 체인) 우측 고정 위치는 오염시키지 못한다.
4. **Redis 장애 폴백에서 Pyxis 쪽은 왜 별도 로컬 리미터를 새로 안 만들고 기존 걸 재사용했나?** — 기존 Resilience4j `RateLimiter`가 이미 같은 실효 한도로 설정돼 있고 검증된 코드였다. ADR 0080 당시에는 5/s·2/s였고, 현재는 ADR 0097 기준 read 20/s·write 2/s다. 새 fallback을 또 만들면 "두 리미터가 같은 숫자를 유지하는지" 계속 동기화해야 하는 부담이 생긴다. 기존 걸 그대로 두고 그 앞에 분산 게이트만 추가하는 구조라, Redis가 빠지면 자연히 원래 검증된 경로로 축소(degrade)된다.
