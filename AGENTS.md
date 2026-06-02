# AGENTS.md — ssuMCP

숭실대학교 MCP 서버 (Spring Boot, Kotlin/Java 21).
MCP 표준 도구로 학식 · 도서관 · 시설 · u-SAINT · LMS 정보를 노출.

## 문서

- `docs/architecture.md` — 패키지 구조, 레이어, Connector 패턴, MCP tool 목록
- `docs/security.md` — 인증 흐름, 자격증명 저장, 로그 금지 항목
- `docs/mcp-tools.md` — MCP tool 입출력 레퍼런스
- `docs/README.md` — 활성 문서와 역사 기록의 구분
- `docs/adr/` — 아키텍처 결정 기록 (0001-0018)

## 워크플로우 개요

**Claude = 설계·검수. Codex / AGY = 구현·배포.**

```
Claude: task → .codex/current-task.md
    ↓
Codex/AGY: "task 읽어" → 구현 → 테스트 → commit → push → PR → merge → DONE 블록 출력
    ↓
사용자: DONE 블록 → Claude 에 전달
    ↓
Claude: 수정 파일만 Read → 검수 → 다음 task 작성
    ↓
(반복)
```

**Codex 토큰 소진 → `agy -m gemini-3.5-flash` 실행 → "task 읽어" → 즉시 이어서 작업.**

## DONE 블록 — 완료 출력 형식 (필수)

구현 AI 는 작업 완료 후 **반드시** 아래 형식을 마지막에 출력:

```
=== DONE ===
요약: [무슨 작업인지 1~2줄]
테스트: ./gradlew.bat test 통과
커밋: [short-hash] [commit message]
PR: https://github.com/ghdtjdwn/ssuMCP/pull/NNN  (없으면 "없음")
머지: 완료 / 대기중

수정 파일:
  A src/.../NewClass.java
  M src/.../ModifiedClass.java
  D src/.../DeletedClass.java
=============
```

파일 목록: `git show --stat --format="" HEAD | head -30`

**Claude 는 이 목록의 파일만 Read 하여 검수한다. 나머지 파일은 읽지 않는다.**

## Credentials & 사용자 정보

필요한 정보는 아래 순서로 조회. 없을 때만 사용자에게 요청:

1. `C:/Users/akftj/mp/myInfo.txt` — 학번·비밀번호·서버 IP 등
2. `C:/Users/akftj/mp/ssuMCP/.env` — 백엔드 환경변수
3. `C:/Users/akftj/mp/ssuMCP/.env.example` — 변수 이름 참조용
4. k8s secret: `kubectl get secret` (서버 접근 가능 시)

## 구현 AI 자율 실행 범위

사용자 확인 없이 모두 실행:

| 작업 | 명령 |
|------|------|
| 테스트 | `.\gradlew.bat test` |
| 커밋 | `git commit` (Conventional Commits) |
| 푸쉬 | `git push origin <branch>` |
| PR 생성 | `gh pr create` |
| PR 머지 | `gh pr merge <N> --rebase --delete-branch` (auto-merge 조건 충족 시) |
| main 동기화 | `git checkout main && git pull --ff-only origin main` |
| CI 확인 | `gh run watch` 또는 GitHub Actions 결과 확인 |

**auto-merge 조건**: tests pass + 런타임 영향 없음 + 신규 파일 위주.
**사용자 확인 필요**: DB 마이그레이션 / prod 환경변수 변경 / 서버 재시작 / major dep bump.

## Troubleshooting 누적 (포트폴리오)

아래 기준 중 하나라도 해당하면 **즉시** `TROUBLESHOOTING.md` 에 기록. 구현 AI 도 기록 의무.

**기록 트리거**: (1) 외부 시스템이 예상과 다르게 동작 (2) 처음 가설이 틀린 경우
(3) 테스트 green인데 prod 깨짐 (4) 프레임워크 내부 우회 (5) 설계 전환 (6) 보안·인증 버그

**필수 포함** (누락 시 기록 무효):
- 처음 세운 가설(틀린 방향) / 실제 원인 / 핵심 파일·커밋
- 포트폴리오 포인트 / 면접 예상 질문 2~3개

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
│       ├── library/          # 도서관 좌석·도서·대출
│       ├── lms/              # LMS 과제
│       ├── mcp/              # McpServerConfig + 23개 @Tool 클래스
│       ├── meal/             # 학식
│       ├── notice/           # 공지사항
│       └── saint/            # u-SAINT (시간표, 성적, 채플, 졸업, 장학금)
└── kotlin/
    ├── com/ssuai/domain/saint/connector/
    │   └── RusaintUniFfiClient.kt    # rusaint JNA 호출 어댑터
    └── dev/eatsteak/rusaint/         # rusaint Kotlin FFI 바인딩 (JNA) — 수정 금지
deploy/                 # k8s Helm chart + Docker
Dockerfile              # 멀티스테이지 빌드 (Rust → JVM)
smithery.yaml           # Smithery 레지스트리 설정
```

## 핵심 패턴

- **Connector**: 외부 시스템 호출 → 내부 DTO 반환. Mock / Real 구현 분리.
  `@ConditionalOnProperty` 로 프로파일별 선택. 새 도구 = 새 Connector.
- **MCP 도구 등록 필수**: 새 `@Tool` 클래스 추가 시 반드시
  `McpServerConfig.java` `toolObjects(...)` 에 등록. 안 하면 클라이언트에 노출 안 됨.
- **Private tool 인증**: `mcp_session_id` 파라미터 + `McpAuthHelper.principalKey()`.
  세션 없으면 `AUTH_REQUIRED` + loginUrl 반환.

## 개발 규칙

- 사용자가 **"task 읽어"** 라고 하면 `.codex/current-task.md` 를 즉시 읽고 실행.
- 일반 작업에서 읽지 말 것: `build/`, `.gradle/`, `.idea/`, `.codex/`,
  `src/main/kotlin/dev/eatsteak/rusaint/` generated binding. 해당 영역을
  바꾸는 태스크일 때만 직접 확인한다.
- 테스트: `.\gradlew.bat test` (Windows) / `./gradlew test` (Linux)
- 새 MCP 도구 추가 후 배포까지:
  1. `.\gradlew.bat test` 통과
  2. Conventional Commit (`feat(mcp): ...`)
  3. PR → CI 통과 → merge → k8s 자동 배포
- Branch: `feat/` `fix/` `refactor/` `chore/` `docs/` + kebab-case
- 한 feature = 한 PR

## 배포

CI → image-build (`ghcr.io/ghdtjdwn/ssumcp`) → Deploy workflow → k8s `kubectl set image`

`KUBE_CONFIG` 시크릿은 2026-05-27 기준 설정되어 있으며 최신 Deploy
workflow 성공을 확인함. 시크릿이 제거되면 deploy job은 no-op (skip).

## MCP 엔드포인트

- 로컬: `http://localhost:8080/mcp` (Streamable HTTP)
- 프로덕션: `https://ssumcp.duckdns.org/mcp`

## 주의

- AI 코드 속성(`Co-Authored-By` 등)을 commit에 넣지 말 것
- commit author = `git config user.name` / `git config user.email` (`ghdtjdwn` / `akftjdwn@gmail.com`)
- 커밋 전 확인: `git log -1 --format='%an <%ae>'`
