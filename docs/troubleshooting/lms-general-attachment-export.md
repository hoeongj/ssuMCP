# LMS 일반 첨부파일이 전체 ZIP 내보내기를 실패시킨 문제

| 항목 | 내용 |
| --- | --- |
| 관찰일 | 2026-07-16 |
| 영향 | 일반 첨부 ZIP이 포함된 LMS 전체 강의자료 다운로드 링크가 파일 생성 실패 페이지를 표시함 |
| 비교군 | PDF 70개 성공, `contentType=file` ZIP 4개 실패, 두 집합을 합치면 전체 실패 |
| 상태 | resolver·worker 회귀 수정 및 fixture 검증 완료, 실계정 일반 첨부 종단 재검증 대기 |

## 기대 동작과 실제 동작

선택한 비영상 강의자료를 과목별 디렉터리로 묶어 하나의 ZIP으로 제공해야 한다. 실제 비교에서는 PDF 70개와 PDF만 포함한 과목별 내보내기는 성공했지만, 일반 첨부 ZIP 4개만 선택한 작업과 그 4개를 포함한 전체 74개 작업은 실패했다.

따라서 312MB 총용량, 74개 파일 수, 바깥쪽 ZIP 압축 자체는 원인에서 제외됐다. 실패 범위는 LearningX가 `contentType=file`로 반환한 일반 첨부의 Commons metadata 또는 실제 다운로드 단계로 좁혀졌다.

## 증거와 원인

같은 일반 첨부 4개는 이전 운영 관찰에서도 `total_file_size=64238`이라는 동일 센티널을 반환했다. 크기 보정이 실패하면 준비 단계는 이 값을 `null`로 바꿔 목록과 작업 생성을 보존한다. 그 결과 준비 응답의 합계는 알려진 PDF 크기만 포함하고 일반 첨부는 0으로 보일 수 있지만, 0-byte 선할당이나 예상 크기 비교 코드는 worker에 없었다. 실제 스트림 제한은 다운로드한 byte 수로 적용된다.

코드 감사에서 두 결함을 확인했다.

1. Commons `content_download_uri`를 항상 `commonsBaseUrl + downloadUri`로 조립했다. PDF fixture처럼 상대 URI이면 동작하지만, 일반 첨부가 절대 Canvas/Commons URI를 반환하면 잘못된 host 문자열이 만들어진다. URI 표준 해석으로 상대 URI만 base에 resolve하고 절대 HTTP(S) URI는 그대로 보존해야 한다.
2. worker의 전체 선택 loop가 하나의 catch-all 안에 있어, 한 항목의 metadata 파싱·protocol·다운로드 오류가 이미 성공한 PDF까지 포함한 작업 전체를 `FAILED`로 만들고 부분 ZIP을 삭제했다.

운영 stack trace에는 현재 접근하지 못했으므로 1번이 해당 4개에서 발생한 정확한 예외였다는 최종 단정은 실계정 재검증 뒤에 한다. 다만 절대 첨부 URL 회귀는 독립 fixture로 재현했고, 2번의 실패 확대 구조는 코드와 성공/실패 비교가 직접 입증한다.

## 해결

- `content_download_uri`는 `URI.resolve` 의미로 정규화한다. 상대 URL은 Commons base에 결합하고 절대 URL은 구성된 Canvas 또는 Commons exact origin일 때만 보존한다. 이 검증은 선택적 크기 `HEAD`와 실제 `GET`보다 먼저 수행한다.
- 기존 `LmsCookieJar`의 exact-origin allowlist도 실제 요청마다 적용한다. URL 복구를 위해 Canvas 쿠키를 임의 host로 재전송하지 않는다.
- metadata 파싱 실패, capability 부재, 명시적인 404/410만 해당 항목에서 제외한다. 하나 이상 성공하면 나머지 자료와 `_ssuAI_export_report.txt`를 포함한 `READY` ZIP을 만든다.
- 보고서는 과목명·파일명과 고정된 안전 사유만 포함한다. content ID, 내부/서명 URL, 쿠키, 예외 메시지는 기록하지 않는다.
- 실제 파일 다운로드의 일시적인 5xx/I/O 계열은 새 임시 파일로 한 번 재시도한다. 첫 시도의 부분 byte가 두 번째 파일에 섞이지 않는다. metadata resolver는 HTTP 계층의 기존 재시도만 사용해 항목 수만큼 중복 증폭하지 않는다.
- 인증 만료, owner revocation, 429, 소진된 외부 장애·5xx와 설정된 byte/file 한도는 즉시 전체 작업을 중단한다. 로컬 디스크·직렬화 같은 예상 밖 오류 역시 항목 실패로 숨기지 않는다.
- 모든 선택 항목이 실패하면 빈 ZIP을 성공으로 제공하지 않고 안전한 전체 실패 메시지를 저장한다.
- current-owner 조건으로 최종 상태가 DB에 반영된 뒤에만 `lms.export.jobs{outcome,reason}`를 기록한다. 같은 시점에 `lms.export.files{outcome=included,reason=none}` 분모와 누락 사유별 counter를 남긴다.

## 검증

- Commons가 상대 다운로드 URI를 반환하면 기존 Commons URL을 만든다.
- 일반 첨부가 절대 Canvas URL을 반환하면 base를 중복 접두하지 않고 그대로 보존한다.
- 정상 1개 + metadata parse 실패 1개는 정상 파일과 안전 보고서를 가진 `READY` ZIP이 된다.
- 전부 parse 실패하면 빈 archive를 게시하지 않고 `FAILED`가 된다.
- 첫 다운로드가 일부 byte를 쓴 뒤 일시 실패해도 두 번째 시도 결과만 ZIP에 들어간다.
- 중간 인증 만료는 이후 항목을 호출하지 않고 인증 필요 메시지로 전체 실패한다.
- metadata나 실제 다운로드의 404/410은 해당 파일만 제외하지만, 400·소진된 503·429는 이후 항목을 호출하지 않고 전체 실패한다.
- stale worker가 최종 상태 저장에 실패하면 성공·부분 성공·실패 metric을 기록하지 않는다.
- 예상 밖 예외의 URL·token 문자열은 사용자 failure reason과 보고서에 포함되지 않는다.

## 남은 위험

- 실제 일반 첨부 4개가 다른 trusted-origin 또는 별도 attachment API를 요구한다면 부분 ZIP은 전체 실패를 막지만 해당 파일 복구에는 추가 resolver가 필요하다. 새 계측과 누락 보고서로 metadata와 download 단계를 구분한 뒤 보완한다.
- 5분을 넘는 멀티포드 build에는 별도의 lease heartbeat와 owner별 `.part` 파일이 필요하다. 이번 비교 작업은 이 시간 경계를 넘지 않았으므로 원인 수정과 분리한다.
- 다운로드 TTL이 확인 시점부터 시작해 긴 build가 사용 시간을 잠식하는 계약은 별도 후속 결정으로 남긴다.

## 예상 면접 질문

- PDF 성공/일반 첨부 실패 비교로 어떤 가설을 제거했는가?
- 개별 외부 파일 오류를 부분 성공으로 바꾸면서 어떤 오류는 계속 fail-closed했는가?
- 절대 URL을 지원하면서 SSRF와 credential scope를 어떻게 유지했는가?
