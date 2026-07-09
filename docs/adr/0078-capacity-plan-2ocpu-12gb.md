# ADR 0078 — Oracle 무료티어 축소 대응 용량 계획 (2 OCPU/12GB 단일노드)

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-07-09 |
| 상태 | Accepted — 용량 계획 확정(예산표·트림 순서·컨틴전시 절차 문서화). 각 차트 `resources.requests/limits` 반영과 실제 노드 재생성은 후속 커밋 |
| 범위 | 클러스터 전역 리소스 예산(전 워크로드 requests/limits), VM 강제 축소 시 재생성 런북 |
| 연관 문서 | ADR 0007(prod-deploy-oracle-k3s), ADR 0008(gitops-argocd-helm), ADR 0069(observability-three-pillars), ADR 0071(event-pipeline-outbox-not-kafka), `mp/SCALE-ROADMAP.md` |

---

## 배경

ADR 0007에서 backend를 올린 Oracle Cloud Always Free Ampere A1은 "영구 무료 4 OCPU / 24 GB RAM"을 전제로 한 결정이었다. 그런데 2026-06-15 Oracle이 사전 공지 없이 Always Free Ampere A1 한도를 **4 OCPU/24 GB → 2 OCPU/12 GB**로 축소했다 — 콘솔 배너나 이메일이 아니라 공개 문서(pricing/free-tier 페이지)만 조용히 바뀌는 방식이었다.

- <https://www.infoq.com/news/2026/07/oracle-cloud-free-tier-limits/>
- <https://linuxiac.com/oracle-quietly-cuts-free-tier-ampere-a1-resources-in-half/>

축소된 조건은 계정당 월 quota **1,500 OCPU-hours / 9,000 GB-hours**다. 현재 VM은 여전히 4 OCPU/24 GB로 가동 중이지만, 이 사양을 한 달 내내 켜두면 4 OCPU × 730h ≈ 2,920 OCPU-hrs, 24 GB × 730h ≈ 17,520 GB-hrs로 **새 quota를 이미 초과**한다. 즉 지금 상태는 "아직 안 잘렸을 뿐" — 언제든 예고 없이 강제 축소되거나 인스턴스가 정지될 수 있는 리스크를 안고 있다.

포트폴리오의 "영구 무료" 서사(ADR 0007)를 지키는 한 유료 전환은 최후 수단이어야 하고, 그 전에 **2 OCPU/12 GB 단일노드**를 실제 운영 예산으로 못 박아 전 스택을 right-size해야 한다.

## 검토한 대안

### ① 멀티노드 분할 (1 OCPU/6 GB × 2) ❌

Oracle Free Tier A1 총 한도(4 OCPU/24 GB → 2 OCPU/12 GB)를 노드 두 대로 쪼개는 방식. 노드마다 kubelet·containerd·CNI·시스템 예약 오버헤드가 다시 붙기 때문에, 6 GB 노드에서 실사용 가능한 몫은 산술 평균(2 OCPU/6 GB)보다 훨씬 작아진다. 워크로드를 노드 간에 쪼개면 특정 노드가 기아 상태가 되기 쉽고(예: DB pod가 있는 노드만 메모리 압박), k3s의 노드 간 오버레이 네트워크 비용까지 더해져 오버헤드 비중이 급격히 커진다. 단일노드 대비 관리 복잡도만 늘고 실질 가용 자원은 줄어드는 손해 거래.

### ② 유료 노드 추가 (~$5–10/월) ❌

가장 쉬운 해법이지만 ADR 0007의 "영구 무료" 서사를 정면으로 훼손한다. 학생 포트폴리오로서 "취업 후에도 비용 없이 계속 살아있는 데모"라는 스토리가 이 결정 하나로 무너진다. 다만 완전히 폐기하지는 않는다 — 트림(아래 §4)까지 다 써도 안 되는 순간이 오면 그때 다시 꺼낼 옵션으로만 남겨둔다.

### ③ 현상 유지 (4 OCPU/24 GB로 계속 운영) ❌

Oracle이 공지 없이 문서만 바꾼 전례를 이미 한 번 겪었다. 강제 축소·재기동·인스턴스 회수가 예고 없이 올 수 있는데, 그 시점에 가서야 대응하면 전 스택이 동시에 멈춘 채로 right-sizing과 장애 복구를 동시에 해야 한다. 리스크를 알면서 방치하는 선택.

### ④ 채택 — 단일노드 2 OCPU/12 GB 예산으로 전 스택 right-sizing ✅

