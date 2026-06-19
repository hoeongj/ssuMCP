# ADR 0049 - B5: 채플 졸업요건 머지 — rusaint 미채움 보정

- **Status**: Accepted - 2026-06-19 구현
- **Date**: 2026-06-19
- **Scope**: `SaintGraduationService`, `SaintGraduationServiceTests`

## 배경

`check_graduation_requirements`가 반환하는 `GraduationStatus.requirements` 중 채플 항목(`name="채플이수"`)이 `required=0/completed=0/satisfied=false`로 채워진다. rusaint의 `GraduationRequirementsApplicationBuilder`가 채플 데이터를 graduation API에서 매핑하지 못하기 때문이다(`GraduationRequirement.requirement = null, calculation = null`).

반면 `SaintChapelConnector`는 학기별로 `ChapelInfo.result`("P"/"F")를 반환한다. 이 두 소스를 합치면 실제 채플 이수 학기 수를 구할 수 있다.

## 결정

### D1. 머지 위치: SaintGraduationService (서비스 레이어)

Connector 레이어(RusaintGraduationConnector)는 외부 시스템 호출만 담당한다. 두 Connector의 결과를 합치는 비즈니스 로직은 Service 레이어에 둔다. Connector에 다른 Connector를 주입하면 레이어 의존 방향이 오염된다.

### D2. 채플 필요 학기 수: 6 (상수)

rusaint graduation API가 `requirement=null`을 반환하므로 숫자를 코드에서 정의해야 한다. 숭실대 학사 규정상 신입생 기준 6학기 이수. 편입생/전과생 규정이 다를 경우 이 상수를 별도 설정으로 분리할 수 있다(현재는 단순화).

### D3. 완료 학기 카운트: 학년(grade)으로 역산 + 학기 순회

`GraduationStatus.grade`로 입학 연도를 추정(`entryYear = currentYear - grade + 1`).
1학기→2학기 순으로 entryYear부터 현재 학기까지 순회하며 `result == "P"` 건수를 집계한다.

현재 학기는 첫 번째 chapel 호출(`year=null, semester=null`)에서 가져오므로 추가 호출 없이 재사용한다.

### D4. 실패 시 graceful fallback (원본 0/0 유지)

- 첫 chapel 호출 실패 → 0을 반환해 원본 GraduationStatus 그대로 반환 (WARN 로그)
- 과거 학기 호출 실패 → 해당 학기 skip (DEBUG 로그, 전체 요청 실패하지 않음)

### D5. rusaint가 이미 데이터를 채운 경우 머지 생략

`chapel.required() > 0 || chapel.completed() > 0`이면 rusaint가 이미 데이터를 갖고 있으므로 chapel connector를 호출하지 않는다.

## 대안 검토

- **RusaintGraduationConnector에서 머지**: Connector 레이어가 다른 Connector를 알게 되어 의존 방향 오염. 기각.
- **MCP Tool에서 두 Tool 결과 합산**: MCP tool은 단일 책임. 사용자가 명시적으로 두 tool을 호출하지 않으면 merge가 발생하지 않는다. 기각.
- **chapel required=6 설정 파일화**: 현시점 단일 값. 추후 편입생/규정 변경 시 분리.

## 포트폴리오 포인트

외부 API가 특정 필드를 채우지 못하는 경우 다른 소스에서 보정 데이터를 가져와 서비스 레이어에서 머지하는 패턴. "데이터 소스 한계를 어떻게 우회했나"가 면접 설명거리가 된다.
