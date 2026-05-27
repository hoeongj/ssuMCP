# ArgoCD GitOps bootstrap

This directory contains the cluster-side GitOps control plane for ssuAI.

Pinned upstream charts:

- `argo/argo-cd` chart `9.5.12`
- `argo/argocd-image-updater` chart `0.14.0`

The Image Updater chart is intentionally pinned to the annotation-based
release line used by `application-ssuai-backend.yaml`. Revisit the 1.x CRD
model in a dedicated migration task.

## Bootstrap order

1. Install cert-manager and the cluster bootstrap manifests:

   ```bash
   kubectl apply -f deploy/cluster-bootstrap/clusterissuer.yaml
   ```

2. Add the Argo Helm repo:

   ```bash
   helm repo add argo https://argoproj.github.io/argo-helm
   helm repo update
   ```

3. Install ArgoCD:

   ```bash
   kubectl create namespace argocd
   helm upgrade --install argocd argo/argo-cd \
     --version 9.5.12 \
     --namespace argocd \
     --values deploy/argocd/values.yaml
   kubectl apply -f deploy/argocd/ingress.yaml
   ```

4. Rotate the admin password immediately after first login. The bootstrap
   password is in:

   ```bash
   kubectl -n argocd get secret argocd-initial-admin-secret \
     -o jsonpath="{.data.password}" | base64 -d
   ```

5. Create the Image Updater Git write-back Secret outside the repo using
   `deploy/argocd/image-updater/secret.example.yaml` as the shape. Use a
   fine-grained GitHub PAT scoped to `contents:write` on `hoeongj/ssuMCP` only.
   `application-ssuai-backend.yaml` references it through:

   ```yaml
   argocd-image-updater.argoproj.io/write-back-method: "git:secret:argocd/argocd-image-updater-git-creds"
   ```

6. Apply the backend Application:

   ```bash
   kubectl apply -f deploy/argocd/application-ssuai-backend.yaml
   ```

7. Install Image Updater:

   ```bash
   helm upgrade --install argocd-image-updater argo/argocd-image-updater \
     --version 0.14.0 \
     --namespace argocd \
     --values deploy/argocd/image-updater/values.yaml
   ```

## Day-2 checks

```bash
kubectl -n argocd get pods
kubectl -n argocd get applications.argoproj.io
kubectl -n argocd logs deploy/argocd-image-updater --tail=100
```

If the UI is reachable, open `https://argo-ssuai.duckdns.org` and confirm the
`ssuai-backend` Application is `Synced` and `Healthy`.

## Emergency break-glass

If GitOps itself is broken:

1. Disable auto-sync from the ArgoCD UI or CLI.
2. Apply a known-good rendered manifest manually.
3. Fix git.
4. Re-enable auto-sync only after `argocd app diff ssuai-backend` is clean.
