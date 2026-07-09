# ADR 0084 — LMS export ZIP 저장소를 단일노드 공유 PVC로 고정

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-07-10 |
| 상태 | Accepted |
| 범위 | `LmsExportBuildWorker`, `lms_export_jobs`, `deploy/charts/ssuai-backend` |
| 연관 문서 | ADR 0033(LMS ZIP export), ADR 0067(1회용 다운로드 토큰), ADR 0078(2 OCPU/12GB 용량 계획), ADR 0079(멀티포드 row claim/lease) |

---

## 문제 정의

LMS export job과 다운로드 토큰 메타데이터는 `lms_export_jobs` 테이블에 저장된다. `token_hash`, 상태, 만료 시각은 DB가 단일 진실원이다. 그러나 실제 ZIP 파일은 worker pod의 로컬 디스크에 만들어졌다.

backend replica를 2개로 늘리면 다음 문제가 생긴다.

- pod A가 ZIP을 만들고 DB job을 `READY`로 바꾼 뒤, 사용자의 다운로드 요청이 pod B로 라우팅되면 pod B의 파일시스템에는 ZIP이 없어 404/410 계열 오류가 난다.
- pod 재시작 중 `jobId.zip` 또는 중간 파일이 남으면 새 pod가 그 로컬 디스크를 볼 수 없어 정리가 지연된다.
- ADR 0079의 row claim/lease는 "누가 job을 빌드할지"는 해결하지만, "완성된 파일을 어느 pod가 서빙할지"는 해결하지 않는다.

## 대안 비교

| 대안 | 장점 | 단점 | 판단 |
|---|---|---|---|
| S3/MinIO 오브젝트 스토리지 | pod 수와 노드 수에 무관하게 동일한 object key로 접근 가능. presigned URL/수명주기 정책으로 확장 가능 | self-hosted MinIO는 pod, PVC, 백업, 모니터링이 추가된다. ADR 0078의 12GB 예산에서 최소 약 +512Mi 메모리를 잡아야 하는데, 현재 단일노드에서는 같은 디스크 위에 MinIO를 얹을 뿐이라 내구성 이득이 0이다 | 멀티노드 전환 전까지 기각 |
| DB BLOB 저장 | DB만 보면 되므로 pod 간 파일 404가 없다. 백업 단위가 단순하다 | 2GB급 ZIP이 PostgreSQL row/TOAST와 백업 크기를 급격히 키운다. 다운로드 스트리밍 부하가 DB connection과 I/O를 점유한다. 만료 파일 정리도 VACUUM 비용으로 남는다 | 기각 |
| 공유 PVC | 기존 k3s local-path provisioner와 Helm chart만으로 구현 가능. 파일 스트리밍은 DB가 아니라 노드 디스크가 담당한다. pod 재시작 후에도 같은 PVC를 다시 마운트해 만료 정리를 재시도할 수 있다 | local-path PVC는 RWO, node-local이다. 같은 노드의 여러 pod에는 충분하지만, 여러 노드에 분산 스케줄링되면 같은 PVC를 모든 노드에서 read-write로 붙일 수 없다 | 선택 |

## 결정

`ssuai-backend` chart에 LMS export 전용 PVC를 유지하고, Deployment가 `SSUAI_LMS_EXPORT_TEMP_DIR`와 같은 경로에 마운트한다.

- PVC: `{{ include "ssuai-backend.fullname" . }}-lms-export`
- access mode: `ReadWriteOnce`
- 기본 storage class: `local-path`
- 기본 크기: `5Gi`
- mount path 및 애플리케이션 temp dir: `/data/lms-export`

현재 운영은 단일노드 k3s이므로 `ReadWriteOnce` PVC를 backend pod 2개가 같은 노드에서 동시에 read-write 마운트할 수 있다. 이 조건에서는 pod A가 만든 ZIP을 pod B도 같은 path에서 읽을 수 있으므로 멀티포드 404가 사라진다.

멀티노드로 전환하거나 backend pod가 서로 다른 노드에 스케줄링되는 구성이 되면 이 결정은 자동으로 만료된다. 그 시점에는 다음 중 하나를 다시 선택해야 한다.

- RWX가 가능한 스토리지 클래스(NFS, CephFS 등)
- 외부 S3
- self-hosted MinIO

트리거는 명확히 둔다: `ssuai-backend`가 2개 이상의 노드에 분산 배치될 수 있는 node pool/affinity 변경, 또는 prod k3s를 멀티노드로 전환하는 PR에서는 이 ADR을 재검토해야 한다.

## 정리 규칙

완성 ZIP의 생명주기는 DB job 상태와 만료 시각을 따른다.

- `READY`/`DOWNLOADED`이고 `expires_at`이 지나지 않은 파일은 유지한다.
- `BUILDING`이고 claim lease가 살아 있는 job의 `jobId.zip`은 다른 pod가 정리하지 않는다.
- `expires_at`이 지난 DB row는 `EXPIRED`로 전환하고 파일 삭제를 재시도한다. 이미 `EXPIRED`인 row도 파일이 남아 있으면 삭제를 재시도한다.
- 공유 디렉터리에 있는 `UUID.zip` 중 DB row가 없거나 더 이상 유지할 상태가 아닌 파일은 orphan으로 보고 삭제한다.

이 규칙은 ADR 0079의 claim/lease와 함께 동작한다. claim은 중복 빌드를 막고, 공유 PVC는 어느 pod가 다운로드 요청을 받아도 같은 ZIP을 보게 하며, sweeper는 pod 재시작 뒤 남은 파일을 회수한다.

## 트레이드오프

- 단일노드라는 운영 가정을 코드 밖 인프라 계약으로 둔다. chart 주석과 이 ADR이 그 계약의 기록이다.
- local-path PVC 장애는 곧 ZIP cache 손실이다. 다만 export ZIP은 원본 데이터가 아니라 20분 TTL의 재생성 가능한 artifact이므로 PostgreSQL이나 사용자 세션만큼 강한 내구성을 요구하지 않는다.
- MinIO를 지금 넣지 않으므로 멀티노드 전환 시 storage migration 작업이 남는다. 대신 현재 2 OCPU/12GB 예산에서 +512Mi급 상시 메모리 비용과 운영 표면을 만들지 않는다.
- PVC 크기 5Gi는 임시 ZIP cache 기준이다. export 한도나 동시 job 수를 키우는 PR에서는 PVC 크기와 정리 cadence를 같이 재검토해야 한다.

## 예상 면접 질문

1. **왜 S3/MinIO가 아니라 PVC를 선택했나?**  
   현재 장애는 "두 pod가 같은 단일노드 디스크를 보지 못함"이다. 단일노드 k3s에서는 RWO PVC를 두 pod가 같은 노드에서 공유 마운트해 해결할 수 있다. MinIO는 같은 노드 디스크 위에 한 계층을 더 얹을 뿐이라 내구성 이득은 없고, 메모리와 운영 복잡도만 늘어난다.

2. **RWO PVC를 replica 2에서 써도 안전한가?**  
   같은 노드에 있는 여러 pod가 하나의 RWO volume을 read-write로 마운트하는 것은 가능하다. 안전 조건은 "단일노드"다. 멀티노드에서 pod가 다른 노드로 분산되면 RWX 스토리지나 오브젝트 스토리지로 바꿔야 한다.

3. **공유 디렉터리 sweeper가 빌드 중인 파일을 지우지 않는 근거는?**  
   DB row의 `BUILDING` 상태와 `claimed_at` lease를 본다. lease가 살아 있는 job의 `jobId.zip`은 유지하고, 만료되었거나 DB row가 없는 managed ZIP만 삭제한다.
