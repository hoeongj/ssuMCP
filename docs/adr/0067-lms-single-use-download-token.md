# ADR 0067 — LMS export 다운로드 토큰 1회 사용 보장

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-06-30 |
| 상태 | Accepted — 적용 |
| 범위 | `LmsExportController`, `LmsExportJobRepository`, `LmsExportStatus` |
| 연관 문서 | ADR 0033, ADR 0055, `docs/security-followups.md` #5 |

---

## 배경

LMS 강의자료 ZIP 내보내기는 ADR 0033에서 비동기 빌드 + capability URL 방식으로 설계했다. URL에는 원문 토큰이 query string으로 실리고, 서버는 저장된 SHA-256 해시와 constant-time 비교로 검증한다. 토큰 TTL은 짧고 응답에는 `Referrer-Policy: no-referrer`, `Cache-Control: no-store`, `Pragma: no-cache`가 붙어 있었다.

문제는 READY 상태의 다운로드 링크가 TTL 안에서는 반복 재생 가능했다는 점이다. 코드 주석과 사용자 경험은 "one-shot stream"을 의도했지만, 성공 다운로드 뒤 상태를 바꾸는 write-back이 없어서 같은 토큰으로 ZIP 바이너리를 다시 받을 수 있었다. 이는 토큰 탈취 가능성을 TTL과 no-store로 줄이긴 하지만, 성공 다운로드 이후 replay 자체를 차단하지는 못한다.

## 결정

READY 바이너리 다운로드만 `StreamingResponseBody`로 전환하고, 파일 바이트를 응답 `OutputStream`에 끝까지 복사한 뒤 `flush()`까지 예외 없이 완료된 경우에만 토큰을 소비한다.

소비는 DB 단일 UPDATE로 처리한다.

```sql
status = READY인 해당 job만 DOWNLOADED로 변경
```

구현상 repository 메서드는 JPQL bulk update로 `READY -> DOWNLOADED`를 조건부 전이하고, 변경된 row 수를 반환한다. 컨트롤러는 반환값이 0이어도 이미 파일 응답은 완료된 뒤이므로 재시도하지 않고 로그만 남긴다. 이 값은 동시 다운로드에서 어느 요청이 전이를 이겼는지 관측하기 위한 신호다.

`format=json` 폴링 경로와 브라우저용 `text/html` 페이지는 상태 확인만 수행하고 토큰을 소비하지 않는다. READY 상태에서 실제 ZIP 바이너리를 보내는 경로만 소비 대상이다.

## 대안과 기각 이유

### 첫 바이트에서 소비

요청을 받거나 첫 바이트를 쓰기 직전에 `DOWNLOADED`로 바꾸는 방식이다. 단순하지만 다음 문제가 있다.

- 브라우저, 프록시, 보안 제품이 다운로드를 probe하거나 연결을 끊으면 실제 사용자는 파일을 받지 못했는데 링크만 소진될 수 있다.
- 네트워크 단절, 모바일 전환, 탭 종료처럼 스트림 중간 실패가 발생해도 TTL 안 재시도가 불가능해진다.
- Range/probe 류 요청을 별도로 막더라도 "쓰기 시작 후 실패"를 복구하기 어렵다.

그래서 토큰 소비 시점을 "성공적으로 전체 파일 복사가 끝난 뒤"로 늦춘다.

### 별도 consumed 테이블 또는 Redis SETNX

토큰 소비 여부를 별도 저장소에 둘 수 있다. 그러나 `lms_export_jobs.status`가 이미 다운로드 생명주기 상태머신이고, replay 차단은 job 단위 terminal state로 충분하다. 새 저장소를 만들면 만료 정리, 일관성, 테스트 표면만 늘어난다.

### DB row lock 후 스트리밍

READY row를 잠근 채 스트리밍하고 완료 시 소비할 수도 있다. 대용량 ZIP 다운로드 동안 DB transaction과 row lock을 오래 유지하게 되어 장애 표면이 커진다. 이 기능에서 필요한 것은 "완료 후 replay 차단"이지 "동시 최초 스트림 하나만 허용"이 아니므로, 짧은 조건부 UPDATE가 더 낫다.

## 마이그레이션이 없는 이유

