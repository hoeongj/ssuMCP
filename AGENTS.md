# AGENTS.md - ssuMCP

숭실대학교 MCP 서버 (Spring Boot, Kotlin/Java 21).

## 프로젝트 구조

- `src/` - Spring Boot 소스
- `src/main/kotlin/dev/eatsteak/rusaint/` - rusaint Kotlin FFI 바인딩
- `deploy/` - k8s Helm chart + Docker
- `Dockerfile` - 멀티스테이지 빌드
- `smithery.yaml` - Smithery 레지스트리 설정

## 개발 규칙

- 테스트: `./gradlew test` (cwd = repo 루트)
- 새 MCP 도구 추가 시 반드시 `McpServerConfig.java`의 `toolObjects(...)`에 등록
- Commit: Conventional Commits (`feat(mcp):`, `fix(saint):` 등)
- Branch: `feat/` `fix/` `refactor/` `chore/` `docs/` + kebab-case
- 한 feature = 한 PR

## 배포

CI -> image-build (`ghcr.io/hoeongj/ssumcp`) -> Deploy workflow -> k8s `kubectl set image`

`KUBE_CONFIG` 시크릿이 없으면 deploy job은 no-op (skip).

## 주의

- AI 코드 속성(`Co-Authored-By` 등)을 commit에 넣지 말 것
- commit author = `git config user.name`/`git config user.email` (`hoengj` / `akftjdwn@gmail.com`)
