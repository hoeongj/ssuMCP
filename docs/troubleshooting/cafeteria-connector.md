# 학식 + 기숙사 식단 connector 트러블슈팅 회상

ssuAI 의 학식 / 기숙사 식단 connector 작업에서 마주친 문제들과 해결
과정을 commit history 기준으로 retroactive 정리한 글이다. 사용자 기억이
흐려진 상태라 일부는 git diff 기반 추정이며 추정 부분은 본문에 명시했다.

작업 시점: 2026-05-XX ~ 2026-05-07 (`f54ba70` ~ `ab742a8`).

---

## 사건 1 — 학식 사이트 파싱 첫 구현 (`f54ba70`)

### 배경

`f54ba70` 은 mock 학식 API 에서 실제 학식 사이트 connector 로 넘어간 첫
큰 commit 이다. `RealMealConnector`, `MealConnector`, `MealService`,
`WeeklyMealExportRunner`, connector 예외 계층, fixture 기반 parse / HTTP
테스트가 한 번에 들어왔다.

학식 사이트는 `https://soongguri.com/m/m_req/m_menu.php` 를 사용했고,
식당 코드는 query parameter `rcd`, 날짜는 `sdt` 로 전달했다. 테스트가
고정한 실제 요청 path 는 다음 형태였다.

```text
/m/m_req/m_menu.php?rcd=1&sdt=20260506
```

즉 URL 구조는 `m_req/m_menu.php?rcd=&sdt=` 이며, 날짜는 `yyyyMMdd`
(`DateTimeFormatter.BASIC_ISO_DATE`) 로 보낸다. 식당 코드는 학생식당 `1`,
숭실도담식당 `2`, 스낵코너 `4`, 푸드코트 `5`, `THE KITCHEN` `6`,
`FACULTY LOUNGE` `7` 로 hard-coded list 에 들어갔다.

HTML 구조는 `td.menu_nm` 이 코너 이름, 그 다음 sibling 인 `td.menu_list`
가 메뉴 본문이었다. 코너 라벨은 `조식`, `중식`, `석식` prefix 로
`MealType.BREAKFAST`, `MealType.LUNCH`, `MealType.DINNER` 에 매핑했다.
`중식1`, `중식2`, `석식1` 같은 상세 코너명은 `MealItem.corner` 에 그대로
남겼다.

fixture 를 보면 메뉴 본문은 단순 list 가 아니었다. `font`, `b`, `div`,
`ul.mean_list`, nested `table`, `<br>` 가 섞이고, 가격 suffix (`- 5.0`,
`- 1.0`), `★` marker, `[Hot Pot]` 같은 카테고리, 알러지 / 원산지 문단이
한 셀 안에 같이 들어왔다. 숭실도담식당 fixture 는 `매실우불고기,
꽈리감자조림- 6.0` 처럼 쉼표로 묶인 메뉴명도 포함했다.

### 막혔던 / 발견한 점

첫 구현에서 가장 까다로운 부분은 "메뉴처럼 보이지 않는 텍스트를 버리는
것" 이었다. `RealMealConnector.extractMenu()` 는 `menuCell.getAllElements()`
를 순회하면서 각 element 의 `wholeOwnText()` 를 읽고, `*알러지유발식품`,
`*원산지` marker 를 만나면 이후 metadata 를 끊었다. 이 패턴은 알러지와
원산지가 메뉴 본문 뒤에 같은 cell 로 따라붙는 구조 때문에 들어간 것으로
보인다.

또 하나의 함정은 줄바꿈 방식이었다. 어떤 메뉴는 `<li>` 로 오고, 어떤
메뉴는 nested table 안의 `<br>` 로 온다. commit 은 raw text 를 `\\R+` 로
쪼개고, 쉼표가 있는 경우 한 번 더 split 했다. `(추정 — git diff 기반)`
사이트가 식당 / 코너마다 HTML 작성 방식이 달라서 selector 하나로 row 를
잡은 뒤 내부 text cleanup 을 강하게 하는 방향을 택한 것으로 보인다.

