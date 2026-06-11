# 도서관 좌석 에이전트 k6 baseline (EPIC 1)

> 측정일: 2026-06-11 · 환경: 로컬 docker-compose + host JVM · 스택: k6 1.0 / WireMock 3.13 /
> Prometheus 3.4 / Grafana 12 / Postgres 17 · 실행 방법: [`load-tests/README.md`](../../load-tests/README.md)

## 1. 배경 — 왜 baseline을 먼저 만들었나

EPIC 2(Resilience4j)·EPIC 3(예약 intent 큐)·EPIC 4(좌석 락) 같은 개선 작업은 "개선 전후
숫자 비교"가 없으면 포트폴리오에서 증명력이 없다. 그래서 기능 추가 전에 현재 상태의
p50/p95/p99·에러율·동시성 정합성을 먼저 수치로 박제한다. **측정 전에 어떤 성능 수치도
문서에 적지 않는다**는 원칙(2026-06-10 결정)의 실행이다.

## 2. 실험 설계 — 검토한 대안과 기각 이유

| 결정 | 채택 | 기각한 대안과 이유 |
|---|---|---|
| 테스트 환경 | 로컬 docker-compose | ① 실제 oasis/Pyxis에 부하 — 금지(기존 결정, 학교 시스템 보호). ② prod k3s에 mock 배포 — 단일 Oracle ARM64 노드라 운영 서비스와 리소스 경합, 결과 오염 + 서비스 위험 |
| Pyxis 대역 | WireMock standalone | in-process mock 커넥터 — 커넥터 인터페이스에서 끊겨 RestClient 커넥션풀·타임아웃·`PyxisResilience`(서킷·재시도)가 부하 경로에서 빠짐. WireMock은 [lognormal 지연·fault 주입 공식 지원](https://wiremock.org/docs/simulating-faults/) |
| 부하 모델 | `ramping-arrival-rate` (RPS 고정) | VU 기반 — 지연이 늘면 처리량이 같이 떨어져 "고정 부하에서의 지연"을 못 잰다. [arrival-rate가 처리량 측정 권장 방식](https://oneuptime.com/blog/post/2026-02-02-k6-load-testing/view) |
| 결과 관측 | k6 → [Prometheus remote write](https://grafana.com/docs/k6/latest/results-output/real-time/prometheus-remote-write/) → Grafana | summary 텍스트만 — 시계열 그래프와 백엔드 내부 메트릭(서킷 상태, action 카운터) 상관 분석 불가 |
| SLO 코드화 | [k6 thresholds](https://oneuptime.com/blog/post/2026-01-27-k6-thresholds-slos/view) (p95<500ms, err<1%) | 수동 판독 — threshold 위반 시 non-zero exit라 이후 CI 회귀 게이트로 재사용 가능 |
| read 대상 | MCP `get_library_seat_status` | REST `/api/library/seats` — JWT 보호 경로(401)이고, 실제 클라이언트(Claude Desktop·향후 ssuAgent)는 MCP로 호출하므로 MCP 경로가 진짜 사용자 경로다 |

**부하 경로**: `k6 → /mcp (JSON-RPC tools/call) → MCP tool → Service → RealConnector
→ PyxisResilience(CircuitBreaker) → RestClient → WireMock`. 프로토콜 핸드셰이크·인증
세션·서킷브레이커까지 전부 포함된 end-to-end다.

**브라우저 없는 인증 부트스트랩**: `start_auth`가 주는 loginUrl의 `state`를
`/api/mcp/auth/library/callback`에 직접 POST하고, WireMock 로그인 스텁이 아무 크리덴셜이나
수락한다. 합성 환경에서만 가능한 절차(실제 Pyxis는 진짜 크리덴셜 요구).

**주입 지연(가정치)**: lognormal — 로그인 150ms / seat-rooms·room-seats 120ms /
current-charge 100ms / reserve 250ms / discharge 200ms (σ 0.35~0.4). 캠퍼스망 실측치가
생기면 WireMock mappings의 `delayDistribution`만 교체하면 된다.

## 3. 측정 결과

### 3-1. read baseline — `get_library_seat_status`, 50 RPS, 5분 (1m ramp / 3m steady / 1m down)

| 지표 | 값 |
|---|---|
| 반복 / HTTP 요청 | 11,999 / 12,123 (에러 0%) |
| p50 / p90 / p95 | **12.2ms / 15.8ms / 19.7ms** |
| avg / max | 28.6ms / **1.31s** |
| threshold | p95<500ms ✅ · err<1% ✅ · checks 100% ✅ |

**해석**: p95 19.7ms는 30초 층별 캐시 덕분 — 대부분 인메모리 응답이고, 이 12ms대는 사실상
MCP JSON-RPC + Tomcat + 직렬화 오버헤드다. max 1.31s가 진짜 업스트림 경로(캐시 미스)다:
`RealLibrarySeatConnector`의 good-citizen jitter 300~1200ms + WireMock 120ms + 처리.
**50 RPS에서 30초당 층당 1번만 풀 비용을 내는 캐시 설계가 동작함을 수치로 확인.**
바꿔 말하면 같은 5분간 WireMock(=Pyxis)이 받은 콜은 수십 건 수준 — 업스트림 보호 L1
전략의 효과가 이미 read 경로에 있다. (남은 것: 캐시 만료 순간 동시 미스를 1콜로 합치는
single-flight — 백로그)

### 3-2. write baseline — prepare→confirm 100명 동시 burst

| 모드 | 결과 (k6 카운터) | action_audit (권위) | confirm 지연 |
|---|---|---|---|
| 같은 좌석(9999) ×100 | SUCCESS 2 · RACE 98 | `SUCCESS` 2 · `FAILED/FAILURE_RACE` 98 · **잔류 0** | p50 887ms / p95 1.50s / max 1.79s |
| 다른 좌석 ×100 | SUCCESS 100 | `SUCCESS` 100 (student 100명 전원 1건씩) · 잔류 0 | p50 959ms / p95 1.42s / max 1.65s |

백엔드 메트릭: `library_action_total{status="prepared"}`=200, `{status="executing"}`=200
(두 런 합계), **서킷브레이커 "pyxis"는 내내 CLOSED**, HTTP 에러 0%.

**해석 ① — P1-1 상태머신 증명**: 200건 전부 PENDING→EXECUTING→터미널로 수렴, EXECUTING
잔류 0. row-lock claim이 100동시성에서 안정적으로 동작한다.

**해석 ② — baseline의 핵심 발견 (같은 좌석 SUCCESS가 1이 아니라 2인 이유)**:
- 직접 원인은 WireMock 시나리오 상태 전이가 원자적 CAS가 아니라는 mock 한계다(동시 2건이
  "Started" 상태를 같이 봄). 실제 Pyxis는 좌석당 1건만 원자적으로 수락한다.
- 그러나 이것이 드러내는 아키텍처 사실이 더 중요하다: **현재 백엔드는 같은 좌석을 노리는
  서로 다른 사용자들의 confirm을 직렬화하지 않는다.** row-lock은 "한 action row의 중복
  실행"만 막을 뿐, 좌석 단위 경쟁은 전부 업스트림에 떠넘긴다. 즉 오늘의 정합성 보장
  지점은 우리 시스템 밖(Pyxis)에 있다.
- 이것이 EPIC 3(intent 큐로 write 직렬화)·EPIC 4(좌석 단위 락)의 정량적 근거다. 개선 후
  같은 시나리오에서 "업스트림으로 나가는 reserve 콜 수"가 100→1로 줄어드는 것을 비교
  측정한다 (WireMock `__admin/requests` 카운트로 검증 가능).

**해석 ③ — confirm p50 ~0.9s의 구성**: 주입 지연(reserve lognormal 중앙값 250ms +
current-charge 100ms)과 100동시 burst 시 WireMock 큐잉이 대부분이고, 백엔드 자체 병목
신호(스레드 고갈·에러·서킷 오픈)는 없었다. 실측 Pyxis 지연으로 교체하면 이 숫자가
"사용자 체감 예약 시간"의 근사치가 된다.

### 3-3. PR2 재측정 — `confirm_action` 예약 경로를 intent 큐로 통합한 뒤

측정일: 2026-06-11. 실행 조건은 같은 로컬 docker-compose + host JVM이며, PR2 측정에서는
`SSUAI_LIBRARY_RESERVATION_INTENT_BATCH_SIZE=100`,
`SSUAI_LIBRARY_RESERVATION_INTENT_POLL_INTERVAL=200ms`를 사용했다. k6 teardown은
WireMock request journal을 직접 세서 `POST /pyxis-api/1/api/seat-charges` with
`seatId=9999`가 정확히 1건인지 검증한다.

| 항목 | EPIC 1 baseline (직접 confirm) | EPIC 3 PR2 (intent 큐 confirm) |
|---|---:|---:|
| k6 사용자 결과 | SUCCESS 2 · RACE 98 | **SUCCESS 1 · RACE 99 · OTHER 0** |
| 권위 DB 결과 | action_audit `SUCCESS` 2 · `FAILURE_RACE` 98 | action_audit `SUCCESS` 1 · `FAILURE_RACE` 99 |
| intent 실행 결과 | 없음 | `SUCCEEDED/SUCCESS` 1 · `FAILED_RACE/FAILED_RACE` 99 |
| WireMock reserve POST | 100건(각 confirm이 직접 Pyxis 호출) | **1건** |
| confirm 지연 | p50 887ms / p95 1.50s / max 1.79s | p50 2.69s / p95 3.08s / max 3.26s |
| threshold | 상태 수렴 검증 | checks 100% · `reserve_success==1` · `reserve_race==99` · WireMock count==1 |

**해석**: PR2의 핵심 개선은 사용자 latency가 아니라 업스트림 보호다. confirm은 이제 worker
tick과 DB 상태 전이를 기다리므로 p50/p95가 baseline보다 늘었다. 대신 같은 좌석 100명 burst가
Pyxis write 100건에서 1건으로 줄었다. 이 수치가 "단일 egress IP 서버가 학교 시스템에 좋은
시민으로 동작한다"는 포트폴리오 근거다.

**중간 실패에서 얻은 보정**: 첫 PR2 k6 run은 사용자 결과가 이미 1/99였지만 WireMock reserve
POST가 2건이었다. 원인은 worker tick이 burst를 여러 번 claim하면서 다음 tick의 winner가 같은
좌석을 다시 Pyxis에 물은 것. PR2는 same-tick grouping에 더해, action TTL 안의 최근 immediate
same-seat terminal attempt가 있으면 후속 group을 로컬 `FAILED_RACE`로 닫는다. 세부 원인과
면접용 해석은 [`TROUBLESHOOTING.md`](../../TROUBLESHOOTING.md)의 2026-06-11 PR2 k6 항목에
기록했다.

## 4. 다음 비교 측정 (개선 후 같은 시나리오 재실행)

1. **single-flight 캐시**: 캐시 만료 직후 동시 read 미스 → 업스트림 콜 1건 합쳐지는지
2. **장애 주입 확장**: WireMock 500 연속·timeout 스텁으로 서킷 OPEN 전이 + 복구를 부하
   중에 관측 (EPIC 2 남은 항목과 연동)
3. read RPS 상향(50→200+)으로 무릎(knee point) 탐색
4. EPIC 4 Redis/Redisson 도입 후 same-seat suppress를 in-memory/DB 최근 결과가 아니라
   seat-level distributed lock으로 검증

## 5. 운영 메모

- Grafana(`localhost:3001`)에서 k6 공식 대시보드 `19665` import — k6 메트릭 438개 시계열이
  remote write로 적재됨을 확인.
- `library_action_total`은 Micrometer 내부 이름 `library.action`으로 등록된다. 준비/실행/만료는
  `status` 태그만 사용하고, 터미널 상태는 `status=success|failed`와 `outcome=SUCCESS|FAILURE_RACE|...`
  태그를 함께 기록한다. Grafana는 DB 조회 없이 action 결과 분포를 이 태그로 그리면 된다.
- 발견된 숨은 커플링: real 커넥터들이 쓰는 `ObjectMapper` 빈이 chat=llm일 때만 등록됨 —
  `TROUBLESHOOTING.md` 2026-06-11 항목 참조.
