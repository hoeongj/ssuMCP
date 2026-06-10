# AGENTS.md — ssuMCP

숭실대학교 MCP 서버 (Spring Boot, Kotlin/Java 21).
MCP 표준 도구로 학식 · 도서관 · 시설 · u-SAINT · LMS 정보를 노출.

> **워크플로우 원본: `../AGENTS.md` (mp 루트).** Claude(Fable 5)가 설계·구현·테스트,
> Codex가 commit/push/PR/merge/배포 확인을 전담한다. 핵심 규칙 요약은 아래 인라인.

## 문서

- `docs/architecture.md` — 패키지 구조, 레이어, Connector 패턴, MCP tool 목록
- `docs/security.md` — 인증 흐름, 자격증명 저장, 로그 금지 항목
- `docs/mcp-tools.md` — MCP tool 입출력 레퍼런스
- `docs/adr/` — 아키텍처 결정 기록
- `TROUBLESHOOTING.md` — 트러블슈팅 누적 (포트폴리오)

## 핵심 규칙 (원본: ../AGENTS.md)

1. **Authorship** — author/committer = 본인 계정(hoengj). AI attribution
   (`Co-Authored-By: Claude`, `🤖 Generated with…`, "Claude"/"Anthropic"/"Codex" 표기) 절대 금지.
   커밋 후 `git log -1 --format='%an <%ae> | %cn <%ce>'` 확인.
2. **의사결정** — 스펙·방법·로직·프레임워크 결정 전 반드시 웹검색 → 포트폴리오 가치(최우선)·
   트렌드 적합성·완성/증명 가능성 평가 → 사용자에게 보고 후 확정. 즉흥 결정 금지.
   목표는 취업 직행 수준의 최고 포트폴리오 — 더 좋은 방향이 있으면 갈아엎기 옵션까지 보고.
3. **사용자 확인 필수** — DB 마이그레이션 적용, prod 환경변수 변경, major dep bump,
   서버 재시작, force-push, 실사용 계정 write 액션(`confirm_action`).
   그 외 테스트·커밋·푸시·PR·머지(tests pass + 런타임 영향 없음)·CI 확인은 자율 실행.
4. **트러블슈팅** — 외부 시스템 예상 외 동작 / 첫 가설 틀림 / 테스트 green인데 prod 깨짐 /
   프레임워크 우회 / 설계 전환 / 보안·인증 버그 발생 시 **즉시** `TROUBLESHOOTING.md` 기록.
   필수 포함: 틀린 가설 / 실제 원인 / 핵심 파일·커밋 / 포트폴리오 포인트 / 면접 예상 질문 2~3개.
5. **문서 최신화** — 큰 작업 완료 시 `../MASTERPLAN.md` + 변경 영역 `docs/` 즉시 갱신.
   기록은 학습 교재 수준: 배경 / 검토한 대안과 기각 이유 / 선택 근거(출처) / 동작 원리까지.
   (사용자가 완성 후 기록을 뜯어보며 공부해 면접을 준비한다 — 요약·생략 금지)
6. **단독 모드** — 사용자가 한쪽 토큰 소진을 알리면 남은 AI가 설계→구현→테스트→커밋→배포
   확인까지 전부 수행. 진행 기준은 `../MASTERPLAN.md` "다음 작업", 완료 시 현황 갱신.
   Codex 단독 시 문서·단순 작업은 `-p git`(mini) 프로필로 토큰 절약.

## 프로젝트 구조

```
src/main/
├── java/com/ssuai/           # 패키지명 com.ssuai (리팩터링 예정 없음)
│   ├── global/               # auth, config, exception, response
│   └── domain/
│       ├── auth/             # MCP 세션 인증 (McpAuthSession, McpAuthStore)
│       ├── campus/           # 시설 검색
│       ├── chat/             # 챗봇 LLM 연동 (LlmChatService, ToolResultCompactor)
│       ├── dorm/             # 레지던스홀 식단
│       ├── library/          # 도서관 좌석·도서·대출 + action (예약/이석/반납)
│       ├── lms/              # LMS 과제
│       ├── mcp/              # McpServerConfig + @Tool 클래스
│       ├── meal/             # 학식
│       ├── notice/           # 공지사항
│       ├── academic/         # 공식 학칙·졸업·장학 출처 RAG
│       └── saint/            # u-SAINT (시간표, 성적, 채플, 졸업, 장학금)
└── kotlin/
    ├── com/ssuai/domain/saint/connector/
    │   └── RusaintUniFfiClient.kt    # rusaint JNA 호출 어댑터
    └── dev/eatsteak/rusaint/         # rusaint Kotlin FFI 바인딩 (JNA) — 수정 금지
deploy/                 # k8s Helm chart + Docker
```

## 핵심 패턴

- **Connector**: 외부 시스템 호출 → 내부 DTO 반환. Mock / Real 구현 분리.
  `@ConditionalOnProperty` 로 프로파일별 선택. 새 도구 = 새 Connector.
- **MCP 도구 등록 필수**: 새 `@Tool` 클래스 추가 시 반드시
  `McpServerConfig.java` `toolObjects(...)` 에 등록. 안 하면 클라이언트에 노출 안 됨.
- **Private tool 인증**: `mcp_session_id` 파라미터 + `McpAuthHelper.principalKey()`.
  세션 없으면 `AUTH_REQUIRED` + loginUrl 반환.

## 개발 규칙

- 테스트: `.\gradlew.bat test` (Windows) / `./gradlew test` (Linux)
- 일반 작업에서 읽지 말 것: `build/`, `.gradle/`, `.idea/`,
  `src/main/kotlin/dev/eatsteak/rusaint/` generated binding
- Branch: `feat/` `fix/` `refactor/` `chore/` `docs/` + kebab-case. 한 feature = 한 PR.
- Commit: Conventional Commits (`feat(mcp): ...`)

## 배포

main push → GitHub Actions (ARM64 image → `ghcr.io/hoeongj/ssumcp`) → ArgoCD Image Updater → k3s 자동 배포

- 로컬 MCP: `http://localhost:8080/mcp` (Streamable HTTP)
- 프로덕션: `https://ssumcp.duckdns.org/mcp` / Grafana: `https://ssumcp.duckdns.org/grafana`

## Credentials

1. `C:/Users/akftj/mp/myInfo.txt` — 학번·비밀번호·서버 IP 등
2. `C:/Users/akftj/mp/ssuMCP/.env` — 백엔드 환경변수 (`.env.example` 변수명 참조)
3. k8s secret: `kubectl get secret` (서버 접근 가능 시)
4. 위에 없을 때만 사용자에게 요청
