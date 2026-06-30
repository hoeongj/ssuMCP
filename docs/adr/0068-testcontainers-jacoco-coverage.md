# ADR 0068 — Testcontainers 통합테스트 + JaCoCo 커버리지 래칫

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-30 |
| 상태 | Accepted — 적용 |
| 범위 | `build.gradle`, `.github/workflows/ci.yml`, `src/test/java/com/ssuai/support/AbstractPostgresIT.java`, `AcademicEmbeddingPostgresIT`, `LibrarySeatRedisIT` |
| 연관 문서 | ADR 0020(평문 임베딩 테이블), ADR 0047(좌석 분산 락), 내부 스펙 문서 §SET B |

> 스펙 초안은 이 ADR 번호를 0067로 적었으나, 0067은 LMS 1회용 토큰(ADR 0067)이 먼저 점유해 0068로 재배정했다.

---

## 배경

백엔드 테스트는 ~1000건으로 많지만, 영속 계층은 **H2 인메모리**, 외부 HTTP는 **WireMock/MockWebServer**, Redis 경로는 **mock/no-op**으로만 검증했다. 두 가지 사각지대가 있었다.

1. **방언·타입 드리프트 미검출** — 엔티티/마이그레이션은 H2에서 통과하지만 prod Postgres에서는 갈릴 수 있다. `academic_embeddings`는 `TIMESTAMP(6) WITH TIME ZONE`·`TEXT`·복합 PK를 쓰는데, H2는 이를 관대하게 받아들여 실제 PG 방언 회귀(타입 매핑, Flyway `postgresql` vendor 마이그레이션 적용)를 잡지 못한다.
2. **분산 락 경로 미검증** — 좌석 예약 per-seat 분산 락(ADR 0047)은 Redisson 기반인데, 단위 테스트는 mock으로만 돌아 "실제 Redis에서 상호배제가 성립하는가"를 증명하지 못했다.

또한 **커버리지가 측정되지 않아** 회귀(테스트 없는 코드 유입)를 막을 게이트가 없었다. 채용공고 수요분석에서도 "테스트 코드/커버리지"·"CI/CD"가 빈출 요건으로 나타났다(신입 차별화 포인트).

## 결정

Testcontainers로 **prod 동등 백킹 서비스(Postgres 16, Redis 7)** 위에서 통합테스트를 돌리고, **JaCoCo로 커버리지를 측정·게이트**한다.

- `AbstractPostgresIT` — `@ServiceConnection` + `PostgreSQLContainer<>("postgres:16-alpine")`로 Spring `DataSource`를 실 PG에 연결, Flyway가 `db/migration/postgresql` vendor 마이그레이션을 적용하고 Hibernate `ddl-auto=validate`로 엔티티를 실 PG 스키마에 검증한다.
- `AcademicEmbeddingPostgresIT` — 기존 H2 시나리오를 실 PG에서 재현(복합 PK round-trip, base64 TEXT 벡터, 모델별 동일 hash 공존).
- `LibrarySeatRedisIT` — 실 Redis 컨테이너에 standalone `RedissonClient`를 연결하고 `RedissonLibraryDistributedLockClient`로 **획득→해제→재획득**과 **다른 스레드의 보유 중 차단(상호배제)**을 단언한다.
- 컨테이너 IT는 `@Testcontainers(disabledWithoutDocker = true)`로 **Docker 없는 환경(개발 머신, 오프라인 빌드)에서 자동 스킵**한다 → 기본 `./gradlew test`는 오프라인에서도 그린, CI(ubuntu-latest는 Docker 탑재)에서만 실제 컨테이너로 실행.
- JaCoCo는 `jacocoTestReport`(xml+html) + `jacocoTestCoverageVerification`(LINE `COVEREDRATIO` 최소 게이트)로 구성하고 `check`에 연결한다. 게이트는 **현재 실측치보다 살짝 아래에서 시작해 래칫 상향**한다.

## 대안과 기각 이유

### H2 유지

가장 싸지만 방언 드리프트를 구조적으로 못 잡는다. 이번 도입 동기 자체가 "H2가 통과시키는 PG 회귀"라 H2 단독은 목적과 모순된다. H2는 빠른 단위 테스트용으로 **유지**하되, 실 PG 검증을 IT로 **추가**한다(대체가 아니라 보강).

### 임베디드 Redis(예: embedded-redis, redis-mock)

JVM 내장 Redis는 셋업이 가볍지만 (1) prod와 다른 구현이라 Redisson 락 의미를 완전히 보장하지 못하고 (2) 유지보수가 끊긴 라이브러리가 많다. Testcontainers는 **실제 redis:7 이미지**를 띄워 prod 동등성을 준다.

### 커버리지 고정 수치 게이트(예: 항상 80%)

