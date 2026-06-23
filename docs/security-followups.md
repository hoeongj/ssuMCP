# 보안 후속 작업 (security follow-ups)

> 2026-06 보안 remediation에서 **의도적으로 보류**한 항목 모음. 이유는 셋 중 하나다 — ① 비가역/오설정 시 prod 아웃 위험 + 자율 검증 불가, ② 저보안가치 대비 고위험 변경, ③ 저장소 밖 의존(다른 repo·외부 계정·사용자 attestation). 배포 완료된 제어는 `docs/security.md` §14-1, 통합 triage 전문은 `../SECURITY_REMEDIATION_PLAN.md`.
>
> 각 항목: **무엇 / 왜 보류 / 권장 접근 / 진행에 필요한 것**. "이미 부분 완화됨"이 있으면 함께 명시한다.

---

## 1. k8s NetworkPolicy (#23 일부)

- **무엇**: backend pod의 ingress/egress를 NetworkPolicy로 명시 제한(예: postgres/redis/ingress만 허용).
- **왜 보류**: 오설정 시 backend↔postgres/redis/ingress 트래픽이 차단되어 **prod 전체 다운**. 클러스터 CNI가 NetworkPolicy를 강제하는지(예: Calico/Cilium)도 확인 필요하고, 자율 적용 후 안전 검증이 불가하다.
- **권장 접근**: 최소 권한 egress/ingress 정책을 staging에서 먼저 검증 → label selector로 DB/Redis/ingress만 허용 → prod 롤아웃.
- **진행에 필요한 것**: 클러스터 CNI가 NetworkPolicy enforce하는지 확인, staging 환경, 롤아웃 후 pod 연결성 점검 윈도우.

## 2. 컨테이너 read-only rootfs (#23 일부)

- **무엇**: pod `securityContext.readOnlyRootFilesystem: true` + 쓰기 필요 경로만 emptyDir/volume로 마운트.
- **왜 보류**: 앱·JVM·rusaint FFI가 런타임에 쓰는 경로(tmp, 캐시, native lib unpack 등)를 전부 식별하지 못하면 부팅·요청 처리 중 크래시. seccompProfile·automountSAToken·권한상승 차단은 이미 적용됨(ADR 0062).
- **권장 접근**: 쓰기 경로를 로그·strace로 열거 → 해당 경로만 writable volume → `readOnlyRootFilesystem: true` 적용을 staging에서 검증.
- **진행에 필요한 것**: 런타임 쓰기 경로 목록, staging 검증.

## 3. DB 무결성·보존 정책 (#27)

- **무엇**: 주요 테이블에 CHECK/FK 제약 + outbox/audit/export retention 정책.
- **왜 보류**: 마이그레이션-온-디플로이라 기존 prod 데이터가 새 제약을 위반하면 Flyway 실패 = 배포 깨짐(자율 검증 불가). 저보안가치(앱이 이미 유효값만 기록).
- **권장 접근**: Postgres `ALTER TABLE ... ADD CONSTRAINT ... NOT VALID`로 **기존 행은 미검증, 신규 쓰기만 강제** → 추후 한가할 때 `VALIDATE CONSTRAINT`. retention은 `@Scheduled` cleanup 잡.
- **진행에 필요한 것**: prod 데이터에 위반 행이 없는지 사전 점검(있으면 데이터 정정 또는 NOT VALID 유지), DB 마이그레이션 적용은 사용자 확인 필요(철칙 3).

## 4. 멀티포드 claim/lease (#14)

- **무엇**: 예약 outbox relay·LMS export worker를 멀티 레플리카에서 안전하게(중복 실행 없이) 돌리기 위한 `FOR UPDATE SKIP LOCKED` claim / lease.
- **왜 보류**: 현재 `replica=1`이라 live-impact 낮음 + 워커 동시성 변경은 회귀 위험. **참고: `RateLimitFilter` 카운터도 per-pod**라 멀티포드 전환 시 shared store(Redis)가 함께 필요하다.
- **권장 접근**: 멀티 레플리카 전환을 결정하면, outbox/export claim에 SKIP LOCKED + lease 타임아웃, rate-limit은 Redis 기반 분산 카운터로 묶어 한 번에.
- **진행에 필요한 것**: 멀티 레플리카 전환 결정(트래픽 근거), 부하/동시성 테스트.

## 5. LMS capability 토큰 1회성 (#16 일부)

