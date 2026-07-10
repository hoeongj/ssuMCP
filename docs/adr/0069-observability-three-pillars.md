# ADR 0069 — 관측성 3-pillars: OTel 분산추적(Tempo) + 구조화 로그(Loki)

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-30 (prod 활성화·검증 2026-07-01) |
| 상태 | Accepted — **prod 라이브**: 3-pillars(메트릭·트레이스·로그) 전부 배포·검증 완료(2026-07-01). 활성화 중 Boot 4 OTLP gotcha 2건 등 해소 → §"활성화 함정 & 검증(prod)". |
| 범위 | `build.gradle`(→ `spring-boot-starter-opentelemetry`), `application.yml`, `logback-spring.xml`, `TraceIdFilter`, `ApiResponse`, `LlmProviderChain`, `deploy/charts/{tempo,loki,promtail}` + `deploy/argocd/application-{tempo,loki,promtail}.yaml`, `load-tests/`(로컬 데모) |
| 연관 문서 | ADR 0061(rate-limit·메트릭), ADR 0047/0048(좌석 락·intent SSE), 내부 스펙 문서 §SET A |

> 스펙 초안은 0066으로 적었으나 0066은 read-only rootfs가 점유 → 0069로 재배정.

---

## 배경

기존 관측성은 **메트릭(Prometheus/Grafana) + per-request `TraceIdFilter`(X-Trace-Id)**뿐이었다. 멀티 홉 요청(HTTP → MCP 툴 → 도메인 서비스 → 10개 LLM provider 페일오버, ssuAgent)에서 **요청 흐름·지연·장애 원인을 한 타임라인으로 추적**할 수 없었고, 로그는 포드별 stdout에 흩어져 중앙 검색이 불가능했다. 채용공고 수요분석에서도 모니터링 17.9%·대용량 24%·"장애대응/트러블슈팅"이 신입 차별화 1순위로 나왔다.

## 결정

**3-pillars를 자가호스팅으로 구성하되, 기존 Grafana·Prometheus를 재사용**한다.

- **추적**: Micrometer **Observation 브리지 → OTel → OTLP exporter → Tempo**. 메트릭은 기존 Prometheus 유지(OTel 메트릭 중복 안 씀, 추적만).
- **로그**: `logback`이 stdout에 **JSON(LogstashEncoder)** → Promtail/Alloy 스크레이프 → Loki. `traceId`/`spanId`(Micrometer MDC)·`requestId`(TraceIdFilter) 필드 포함 → Grafana에서 **trace ↔ logs 상호이동**.
- **LLM provider 스팬**: `LlmProviderChain.complete()`의 provider 시도마다 `llm.provider.call` 스팬(`provider`/`privacy_mode` 속성). **10-provider 자동 페일오버가 추적 타임라인에 그대로**(provider1 실패→provider2 성공) 보인다 — 핵심 면접 포인트.
- **안전 기본값(off) → prod에서 env로 ON**: 코드 기본은 샘플링 `TRACING_SAMPLE_RATE` **0.0**(스팬 미샘플) + JSON 로그 `json-logs` 프로파일 게이트라 **계측만 심고 비활성**으로 배포 가능. prod에서는 chart env(`SPRING_PROFILES_ACTIVE=prod,json-logs`, `TRACING_SAMPLE_RATE=0.1`, `OTLP_TRACING_ENDPOINT`)로 켜서 **현재 라이브**(§활성화). 로컬/load-tests는 샘플링 1.0 + `json-logs`로 전체 파이프라인 증명.

## 대안과 기각 이유

- **Spring Cloud Sleuth** ❌ — EOL(Micrometer Tracing으로 대체됨).
- **Zipkin/Jaeger 단독** △ — 추적은 되나 메트릭·로그가 분리돼 3-pillars 상호이동이 약함. Tempo는 기존 Grafana에 붙어 Prometheus·Loki와 한 화면.
- **Datadog/New Relic(SaaS)** ❌ — 비용. 단일 노드 자가호스팅이 포트폴리오·비용 양면에서 적합.
- **계측 방식**: javaagent(제로코드) △ vs **Micrometer Observation 브리지** ✓ — 코드 레벨 제어로 **LLM 커스텀 스팬** 같은 도메인 의미를 넣을 수 있음.
- **로그 전송**: loki4j 직접 appender △ vs **stdout-JSON + Promtail** ✓ — 앱과 Loki를 디커플(앱은 stdout만, 수집은 인프라가).

## MDC 키 충돌 해소 (TraceIdFilter)

