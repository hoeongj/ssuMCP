# ADR 0030: LMS Video Transcript MCP Tools

## 배경 (Background)

- 숭실대학교 LMS는 `commons.ssu.ac.kr`의 uni-player 기반 강의 영상을 제공한다. 교수자가 직접 녹화한 강의는 Canvas LearningX 활동과 Commons 콘텐츠 서버에 나뉘어 저장된다.
- 일부 강의에는 `caption_list_({storyGuid}).xml` 형태의 자막 파일이 있지만, 모든 강의가 자막을 제공하지는 않는다.
- MCP tool로 강의 내용을 텍스트화하면 강의 요약, Q&A, 시험 전 복습, agent 기반 학습 보조로 확장할 수 있다. 포트폴리오 관점에서는 단순 CRUD가 아니라 학교 SSO 세션, CDN 인증, XML/JSON 파싱, 미디어 처리, STT 폴백을 한 흐름으로 연결하는 설명 가능한 기능이다.

## 검토한 대안 (Alternatives considered)

- **AssemblyAI**: URL 입력 기반 전사 API가 편하지만 Commons CDN은 LMS 세션 쿠키가 필요한 경우가 있어 외부 서비스가 MP4 URL을 직접 가져가기 어렵다. 서버가 영상을 먼저 다운로드해 다시 업로드해야 하므로 현재 선택지 대비 단순성이 떨어진다.
- **Whisper API**: 정확도와 안정성은 좋지만 별도 과금 부담이 있다. 이번 기능은 사용자가 수동 호출하는 MCP 도구이고 기존 Groq 키를 재사용할 수 있으므로 비용 대비 설득력이 낮다.
- **범용 멀티모달 audio 모델**: 강의 전사 전용 평가와 운영 한계가 명확하지 않다. STT 전용 엔드포인트보다 실패 시 원인 분석과 테스트가 어려워 제외했다.
- **Groq `whisper-large-v3-turbo`**: 속도는 빠르지만 정확도 우선 포트폴리오 기능에서는 표준 `whisper-large-v3`가 더 설명하기 좋다. 자막 없는 강의는 수동 호출이고 수 분 대기 가능성을 tool description에 명시했으므로 속도보다 정확도를 우선했다.
- **자막 전용 (STT 없음)**: 비용과 구현은 가장 작지만 자막 없는 강의가 많으면 실사용 가치가 급격히 낮아진다. "가능한 강의만 지원"이 되어 포트폴리오 완성도가 떨어진다.

## 결정 (Decision)

- **자막 우선 + Groq `whisper-large-v3` STT 폴백**을 선택한다.
  - 자막이 있으면 `caption_list` XML을 파싱한다. 즉시 반환되고 API 비용이 없다.
  - 자막이 없으면 서버가 MP4를 임시 파일로 다운로드하고 FFmpeg로 16kHz mono MP3 오디오를 추출한 뒤 Groq STT로 전사한다.
- **FFmpeg 청킹**은 10분 단위(`chunk-duration-seconds=600`)로 둔다. Groq 문서는 큰 오디오 파일에 대해 chunking을 권장하고, FFmpeg는 입력 파일 변환과 구간 추출을 표준 기능으로 제공한다.
- **동기 MCP tool로 유지**한다. 영상 전사는 사용자가 명시적으로 호출하는 긴 작업이며, 이번 범위에서는 작업 큐/DB 상태 머신을 추가하지 않는다. 비동기 job은 장기적으로 frontend 진행률 표시가 필요할 때 별도 ADR로 다룬다.
- **SemaphoreBulkhead는 추가하지 않는다.** 현 기능은 자동 배치가 아니라 수동 호출이고, 현재 서버 리소스에서는 테스트 가능한 단순 경로가 더 중요하다. 운영에서 동시 호출 문제가 확인되면 기존 Resilience4j 패턴으로 확장한다.

## 작동 방식 (How it works)

- `get_my_lecture_list`
  1. `LmsSessionStore`에서 학생별 LMS 쿠키를 조회한다.
  2. Canvas LearningX API로 현재 term을 찾는다.
  3. term의 course 목록을 가져온다.
  4. 각 course에 대해 `modules`, `xncontent_list`, Commons `xncontent_list` 후보 엔드포인트를 순서대로 probing한다.
  5. JSON 구조가 강의별로 달라질 수 있어 `content_id`, `xn_content_id`, 유사 필드와 URL query를 보수적으로 탐색한다.

- `get_lecture_transcript`
  1. `content.php?content_id=...`를 호출해 `storyGuid`, MP4 파일명, media URL template, `web_files` URL, duration을 얻는다.
  2. `web_files` 아래의 세 caption 후보 경로를 시도한다.
  3. 자막 XML이 있으면 `text`, `Text`, `CaptionText`, TTML `p`를 파싱해 plain text로 반환한다.
  4. 자막이 없고 Groq key가 있으면 MP4를 임시 파일로 다운로드한다.
  5. FFmpeg가 `/tmp/ssuai-audio-*.mp3` 오디오 chunk를 만든다.
  6. 각 chunk를 `/audio/transcriptions` multipart 요청으로 전사하고 합친다.
  7. video/audio temp file은 `finally`에서 항상 삭제한다.

## 근거와 출처 (Sources)

- Groq Speech to Text 문서: 큰 오디오 파일 또는 정밀 제어가 필요한 경우 chunking을 권장한다. `https://console.groq.com/docs/speech-to-text`
- Groq Whisper Large v3 문서: multilingual STT와 accuracy-first 모델 선택 근거로 사용했다. `https://console.groq.com/docs/model/whisper-large-v3`
- FFmpeg 공식 문서: FFmpeg는 범용 media converter이며 입력 파일의 audio/video 변환과 구간 추출에 적합하다. `https://ffmpeg.org/ffmpeg.html`

## 검증 (Validation)

- `CommonsContentClientTests`: `content.php` XML fixture에서 story GUID, MP4 filename, media template, web files URL, duration, MP4 URL 조합을 검증한다.
- `CaptionXmlParserTests`: 한국 LMS caption XML 두 형태와 malformed XML graceful fallback을 검증한다.
- `GroqSttClientTests`: WireMock multipart endpoint 성공/500 실패, API key blank 상태를 검증한다.
- `LmsVideoServiceTests`: caption 우선, STT 폴백, STT 미설정, 세션 만료, temp file cleanup을 검증한다.
