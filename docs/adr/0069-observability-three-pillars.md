# ADR 0069 — 관측성 3-pillars: OTel 분산추적(Tempo) + 구조화 로그(Loki)

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-30 |
| 상태 | Accepted — 앱 계측 적용(prod inert), Tempo/Loki prod 배포는 보류 |
| 범위 | `build.gradle`, `application.yml`, `logback-spring.xml`, `TraceIdFilter`, `ApiResponse`, `LlmProviderChain`, `load-tests/`(tempo/loki/promtail/grafana) |
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
- **prod 안전(inert) 기본값**: 샘플링 `TRACING_SAMPLE_RATE` 기본 **0.0**(스팬 미샘플 → exporter 미접속), JSON 로그는 **`json-logs` 프로파일 게이트**(prod 기본은 기존 콘솔 로그 그대로). 즉 **prod 배포는 동작 무변(no-op)** — 계측만 심고 비활성. 로컬/load-tests는 샘플링 1.0 + `json-logs`로 전체 파이프라인 증명.

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
3. 로그는 JSON으로 stdout → Promtail이 `traceId` 라벨로 Loki push.
4. Grafana: Tempo 스팬 → `tracesToLogsV2`로 같은 traceId Loki 로그, Loki 로그 → `derivedFields`로 Tempo 스팬.

## 보류·후속 (prod 적용은 사용자 확인)

- **Tempo/Loki prod(k3s/ArgoCD) 배포는 보류** — 단일 Oracle ARM 노드 메모리 예산(가드레일) + prod 인프라 변경은 사용자 확인(철칙3). 앱 계측은 inert로 이미 배포 가능.
- **활성화 절차**(아침 결정): ① Tempo/Loki ArgoCD 앱 추가(경량 single-binary) ② ssuMCP env `SPRING_PROFILES_ACTIVE`에 `json-logs` + `TRACING_SAMPLE_RATE=0.1` + `OTLP_TRACING_ENDPOINT`(클러스터 tempo svc) ③ Grafana에 Tempo/Loki 데이터소스.
- **model-level 스팬**: 현재 provider-level. provider 내부 model 폴백까지 자식 스팬으로 넣는 것은 후속(있으면 좋은 디테일).

## 검증

- 로컬 `./gradlew test`: **1030 tests, 0 failures, 4 skipped**(컨테이너 IT). MDC 리네임·스팬 추가로 깨진 테스트 0.
- prod 배포 안전성: 샘플링 0 + json-logs 게이트로 **동작 무변**(계측 코드만 존재). 배포 후 health UP + 무재시작으로 확인.
- 로컬 3-pillars: `docker compose -f load-tests/docker-compose.yml --profile full up`로 Tempo/Loki/Promtail/Grafana 기동 후 trace↔log 상관 확인.

## 예상 면접 질문

1. 분산추적을 왜 도입했고, javaagent 대신 Micrometer Observation 브리지를 쓴 이유는? (코드 레벨 제어 → LLM provider 커스텀 스팬 같은 도메인 의미 부여)
2. 10-provider LLM 페일오버를 추적에서 어떻게 보나? (`LlmProviderChain`의 provider별 `llm.provider.call` 스팬이 타임라인에 폴백 시퀀스로 표시)
3. 단일 노드에서 관측성 스택 비용을 어떻게 통제했나? (샘플링 10%·single-binary Tempo/Loki·prod 기본 inert(샘플링 0/json-logs 게이트)로 켜기 전까지 0 오버헤드)

## SET A 활성화 (prod ON) — branch `feat/observability-tempo-loki`

위 "보류·후속"의 활성화 절차 ①②③을 실제 배포 매니페스트로 구현한 브랜치(미머지, 리뷰 대기).

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

**리뷰 주의(머지 전)**: (a) `kubectl top nodes`로 노드 메모리 여유 먼저 확인 — Tempo+Loki+Promtail이 24GB 노드에 ~1.5GB 추가. (b) 단일 파드 앱에서 분산추적 ROI는 낮음(ADR 0069) → SET A는 선택적. (c) 차트 버전 핀은 배포 시점 재확인.