Oracle이 실제로 시행 중인 한도에 선제적으로 맞춘다. 오늘 강제로 잘려도 스택이 이미 그 예산 안에서 돌고 있으므로 무중단으로 넘어간다. ADR 0007의 단일노드 구조(k3s + Traefik + ArgoCD)를 그대로 유지하면서, 신규/기존 컴포넌트마다 `requests`/`limits`를 명시적으로 예산 항목으로 관리한다.

## 메모리 예산

12 GB 노드에서 OS/kubelet 시스템 예약을 감안한 **request 기준 예산표**. Requests 합계는 스케줄링 가능 총량이고, limits는 개별 컴포넌트가 스파이크 시 쓸 수 있는 상한(오버커밋 허용)이다.

| 컴포넌트 | request | limit | 비고 |
|---|---|---|---|
| OS + k3s + Traefik + cert-manager | ~1.5G | (고정) | 시스템 예약, 워크로드 아님 |
| ssuMCP backend ×2 | 512Mi × 2 | 1Gi × 2 | 무중단 롤링(maxUnavailable 0)을 위한 최소 2replica |
| Redis | 64Mi | 128Mi | 캐시 전용, 영속 데이터 소량 |
| PostgreSQL | 512Mi | 1Gi | 단일 인스턴스, self-hosted |
| ssuAgent | 256Mi | 512Mi | |
| gateway | 128Mi | 256Mi | |
| Prometheus | 512Mi | 768Mi | ADR 0069 관측성 스택 |
| Grafana | 256Mi | 512Mi | ADR 0069 |
| Loki + Promtail | 384Mi | 512Mi | ADR 0069 |
| Tempo | 256Mi | 384Mi | ADR 0069 |
| ArgoCD | ~400Mi | (경량화 설정) | ADR 0008, 불필요 컴포넌트 축소 적용 |
| Kafka(KRaft) | 768Mi | 1.25Gi | 예정 — 이벤트 파이프라인 확장 시 |
| OpenSearch | 1Gi | 1.5Gi | 예정 — 로그 검색 확장 시 |
| 로그 컨슈머 | 192Mi | 256Mi | 예정 |
| Cilium | ~448Mi | ~768Mi | 예정 — CNI 교체 시(현재는 k3s 기본 flannel) |
| MinIO | 0 (기각) | — | 신규 오브젝트 스토리지 대신 기존 PVC 공유로 대체, 예산 배정 안 함 |

- 예산 산식: 표의 항목별 request 합계 ≈ **7.6G**(시스템 예약 1.5G 포함) + k3s 기본 애드온(coredns/kube-proxy/metrics-server/local-path-provisioner)·커널/kubelet 버퍼에 별도 배정한 ~**1.9G** = 계획 총량 ≈ **9.5G**. 12G 중 순수 headroom은 약 **2.3G**다. 표만 합산하면 7.6G로 보이지만, 표 밖의 시스템 애드온 몫 1.9G까지가 이미 배정된 예산이므로 신규 컴포넌트가 쓸 수 있는 진짜 여유는 2.3G뿐이다.
- Kafka/OpenSearch/로그 컨슈머/Cilium 행은 "예정" — 아직 미배포이며, 배포 시점에 그만큼 headroom을 깎아먹는다는 것을 미리 예산에 박아둔 것. 이 네 항목이 한꺼번에 들어오면 headroom이 마이너스가 되므로, 실제 도입 순서는 하나씩·트림 여유를 확인하며 진행한다.
- MinIO는 LMS export 아티팩트의 멀티포드 공유 저장용으로 검토했으나, 단일노드에서는 기존 local-path PVC 공유 마운트로 충분하고 별도 오브젝트 스토리지 pod가 주는 내구성 이득이 없어 기각했다(멀티노드 전환 시 S3/MinIO 재검토 트리거).

## 미적합 시 트림 순서

headroom을 다 써도 부족해지면(신규 컴포넌트 온보딩, 트래픽 스파이크 등) 아래 순서로 되돌린다. 앞 단계일수록 포트폴리오 핵심 서사에서 먼 컴포넌트다.