기존 `TraceIdFilter`가 MDC 키 `"traceId"`를 점유했는데, Micrometer Tracing도 `"traceId"`/`"spanId"`를 MDC에 넣어 **충돌**한다(로그의 traceId가 OTel traceId가 아니면 trace↔log 상관이 깨짐). 해소: 필터의 MDC 키를 **`"requestId"`로 변경**해 Micrometer가 `traceId`/`spanId`를 소유하게 했다. **클라이언트 영향 없음** — `ApiResponse`의 JSON 필드명은 그대로 `traceId`이고 값도 동일한 per-request UUID(소스 MDC 키만 바뀜), `X-Trace-Id` 응답 헤더도 불변. 회귀 테스트(TraceIdFilterTests + 컨트롤러 envelope 테스트 1030건)로 무변경 확인.

## 동작 방식

1. 요청 진입 → `TraceIdFilter`가 `requestId`(UUID/X-Trace-Id) MDC 설정 → Micrometer 서버 observation이 `traceId`/`spanId` MDC 설정.
2. RestClient/HTTP/LLM 호출이 W3C trace context 전파 → OTLP로 Tempo 전송(샘플링 비율 적용).
3. 로그는 JSON으로 stdout → Promtail이 Loki push(k8s 라벨만 부여; `traceId`는 라벨로 승격하지 않고 Grafana 쿼리 시점에 본문 regex로 추출 — §③, 고카디널리티 회피).
4. Grafana: Tempo 스팬 → `tracesToLogsV2`로 같은 traceId Loki 로그, Loki 로그 → `derivedFields`로 Tempo 스팬.

## 활성화 완료 (prod 라이브, 2026-07-01)

- **Tempo/Loki/Promtail prod(k3s/ArgoCD) 배포 완료** — 아래 §"SET A"의 매니페스트를 배포. ArgoCD 앱 3개 전부 Synced/Healthy(namespace `monitoring`). 단일 Oracle ARM 노드 예산 내(≈+1.5GB).
- **백엔드 emit ON**: chart env로 `SPRING_PROFILES_ACTIVE=prod,json-logs` + `TRACING_SAMPLE_RATE=0.1` + `OTLP_TRACING_ENDPOINT`(클러스터 tempo svc FQDN) 주입, 무중단 롤(`maxUnavailable:0`).
- **검증**: Loki에 ssuai-prod 실로그 수집 확인, Tempo에 `service.name=ssuai` trace 저장 확인(§"활성화 함정 & 검증").
- **후속(선택)**: model-level 스팬(현재 provider-level) · 알림 룰(트레이스 기반 에러율) — 있으면 좋은 디테일, 필수 아님.

## 검증

- 로컬 `./gradlew test`: **1030 tests, 0 failures, 4 skipped**(컨테이너 IT). MDC 리네임·스팬 추가로 깨진 테스트 0.
- prod 배포 안전성: 샘플링 0 + json-logs 게이트로 **동작 무변**(계측 코드만 존재). 배포 후 health UP + 무재시작으로 확인.
- 로컬 3-pillars: `docker compose -f load-tests/docker-compose.yml --profile full up`로 Tempo/Loki/Promtail/Grafana 기동 후 trace↔log 상관 확인.

## 예상 면접 질문

1. 분산추적을 왜 도입했고, javaagent 대신 Micrometer Observation 브리지를 쓴 이유는? (코드 레벨 제어 → LLM provider 커스텀 스팬 같은 도메인 의미 부여)
2. 10-provider LLM 페일오버를 추적에서 어떻게 보나? (`LlmProviderChain`의 provider별 `llm.provider.call` 스팬이 타임라인에 폴백 시퀀스로 표시)
3. 단일 노드에서 관측성 스택 비용을 어떻게 통제했나? (single-binary Tempo/Loki·retention 72h·**코드 기본값 off**(샘플링 0/json-logs 게이트)로 켜기 전까지 0 오버헤드, prod에서 켠 뒤엔 샘플링 10%로 제한 — 24GB 노드에 ~+1.5GB)

## SET A 활성화 (prod ON) — 배포 완료

위 활성화 절차 ①②③을 실제 배포 매니페스트로 구현·**prod 배포 완료**. 관련 커밋: `dd417a2`(Tempo/Loki 배포 + 백엔드 emit ON), `a097174`·`a15ae21`(로그 파이프라인 함정 수정), `8a446b5`·`0f73728`(Boot 4 OTLP 트레이스 수정).

