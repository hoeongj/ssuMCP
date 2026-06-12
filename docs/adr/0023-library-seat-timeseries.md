# ADR 0023 - 도서관 좌석 상태 시계열 적재

- **Status**: Accepted - 2026-06-12 구현
- **Date**: 2026-06-12
- **Scope**:
  - `V10__create_library_seat_samples.sql` (`db/migration/postgresql`, `db/migration/h2`)
  - `com.ssuai.domain.library.timeseries`
  - `LibraryRoomSeatCache`
  - `LibrarySeatRoomCatalogService`
  - `docs/architecture.md`

## 배경

도서관 좌석 기능은 이미 live read, 추천, 예약 intent queue를 갖고 있다. 다음 포트폴리오 포인트는 "실제 운영 데이터가 쌓이는 시스템"이다. 2F/5F/6F 정적 좌석 카탈로그 기준 좌석은 753석이고, 5분마다 좌석별 스냅샷을 적재하면 하루 288회, 약 216,864행이 생긴다. 한 달은 약 650만 행, 90일 원본 보존은 약 1,950만 행이다.

이 데이터는 두 가지 목적을 가진다.

- PostgreSQL RANGE partition, `(room_id, sampled_at)` 인덱스, `EXPLAIN ANALYZE`로 파티션 프루닝과 인덱스 튜닝을 증명한다.
- 시간대별 혼잡도, 열람실별 점유 변화, 향후 혼잡도 예측 모델의 학습 데이터를 만든다.

환경 제약은 명확하다. 현재 운영은 단일 k3s 노드와 backend pod 1개다. 새 인프라를 추가하면 완성·운영 검증 비용이 커진다. 테스트는 H2를 계속 사용하므로 PostgreSQL 전용 DDL을 기본 migration 위치에 넣으면 CI가 깨진다.

## 결정

### D1. 원본은 PostgreSQL 월 단위 declarative RANGE partition으로 저장한다

`library_seat_samples`는 `sampled_at` 기준 월 단위 RANGE partition 테이블이다. 기본키는 `(sampled_at, room_id, external_seat_id)`로 잡아 PostgreSQL partitioned table의 unique/primary key 제약 조건인 "partition key 포함"을 만족한다. 조회 튜닝의 첫 기준 인덱스는 `(room_id, sampled_at)`이다.

PostgreSQL 공식 문서는 declarative partition에서 partition pruning이 파티션 bound를 보고 불필요한 partition scan을 제거한다고 설명한다. 이 기능은 "최근 24시간", "특정 열람실의 특정 기간" 같은 시계열 조회에서 바로 증명할 수 있다. 또한 `EXPLAIN`과 `EXPLAIN ANALYZE`로 pruning 여부와 실행 중 pruning 효과를 확인할 수 있다.

Sources:

- https://www.postgresql.org/docs/current/ddl-partitioning.html
- https://www.postgresql.org/docs/current/pgbench.html

### D2. 파티션 생성/drop은 애플리케이션 `@Scheduled` 잡이 관리한다

운영은 단일 backend pod이므로 분산 락은 도입하지 않는다. `LibrarySeatSamplePartitionMaintenance`가 부팅 시 한 번, 이후 매일 UTC 03:23에 실행된다. 현재 월과 다음 월 partition을 `CREATE TABLE IF NOT EXISTS ... PARTITION OF`로 보장하고, 각 partition의 월 상한이 `now - retentionDays`보다 오래된 경우 `DROP TABLE IF EXISTS`로 제거한다.

원본 보존은 90일이다. 월 partition drop은 대량 `DELETE`보다 단순하고, vacuum bloat를 만들지 않는다. 90일 경계가 월 중간이면 해당 월 partition은 상한이 retention cutoff를 지난 뒤 떨어진다. 따라서 실제 보존 기간은 "최소 90일 + 월 partition 경계에 따른 여유"가 된다. 원본 보존을 정확히 일 단위로 맞추기보다 운영 단순성과 파티션 드롭 비용 절감을 우선했다.

