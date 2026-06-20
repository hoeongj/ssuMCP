# ADR 0049 - B5: 채플 졸업요건 머지 — rusaint 미채움 보정

- **Status**: Accepted - 2026-06-19 구현 / 2026-06-20 lookup() 방식으로 교체 / 2026-06-20 성적표(CourseGrades) 피벗으로 재교체 / 2026-06-20 courseCode 매칭으로 보강
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

### D3. 완료 학기 카운트: 성적표(CourseGradesApplication) 기반 카운트 (3차 수정, 2026-06-20)

**1차 구현(`5ee1526`)** — `fetchChapelInfo(year, semester)` 학기별 직접 조회: u-SAINT 채플 드롭다운이 오래된 학기를 노출하지 않아 과거 학기 호출이 전부 "No chapel information provided" → 기각.

**2차 구현(`f97bd2a`)** — `ChapelApplication.lookup()` "이전학기 버튼"으로 가정한 역방향 순회: prod 배포 후에도 채플 항목이 0/0 그대로 → 바인딩 소스 직접 확인(`rusaint_ffi.kt:1938`): `lookup()` doc = "최신 정보를 조회합니다. 새로고침 시 사용합니다" = **현재 학기 refresh**, 이전 학기 이동이 아님. `getSelectedSemester()`가 매번 같은 값을 반환 → stuck 감지 즉시 발동 → 현재 학기 1개만 읽고 종료 → 기각.

**3차 구현(현재)** — `CourseGradesApplication`(성적표) 기반 카운트: 숭실대 채플은 성적표에 학기마다 0.5학점 P/F 과목으로 기록된다. `app.semesters(BACHELOR)` 한 번으로 **전학기 누적 이력**을 반환(드롭다운/lookup 네비게이션 불필요). 각 학기 `app.classes()`에서 `isChapelCourse(c.code, c.className) && score == ClassScore.Pass`를 세면 누적 이수 학기 수가 나온다. 이 경로는 `get_my_grades`(`fetchGrades()`)로 이미 prod 검증 완료.

```kotlin
app.semesters(courseType).sumOf { term ->
    app.classes(courseType, term.year, term.semester, false)
        .count { c -> isChapelCourse(c.code, c.className) && c.score == ClassScore.Pass }
}
```

대안 비교:
| 소스 | 누적 | 과거 학기 접근 | 이미 검증됨 |
|---|---|---|---|
| `ChapelApplication.information()` (드롭다운) | ✗ | ✗ (오래된 학기 미노출) | 실패 확인 |
| `ChapelApplication.lookup()` | ✗ | ✗ (refresh, not navigate) | 실패 확인 |
| `CourseGradesApplication.semesters()` | **✓** | **✓** | **✓** (`get_my_grades`) |

### D6. 채플 과목 매칭 키: 학수번호(courseCode) 우선 (2026-06-20, step 0 실측 반영)

배포 전 검증 스파이크(`get_my_grades` 실측)에서 과목명이 학기마다 다름을 확인:
- 2022학번 코호트: `"CHAPEL"` (영문)
- 2025학번 코호트: `"비전채플"` (한글)
- 공통: courseCode `"21501015"` (학수번호, 카탈로그 레벨 → 과목명 개명에도 불변)

`className.contains("채플")` 단독 매칭은 "CHAPEL" 행을 놓쳐 4 대신 2를 반환했을 것. 학수번호를 primary key, 이름을 fallback으로:

```kotlin
// companion object (RusaintUniFfiClient)
internal const val CHAPEL_COURSE_CODE = "21501015"

internal fun isChapelCourse(code: String, className: String): Boolean =
    code == CHAPEL_COURSE_CODE ||
        className.contains("채플") ||
        className.uppercase().contains("CHAPEL")
```

**대안 비교**:

| 매칭 전략 | "비전채플" 2025 | "CHAPEL" 2022 | 향후 개명 대응 |
|---|---|---|---|
| `className.contains("채플")` | ✓ | **✗** | 미지수 |
| 학수번호 `"21501015"` 단독 | ✓ | ✓ | ✓ (카탈로그 불변) |
| 학수번호 + 이름 fallback (채택) | ✓ | ✓ | ✓ + 이름 변경 시 추가 보호 |

술어를 companion 순수 함수로 추출해 라이브 세션 없이 CI 단위 테스트 가능 — 세 번 조용히 실패한 매칭 로직이 처음으로 직접 테스트 커버리지를 얻게 됨.

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
