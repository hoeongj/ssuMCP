# 보안 후속 작업 (security follow-ups)

> 2026-06 보안 remediation에서 **의도적으로 보류**한 항목 모음. 이유는 셋 중 하나다 — ① 비가역/오설정 시 prod 아웃 위험 + 자율 검증 불가, ② 저보안가치 대비 고위험 변경, ③ 저장소 밖 의존(다른 repo·외부 계정·사용자 attestation). 배포 완료된 제어는 `docs/security.md` §14-1에 정리했다.
>
> 각 항목: **무엇 / 왜 보류 / 권장 접근 / 진행에 필요한 것**. "이미 부분 완화됨"이 있으면 함께 명시한다.

---

## 1. k8s NetworkPolicy (#23 일부)

- **무엇**: backend pod의 ingress/egress를 NetworkPolicy로 명시 제한(예: postgres/redis/ingress만 허용).
- **왜 보류**: 오설정 시 backend↔postgres/redis/ingress 트래픽이 차단되어 **prod 전체 다운**. 클러스터 CNI가 NetworkPolicy를 강제하는지(예: Calico/Cilium)도 확인 필요하고, 자율 적용 후 안전 검증이 불가하다.
- **권장 접근**: 최소 권한 egress/ingress 정책을 staging에서 먼저 검증 → label selector로 DB/Redis/ingress만 허용 → prod 롤아웃.
- **진행에 필요한 것**: 클러스터 CNI가 NetworkPolicy enforce하는지 확인, staging 환경, 롤아웃 후 pod 연결성 점검 윈도우.
- **2026-06-30 결정(egress 허용목록 버전 비현실적 → 미적용, Cilium 필요)**: 백엔드 egress 대상을 코드/설정에서 전수 추출한 결과 **외부 호스트 ~20개**(LLM 9곳: groq·mistral·cerebras·deepinfra·fireworks·sambanova·nscale·openrouter·huggingface + Gemini googleapis / 학교 11곳: saint·lms·canvas·oasis·scatch·rule·smartid·ssudorm·commons·ssu.ac.kr·ssufid + 기타 aladin·soongguri). 문제: ① **k3s 내장 NetworkPolicy 컨트롤러(kube-router)는 FQDN egress 규칙을 지원하지 않음**(IP/CIDR만). ② 위 20개는 전부 동적 CDN(Cloudflare 등) IP라 **CIDR allowlist는 IP 회전 때마다 깨져 기능이 조용히 사망**. ③ egress default-deny 시 9개 LLM + 11개 학교 시스템 호출이 전부 차단 = 앱 대부분 마비. → **egress 제한 NetworkPolicy는 적용하지 않는다.** **진짜 해법 = CNI를 Cilium으로 교체 후 FQDN/DNS-aware egress 정책**(별도 인프라 작업, 단일노드 포트폴리오엔 과투자). ingress-only NP(traefik만 허용)는 단일노드+staging無에서 kubelet health probe/selector 오타 시 prod 503 위험 대비 가치가 marginal이라 함께 보류. **면접 서사**: "쿠버네티스 NetworkPolicy로 egress를 왜 안 막았나" → CNI(kube-router)의 FQDN 미지원 + SaaS 동적 IP 현실을 설명하고 Cilium 대안을 제시(통제의 한계를 아는 것도 시스템 사고). cf. read-only rootfs(#2)·pod-security(ADR0062)는 적용 완료 — pod 레벨 하드닝은 했고 network 레벨은 CNI 제약으로 보류.

## 2. 컨테이너 read-only rootfs (#23 일부) — ✅ 적용·prod 검증 완료 (2026-06-30)

- **무엇**: pod `securityContext.readOnlyRootFilesystem: true` + 쓰기 필요 경로만 emptyDir/volume로 마운트.
- **반영**: `deploy/charts/ssuai-backend`에서 backend rootfs를 read-only로 전환하고 `/tmp`를 항상 `emptyDir`로 마운트했다. LMS export ZIP은 기존처럼 `/data/lms-export` PVC에 쓰며, JVM/JNA scratch는 `-Djava.io.tmpdir=/tmp -Djna.tmpdir=/tmp`로 `/tmp` emptyDir에 고정한다(ADR 0066).
- **검증 완료(2026-06-30)**: merge `dc7df68` → ArgoCD 배포 후 새 backend pod `ssuai-backend-9f8f879-bkfr6` Ready(maxUnavailable0 무중단), rootfs 쓰기 차단(`touch /test_rootfs`→Read-only)·`/tmp` emptyDir 쓰기 가능·`java.io.tmpdir`/`jna.tmpdir` 양쪽 `/tmp`·`librusaint_ffi.so` 읽기 정상·health UP을 실측했다. 이후 로그인 인시던트 pod 로그에서 authenticated u-SAINT/rusaint 호출이 성공(`saint rusaint session stored`)해 JNA `jnidispatch`가 `/tmp` emptyDir에서 추출·로드되고 rusaint FFI가 read-only rootfs에서 동작함을 확정했다.

## 3. DB 무결성·보존 정책 (#27)

- **무엇**: 주요 테이블에 CHECK/FK 제약 + outbox/audit/export retention 정책.
- **왜 보류**: 마이그레이션-온-디플로이라 기존 prod 데이터가 새 제약을 위반하면 Flyway 실패 = 배포 깨짐(자율 검증 불가). 저보안가치(앱이 이미 유효값만 기록).
- **권장 접근**: Postgres `ALTER TABLE ... ADD CONSTRAINT ... NOT VALID`로 **기존 행은 미검증, 신규 쓰기만 강제** → 추후 한가할 때 `VALIDATE CONSTRAINT`. retention은 `@Scheduled` cleanup 잡.
- **진행에 필요한 것**: prod 데이터에 위반 행이 없는지 사전 점검(있으면 데이터 정정 또는 NOT VALID 유지), DB 마이그레이션 적용은 사용자 확인 필요(철칙 3).
- **2026-06-30 결정(분석 완료 → 제약 추가 보류)**: 스키마(V1~V13) 전수 검토 결과 안전하게 추가할 제약이 없다. ① **CHECK 제약**(status 컬럼)은 #3의 핵심이지만 **ADR 0055에서 이미 "CHECK 추가가 오히려 취약점"으로 기각**됨 — `action_audit.status`·`library_reservation_intents.status`·`lms_export_jobs.status`는 코드 enum 문자열이라, 마이그레이션에 박은 값집합이 향후 enum 추가와 어긋나면 정상 쓰기가 차단된다(자율 검증 불가한 잠복 회귀). ② **FK 후보**(`library_reservation_outbox.intent_id→library_reservation_intents.id`, `library_reservation_intents.action_audit_id→action_audit.id`)는 추가 시 같은 항목이 요구하는 **retention(오래된 outbox/audit/intent 정리) 잡과 delete-coupling 충돌**(CASCADE 또는 삭제 순서 의존 부채). → **추측성 제약을 prod에 넣지 않는다.** 무결성은 앱 레이어가 이미 보장(유효값만 기록), 진짜 해법은 retention `@Scheduled` 잡(별도 작업)이다. 면접 서사로도 "왜 DB CHECK를 안 넣었나(ADR0055)"가 더 강하다.

## 4. 멀티포드 claim/lease (#14)

- **무엇**: 예약 outbox relay·LMS export worker를 멀티 레플리카에서 안전하게(중복 실행 없이) 돌리기 위한 `FOR UPDATE SKIP LOCKED` claim / lease.
- **왜 보류**: 현재 `replica=1`이라 live-impact 낮음 + 워커 동시성 변경은 회귀 위험. **참고: `RateLimitFilter` 카운터도 per-pod**라 멀티포드 전환 시 shared store(Redis)가 함께 필요하다.
- **권장 접근**: 멀티 레플리카 전환을 결정하면, outbox/export claim에 SKIP LOCKED + lease 타임아웃, rate-limit은 Redis 기반 분산 카운터로 묶어 한 번에.
- **진행에 필요한 것**: 멀티 레플리카 전환 결정(트래픽 근거), 부하/동시성 테스트.

## 5. LMS capability 토큰 1회성 (#16 일부) — ✅ 적용 (2026-06-30)

- **무엇**: LMS export 다운로드 capability 토큰을 단기 TTL뿐 아니라 **1회 사용 후 무효화**(single-use).
- **반영**: `LmsExportStatus.DOWNLOADED` terminal state를 추가하고, READY 바이너리 다운로드만 `StreamingResponseBody`로 전환했다. 파일 복사와 flush가 예외 없이 완료된 뒤에만 `markDownloaded(jobId, now)`를 호출한다. repository는 `where status = READY` 조건부 UPDATE로 원자적 `READY -> DOWNLOADED` 전이를 수행한다. 상세 설계는 [ADR 0067](adr/0067-lms-single-use-download-token.md).
- **부분 완화 유지**: 기존 no-referrer/no-store/no-cache + 단기 토큰(Bundle C1 `39ad2d9`, #16 부분)은 그대로 유지한다. 이번 변경은 여기에 성공 다운로드 후 replay 차단을 추가한다.
- **잔여**: 거의 동시에 시작된 두 READY 스트림은 둘 다 파일을 받을 수 있지만, 완료 후 전이는 하나만 성공한다. post-download replay 차단이 목표라 수용한다.

## 6. ssu-ai-service `/v1/embeddings` 인증 (#20)

- **무엇**: ssu-ai-service의 `/v1/embeddings`가 무인증 + Gemini 키가 URL `?key=`로 노출됨. 키를 `Authorization: Bearer` 헤더로 이동 + 호출자 인증 추가.
- **왜 보류**: **이 서비스는 별도의 git 추적 안 되는(untracked) 저장소**라 ssuMCP에서 커밋·배포 불가.
- **2026-06-30 갱신**: prod k3s 전체 네임스페이스 스캔 결과 **ssu-ai-service는 클러스터 어디에도 배포돼 있지 않음**(`kubectl get deploy,svc -A`에 embeddings/ai-service 0건). ssuMCP RAG는 Gemini를 직접 호출(이 서비스 미경유). 즉 현 시점 **노출면 없음**(미배포 유휴 서비스) → 보안 우선순위 사실상 N/A. 추후 git화하여 정식 운영할 경우에만 위 인증 보강 적용.
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
- **2026-06-30 결정(스킵)**: 포트폴리오 가치 판정 결과 스킵. 책임 분리 리팩터 역량은 이미 ADR 0051/0052(dispatcher/evaluator 분리)로 입증됐다. 같은 패턴 반복은 새 면접 서사 가치 0에 가깝고, 대규모 diff 회귀 위험만 크다. 진짜 필요해지면 별도 전용 세션에서 테스트 커버리지를 먼저 확보한 뒤 진행한다.

## 9. Dependabot 보안 후속 bump #110–114, #132–135

- **배경**: Dependabot PR의 원본 커밋은 bot author라 그대로 fast-forward merge하면 main의 authorship 철칙 1을 위반한다. 따라서 안전한 bump도 PR branch 커밋을 병합하지 않고, 변경 파일만 가져와 `hoengj <seongjuice999@gmail.com>` 로컬 authored/committed commit으로 재작성해야 한다.
- **2026-06-30 시도 후 롤백**: #110 server minor/patch group(Spring Boot 4.0.6→4.1.0, Kotlin 2.3.21→2.4.0, Gradle wrapper 9.5.1→9.6.1, Redisson 4.5.0→4.6.1, Resilience4j 2.3.0→2.4.0, OkHttp 5.3.2→5.4.0, JNA 5.18.1→5.19.1 등)과 #132 setup-java 5.3.0→5.4.0, #133 setup-node 4.4.0→6.4.0, #134 action-gh-release 2.6.2→3.0.1, #135 checkout 6.0.3→7.0.0을 한 로컬 commit(`b923464`)으로 재작성 반영했으나 prod 로그인 장애로 롤백한다.
- **롤백 사유**: Spring Boot 4.1.0은 관리 Jackson 계열을 Jackson 2(`com.fasterxml.jackson`)에서 Jackson 3(`tools.jackson`) 중심으로 이동시킨다. 현재 JWT 라이브러리 `jjwt 0.13.0`의 `jjwt-jackson`은 Jackson 2 기반이라 `JwtProvider.parse`의 `Jwts.parser().parseSignedClaims()` claim 역직렬화 경로가 Boot 4.1.0 런타임에서 실패했고, refresh 쿠키가 정상 전송되어도 `AuthController.refresh`가 401을 반환했다.
- **재시도 조건**: Boot 4.1.x 재시도 전 `jjwt`를 Jackson 3 호환 구성으로 마이그레이션하거나, `jjwt` claim 역직렬화 경로에 Jackson 2를 명시적으로 pin하는 설계를 먼저 검증한다. 로그인 refresh E2E와 토큰 수동 HMAC 검증을 함께 회귀 테스트로 묶는다.
- **분리 재적용**: #132/#133/#134/#135 GitHub Actions pin bump는 상대적으로 안전하지만 `b923464`에 함께 묶여 있어 이번 revert에서 같이 롤백된다. 서버 의존성 bump와 분리해 별도 로컬 authored commit으로 재적용한다. → **✅ 재적용 완료(2026-07-02)**: checkout `v7.0.0` · setup-node `v6.4.0` · action-gh-release `v3.0.1` · setup-java `5.4.0` SHA pin을 별도 로컬 authored commit으로 main 반영(워크플로우 3종 전체).
- **종결 처리**: #111, #112, #114의 standalone MCP SDK 2.0 bump는 `spring-ai-bom 1.1.7`이 고정하는 MCP SDK 1.x 계열과 충돌해 단독 반영 시 컴파일 실패 위험이 크므로 superseded로 닫는다.
- **보류**: #113 spring-ai 2.0 / Jackson 2→3 전환은 약 80개 `com.fasterxml.jackson` 참조, MCP SDK 2.0 API 변화, annotation package 이동을 동반하는 breaking migration이다. 보안 후속 routine bump로 섞지 않고 전용 migration branch에서 설계·테스트한다.

## 10. cosmetic 응답 포맷 (경미)

- **무엇**: meal date ISO 포맷, `search_notices` dedup/limit, scholarship 네이밍, `get_my_lms_courses` `term_id` 설명 등 응답 포맷 정리.
- **왜 보류**: 클라이언트 계약 변경 위험 + 저가치. 통합 envelope 전면 채택 여부는 Rule 2 결정 사항.
- **권장 접근**: 클라(ssuAI/ssuAgent) 영향 확인 후 하위호환 유지하며 단계 적용, 또는 envelope 표준화 결정 시 일괄.
- **진행에 필요한 것**: 클라 계약 영향 점검, 포맷 표준화 결정.
- **2026-06-30 결정(스킵)**: 스킵. 날짜 포맷 통일·dedup 등은 면접 서사가 되지 않고(포트폴리오 가치 ~0), 클라이언트 계약 변경(외부 파서) 위험만 있다. 의미 있는 응답 신호인 빈 결과 표시는 이미 ADR 0053(공개 목록 응답에 `empty`/`note` additive 추가, 하위호환)으로 처리됐다. 통합 envelope 표준화를 따로 결정하면 그때 일괄 처리한다.

## 11. ssuAgent `/agent` opt-in 인증 활성화 (P1-N) — ✅ 활성화·검증 완료 (2026-06-30)

> **✅ 종결(2026-06-30)**: 양쪽 `AGENT_API_KEY` 설정으로 **인증 강제 활성화 완료**. ssuAI(Vercel) env + ssuAgent k3s secret `ssuagent-secrets`에 동일 키 추가 → `ssu-agent` rollout restart(무중단, maxSurge1/maxUnavail0). **3중검증**: ① 키 없는 직접호출 `POST https://ssuagent.duckdns.org/agent/stream`→**401**(`Invalid or missing X-Agent-Key`) = 익명 접근 차단 = 강제 ON ② 올바른 키 직접호출→**422**(body 검증오류, 401아님) = 키 수락 ③ Vercel 프록시 `POST /api/agent/stream` 경유→**422**(401아님) = Vercel키↔k3s키 **E2E 일치**. 익명 `/agent` LLM 비용소진·DoS 벡터 차단 종결. k3s secret은 git-untracked(out-of-band)라 ArgoCD가 안 건드림. → **#12(thread 인증 바인딩) 선행조건 해제, 착수 가능.**

> **진행(2026-06-30)**: Next 프록시 **구현·머지·배포 완료** — ssuAI PR #205 `feat(agent): proxy /agent SSE...` (`c891ba6`, **MERGED**, Vercel 배포). `app/api/agent/{stream,resume}/route.ts`가 서버사이드에서 `X-Agent-Key`(env `AGENT_API_KEY`)를 주입하고 SSE를 패스스루, 클라(`lib/api/agent.ts`)는 same-origin `/api/agent/*` 호출로 변경. **prod 스모크 확인**: `POST https://ssuai.vercel.app/api/agent/stream` 빈 body → 422(ssuAgent 검증오류가 프록시 통해 반환) = 라우트·프록시·패스스루 정상. **하위호환**: 키 미설정이라 현재 ssuAgent no-op(현행 동작 유지). **주의**: 브라우저→Vercel함수→ssuAgent 경로라 긴 SSE는 Vercel `maxDuration`(60s, Hobby max; Pro면 300 권장)에 걸릴 수 있음 — 인증된 세션으로 에이전트 멀티스텝 스트림 실측 권장(UI).
> **남은 활성화 절차(사용자, 철칙3) = 인증 강제 켜기**: ① ssuAI(Vercel) env `AGENT_API_KEY`(+선택 `SSUAGENT_BASE_URL`) 설정 → 프록시가 키 전송 시작(ssuAgent 아직 no-op이라 무해) ② ssuAgent prod env `AGENT_API_KEY`를 **동일 값**으로 설정 → 인증 강제. 순서 중요(프록시가 키 보내기 시작한 뒤 ssuAgent 강제). 이후 #12(S4 thread 인증 바인딩) 진행 가능.

- **무엇**: ssuAgent `/agent` 엔드포인트 **API 키 인증을 활성화**. opt-in 코드 머지됨(`AGENT_API_KEY` 설정 시에만 동작, 미설정=현행, `408a9e7`).
- **2026-06-23 갱신**: 비용/DoS 즉시 위험(무 rate-limit·무 크기상한·무 에러비노출·CORS `*`)은 **W4 `0dd88c2`(ADR0009)로 해소** — slowapi per-IP rate-limit + message 크기상한 + 에러 비노출 + CORS methods 축소. **남은 것은 API 키 인증 활성화뿐.**
- **왜 보류**: 브라우저(ssuAI)가 `/agent/*`를 직접 호출하므로 키를 클라에 두면 노출됨. 안전하려면 **ssuAI Next Route Handler 프록시**(서버사이드 키 주입)가 선결 + 양쪽 동시 배포.
- **권장 접근**: ssuAI에 `/api/agent/*` Route Handler 프록시 신설(서버에서만 `AGENT_API_KEY` 주입) → ssuAgent `AGENT_API_KEY` 설정 → 동시 롤아웃.
- **진행에 필요한 것**: Next 프록시 구현 + 양쪽 env-var(prod 변경=사용자 확인, 철칙 3) + 동시 배포 윈도우.

## 12. ssuAgent 대화 thread 인증 바인딩 (S4, 2026-06-23 신규) — ✅ 완료 (2026-06-30)

> **✅ 종결(2026-06-30)**: thread 소유권 바인딩 구현·머지·prod 검증 완료. ssuAgent에 `thread_owners` 테이블 + `claim_or_verify_thread_owner`로 thread_id를 호출자 신원에 바인딩(ADR 0010, PR #14 `0c8931f` 머지) — 첫 호출자가 thread를 claim하고 이후 다른 신원의 접근은 거부. ssuAI는 로그아웃 시 thread 리셋(PR #206 `272dd42`). **prod IDOR 실측**: 타인 thread_id로 접근 → **403** 확인.

- **무엇**: ssuAgent `thread_id`(클라 제공)가 LangGraph 체크포인트 키인데 인증·소유권 바인딩이 없다(`main.py`). 대화 기밀성이 thread_id 비밀성 + TLS에만 의존.
- **왜 보류**: 근본 차단은 `/agent` 인증(#11)이 활성화돼야 thread_id를 호출자 신원에 바인딩할 수 있다 → #11과 한 묶음. 완화: ssuAI가 `crypto.randomUUID()`(122bit)로 생성해 추측 난이도 높음.
- **권장 접근**: #11 인증 활성화 시 thread_id를 호출자 신원(키/세션)에 바인딩 → IDOR 원천 차단. cf. ssuMCP는 #1에서 `(owner, conversationId)` 복합키로 해결.
- **2026-06-30 갱신**: **#11 활성화 완료 → 선행조건 해제, 착수 가능.** 단 현 #11은 프록시가 **단일 공유 키**를 주입하는 구조라 "호출자별 신원"이 에이전트 계층에 없음 → thread_id 바인딩 설계는 (a) ssuAI 프록시가 per-user 식별자를 추가 주입해 namespace화 vs (b) randomUUID(122bit) + 위협모델 문서화로 수용 중 택1이 필요(철칙2 옵션 보고 대상).
- **진행에 필요한 것**: ~~#11 선행~~✅완료. ~~위 (a)/(b) 설계 결정~~ → `thread_owners` 소유권 claim 바인딩으로 종결(✅ 위 종결 노트).

## 13. `get_notice_detail` SSRF 도메인 allowlist (M1, 2026-06-23 신규) — ✅ 완료 (2026-06-30)

> **해결**: `NoticeService.getNoticeDetail`에 호출자 제공 URL의 host allowlist 검증 추가 — scheme은 http(s)만, host는 `ssu.ac.kr` 또는 `.ssu.ac.kr` 서브도메인만 허용(공지 본문은 scatch.ssu.ac.kr). IP 리터럴·내부주소는 `.ssu.ac.kr`로 안 끝나므로 자동 차단(defense-in-depth). 위반 시 `IllegalArgumentException`(fetch 전 거부). 테스트 4건(외부host·내부IP·non-http scheme 거부 + ssu host 통과). **잔여**: ~~Jsoup 리다이렉트 추종은 그대로 — 리다이렉트-경유 우회는 egress 격리로 완화(코드레벨 재검증은 추후)~~ → **✅ 리다이렉트 재검증 적용(2026-07-02)**: `RealNoticeConnector`가 Jsoup 자동 리다이렉트를 끄고(`followRedirects(false)`) 수동 hop 루프로 각 `Location` host를 동일 allowlist(`NoticeHostAllowlist`, 초기 검증과 공유)와 대조 — 위반 시 동일한 `IllegalArgumentException`, 최대 5 hop 초과도 거부. **구현 결정**: (a) Jsoup 자동 추종 유지 + 최종 URL 사후 검증 vs (b) 수동 hop 루프 중 **(b) 채택** — (a)는 off-allowlist 요청이 이미 나간 뒤의 검증이라 SSRF 차단이 되지 않고, (b)는 각 hop을 fetch **전에** 검증한다. 테스트 3건 추가(off-allowlist 302 거부 · allowlist 내 체인 추종 · 리다이렉트 루프 >5 hop 거부, WireMock).

- **무엇**: `RealNoticeConnector.connect()`가 임의 URL을 `Jsoup.connect()`로 fetch — `scatch.ssu.ac.kr` 등 학교 도메인 allowlist 없음. 외부 임의 http/https 도달 가능(서버를 요청 프록시로 악용·블라인드 포트스캔 오라클).
- **왜 보류(부분 완화)**: 현 prod 배포에서 `file://`·메타데이터(169.254)·루프백·사설대역은 egress/컨테이너 격리로 차단됨(Critical 아님). 코드레벨 allowlist는 미적용.
- **권장 접근**: `connect()`에서 host를 학교 도메인 strict allowlist(`ssu.ac.kr` 및 `.ssu.ac.kr` 서브도메인 — `host.equals(d) || host.endsWith("."+d)`)와 대조 후에만 fetch + 사설/링크로컬 대역 코드레벨 차단(defense-in-depth) + 리다이렉트 호스트 재검증.
- **진행에 필요한 것**: 허용 도메인 목록 확정(공지 출처 enumerate).

## 14. 도서관 debug/내부 카운터 노출 (M2, 2026-06-23 신규) — ✅ 완료 (2026-06-30)

> **해결**: ① `get_library_seat_catalog`에서 `debug` 파라미터 제거 → public tool로 `captureNotes`(내부 백엔드명·TODO·스크린샷 방법) 요청 불가. captureNotes는 service 4-arg 오버로드로 내부/테스트에서만 접근. ② `LibrarySeatRecommendationResponse`에서 내부 카운터 4종(`liveAvailableSeats`·`liveSeatItemsSeen`·`catalogSeatsOnFloor`·`catalogMatchedAvailableSeats`) 제거 — ssuAI grep 0건, LLM은 자연어 `message`로 가용성 파악. 테스트 갱신.

- **무엇**: `get_library_seat_catalog(debug=true)`가 `captureNotes`(내부 백엔드명 Pyxis·미완 TODO·스크린샷 수집방법)를 누출 — `debug`가 public tool 파라미터라 누구나 true. `recommend_library_seats`가 `liveSeatItemsSeen` 등 내부 카운터 4종 상시 노출.
- **왜 보류**: 저민감도(운영 시그널)지만 정보노출. 응답 record 필드 제거는 계약 변경(ssuAI 미사용 확인됨 → 안전하나 별도 검증 권장).
- **권장 접근**: `debug`를 tool 파라미터에서 빼고 서버 env(`SSU_DEBUG`)로 게이팅 + `recommend_library_seats` 내부 카운터 4종을 응답에서 제거(`LibrarySeatRecommendationResponse`, ssuAI grep 사용처 0건 확인됨).
- **진행에 필요한 것**: 없음(작은 변경) — 후속 배치에서 처리.