**① Tempo/Loki/수집기 배포** — 기존 monitoring 패턴(업스트림 차트 + 리포 values 파일 + ArgoCD Application) 그대로 미러:
- `deploy/argocd/application-tempo.yaml` + `deploy/charts/tempo/values.yaml` — `grafana/tempo` 1.24.4 **single-binary(monolithic)**, local(filesystem) 백엔드, block_retention 72h. 차트 기본 `memBallastSizeMbs: 1024`는 512Mi limit을 단독으로 초과해 기동 OOM → **0으로 비활성**(핵심 함정).
- `deploy/argocd/application-loki.yaml` + `deploy/charts/loki/values.yaml` — `grafana/loki` 6.55.0 **SingleBinary**, filesystem, retention 72h(compactor retention loop on). 차트 기본 heavy 컴포넌트(gateway/chunksCache/resultsCache/lokiCanary/test/minio/self-monitoring) 전부 off — 단일 24GB 노드 예산.
- `deploy/argocd/application-promtail.yaml` + `deploy/charts/promtail/values.yaml` — `grafana/promtail` 6.17.1 DaemonSet.

**수집기 = Promtail (Alloy 아님) 근거**: ADR 0069 범위·load-tests 파이프라인이 Promtail을 명시 → 이미 로컬에서 증명된 stdout-JSON + Promtail 구성을 prod에 그대로 이식(재현성). Alloy가 Grafana의 장기 후속(Promtail 유지보수 모드)이라는 점은 리뷰 코멘트로 남김.

**② 백엔드 emit ON** (`deploy/charts/ssuai-backend/values.yaml` + `templates/configmap.yaml`):
- `SPRING_PROFILES_ACTIVE=prod,json-logs` — logback `json-logs` 프로파일 게이트 개방(LogstashEncoder stdout).
- `TRACING_SAMPLE_RATE=0.1` (application.yml `management.tracing.sampling.probability` 기본 0.0 → 0.1).
- `OTLP_TRACING_ENDPOINT=http://tempo.monitoring.svc.cluster.local:4318/v1/traces` (in-cluster Tempo svc).
- ConfigMap 변경 → deployment의 `checksum/config` 애노테이션이 파드 롤을 유발(envFrom는 기동 시 1회 로드).

**③ Grafana 데이터소스** (`deploy/charts/monitoring/values.yaml` `grafana.additionalDataSources`): Tempo(uid tempo, `:3200`) + Loki(uid loki, `:3100`), in-cluster svc URL.
- Tempo→logs: `tracesToLogsV2.filterByTraceID` (±1h).
- logs→Tempo: Loki `derivedFields`가 **로그 본문 JSON에서 traceId를 regex 추출**해 링크. load-tests는 traceId를 Loki *라벨*로 승격했으나(데모 규모라 무해), prod에서 UUID를 라벨로 올리면 **고카디널리티로 Loki 인덱스가 폭발** → 라벨 대신 본문 regex 매칭으로 전환(카디널리티 0 비용). 이것이 두 구성의 유일한 의미 차이.

**대안 기각(수집기 라벨링)**: promtail 전역 JSON 파이프라인으로 traceId 라벨 승격 △ — 비-JSON 파드(grafana/prometheus/tempo 등) 로그에 파싱 에러 + 고카디널리티. 채택: 수집기는 k8s 라벨만(cri 파이프라인 기본), traceId 상관은 Grafana 쿼리 시점 regex로. 앱↔Loki 디커플 원칙과도 일관.

**배포 시 확인(완료)**: (a) 노드 메모리 여유 확인 — Tempo+Loki+Promtail 24GB 노드에 ~1.5GB 추가(예산 내). (b) 단일 파드 앱에서 분산추적 ROI는 낮으나 포트폴리오·트러블슈팅 서사로 채택. (c) 차트 버전 핀 배포 시점 재확인.

---

## 활성화 함정 & 검증 (prod, 2026-07-01)

로컬 compose에서 증명된 파이프라인을 prod k3s에 켜는 과정에서 **로컬에선 안 드러난 함정 5건**을 만나 해소했다. (전부 "인프라/프레임워크가 기대와 다르게 동작" — 트러블슈팅 서사의 핵심.)

**트레이스 (Boot 4 OTLP — 2단계):**
1. **프로퍼티 키 rename** — Boot 3 `management.otlp.tracing.endpoint`가 Boot 4에서 조용히 무시됨(바인딩 오류 없음). 정식 키 `management.opentelemetry.tracing.export.otlp.endpoint`. 고쳐도(`8a446b5`) span 0 유지.
2. **autoconfig 모듈 이관(진짜 원인)** — Boot 4가 OTLP tracing auto-configuration을 새 **`spring-boot-starter-opentelemetry`**(내부 `spring-boot-opentelemetry` + `spring-boot-micrometer-tracing-opentelemetry`)로 옮김. 저수준 의존성(`micrometer-tracing-bridge-otel`+`opentelemetry-exporter-otlp`)만으론 autoconfig glue 부재로 **exporter가 생성조차 안 됨**(span 0 + 로그 흔적 0). `gradle dependencies`로 모듈 부재를 입증 → 스타터로 교체(`0f73728`)해 해결.
3. **부작용 차단** — 스타터가 `micrometer-registry-otlp`도 데려와 방치 시 OTLP 메트릭을 localhost:4318로 자동 push(오류 소음 + Prometheus 중복) → `management.otlp.metrics.export.enabled=false`.

