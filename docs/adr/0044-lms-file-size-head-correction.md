# ADR 0044 — LMS 파일 크기 HEAD 보정

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-19 |
| 상태 | Accepted |
| 범위 | `RealLmsMaterialsConnector`, `get_my_lms_courses`, `get_my_lms_materials`, LMS 자료 내보내기 예상 용량 |
| 연관 ADR | [ADR 0033](0033-lms-material-zip-export.md) (LMS 비영상 자료 ZIP 내보내기) |

---

## 배경과 운영 증거

LMS 자료 목록은 LearningX modules 응답의 `item_content_data.total_file_size`를 각 파일의
`sizeBytes`로 사용했다. 운영 세션에서 네트워크프로그래밍 과목(id `45066`)을 확인한 결과 이
필드는 콘텐츠 유형에 따라 신뢰도가 달랐다.

- `contentType: "pdf"`: `399826`, `11967802`, `20220183`처럼 대부분 파일별로 다른 현실적인 값
- PDF 예외: `01 Introduction to Client-Server Networking (Part 2).pdf`는 `0`
- `contentType: "file"`: 서로 다른 ZIP 4개가 전부 정확히 `64238`

독립적인 ZIP 4개가 byte 단위까지 같은 것은 실제 크기라 보기 어렵다. `64238`은 LMS가 비-PDF
항목에 넣는 센티널이고 `0`은 미채움 값이라는 운영 증거가 확보됐다. 따라서 JSON 구조나 파싱
노드가 틀린 문제가 아니라, 외부 API가 형식상 유효하지만 의미상 잘못된 값을 주는 문제다.

잘못된 `sizeBytes`는 자료 목록뿐 아니라 과목 `totalBytes`, 선택 내보내기의 예상 용량과 제한
판정까지 전파된다. 반대로 크기를 알 수 없을 때 센티널을 유지하면 사용자에게 거짓 정밀도를
제공한다.

---

## 조사 근거

HTTP Semantics RFC 9110 §9.3.2는 HEAD를 GET과 같은 메타데이터를 얻되 응답 콘텐츠를 전송하지
않는 메서드로 정의한다. 다만 서버가 동적으로 계산하는 `Content-Length` 같은 필드는 HEAD에서
생략할 수 있다고 명시한다. 따라서 HEAD 성공 시 `Content-Length`를 실제 크기로 쓰고, 헤더가
없으면 unknown으로 두는 정책이 표준 의미와 맞다.

Java 21 `HttpRequest.Builder.method(...)`는 `HEAD`와 no-body publisher를 구성할 수 있고,
`HttpHeaders.firstValueAsLong("Content-Length")`는 헤더 부재를 `OptionalLong.empty()`로
표현한다. 별도 HTTP 라이브러리나 전체 응답 body 버퍼링이 필요 없다.

출처:

- RFC 9110 §9.3.2 HEAD — https://www.rfc-editor.org/rfc/rfc9110.html#name-head
- Java SE 21 `HttpRequest.Builder.method` — https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html#method(java.lang.String,java.net.http.HttpRequest.BodyPublisher)
- Java SE 21 `HttpHeaders.firstValueAsLong` — https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpHeaders.html#firstValueAsLong(java.lang.String)

---

## 결정

API 값이 신뢰 가능한 **양수 PDF**만 그대로 사용한다. 다음 항목은 비신뢰값으로 분류해 기존
Commons 다운로드 URL 해석 후 인증된 HEAD `Content-Length`로 보정한다.

- `contentType != "pdf"`: 양수여도 `64238` 같은 센티널 가능성이 있으므로 보정
- `sizeBytes == null` 또는 `sizeBytes <= 0`: PDF를 포함해 미채움/무효값이므로 보정

HEAD가 2xx가 아니거나, 요청이 실패하거나, `Content-Length`가 없으면 `sizeBytes = null`로
반환한다. 원래 API의 `0` 또는 양수 센티널로 되돌아가지 않는다. `totalBytes`는 기존 서비스가
보정된 자료 목록의 non-null 크기만 합산하므로 자동으로 다시 계산된다.

