# postgres chart — GitOps 편입 (adoption) 안내

지금까지 prod의 PostgreSQL(`ssuai-prod` 네임스페이스, Deployment `postgres`)은
수동 `kubectl apply`로 배포되어 있었다(last-applied-configuration은 있지만
git에는 대응하는 소스가 없었음). 이 차트는 그 리소스를 그대로 ArgoCD 관리 하로
"편입(adopt)"하기 위한 것이다 — 새로 만드는 게 아니라 기존 오브젝트에
동일한 이름/셀렉터/라벨로 덮어 적용해 ArgoCD가 소유권만 넘겨받게 한다.

## 첫 sync 전 확인할 것

1. **셀렉터 불변성**: `spec.selector.matchLabels`가 정확히 `app: postgres` 여야
   한다. 다르면 Kubernetes가 Deployment 업데이트를 거부해 ArgoCD가
   delete+recreate로 처리하게 되고, 불필요한 다운타임이 생긴다.
2. **PVC/Secret은 이 차트가 만들지 않는다**: `postgres-pvc`, `ssuai-db-secret`은
   이미 존재하며 실제 데이터/자격증명을 담고 있다. 차트는 이름으로만
   참조한다(`persistence.existingClaim`, `existingSecret.name`).
3. **첫 sync는 DB 파드를 재시작시킨다**: `strategy: Recreate` 이므로(단일
   PVC ReadWriteOnce라 RollingUpdate는 볼륨 마운트에서 교착 상태에
   빠진다) ArgoCD가 이 차트를 처음 적용하는 순간 기존 파드가 종료되고
   새 파드가 뜬다. 이미지가 라이브와 동일(`pgvector/pgvector:pg17`)하고
   env/마운트가 동일하면 데이터 손실은 없지만, **약 10~30초의 DB
   다운타임**은 예상해야 한다.

## Sync 정책: manual (자동 아님)

이 차트의 ArgoCD Application(`deploy/argocd/application-postgres.yaml`)은
n8n/backend와 달리 `syncPolicy.automated`를 켜지 않았다. 이유:

- 편입 첫 sync는 살아있는 DB 파드를 재시작시키는 유일한 종류의 sync다 — 다른
  차트들의 "새 이미지 롤아웃"과 리스크 급이 다르다.
- 렌더링된 매니페스트가 라이브 스펙과 정확히 일치하는지(`argocd app diff
  postgres`) 사람이 먼저 확인한 뒤 수동으로 sync를 눌러야, 셀렉터 불일치로
  인한 예기치 않은 recreate를 사전에 잡을 수 있다.
- 안정화 확인 후(수 차례 정상 sync) `syncPolicy.automated`로 전환해 다른
  앱들과 동일한 자율 운영 수준으로 승격하는 것을 후속 작업으로 고려한다.

## 첫 sync 절차

```bash
# 1. diff부터 — 라이브와 다른 점이 selector/label/env 등 위험 필드가 아닌지 확인
kubectl -n argocd exec deploy/argocd-application-controller -- \
  argocd app diff postgres --local deploy/charts/postgres

# 2. 문제 없으면 수동 sync
argocd app sync postgres

# 3. 롤아웃 확인
kubectl -n ssuai-prod rollout status deployment/postgres --timeout=120s

# 4. 백엔드 헬스 확인 (DB 재연결 성공 여부)
curl -i https://ssumcp.duckdns.org/actuator/health
```

`rollout status`가 끝나고 `/actuator/health`가 200을 반환하면 편입 완료.
