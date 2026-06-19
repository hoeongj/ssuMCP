# ADR 0043 — 장학 정책 구조화 판정 응답

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-19 |
| 상태 | Accepted |
| 범위 | `check_scholarship_policy`, `AcademicPolicyService.checkScholarshipPolicy(...)`, `ScholarshipPolicyCheckResponse` |
| 연관 ADR | [ADR 0020](0020-academic-policy-hybrid-rag.md) (학칙·졸업·장학 공식 출처 RAG) |

---

## 배경

`check_scholarship_policy`는 장학 질문과 사용자가 제공한 `gpa`, `earnedCredits`, `admissionYear`, `topikLevel`, `internationalStudent` 값을 조합해 공식 장학 근거를 찾는 도구였다. 그러나 응답은 `evidence`와 주의문 중심이라, 호출자는 "이 학생이 조건을 충족하는가"를 다시 해석해야 했다.

이 구조는 포트폴리오 관점에서 약하다.

- 근거는 보여 주지만 최종 판정(`충족`, `미충족`, `판단 근거 부족`)이 없다.
- 어떤 조건이 어떤 기준으로 통과/실패/미확인인지 추적할 수 없다.
- 필요한 학생 입력값이나 정책 기준이 없을 때 모델이 추측하기 쉽다.
- 장학금처럼 학생에게 실제 영향을 주는 판단은 "모르면 모른다" 경로가 명시되어야 한다.

2026년 business-rule-engine 흐름도 이 방향과 맞다. DecisionRules의 2026 비교 글은 최신 BRE가 단순 실행보다 decision table, rule lifecycle, governance, auditability를 중시한다고 정리한다. Eligify는 eligibility rule을 숨은 코드가 아니라 data-driven, auditable evaluation으로 만들고 각 evaluation에 "why"가 붙는다고 설명한다. EU 쪽에서도 SCHUFA 판례와 EU AI Act Article 13은 자동화 판단의 투명성·해석 가능성을 중요한 요구로 다룬다.

출처:

- DecisionRules, "Top 10 Business Rule Engines for 2026: Features Compared" — https://www.decisionrules.io/en/articles/top-10-business-rule-engines/
- DEV Community, "Eligify — The Criteria and Rule Engine for Explainable Decisions" — https://dev.to/nasrulhazim/eligify-the-criteria-and-rule-engine-for-explainable-decisions-3jbe
- CJEU SCHUFA C-634/21 — https://infocuria.curia.europa.eu/tabs/redirect/juris/document/document.jsf?docid=280426&doclang=en
- EU AI Act Article 13 transparency — https://artificialintelligenceact.eu/article/13/

---

## 결정

`check_scholarship_policy`는 evidence-only 응답을 유지하지 않고, 공식 근거에서 추출 가능한 조건별 판정과 전체 판정을 함께 반환한다.

응답 필드:

- `decision`: `ELIGIBLE`, `NOT_ELIGIBLE`, `INSUFFICIENT_EVIDENCE`
- `matchedRequirements`: 조건별 판정 배열
  - `requirement`: 조건 이름 또는 설명
  - `required`: 공식 근거에서 추출한 기준값 또는 필요한 확인 항목
  - `userValue`: 학생 입력값. 없으면 `null`
  - `result`: `OK`, `FAIL`, `UNKNOWN`
- 기존 `evidence`: 유지. 출처 URL, revision/effectiveDate, snippet을 계속 제공한다.
- 기존 `query`, `inputFacts`, `summary`, `caveats`: 유지. 기존 호출자가 근거와 주의문을 함께 볼 수 있게 한다.

판정 집계 규칙:

1. 하나라도 `FAIL`이면 `NOT_ELIGIBLE`.
2. `FAIL`이 없고 하나라도 `UNKNOWN`이면 `INSUFFICIENT_EVIDENCE`.
3. 모든 조건이 `OK`이면 `ELIGIBLE`.

핵심은 `INSUFFICIENT_EVIDENCE`다. 필요한 학생 입력값이 없거나 공식 근거에서 기준 수치를 명확히 추출하지 못하면 절대 `OK` 또는 `FAIL`로 추측하지 않는다.

---

## 기각한 대안

| 대안 | 판정 | 짧은 이유 |
|---|---|---|
| 판정 + 짧은 이유 한 줄 | 기각 | 감사·설명 약함. 어떤 조건이 어떤 값으로 실패했는지 추적 불가 |
| 기존 evidence + `decision` 1필드만 추가 | 기각 | 조건별 `required/userValue/result`가 없어 재현 가능한 판단 기록이 안 됨 |
| Drools 같은 범용 rule engine 도입 | 기각 | 현재 조건 수가 작고 런타임 정책을 외부 편집하지 않음. 의존성·운영 복잡도만 증가 |
| 정책 근거가 애매해도 입력값으로 보수 판정 | 기각 | "모르면 모른다" 요구와 충돌. 장학 판단에서 추측은 사용자 신뢰를 해침 |

---

## 동작 방식

1. 기존처럼 `query`와 입력값을 `inputFacts`로 합쳐 장학 공식 근거를 검색한다.
2. `AcademicPolicyBriefResponse.evidence()`를 그대로 응답에 보존한다.
3. evidence의 `title`, `heading`, `snippet`, `matchedTerms`에서 명시 조건을 추출한다.
   - GPA/평점: `GPA 3.5 이상`, `평점 3.0 이상` 같은 기준
   - 취득학점: `취득학점 15학점 이상`, `이수학점 12학점 이상` 같은 기준
   - 입학연도: `2025학년도 이후 입학자` 같은 기준
   - TOPIK: `TOPIK 4급 이상` 같은 기준
   - 외국인 유학생 여부: policy/query가 외국인 유학생 장학금 범위를 명시할 때 확인
4. 각 조건을 `MatchedRequirement`로 변환한다.
   - 기준과 학생값이 모두 있으면 비교 후 `OK` 또는 `FAIL`.
   - 학생값이 없으면 `UNKNOWN`.
   - 근거에 조건은 보이지만 정량 기준을 못 찾으면 `UNKNOWN`.
   - 복수 기준이 동시에 잡혀 세부 구간을 특정할 수 없으면 `UNKNOWN`.
5. evidence에 `등록 상태`, `국가장학금 신청 여부`, `중복 수혜 제한`, `정규학기 제한`처럼 현재 도구 인자로 확인할 수 없는 필요 조건이 있으면 해당 조건도 `UNKNOWN`으로 기록한다.
6. `FAIL > UNKNOWN > OK` 우선순위로 전체 `decision`을 집계한다.

이 방식은 full BRE 제품을 붙이지 않아도 핵심 포트폴리오 포인트를 만든다. 공식 근거 검색(RAG)은 그대로 설명 가능하고, 그 위에 deterministic rule evaluation과 per-condition audit trail이 생긴다.

---

## 검증

단위 테스트는 네 가지 경로를 고정한다.

- 모든 조건 `OK` → `ELIGIBLE`
- 하나라도 `FAIL` → `NOT_ELIGIBLE`
- 필요한 학생 입력값 누락 → 해당 조건 `UNKNOWN` + `INSUFFICIENT_EVIDENCE`
- 정책 근거에 조건은 있으나 정량 기준 없음 → `UNKNOWN` + `INSUFFICIENT_EVIDENCE`

네트워크를 쓰지 않고 mocked `AcademicPolicyCorpusSnapshot`으로 공식 근거 문장을 주입해 검증한다.
