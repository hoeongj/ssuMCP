# ADR 0052: 장학금 판정 로직을 ScholarshipPolicyEvaluator로 분리

- 상태: 승인
- 일자: 2026-06-20
- 범위: `academic/service`
- 성격: 동작 보존 리팩터링

## 배경

`AcademicPolicyService`는 하나의 클래스에서 서로 다른 두 책임을 수행하고 있었다.

1. 공식 학사 정책 corpus를 대상으로 lexical/vector 후보를 만들고 RRF로 결합한 뒤 evidence를 반환하는 검색·검색결과 요약 책임
2. 장학금 evidence에서 GPA, 취득학점, 입학연도, TOPIK 기준을 추출하고 학생 입력값과 비교하여 `ELIGIBLE`, `NOT_ELIGIBLE`, `INSUFFICIENT_EVIDENCE`를 결정하는 판정 책임

두 책임은 변경 이유가 다르다. 검색 품질을 조정할 때는 chunking, 점수, 후보 수, RRF 상수와 evidence 생성이 바뀌지만, 장학금 판정 정책을 조정할 때는 정규식, 요구사항 비교, `OK`/`FAIL`/`UNKNOWN` 집계가 바뀐다. 한 클래스에 유지하면 변경 범위가 불필요하게 넓고 리뷰 시 검색 변경과 판정 변경을 구분하기 어렵다. 이는 서로 다른 책임을 별도 클래스로 추출하는 Extract Class의 적용 조건과 일치한다.

## 검토한 대안

### 대안 1: `AcademicPolicyService` 내부에 그대로 유지

- 장점: 생성자와 테스트 wiring이 바뀌지 않는다.
- 기각 이유: 검색과 장학금 판정이라는 두 변경 축이 계속 결합된다. 후속 변경의 영향 범위와 코드 리뷰 비용을 줄이지 못한다.

### 대안 2: evaluator가 corpus/cache 또는 `AcademicPolicyService`를 주입받아 직접 검색

- 장점: `checkScholarshipPolicy` 전체 흐름을 evaluator로 옮길 수 있다.
- 기각 이유: evaluator가 검색 인프라에 의존하여 새 순환 의존 또는 과도한 책임을 갖게 된다. 순수 판정 로직을 격리하려는 목적과 맞지 않고, 검색 동작을 함께 건드려 동작 보존 증명 범위를 넓힌다.

### 대안 3: 검색은 서비스에 남기고, stateless evaluator를 생성자 주입

- 장점: `AcademicPolicyService`가 기존과 동일하게 `brief(..., "scholarship", ...)`를 실행하고, evaluator는 이미 수집된 evidence와 학생 사실만 받아 동일 응답을 조립한다. 검색 경로와 MCP 공개 진입점은 그대로 유지된다.
- 단점: `AcademicPolicyService` 생성자에 의존성 하나가 추가되고 직접 생성하는 테스트 한 곳의 wiring 변경이 필요하다.
- 선택: 변경 표면이 가장 작고 두 책임의 경계를 명확히 증명할 수 있어 채택했다.

## 결정

`ScholarshipPolicyEvaluator`를 Spring `@Component`로 추가하고 `AcademicPolicyService`에 생성자 주입한다.

`AcademicPolicyService`에 남기는 항목:

- `checkScholarshipPolicy(...)` 공개 진입점과 학생 사실 문자열 구성
- `brief(...)` 호출을 통한 corpus 검색/evidence 수집
- lexical/vector 후보 생성, RRF 결합, score/snippet/token 처리와 검색 상수

`ScholarshipPolicyEvaluator`로 이동하는 항목:

- `buildScholarshipQuery`
- GPA, 취득학점, 입학연도, TOPIK 전용 정규식
- evidence 문자열 구성과 숫자 기준 추출
- 요구사항별 `OK`/`FAIL`/`UNKNOWN` 판정
- `NOT_ELIGIBLE` 우선, `UNKNOWN` 존재 시 `INSUFFICIENT_EVIDENCE`, 그 외 `ELIGIBLE`인 집계 규칙
- summary/caveat와 `ScholarshipPolicyCheckResponse` 조립

정규식 `Pattern`은 생성 후 불변이며 여러 스레드가 안전하게 공유할 수 있으므로 기존과 동일하게 클래스의 `static final` 상수로 유지하되, 실제 사용 책임이 있는 evaluator로만 옮긴다.

## 동작 방식

1. `AcademicPolicyService.checkScholarshipPolicy(...)`가 기존 순서대로 null이 아닌 학생 입력을 `facts`에 추가한다.
2. evaluator가 기존 문자열 규칙 그대로 검색 query를 만든다.
3. 서비스가 기존 `brief` 경로로 장학금 evidence와 검색 caveat를 수집한다.
4. evaluator가 query, facts, evidence, caveat, 학생 입력값을 받아 기존 정규식과 비교 분기를 그대로 수행한다.
5. evaluator가 기존 순서와 문구 그대로 `ScholarshipPolicyCheckResponse`를 반환한다.

서비스가 evaluator에 evidence를 전달하므로 corpus/cache/embedding 의존성은 evaluator에 유입되지 않는다. evaluator에는 `chat` 패키지 의존성도 없다.

## 동작 보존 근거

- 기존 판정 메서드와 전용 정규식을 조건·상수·문구·목록 추가 순서 변경 없이 이동했다.
- 공개 메서드 시그니처와 MCP tool 호출 경로를 변경하지 않았다.
- 검색 category, limit, live 전달값과 `brief` 호출 시점을 변경하지 않았다.
- 응답의 `query`, `facts`, `decision`, `matchedRequirements`, `summary`, `caveats`, `evidence` 조립 순서를 유지했다.
- 기존 `AcademicPolicyServiceTests`가 동일한 외부 진입점을 통해 단일 기준, 복수 기준, 입력 누락, evidence 부족의 판정 결과를 검증한다.
- 최종 승인 조건은 전체 `gradlew.bat test`의 `BUILD SUCCESSFUL`이다. 테스트를 수정하거나 완화하지 않고 생성자 wiring만 갱신한다.

## 결과와 트레이드오프

- 검색 서비스는 검색·retrieval 책임에 집중하고 장학금 판정 변경은 evaluator 안에서 검토할 수 있다.
- evaluator가 stateless이므로 Spring singleton lifecycle에 별도 동기화가 필요하지 않다.
- 이번 변경은 순수 리팩터링이므로 새 정책 규칙, 새 테스트 시나리오, 새 API를 추가하지 않는다.

## 참고 자료

- Martin Fowler, Extract Class: https://refactoring.com/catalog/extractClass.html
- Spring Framework Reference, Constructor-based Dependency Injection: https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html#beans-constructor-injection
- Java SE 21 API, `java.util.regex.Pattern`: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/regex/Pattern.html

## 예상 면접 질문

1. 왜 evaluator가 직접 `AcademicPolicyService`나 corpus cache를 호출하도록 만들지 않았나요?
2. 순수 리팩터링에서 응답 동작이 동일하다는 것을 어떤 경계와 테스트로 증명했나요?
3. stateless evaluator를 Spring singleton component로 두어도 안전한 이유는 무엇인가요?