**로그 (Loki/Promtail):**
4. **Loki `reject_old_samples`** — 기본 on(+168h window)이라 첫 배포 시 백로그 로그를 400으로 거부해 Loki가 비어 보임 → `reject_old_samples:false`(`a097174`).
5. **Promtail k3s 심볼릭링크** — k3s는 컨테이너 로그를 `/var/lib/rancher/k3s/...`에 저장하고 `/var/log/pods`는 그리로의 심링크. 이 트리를 마운트 안 하면 promtail이 링크를 자기 파일시스템 밖 경로로 해석해 아무것도 못 읽음 → hostPath 마운트 추가(`a15ae21`).

**검증 함정 (distroless):** loki/tempo/promtail 컨테이너는 distroless라 `sh`/`wget`이 없어 `kubectl exec ... wget` probe가 false-negative(빈 결과). **노드에서 `curl`→ClusterIP**로 검증해야 실제 상태가 보임.

**검증 결과:** Loki에 ssuai-prod 실로그 수집(수천 라인). Tempo `tempo_distributor_push_duration_seconds_count>0`, TraceQL `service.name=ssuai`로 실제 trace 확인(root span `http get /api/meals/today` 등). **3-pillars 전부 라이브.**

**추가 면접 질문:**
4. "로컬 compose에선 됐는데 prod k3s에서 안 될 때?" → 런타임 환경 차이(distroless probe, k3s 로그 경로, Boot 프로퍼티/의존성)를 하나씩 배제. 특히 로그·설정으로 안 잡히면 `gradle dependencies`로 autoconfig 모듈 존재를 확인.
5. "저수준 라이브러리 대신 Boot 스타터를 써야 하는 이유?" → 스타터 = 라이브러리 + **autoconfiguration** + 기본값 묶음. Boot 4에서 OTLP autoconfig가 스타터로 이관돼 저수준 deps만으론 자동설정이 안 붙음.

## Kafka/EDA Grafana 대시보드

Phase 2 Kafka EDA 관측성 마무리로 `ssuAI — Kafka / Event-Driven Architecture` 대시보드를 추가한다. Kafka로 전환된 툴콜 이벤트 파이프라인과 library intent-status 버스가 실제로 방출·소비되고 있는지, 드롭·lag·리밸런스 같은 조기 경보가 생기는지 한 화면에서 확인하는 목적이다.

패널은 다음 질문에 답한다.
- **MCP Tool-Call Events**: 툴콜 이벤트가 `result`별로 초당 얼마나 방출되며, 드롭 계열 결과가 발생하는가?
- **Intent-Status Bus Events**: 예약 intent 상태 버스가 `sent`/`dropped_*` 결과로 얼마나 발행되는가?
- **Kafka Consumer Lag**: dotted topic(`*.v1`) 기준 consumer lag가 누적되어 소비 지연이 생기는가?
- **Tool-Call Drop Fraction**: 최근 5분 툴콜 이벤트 중 드롭 비율이 1%/5% 임계치를 넘는가?
- **HPA Replicas**: `ssuai-backend` HPA의 current/desired/min/max replica가 Kafka 부하에 맞게 움직이는가?
- **Kafka Consumer Failed Rebalances**: failed rebalance 누적으로 리밸런스 storm 조짐이 있는가?

메트릭 출처는 Micrometer counter `mcp.toolcall.event`/`library.intent.bus.event`의 `result` 태그, Spring Kafka/Micrometer가 노출하는 `kafka_consumer_*` 계열, 그리고 kube-state-metrics의 HPA 메트릭이다.

구현 결정: 대시보드 PromQL은 `application` 공통 태그가 아니라 `job="ssuai-backend"`/`namespace="ssuai-prod"`로 필터한다.
백엔드는 `spring.application.name`만 설정하며 Micrometer `application` 공통 태그를 방출하지 않는다. 적대적 검증에서 prod Prometheus 기준 Kafka 5/6 패널과 RED HTTP 패널이 빈 것을 실측·확인했다.

프로비저닝은 `deploy/charts/ssuai-backend/templates/grafana-dashboard-kafka.yaml` ConfigMap으로 처리한다. kube-prometheus-stack Grafana sidecar가 `monitoring` 네임스페이스에서 `grafana_dashboard=1` 라벨이 붙은 ConfigMap을 자동 로드한다.