### D3. 수집은 기존 `LibraryRoomSeatCache`를 반드시 통과한다

샘플러는 Pyxis connector를 직접 호출하지 않는다. `LibrarySeatRoomCatalogService.rooms()`에서 `roomId`가 있는 2F/5F/6F reservable room만 고른 뒤, 각 room을 `LibraryRoomSeatCache.get(roomId, samplerToken)`로 읽는다. mock/local에서 sampler 자격증명이 없으면 `samplerToken`은 null로 남아 기존 mock 경로와 같은 방식으로 동작한다. 이 캐시는 5초 TTL과 single-flight를 제공하므로 수집 직전에 MCP read tool이나 예약 worker가 같은 room을 읽어도 upstream 호출은 합쳐진다.

저장 status code는 compact code로 매핑한다.

| DTO status | 저장 code | 의미 |
| --- | --- | --- |
| `available` | `A` | 사용 가능 |
| `occupied` | `O` | 사용 중 |
| `away` | `W` | 이석 중, 좌석은 점유 상태 |
| `inactive` | `I` | 비활성/고장 |
| 그 외 | `U` | 미분류 |

`sampled_at`은 한 run에서 하나의 UTC `Instant`를 공유한다. run 중 특정 room read가 실패하면 scheduled method가 예외를 로그로 남기고 삼키므로 다른 scheduler를 깨뜨리지 않는다. 운영 검증 단계에서 room별 부분 성공이 필요하다고 판단되면 room 단위 catch로 확장할 수 있지만, 현재는 "한 run의 스냅샷 일관성"을 우선한다.

### D3-1. 샘플러는 전용 Pyxis service session을 사용한다

Pyxis per-seat endpoint(`GET /pyxis-api/1/api/rooms/{roomId}/seats`)는 익명 호출을 `error.authentication.needLogin`으로 거부한다. 기존 테스트가 green이었던 이유는 mock 경로가 null token을 허용했기 때문이다. 따라서 운영 수집에는 sampler 전용 Pyxis session이 필요하다.

채택안은 `LibrarySamplerSessionManager`가 `internal:seat-sampler`라는 내부 key로 `LibrarySessionStore`에 Pyxis token을 저장하는 방식이다. 자격증명은 k8s Secret `ssuai-library-sampler`의 `SSUAI_LIBRARY_SAMPLER_LOGIN_ID`, `SSUAI_LIBRARY_SAMPLER_PASSWORD`에서만 읽는다. backend는 Secret의 일반 비밀번호를 oasis 웹 클라이언트와 같은 PBKDF2(SHA-1, 5000회) + AES-CBC 형식으로 암호화한 뒤 기존 `LibraryCredentialLoginService`의 `/pyxis-api/api/login` 호출을 재사용한다.

사용자 세션 piggyback은 기각했다. 사용자가 도서관 로그인을 하지 않은 시간대에는 수집이 멈추고, session owner가 특정 사용자로 보이는 운영/보안 혼선이 생긴다. 좌석 시계열은 개인 데이터가 아니라 운영 관측 데이터이므로, 명시적으로 분리된 service session이 더 예측 가능하다.

로그인 폭주를 막기 위해 한 sample run에서는 login attempt를 최대 1회로 제한한다. 저장된 token이 거부되면 token을 무효화하고 한 번만 재로그인해 run 전체를 다시 시도한다. 로그인 실패, 재로그인 불가, 재로그인 token 재거부는 WARN 후 해당 run을 skip한다. scheduler 자체는 깨지지 않는다.

Kubernetes Secret은 container env var로 주입할 수 있지만, Secret 변경이 이미 실행 중인 container env에 자동 반영되지는 않는다. 따라서 prod에서 sampler Secret 값을 바꾸면 pod replacement/rollout으로 새 env를 읽혀야 한다.