- **무엇**: LMS export 다운로드 capability 토큰을 단기 TTL뿐 아니라 **1회 사용 후 무효화**(single-use).
- **왜 보류**: 현재 단기 TTL이지만 TTL 내 재사용 가능. 1회성은 다운로드 완료 시점 write-back(상태 저장)이 필요한 후속 작업이다. 이미 부분 완화: 응답에 no-referrer/no-store/no-cache + 단기 토큰(Bundle C1 `39ad2d9`, #16 부분).
- **권장 접근**: 토큰 사용 시 atomic하게 "consumed" 마킹(행 락 또는 Redis SETNX) → 재사용 거부.
- **진행에 필요한 것**: 다운로드 완료 판정 시점 정의(스트림 종료 vs 첫 바이트), 토큰 상태 저장소.

## 6. ssu-ai-service `/v1/embeddings` 인증 (#20)

- **무엇**: ssu-ai-service의 `/v1/embeddings`가 무인증 + Gemini 키가 URL `?key=`로 노출됨. 키를 `Authorization: Bearer` 헤더로 이동 + 호출자 인증 추가.
- **왜 보류**: **이 서비스는 별도의 git 추적 안 되는(untracked) 저장소**라 ssuMCP에서 커밋·배포 불가.
- **권장 접근**: 키를 쿼리스트링에서 `Authorization` 헤더로 이동 + 엔드포인트에 호출자 인증 게이트.
- **진행에 필요한 것**: **실제 ssu-ai-service 소스 저장소 위치**(사용자 제공 필요). 위치가 확인되면 권장 diff 적용 가능.

## 7. Mistral 학습 opt-out attestation (P2-Z)

- **무엇**: `SSUAI_MISTRAL_TRAINING_OPT_OUT_CONFIRMED` knob을 `true`로 설정(Mistral 등 provider 학습 데이터 opt-out 반영).
- **왜 보류**: `true`는 **사용자가 Mistral 계정에서 실제로 학습 opt-out을 확인**해야 하는 attestation이라 임의로 플립할 수 없다.
- **권장 접근**: Mistral 계정 설정에서 training opt-out을 적용·확인한 뒤 env knob을 `true`로.
- **진행에 필요한 것**: 사용자의 Mistral 계정 opt-out 확인(attestation), prod env-var 변경은 사용자 확인 필요(철칙 3).

## 8. 대형 클래스 리팩터 (#31)

- **무엇**: `LlmChatService`(~693줄)·`ToolResultCompactor`(~431줄)·예약 컨트롤러(~445줄) 등 god-class 분리.
- **왜 보류**: 저보안가치 + 대규모 diff 고위험. 보안 remediation 범위가 아니다.
- **권장 접근**: 별도 refactor 작업으로 책임 단위 추출(ADR 0051/0052의 dispatcher/evaluator 분리 패턴 연장), 회귀 테스트로 보호.
- **진행에 필요한 것**: 별도 일정, 충분한 테스트 커버리지 확보.

## 9. Dependabot 메이저 PR #109–114

- **무엇**: 메이저 버전 bump(예: 2.0.0) PR들 검토·반영.
- **왜 보류**: 메이저 bump는 breaking change 가능성이 있어 검토 필요. 의존성 bump는 자율 머지 금지(철칙 3).
- **권장 접근**: PR별 changelog/breaking change 확인 → 로컬 테스트 → 사용자 확인 후 머지.
- **진행에 필요한 것**: 각 PR 영향 분석, 사용자 확인.

## 10. cosmetic 응답 포맷 (경미)

- **무엇**: meal date ISO 포맷, `search_notices` dedup/limit, scholarship 네이밍, `get_my_lms_courses` `term_id` 설명 등 응답 포맷 정리.
- **왜 보류**: 클라이언트 계약 변경 위험 + 저가치. 통합 envelope 전면 채택 여부는 Rule 2 결정 사항.
- **권장 접근**: 클라(ssuAI/ssuAgent) 영향 확인 후 하위호환 유지하며 단계 적용, 또는 envelope 표준화 결정 시 일괄.
- **진행에 필요한 것**: 클라 계약 영향 점검, 포맷 표준화 결정.

## 11. ssuAgent `/agent` opt-in 인증 활성화 (P1-N)

- **무엇**: ssuAgent `/agent` 엔드포인트 **API 키 인증을 활성화**. opt-in 코드 머지됨(`AGENT_API_KEY` 설정 시에만 동작, 미설정=현행, `408a9e7`).
- **2026-06-23 갱신**: 비용/DoS 즉시 위험(무 rate-limit·무 크기상한·무 에러비노출·CORS `*`)은 **W4 `0dd88c2`(ADR0009)로 해소** — slowapi per-IP rate-limit + message 크기상한 + 에러 비노출 + CORS methods 축소. **남은 것은 API 키 인증 활성화뿐.**
- **왜 보류**: 브라우저(ssuAI)가 `/agent/*`를 직접 호출하므로 키를 클라에 두면 노출됨. 안전하려면 **ssuAI Next Route Handler 프록시**(서버사이드 키 주입)가 선결 + 양쪽 동시 배포.
- **권장 접근**: ssuAI에 `/api/agent/*` Route Handler 프록시 신설(서버에서만 `AGENT_API_KEY` 주입) → ssuAgent `AGENT_API_KEY` 설정 → 동시 롤아웃.
- **진행에 필요한 것**: Next 프록시 구현 + 양쪽 env-var(prod 변경=사용자 확인, 철칙 3) + 동시 배포 윈도우.

## 12. ssuAgent 대화 thread 인증 바인딩 (S4, 2026-06-23 신규)

- **무엇**: ssuAgent `thread_id`(클라 제공)가 LangGraph 체크포인트 키인데 인증·소유권 바인딩이 없다(`main.py`). 대화 기밀성이 thread_id 비밀성 + TLS에만 의존.
- **왜 보류**: 근본 차단은 `/agent` 인증(#11)이 활성화돼야 thread_id를 호출자 신원에 바인딩할 수 있다 → #11과 한 묶음. 완화: ssuAI가 `crypto.randomUUID()`(122bit)로 생성해 추측 난이도 높음.
- **권장 접근**: #11 인증 활성화 시 thread_id를 호출자 신원(키/세션)에 바인딩 → IDOR 원천 차단. cf. ssuMCP는 #1에서 `(owner, conversationId)` 복합키로 해결.
- **진행에 필요한 것**: #11 선행.

## 13. `get_notice_detail` SSRF 도메인 allowlist (M1, 2026-06-23 신규)

- **무엇**: `RealNoticeConnector.connect()`가 임의 URL을 `Jsoup.connect()`로 fetch — `scatch.ssu.ac.kr` 등 학교 도메인 allowlist 없음. 외부 임의 http/https 도달 가능(서버를 요청 프록시로 악용·블라인드 포트스캔 오라클).
- **왜 보류(부분 완화)**: 현 prod 배포에서 `file://`·메타데이터(169.254)·루프백·사설대역은 egress/컨테이너 격리로 차단됨(Critical 아님). 코드레벨 allowlist는 미적용.
- **권장 접근**: `connect()`에서 host를 학교 도메인 strict allowlist(`ssu.ac.kr` 및 `.ssu.ac.kr` 서브도메인 — `host.equals(d) || host.endsWith("."+d)`)와 대조 후에만 fetch + 사설/링크로컬 대역 코드레벨 차단(defense-in-depth) + 리다이렉트 호스트 재검증.
- **진행에 필요한 것**: 허용 도메인 목록 확정(공지 출처 enumerate).

## 14. 도서관 debug/내부 카운터 노출 (M2, 2026-06-23 신규)

- **무엇**: `get_library_seat_catalog(debug=true)`가 `captureNotes`(내부 백엔드명 Pyxis·미완 TODO·스크린샷 수집방법)를 누출 — `debug`가 public tool 파라미터라 누구나 true. `recommend_library_seats`가 `liveSeatItemsSeen` 등 내부 카운터 4종 상시 노출.
- **왜 보류**: 저민감도(운영 시그널)지만 정보노출. 응답 record 필드 제거는 계약 변경(ssuAI 미사용 확인됨 → 안전하나 별도 검증 권장).
- **권장 접근**: `debug`를 tool 파라미터에서 빼고 서버 env(`SSU_DEBUG`)로 게이팅 + `recommend_library_seats` 내부 카운터 4종을 응답에서 제거(`LibrarySeatRecommendationResponse`, ssuAI grep 사용처 0건 확인됨).
- **진행에 필요한 것**: 없음(작은 변경) — 후속 배치에서 처리.
