# ADR 0081 — 글로벌 LLM 일/월 spend 서킷브레이커

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-07-10 |
| 상태 | Accepted — 구현 |
| 범위 | `global.resilience`(신규: `GlobalLlmSpendBreaker`·`GlobalLlmSpendProperties`·`GlobalLlmSpendMetrics`), `domain.academic.embedding.AcademicEmbeddingClient`(호출부), `domain.chat.service.LlmProviderChain`(호출부), `application.yml` |
| 연관 ADR | [0061](0061-per-ip-rate-limit-input-caps.md)(per-IP inbound rate limit — 이 유닛이 메우는 빈틈의 기존 경계), [0065](0065-rag-embedding-model-quota-fix.md)(임베딩 quota 실측치의 출처), [0069](0069-observability-three-pillars.md)(Micrometer 계측 컨벤션), [0080](0080-multipod-shared-ratelimit-dualcap.md)(Redis-shared 카운터·fail-open 컨벤션의 최초 확립 — 이 유닛이 그대로 재사용) |
| 연관 문서 | `SCALE-ROADMAP.md` A. Rate-limit/비용 통제 항목 A3 |
| 번호 의존성 | ADR 0080이 가장 최근 번호를 선점 — 이 ADR은 그 다음 번호 0081을 쓴다. |

---

## 배경 — 무슨 문제

`SCALE-ROADMAP.md` 감사 A3: 백엔드가 호출하는 metered LLM API — `LlmProviderChain`(10개 chat completion 프로바이더, 그중 Gemini 직접 호출·OpenRouter·Mistral은 실비용) + `AcademicEmbeddingClient`(Gemini `gemini-embedding-001` 임베딩) — 에는 **per-IP 요청 캡만** 있고(`ssuai.ratelimit.chat-per-minute=30`, ADR 0061) **글로벌 일/월 누적 상한이 없다**.

per-IP 캡은 "한 IP가 초당/분당 몇 번 부르는가"만 막는다. 서로 다른 IP가 여러 개면(정상 트래픽 증가로 실사용자가 늘거나, 공격자가 소스 IP를 회전시키면) 각 IP는 개별적으로 캡을 지키면서도 **전체 합산 호출량은 무한정 커질 수 있다** — 청구서가 무제한으로 열려 있는 구조다. 이건 ADR 0080이 고친 "per-pod 카운터가 N배 누수"(A1)와는 다른 축의 문제다: A1은 "같은 상한을 여러 pod가 각자 허용"했고, A3는애초에 "총량 상한 자체가 없다".

## 검토한 대안

### 1. per-IP 캡만 유지(현행 유지)

기각 — 문제 정의 자체가 "per-IP만으로는 총량을 못 막는다"는 것이므로 대안이 되지 못한다.

### 2. 글로벌 누적 카운터(채택)

ADR 0080이 이미 Redis-shared 카운터(`RAtomicLong` 고정윈도우, 기간을 키에 인코딩, TTL 자동 만료, Redis 장애 시 fail-open) 패턴과 컨벤션을 확립해 뒀다. 이번 유닛은 그 패턴을 "윈도우 = 초 단위 rate limit"에서 "윈도우 = 일/월 단위 spend 상한"으로 그대로 확장한다 — 새 알고리즘을 발명하지 않는다.

### 3. 예산 기반 토큰버킷(초당 refill)

기각 — 토큰버킷은 "순간 처리율"을 부드럽게 펴는 데 적합하지만(ADR 0080의 Pyxis 캡이 이미 이 용도로 존재), A3가 막으려는 건 순간 스파이크가 아니라 **하루/한달 누적 총량**이다. 토큰버킷으로 일/월 총량을 표현하려면 refill 속도를 total/86400s처럼 역산해야 하는데, 그러면 "일과 시작 시각에 버킷이 가득 차 있다가 하루 안에 조기 소진되면 그 뒤로는 계속 거부"라는 원하는 의미(고정 윈도우, 자정에 리셋)가 아니라 "언제나 초당 조금씩 새는" 의미가 되어 직관과 어긋난다. 고정윈도우 카운터가 "일/월 예산"이라는 요구사항에 더 직접적이다.