1. **n8n 정지** — 운영 편의용 자동화, 서비스 핵심 경로가 아니므로 가장 먼저 내린다.
2. **Tempo 축소** — 트레이스는 3-pillar 관측성(ADR 0069) 중 상대적으로 디버깅 보조 용도가 커서, replica/retention을 줄이거나 일시 정지한다.
3. **OpenSearch → Loki 싱크 폴백(-1.2G)** — 로그 검색을 OpenSearch 풀텍스트에서 Loki 기본 라벨 검색으로 되돌려 OpenSearch pod를 내린다.
4. **Hubble relay 제거** — Cilium 도입 시점에 함께 들어올 관측 컴포넌트로, CNI 자체 기능(트래픽 forwarding)에는 영향 없이 가시성만 잃는다.

## 강제 축소 컨틴전시 (VM 재생성 절차)

Oracle이 예고 없이 인스턴스를 강제로 축소/회수하는 최악의 경우, GitOps(ArgoCD, ADR 0008) 덕에 클러스터 상태 자체는 전부 git에 있다. 절차:

1. **PG dump + PVC tar 백업** — `pg_dump`로 논리 백업, 나머지 stateful PVC(Redis는 캐시라 생략 가능)는 tar로 스냅샷 떠서 로컬/오브젝트 스토리지에 보관.
2. **새 VM(2 OCPU/12GB) 생성** — Oracle 콘솔에서 동일 shape로 재프로비저닝, 네트워크/보안 그룹 설정 재적용.
3. **k3s 재설치** — 기존과 동일 버전으로 단일노드 k3s 설치, Traefik/cert-manager 기본 애드온 활성화.
4. **ArgoCD 재부트스트랩** — 전체 매니페스트가 GitOps로 git에 있으므로, ArgoCD만 새로 설치하고 기존 Application/AppProject를 다시 apply하면 나머지는 auto-sync가 알아서 복원한다.
5. **데이터 복원** — PG dump restore, PVC tar 복원 후 각 stateful pod 재시작으로 데이터 정합성 확인.
6. **DNS/Tailscale 전환** — duckdns A 레코드를 새 VM IP로 갱신, Tailscale 노드도 새 VM에서 재등록.

## 트레이드오프

**얻는 것**: 실제 Oracle 한도 안에서 이미 동작 중이므로 강제 축소가 와도 무중단으로 넘어간다. 비용은 계속 0으로 유지된다.

**잃는 것**: headroom이 2.3G로 빠듯해지면서 트래픽 스파이크나 컴포넌트 재시작이 겹치는 순간에 취약해진다. 신규 컴포넌트(Kafka, OpenSearch, Cilium 등)를 들일 때마다 "어디를 트림할지"를 먼저 결정해야 하는 예산 협상 비용이 매번 발생한다 — 이전엔 24G 여유 안에서 그냥 추가하면 됐던 일이 이제는 §4 트림 순서를 먼저 소비하거나 headroom 재계산을 거쳐야 하는 일이 됐다.

## 예상 면접 질문

1. **Oracle이 공지 없이 free tier를 축소했을 때 왜 유료 전환이 아니라 right-sizing을 택했나?** 포트폴리오의 핵심 서사가 "영구 무료 인프라 위의 실 서비스"이기 때문에, 비용을 들이는 순간 그 서사가 깨진다. 트림 순서(§4)와 강제 축소 런북(§5)까지 준비해두면 비용 없이도 강제 축소를 버틸 수 있다는 게 이 ADR의 핵심 주장이다.
2. **멀티노드로 쪼개지 않고 단일노드를 유지한 이유는?** 노드를 나누면 노드별 시스템 오버헤드(kubelet/containerd/CNI)가 다시 붙어 총 가용 자원이 줄고, 워크로드를 인위적으로 분산 배치해야 해서 특정 노드가 기아 상태가 되기 쉽다. 단일노드가 오히려 오버헤드 비중이 작다.
3. **headroom 2.3G를 왜 "여유"가 아니라 "예산 항목"으로 취급하나?** coredns/kube-proxy/metrics-server 같은 k3s 기본 애드온과 커널/kubelet 버퍼가 이 안에서 실제로 소비되기 때문에, 이 수치를 진짜 여유로 착각하고 새 컴포넌트를 추가하면 곧바로 OOM/Evict가 난다.
4. **강제 축소가 실제로 발생하면 다운타임은 얼마나 예상하나?** GitOps 구조 덕분에 매니페스트 복원은 ArgoCD auto-sync로 자동화되지만, VM 재프로비저닝·k3s 재설치·DNS 전파 시간은 수동 단계라 수십 분~수 시간 단위 다운타임은 불가피하다. 이 ADR의 목표는 그 이전 단계(강제 축소 자체)를 최대한 피하는 것이다.