높은 고정 게이트는 기존 코드에서 즉시 빨간 빌드를 만들거나, 반대로 낮으면 의미가 없다. **래칫(현재 실측보다 살짝 아래에서 시작 → 점진 상향)**은 "회귀만 차단, 기존 코드로 빌드를 깨지 않음"을 만족한다. 측정 LINE 커버리지 0.738을 기준으로 **floor 0.70**에서 시작한다.

### javaagent/별도 커버리지 SaaS(Codecov 등)

JaCoCo Gradle 플러그인은 추가 인프라 없이 리포트(xml/html)와 게이트를 모두 제공한다. SaaS는 배지·PR 코멘트에 유리하나 외부 의존·토큰 관리를 늘려 현 단계에선 과하다(배지는 후속 선택).

## 게이트를 래칫으로 둔 이유 / 제외 클래스

`jacocoTestCoverageVerification`의 `classDirectories`에서 **생성 FFI 바인딩(`dev/eatsteak/rusaint/**`)·`*Config*`·`dto`·`*Application*`**을 제외한다. 생성 코드와 단순 홀더가 분모를 지배하면 커버리지 신호가 왜곡되기 때문이다. 제외는 리포트와 검증 양쪽에 **동일 적용**한다.

JaCoCo의 `classDirectories` 변형은 소스셋이 확정된 뒤여야 하므로 **top-level `afterEvaluate`** 안에서 적용한다. (이걸 lazy task 설정 블록 내부의 `afterEvaluate`로 넣으면 프로젝트 평가 이후 실행돼 `afterEvaluate(...) cannot be executed in the current context` 오류가 난다 — 실제로 1차 구현에서 이 함정을 밟고 top-level로 교정했다.)

## 의존성 버전 메모

Spring Boot 4.0.6 플랫폼은 standalone Testcontainers 아티팩트(`org.testcontainers:junit-jupiter`, `:postgresql`)의 버전을 관리하지 않아, BOM 없이는 빈 버전으로 해소돼 빌드가 깨진다. 따라서 `org.testcontainers:testcontainers-bom:1.20.6`을 `dependencyManagement.imports`에 추가한다. `com.redis:testcontainers-redis:2.2.2`는 BOM 미관리라 명시 핀하며, 이 1.20.x 라인과 호환된다.

## 동작 방식

1. `./gradlew test`(또는 CI의 `test jacocoTestReport jacocoTestCoverageVerification`)가 전체 스위트를 실행한다.
2. Docker가 있으면 `AbstractPostgresIT` 서브클래스가 PG 컨테이너를 띄우고(@ServiceConnection로 DataSource 주입), Flyway가 `postgresql` vendor 마이그레이션을 적용, Hibernate가 엔티티를 검증한 뒤 리포지토리 round-trip을 단언한다. `LibrarySeatRedisIT`는 Redis 컨테이너에 Redisson을 연결해 분산 락 상호배제를 단언한다.
3. Docker가 없으면 두 IT 클래스는 `disabledWithoutDocker`로 통째 스킵된다(컨텍스트 로딩 자체를 시도하지 않음).
4. `jacocoTestReport`가 제외 규칙을 적용해 xml/html 리포트를 생성하고, `jacocoTestCoverageVerification`이 LINE 커버리지 < 0.70이면 빌드를 실패시킨다.
5. CI는 리포트를 `jacoco-coverage-report` 아티팩트로 업로드한다.

## 검증

- 오프라인 로컬 `./gradlew test jacocoTestReport jacocoTestCoverageVerification`: **1030 tests, 0 failures, 4 skipped(컨테이너 IT 4건이 Docker 부재로 스킵)**, LINE 커버리지 **0.738**, 게이트(0.70) 통과.
- CI(ubuntu-latest, Docker 탑재): 컨테이너 IT가 실제 PG/Redis로 실행된다(여기서 방언·락 회귀가 잡힌다).
- 게이트는 회귀(커버리지 하락)에서만 빌드를 깨뜨린다.

## 예상 면접 질문

1. H2 대신 Testcontainers로 실제로 어떤 부류의 버그를 잡을 수 있나? (PG 방언/타입/마이그레이션, Redisson 락 상호배제 — H2/mock이 못 보는 영역)
2. 커버리지 게이트를 고정 수치가 아니라 래칫으로 운영하는 이유와 초기값 산정(0.738 실측 → 0.70 floor)?
3. CI에서 컨테이너 테스트의 비용·시간을 어떻게 통제하나? (오프라인 자동 스킵, 경량 alpine 이미지, BOM 버전 정렬)
4. 컨테이너 IT가 로컬에서는 스킵되고 CI에서만 도는 게 신뢰성에 문제는 없나? (CI가 권위 게이트, 로컬은 빠른 피드백)
