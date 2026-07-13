# `deploy/` production deployment runbook

This is the operator guide for ssuMCP's production environment:

- Spring Boot backend on a single-node k3s cluster on Oracle Cloud Free Tier ARM Ampere A1.
- Next.js frontend on Vercel.
- TLS via cert-manager + Let's Encrypt.
- MCP server image registry at `ghcr.io/ghdtjdwn/ssumcp`.
- GitOps rollout via ArgoCD + Helm.
- Observability via kube-prometheus-stack, Prometheus, Alertmanager, and Grafana.

Current live endpoints:

- Frontend: `https://ssuai.vercel.app`
- Backend: `https://ssumcp.duckdns.org`
- MCP Streamable HTTP: `https://ssumcp.duckdns.org/mcp`
- ArgoCD UI: `https://argo-ssuai.duckdns.org`
- Grafana UI: `https://ssumcp.duckdns.org/grafana`

Architecture rationale lives in:

- [`docs/adr/0007-prod-deploy-oracle-k3s.md`](../docs/adr/0007-prod-deploy-oracle-k3s.md)
- [`docs/adr/0008-gitops-argocd-helm.md`](../docs/adr/0008-gitops-argocd-helm.md)

The directory layout:

```text
deploy/
├── argocd/
│   ├── application-ssuai-backend.yaml
│   ├── application-monitoring.yaml
│   ├── image-updater/
│   ├── ingress.yaml
│   ├── README.md
│   └── values.yaml
├── charts/
│   ├── monitoring/
│   └── ssuai-backend/
├── cluster-bootstrap/
│   └── clusterissuer.yaml
├── scripts/
└── README.md
```

`deploy/charts/ssuai-backend/` is the source of truth for backend Kubernetes
resources. The old raw `deploy/k8s/` manifests were retired after the chart
rendered and passed client-side Kubernetes dry-run validation.
The container image is built from the repository-root `Dockerfile` used by CI.
`deploy/generated/` is reserved for ignored, local break-glass renders.

---

## 0. Prerequisites

| What | Where | Notes |
|---|---|---|
| Oracle Cloud account | <https://cloud.oracle.com> | Free tier signup needs a valid credit card. Use `ap-seoul-1` if you want Korea Central. |
| DuckDNS subdomains + token | <https://duckdns.org> | Point `ssumcp` and `argo-ssuai` at the VM public IP. |
| Vercel account | <https://vercel.com> | Frontend deploys from the `ssuAI` repository root. |
| Local tools | your laptop | `kubectl`, `helm`, `ssh`, optional `argocd` CLI. |
| GitHub PAT | GitHub UI | Fine-grained PAT scoped to `contents:write` on `ghdtjdwn/ssuMCP` only, used by Image Updater. |

You also need a real email address for Let's Encrypt expiration notices.

---

## 1. Provision the Oracle Cloud VM

In the Oracle Cloud console:

1. Create an Ubuntu 22.04 LTS aarch64 instance.
2. Shape: `VM.Standard.A1.Flex`, 4 OCPUs / 24 GB memory.
3. Enable a public IPv4 address.
4. Add your SSH public key.
5. Use a boot volume up to 100 GB.

Open inbound TCP ports `22`, `80`, and `443` in the VCN security list or NSG.
Then SSH in and enable UFW:

```bash
ssh ubuntu@<VM_PUBLIC_IP>
sudo ufw allow OpenSSH
sudo ufw allow 80
sudo ufw allow 443
sudo ufw enable
```

---

## 2. Point DuckDNS at the VM

Set these subdomains to the VM public IP:

- `ssumcp.duckdns.org`
- `argo-ssuai.duckdns.org`

Verify:

```bash
dig +short ssumcp.duckdns.org
dig +short argo-ssuai.duckdns.org
```

Install a DuckDNS updater cron on the VM. Keep the token root-owned and never
commit it:

