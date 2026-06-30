# ADR 0040 — 외부 리뷰 데이터 정합성 수정 3종 (defaultTerm 단일화 · 0-file export 거부 · 기숙사 "." 더미 메뉴 제거)

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-18 |
| 상태 | Accepted |
| 연관 ADR | [ADR 0033](0033-lms-material-zip-export.md) (LMS ZIP export), [ADR 0039](0039-mcp-session-isolation-review.md) (같은 점검의 보안 항목) |
| 트리거 | ChatGPT·Claude Desktop 외부 MCP 클라이언트 전수 호출 점검에서 드러난 P1 데이터 품질 문제 |

> 전수 점검에서 드러난 다수 항목 중 **코드만으로 원인을 확정·재현할 수 있는** 3건을 수정한다. scraper 셀렉터/라이브 페치 의존 항목(B1 `get_academic_policy_brief` live 크래시, B4 `get_notice_detail` 메타데이터 공백)과 **스펙 변경이 필요한 설계 항목**(응답 스키마 통일, 장학 판정 분리, 임베딩 하이브리드, 좌석 wait 안전성)은 본 ADR 범위 밖 — 사용자 확정/실측 후속이다(철칙 2).

---

## 수정 1 — `get_my_lms_terms`: 다중 `defaultTerm:true` → 단일 활성 학기

### 배경
리뷰어 둘 다 `get_my_lms_terms`가 여러 학기(id 47·46·45·38 등)를 동시에 `defaultTerm:true`로 반환함을 지적. 도구 설명은 "term_id 생략 시 기본 학기 자동 선택"이라 **기본이 복수면 어느 학기가 선택될지 비결정적**으로 보인다.

### 원인
`LmsTermItem.defaultTerm`은 Canvas API의 원시 플래그를 그대로 노출한다. Canvas는 "수강신청이 열린 학기"를 default로 표시할 수 있어(여름 수강신청이 봄학기 수업 중에 열림) **여러 학기가 동시에 default**가 된다 — 이건 이미 알려진 quirk라서 내부 학기 해석은 `LmsTermResolver`(날짜 겹침 우선, ADR 0033 후속/2026-06-16)를 쓰고 **원시 default를 신뢰하지 않는다**. 즉 버그는 "해석 로직"이 아니라 **도구가 노출하는 출력**이 원시 플래그를 그대로 내보낸 것.

### 대안
| 대안 | 채택 |
|---|---|
| A. DTO에 `isCurrentAcademicTerm`/`isNonRegular` 등 새 필드 추가 | ✗ 스키마 변경 + 프론트/LLM 동시 변경. 리뷰어 제안이나 효용 대비 변경 큼 |
| B. (채택) 노출 직전 `LmsTermResolver`가 고른 학기 1개만 `defaultTerm=true`로 정규화 | ✓ 스키마 무변경. 노출 플래그가 **실제로 assignments/export가 쓰는 학기와 일치**(단일 진실원) |

### 결정
`LmsTermResolver.withResolvedDefault(terms)` 추가 — `resolveCurrentTermId`가 고른 학기에만 `defaultTerm=true`, 나머지 false인 복사본 반환. `LmsAssignmentsMcpTool.getMyLmsTerms`가 `fetchTerms` 결과를 이 함수로 감싸 노출. 해석 로직과 동일 함수를 재사용하므로 노출과 실제 선택이 **영원히 일치**. 테스트 결정성을 위해 `resolveCurrentTermId`와 동일하게 `(terms, now)` 패키지-프라이빗 오버로드 제공.

---

## 수정 2 — `prepare/confirm_lms_material_export`: 0개 파일 export 거부

### 배경
리뷰어가 invalid content_id만 넣었는데도 prepare가 "내보내기 준비 완료"로 처리하고, 이어 confirm이 **0개 파일짜리 export job + 다운로드 capability URL**을 발급함을 지적. 기능 버그이자 경미한 보안 문제(빈 토큰 URL 발급).

### 원인
`LmsMaterialExportService.finalizeExport`가 `acceptedSelections`가 비어 있어도 **무조건** `actionService.createPendingAction(...)`을 호출하고 "준비 완료" 메시지를 반환. 그러면 confirm이 그 pending action을 claim해 0-file job과 토큰 URL을 만든다.

