# ADR 0029: P1-3 Resilience4j RateLimiter + Bulkhead

## 배경 (Background)

- 단일 k3s 노드의 공인 IP 1개에서 모든 사용자의 Pyxis(도서관 시스템) 요청이 발송된다.
- 기존: CB(Circuit Breaker) + Retry만 존재 → 업스트림이 느려질 때 쓰레드/연결이 집중되는 Cascading failure 가능성, 초당 요청 수 상한 없어 IP ban/rate-limit 트리거 위험.
- P1-3 목표: RateLimiter(아웃바운드 속도 제한) + Bulkhead(동시 실행 상한) 추가.

## 검토한 대안 (Alternatives considered)

- **Bucket4j (L2 토큰버킷)**: Bucket4j는 L2(인바운드 보호용)로 별도 계획. 여기서 Resilience4j RateLimiter를 선택한 이유 — 이미 Resilience4j가 의존성에 있어 추가 라이브러리 없음, Micrometer 지표 자동 노출, CB/Retry와 동일한 Functional API 패턴 유지.
- **ThreadPoolBulkhead vs SemaphoreBulkhead**: Java 21 virtual thread 환경에서 ThreadPoolBulkhead는 별도 스레드 풀을 생성해 복잡성만 추가. SemaphoreBulkhead는 호출 스레드에서 세마포어로 동시성을 제한 → 단순·충분.
- **CB 이전 vs 이후 RateLimiter**: 권장 실행 순서는 Bulkhead → RateLimiter → CB → Retry(Resilience4j 공식 문서 기준). CB가 OPEN일 때 RateLimiter permit을 소모하지 않는 게 효율적이므로 RateLimiter를 CB 바깥에 위치시킴. Retry는 CB 안에서만 작동해야 하므로 innermost.

## 결정 (Decision)

- read: limitForPeriod=5/s, timeout=500ms — 사용자 확인(2026-06-13), "사람 사용 패턴 수준"
- write: limitForPeriod=2/s, timeout=200ms — 예약/반납은 더 엄격히 제한
- SemaphoreBulkhead: maxConcurrentCalls=10, maxWait=500ms — 10개 동시 Pyxis 연결이면 대학 도서관 시스템 기준 충분히 안전
- 실행 순서 (Functional API decorator chain): innermost=Retry, then CB, then RateLimiter, then Bulkhead(outermost)
- 테스트 격리: forTesting() factory가 limitForPeriod=100_000으로 설정해 단위 테스트가 속도 제한에 걸리지 않도록 함

## 작동 방식 (How it works)

- Bulkhead.decorateSupplier가 가장 바깥을 감싸서 동시 실행 수를 세마포어로 제한
- RateLimiter.decorateSupplier가 그 안에서 초당 permit 소모를 관리
- CB.decorateSupplier가 실패율을 추적하고 OPEN 상태에서 단락
- Retry.decorateSupplier가 가장 안쪽에서 CB-guarded 호출을 재시도
- Micrometer: TaggedRateLimiterMetrics, TaggedBulkheadMetrics 자동 등록 → Grafana에서 resilience4j_ratelimiter_*, resilience4j_bulkhead_* 지표 확인 가능
- Admin 노출: circuitBreakerState(), circuitBreakerFailureRate() 접근자 추가 (이후 admin endpoint PR에서 사용)
