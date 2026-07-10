# ADR 0091 — Reservation intent-status bus graduated to Kafka

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-07-10 |
| 상태 | Accepted — 라이브 cutover 완료·검증 (2026-07-10, PR #192→#195 `825745e`) |
| 범위 | 도서관 예약 intent-status의 크로스-포드 fan-out을 Redisson RTopic → Kafka로 승격 (Phase 2-C) |
| 연관 문서 | ADR 0071(Kafka graduation trigger), ADR 0089(Kafka broker), ADR 0090(tool-call pipeline·재사용 패턴), ADR 0022(event payload privacy), ADR 0088(멀티포드 HA) |

---

## Context

도서관 예약 알림 경로는 이미 durability·순서·at-least-once를 갖춘 outbox 파이프라인이다:

```
library_reservation_outbox (테이블)
  → LibraryReservationEventRelay (@Scheduled, claim/lease)
  → ApplicationEventPublisher → LibraryReservationEventListener (@EventListener)
  → LibraryIntentStatusBus.publish(LibraryIntentStatusMessage{intentId, eventType, timestamp})
  → [각 포드] LibraryIntentSseRegistry.subscribe(...) → SseEmitter(per intentId) → 프론트
```

핵심은 **크로스-포드 fan-out**이다. 클라이언트의 SSE 연결은 접속한 임의의 포드에 sticky하게 붙지만(그 포드의 `LibraryIntentSseRegistry`에 emitter가 산다), 예약 상태를 확정·relay하는 주체는 **리더 포드**(claim/lease)일 수 있다 — 즉 이벤트를 만드는 포드와 emitter를 가진 포드가 다르다. 그래서 `LibraryIntentStatusBus`는 모든 포드에 이벤트를 **브로드캐스트**해야 한다.

현재 구현 `RedissonLibraryIntentStatusBus`는 Redisson RTopic pub/sub이다. 동작하지만 **at-most-once·비영속**: 브로커에 로그가 남지 않아 replay·감사·디버깅이 불가능하다. ADR 0071이 "Redis 임시 fan-out을 영속 로그로 졸업(graduate)시키는 트리거"를 미리 정의해 두었고, ADR 0089(브로커)·0090(재사용 가능한 producer/consumer 패턴)이 이제 그 트리거를 충족한다.

## Decision

같은 `LibraryIntentStatusBus` 인터페이스(publish + subscribe)를 구현하는 **`KafkaLibraryIntentStatusBus`**를 도입한다. `LibraryReservationEventListener`와 `LibraryIntentSseRegistry`는 인터페이스만 의존하므로 **한 줄도 바뀌지 않는다** — 버스는 설정 계층(`LibraryRedisConfiguration.libraryIntentStatusBus`)에서 교체된다.

- **publish**: `LibraryIntentStatusMessage`를 JSON 직렬화 → Kafka 토픽 `library.reservation.events.v1`에 **key=intentId**로 발행.
- **subscribe**: **포드마다 고유한 consumer group**으로 구독(브로드캐스트 fan-out).
- **게이트**: 툴콜 파이프라인(`ssuai.kafka.enabled`)과 **독립된** 별도 플래그 `ssuai.kafka.intent-bus.enabled`(prod=true, cutover 완료). 켜지면 Kafka 버스 빈이 존재하고 **`@Primary`가 붙은** `libraryIntentStatusBus`가 그 인스턴스를 반환해 Redisson보다 **우선** 선택된다. 꺼지면 Redisson RTopic(→ noop) 경로로 코드 변경 없이 되돌아간다.
- **`@Primary` 필수(사고로 확인)**: 플래그가 켜지면 `libraryIntentStatusBus`(정식 빈)와 `kafkaLibraryIntentStatusBus`(그 delegate) **두 개**가 `LibraryIntentStatusBus` 타입으로 존재한다. 단일 인자 소비자(`LibraryIntentSseRegistry`, `LibraryReservationEventListener`)가 모호해지므로 정식 빈에 `@Primary`를 붙여 해소한다. (1차 cutover가 이 모호성으로 crash-loop 했고 `@Primary`로 근본수정 — 하단 Cutover 결과 참조.)

## Why Kafka, not Redis pub/sub

RTopic은 "지금 붙어 있는 SSE 클라이언트에게 한 번 전달"만 필요하던 초기엔 옳았다. 하지만 실서비스 규모에선 예약 알림 이벤트를 **영속 로그**로 남겨 (1) 특정 offset부터 replay, (2) 예약-알림 경로 장애의 사후 감사, (3) 컨슈머 교체/재시작 시 무손실 계약이 필요하다. 이것이 ADR 0071이 남겨둔 graduation 트리거이며 ADR 0090과 동일한 논리다.

## Broadcast fan-out: 왜 포드마다 고유 consumer group인가 (핵심)

Kafka consumer group은 **competing-consumer**다 — 한 group 안에서는 파티션이 멤버들에게 나눠지고, 각 레코드는 group 내 **한 컨슈머**만 받는다. 그러나 우리는 모든 포드가 모든 이벤트를 받아야 한다(어느 포드에 emitter가 있는지 모름). 따라서 **각 포드가 유일한 `group.id`(`library-intent-sse-<UUID>`)를 쓴다** → Kafka는 각 레코드를 group마다 복제 → 브로드캐스트가 된다. 이는 "Kafka로 SSE fan-out"의 문서화된 표준 패턴이다(웹서치 근거: unique group per instance + offset=latest).

- **`auto.offset.reset=latest`**: 새로 뜬 포드는 "지금부터"의 이벤트만 받는다. 이미 종료된 예약의 옛 이벤트는 새 포드에 무의미하다.
- **ephemeral group**: latest + 의미 없는 offset이라 group 메타데이터가 쌓이지 않는다 — Kafka가 `offsets.retention.minutes` 후 빈 group을 회수한다. (표준 패턴의 유일한 trade-off인 "group 메타데이터 누적"을 이렇게 무력화.)

## Ordering

key=intentId → **한 예약의 모든 이벤트가 같은 파티션**에 들어가 순서 보장. producer 측은 **단일 스레드 executor(FIFO) + idempotent producer**라 `WAIT_REGISTERED → SEAT_FOUND → terminal` 순서가 relay가 만든 순서 그대로 유지된다. (툴콜 파이프라인의 core/max 1/2와 달리 여기선 순서가 계약이라 1/1.)

## Fail-open / non-blocking

`@Scheduled` relay 스레드는 절대 Kafka를 기다리면 안 된다. publish는 bounded 단일 스레드 executor(`queue-capacity=500`, `AbortPolicy` load-shed)에 offload된다 — 큐가 차면 이벤트를 버리고 카운터만 올린다. 기존 `LibraryReservationEventListener`가 이미 publish 예외를 삼키므로, **브로커가 죽어도 예약 자체는 outbox+폴링으로 계속 진행되고 알림만 지연/드롭**된다. 메트릭: `library.intent.bus.event{result=sent|dropped_queue_full|dropped_error}`. 이는 ADR 0090의 fail-open 규율을 라이브 예약 경로에 그대로 적용한 것이다.

## At-least-once 멱등성

RTopic(at-most-once)과 달리 Kafka는 at-least-once라 중복 전달이 가능하다(리밸런스·재전달). 다운스트림이 이미 멱등적이다:

- **terminal 이벤트**(`RESERVATION_SUCCEEDED/FAILED`, `CANCELLED`, `EXPIRED`): `LibraryIntentSseRegistry`가 intentId 키를 통째로 제거하고 emitter를 complete한다 → **중복 terminal은 `get()==null`로 no-op**.
- **non-terminal 중복**: 같은 상태를 SSE로 한 번 더 보낼 뿐 — 상태(증분 아님)라 프론트에서 멱등.

포드당 group에 컨슈머가 하나뿐이라 리밸런스 파트너가 없어 중복은 사실상 crash-before-commit 정도로 희박하다.

## Reversibility / staged rollout

- 별도 dormant 플래그 → **코드 머지 = 런타임 영향 0**(빈이 생성되지 않음). 항상-켜진 변경은 `libraryIntentStatusBus` 팩토리에 ObjectProvider 파라미터 추가(플래그 off면 기존과 동일 동작) + 차트 dormant 키뿐.
- **라이브 cutover = prod 플래그 한 개(`SSUAI_KAFKA_INTENTBUS_ENABLED=true`)** — prod env 변경이라 사용자 승인 게이트.
- **롤백 = 플래그 off** → Redisson RTopic 경로 복원, 코드 재배포 불필요.
- 종속: intent-bus 활성화는 `ssuai.kafka.enabled=true`(공유 producer/broker)를 요구 — 차트가 두 값을 함께 관리, 아니면 startup fail-fast.

## Trade-offs

| 얻은 것 | 잃은 것 / 비용 | 완화 |
|---|---|---|
| 영속 로그 + offset replay + 감사 | 라이브 알림 경로에 브로커 의존 추가 | fail-open + Redisson 폴백(플래그) |
| 표준 브로드캐스트 fan-out 서사 | 포드당 group 메타데이터 churn | latest offset + 빈 group 회수 |
| 순서·멱등 계약 명시 | Redis 대비 hop 지연 소폭↑ | 인간용 알림이라 무시 가능 |

## 예상 면접 질문

1. **"Kafka consumer group은 competing-consumer인데 어떻게 모든 포드가 같은 이벤트를 받나?"** → 포드마다 유일한 `group.id` + `auto.offset.reset=latest`. Kafka가 group마다 레코드를 복제해 브로드캐스트가 된다. 표준 "Kafka→SSE fan-out" 패턴.
2. **"그럼 group이 무한히 쌓이지 않나?"** → latest offset이라 커밋할 의미 있는 offset이 없고, Kafka가 `offsets.retention.minutes` 후 빈 group을 회수한다.
3. **"왜 RTopic을 두고 Kafka로?"** → 영속성 + offset replay + 무손실 컨슈머 교체(ADR 0071 graduation 트리거). RTopic은 at-most-once·비영속.
4. **"라이브 예약 경로에 브로커를 끼우면 위험하지 않나?"** → fail-open: publish는 non-blocking offload라 relay를 절대 안 막고, 브로커가 죽어도 예약은 outbox로 진행·알림만 지연. 플래그로 즉시 Redisson 롤백.
5. **"at-least-once 중복은?"** → terminal은 키 제거로 멱등 no-op, non-terminal은 상태 재전송이라 멱등.

## Cutover 결과 (2026-07-10, 라이브 검증)

- **코드 머지**: PR #192 `cc05c28`(dormant, 런타임 영향 0). **라이브 cutover**: prod `SSUAI_KAFKA_INTENTBUS_ENABLED=true` 플립(PR #195 `825745e`).
- **프로드 실측**: 파드 2/2 Ready(flag ON), 토픽 `library.reservation.events.v1` 생성, **포드당 유일 consumer group 2개(`library-intent-sse-<UUID>`) = 브로드캐스트 fan-out 실증**, 기동 로그 `KafkaLibraryIntentStatusBus subscribed`, argocd Synced/Healthy, health 200.
- **fail-open 드릴**: `kubectl delete pod kafka-0 --force` → 백엔드 health 32초 outage 내내 200 유지 → kafka-0 ~47초 자동복구, 토픽은 local-path PVC로 유지. 라이브 예약 경로가 브로커 장애에 안전함을 실증.
- **1차 cutover 사고 → @Primary 근본수정**: 최초 flip이 **bean 모호성**으로 crash-loop(`LibraryIntentSseRegistry`가 `LibraryIntentStatusBus` 단일 빈을 기대하는데 `libraryIntentStatusBus`·`kafkaLibraryIntentStatusBus` 2개 발견). 무중단(`maxUnavailable:0`이 구 파드 유지). 회귀 IT가 `@SpringBootTest(classes=...)` 슬라이스라 두 빈 생산자를 한 컨텍스트에 안 올려 못 잡았음. 수정 = 정식 빈에 `@Primary`(PR #194 `0bf4fd8`) + `IntentBusCutoverContextIT`(전체 앱 컨텍스트 + 플래그 ON + EmbeddedKafka; @Primary 없이 FAIL함을 실증). 상세 = `TROUBLESHOOTING.md` 2026-07-10.
- **롤백**: `kafkaIntentBusEnabled: false`(코드 재배포 불필요, RTopic 복원). **남은 검증** = 실제 예약 → intent-status SSE 종단(실계정+현장).