### 4. 외부 billing alert(Gemini/OpenRouter 등 프로바이더 자체 대시보드 알림)

기각(보완재로는 유지) — 프로바이더 쪽 alert는 사후 통지이고, 알림이 오는 시점엔 이미 상한을 넘겨 과금된 뒤다. 이 유닛이 요구하는 건 **사전 차단**(circuit breaker)이지 사후 알림이 아니다. 프로바이더 대시보드 알림은 이 브레이커와 상호 배타적이지 않고 별도로 켜 두면 되는 이중 방어선이라, "기각"이라기보다 "이 ADR의 범위 밖"에 가깝다.

## 선택

`global.resilience.GlobalLlmSpendBreaker` — Redis-shared 일/월 호출-카운트 카운터. 체크(`tryAcquire`)와 기록(`recordUsage`)을 분리한 2단계 API로, 호출부가 "성공한 호출만 예산을 소비한다"를 강제할 수 있게 했다(아래 "계측 순서" 절 참고).

**메터(meter) 두 개를 독립 운영한다** — `chat`(LlmProviderChain, 10개 프로바이더 합산 1개 예산)과 `embedding`(AcademicEmbeddingClient). 두 표면의 비용/quota 프로파일이 서로 무관하기 때문이다: 하나로 합치면 chat 트래픽 급증이 embedding의 (훨씬 작은 free-tier) 예산을 굶기거나 그 반대가 벌어진다.

### 계측 단위 선택 근거 — 왜 토큰이 아니라 호출 횟수인가

토큰 수는 이 강제 지점(enforcement boundary)에서 균일하게 얻을 수 없다. `LlmProviderChain`은 10개의 독립된 OpenAI-호환 프로바이더로 팬아웃하고, 그 응답은 `LlmCompletionResult`(providerName/model/message)로 정규화되는데 **usage/token 필드가 없다** — 추가하려면 10개 프로바이더 각각의 응답 파싱을 건드려야 한다(이번 유닛의 범위 밖, LlmProvider 구현체는 손대지 않았다). 반면 호출 횟수는 두 메터 모두에서 단일 지점(embed 배치 루프 / 프로바이더 attempt 성공 시점)에서 바로 잴 수 있다.

호출-횟수 상한이 실제로 비용을 간접적으로 통제하는 이유: 호출당 토큰 자체가 이미 다른 설정으로 별도 상한이 걸려 있다 —`ssuai.chat.llm.max-tokens=400`(응답 토큰), `ssuai.academic-policy.embedding.batch-size=8`(배치당 입력 텍스트 수, ADR 0065가 확정한 Gemini free-tier TPM 페이싱). 즉 "호출 1건의 비용 상한 × 호출 횟수 상한"이 총 비용의 상한을 준다 — 토큰 필드를 직접 재지 않아도 총량 통제 목적은 달성된다.

### 계측 순서 — check-then-call-then-record, increment는 성공 후에만

`tryAcquire(meter)`는 현재 카운트를 **읽기만** 한다(증가 없음). 실제 호출을 수행한 뒤, **성공했을 때만** `recordUsage(meter)`를 호출해 카운터를 올린다. 이 순서는 구현 디테일이 아니라 요구사항이다 — 반대로 하면(호출 전에 미리 증가) 업스트림이 5xx/429를 반복하는 구간에서 실패한 호출들이 예산을 계속 깎아 먹어, 정작 성공할 수 있었던 트래픽까지 브레이커가 막아버린다. 두 호출부 모두 이 규약을 지킨다:

- **`AcademicEmbeddingClient.embed()`**: 배치 루프에서 배치를 보내기 직전에 `tryAcquire("embedding")`. 거부되면 **기존에 이미 있던 "부분 성공 prefix 반환" 경로**(배치 실패/rate-limit 시 `return vectors`)를 그대로 재사용 — 새 에러 표면을 만들지 않았다. `embedQuery()`(단일 배치 `embed()`)가 거부되면 자연히 `float[0]`을 반환하고, 이는 `AcademicPolicyService.search()`가 이미 처리하는 "임베딩 실패 → lexical로 강등" 경로로 그대로 흡수된다. 배치가 성공적으로 디코딩된 직후에만 `recordUsage("embedding")`.
- **`LlmProviderChain.complete()`**: 프로바이더 attempt를 시작하기 전, `attempts.isEmpty()`일 때와 **완전히 동일한** `ChatUnavailableException()`을 던진다 — "쓸 수 있는 LLM이 없다"는 기존 실패 시맨틱을 그대로 재사용했다(새 예외 타입 없음). `circuitBreaker.executeSupplier(...)`가 예외 없이 반환한 직후(= 해당 프로바이더 호출이 진짜 성공한 시점)에만 `recordUsage("chat")` — `CallNotPermittedException`/`LlmProviderException`으로 catch 블록에 걸린 attempt는 이 줄에 도달하지 않는다.

