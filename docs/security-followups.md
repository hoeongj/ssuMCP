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
- **왜 보류**: 현재 단기 TTL이지만 TTL 내 재사용 가능. 1회성은 다운로드 완료 시점 write-back(상태 저장)이 필요한 후속 작업이다. 이미 부분 완화: 응답에 no-referrer/no-store/no-cache + 단기 토큰(ADR 0062/Bundle C1).
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

- **무엇**: ssuAgent `/agent` 엔드포인트 인증을 **활성화**. opt-in 코드는 이미 머지됨(`AGENT_API_KEY` 설정 시에만 동작, 미설정=현행 → prod 안 깨짐, ssuAgent PR#12 `408a9e7`).
- **왜 보류**: 활성화는 양쪽 배포에 env-var를 동시에 넣어야 한다 — 한쪽만 설정하면 호출이 깨진다.
- **권장 접근**: `AGENT_API_KEY`를 **ssuAgent와 ssuAI 양쪽에 동일 값**으로 설정 → 동시 롤아웃.
- **진행에 필요한 것**: 양쪽 env-var 설정(prod env-var 변경 = 사용자 확인 필요, 철칙 3), 동시 배포 윈도우.