Source:

- https://kubernetes.io/docs/tasks/inject-data-application/distribute-credentials-secure/

### D4. 시간 단위 room rollup은 영구 보관한다

원본 raw sample은 90일 뒤 사라지지만 `library_room_occupancy_hourly`는 영구 보관한다. 매시 7분 UTC에 직전 완료 시간을 집계한다.

집계는 두 단계다.

1. `(room_id, sampled_at)` 단위로 `available`, `occupied` count를 계산한다. `occupied`는 `O`와 `W`를 포함한다. `inactive`는 가용도 아니고 사용 중도 아니므로 제외한다.
2. 같은 room/hour 안의 sample들을 평균/최댓값으로 줄인다.

저장 필드는 `sample_count`, `avg_available_seats`, `avg_occupied_seats`, `max_occupied_seats`다. PostgreSQL은 `ON CONFLICT (room_id, bucket_start) DO UPDATE`로 idempotent upsert를 수행하고, H2는 같은 bucket을 delete 후 insert한다.

### D5. Flyway는 vendor-specific location으로 분리한다

`spring.flyway.locations`는 `classpath:db/migration/V*__*.sql,classpath:db/migration/{vendor}`다. 기존 V1-V9는 그대로 두어 checksum을 건드리지 않는다. V10만 PostgreSQL과 H2 위치에 각각 둔다.

초기 검증에서는 `classpath:db/migration,classpath:db/migration/{vendor}`를 사용했지만, Flyway classpath location이 재귀 scan이라 parent location이 `h2`와 `postgresql` 하위 V10을 모두 발견했다. 따라서 공통 location은 root의 versioned SQL 파일만 고르는 wildcard로 좁혔다. 이 조정은 V1-V9 파일을 이동하지 않으면서 vendor V10 충돌을 피하기 위한 framework 동작 대응이다.

PostgreSQL V10은 partitioned table과 초기 현재/다음 월 partition을 만든다. H2 V10은 동일 column set의 plain table을 만든다. rollup table은 양쪽 모두 plain table이다.

Spring Boot 문서는 Flyway location의 `{vendor}` placeholder가 database type별 folder를 고르는 방식이라고 설명한다. Redgate Flyway 문서는 location scan이 recursive라고 명시한다. Reflectoring의 migration 테스트 글은 H2 compatibility mode가 production PostgreSQL 전용 DDL을 완전히 검증하지 못한다는 한계를 설명한다. 이 프로젝트는 H2 CI를 유지하되, PostgreSQL 전용 DDL은 vendor folder로 격리한다.

Sources:

- https://docs.spring.io/spring-boot/docs/2.0.0.M5/reference/html/howto-database-initialization.html
- https://documentation.red-gate.com/fd/flyway-locations-setting-277579008.html
- https://reflectoring.io/spring-boot-flyway-testcontainers/

## 대안과 기각 사유

### TimescaleDB hypertable

기각했다. Hypertable은 time-series chunking, compression, continuous aggregate를 편하게 제공한다. Tiger Data 자료도 hypertable이 일반 PostgreSQL table처럼 보이면서 내부적으로 time-based partitioning을 자동화한다고 설명한다. 그러나 현재 운영은 단일 k3s 노드의 기존 PostgreSQL이고, TimescaleDB는 DB image/extension 운영 변경이 필요하다. 이 작업의 목표는 "PostgreSQL partitioning/index/EXPLAIN 튜닝을 직접 증명"하는 것이므로 자동화 extension 뒤에 핵심 학습 포인트가 가려진다.

Source:

- https://www.tigerdata.com/docs/learn/hypertables/understand-hypertables

### pg_partman