`lms_export_jobs.status`는 `VARCHAR(16)`이고 CHECK 제약이 없다. ADR 0055와 `docs/security-followups.md` #3에서 정리한 것처럼, enum 문자열 컬럼에 CHECK 제약을 뒤늦게 넣으면 향후 정상 enum 추가가 배포를 깨는 잠복 회귀가 될 수 있다.

새 값 `DOWNLOADED`는 10자라 현재 컬럼 길이에 들어간다. JPA는 `EnumType.STRING`으로 저장하므로 새 enum 문자열은 스키마 변경 없이 영속된다. 따라서 Flyway 마이그레이션은 추가하지 않는다.

## 동작 방식

1. 요청의 job id를 조회하고, query token을 SHA-256으로 해시한 뒤 저장된 token hash와 constant-time 비교한다.
2. HTML 페이지 요청이면 기존처럼 페이지를 반환하고 소비하지 않는다. 페이지는 이후 `format=json`으로 상태를 폴링한다.
3. JSON 상태 요청 또는 바이너리 다운로드 요청에서 상태가 `DOWNLOADED`이면 `410 Gone`과 `{"status":"DOWNLOADED","message":"이미 다운로드된 1회용 링크입니다. 다시 내보내기 해주세요."}`를 반환한다.
4. 만료, 빌드 중, 실패, 명시적 EXPIRED 상태는 기존처럼 JSON 상태를 반환한다.
5. READY 상태에서 `format=json`이면 readiness JSON만 반환하고 소비하지 않는다.
6. READY 바이너리 다운로드이면 기존 path traversal guard(`isInsideExportBase`)를 통과한 파일만 스트리밍한다.
7. `Files.copy(file, responseOutputStream)`와 `flush()`가 예외 없이 끝난 뒤에만 `markDownloaded(jobId, now)`를 호출한다.
8. 복사 또는 flush 중 예외가 나면 repository update는 호출되지 않는다. 사용자는 TTL 안에서 다시 시도할 수 있다.

## 동시성

동시 요청 두 개가 같은 READY 토큰으로 거의 동시에 바이너리 다운로드를 시작하면 둘 다 파일을 받을 수 있다. 그러나 완료 후 `where status = READY` 조건부 UPDATE를 먼저 실행한 요청 하나만 `DOWNLOADED` 전이에 성공한다. 늦게 끝난 요청은 affected row 0을 받는다.

이는 허용 가능한 잔여다. 이번 변경의 목표는 "성공 다운로드 이후 같은 토큰으로 나중에 replay"하는 경로 차단이다. 동시 in-flight 스트림 하나까지 강제로 막으려면 스트리밍 동안 lease/lock을 잡아야 하고, 그 비용은 현재 threat 대비 과하다.

## 잔여 위험과 완화

기존 완화는 그대로 유지된다.

- 토큰 TTL은 짧다.
- URL 토큰은 SHA-256 해시로만 저장된다.
- 응답은 no-referrer/no-store/no-cache 헤더를 유지한다.
- path traversal guard가 export base 밖 파일 제공을 차단한다.

이번 변경은 여기에 성공 다운로드 후 replay 차단을 추가한다. 남는 위험은 같은 토큰으로 동시에 시작한 매우 드문 in-flight 중복 스트림뿐이며, post-download replay 위협은 `DOWNLOADED` terminal state로 닫힌다.

## 검증

- 첫 바이너리 다운로드가 ZIP 바이트를 반환하고 job을 `DOWNLOADED`로 전이한다.
- `DOWNLOADED` job의 두 번째 바이너리 요청은 `410 Gone`을 반환한다.
- READY job의 `format=json` 폴링은 상태만 반환하고 소비하지 않으며, 이후 바이너리 다운로드가 정상 동작한다.
- 스트림 복사 중 예외가 발생하면 `markDownloaded`를 호출하지 않는다.

## 예상 면접 질문

1. 왜 토큰을 첫 바이트가 아니라 전체 복사 완료 후 소비했나?
2. `READY -> DOWNLOADED`를 load/save가 아니라 조건부 UPDATE로 처리한 이유는?
3. 새 enum 상태를 추가하면서 DB 마이그레이션을 넣지 않은 근거는?
4. 동시 in-flight 다운로드가 둘 다 파일을 받을 수 있는 잔여를 왜 수용했나?
