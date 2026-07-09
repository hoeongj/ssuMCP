# ADR 0088 — Multi-replica HA: replicas=2 + HPA(2~3) + PDB(minAvailable=1)

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-07-10 |
| 상태 | Accepted — 구현 |
| 범위 | `deploy/charts/ssuai-backend` (Deployment strategy, HPA, PDB), `deploy/argocd/application-ssuai-backend.yaml` |
| 연관 ADR | 0078(capacity-plan-2ocpu-12gb), 0079(claim-lease), 0080(shared-ratelimit), 0081(spend-breaker), 0083(fresh-read), 0084(lms-export-shared-pvc), 0085(cb-read-write-split), 0086(confirm-async) |

---

## 문제 정의

Phase 1의 앞선 유닛(0079~0086)은 전부 "언젠가 replica가 2개 이상이 되어도 안전하도록" pod 간
공유 상태(claim/lease, Redis rate limit, breaker, PVC, 안정적 principal)를 준비하는 작업이었다.
그런데 `replicaCount`는 여전히 1로 고정돼 있었다 — 즉 **그 안전장치들이 실제로는 한 번도 멀티포드
경로를 타지 않은 상태**였다. 단일 포드로 운영하면 두 가지 문제가 남는다.

1. **단일포드 병목**: 포드 하나가 처리할 수 있는 동시 요청 수가 곧 서비스 전체의 처리량 상한이다.
2. **무중단 배포 불가**: replica가 1개면 롤링 업데이트 중 반드시 "기존 포드 종료 → 신규 포드 Ready"
   사이에 요청을 받을 포드가 0개인 순간이 생긴다(`maxUnavailable: 0`이 애초에 강제 불가능한 제약이
   된다).

## 검토한 대안

### ① 수직 확장 (backend 1 포드의 request/limit만 키운다) ❌

`resources.requests.memory`를 512Mi보다 키우는 방식. ADR 0078 예산표에서 backend 항목은 이미
"×2, 512Mi"로 무중단 롤링을 전제로 잡혀 있어, 수직 확장은 그 전제와 충돌한다. 또한 단일 포드
구조는 롤링 업데이트 중 무중단이 원천적으로 불가능한 문제(§문제정의 ②)를 전혀 해결하지 못한다 —
포드가 커져도 여전히 1개이므로 재시작 순간의 공백은 그대로 남는다. 기각.

### ② 현상 유지 (replicaCount=1) ❌

0079~0086에서 이미 들인 pod-safety 비용(claim/lease, Redis lock, dual-cap 등)을 실제로 회수하지
못한다. 배포·장애 복구 시마다 무조건 다운타임이 발생하는 것도 그대로 남는다. 기각.

### ③ 채택 — replicas 2(정상 상태) + HPA(min 2 / max 3, CPU 70%) + PDB(minAvailable 1) ✅

Deployment `replicaCount`를 2로 올리고, HPA로 순간 CPU 스파이크에 한해 3까지 자동 확장하며, PDB로
자발적 축출(node drain, ArgoCD 강제 재시작 등) 상황에서도 최소 1개 포드가 항상 남도록 보장한다.
0079~0086이 만들어 둔 pod-safety 인프라를 그대로 소비하는 방향이며, ADR 0078 예산표가 이미 이
구성(×2)을 전제로 짜여 있었다.

## 단일노드 + RWO PVC 제약과 surge 전략 근거

`persistence.lmsExport`(ADR 0084)는 `local-path` StorageClass의 `ReadWriteOnce` PVC 하나를
`deploy/charts/ssuai-backend/templates/deployment.yaml`의 단일 `volumes[].persistentVolumeClaim`으로
모든 replica가 공유 마운트한다(replica별 PVC가 아니다 — StatefulSet의 `volumeClaimTemplates`가
아니라 Deployment의 공유 volume).

**RWO는 "포드 1개"가 아니라 "노드 1개"로 마운트를 제한한다.** 같은 노드 위의 여러 포드는 동일 RWO
PVC를 동시에 읽고 쓸 수 있다 — 이 클러스터는 애초에 노드가 1개뿐이므로(ADR 0078), 스케줄러가 두
번째·세 번째 replica를 다른 노드로 보낼 방법 자체가 없다. 즉 "RWO가 멀티 마운트를 막는다"는 통념은
멀티노드 클러스터에서나 문제이고, 이 클러스터에서는 애초에 성립하지 않는 제약이다.

이 사실이 `strategy.rollingUpdate.maxSurge: 1, maxUnavailable: 0`(deployment.yaml, 초기 릴리스부터
존재했으나 `replicaCount=1`일 때는 사실상 미작동 — 지금 이 유닛에서 `replicaCount=2`로 올리면서
비로소 의미가 생긴다) 설정이 안전한 이유다. 롤링 업데이트 중 최대 `2(기존) + 1(surge)` = 3개
포드가 잠깐 동시에 뜰 수 있는데, RWO PVC가 이를 막지 않는다는 것을 위 근거로 확인했다. 대안으로
`maxUnavailable: 1, maxSurge: 1`(구 포드를 먼저 하나 내림)도 검토했으나, 이는 순간적으로 가용
포드를 1개로 줄여 무중단 목표(§문제정의 ②)를 스스로 깨는 선택이라 채택하지 않았다 — RWO 제약이
실제로는 존재하지 않으므로 굳이 가용성을 낮출 이유가 없다.

