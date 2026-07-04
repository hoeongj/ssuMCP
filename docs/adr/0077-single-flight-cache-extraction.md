# ADR 0077 — 5개 읽기 캐시의 single-flight 스켈레톤을 제네릭으로 추출

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-07-04 |
| 상태 | Accepted — 구현·머지 |
| 범위 | `global/cache/SingleFlightCache<K,V>` 신설 + `LibrarySeatCache`·`LibraryRoomSeatCache`·`LibraryBookCache`·`SaintScheduleCache`·`NoticeListCache` 위임 전환 |
| 연관 문서 | `docs/architecture.md`(캐시 계층), `docs/performance/`(캐시-스탬피드 방어) |

---

## 배경

챗봇은 한 사용자가 같은 질문("빈 좌석?", "내일 1교시?")을 짧은 간격으로 반복하고, 여러 사용자가 동시에 같은 리소스(같은 층 좌석, 같은 공지 목록)를 조회한다. 이를 그대로 상류(Pyxis/u-SAINT/공지)에 흘리면 캐시-스탬피드가 난다. 방어책으로 5개 읽기 캐시가 **동일한 single-flight + TTL 미스 경로**를 각자 복붙해 갖고 있었다:

1. 신선도 검사 → 있으면 반환,
2. `inflight.putIfAbsent(key, mine)`로 in-flight future 등록,
3. 경쟁에서 이긴 스레드가 **다시 한 번** 신선도 재검사(double-check),
4. 로더 1회 실행 → `entries.put` → `mine.complete`,
5. 실패 시 `completeExceptionally` + 재던짐, `finally`에서 in-flight 제거,
6. 진 스레드는 `winner.get()` 대기 — `InterruptedException`(인터럽트 복원)·`ExecutionException`(RuntimeException 언랩) 처리.

이 블록은 **틀리기 쉬운 동시성 코드**다: 실패를 캐싱해 캐시를 오염시키거나, in-flight 엔트리를 누수하거나, 인터럽트를 삼키는 실수가 5곳에 각각 잠복할 수 있다. 5벌을 따로 관리하는 한, 한 곳을 고쳐도 나머지 넷은 그대로다.

## 검토한 대안

### 그대로 둔다(복붙 유지) ❌
동작은 하지만 동시성 불변식이 5곳에 분산돼, 버그 수정·개선(예: 로더 타임아웃, 지표 추가)이 5배 비용이고 드리프트가 필연이다. 면접에서 "이 미스 경로가 왜 5번 반복되나?"에 답할 수 없다.

### Caffeine 등 캐시 라이브러리 도입 ❌
Caffeine은 single-flight(`get(key, loader)` 동기화)를 기본 제공하지만, ① room-seat의 **Redis L2 read-through/write-behind + L2 히트를 half-TTL로 L1 저장**하는 특수 로직, ② 주입 가능한 `Clock` 기반 결정론적 만료(현 테스트가 `MutableClock`으로 TTL을 넘긴다)를 그대로 옮기려면 라이브러리 위에 다시 래퍼를 씌워야 한다. 의존성 추가 대비 이득이 낮고, 기존 테스트(54건)의 시간 제어 방식을 깨뜨린다. 순수 표준 라이브러리 스켈레톤이 더 작고 명료하다.

### 제네릭 `SingleFlightCache<K,V>`로 추출 ✅ (채택)
불변식을 한 클래스에 한 번 담고, 각 캐시는 **다른 것만** 공급한다: 키 타입, TTL, 백킹 맵 정책(무한 `ConcurrentHashMap` vs LRU 상한 `LinkedHashMap`), 로더. 스프링 빈이 아니라 각 캐시가 자기 인스턴스를 소유해 키·값 타입과 TTL이 독립적으로 유지된다.

## 핵심 결정

- **로더 2단 API**:
  - `get(key, Function<K,V> loader)` — 흔한 경우. 로더가 값을 반환하면 캐시가 기본 TTL로 만료를 찍는다.
  - `getWithEntry(key, Function<K,Entry<V>> loader)` — 로더가 **만료까지 직접 결정**. room-seat가 L2 히트를 `newEntry(seats, ttl/2)`로, 상류 fetch를 `newEntry(seats)`(full TTL)로 저장하는 데 사용. L2 write와 지표/로깅 같은 부수효과도 로더 안에서 수행한다.
