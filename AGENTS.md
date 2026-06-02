# AGENTS.md — ssuMCP

숭실대학교 MCP 서버 (Spring Boot, Kotlin/Java 21).
MCP 표준 도구로 학식 · 도서관 · 시설 · u-SAINT · LMS 정보를 노출.

## 문서

- `docs/architecture.md` — 패키지 구조, 레이어, Connector 패턴, MCP tool 목록
- `docs/security.md` — 인증 흐름, 자격증명 저장, 로그 금지 항목
- `docs/mcp-tools.md` — MCP tool 입출력 레퍼런스
- `docs/README.md` — 활성 문서와 역사 기록의 구분
- `docs/adr/` — 아키텍처 결정 기록 (0001-0018)

## 프로젝트 구조

```
src/main/
├── java/com/ssuai/           # 패키지명 com.ssuai (리팩터링 예정 없음)
│   ├── global/               # auth, config, exception, response
│   └── domain/
│       ├── auth/             # MCP 세션 인증 (McpAuthSession, McpAuthStore)
│       ├── campus/           # 시설 검색
│       ├── chat/             # 챗봇 LLM 연동
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
    └── dev/eatsteak/rusaint/         # rusaint Kotlin FFI 바인딩 (JNA)
        ├── ffi/rusaint_ffi.kt
        └── model/rusaint.kt
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

- 구현 AI는 세션 시작 직후 `.codex/current-task.md`를 먼저 읽는다.
  이 디렉터리는 로컬 작업 컨텍스트이므로 git에 커밋하지 않는다.
- 일반 작업에서 읽지 말 것: `build/`, `.gradle/`, `.idea/`, `.codex/`,
  `src/main/kotlin/dev/eatsteak/rusaint/` generated binding. 해당 영역을
  바꾸는 태스크일 때만 직접 확인한다.
- 테스트: `.\gradlew.bat test` (cwd = repo 루트, Windows) / `./gradlew test` (Linux)
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
