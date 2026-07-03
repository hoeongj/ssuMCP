# 보안 후속 작업 (security follow-ups)

> 2026-06 보안 remediation의 후속 레지스터. **열려 있는 항목**과 **종결·기각 결정 기록**을 분리해 관리한다. 배포 완료된 제어의 전체 색인은 `docs/security.md` §14-1.

---

## 열려 있는 항목

### 1. 멀티포드 claim/lease (#14) — 조건부 (멀티 레플리카 전환 시)

- **무엇**: 예약 outbox relay·LMS export worker를 멀티 레플리카에서 안전하게(중복 실행 없이) 돌리기 위한 `FOR UPDATE SKIP LOCKED` claim / lease. **참고: `RateLimitFilter` 카운터도 per-pod**라 멀티포드 전환 시 shared store(Redis)가 함께 필요하다.
- **왜 대기**: 현재 `replica=1`이라 live-impact 없음 + 워커 동시성 변경은 회귀 위험.
- **트리거**: 멀티 레플리카 전환 결정(트래픽 근거). 그때 outbox/export claim(SKIP LOCKED + lease 타임아웃)·rate-limit 분산 카운터(Redis)·세션 스토어 내구성/취소 재검토를 한 묶음으로 진행.

### 2. Mistral 학습 opt-out attestation (P2-Z) — 사용자 액션 대기

- **무엇**: `SSUAI_MISTRAL_TRAINING_OPT_OUT_CONFIRMED=true` 설정.
- **왜 대기**: 사용자가 Mistral 계정에서 실제로 학습 opt-out을 확인해야 하는 attestation이라 임의로 플립할 수 없다. prod env-var 변경은 사용자 확인 필요.

### 3. Spring Boot 4.1.x 서버 의존성 bump (#110) — 재시도 게이트 있음

- **경과**: 2026-06-30 Boot 4.0.6→4.1.0 bump가 prod 로그인 장애로 롤백됨. Boot 4.1.0이 관리 Jackson을 2→3(`tools.jackson`)으로 이동시키는데 `jjwt-jackson`이 Jackson 2 기반이라 `parseSignedClaims()` claim 역직렬화가 실패 → refresh 401. (함께 묶였던 GitHub Actions pin #132–135는 2026-07-02 별도 커밋으로 재적용 완료.)
- **재시도 게이트**: ① `jjwt`를 Jackson 3 호환 구성으로 마이그레이션(또는 claim 경로에 Jackson 2 명시 pin) ② 로그인 refresh E2E + 토큰 수동 HMAC 검증을 회귀 테스트로 묶은 뒤 진행.

---

## 종결·기각 결정 기록 (요약 + 근거 링크)

| # | 항목 | 결론 | 근거 |
|---|---|---|---|
| R1 | k8s NetworkPolicy egress 제한 | **기각(현 인프라)** — k3s 내장 kube-router는 FQDN egress 미지원(IP/CIDR만)이고 egress 대상 ~20개(LLM 9·학교 11)가 전부 동적 CDN IP라 CIDR allowlist는 IP 회전마다 조용히 사망. default-deny 시 앱 대부분 마비. 진짜 해법 = **Cilium CNI 교체 후 FQDN/DNS-aware egress**(단일노드 포트폴리오엔 과투자). ingress-only NP도 staging 없는 단일노드에서 위험 대비 가치 marginal. 면접 서사: "왜 egress를 안 막았나" → CNI 제약 + SaaS 동적 IP 현실 + Cilium 대안 제시 | 2026-06-30 결정 |
| R2 | 컨테이너 read-only rootfs | ✅ **적용·prod 검증**(rootfs 쓰기 차단 + `/tmp` emptyDir, JNA/rusaint FFI 동작 확인) | [ADR 0066](adr/0066-readonly-rootfs.md) |
| R3 | DB 무결성·retention (#27) | ✅ **retention 잡 배포**(terminal 행 일일 정리 180/30/30일). CHECK/FK 제약은 **의도적 미적용** — status CHECK는 enum 추가와 어긋나면 정상 쓰기 차단(ADR 0055 기각 유지), FK는 retention delete-coupling 충돌 | [ADR 0072](adr/0072-db-retention-scheduled-job.md) · ADR 0055 |
| R4 | LMS capability 토큰 1회성 | ✅ **적용**(READY→DOWNLOADED 원자 전이, post-download replay 차단) | [ADR 0067](adr/0067-lms-single-use-download-token.md) |
| R5 | ssu-ai-service `/v1/embeddings` 인증 | ✅ **완료·prod 배포**(Bearer 헤더 이동 + 인바운드 `X-API-Key` fail-closed + 에러 비반사; 2026-07-03 Let's Encrypt ingress로 외부 개통, 무키 401 실측) | ssu-ai-service README |
| R6 | 대형 클래스 리팩터 (#31) | **기각** — 책임 분리 역량은 ADR 0051/0052(dispatcher/evaluator 분리)로 이미 입증, 같은 패턴 반복은 서사 가치 0에 대규모 diff 회귀 위험만 | 2026-06-30 결정 |
| R7 | cosmetic 응답 포맷 | **기각** — 면접 서사 없음 + 클라 계약 변경 위험. 의미 있는 빈-결과 신호는 ADR 0053으로 이미 처리 | 2026-06-30 결정 |
| R8 | spring-ai 2.0 / MCP SDK 2.0 (#113) | **NO-GO·PR 닫음(2026-07-03)** — 실측 스파이크에서 SDK 2.0이 `AUTH_REQUIRED`(정상 결과)를 `CallToolResult.isError=true`로 매핑하는 wire 회귀 확인(인증 도구 ~15종 영향) + annotation 주입이 package-private 리플렉션 의존 + 기능 이득 0. 재시도 게이트: ① AUTH_REQUIRED non-error 복원 ② annotation 주입을 2.0 공개 API로 이관 ③ `McpSelfDogfoodTests`에 annotation-served 단언 추가 후 전체 green + prod smoke | 2026-07-02 스파이크 · PR #113 코멘트 |
| R9 | MCP SDK 2.0 단독 bump (#111/#112/#114) | **superseded 닫음** — `spring-ai-bom 1.1.7`이 고정하는 SDK 1.x와 충돌 | — |
| R10 | ssuAgent `/agent` opt-in 인증 | ✅ **활성화·3중 검증**(익명 401 / 유효키 422 / Vercel 프록시 E2E 일치) | ssuAgent ADR 0009 |
| R11 | ssuAgent thread 인증 바인딩 (S4) | ✅ **완료** — `thread_owners` claim 바인딩, prod IDOR 실측 403. ssuAI는 로그아웃 시 thread id 초기화(2026-07-03 `useSaintAuth.logout()`으로 이동해 탭 무관 보장) | ssuAgent ADR 0010 |
| R12 | `get_notice_detail` SSRF allowlist (M1) | ✅ **완료** — host allowlist + 수동 hop 루프 리다이렉트 재검증(fetch 전 검증, ≤5 hop) | 2026-06-30 · 2026-07-02 |
| R13 | 도서관 debug/내부 카운터 노출 (M2) | ✅ **완료** — public tool에서 `debug` 파라미터 제거 + 내부 카운터 4종 응답 제거 | 2026-06-30 |