- **백킹 맵 정책은 팩터리로 분리**: `unbounded(...)`(층·방처럼 키 공간이 작고 유한) vs `lruBounded(..., capacity)`(검색어·학번처럼 사용자가 무한정 키울 수 있는 공간). LRU는 접근순서 `LinkedHashMap` + `removeEldestEntry`.
- **결정론적 만료**: 신선도는 `expiresAt.isAfter(clock.instant())`. 주입 `Clock` 유지로 기존 `MutableClock` 테스트가 그대로 동작.
- **Entry 불변식**: `value`/`expiresAt` non-null 강제(기존 3개 캐시의 가드와 일치). room-seat의 리스트 방어 복사(`List.copyOf`, null→`List.of()`)는 값 의미라 **로더 쪽에 남긴다**(제네릭은 값 타입을 모른다).
- **부수효과 순서**: `getWithEntry`에서 로더가 Entry를 반환한 뒤 제네릭이 `entries.put`+`complete`. room-seat의 `writeL2`가 로더 안(=L1 put 직전)으로 이동하지만, L2/L1은 독립 저장소라 순서 재배열은 무해하며 single-flight·double-check 불변식은 불변.

## 동작 방식

`SingleFlightCache`가 `entries`(정책별 맵)·`inflight`(`ConcurrentHashMap<K, CompletableFuture<Entry>>`)·`clock`·`ttl`·`label`(에러 메시지용)을 보유하고, 위 6단계 미스 경로를 한 번 구현한다. 5개 캐시는 생성자에서 `unbounded`/`lruBounded` 팩터리로 인스턴스를 만들고, `get()`에서 키를 만들어 로더 람다를 넘긴다. 에러 메시지 접두어(`"library seat cache wait interrupted"` 등)는 `label`로 파라미터화해 도메인별 식별성을 유지한다.

## 검증

- 기존 캐시 테스트 **54건 전수 통과**(LibrarySeat 7·LibraryRoomSeat 10·LibraryBook 8·SaintSchedule 7·Notice 간접 22) — 동작 보존 증명.
- 신설 `SingleFlightCacheTests` 8건이 공통 스켈레톤을 직접 검증: TTL 내 히트가 로더를 건너뜀, TTL 경과 후 재로딩, LRU 최소사용 축출, **동시 미스가 로더 1회로 합쳐짐**(블로킹 로더 + 4스레드), 로더 실패 전파·미오염(재시도 성공), `getWithEntry` half-TTL 만료, `invalidate`, Entry null 거부.
- 전체 스위트 **1075건 green**.

## 예상 면접 질문

1. **single-flight에서 "이긴 스레드가 다시 신선도를 재검사"하는 이유는?** `putIfAbsent`로 in-flight를 등록하기 직전에 다른 스레드가 방금 로딩을 끝내 `entries`에 채워 넣었을 수 있다. double-check 없이 바로 로더를 부르면 방금 채워진 신선한 값을 무시하고 상류를 한 번 더 때린다.
2. **실패한 로드를 캐싱하면 왜 위험한가?** 상류 일시 장애(만료 쿠키, 타임아웃)를 TTL 동안 캐싱하면 회복된 뒤에도 계속 실패를 돌려주는 "캐시 오염"이 된다. 그래서 예외 경로는 `entries.put`을 하지 않고 future만 예외 완료시켜 다음 호출이 즉시 재시도하게 한다.
3. **왜 라이브러리(Caffeine) 대신 직접 추출했나?** room-seat의 L2 half-TTL read-through와 주입 `Clock` 기반 결정론 테스트를 그대로 담으려면 라이브러리 위 래퍼가 또 필요하다. 표준 라이브러리 스켈레톤 한 클래스가 의존성 없이 더 작고, 기존 시간 제어 테스트를 깨지 않는다.
4. **로더가 만료를 직접 정하는 `getWithEntry`가 왜 필요했나?** Redis L2에서 이미 나이 든 엔트리를 L1에 full-TTL로 다시 얹으면 총 staleness가 2배가 된다. L2 히트만 half-TTL로 저장해야 하는데, 값 반환형 로더로는 만료를 표현할 수 없어 Entry 반환형 훅을 뒀다.