휴무도 별도 문제였다. 어린이날 fixture 와 `오늘은 쉽니다.` 테스트가 같이
들어왔다. 메뉴가 비어 있을 때 `td[colspan=2]` 나 `td.menu_list` 를 훑어
`쉽니다`, `휴무`, `어린이날`, `공휴일`, `운영하지`, `운영 안` 같은 keyword
를 찾고 `MealClosure` 로 반환했다.

### 결정

파싱 전략은 "넓게 잡고, 메뉴 token 으로 강하게 정제" 하는 방식으로
정착했다. `td.menu_nm` / sibling `td.menu_list` 로 코너 row 를 잡고,
`stripMetadata`, `cleanMenuToken`, `splitMenuToken`, `shouldKeepMenuToken`
을 거쳐 실제 메뉴명만 남겼다.

외부 호출에는 `User-Agent: ssuAI/0.1 (+akftjdwn@gmail.com)` 와
`Accept-Language: ko-KR,ko;q=0.9` 를 명시했다. connector 내부에는
`DEFAULT_MIN_INTERVAL_MS = 1_000L` 를 두어 사이트 매너를 지키려 했다.

### 남긴 흔적

이 commit 에서 `MealResponse` 는 `meals` 와 `closures` 를 함께 갖는
형태가 됐다. 정상 메뉴와 휴무 사유를 같은 응답 envelope 안에서 구조적으로
분리하는 패턴은 이후 부분 실패 처리까지 확장됐다.

또한 connector 예외 계층 (`ConnectorTimeoutException`,
`ConnectorUnavailableException`, `ConnectorParseException`) 이 만들어졌다.
이후 REST error envelope, MCP error UX, logging 개선은 모두 이 예외 계층을
기반으로 이어졌다.

---

## 사건 2 — Windows 에서만 깨지는 parse 테스트 (`19aaf4d`)

### 배경

`19aaf4d` 는 `RealMealConnectorHttpTests.fetchMealThrowsParseExceptionForEmptyHtml`
하나만 고친 commit 이다. commit message 가 원인을 비교적 명확하게 남겼다.
Windows 에서 `MockWebServer` cold-start cost 와 6회 sequential request 가
합쳐져 1초 read timeout 을 넘었고, 테스트가 기대한 `ConnectorParseException`
대신 `ConnectorTimeoutException` 이 발생했다.

처음에는 fixture encoding, CRLF vs LF, locale 같은 Windows 전용 문제를
의심하기 쉬운 상황이었다. 그러나 실제 diff 를 보면 파일 내용이나 parsing
assertion 은 바뀌지 않았다. 바뀐 것은 timeout 과 artificial delay 뿐이다.

### 시도 / 발견

기존 테스트는 empty HTML 응답 6개를 enqueue 하고, connector timeout 을
1,000ms 로 둔 상태에서 `fetchMeal` 을 호출했다. 당시 connector 는 6개
식당을 내부 loop 로 sequential 조회했기 때문에, 이 테스트도 6번의 HTTP
round-trip 을 수행했다.

empty response helper 는 각 응답에 `setBodyDelay(1, TimeUnit.MILLISECONDS)`
도 붙이고 있었다. 1ms delay 자체는 작지만, Windows 환경에서 MockWebServer
시작과 요청 처리 비용이 겹치면 parse failure 에 도달하기 전에 timeout 이
먼저 발생할 수 있었다.

### 결정

parse exception 을 검증하는 테스트의 timeout 을 1초에서 5초로 늘렸다.
timeout 동작 자체는 별도 테스트 `fetchMealThrowsTimeoutWhenServerDoesNotRespond`
가 담당하므로, empty HTML parse test 가 timeout boundary 까지 같이 검증할
필요는 없었다.

또한 empty response 에 붙어 있던 `setBodyDelay(1, TimeUnit.MILLISECONDS)`
를 제거했다. parse 실패를 유도하는 데 의미 없는 delay 였고, OS dependent
flakiness 를 키우는 noise 였다.

### 교훈