```bash
sudo tee /etc/cron.d/duckdns >/dev/null <<'EOF'
*/5 * * * * root curl -sk -o /dev/null \
  "https://www.duckdns.org/update?domains=ssumcp,argo-ssuai&token=<YOUR_TOKEN>"
EOF
sudo chmod 600 /etc/cron.d/duckdns
```

---

## 3. Install k3s and cert-manager

Install k3s:

```bash
ssh ubuntu@<VM_PUBLIC_IP>
curl -sfL https://get.k3s.io | sh -
sudo k3s kubectl get nodes
```

Copy `/etc/rancher/k3s/k3s.yaml` to your laptop kubeconfig and replace
`https://127.0.0.1:6443` with `https://<VM_PUBLIC_IP>:6443`.

Install cert-manager:

```bash
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/latest/download/cert-manager.yaml
kubectl wait --for=condition=available --timeout=120s \
  -n cert-manager deploy/cert-manager deploy/cert-manager-webhook deploy/cert-manager-cainjector
```

Apply the cluster-wide issuer once:

```bash
kubectl apply -f deploy/cluster-bootstrap/clusterissuer.yaml
```

Before applying, replace `REPLACE_WITH_OPERATOR_EMAIL@example.com` in a local
copy or render it with `deploy/scripts/prepare-live-deploy.ps1`.

---

## 4. Make the ghcr.io image pullable

The CI image-build job pushes
`ghcr.io/ghdtjdwn/ssumcp:sha-<full-sha>` for normal pushes to `main` and for
manual `workflow_dispatch` runs. The workflow ignores pushes that only update
`deploy/charts/ssuai-backend/values.yaml`, because those are ArgoCD Image
Updater write-back commits. Otherwise the auto-generated image pin commit
would build a new image for itself and cause another image pin commit.

If the package is public, no pull secret is needed. If it is private, create a
`dockerconfigjson` Secret and wire `imagePullSecrets` into the chart in a
separate change.

---

## 5. Bootstrap ArgoCD

Use the pinned upstream charts documented in [`argocd/README.md`](argocd/README.md).

```bash
helm repo add argo https://argoproj.github.io/argo-helm
helm repo update

kubectl create namespace argocd
helm upgrade --install argocd argo/argo-cd \
  --version 9.5.12 \
  --namespace argocd \
  --values deploy/argocd/values.yaml

kubectl apply -f deploy/argocd/ingress.yaml
```

Rotate the initial ArgoCD admin password immediately after first login:

```bash
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath="{.data.password}" | base64 -d
```

---

## 6. Enable the backend Application

Create the Image Updater git write-back Secret outside the repo. Use
`deploy/argocd/image-updater/secret.example.yaml` only as the shape:

```bash
kubectl -n argocd create secret generic argocd-image-updater-git-creds \
  --from-literal=username=argocd-image-updater \
  --from-literal=password=<GITHUB_FINE_GRAINED_PAT> \
  --dry-run=client -o yaml | kubectl apply -f -
```

Apply the backend Application and install Image Updater:

```bash
kubectl apply -f deploy/argocd/application-ssuai-backend.yaml

helm upgrade --install argocd-image-updater argo/argocd-image-updater \
  --version 0.14.0 \
  --namespace argocd \
  --values deploy/argocd/image-updater/values.yaml
```

ArgoCD now owns the backend resources rendered from
`deploy/charts/ssuai-backend/`.

Apply the monitoring Application after the monitoring manifests are on `main`:

```bash
kubectl create namespace monitoring --dry-run=client -o yaml | kubectl apply -f -
kubectl -n monitoring create secret generic grafana-admin \
  --from-literal=admin-user=admin \
  --from-literal=admin-password=<GRAFANA_ADMIN_PASSWORD> \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f deploy/argocd/application-monitoring.yaml
```

