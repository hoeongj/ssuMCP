# ADR 0072 — DB retention: 앱 레벨 @Scheduled 정리 잡 (terminal 행만, 테이블별 독립 트랜잭션)

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-07-02 |
| 상태 | Accepted — 구현·머지 |
| 범위 | `action_audit`, `library_reservation_outbox`, `library_reservation_intents`의 오래된 terminal 행 정리 |
| 연관 문서 | security-followups #3(보류 이력), ADR 0055(CHECK 기각·confirm 상태머신), ADR 0059(감사 단일 진실원천), ADR 0071(outbox 근거), `docs/security-followups.md` |

---

## 배경

보안 후속 #3(DB 무결성·보존 정책)의 2026-06-30 분석에서 **DB 레벨 제약(CHECK/FK)은 전부 기각**됐다 — CHECK는 ADR 0055에서 "코드 enum과 마이그레이션에 박은 값집합이 어긋나면 정상 쓰기가 차단되는 잠복 회귀"로 이미 기각됐고, FK는 바로 이 retention 잡과 delete-coupling(CASCADE 또는 삭제 순서 의존)을 만든다. 그 분석의 결론이 "**진짜 해법은 retention `@Scheduled` 잡**"이었고, 이 ADR이 그 잡의 구현 기록이다.

정리 대상 3개 테이블은 모두 **무한 성장**한다:

- `action_audit` — prepare/confirm 2단계 쓰기 액션의 감사 레코드(ADR 0059). 매 예약 시도마다 1행+.
- `library_reservation_outbox` — intent 상태 이벤트의 outbox(relay가 발행 후 `published_at` 스탬프). 발행 후에는 재사용되지 않는다.
- `library_reservation_intents` — 좌석 대기/즉시예약 intent. terminal 후에는 최근 상태 조회 외 용도가 없다.

## 결정

**앱 레벨 `@Scheduled` 일일 정리 잡**(`DataRetentionJob`, 04:30 KST)을 추가한다. 핵심 안전 속성: **나이만으로는 절대 삭제하지 않는다** — terminal 판정이 1차 게이트이고 나이는 2차다.

| 테이블 | terminal 판정 | 보존 기간(기본) | 프로퍼티 |
|---|---|---|---|
| `action_audit` | `status IN (SUCCESS, FAILED, EXPIRED, SUPERSEDED)` — PENDING/EXECUTING 제외 | **180일** | `ssuai.retention.action-audit-days` |
| `library_reservation_outbox` | `published_at IS NOT NULL` (status 컬럼 없음 — 발행 스탬프가 terminal 표식) | **30일** | `ssuai.retention.reservation-outbox-days` |
| `library_reservation_intents` | `status IN (SUCCEEDED, FAILED_RACE, FAILED_AUTH, FAILED_UPSTREAM, CANCELLED, EXPIRED)` — REQUESTED/WAITING_FOR_SEAT/RESERVING 제외 | **30일** | `ssuai.retention.reservation-intent-days` |

- 전체 on/off: `ssuai.retention.enabled`(기본 `true`). 나이는 세 테이블 모두 NOT NULL인 `created_at` 기준.
- **왜 180일 vs 30일**: `action_audit`는 실계정 쓰기 액션의 유일한 감사 흔적(ADR 0059)이라 포트폴리오·사후 분석 가치가 있어 후하게 잡았다. outbox/intents는 발행·완료 후 운영 가치가 소멸하는 순수 운영 데이터라 30일이면 디버깅 여유까지 충분하다.

## 대안과 기각 이유

### DB 레벨 제약(CHECK/FK)으로 무결성·보존 대체 ❌ (선행 기각, 2026-06-30)

- CHECK: 코드 enum 문자열을 마이그레이션에 박으면 enum 추가 시 정상 쓰기가 조용히 깨진다(ADR 0055에서 기각). FK: retention 삭제와 coupling — outbox가 intent를 참조하는 FK가 있으면 intent 삭제가 CASCADE나 삭제 순서 부채를 강제한다. 무결성은 앱 레이어(상태머신·조건부 UPDATE)가 이미 보장한다.

### pg_cron(DB 내 스케줄러) ❌

- 삭제 자체는 SQL 한 줄이지만, **배포 단위가 둘로 갈라진다** — 정리 규칙(어떤 status가 terminal인가)은 코드 enum이 진실원천인데 pg_cron 잡은 DB에 산다. enum이 바뀌면 코드와 DB 잡을 따로 맞춰야 하고, 마이그레이션-온-디플로이 파이프라인 밖의 수동 관리 대상이 된다. k3s 단일노드 self-hosted Postgres에 extension 추가·업그레이드 관리 비용도 붙는다. 앱 레벨 잡은 terminal status 집합을 코드에서 직접 참조하므로 단일 배포 단위가 유지된다.

### 파티셔닝(월별 파티션 drop) ❌

- `library_seat_samples`(분당 수십 행, 90일에 ~2천만 행)에는 이미 파티션 drop을 쓴다(ADR 관련: `LibrarySeatSamplePartitionMaintenance`). 그러나 이 3개 테이블은 하루 수십~수백 행 수준이라 DELETE 비용이 무시 가능하고, 파티셔닝은 PK 재설계(파티션 키 포함)·기존 데이터 이관 마이그레이션을 요구한다. 이 규모에 과투자.