기각했다. pg_partman은 time-based partition 생성/관리 자동화에 적합하고 월 단위 partition 운영을 쉽게 만든다. Crunchy Data 자료도 time partitioning interval과 자동 관리 기능을 설명한다. 하지만 extension 설치와 운영 정책이 필요하며, 현재 규모에서는 애플리케이션 잡의 `CREATE TABLE IF NOT EXISTS`와 `DROP TABLE IF EXISTS`로 충분하다. 나중에 multi-pod나 더 긴 retention이 필요해질 때 재검토한다.

Sources:

- https://www.crunchydata.com/blog/time-partitioning-and-custom-time-intervals-in-postgres-with-pg_partman
- https://www.postgresql.org/about/news/pg_partman-510-480-released-2834/

### 단일 plain table

기각했다. 구현은 가장 쉽지만 90일 retention에서 오래된 데이터를 지우려면 대량 `DELETE`가 필요하다. 이는 dead tuple과 vacuum 부담을 만들고, "기간 조건이 partition pruning으로 물리 scan 범위를 줄인다"는 포트폴리오 증명도 사라진다.

### 좌석별 변화 이벤트만 저장

기각했다. 저장량은 줄지만 "2026-06-12 10:15의 room별 점유율"을 구하려면 이전 상태를 재생해야 한다. 예측 학습 데이터는 고정 간격 time grid가 단순하고, 결측/휴관/업스트림 오류를 다루기도 쉽다. 따라서 per-seat snapshot을 선택했다.

### pg_cron 또는 외부 scheduler

기각했다. 파티션 유지보수는 하루 한 번 실행되는 단순 SQL이다. 새 extension이나 외부 cron pod를 넣으면 현재 단일 pod 운영보다 검증 면적이 커진다. Spring `@Scheduled`는 fixed delay/cron metadata를 붙인 no-arg method를 주기적으로 실행할 수 있고, 이미 프로젝트가 scheduling을 사용한다.

Source:

- https://docs.spring.io/spring-framework/reference/integration/scheduling.html

## 동작 방식

### 적재 흐름

```text
@Scheduled fixedDelay 5m
  -> LibrarySamplerSessionManager.currentToken()
  -> token 없음 또는 token 거부 시 service login(run당 최대 1회)
  -> LibrarySeatRoomCatalogService.rooms()
  -> roomId가 있는 2F/5F/6F reservable room만 선택
  -> LibraryRoomSeatCache.get(roomId, samplerToken)
  -> PyxisSeatInfo status 문자열을 A/O/W/I/U로 매핑
  -> JdbcTemplate batchUpdate(library_seat_samples)
```

한 run은 하나의 `sampled_at`을 공유한다. `created_at`은 DB 입력 시각 추적용이고 partition key가 아니다.

### 파티션 유지보수

```text
@PostConstruct + @Scheduled daily UTC
  -> datasource URL이 jdbc:postgresql: 로 시작하지 않으면 return
  -> CREATE TABLE IF NOT EXISTS library_seat_samples_YYYYMM PARTITION OF ...
     - 현재 월
     - 다음 월
  -> pg_inherits에서 child partition 이름 조회
  -> library_seat_samples_YYYYMM 이름을 YearMonth로 파싱
  -> 월 상한 <= now - retentionDays 이면 DROP TABLE IF EXISTS
```

H2에서는 no-op이다. 이 guard는 예약 intent의 PostgreSQL NOTIFY notifier와 같은 운영 원칙이다. "PostgreSQL 전용 기능은 datasource vendor를 확인하고, H2에서는 조용히 비활성화한다."

### 시간 rollup

```text
@Scheduled hourly UTC
  -> 직전 완료 hour 계산
  -> raw sample을 room_id, sampled_at으로 1차 집계
  -> room_id, bucket_start로 2차 집계
  -> PostgreSQL: ON CONFLICT DO UPDATE
  -> H2: DELETE same bucket 후 INSERT
```

원본 partition을 drop해도 시간 rollup은 남는다. 대시보드/예측은 장기 추세에는 rollup을 쓰고, 최근 90일 세부 분석에는 raw sample을 쓴다.
