# LMS 전체 강의자료 조회의 보조 metadata 파싱 회귀

| 항목 | 내용 |
|---|---|
| 관찰일 | 2026-07-16 |
| 영향 기능 | `get_my_lms_courses`, `get_my_lms_materials`, `export_all_lms_materials` 준비 단계 |
| 사용자 증상 | LMS 재로그인은 성공하지만 `외부 서비스 응답 파싱 오류`로 자료 목록과 ZIP 생성을 시작하지 못함 |
| 상태 | fixture로 재현한 회귀 수정·운영 배포 완료, 실제 계정 종단 재검증 대기 |
| 관련 결정 | [ADR 0044](../adr/0044-lms-file-size-head-correction.md) |

## 기대 동작과 실제 동작

로그인된 사용자의 현재 학기(term 46) 과목과 자료 목록을 읽고, 영상·오디오를 제외한 자료를
ZIP 내보내기로 준비해야 했다. 실제로는 로그인과 학기 선택까지 성공한 뒤 자료 목록을 모으는
과정에서 connector parse error가 발생했다. `get_my_lms_courses`와
`export_all_lms_materials`가 같은 실패를 보였으므로 MCP 도구 포장보다 두 경로가 공유하는
`fetchCourses -> fetchMaterials -> correctUnreliableSizes`를 유력 경로로 먼저 조사했다. 현재
운영 stack trace를 확보하지 못했으므로 아래 내용은 실제 코드에서 fixture로 입증된 회귀다.
사용자가 관찰한 오류의 운영 호출 단계와 원인은 배포 후 같은 계정 흐름의 재시도로 확정한다.

## 재현과 증거

1. LearningX modules 응답에 비-PDF 자료 또는 신뢰할 수 없는 크기 값이 있으면 선택적 크기
   보정이 실행된다.
2. 보정은 Commons `content.php`에서 다운로드 URL metadata를 얻는다.
3. 이 endpoint가 HTTP 200 `text/html` 오류 페이지를 반환하면 `LmsHttpSession.getText()`가
   `ConnectorParseException`을 던진다.
4. 예외가 `correctUnreliableSizes` 밖으로 전파되어 이미 정상 파싱한 과목 자료 목록까지
   폐기된다.

MockWebServer로 LearningX JSON 다음에 Commons HTML을 반환하도록 구성했을 때 같은 실패를
재현했다. 회귀를 도입한 공통 세션 리팩터링 전 구현은 metadata의 비-2xx·I/O 실패를
`Optional.empty()`로 바꿨지만, 이후 구현은 HTTP 응답 분류 예외를 그대로 전파했다.

## 검토한 가설

- **LMS 세션 또는 재로그인 문제**: 사용자는 재로그인 성공과 term 46 확인을 관찰했지만,
  이것만으로 뒤따르는 courses/modules/Commons 요청의 쿠키 상태까지 입증되지는 않는다. 운영
  stack trace와 단계별 응답 분류를 확보할 때까지 열린 대안이다.
- **term 46의 courses/modules 응답 스키마 변경**: 학기 목록 확인은 별도 응답이며 과목·모듈
  스키마를 검증하지 않는다. Commons fixture가 독립적인 회귀를 입증했지만 이 운영 가설을
  배제하지는 못한다.
- **모든 Canvas 쿠키를 Commons로 강제 전달**: 기능은 우회할 수 있지만 host별 cookie scope를
  무너뜨려 자격 증명 누출 범위를 키운다. 인증 경계를 약화시키는 방식이라 기각했다.
- **모든 `resolveDownload` 오류를 무시**: 실제 ZIP 다운로드가 실패해도 성공처럼 보이게 하므로
  기각했다.

## fixture로 재현된 회귀

파일 크기 보정은 목록 품질을 높이는 선택 기능인데, 공통 `LmsHttpSession` 리팩터링에서
metadata 응답 검증이 엄격해지면서 그 실패가 필수 자료 조회 실패로 승격됐다. 데이터 plane의
핵심 결과(과목·자료 목록)와 enrichment 결과(정확한 byte 크기)의 실패 경계가 뒤섞인 것이다.

## 해결

`correctUnreliableSizes`에서만 `ConnectorException`, `LmsApiException`,
`LmsSessionExpiredException`을 격리한다. 해당 파일의 `sizeBytes`를 `null`로 남기고 나머지
자료를 계속 반환한다. Commons metadata GET/파싱 단계가 typed connector·LMS·세션 예외를
던지면 요청 단위 budget을 닫아, 다음 파일과 다음 과목의 metadata GET을 건너뛴다. WARN
집계에는 실패·건너뜀 수만 기록하고 URL·쿠키·응답 본문은 남기지 않는다.