## 메모리 예산 재확인 (ADR 0078 대조)

ADR 0078의 예산표는 **이미 backend 항목을 "×2, 512Mi request / 1Gi limit"로 계획**해 뒀다
("무중단 롤링(maxUnavailable 0)을 위한 최소 2replica"). 즉 `replicaCount: 2`는 새로운 예산 항목이
아니라, 이미 배정된 예산을 실제로 사용하는 것뿐이다 — 계획 총량(≈9.5G)·headroom(≈2.3G)에 변화 없음.

HPA `maxReplicas: 3`은 이 예산 밖의 순간적 추가 소비다. 3번째 포드가 뜨면 request 기준
+512Mi(0.5G)가 추가로 필요하므로, headroom은 스케일아웃이 실제로 발동하는 짧은 구간 동안만
**2.3G → 1.8G**로 줄어든다. HPA가 min(2)으로 복귀하면 즉시 원복되므로 상시 예산에는 반영하지
않았지만, headroom이 빠듯한 시점(ADR 0078 §4 트림 순서 진행 중 등)에 스케일아웃이 겹치면 실제 여유가
1.8G보다 더 낮아질 수 있다는 점은 트림 순서 검토 시 함께 고려해야 한다.

## seat-sampler 멀티포드 안전성 — 조사 결론: 이미 안전함

`LibrarySeatSampleSampler.sampleScheduled()`는 ADR 0079(claim/lease)의 대상 목록에 없어서 별도로
조사했다. 결과: **이미 Redis 분산 락으로 가드돼 있다** — 신규 유닛 필요 없음.

- `sampleScheduled()`는 매 `@Scheduled` 호출마다 `LibrarySchedulerLeadership.runIfLeader("seat-sampler", ...)`를
  거친다. 이는 `RedissonLibraryDistributedLockClient`(Redisson `RLock.tryLock`, 기본 watchdog lease)로
  구현된 **호출 단위 상호 배제** 락이다. 같은 순간 두 포드가 동시에 락을 잡고 샘플링을 실행하는 경우는
  없다.
- `librarySchedulerLockWait: 0ms`(values.yaml)라 락을 못 잡은 포드는 대기 없이 즉시 스킵한다(로그:
  `library scheduler lock skipped`).
- Redis 자체가 불능이면 `runWithoutLock`으로 폴백해 락 없이 실행한다(기존 동작, 이번 유닛에서 변경
  없음). 이 폴백 구간에서는 이론상 중복 샘플링이 가능하지만, 그 순간 이미 Pyxis 외부 호출은
  ADR 0080의 shared dual-cap rate limiter가 별도로 보호하므로 업스트림 과다 호출로 번지지는 않는다.
- **잔여 뉘앙스(차단 사유는 아님)**: 이 락은 "포드 A가 리더로 계속 남는" 리더 선출이 아니라
  "매 tick마다 이긴 포드가 그 tick만 실행"하는 방식이다. 두 포드의 `@Scheduled(fixedDelay=...)`는
  각자 기동 시각 기준으로 독립적으로 도는데, 샘플링 자체는 수 초 안에 끝나고 락도 즉시 해제되므로,
  포드 A의 tick(t)과 포드 B의 tick(t+Δ, Δ < cadence)이 둘 다 락을 잡는 데 성공할 수 있다. 정확히 같은
  `sampledAt` 값의 **중복 행**은 생기지 않지만(각 실행이 자기 `sampledAt`을 새로 찍음), cadence(5분)
  보다 실질 샘플링 빈도가 최대 2배(replica 2) ~ 3배(HPA 상한 3)까지 올라갈 수 있다. 데이터 손상이나
  DB 유니크 제약 충돌은 없고(레포지토리는 매 실행마다 새 배치 insert), 시계열 데이터 밀도만 늘어나는
  방향이라 기능상 안전으로 판정했다. 다른 3개 스케줄 잡(`LibraryRoomOccupancyHourlyRollupJob`,
  `LibrarySeatSamplePartitionMaintenance`, `DataRetentionJob`)도 같은 `runIfLeader` 패턴을 쓰고,
  `LibraryReservationEventRelay`/`LmsExportBuildWorker`는 ADR 0079의 claim/lease를 쓴다 — 조사한
  스케줄 작업 중 pod-safety 가드가 없는 것은 없었다(SSE registry·세션 store 등 나머지 `@Scheduled`는
  포드-로컬 인메모리 상태 정리라 애초에 크로스포드 조정이 필요 없다).

## 구현 선택

- **`autoscaling.enabled` 플래그(default true)**: HPA를 chart 값으로 켜고 끌 수 있게 해서, ADR 0078
  §4 트림이 필요한 상황이 오면 코드 삭제 없이 `false`로 즉시 되돌릴 수 있게 했다.
