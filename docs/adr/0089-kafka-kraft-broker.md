# ADR 0089 — Kafka KRaft 단일 브로커 도입

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-07-10 |
| 상태 | Accepted (2026-07-10) |
| 범위 | Phase 2 event-driven architecture의 영속 이벤트 백본, GitOps Kafka 배포 |
| 연관 문서 | ADR 0008(GitOps ArgoCD Helm), ADR 0071(event pipeline outbox-not-kafka), ADR 0078(capacity plan 2 OCPU/12GB) |

---

## 배경

Phase 2는 단일 reservation fan-out을 넘어 여러 도메인이 같은 이벤트 백본을 공유하는 event-driven architecture로 확장한다. 목표 시나리오는 **"숭실대 공식 캠퍼스 MCP"로서 전교생 수만 명 규모(피크 = 수강신청·시험기간 좌석예약 몰림)** 이다. 이 규모에서는 "수십 명 데모" 수준의 Redis pub/sub만으로 포트폴리오 방어가 어렵고, 영속 로그·독립 replay·감사/분석을 제공하는 실브로커가 필요하다.

ADR 0071은 당시 Kafka를 넣지 않는 결정을 하면서 graduate trigger를 명문화했다: 지속 처리량 증가, 5+ consumer group, 독립 replay, 이벤트 보존/감사, 멀티팀·멀티레포 계약 경계. Phase 2A/B/C는 좌석·도구 호출·감사/분석 등 여러 유스케이스가 같은 플랫폼 이벤트를 재사용하는 방향으로 이동하므로, **영속 이벤트 백본 + 독립 replay + 감사/분석 + 멀티 유스케이스 플랫폼** 조건이 충족됐다.

ADR 0078의 2 OCPU/12GB 단일노드 용량표에는 이미 Kafka(KRaft) request 768Mi / limit 1.25Gi 라인이 예약돼 있었다. OpenSearch 확장은 기존 Loki sink로 대체하는 트림 순서 #3을 적용해 예산을 확보한다.

## 결정

Apache Kafka 4.3.0을 KRaft combined mode로 배포한다. broker와 controller를 단일 프로세스에 합치고 Zookeeper를 제거한다.

- 단일 브로커 StatefulSet, replica 1.
- 직접 작성한 local Helm chart(`deploy/charts/kafka`)와 ArgoCD Application(`deploy/argocd/application-kafka.yaml`)으로 GitOps 관리.
- Headless Service(`clusterIP: None`)로 stable per-pod DNS 제공.
- local-path RWO PVC 5Gi를 `/var/lib/kafka/data`에 마운트.
- 토픽 auto-create는 비활성화한다. 애플리케이션 토픽은 Unit 1에서 Spring `KafkaAdmin`으로 선언한다.
- 컨테이너 이미지는 `apache/kafka:4.3.0` public upstream image를 pin한다. CI가 빌드하지 않으므로 image updater 대상에 넣지 않는다.

## 대안과 기각 이유

### Redpanda ❌

2026-04 단일노드 벤치에서 acks=all 조건으로 Kafka KRaft가 메모리를 약 3배 적게 사용했다. Redpanda community single-broker는 동기 fsync가 강제되어 단일 Oracle A1 노드에서 비용이 커진다. 또한 이 프로젝트의 포트폴리오 키워드는 "Kafka" 자체가 더 직접적이다.

### RabbitMQ ❌

RabbitMQ는 queue 중심 워크로드에 강하지만, 캠퍼스 규모 throughput을 설명하는 핵심인 partition 기반 수평확장과 consumer group replay 모델이 Kafka보다 약하다. Phase 2의 이벤트 백본 요구에는 Kafka 로그 모델이 더 맞다.

### Bitnami Kafka Helm chart ❌

Broadcom은 2025-08 무료 Bitnami image 제공 정책을 종료했고, 무료 사용자는 `:latest` 중심 또는 2026-08 legacy 이관 흐름에 묶인다. 장기적으로 pinned image supply chain을 설명하기 어렵다. 이 repo는 small local chart로 필요한 StatefulSet/Service만 직접 소유한다.

### Strimzi operator ❌

운영 기능은 좋지만 operator pod 자체가 상시 약 256-512Mi를 쓴다. ADR 0078의 실제 headroom은 약 2.3G이고 Kafka 라인은 이미 768Mi/1.25Gi로 예약되어 있으므로, operator 상시 비용은 예산 여유를 초과한다.

### Redis pub/sub ❌

Redis는 이미 fan-out에 사용 중이고 저지연 알림에는 충분하다. 하지만 영속 이벤트 로그, offset 기반 독립 replay, 장기 audit/analysis 요구가 없다. Phase 2에서 "왜 Redis 아니고 Kafka인가"라는 질문에 대한 방어논지 자체가 Kafka 도입 이유다.

## Sizing

Kafka heap은 `-Xmx640m -Xms640m`로 고정한다. 이는 1280Mi limit의 약 50%이고, 나머지는 OS page cache와 native memory에 남긴다. `apache/kafka:4.3.0` 로컬 docker smoke에서 idle RSS는 약 295MiB로 측정됐다.