ArgoCD then owns Prometheus, Grafana, Alertmanager, kube-state-metrics,
node-exporter, and the ServiceMonitor that scrapes `/actuator/prometheus`
from the backend Service.

Current monitoring checks:

```bash
kubectl -n argocd get applications.argoproj.io monitoring
kubectl -n monitoring get pods
kubectl -n monitoring get ingress,certificate
kubectl -n ssuai-prod get servicemonitor
curl -I https://ssumcp.duckdns.org/grafana/login
curl https://ssumcp.duckdns.org/actuator/prometheus | head
```

Grafana is exposed under the existing backend host at
`https://ssumcp.duckdns.org/grafana`. Do not create a separate
`grafana-ssumcp.duckdns.org` hostname unless the DuckDNS record is also
created and pointed at the VM.

Retrieve the generated Grafana admin password from the cluster, not from git:

```powershell
kubectl -n monitoring get secret grafana-admin -o jsonpath="{.data.admin-password}" |
  % { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($_)) }
```

---

## 7. GitOps upgrade workflow

Normal deploy path:

1. Push to `main`.
2. CI builds and pushes `ghcr.io/ghdtjdwn/ssumcp:sha-<full-sha>`.
3. ArgoCD Image Updater detects the newest SHA tag.
4. Image Updater writes the tag back to `deploy/charts/ssuai-backend/values.yaml`.
5. ArgoCD reconciles the chart and rolls the backend Deployment.

Watch status:

```bash
kubectl -n argocd get applications.argoproj.io ssuai-backend
kubectl -n argocd logs deploy/argocd-image-updater --tail=100
kubectl -n ssuai-prod rollout status deploy/ssuai-backend
kubectl -n ssuai-prod get pods,svc,ingress
```

Rollback is a git revert of the image-bump commit. ArgoCD reconciles back to
the previous chart value.

> **Current state (2026-06-06)**: the live single-node k3s cluster has
> ArgoCD, the backend Application, and the monitoring Application installed.
> The monitoring Application is `Synced`/`Healthy`, Grafana is available at
> `/grafana`, and Prometheus scrapes the backend ServiceMonitor successfully.
> GitHub Actions auto-deploy remains documented as a fallback path for backend
> image rollouts if GitOps is unavailable.

### 7.1 Manual deploy (break-glass fallback)

After CI's `image-build` job publishes `ghcr.io/ghdtjdwn/ssumcp:sha-<full-sha>`:

```bash
SHA=$(git rev-parse origin/main)
sudo kubectl set image deployment/ssuai-backend \
  backend=ghcr.io/ghdtjdwn/ssumcp:sha-${SHA} \
  -n ssuai-prod

sudo kubectl rollout status deployment ssuai-backend -n ssuai-prod --timeout=360s

# Confirm new image is what is actually running:
sudo kubectl get pod -n ssuai-prod \
  -o jsonpath='{.items[*].spec.containers[*].image}'; echo
```

The Deployment manifest is currently pinned to `:latest` with
`imagePullPolicy: IfNotPresent`, so a plain `rollout restart` does
**not** pick up the new image — the explicit `set image` is required.

### 7.2 GitHub Actions auto-deploy (current default)

`.github/workflows/deploy.yml` runs `kubectl set image` automatically
after CI succeeds on `main`. `KUBE_CONFIG` is provisioned in repository
secrets; the latest Deploy workflow was verified successful on 2026-05-27.
If the secret is absent after a future rotation, the workflow logs a skip
notice and makes no production change.

To provision or rotate the secret:

1. On the cluster machine, copy the kubeconfig and base64-encode it:

   ```bash
   sudo cat /etc/rancher/k3s/k3s.yaml | base64 -w 0 > /tmp/kubeconfig.b64
   # If the server address inside is 127.0.0.1, replace it with the
   # public DNS first:  sed 's#127.0.0.1#ssumcp.duckdns.org#' …
   cat /tmp/kubeconfig.b64
   ```

   Make sure the embedded `server:` field is reachable from the public
   internet (GitHub-hosted runners) — usually `https://ssumcp.duckdns.org:6443`
   plus the matching port exposed via firewall.

