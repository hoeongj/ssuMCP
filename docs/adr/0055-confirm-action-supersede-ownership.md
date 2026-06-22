# ADR 0055 - confirm_action 액션 supersede + 소유권/명시적 ID 확정

- **상태**: Accepted - 2026-06-21 구현
- **날짜**: 2026-06-21
- **범위**: `ActionService` / `ConfirmActionMcpTool` / `ActionAudit`·`ActionAuditRepository`·`ActionStatus`. 비동기 예약 worker와 `ActionAudit` 타임아웃 로직은 별도 PR로 제외.

## 배경

`confirm_action`은 액션 ID를 받지 않고 "가장 최근 PENDING 액션"을 확정·실행했다. 그리고 prepare 도구(`prepare_reserve/cancel/swap_library_seat`, LMS export)는 새 PENDING 액션을 만들 때 기존 PENDING을 만료·무효화하지 않고 계속 쌓았다. 그 결과 다음 시나리오에서 사용자가 다시 승인하지 않은 상태 변경이 실행될 수 있었다:

```
prepare A → prepare B → confirm (B 실행) → confirm 재호출 (오래된 A 실행)
```

`claimPendingAction`은 행 락(SELECT ... FOR UPDATE) + TTL 재검증으로 "같은 액션의 동시 이중 실행"과 "만료 실행"은 막지만, 위처럼 **누적된 서로 다른 PENDING 중 오래된 것**이 두 번째 confirm에서 살아나는 구멍은 막지 못했다. 실제 도서관 좌석 예약/반납/변경 같은 외부 쓰기에서 발생하면 사용자가 의도하지 않은 행동이 일어난다.

또 `confirm_action`은 어떤 액션을 확정하는지 호출자가 특정할 수 없어, 멀티 액션 상황에서 "최근 것"이라는 암묵적 추측에 의존했다.

## 결정

1. **prepare 시 supersede (핵심).** `ActionService.createPendingAction()`이 새 PENDING 행을 insert하기 **전에**, 같은 owner(`student_id` = provider principalKey)의 모든 PENDING 액션을 단일 `@Modifying` UPDATE로 `SUPERSEDED`(신규 종결 상태)로 원자적 전이한다. 이후 한 owner의 활성 PENDING은 항상 최대 1건이다. 모든 prepare 경로가 이 한 지점을 통과하므로 도서관·LMS 액션이 동일하게 보호된다.

2. **prepare 응답에 actionId 노출.** 도서관 prepare는 이미 `LibraryPrepareResult.actionId`로 노출 중. `confirm_action`이 이를 `action_id`로 받아 특정 액션을 지정할 수 있다.

3. **confirm 시 선택적 action_id 타겟팅.**
   - `action_id` 제공 → `claimPendingActionById(owner, id)`로 **해당 액션만** 확정. id가 호출자 owner 소유가 아니거나, PENDING이 아니거나, 만료됐으면 명확한 오류를 반환하고 **절대 다른 액션을 실행하지 않는다.**
   - `action_id` 생략 → `findActivePendingActions(owner)`로 0/1/다수 분기. 0건 → "대기 액션 없음", 다수 → "여러 개이니 action_id 지정" 거부, 1건 → 그 단일 액션을 ID로 확정. supersede 덕분에 1건이 정상이므로 기존 무인자 호출이 그대로 동작하면서 모호함이 사라진다.

4. **confirm 직전 재검증.** 소유권 + 미만료 + 여전히 PENDING을 행 락 안에서 한 번에 확인한다(`lockByIdAndStudentIdAndStatus`의 WHERE 절 = `id AND student_id AND status=PENDING`). 별도 재검증 코드를 추가하지 않고 잠금 클레임이 곧 재검증이다.

## 대안과 기각 이유

- **supersede 범위를 (owner, actionType)별로 한정**: "owner당 PENDING 1건" 요건을 깨고 구멍을 재현한다. prepare reserve(A)와 prepare cancel(B)은 타입이 달라 A가 PENDING으로 남고, B 확정 후 무인자 confirm이 A를 단일 PENDING으로 인식해 실행한다. 그래서 **owner 전체**로 결정했다. 도서관·LMS는 보통 principalKey가 다르지만(서로 다른 로그인 시스템) 한 학생이 충돌하더라도 "owner당 1건"은 방어 가능하며 전체 테스트가 기존 흐름을 보증한다.
- **action_id를 필수로 변경**: 가장 안전하지만 기존 무인자 호출(ssuAgent HITL, 외부 MCP 클라이언트)을 깨뜨려 하위 호환을 잃으므로 기각. 선택적 파라미터 + supersede로 무인자 경로의 모호함을 제거하는 쪽을 택했다.
- **명시적 id 실패 시 "최근 액션"으로 폴백**: 바로 이 폴백이 보안 구멍이다. 잘못된/타 owner id가 다른 액션을 실행하게 되므로 절대 폴백하지 않고 거부한다(크로스-owner 테스트가 executor 미호출로 증명).
- **load + saveAll로 supersede**: 영속성 컨텍스트에 다건 로딩 후 저장하는 방식보다 단일 bulk UPDATE가 원자적이고 동시성에 안전하며 비용이 낮아 `@Modifying` UPDATE를 택했다.
- **SUPERSEDED용 Flyway 마이그레이션 추가**: `action_audit.status`는 `VARCHAR(16)`이고(`SUPERSEDED` 10자 수용) V3·V7 어디에도 CHECK 제약이 없다. `ddl-auto: validate`는 `EnumType.STRING` 열의 enum 문자열 멤버십을 검증하지 않으므로 스키마 변경이 **불필요**하다. CHECK를 추가하는 ALTER는 "additive"가 아니라 향후 enum 확장을 막는 취약점이 되므로 추가하지 않는다. (`expired_at` 컬럼을 supersede 시각 저장에 재사용 — SUPERSEDED도 EXPIRED처럼 "확정되지 못한 PENDING"이다.)

