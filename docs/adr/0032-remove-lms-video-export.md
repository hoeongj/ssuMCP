# ADR 0032 — LMS 동영상 강의 전사 기능 제거

- **Status**: Accepted
- **Date**: 2026-06-16

## 배경

ADR-0030에서 구현된 LMS 동영상 MP4 다운로드 → FFmpeg 오디오 추출 → Groq Whisper STT 전사 파이프라인을
현재 코드베이스에서 완전히 제거한다.

## 제거 이유

1. **저작권·ToS 리스크**: 강의 MP4를 서버에서 직접 다운로드·처리하는 행위는 숭실대 LMS 이용약관 위반
   소지가 있으며, 포트폴리오에서 "강의 영상을 긁는 툴"로 보여 면접에서 부정적 인상을 줄 수 있다.
2. **운영 부담**: Docker runtime에 ffmpeg가 필요하고, Groq STT API 호출 비용이 발생하며,
   대용량 MP4 임시 저장으로 디스크·메모리 부담이 증가한다.
3. **가치 대비 위험**: 대용량 미디어 파이프라인을 운영하는 비용·리스크 대비 실용 가치가 낮다.

## 검토한 대안

| 대안 | 평가 |
|---|---|
| 현 기능 유지 | 리스크·비용 대비 가치 낮음. 탈락. |
| 자막 XML만 유지 (MP4 다운로드 제거) | 여전히 외부 commons 서버 의존, 구조 복잡. 탈락. |
| 전체 제거 + 비영상 자료 ZIP (ADR-0033) | 책임 있는 자동화, 저작권 위험 없음, 포트폴리오 가치 높음. 채택. |

## 결정

- `domain/lms/video/` 패키지 전체(service/connector/util/properties/dto) 삭제.
- `LmsVideoMcpTool` 삭제. `get_my_lms_terms`는 이미 존재하는
  `LmsAssignmentsService.fetchTerms()` → `LmsAssignmentsMcpTool`로 재배치(동일 Canvas endpoint, 행동 변화 없음).
- Dockerfile에서 `ffmpeg` 제거.
- Helm ConfigMap에서 `SSUAI_CONNECTOR_LMS_VIDEO` 제거.
- `SSUAI_GROQ_API_KEY`는 LLM 챗 provider 공유 키이므로 **유지**.
- 깃 히스토리 보존 (force-push/rebase 금지).

## 결과

- 영상 전사 기능 사용 불가. 대신 비영상 강의자료 ZIP 내보내기(ADR-0033)로 대체.
- Docker 이미지 용량 감소, 외부 의존성 단순화.
- get_my_lms_terms는 이름 그대로 유지되어 기존 클라이언트(ssuAgent 포함) 호환 유지.

## 근거와 출처

- AGENTS.md Rule 2: 포트폴리오 가치 > 트렌드 부합 > 완성·증명 가능성.
- 설명 불가한 저작권 리스크는 포트폴리오에서 치명적 약점.
