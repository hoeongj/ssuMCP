# ADR 0034 — LMS 과목 조회 API를 LearningX 엔드포인트 및 Bearer 인증 방식으로 전환

- **Status**: Accepted
- **Date**: 2026-06-16

## 배경

`get_my_lms_courses` 도구가 LMS 세션이 활성화되어 있음에도 `AUTH_REQUIRED`를 반환하는 버그가 발생했다. 원인은 `RealLmsMaterialsConnector.fetchCourses()`가 Canvas Native API인 `/api/v1/courses`를 호출할 때 Cookie 기반 인증만 수행했기 때문이다.
반면 다른 모든 LMS API 요청들은 LearningX 엔드포인트(`/learningx/api/v1/...`)와 `Authorization: Bearer {xn_api_token}` 인증을 사용하고 있었고, Canvas Native API 호출 실패가 발생해 `LmsSessionExpiredException`으로 잡혀 잘못된 세션 만료 응답이 가고 있었다.

## 검토한 대안

### 1. Canvas Native API에 Bearer 헤더를 추가해서 유지
- **평가**: Canvas Native API 역시 Bearer 헤더를 수락할 수 있으나, 학기(Term) 필터링이나 API 스펙 일관성 측면에서 다른 API들과 일치하지 않음.

### 2. LearningX API 엔드포인트 `/learningx/api/v1/learn_activities/courses?term_ids[]={termId}` 사용 (채택)
- **평가**: `RealLmsAssignmentsConnector`에서 이미 동일한 방식으로 과목 목록을 정확하게 조회하고 있으므로 검증된 방식임. 서버사이드에서 학기 필터링이 가능하고 Bearer 토큰이 필수적으로 포함되어 일관성이 높음.

## 결정

1. `RealLmsMaterialsConnector.fetchCourses`가 `/learningx/api/v1/learn_activities/courses?term_ids[]={termId}` 엔드포인트를 호출하도록 수정한다.
2. Bearer 인증 헤더(`Authorization: Bearer {xn_api_token}`)를 추가한다.
3. API 응답 형식 파싱 로직을 기존 Canvas Native 전용 파서(`parseCanvasJson`) 대신 standard `objectMapper.readTree()`로 전환한다.
4. 클라이언트 사이드 학기 필터링(`enrollmentTermId == termId`) 로직을 제거한다.

## 어떻게 작동하는지

- `extractXnApiToken()`을 이용해 쿠키에서 `xn_api_token`을 추출하여 Authorization 헤더에 Bearer 토큰으로 포함한다.
- `term_ids[]` 쿼리 파라미터를 통해 서버 사이드에서 지정된 학기로 필터링된 과목 목록만 수신한다.
- LearningX API는 XSSI 방지용 `while(1);` 접두사가 포함되지 않은 표준 JSON 배열을 반환하므로, 접두사 스트리핑(stripping)을 하지 않고 바로 `objectMapper.readTree()`로 파싱한다.

## 결과

- **세션 예외 처리 신뢰성 향상**: 정상적인 세션 상태임에도 잘못된 Canvas Native API 오류로 인해 세션 만료(`AUTH_REQUIRED`)로 오진되는 문제가 완전히 해결된다.
- **불필요한 연산 제거**: XSSI `while(1);` 접두사 제거를 위한 문자열 스캔 연산(`parseCanvasJson`)이 생략되어 메모리 및 응답 파싱 속도가 개선된다.
- **클라이언트 필터 제거**: 서버사이드에서 학기(`termId`) 단위로 필터링되어 전달되므로 클라이언트 사이드에서의 불필요한 필터링 루프가 제거된다.

## 근거와 출처

- `RealLmsAssignmentsConnector.fetchCourseNames()`의 검증된 학습 활동 과목 목록 API 호출 형태를 이식하여 일관성을 확보함.