flaky test 의 핵심은 "테스트 이름이 검증하려는 실패 모드와 실제로 먼저
발생하는 실패 모드가 다를 수 있다" 는 점이었다. 이 사건은
`ConnectorParseException` 을 검증하는 테스트가 timeout 조건에 너무 가까이
붙어 있으면 OS / machine 속도에 따라 다른 예외가 먼저 나온다는 것을
보여준다.

남은 패턴은 실패 모드별 테스트 분리다. timeout 은 timeout 전용 테스트에서,
parse failure 는 충분한 timeout 을 둔 상태에서 검증한다.

---

## 사건 3 — Connector 예외 stacktrace 가 로그에서 사라지던 문제 (`37d9015`)

### 배경

`37d9015` 는 `GlobalExceptionHandler` 의 connector exception handler 들만
수정했다. 기존 로그는 다음 형태였다.

```java
log.warn("Connector exception occurred: exceptionType={}", exception.getClass().getName());
```

이 로그는 exception class 이름만 남긴다. user-facing error envelope 은
정상적으로 내려가지만, 서버 로그에는 cause stacktrace 가 남지 않는다. 실제
외부 사이트 timeout, HTTP error, parse error 가 발생했을 때 사후 분석할
정보가 부족해진다.

### 막혔던 / 발견한 점

`ConnectorException` 계층은 이미 cause 를 보존하도록 설계되어 있었다.
예를 들어 `ConnectorTimeoutException(Throwable cause)` 는 원래
`SocketTimeoutException` 이나 `IOException` 을 cause 로 받는다. 문제는
예외 객체에 cause 가 들어 있어도 logging call 이 throwable 을 넘기지
않으면 stacktrace 가 출력되지 않는다는 점이었다.

`ConnectorUnavailableException` 과 `ConnectorParseException` 도 마찬가지다.
HTTP status, selector parse failure, IO failure 를 실제 원인으로 품고
있어도 handler 로그가 class name 만 찍으면 운영자는 "어떤 URL / 어떤
stack 에서 실패했는지" 를 알 수 없다. `(추정 — git diff 기반)` 실제
디버깅 중 error envelope 은 보이는데 서버 로그가 원인을 설명하지 못하는
상황을 겪고 이 commit 이 나온 것으로 보인다.

### 결정

SLF4J 의 마지막 argument 로 throwable 을 넘기는 패턴으로 바꿨다.

```java
log.warn("Connector exception: code={} type={}",
        ErrorCode.CONNECTOR_TIMEOUT.name(), exception.getClass().getSimpleName(), exception);
```

이 변경은 user-facing response 를 바꾸지 않았다. `ErrorCode` 와 HTTP status
mapping 은 그대로 두고, server-side observability 만 보강했다.

### 남긴 흔적

이후 connector exception handler 들은 `code`, `type`, throwable 을 함께
남기는 형태가 됐다. REST client 에게는 안정된 error envelope 을 주고,
운영자에게는 stacktrace 를 주는 분리가 명확해졌다.

이 패턴은 나중에 MCP error UX 를 별도로 다룰 때도 중요한 전제가 됐다.
사용자에게 보이는 메시지를 친절하게 다듬더라도, 서버 로그에서는 원인
stacktrace 를 보존해야 한다.

---

## 사건 4 — Connector 실패 로그에 식당/날짜 컨텍스트 부족 (`77e141c`)

### 배경

`77e141c` 는 `RealMealConnector` 의 failure log 에 restaurant 과 date 를
추가했다. commit message 가 문제를 직접 설명한다. 당시 `fetchMeal` 은
한 호출 안에서 6개 식당을 순차 조회하고 있었는데, 실패 로그에는 `reason`
과 `ms` 만 있었다.

```text
connector=meal status=fail reason={} ms={}
```

이 형태로는 timeout 이나 503 이 어느 식당의 어느 날짜에서 발생했는지
알 수 없다. 6개 식당 fan-out 중 하나만 실패해도 로그만 보고는 재현 URL 을
만들기 어렵다.

### 막혔던 / 발견한 점