리소스는 ADR 0078 예약 라인과 동일하다.

| 항목 | request | limit |
|---|---:|---:|
| cpu | 500m | 1000m |
| memory | 768Mi | 1280Mi |
| PVC | 5Gi local-path RWO | - |

OpenSearch 라인은 당장 도입하지 않고 기존 Loki sink로 대체한다(ADR 0078 트림 순서 #3). 따라서 Kafka를 먼저 넣어도 2 OCPU/12GB 단일노드 예산 안에서 설명 가능하다.

## 동작 방식

KRaft combined mode에서는 단일 Kafka process가 broker와 controller 역할을 동시에 수행한다. `KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka-0.kafka.ssuai-prod.svc.cluster.local:9093` 로 자기 자신을 controller quorum voter로 선언하고, headless Service가 `kafka-0.kafka.ssuai-prod.svc.cluster.local` DNS를 제공한다.

Service에는 `publishNotReadyAddresses: true`를 둔다. controller가 Ready 전 boot 과정에서 자기 FQDN으로 9093을 self-dial해야 하는데, NotReady pod DNS를 publish하지 않으면 quorum formation이 끝나지 않고 readiness도 통과할 수 없어 deadlock이 난다.

`CLUSTER_ID`는 `lhsmkW4aQTOdrb4iGBLPMg`로 고정한다. local-path PVC가 재마운트될 때 저장된 `meta.properties`의 cluster id와 새 환경변수가 어긋나면 Kafka가 부팅하지 않으므로 deterministic id가 필요하다.

토픽 auto-create는 꺼둔다. Unit 1에서 Spring KafkaAdmin이 `mcp.toolcall.events.v1` 같은 토픽을 선언해 partition count, RF, retention을 코드/설정으로 명시한다.

## 트레이드오프

**얻는 것**: 영속 partition 이벤트 백본, consumer별 독립 replay, producer/consumer 디커플링, 감사/분석 파이프라인, N-broker로 가는 수평확장 경로.

**잃는 것**: 브로커 운영복잡도, 약 0.77-1.25Gi 메모리 예산, StatefulSet/PVC 운영 부담. 단일노드 RWO라 replication factor는 1이고 데이터 복제는 없다.

## 단일노드 한계와 미래 경로

현재 배포는 RF=1 단일 브로커다. broker pod나 node가 죽으면 Kafka 가용성도 함께 사라진다. local-path RWO PVC는 같은 단일노드에서는 단순하지만, 멀티노드 확장 시에는 node-local storage가 병목이 된다.

N-broker로 확장하려면 다음이 필요하다.

1. broker별 advertised listener를 per-pod DNS로 구성한다. headless Service는 이미 `kafka-0.kafka...`, `kafka-1.kafka...` 같은 안정 DNS 경로를 제공한다.
2. 토픽 RF를 3 이상으로 올리고 ISR 정책을 재설계한다.
3. RWO local-path가 아닌 멀티노드에 적합한 storage class 또는 broker별 node/pv 운영 계획을 세운다.
4. ADR 0078 예산표를 다시 계산한다. 단일 무료노드 안에서는 N-broker Kafka가 목표가 아니다.

## 실측 검증 노트

`apache/kafka:4.3.0` docker run으로 동일 KRaft 설정을 검증했다.

- single-node KRaft combined mode 부팅 약 40초.
- `mcp.toolcall.events.v1` 토픽 6 partitions / RF=1 생성 성공.
- topic create/list smoke 성공.
- `-Xmx640m -Xms640m`에서 idle RSS 약 295MiB.
- controller self-dial 과정 때문에 headless Service의 `publishNotReadyAddresses: true`가 필요함을 확인.

## 예상 면접 질문

1. **ADR 0071에서는 Kafka를 안 쓴다고 했는데 왜 바뀌었나?** 당시에는 분당 수백 건, 소비자 소수, replay 요구 없음이어서 Redis/PG가 맞았다. Phase 2는 공식 캠퍼스 MCP 규모와 멀티 유스케이스 플랫폼을 전제로 하므로 ADR 0071의 graduate trigger가 충족됐다.
2. **왜 단일 브로커인가?** 현재 인프라는 ADR 0078의 2 OCPU/12GB 단일노드 예산이다. 포트폴리오에서는 Kafka 운영 모델과 replay/backbone을 보여주는 것이 목표이고, HA Kafka cluster는 이 예산의 목표가 아니다.
3. **RF=1이면 Kafka를 쓰는 의미가 줄지 않나?** 고가용성은 없지만 영속 로그, partition, offset replay, consumer group contract는 얻는다. HA는 N-broker 전환 시 RF>=3으로 확장할 별도 단계다.
4. **왜 Redis pub/sub를 계속 쓰지 않나?** Redis fan-out은 즉시 알림에 좋지만 offset replay와 감사용 보존 로그가 없다. Phase 2의 "event backbone" 요구는 Kafka가 더 직접적으로 충족한다.