### 앱 레벨 `@Scheduled` bulk DELETE ✅ (채택)

- terminal 판정의 진실원천(코드 enum)과 같은 곳에 살고, 단일 배포 단위·기존 스케줄 인프라(leadership lock)·기존 테스트 인프라(H2+Flyway 통합테스트)를 그대로 쓴다. 프로퍼티로 창 조절, 플래그로 즉시 무력화 가능.

## 구현 결정(세부)

- **bulk JPQL DELETE**(`@Modifying @Query`) vs 엔티티 로드 후 `deleteAll` — 채택: bulk. 후보였던 load-then-delete는 수천 행을 영속성 컨텍스트에 하이드레이션하는 낭비 + flush 순서 간섭 위험. DELETE 한 문장이면 된다(기존 `markPendingSuperseded`의 `@Modifying(clearAutomatically=true, flushAutomatically=true)` 관례를 따름).
- **테이블별 독립 트랜잭션**(`REQUIRES_NEW` TransactionTemplate) — 한 테이블 실패(락 경합 등)가 다른 테이블 정리를 롤백/차단하지 않도록. 각 sweep은 try/catch로 격리하고 삭제 행수를 로그로 남긴다.
- **cutoff 기준 컬럼 = `created_at`** — 세 테이블 모두 NOT NULL이라 단일 규칙으로 통일. `completed_at`/`published_at` 기준 대비 편차는 액션 TTL(분)·relay 주기(초) 수준이라 30/180일 창에서 무의미.
- **`published_at IS NOT NULL`이 outbox의 terminal 판정** — outbox엔 status 컬럼이 없다. 미발행 행은 나이가 몇 살이든 남긴다(relay가 언젠가 발행할 권리 보존, at-least-once 훼손 금지).
- **leadership lock 재사용** — 일일 cron 잡 관례(`LibrarySeatSamplePartitionMaintenance`)를 따라 `LibrarySchedulerLeadership.runIfLeader("data-retention", …)`로 멀티포드 중복 실행을 회피한다. 삭제 자체는 멱등이라 lock은 안전장치가 아니라 중복 작업 회피용.
- **04:30 KST**(`zone = "Asia/Seoul"`) — 도서관 좌석 트래픽이 죽는 새벽 시간대, 기존 UTC 03:23(=KST 12:23) 파티션 잡과 분산.

## 동작 방식

1. 매일 04:30 KST `DataRetentionJob.cleanUpScheduled()` → leadership lock 획득 시 `cleanUp()`.
2. `enabled=false`면 즉시 반환. 아니면 테이블별로: cutoff(`now - N일`) 계산 → `REQUIRES_NEW` 트랜잭션에서 bulk DELETE 1문장 → `data retention sweep: table=… cutoff=… deleted=…` 로그.
3. 실패는 테이블 단위로 격리(warn 로그) — 다음 날 재시도가 자연 복구.

## 검증

- H2(PostgreSQL mode)+Flyway 실스키마 통합테스트 `DataRetentionJobIntegrationTests` 5건: ① 오래된 terminal audit 4종(SUCCESS/FAILED/EXPIRED/SUPERSEDED) 삭제, 오래된 PENDING/EXECUTING·최근 SUCCESS 보존 ② 오래된 published outbox 삭제, 오래된 **미발행** outbox·최근 published 보존 ③ 오래된 terminal intent 3종 삭제, 오래된 WAITING_FOR_SEAT/RESERVING·최근 SUCCEEDED 보존 ④ `enabled=false`면 전부 보존 ⑤ 기본값(180/30/30, enabled=true) 고정.
- 전체 `./gradlew test` green 후 머지. 스키마 마이그레이션 0건(DELETE는 기존 스키마로 충분) — prod 배포는 이미지 교체만.

## 예상 면접 질문

1. **retention을 왜 DB(pg_cron)가 아니라 앱에서 돌리나?** (terminal 판정의 진실원천이 코드 enum — DB 잡이면 enum 변경 시 이중 관리. 단일 배포 단위 유지 + 기존 leadership lock/테스트 인프라 재사용. 규모상 DELETE 비용도 무시 가능)
2. **오래된 PENDING 행이 영원히 남으면 어떡하나?** (retention은 나이만으로 절대 삭제하지 않는 게 안전 속성이고, PENDING의 수명은 별도 메커니즘이 담당 — `ActionService.expireStaleActions`가 60초마다 TTL 지난 PENDING을 EXPIRED로 전이시키므로, 전이된 뒤 180일 후 retention이 수거한다. 두 잡의 책임 분리: 상태 전이 vs 저장 공간)
3. **outbox 행을 미발행인 채로는 왜 안 지우나?** (outbox의 계약은 at-least-once — `published_at IS NULL`은 "아직 전달 의무가 남은" 행이라 나이와 무관하게 보존. 발행 스탬프가 곧 terminal 표식이므로 status 컬럼 없이도 안전한 정리 술어가 된다)
