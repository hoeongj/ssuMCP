# ssuMCP n8n 운영 자동화 데모

ssuMCP 공개 API를 데이터 소스로 하는 n8n 운영 자동화 워크플로우 데모.  
핵심 비즈니스 로직(예약 상태머신, auth, resilience)은 n8n에 없고 ssuMCP 서버가 담당한다. n8n은 "이미 완성된 서비스를 연결하는 운영 자동화"만 수행한다.

## 워크플로우

| 파일 | 설명 | 트리거 |
|------|------|--------|
| `notice-discord.json` | 숭실대 새 공지 감지 → Discord 알림 | 5분마다 폴링 |
| `weekly-report.json` | 주간 공지 + 학식 복합 리포트 → Discord | 매주 월요일 09:00 |

### 공지 모니터링 플로우
```
Cron(5분) → GET /api/notices → 새 공지 필터(Static Data) → Discord Webhook
```
- n8n Static Data로 "마지막으로 본 공지 ID" 유지 → pod 재시작에도 상태 보존
- `SSUAI_API_BASE` 환경변수로 API 엔드포인트 교체 가능 (로컬 테스트 지원)

### 주간 리포트 플로우
```
Cron(월09:00) → GET /api/notices + GET /api/meals/weekly → 병합 → 리포트 생성 → Discord
```
- 공지와 식단을 병렬 조회 후 Merge 노드에서 결합
- Code 노드에서 마크다운 리포트 텍스트 생성

## 실행 방법 (로컬 Docker Compose)

```bash
# 1. 환경 변수 설정
cp .env.example .env
# .env에 Discord Webhook URL 입력

# 2. n8n 시작
docker compose up -d

# 3. 접속
# http://localhost:5678  (admin / .env의 N8N_PASSWORD)

# 4. 워크플로우 import
# Settings > Import Workflow > workflows/notice-discord.json
# Settings > Import Workflow > workflows/weekly-report.json
```

## 환경 변수

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `N8N_PASSWORD` | (필수) | n8n 관리자 비밀번호 — 기본값 없음, `.env`에 직접 설정 |
| `DISCORD_WEBHOOK_URL` | (필수) | Discord Webhook URL |
| `SSUAI_API_BASE` | `https://ssumcp.duckdns.org` | ssuMCP API 베이스 URL |

## 포트폴리오 포인트

- **live demo 가능**: ssuMCP 실제 공개 API(`/api/notices`, `/api/meals/weekly`)를 데이터 소스로 사용
- **상태 유지 설계**: n8n Static Data로 공지 ID를 추적해 중복 알림 방지
- **관심사 분리**: 예약 상태머신/auth/resilience는 ssuMCP 코드에, 운영 자동화만 n8n에
- **n8n vs Zapier/Make**: 자체 코드 삽입(Code 노드) + 셀프호스팅으로 데이터 주권 확보