다중 외부 호출 환경에서는 "connector=meal" 만으로 충분하지 않다.
`RealMealConnector` 는 식당 코드와 날짜로 URL 을 만들기 때문에, 문제를
재현하려면 최소한 restaurant 과 date 가 필요하다. 당시 로그에는 둘 다
없었고, elapsed time 만 있었다.

성공 로그도 마찬가지로 date 가 없었다. 성공 로그는 실패 분석보다 덜
급하지만, 날짜가 있어야 특정 export run 이 어떤 날짜 데이터를 가져왔는지
나중에 맞춰볼 수 있다.

### 결정

loop 안에서 현재 처리 중인 식당 이름을 `currentRestaurant` 로 추적하고,
모든 catch branch 의 `logFailure` 호출에 restaurant 과 date 를 넘겼다.

```text
connector=meal status=fail restaurant={} date={} reason={} ms={}
```

성공 로그도 `date={}` 를 포함하도록 바꿨다. 실패 로그만 강화하면 성공과
실패의 비교가 어색해지므로, 같은 축의 context 를 성공 로그에도 넣은
결정이다.

### 남긴 흔적

이후 connector / service logging 에서는 "어떤 external call 이 실패했는가"
를 나타내는 context 를 같이 남기는 방향이 자리잡았다. 특히 6개 식당 fan-out
처럼 같은 connector 가 여러 downstream call 을 수행할 때, log context 는
선택이 아니라 필수에 가깝다는 교훈을 남겼다.

`currentRestaurant = "?"` 초기값도 흔적으로 남았다. loop 진입 전 예외가
나더라도 log format 을 깨뜨리지 않기 위한 방어적 기본값이다.

---

## 사건 5 — Connector 책임 비대 → 단일 식당 단위로 분리 (`25c6827`)

### 배경

`25c6827` 은 학식 connector 구조를 크게 바꾼 refactor 다. 이전
`RealMealConnector.fetchMeal(LocalDate date)` 는 내부에 6개 식당 list 를
hard-code 하고, 한 method 안에서 전체 fan-out, HTTP 호출, parse, 휴무 처리,
예외 mapping, logging 을 모두 수행했다.

이 구조에서는 한 식당의 503, timeout, parse error 가 전체 `fetchMeal` 의
5xx 로 올라갔다. commit message 는 "clients saw zero menus" 라고 설명한다.
즉 학생식당 1곳만 실패해도 나머지 5개 식당 메뉴를 버리게 되는 구조였다.

### 막혔던 / 발견한 점

책임이 섞이면서 테스트 경계도 흐려졌다. connector test 는 실제로는
"한 식당 HTML parse" 와 "6개 식당 fan-out" 과 "부분 실패 정책" 을 함께
검증해야 했다. parse selector 를 고치려 해도 fan-out 테스트가 같이 영향을
받고, 부분 실패 정책을 바꾸려 해도 connector 내부 loop 를 이해해야 했다.

또한 이후 성능 개선을 어렵게 만드는 구조였다. rate-limit 과 fan-out 이
같은 connector method 안에 묶여 있으면, service layer 에서 병렬화하거나
부분 실패를 domain response 로 흡수하기 어렵다. `(추정 — git diff 기반)`
이 refactor 가 뒤의 per-rcd rate-limit / parallel fan-out 결정의 기반이
된 것으로 보인다.

### 결정

`MealConnector` contract 를 단일 식당 조회로 바꿨다.

```java
MealResponse fetchMeal(LocalDate date, MealRestaurant restaurant);
```

`MealRestaurant` enum 을 새로 만들고, 식당 code 와 display name 을 enum 에
올렸다. `RealMealConnector` 는 이제 받은 restaurant 하나의 URL 만 호출하고
그 결과만 parse 한다. `MockMealConnector` 도 같은 contract 를 따른다.

fan-out 은 `MealService` 로 올라갔다. `MealService.getMeal` 이
`MealRestaurant.values()` 를 순회하며 connector 를 호출하고, 식당별
`ConnectorException` 은 `MealClosure(restaurant, "조회 실패: <ErrorCode>")`
로 흡수한다. 단 모든 식당이 실패하면 마지막 예외를 다시 throw 해 운영자에게
5xx 신호가 가도록 했다.

