# ADR 0007 — Production deploy: k3s on Oracle Cloud Free Tier + Vercel

- **Status**: Accepted (Task 06 merged; live at https://ssumcp.duckdns.org with Vercel frontend at https://ssuai.vercel.app)
- **Date**: 2026-05-07
- **Scope**: `deploy/`, `backend/.../WebCorsConfig.java`, `application-prod.yml`, `frontend/` Vercel project, `.github/workflows/ci.yml` image-build job

## Context

Task 05 의 frontend MVP 가 머지되면서 ssuAI 는 backend + frontend 가 로컬에서
end-to-end 로 동작하는 상태가 되었습니다. 다음 narrative beat 는 "실제
infrastructure 위에서 살아있다" — 즉 라이브 데모 URL 입니다. 이 결정 없이는
프로젝트가 포트폴리오로서의 신빙성을 잃습니다.

요구사항은 다음과 같았습니다.

1. **영구 무료** 운영. 학생 portfolio 라 월 비용을 감당할 수 없고, 1~3년 후
   취업 면접에서도 데모 URL 이 살아있어야 합니다.
2. **한국 region**. Backend 가 숭실대 사이트를 긁어오므로 connector latency
   예산을 지키려면 인근 region 이 필요합니다.
3. **포트폴리오 임팩트**. 단순히 "PaaS 에 클릭해서 배포" 가 아니라, 한국
   채용 시장에서 의미 있는 시그널 — Linux + container + Kubernetes +
   GitOps + observability — 을 한 프로젝트에서 보여줄 수 있어야 합니다.
4. **Spring Boot + Next.js** 라는 stack 의 특성. Backend 는 stateful
   process (cache, scraping rate-limit), frontend 는 Next.js 라
   first-party platform 이 별도로 존재합니다.

후보군을 위 4가지 기준으로 평가했습니다.

## Decision

**Backend 는 Oracle Cloud Free Tier ARM Ampere A1 (`ap-seoul-1`) 위의
single-node k3s 클러스터에 배포합니다. Frontend 는 Vercel 에 배포합니다.**

세부 결정:

- **Cluster**: k3s (lightweight Kubernetes, ~100 MB RAM 오버헤드).
  Bundled Traefik ingress + ServiceLB 사용. 한 VM 한 클러스터로 시작하고,
  필요 시 Task 07+ 에서 multi-node 로 확장.
- **TLS**: cert-manager + Let's Encrypt prod (HTTP-01 challenge).
  자동 갱신.
- **Domain**: `ssuai-api.duckdns.org` (duckdns 무료 dynamic DNS). 향후
  custom domain 으로 swap 은 ingress YAML 한 줄 + cert-manager 재발급.
- **Image registry**: GitHub Container Registry (`ghcr.io`). Public repo
  무료, Docker Hub free tier 의 pull rate-limit 회피.
- **Image build**: GitHub Actions 가 `main` push 마다 ARM64 image 를
  빌드하고 `:<sha>` + `:latest` 로 push.
- **Deploy**: 이번 task 는 수동 `kubectl apply`. ArgoCD GitOps 는 Task 07.
- **Frontend**: Vercel 의 GitHub 연동 자동 deploy. 환경변수
  `NEXT_PUBLIC_SSUAI_API_BASE` 만 prod backend URL 로 설정.
- **Prod CORS**: `WebCorsConfig` 의 `@Profile("prod")` 변형이
  `SSUAI_FRONTEND_ORIGIN` env var 한 개만 명시적으로 allowlist. Wildcard
  도, Vercel preview 서브도메인도 허용하지 않음.

## Consequences

**좋은 점**

- **영구 무료**가 진짜로 영구. Oracle Free Tier ARM 은 신용카드 인증만
  하면 만료 없이 4 OCPU / 24 GB RAM / 200 GB 스토리지 / 10 TB egress 를
  계속 쓸 수 있습니다.
- **Seoul region**. 숭실대 사이트와 같은 권역 안에 backend 가 있어
  connector latency 예산이 빡빡해지지 않습니다.
- **K8s 표면 그대로 노출**. `Deployment` / `Service` / `Ingress` /
  `ConfigMap` / `Secret` / `ClusterIssuer` / `Certificate` —
  포트폴리오 narrative 에서 가장 묵직한 6개를 다 다루게 됩니다.
- **확장 경로가 자연스러움**. ArgoCD (Task 07), Prometheus + Grafana
  + Loki (Task 08), Postgres + Redis pod (auth task) 가 같은 클러스터에
  layer 처럼 쌓입니다. 각 task 마다 narrative beat 1개씩.
- **Frontend / backend 분리 deploy**. Vercel 의 first-party Next.js
  지원 (edge cache, image optimization, preview deploy) 을 그대로 받고,
  K8s narrative 는 backend 에 집중. 두 platform 모두 1군 사용 경험을
  보여줄 수 있습니다.
- **Public ghcr.io image 가 supply chain 투명성 narrative**. 누구나
  `docker pull ghcr.io/<user>/ssuai-backend:<sha>` 로 같은 이미지 검증 가능.

**대가**

- **Setup 시간 1~2일**. Cloud Run 이라면 30분이면 끝날 일이 Oracle 계정
  생성 + VM provisioning + k3s 설치 + cert-manager + ingress + duckdns +
  ghcr.io + GitHub Actions 까지 포함하면 하루 걸립니다.
- **Single-node 클러스터의 한계**. 노드 자체 장애 = 전체 다운. Free
  tier 의 ARM 인스턴스가 가끔 reclaim 되는 사례도 보고됩니다 (드물지만
  발생). 재배포 runbook 이 `deploy/README.md` 에 살아있어야 하는 이유.
- **운영 책임이 본인에게**. 패치, OS 업데이트, k3s upgrade, cert-manager
  upgrade — managed 서비스라면 platform 이 해줄 일을 직접 합니다. 1년
  단위로 재방문해야 합니다.
- **수동 deploy (이번 task)**. SHA 를 manifest 에 박아 `kubectl apply`
  하는 워크플로는 production-grade 가 아닙니다. Task 07 의 ArgoCD 까지
  가야 portfolio 가 완성됩니다.
- **Free tier 제약을 spec 에 박아넣음**. 향후 비용을 들여 GKE / EKS 로
  옮기더라도 k3s manifest 가 거의 그대로 동작하지만, 노드 풀, RBAC,
  network policy 등 prod-grade 디테일은 다시 손봐야 합니다.

## Alternatives considered

- **Google Cloud Run + Supabase + Upstash** — GCP serverless stack.
  영구 무료 (각 free tier 안에서) 가능하고 Tokyo region 이 있어 latency
  도 합격선. 단점: narrative 가 "managed runtime 클릭 deploy" 로 얇음.
  K8s API surface 를 다루지 않으므로 portfolio 에서 보여줄 수 있는
  운영 스킬이 적습니다.
- **GKE Autopilot** — Managed K8s. 90일 $300 크레딧 동안 무료지만 그
  이후엔 control plane 비용 ~$73/월 이 발생해 "영구 무료" 조건을 위반.
  졸업 후 1~2년이 portfolio 의 가장 활발한 사용 구간인데 그때 데모
  URL 이 죽어있으면 의미가 없습니다.
- **Fly.io** (Tokyo region) — Modern indie infra, Dockerfile 만으로
  배포 가능, free tier 영구. 단점: 한국 채용 시장에서 인지도가 낮고
  narrative 가 "PaaS 클릭 deploy" 에 가까움. K8s manifest 도 안
  다루게 됨.
- **Railway** — 한국 학생 사이에서 흔하지만 free tier 가 사실상 $5
  크레딧으로 고갈성이고 region 이 US 뿐. 영구 무료 + Seoul 두 조건을
  모두 깸.
- **AWS ECS Fargate / App Runner** — AWS 시그널이 한국 채용 시장에서
  가장 강하지만, Fargate 는 무료 tier 가 없고 App Runner 는 active
  hour 단위로 과금되어 영구 무료 불가.
- **Self-hosted on Oracle ARM with Docker Compose only (k3s 없이)** —
  Setup 이 쉽지만 narrative 가 "Linux + Docker" 까지로만 닿고 K8s
  스킬 시그널이 없어집니다. 이 프로젝트의 portfolio 가치를 극대화하지
  못합니다.

## Open questions / future tasks

- **Vercel preview deploy 의 CORS** — `*.vercel.app` 의 회전 서브도메인을
  prod backend 가 어떻게 받아들일지. 정책 결정이 필요한 별도 사안 (regex
  allowlist vs. 별도 staging backend vs. 무시). 이번 task 에서는 production
  Vercel origin 한 개만 allowlist.
- **Custom domain** (`ssuai.app` 등) — 연 $1~$10. 영구 무료 조건에서
  벗어나므로 별도 결정. 사용자가 비용을 부담할 의사가 생기면 ingress
  hostname 한 줄 + cert-manager 재발급으로 swap.
- **Multi-replica + HPA** — 현재 트래픽 (개발자 + 리뷰어 2명) 에는
  과합니다. Task 08 의 Prometheus 가 들어온 다음 metrics-server 위에서
  HPA 를 켜는 것이 자연스럽습니다.
- **Backup / DR** — single-node + DB 없음 = 백업 대상이 없음. Postgres
  pod 가 들어오는 task 부터 정책 필요 (pg_basebackup → object storage).
- **WAF / DDoS** — Cloudflare 무료 layer 를 ingress 앞에 둘지 여부.
  학생 portfolio 에 실용 가치는 낮지만 narrative 로는 좋음. 우선순위 낮음.