## 동작 방식

1. `createPendingAction(owner, type, payload)`: `markPendingSuperseded(owner, now)` 실행 → 변경 건수>0이면 `library.action{status=superseded}` 카운터를 건수만큼 증가 → 새 PENDING 행 insert. `expire()`/`markExecuting()`이 `status==PENDING`에서만 동작하므로 SUPERSEDED 행은 이후 어떤 전이에도 불활성이고 만료 스케줄러도 건드리지 않는다.
2. `confirmAction(mcp_session_id, action_id?)`: 세션·LIBRARY provider 해석 → owner(principalKey) 확정.
3. 타겟 결정: `action_id` 있으면 그 값, 없으면 활성 PENDING 0/1/다수 분기로 단일 건의 id를 채택(0·다수는 즉시 사용자 메시지 반환, 외부 세션 미접촉).
4. 도서관 토큰 확인(없으면 AUTH_REQUIRED) 후 `claimPendingActionById(owner, targetId)`로 행 락 클레임 → PENDING→EXECUTING. 소유권 불일치/미존재/이미 처리·만료는 예외 → 명확한 거부 메시지(다른 액션 실행 없음).
5. 액션 타입별 실행(예약 intent 큐 / 직접 반납 / 직접 변경)은 기존과 동일하며 결과를 `completeAction`으로 종결한다.

LMS `confirm_lms_material_export`와 `LibraryReservationWebController.confirm`은 여전히 무인자 `claimPendingAction(owner)`(최근 PENDING 클레임)을 쓰지만, supersede로 owner당 PENDING이 1건이 되어 모호함이 사라졌으므로 변경하지 않았다. LMS confirm은 id 타겟팅 대상이 아니라 `LmsExportPrepareResponse`에 actionId를 추가하지 않았다(미사용 필드 회피).

### 테스트

- 단위(repo mock): supersede 호출 검증, `claimPendingActionById`의 소유/만료/미소유 분기, confirm의 명시 id 실행·크로스owner 거부·만료 거부·0건·다수 거부(모두 executor/connector mock — 실제 예약·외부 쓰기 없음).
- 통합(`@SpringBootTest` + 실제 H2 + Flyway): owner X prepare가 X의 기존 PENDING만 SUPERSEDED로 바꾸고 **owner Y는 불변**, `SUPERSEDED`가 `VARCHAR(16)`에 영속, `lockByIdAndStudentIdAndStatus`의 student_id 술어가 실제로 타 owner를 걸러냄, SUPERSEDED 행은 PENDING 잠금 클레임에서 누락. → WHERE 절 소유권 술어를 빼면 실패하는 진짜 보안 검증.

## 예상 면접 질문

1. supersede 범위를 (owner, actionType)이 아니라 owner 전체로 정한 이유는? (타입별로 하면 reserve→cancel 누적으로 동일한 stale 실행 구멍이 재현된다)
2. 명시적 action_id 확정에서 실패 시 "최근 액션"으로 폴백하지 않고 거부하는 이유는? 그 결정이 어떤 공격/오작동을 막는가? (타 owner·잘못된 id가 의도치 않은 외부 쓰기를 실행하는 것)
3. 새 SUPERSEDED 상태에 Flyway 마이그레이션을 추가하지 않아도 안전하다고 판단한 근거는? (`VARCHAR(16)` + CHECK 제약 부재 + `validate`가 enum 문자열 멤버십 미검증) CHECK 추가가 오히려 위험한 이유는?
4. supersede를 load+saveAll이 아닌 단일 `@Modifying` UPDATE로 구현한 이유와 동시성·원자성 측면의 이점은?
5. 보안 경계가 SQL(WHERE 절)에 있을 때 repository를 mock한 단위 테스트만으로 부족한 이유와, 실제 H2 통합 테스트가 무엇을 추가로 증명하는가?