### 남긴 흔적

이 사건 이후 connector 는 "외부 시스템 한 단위 호출" 을 책임지고, service 는
"도메인 fan-out 과 부분 실패 정책" 을 책임지는 구조가 됐다.

`MealConnector` 의 `(date, restaurant)` signature 와 `MealRestaurant` enum 은
이후 codebase 의 안정된 경계가 됐다. ADR 0001 도 이 commit 에서 update 되어
`closures` 가 실제 휴무뿐 아니라 "조회 실패한 식당" 을 표현할 수 있음을
명시했다.

---

## 사건 6 — 기숙사 사이트는 다른 세계 (`0d181e2`)

### 배경

`0d181e2` 는 기숙사 식단 connector 를 추가했다. 학식 사이트와 같은 학교
식단 도메인이지만, 실제 사이트 특성은 완전히 달랐다. 학식은
`soongguri.com` 의 식당별 / 날짜별 menu endpoint 였고, 기숙사는
`https://ssudorm.ssu.ac.kr:444/SShostel/mall_main.php?viewform=B0001_foodboard_list&board_no=1`
한 페이지에서 주간 표를 가져오는 구조였다.

`RealDormMealConnector` 는 `CHARSET = "EUC-KR"` 를 명시했다. HTTP response 를
`response.bodyAsBytes()` 로 받은 뒤 `ByteArrayInputStream` 으로 감싸고,
`Jsoup.parse(stream, CHARSET, pageUrl)` 로 parsing 했다. HTTP test 도 fixture
를 UTF-8 로 읽은 뒤 EUC-KR bytes 로 바꿔 MockWebServer 에 enqueue 하고,
한글이 깨지지 않는지 확인했다.

### 막혔던 / 발견한 점

기숙사 페이지는 학식처럼 `td.menu_nm` / `td.menu_list` 가 아니었다.
selector 는 `table.boxstyle02 tbody tr` 이고, 각 row 의 `th` 에서 날짜를
정규식 `(\\d{4}-\\d{2}-\\d{2})` 로 뽑았다. `td` cell 은 column index 로
조식, 중식, 석식에 매핑했다.

메뉴 cell 도 `<br>` 기반이었다. connector 는 cell HTML 의 `<br>` 을 newline
으로 바꾼 뒤 `wholeText()` 로 읽고, 줄 단위로 split 했다. fixture test 는
월요일 중식 / 석식 메뉴 순서가 보존되는지 확인한다.

휴무 표현도 column 단위였다. fixture 에서는 조식이 매일 `미운영` 으로
나오며, parser 는 이를 `MealClosure(RESTAURANT, "조식 미운영")` 로 기록한다.
별도 test 는 `공휴일 미운영`, `어린이날 휴무`, `운영하지 않습니다` 를 각각
조식 / 중식 / 석식 closure 로 처리하는지 검증했다.

### 결정

기숙사는 학식 connector 에 억지로 합치지 않고 별도 `DormMealConnector`
interface 로 분리했다. contract 도 학식의 daily / restaurant 단위가 아니라
`WeeklyMealResponse fetchThisWeekMeal()` 로 잡았다. 한 번의 외부 호출이
이미 7일 표를 반환하기 때문이다.

REST 도 `/api/dorm/meals/this-week` 로 별도 controller 를 두고,
`DormMealService` 가 connector 를 감쌌다. DTO 는 기존 meal DTO 인
`MealResponse`, `MealItem`, `MealClosure`, `WeeklyMealResponse` 를 재사용해
응답 모델은 공유했다.

### 교훈

같은 학교 식단이라도 사이트별 구현은 완전히 다른 세계일 수 있다. encoding,
port, URL shape, table selector, 날짜 위치, 휴무 표현이 모두 달랐다.

이 사건은 connector pattern 의 도메인별 분리 가치가 드러난 사례다. 공통
추상화를 서둘러 만들었다면 EUC-KR weekly table 과 soongguri daily endpoint
를 하나의 모양에 끼워 맞추느라 오히려 코드가 복잡해졌을 가능성이 크다.