- **HPA는 `autoscaling/v2` + `Resource(cpu, Utilization)`만 사용**: 커스텀 메트릭 어댑터(Prometheus
  Adapter 등) 없이 k3s 기본 `metrics-server`만으로 동작하는 가장 단순한 형태를 택했다. 커스텀
  메트릭 기반 스케일링(예: `http_server_requests` 기반)은 이 단일노드 규모에서 오버엔지니어링이라
  범위 밖으로 남긴다.
- **PDB `minAvailable: 1`(백분율이 아닌 절대값)**: `maxReplicas: 3`이라도 최소 보장선은 항상 1개
  포드로 고정하는 것이 의도이므로, replica 수에 비례하는 백분율(`50%` 등)보다 절대값이 의도를 더
  정확히 표현한다.
- **ArgoCD `ignoreDifferences: /spec/replicas`(`application-ssuai-backend.yaml`)**: 이 Application은
  `syncPolicy.automated.selfHeal: true`다. HPA가 poll 사이 `spec.replicas`를 2→3으로 바꾸면,
  `ignoreDifferences` 없이는 ArgoCD가 다음 sync(기본 3분 주기)에서 git의 `replicaCount: 2`로 즉시
  되돌려 HPA와 GitOps가 서로 되돌리기를 반복한다("HPA vs GitOps selfHeal 전쟁"은 Argo 커뮤니티에서
  잘 알려진 실패 패턴). HPA를 실제로 동작시키려면 이 설정이 HPA 도입과 반드시 같이 들어가야 한다는
  점을 구현 중 확인했다 — 원래 작업 지시서에는 없던 항목이라 별도 implementation-level 결정으로
  기록한다.

## 트레이드오프

**얻는 것**: 처리량 상한이 포드 1개에서 2~3개로 올라가고, 롤링 업데이트·자발적 축출 상황에서
무중단이 실제로 보장된다. 0079~0086에서 미리 들인 pod-safety 비용을 이제야 회수한다.

**잃는 것**: ADR 0078의 headroom 여유(2.3G)가 HPA 스케일아웃 순간 1.8G까지 줄어드는 구간이 생긴다
— 신규 컴포넌트 온보딩과 스케일아웃이 겹치면 예산 협상이 더 빡빡해진다. seat-sampler의 실질 샘플링
빈도가 최대 3배까지 늘어날 수 있어(§조사 결론), 장기적으로는 cadence 값 자체를 replica 수 인지형으로
바꾸거나 진짜 리더 선출(term 기반)로 승격하는 후속 검토가 있을 수 있다 — 지금은 데이터 손상 위험이
없어 범위 밖으로 미룬다.

## 검증

- `helm lint deploy/charts/ssuai-backend` — 통과.
- `helm template deploy/charts/ssuai-backend` (기본값), `--set replicaCount=2 --set autoscaling.enabled=true`,
  `--set autoscaling.enabled=false --set podDisruptionBudget.enabled=false` — 세 조합 모두 렌더링 성공.
  HPA(`autoscaling/v2`)·PDB(`policy/v1`) 매니페스트가 플래그에 따라 렌더/생략되는 것, `replicas: 2`가
  Deployment에 반영되는 것, PVC가 replica별로 분리되지 않고 단일 공유 마운트로 남는 것을 확인.
- `load-tests/k6/replica-scale-comparison.js`를 존재하지 않는 주소로 짧게 실행해 옵션 파싱·threshold
  평가·ramping-arrival-rate executor가 정상 동작하는지 스모크 테스트.

## 예상 면접 질문

1. **RWO PVC인데 어떻게 replica를 2개로 늘렸나?** RWO는 "포드 1개"가 아니라 "노드 1개" 제약이다.
   이 클러스터는 원래 노드가 1개뿐이라(ADR 0078) 두 포드가 다른 노드로 갈 방법이 없고, 따라서 RWO
   제약이 실질적으로 걸리지 않는다. 멀티노드로 전환하는 순간에는 이 가정이 깨지므로 ADR 0084가 이미
   그 트리거를 남겨 뒀다.
2. **HPA를 켰는데 GitOps(ArgoCD selfHeal)와 충돌하지 않았나?** 충돌한다 — `ignoreDifferences`
   없이 HPA만 추가했다면 selfHeal이 매 sync마다 replicas를 git 값으로 되돌려 HPA가 사실상 무력화됐을
   것이다. `application-ssuai-backend.yaml`에 `/spec/replicas`를 ignore하도록 추가해서 두 컨트롤러의
   책임 범위를 분리했다.
3. **seat-sampler처럼 스케줄 작업이 있는 서비스를 멀티포드로 늘릴 때 무엇부터 점검하나?**
   "이 작업이 외부 부작용(API 호출, DB write, 알림 발송)을 내는가"와 "여러 포드가 동시에 크론 틱을
   맞으면 무슨 일이 일어나는가"를 먼저 확인한다. 이번 조사에서는 이미 Redis 분산 락(`runIfLeader`)
   또는 행 단위 claim/lease(ADR 0079) 둘 중 하나로 전부 가드돼 있는 것을 확인했고, 가드가 없는
   나머지 스케줄 작업은 포드-로컬 인메모리 상태 정리뿐이라 애초에 조정이 필요 없었다.