비용은 비신뢰 항목으로 한정한다. 양수 PDF는 추가 네트워크 요청이 없다. 비신뢰 항목은 파일마다
기존 `contentId -> download URL` metadata GET 1회와 HEAD 1회가 필요하다. 커넥터는 한 응답의
`attempted`, `corrected`, `unknown` 수를 단일 로그로 남겨 운영 비용과 성공률을 관측한다.

---

## 기각한 대안

| 대안 | 판정 | 기각 이유 |
|---|---|---|
| `total_file_size`를 계속 신뢰 | 기각 | 운영에서 서로 다른 ZIP 4개가 모두 `64238`, PDF 1개가 `0`인 반증이 있음. 목록과 용량 제한에 거짓값 전파 |
| 같은 크기가 2개 이상 반복될 때만 센티널 판정 | 기각 | 단일 비-PDF 파일만 있는 응답에서는 센티널을 발견할 수 없음. 운영 증거상 `contentType != pdf`가 더 단순하고 안전한 경계 |
| 파일 전체를 GET으로 내려받아 byte 수 계산 | 기각 | 목록 조회만으로 대용량 파일을 모두 전송해 지연·대역폭·메모리/디스크 비용이 과도함 |
| 실패 시 원래 API 값을 fallback | 기각 | unknown을 거짓 정밀도로 바꾸며 이번 장애를 다시 노출함. 집계는 known 크기만 포함하는 편이 정확함 |

---

## 동작 방식

1. LearningX modules JSON을 기존처럼 `LmsMaterial` 목록으로 파싱한다.
2. 양수 PDF는 즉시 유지한다.
3. 그 외 항목은 기존 Commons metadata URL에 같은 LMS 쿠키로 GET해 실제 다운로드 URL을 얻는다.
4. `LmsMaterialSizeResolver`가 fetch에 사용한 인증 `HttpClient`와 같은 쿠키 헤더로 다운로드 URL에 HEAD를 보낸다.
5. 2xx + `Content-Length`이면 해당 값을 `sizeBytes`로 교체한다.
6. URL 해석/HEAD/헤더 중 하나라도 실패하면 `sizeBytes`를 `null`로 교체한다.
7. `LmsMaterialsService`와 `LmsMaterialExportService`가 보정된 목록을 그룹화하고 non-null 값으로 `totalBytes`를 다시 계산한다.

크기 확인 경계를 인터페이스로 분리해 커넥터 단위 테스트는 실제 네트워크 없이 성공/실패를 결정할
수 있다. HEAD 구현 자체는 MockWebServer로 메서드와 쿠키 전달, `Content-Length` 해석을 검증한다.

---

## 검증

- 양수 PDF: API 크기 유지, resolver 호출 없음
- `file` + `0`: mocked HEAD `Content-Length`로 교체
- `file` + `64238`: HEAD 실패 시 `null`, 센티널 유지 금지
- HEAD 구현: `HEAD` 메서드와 LMS `Cookie` 헤더 전달, `Content-Length` 반환
- HEAD 비-2xx: empty 반환
- 전체 Gradle 테스트 스위트로 기존 다운로드·집계·MCP 동작 회귀 확인

---

## 포트폴리오 가치와 한계

이 결정은 외부 API 문서의 명목상 필드보다 운영 데이터의 불변 패턴을 근거로 데이터 신뢰 경계를
세운 사례다. 표준 HTTP 메타데이터 메서드로 대역폭을 제한하고, 실패 시 unknown을 명시하며,
보정 성공률을 수치로 관측할 수 있다.

한계는 비-PDF가 많은 과목에서 metadata GET + HEAD fan-out이 늘어난다는 점이다. 현재는 사용자
요청 흐름 안에서 순차 실행한다. 운영 로그에서 지연이 문제가 되면 bounded concurrency 또는
짧은 TTL의 `(contentId, download URL) -> size` 캐시를 별도 ADR로 검토한다.
