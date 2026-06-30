# ADR 0071 — 이벤트 파이프라인: Outbox 근거와 "왜 Kafka를 안 쓰나"

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-30 |
| 상태 | Accepted — 현행 유지 결정(코드 변경 없음, 근거 자산화) |
| 범위 | 좌석/예약 이벤트 전파(Redisson `RTopic`, PG `LISTEN/NOTIFY`), 예약 intent SSE fan-out |
| 연관 문서 | ADR 0047(좌석 분산 락), ADR 0048(intent SSE·Redis fan-out), ADR 0055(confirm 상태머신), `IMPROVEMENT_SPEC_JOB_DRIVEN_2026-06-30.md` §SET D |

> 스펙 초안은 0069로 적었으나 번호 충돌로 0071로 재배정(0069=관측성, 0070=pgvector).

---

## 배경

채용공고 수요분석에서 "Kafka·이벤트 기반"이 5.4%로 등장한다. 현재 mp의 이벤트 전파는 **Kafka 없이** 두 메커니즘으로 동작한다.

- **Redisson `RTopic`** — 좌석 상태 변경·예약 intent 상태를 멀티 포드에 fan-out(ADR 0048). 어떤 포드가 intent를 처리해도 전 구독자(SSE)에게 전달.
- **PostgreSQL `LISTEN/NOTIFY`** — 예약 intent wake 신호 등 경량 알림.

"포트폴리오에 Kafka를 넣어야 하나?"가 이 ADR의 질문이다. 무작정 추가가 아니라 **규모에 맞는 선택인지**를 웹서치로 검증하고 결정을 기록한다(철칙2).

## 결정

**Kafka를 추가하지 않는다.** 현행 Redisson topic + LISTEN/NOTIFY를 유지하고, 대신 두 가지를 문서로 자산화한다.

1. **"Kafka로 graduate하는 트리거"를 명문화** — 아래 조건 중 하나라도 충족되면 Kafka(또는 Redpanda) 도입을 재검토한다: ① 지속 처리량이 수천 msg/s를 넘거나 ② 소비자 그룹이 5+로 늘고 독립 재처리/리플레이가 필요하거나 ③ 이벤트 보존·재처리(event sourcing/감사 리플레이)가 요구사항이 되거나 ④ 서비스가 멀티 팀·멀티 레포로 분화해 브로커가 계약 경계가 될 때.
2. **신뢰 전달이 필요해지면 Transactional Outbox 패턴**을 우선 도입한다(브로커 교체보다 먼저). 단, **지금은 테이블/마이그레이션을 만들지 않는다** — prod 기본 마이그레이션 경로에 새 migration을 0개로 유지(불필요한 prod 스키마 변경 회피).

## 대안과 기각 이유

### Kafka 직접 추가 ❌

- **규모 불일치**: 현재 이벤트는 분당 수백 건 수준(좌석/예약 상태 변경). Kafka는 고처리량·다소비자·보존/리플레이가 가치인 도구다. 이 규모에선 LISTEN/NOTIFY·Redis pub/sub가 충분하다(jusDB "Postgres LISTEN/NOTIFY without Kafka", Confluent "when NOT to use Kafka").
- **운영 부담**: 단일 Oracle ARM 노드에 ZooKeeper-less라도 브로커 + 토픽 운영·모니터링·리밸런싱 비용이 든다. Confluent 자체 가이드도 소규모에서 Kafka 운영을 "0.5~2 FTE"로 본다. 단일노드 포트폴리오에 부적합.
- **포트폴리오 역효과**: "왜 이 규모에 Kafka를?"라는 과설계 신호가 될 수 있다. 오히려 **규모 판단을 보여주는 게** 더 강한 신호다.

### LISTEN/NOTIFY 현행 유지 ✅ (채택)

- `<1000 msg/s`, `<5 소비자`, 단일 트랜잭션 경계 내 알림에 적합. Redisson topic이 멀티포드 fan-out을, LISTEN/NOTIFY가 DB 트랜잭션 커밋과 결합된 wake를 담당한다. 이미 ADR 0047/0048로 동작·검증됨.

### Transactional Outbox 도입 ✅ (조건부, 지금은 ADR만)

- dual-write 문제(DB 커밋과 메시지 발행의 원자성 부재)를 풀어야 할 때의 **올바른 1순위 해법**이다(Conduktor/Morling outbox). 같은 트랜잭션에 `outbox_event` 행을 insert하고 폴러가 미발행 행을 relay → at-least-once. 현재는 Redisson topic으로 relay하다가, 후일 Kafka가 필요해지면 relay 타깃만 교체하면 되므로 **브로커 선택을 미래로 미룰 수 있다**.
- **지금 구현하지 않는 이유**: 현재 예약 흐름은 confirm 상태머신(ADR 0055)+감사 단일 진실원천(ADR 0059)으로 일관성을 이미 확보했고, 교차서비스 신뢰 전달 요구가 아직 없다. 테이블을 미리 만들면 prod 마이그레이션·폴러·정리잡만 늘어난다(YAGNI).

## 동작 방식 (현행)

1. 좌석/예약 상태 변경 → 처리 포드가 Redisson `RTopic`에 발행 → 전 포드의 SSE 레지스트리 구독자에게 fan-out(ADR 0048).
2. 예약 intent wake → PG `LISTEN/NOTIFY`로 워커 깨움 → 상태머신(ADR 0055) 진행.
3. (미래) 신뢰 전달 필요 시 → 같은 트랜잭션에 outbox insert → 폴러가 미발행 행을 relay(처음엔 Redisson, 트리거 충족 시 Kafka).

## 검증

- 코드 변경 없음(현행 메커니즘은 ADR 0047/0048에서 이미 검증·배포됨). 이 ADR은 결정·근거 기록이 산출물이다.
- prod 기본 마이그레이션 경로 신규 migration 0개 유지(Outbox 테이블 미생성).

## 예상 면접 질문

1. 이벤트 기반인데 왜 Kafka를 안 썼나? (규모: 분당 수백 건·<5 소비자 → LISTEN/NOTIFY·Redis로 충분, 단일노드 Kafka 운영비용이 가치보다 큼. "graduate 트리거"를 ADR로 명문화)
2. Transactional Outbox가 푸는 문제는? (DB 커밋과 메시지 발행의 dual-write 비원자성 → 같은 트랜잭션 insert + 폴러 relay로 at-least-once)
3. 언제 Kafka로 가나? (처리량 수천 msg/s·다소비자 리플레이·이벤트 보존·멀티팀 계약 경계 중 하나라도 충족 시. 그전엔 Outbox로 relay 타깃만 교체)