HEAD resolver의 비-2xx·I/O·`Content-Length` 누락은 기존 계약대로 개별 파일의
`OptionalLong.empty()`로 정규화한다. 이 경우 해당 크기만 `null`이 되고 request budget은
닫히지 않아 다음 파일 보정은 계속한다. 즉 이번 증폭 제한은 Commons metadata GET의 typed
예외에 한정되며, HEAD 장애의 전체 요청 차단은 후속 과제다.

공개 `resolveDownload`와 ZIP worker의 실제 다운로드 경로는 strict하게 유지했다. HTML,
malformed XML, `<content>`가 아닌 well-formed XML 같은 protocol 예외는 job을 실패시킨다.
Commons `<content>` XML이 문법상 유효하지만 다운로드 URI가 없는 명시적 capability 부재만
`Optional.empty()`로 처리해 해당 항목을 제외하고 나머지 파일로 READY ZIP을 만든다.

## 검증과 회귀 방지

- Commons가 HTTP 200 HTML을 반환해도 자료의 ID·파일명은 보존되고 크기만 `null`이 된다.
- Commons가 malformed XML을 반환해도 목록의 선택적 크기 보정은 격리된다.
- 선택적 metadata 단계가 실패하면 HEAD resolver는 호출되지 않는다.
- 첫 metadata provider 예외 뒤 같은 요청의 남은 파일·과목에는 metadata 요청을 추가하지 않는다.
- 양수 PDF는 추가 요청 없이 기존 크기를 유지한다.
- HEAD 성공은 실제 길이로 교체하고, HEAD 실패는 센티널 대신 `null`을 유지한 뒤 다음 파일은
  계속 보정한다.
- 실제 다운로드에서 malformed XML과 non-Commons XML은 예외로 실패하고, 유효한 Commons
  `<content>` XML의 capability 부재만 개별 항목 제외로 처리한다.
- 실제 학교 endpoint를 테스트에서 호출하지 않고 MockWebServer fixture로 실패를 고정한다.

### 전달과 운영 배포

수정은 PR #220의 `11551e0`으로 main에 fast-forward했다. PR의 Gradle·JaCoCo와 gitleaks가
성공했고, main CI `29474271476`도 backend gate 뒤 AMD64/ARM64 이미지를 발행했다. Image
Updater가 `a6a7215`에서 chart를 `sha-11551e0...`으로 갱신했다. 이어서 배포된 PR #221의
`sha-479fa53...` 이미지도 이 LMS 커밋을 포함하며, 공개 health·readiness와 새 MCP server card,
비로그인 층별 좌석 MCP 호출이 운영 Pod에서 HTTP 200인 것을 확인했다.

개인 LMS 계정의 term 46 course list → export prepare → ZIP READY는 이 검증에 포함하지 않았다.
따라서 코드 fixture의 회귀 수정과 운영 전달은 완료됐지만, 사용자가 처음 본 오류의 정확한 live
단계와 종단 복구는 같은 계정 흐름을 다시 실행한 뒤 확정한다.

## 남은 위험

Commons 자체가 실제 다운로드 시점에도 인증 HTML을 반환하면 ZIP worker는 예외로 실패한다.
반면 metadata가 유효하지만 capability가 없는 개별 항목은 제외된 부분 ZIP이 만들어질 수 있다.
HEAD provider가 광범위하게 실패하면 파일별 `null`로 계속 진행하므로 metadata GET + HEAD
fan-out은 남는다.
이번 수정은 자료 목록과 export 준비를 보조 크기 조회 때문에 잃는 회귀만 닫는다. 배포된
운영 코드에서 실제 계정으로 목록 조회, export job 생성, ZIP 완료까지 확인해야 한다.

## 예상 면접 질문

- 선택적 enrichment와 핵심 조회의 실패 경계를 어떻게 나눴는가?
- 왜 `RuntimeException` 전체를 잡지 않고 외부 connector 예외 계층만 잡았는가?
- 쿠키를 모두 재전송하는 기능 우회가 왜 보안상 좋지 않은가?
- 목록은 fail-soft인데 실제 다운로드는 fail-closed여야 하는 이유는 무엇인가?