### 임계값 산정

Gemini `gemini-embedding-001` free tier는 하루 1,000 요청으로 제한된다(SCALE-ROADMAP 감사 H1, ADR 0065가 실측). `embedding` 메터의 daily-call-ceiling을 **800**으로 잡아 이 하드 quota보다 낮게 설정했다 — 그래야 우리 브레이커가 먼저 열려 "제어된, 관측 가능한(WARN 로그 + `llm.spend.breaker.open` 메트릭) lexical 강등"으로 이어지고, Gemini의 원시 429가 요청 도중 그대로 튀어나오는 상황을 피한다. monthly는 20,000(≈ 하루 800씩 25일 여유).

`chat` 메터는 per-IP 캡(`chatPerMinute=30`, ADR 0061)이 이미 있는 상태에서, "실사용 수십명"(SCALE-ROADMAP 프레이밍) 규모의 정상 트래픽은 하루 최대 수천 건 수준(사용자 1명이 활발히 채팅해도 하루 수십~수백 턴, 각 턴이 프로바이더 폴백 포함 1~2회의 `complete()` 호출)으로 추정된다. daily-call-ceiling **5,000**은 이 추정치보다 10배 이상 여유를 둬 오늘 트래픽에서는 절대 걸리지 않으면서, "다수 IP가 각자 캡을 지키며 총량을 밀어붙이는" A3 시나리오는 확실히 막는다. monthly **100,000**은 daily 상한이 매일 소진돼도 20일치 버퍼가 있다.

두 메터 모두 `SSUAI_LLM_SPEND_*_CEILING` env로 오버라이드 가능 — 실측 트래픽이 쌓이면(Grafana의 `llm.spend.used`/`llm.spend.ceiling` 게이지로 확인) 숫자를 재보정하면 된다.

## Redis 장애 시 폴백 시맨틱

ADR 0080의 컨벤션을 그대로 따른다: Redis 호출에서 발생하는 모든 `RuntimeException`, 그리고 `redissonClient == null`(기능 비활성 또는 빈 없음)을 동일한 코드 경로로 취급해 **fail-open**한다 — `tryAcquire`는 무조건 `true`(호출 허용), `recordUsage`는 조용히 스킵. 두 경우 모두 WARN 로그 + `llm.spend.redis.fallback` 카운터를 남긴다.

ADR 0080의 두 사례(SharedIpRateLimiter, PyxisResilience)와 다른 점: **이 유닛에는 로컬(per-pod) 폴백 카운터가 없다.** 그 둘은 "이미 있던 로컬 리미터"로 되돌아갈 수 있었지만(Redis 도입 전부터 존재), 글로벌 spend 상한은 이번에 처음 생기는 기능이라 되돌아갈 로컬 버전 자체가 없다. per-pod 로컬 카운터를 새로 만드는 것도 검토했으나 기각 — A3가 고치려는 정확히 그 문제(pod별로 나뉜 카운터는 총량을 못 잡는다)를 폴백 안에서 재도입하는 셈이라, 차라리 "Redis가 죽으면 짧게 상한 자체가 없어진다"는 정직한 상태(+ 관측 가능한 메트릭)가 낫다고 판단했다. Redis는 여기서도 correctness가 아니라 efficiency/가시성 계층이라는 ADR 0024/0080의 원칙을 그대로 유지한다.

## 계측 (Micrometer, ADR 0069 컨벤션)

