# load-tests — 도서관 좌석 에이전트 k6 baseline (EPIC 1)

실제 학교 시스템(oasis/Pyxis)에는 **단 한 요청도 보내지 않는다.** Pyxis는 WireMock으로
대체하고, 백엔드의 real 커넥터 + Resilience4j(`PyxisResilience`) + RestClient 경로를
그대로 부하 경로에 포함시킨다. 배경·결과 해석은
[`../docs/performance/library-agent-load-test.md`](../docs/performance/library-agent-load-test.md) 참조.

## 구성

```
k6 ──RPS──▶ ssuMCP backend ──RestClient+CircuitBreaker──▶ WireMock(Pyxis 스텁)
│                │
│ remote write   │ /actuator/prometheus 스크레이프
▼                ▼
Prometheus ◀──── Grafana(:3001, admin/admin)
```

| 서비스 | 포트 | 용도 |
|---|---|---|
| postgres | 55432 | Flyway 마이그레이션 + action_audit 검증 |
| wiremock | 8089 | Pyxis 스텁 (지연 lognormal 주입, 경합 좌석 시나리오) |
| prometheus | 9090 | 백엔드 메트릭 스크레이프 + k6 remote write 수신 |
| grafana | 3001 | 대시보드 (k6 공식 대시보드 ID `19665` import) |

## 실행 순서

### 1. 인프라 기동

```powershell
cd ssuMCP/load-tests
docker compose up -d
```

### 2. 백엔드 기동 (host JVM — 기본 워크플로우)

```powershell
$env:SSUAI_DB_URL = 'jdbc:postgresql://localhost:55432/ssuai'
$env:SSUAI_DB_USERNAME = 'ssuai'
$env:SSUAI_DB_PASSWORD = 'loadtest'
$env:SSUAI_CONNECTOR_LIBRARY_SEAT = 'real'
$env:SSUAI_CONNECTOR_LIBRARY_RESERVATION = 'real'
$env:SSUAI_LIBRARY_SEAT_BASE_URL = 'http://localhost:8089'
$env:SSUAI_LIBRARY_RESERVATION_BASE_URL = 'http://localhost:8089'
$env:SSUAI_LIBRARY_LOGIN_BASE_URL = 'http://localhost:8089'
$env:SSUAI_LIBRARY_RESERVATION_INTENT_BATCH_SIZE = '100'
$env:SSUAI_LIBRARY_RESERVATION_INTENT_POLL_INTERVAL = '200ms'
$env:SSUAI_FRONTEND_ORIGIN = 'http://localhost:3000'
# prod 미러 + 필수: real 커넥터가 쓰는 ObjectMapper 빈이 chat=llm일 때만 등록된다
# (LlmProviderConfig.primaryObjectMapper — LLM 키 없어도 기동엔 문제 없음)
$env:SSUAI_CONNECTOR_CHAT = 'llm'
.\gradlew.bat bootRun
```

(컨테이너로 띄우려면 `docker compose --profile full up -d --build` — 첫 빌드는
rusaint Rust 컴파일 때문에 오래 걸린다.)

### 3. read baseline

```powershell
docker compose run --rm k6 run -o experimental-prometheus-rw /scripts/library-seat-read-baseline.js
```

- 프로파일: 1m ramp-up → 3m steady → 1m ramp-down, `READ_RPS`(기본 50) 고정 도착률
- threshold: `p(95)<500ms`, `error rate<1%` — 위반 시 k6가 non-zero exit

### 4. write baseline

```powershell
# 같은 좌석 100명 동시 confirm — 정확히 1명만 SUCCESS, 나머지 FAILURE_RACE
docker compose run --rm k6 run -o experimental-prometheus-rw /scripts/library-reservation-baseline.js

# 서로 다른 좌석 100명 — 전원 SUCCESS
docker compose run --rm -e MODE=distinct-seats k6 run -o experimental-prometheus-rw /scripts/library-reservation-baseline.js
```

setup()이 브라우저 없이 MCP 세션 100개를 만든다: `start_auth`의 loginUrl에서 state를
추출해 `/api/mcp/auth/library/callback`에 직접 POST → WireMock 로그인 스텁이 수락.
**합성 환경에서만 가능한 절차다. prod에 절대 돌리지 말 것.**

### 5. action_audit 검증 (권위 있는 결과)

```powershell
docker exec -it ssumcp-loadtest-postgres-1 psql -U ssuai -d ssuai -c `
  "SELECT action_type, status, outcome_code, count(*) FROM action_audit GROUP BY 1,2,3 ORDER BY 1,2,3;"

docker exec -it ssumcp-loadtest-postgres-1 psql -U ssuai -d ssuai -c `
  "SELECT status, outcome_code, count(*) FROM library_reservation_intents GROUP BY 1,2 ORDER BY 1,2;"
```

기대값 (same-seat 모드, SESSIONS=100): `SUCCESS/SUCCESS` 1건, `FAILED/FAILURE_RACE` 99건,
중간 상태(EXECUTING/PENDING, REQUESTED/RESERVING) 잔류 0건. intent 테이블도
`SUCCEEDED/SUCCESS` 1건, `FAILED_RACE/FAILED_RACE` 99건이어야 한다.

### 6. Grafana

`http://localhost:3001` (admin/admin) → Dashboards → Import → `19665` (k6 Prometheus).
백엔드 쪽은 `resilience4j_circuitbreaker_state`, `library_action_total`,
`http_server_requests_seconds` 쿼리로 패널 구성.

## 결과 해석 시 주의 (안 읽으면 수치를 오독한다)

1. **좌석 read 30s 캐시** — `/api/library/seats`는 층별 30초 캐시가 있어 대부분 캐시
   히트다. 30초마다 층당 1건만 풀 비용(업스트림 왕복)을 낸다. p95가 낮은 건 캐시
   설계가 동작한다는 뜻이고, p99~max 스파이크가 실제 업스트림 경로다.
2. **good-citizen jitter** — `RealLibrarySeatConnector`는 업스트림 호출 전 300~1200ms
   랜덤 지연을 깐다(사람 패턴 모사). 캐시 미스 요청의 지연은 대부분 이 jitter다.
3. **WireMock 지연은 가정치** — lognormal(median 120~250ms)은 캠퍼스망 실측 전 가정.
   실측치가 생기면 mappings의 `delayDistribution`만 바꾸면 된다.
4. **PR2 이후 same-seat 기준** — `confirm_action`의 예약 경로는 intent 큐를 거친다.
   같은 좌석 100건은 worker가 한 batch에서 좌석별로 그룹화하므로 WireMock
   `/pyxis-api/1/api/seat-charges` POST가 정확히 1건이어야 한다. k6 teardown이
   WireMock request journal로 이를 자동 검증한다.