2. In GitHub → Repo Settings → Secrets and variables → Actions →
   **New repository secret**:
   - Name: `KUBE_CONFIG`
   - Value: the base64 blob from step 1.

3. Smoke test: push a no-op commit to `main` and watch the **Deploy**
   workflow run. The first run after secret provisioning should
   succeed; without the secret it logs a skip notice and exits 0.

Security notes:

- The kubeconfig grants whatever RBAC the embedded user has. Prefer
  creating a scoped ServiceAccount (`patch deployments` on `ssuai-prod`
  namespace only) and embedding *its* kubeconfig, not the cluster-admin
  `k3s.yaml`.
- Rotate `KUBE_CONFIG` whenever a maintainer leaves or the cluster CA
  rotates.
- The workflow uses `workflow_run` triggered by CI completion, so a
  failed CI run does not deploy.

---

## 8. Verify production

```bash
curl -i https://ssumcp.duckdns.org/actuator/health
curl https://ssumcp.duckdns.org/actuator/prometheus | head
curl https://ssumcp.duckdns.org/api/meals/today | jq .

curl -I -H "Origin: https://attacker.example" \
  https://ssumcp.duckdns.org/api/meals/today
```

The final CORS check should not echo `Access-Control-Allow-Origin`.

---

## 9. Emergency break-glass

Use this only when GitOps itself is broken.

1. Disable auto-sync from the ArgoCD UI or CLI.
2. Render the chart locally.
3. Apply the rendered backend manifests manually.
4. Fix git and ArgoCD.
5. Re-enable auto-sync after `argocd app diff ssuai-backend` is clean.

Local render helper:

```powershell
powershell -ExecutionPolicy Bypass -File deploy/scripts/prepare-live-deploy.ps1 `
  -BackendHost ssumcp.duckdns.org `
  -FrontendOrigin https://ssuai.vercel.app `
  -OperatorEmail <YOUR_EMAIL> `
  -Image ghcr.io/ghdtjdwn/ssumcp:sha-<full-sha>
```

Manual apply helper:

```powershell
powershell -ExecutionPolicy Bypass -File deploy/scripts/apply-live-deploy.ps1
```

---

## 10. Common troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Pod stays `ImagePullBackOff` | ghcr.io package is private or tag missing | Make the package public, add pull credentials, or check the SHA tag. |
| `Certificate` stuck `READY=False` | DNS not propagated or port 80 blocked | Check DuckDNS, Oracle VCN/NSG, and UFW. |
| ArgoCD Application is `OutOfSync` | Git and cluster differ | Inspect `argocd app diff ssuai-backend`, then sync or fix git. |
| Image Updater logs credential errors | PAT Secret missing or wrong scope | Recreate `argocd-image-updater-git-creds` with `contents:write`. |
| Grafana certificate challenge is pending | Hostname has no DuckDNS record or port 80 is blocked | Prefer existing `ssumcp.duckdns.org/grafana`, or create the DuckDNS record before changing the Ingress host. |
| Prometheus has no backend target | ServiceMonitor not installed or selector mismatch | Check `kubectl -n ssuai-prod get servicemonitor ssuai-backend -o yaml` and Prometheus targets. |
| Backend 502 from ingress | Service/Deployment selector or pod readiness issue | Check `kubectl -n ssuai-prod describe ingress,svc,pod`. |
| CORS fails in browser | `SSUAI_FRONTEND_ORIGIN` mismatch | Update chart values and let ArgoCD reconcile. |

---

## 11. Cost reminders

- Oracle Always Free ARM capacity is generous but not guaranteed forever.
- Bandwidth is far above this MVP's expected traffic, but still monitor it.
- ArgoCD adds real pods; check node pressure after installing observability.