---

## 사건 7 — Export runner 가 dev 에서 실수로 도는 위험 (`ab742a8`)

### 배경

`ab742a8` 은 `WeeklyMealExportRunner` 에 `@Profile("export")` 를 추가하고
`application-export.yml` 을 만든 commit 이다. 기존 runner 는
`ApplicationRunner` 로 등록되어 있었고,
`@ConditionalOnProperty(name = "ssuai.meal.export.enabled", havingValue = "true")`
만 gate 로 사용했다.

문제는 runner 가 JSON 을 쓴 뒤 `SpringApplication.exit()` 를 호출한다는
점이었다. one-shot batch container 라면 정상 동작이지만, dev 또는 prod API
server 에서 실수로 `ssuai.meal.export.enabled=true` 만 켜지면 서버가 외부
사이트를 hit 한 뒤 조용히 종료될 수 있었다.

### 막혔던 / 발견한 점

배치성 component 는 Spring bean 으로 등록되는 순간 실행 위험이 생긴다.
특히 `ApplicationRunner` 는 application startup 에 자동 실행되므로, 단순
`bootRun` 이나 API server 실행과 batch 실행을 같은 runtime 에 두면 gate 가
명확해야 한다.

`enabled=true` property 하나만으로는 실수 방지 장치가 약했다. 환경 파일이나
command line arg 를 잘못 합치면 API server runtime 에서도 조건이 맞을 수
있다. `(추정 — git diff 기반)` 실제로 export runner 를 반복 실행하며
bootRun 과 batch run 의 경계가 헷갈릴 위험을 발견한 것으로 보인다.

### 결정

두 겹 gate 로 바꿨다.

```java
@Profile("export")
@ConditionalOnProperty(name = "ssuai.meal.export.enabled", havingValue = "true")
```

즉 `export` profile 이 active 이고, enabled flag 도 true 일 때만 runner 가
동작한다. 파일 주석에도 "API server 에서 켜지면 안 되는 one-shot runner" 라는
의도를 남겼다.

또한 `application-export.yml` 을 새로 만들었다. 여기에는 real connector 설정,
`ssuai.meal.export.enabled=true`, optional `output`, `start-date` 예시가
담겼다. 실행자는 개별 property 를 기억하기보다
`--spring.profiles.active=export` 를 명시해 batch mode 로 들어간다.

### 교훈

외부 시스템을 hit 하고 process 종료까지 수행하는 component 는 일반 API
server 와 같은 조건으로 등록되면 위험하다. profile 과 property 를 함께
요구하는 정도의 friction 은 번거로움이 아니라 안전장치다.

이 사건 이후 export runner 는 "명시적으로 export mode 로 들어갔을 때만
실행되는 one-shot batch" 라는 성격이 분명해졌다.

---

## 회고

세 가지 큰 줄기가 남았다.

1. **사이트별 다른 세계**: 학식과 기숙사가 같은 학교 시스템이라도 인코딩,
   응답 구조, URL shape, 운영 패턴이 완전히 다르다. connector 패턴의 도메인
   분리 가치를 직접 체감한 흐름이었다.
2. **로그 / 예외의 디버깅 가능성**: 6 식당 fan-out 같은 다중 호출 환경에서는
   어느 호출이 실패했는지 추적할 수 있는 컨텍스트가 처음부터 들어가야 한다.
   stacktrace 와 restaurant / date context 는 사후 분석의 최소 조건이다.
3. **배치성 컴포넌트의 명시적 게이트**: 외부 시스템을 hit 하는 자동 실행
   컴포넌트는 profile + condition 두 겹 정도가 안전하다. 실행 편의보다
   의도치 않은 실행을 막는 것이 우선이다.

이 글은 commit history 만 보고 재구성한 회상이다. 특히 "왜 이 결정을 했는가"
부분은 commit message 와 diff 에서 합리적으로 추론한 내용이므로, 실제 작업
기억과 다른 부분은 이후 보강 대상이다.