### 대안
| 대안 | 채택 |
|---|---|
| A. DTO에 `status: VALIDATION_FAILED` 필드 추가 + confirm에 `EXPORT_EMPTY` 코드 | ✗ 스키마 변경. 효과는 같지만 변경 큼 |
| B. (채택) `acceptedSelections`가 비면 **pending action을 만들지 않고** 명확한 메시지로 조기 반환 | ✓ 최소 변경. confirm은 claim할 게 없어 기존 `NoPendingActionException` 경로로 "대기 중인 요청 없음"을 안내(이미 도구가 catch). prepare/exportAll 양쪽을 한 곳에서 커버 |

### 결정
`finalizeExport`의 한도 루프 직후 `if (acceptedSelections.isEmpty())` 가드: pending action 생성 없이 `fileCount=0` + "내보낼 수 있는 파일이 없습니다…" 메시지 반환. `LMS_MATERIAL_EXPORT` pending action을 만드는 경로는 `finalizeExport` 하나뿐이라 prepare·exportAll 모두 안전해진다. confirm 측은 변경 불필요(빈 pending action이 더는 생성되지 않으므로 0-file job 발급 자체가 불가능).

> capability URL 토큰 마스킹/one-time/소유자 검증 같은 추가 강화는 리뷰의 별도 P1/P2 — 본 수정은 "빈 job에 URL을 발급하지 않는다"만 보장한다.

---

## 수정 3 — 기숙사 주간식단: "." 등 구두점-only 더미 메뉴 제거

### 배경
리뷰어가 기숙사 주말 데이터에서 메뉴가 `"."`(또는 `"-"`)로 들어오는 케이스를 지적. 크롤링 원본의 빈칸 placeholder 문자를 메뉴로 처리한 것.

### 원인
`RealDormMealConnector.splitMenu`가 줄을 `!line.isBlank()`로만 거른다. `"."`/`"-"`는 blank가 아니라 살아남아 메뉴 항목이 된다.

### 대안
| 대안 | 채택 |
|---|---|
| A. 더미 토큰 denylist (`".", "-", …`) | ✗ 빠뜨림 발생(`·`, `—`, `...` 등 변형) |
| B. (채택) 글자/숫자가 하나도 없는 줄을 메뉴 아님으로 제거 | ✓ 모든 구두점-only 변형을 한 번에 처리. 실제 메뉴는 항상 한글/영문/숫자를 포함하므로 오탈락 없음 |

### 결정
`splitMenu`에 `hasMenuContent` 필터 추가 — `line.codePoints().anyMatch(Character::isLetterOrDigit)`. 한글은 letter라 보존, 순수 구두점 placeholder만 탈락. closure(휴무/미운영) 처리는 별도 키워드 경로라 영향 없음.

> 문장형 안내("하계방학기간 …운영하지 않습니다")를 메뉴가 아닌 `closures`로 빼는 처리는 이미 `CLOSURE_KEYWORDS` 경로가 담당. 코너별 미운영(특정 코너만 닫힘) 같은 구조 개선은 스키마 변경이라 후속(리뷰 P2).

---

## 검증
- 신규/보강 단위 테스트 green: `LmsTermResolverTests`(다중 default 단일화·null/empty), `LmsMaterialExportServiceTests`(전량 invalid → pending action 미생성·"파일 없음" 메시지), `RealDormMealConnectorParseTests`(구두점-only placeholder 탈락). 전체 스위트 green.

## 예상 면접 질문
1. Canvas가 여러 학기를 동시에 default로 표시하는데, 왜 DTO에 새 필드를 추가하지 않고 노출 직전 단일화를 택했나? 단일 진실원(해석 함수 재사용)이 주는 이점은?
2. 0-file export를 confirm 쪽이 아니라 prepare(`finalizeExport`)에서 막은 이유는? pending action을 만드는 경로가 하나뿐이라는 사실이 왜 중요한가?
3. 더미 메뉴를 denylist가 아니라 "글자/숫자 유무"로 거른 이유는? 한글이 `Character.isLetterOrDigit`에서 어떻게 처리되나?