- `llm.spend.used{meter,window}` (gauge) — 현재 카운트. `window`는 `daily`/`monthly`.
- `llm.spend.ceiling{meter,window}` (gauge) — 설정된 상한.
- `llm.spend.breaker.open{meter,window}` (counter) — 상한 도달로 거부된 이벤트.
- `llm.spend.redis.fallback{meter,exception}` (counter) — Redis 장애/비활성으로 폴백한 이벤트.
- WARN 로그: 사용량이 `warnThresholdRatio`(기본 0.8) 이상일 때, 그리고 브레이커가 열릴 때.

## 트레이드오프

- **정확도 vs 관측성/가용성**: `tryAcquire`가 읽기만 하고 증가는 성공 후에 하는 구조라, 동시에 여러 요청이 같은 순간 `tryAcquire`를 통과한 뒤 나란히 성공하면 상한을 약간(동시성 정도만큼) 초과할 수 있다 — Redis 원자적 CAS로 "예약"까지 하는 설계도 검토했으나, 그러면 실패한 호출의 "예약"을 되돌리는 보정 로직이 필요해지고 그 자체가 새 실패 모드(보정 실패)를 만든다. spend 브레이커는 정밀한 과금 미터가 아니라 "폭주를 막는 러프한 안전망"이 목적이므로, 약간의 초과 허용이 로직 단순성 대비 합리적인 트레이드오프라고 판단했다.
- **Redis 장애 중 무방비**: 위 "로컬 폴백 없음" 절에서 설명한 대로, Redis가 죽으면 그 순간 A3의 보호가 사라진다(fail-open). ADR 0080과 동일한 판단(보조 인프라 하나 때문에 서비스 전체를 막지 않는다)을 재적용했다.
- **호출-횟수가 실제 비용과 정비례하지 않음**: 짧은 질문과 긴 tool-call 체인은 토큰 소비가 다른데 카운터는 똑같이 1을 더한다. 토큰 계측을 도입하려면 프로바이더별 응답 파싱을 건드려야 해서(위 "계측 단위 선택 근거" 참고) 이번 유닛 범위 밖으로 뒀다 — SCALE-ROADMAP 후속 항목으로 남긴다.

## 예상 면접 질문

1. **per-IP rate limit이 이미 있는데 왜 별도로 글로벌 spend 상한이 또 필요한가?** — per-IP 캡은 "한 IP가 얼마나 빨리 부르는가"라는 축을 막고, 글로벌 spend 상한은 "총 몇 명/몇 IP가 부르든 합산이 얼마인가"라는 독립된 축을 막는다. 실사용자가 늘거나(정상 성장) 공격자가 IP를 회전시키면 각 IP는 캡을 지키면서도 총량은 무한히 커질 수 있어, 두 축을 각각 잠그지 않으면 한쪽만으로는 청구서가 열려 있는 상태가 된다.
2. **토큰 수가 아니라 호출 횟수로 예산을 잰 이유는? 부정확하지 않나?** — 이 강제 지점에서 토큰 수를 균일하게 얻을 수 없다(10개 프로바이더의 정규화된 응답 DTO에 usage 필드가 없음, 추가하려면 각 프로바이더 파싱을 다 건드려야 함). 대신 호출당 토큰 자체가 이미 별도 설정(`max-tokens`, embedding `batch-size`)으로 상한이 걸려 있어 "호출당 비용 상한 × 호출 횟수 상한"이 총 비용의 상한을 준다 — 정밀한 과금 미터가 아니라 폭주 방지가 목적이라 이 정도 근사로 충분하다고 판단했다.
3. **카운터를 호출 "전"이 아니라 "성공 후"에 올리는 이유는?** — 호출 전에 올리면 업스트림이 반복 실패(5xx/429)하는 구간에서 실패한 시도들이 예산을 계속 깎아 먹어, 그 시점에 다른 정상 트래픽까지 브레이커에 걸려 차단된다. 실패는 애초에 비용이 청구되지 않았거나(대부분의 프로바이더가 실패 요청을 과금하지 않음) 청구돼도 우리가 막으려는 "다수 IP가 성공적으로 API를 두들겨 비용을 키우는" 시나리오와는 다른 문제라, "성공한 호출만 예산을 소비한다"는 시맨틱을 명시적으로 지켰다.
