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
# http://localhost:5678 — 최초 접속 시 owner 계정(이메일/비번)을 1회 설정

# 4. 워크플로우 import
# Settings > Import Workflow > workflows/notice-discord.json
# Settings > Import Workflow > workflows/weekly-report.json
```

## 환경 변수

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `DISCORD_WEBHOOK_URL_NOTICE` | (필수) | 공지 알림 채널 Discord Webhook URL |
| `DISCORD_WEBHOOK_URL_WEEKLY` | (필수) | 주간 리포트 채널 Discord Webhook URL |
| `SSUAI_API_BASE` | `https://ssumcp.duckdns.org` | ssuMCP API 베이스 URL |

> **채널 분리**: 공지 알림과 주간 리포트를 서로 다른 Discord 채널로 보내려고 웹훅을 2개로 나눴다. 각 워크플로우는 HTTP Request 노드에서 `{{ $env.DISCORD_WEBHOOK_URL_* }}`로 자기 채널 웹훅을 읽는다.
> **n8n 2.x 주의**: `N8N_BLOCK_ENV_ACCESS_IN_NODE`가 2.0부터 기본 `true`라 노드 표현식의 `{{ $env.* }}`가 막힌다. 워크플로우가 `$env`로 웹훅·API 베이스를 읽으므로 `N8N_BLOCK_ENV_ACCESS_IN_NODE=false`가 필요하다(docker-compose·Helm 모두 반영됨).

## 포트폴리오 포인트

- **live demo 가능**: ssuMCP 실제 공개 API(`/api/notices`, `/api/meals/weekly`)를 데이터 소스로 사용
- **상태 유지 설계**: n8n Static Data로 공지 ID를 추적해 중복 알림 방지
- **관심사 분리**: 예약 상태머신/auth/resilience는 ssuMCP 코드에, 운영 자동화만 n8n에
- **n8n vs Zapier/Make**: 자체 코드 삽입(Code 노드) + 셀프호스팅으로 데이터 주권 확보

## Prod deployment

로컬 Docker Compose와 별개로, 프로덕션(k3s / Oracle A1, 네임스페이스 `ssuai-prod`)에는
Helm 차트 + ArgoCD로 배포한다.

- Helm 차트: [`deploy/charts/n8n`](../deploy/charts/n8n)
- ArgoCD Application: [`deploy/argocd/application-n8n.yaml`](../deploy/argocd/application-n8n.yaml)
- 접속: `https://ssun8n.duckdns.org` (traefik ingress + cert-manager `letsencrypt-prod`)

주요 설계:
- 단일 PVC(1Gi, `/home/node/.n8n`) + `Recreate` 전략 (상태 보존형, single-node).
- 공개 업스트림 이미지(`docker.n8n.io/n8nio/n8n:2.27.5`) 고정 태그 → argocd-image-updater 미사용.
- 비밀값은 `n8n-secrets` Secret으로 클러스터에 직접 생성(레포에 커밋하지 않음). 채널별 웹훅 2개(`DISCORD_WEBHOOK_URL_NOTICE`/`_WEEKLY`)도 여기 담는다.
- Discord 전송은 네이티브 Discord 노드 대신 **HTTP Request 노드**로 웹훅에 직접 POST → 노드 스키마 버전 의존 제거 + 웹훅 URL을 `$env`(=k8s Secret)로 주입.
- n8n 2.x는 basic auth를 제거 → **owner 계정을 env로 선언적 프로비저닝**(`N8N_INSTANCE_OWNER_MANAGED_BY_ENV`), 비밀번호는 bcrypt 해시로 `n8n-secrets`에 저장. UI 수동 셋업 없이 첫 부팅에 자동 생성(GitOps 재현성).

첫 배포 순서:
1. `n8n-secrets` Secret 생성 (명령어는 `deploy/charts/n8n/values-prod.yaml` 참고).
2. ArgoCD가 sync → n8n 기동.
3. `n8n/workflows/*.json` import. 각 워크플로우는 `$env`로 채널별 웹훅을 읽는다(공지=`DISCORD_WEBHOOK_URL_NOTICE`, 주간=`DISCORD_WEBHOOK_URL_WEEKLY`).
4. Discord 웹훅 2개를 `n8n-secrets`에 주입 후 워크플로우 활성화.

**현재 상태 (2026-07-02, 라이브)**: 웹훅 2개(`DISCORD_WEBHOOK_URL_NOTICE`/`_WEEKLY`) `n8n-secrets` 주입 완료, 두 워크플로우 모두 `active:true`. 공지 모니터링은 스케줄 1회차 실행에서 실제 Discord 전송(2xx)까지 확인됐고, 주간 리포트는 매주 월 09:00 cron으로 대기한다.

운영 노트:
- REST로 활성화하려면 `POST /rest/workflows/{id}/activate`에 해당 워크플로우의 `versionId`를 body로 보내야 한다(생략 시 실패).
- 워크플로우 삭제는 즉시 DELETE가 아니라 **archive 후 DELETE** 순서다(n8n 2.x).
