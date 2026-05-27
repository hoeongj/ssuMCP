# Pipeline diagnosis - 2026-05-14

## Symptom

Live backend at `https://ssumcp.duckdns.org` is reachable through the
DuckDNS/k3s/cert-manager route, but `https://ssumcp.duckdns.org/api/chat`
returns 404 and `/v3/api-docs` does not list the chat path after the chatbot
and MCP self-dogfood changes were merged.

Expected current source state:

- Last `origin/main` commit at diagnosis time:
  `6b974a7719aaf5f65d1ab025d1ea768cd0a8b58c`.
- Chatbot boot fix commit already on `main`:
  `8f0cd37b87aefc2f9b23bccb6e5a453e03d40376`.

The symptom therefore looks like the live pod is still running an image built
before the chatbot slice landed, not like an ingress or route outage.

## Findings

### Workflows

Repository workflow files:

| File | Relevant behavior |
|---|---|
| `.github/workflows/ci.yml` | Runs on `push` to `main` and `pull_request`. Builds backend, frontend, and an ARM64 backend image. |
| `.github/workflows/security.yml` | Runs gitleaks on `push` to `main` and `pull_request`. |

`ci.yml` image job:

- Job name: `Backend image (ghcr.io, ARM64)`.
- Gate: `if: github.event_name == 'push' && github.ref == 'refs/heads/main'`.
- Permissions: `packages: write`.
- Tags produced by `docker/metadata-action@v6`:
  - `ghcr.io/ghdtjdwn/ssuai-backend:latest`
  - `ghcr.io/ghdtjdwn/ssuai-backend:sha-<full-sha>`
- Platform: `linux/arm64`.

Recent `main` push CI runs from `gh run list --workflow=ci.yml --branch main
--event push --limit 8`:

| Run | Head | Status at diagnosis | Evidence |
|---|---|---|---|
| `25812748023` | `6b974a7` | in progress | Backend and Frontend jobs passed; image job was still in `Build and push`. |
| `25812740520` | `d3fcd3c` | in progress | Triggered by the async timeout cleanup merge. |
| `25812730679` | `38baa2f` | in progress | Triggered by the deprecation cleanup merge. |
| `25812720976` | `4633800` | success | Image job completed successfully. |
| `25809385813` | `8f0cd37` | success | Image job completed successfully after the LLM boot fix merge. |

Important image-build evidence:

- Run `25809385813` built commit
  `8f0cd37b87aefc2f9b23bccb6e5a453e03d40376`.
- The `Backend image (ghcr.io, ARM64)` job pushed:
  - `ghcr.io/ghdtjdwn/ssuai-backend:latest`
  - `ghcr.io/ghdtjdwn/ssuai-backend:sha-8f0cd37b87aefc2f9b23bccb6e5a453e03d40376`
- The job completed successfully at `2026-05-13T15:43:28Z`.

This rules out the simplest "CI never built a chatbot-capable image" theory
for commit `8f0cd37`.

### Image registry

The requested package API command failed:

```text
gh api '/users/ghdtjdwn/packages/container/ssuai-backend/versions?per_page=5'
HTTP 403: You need at least read:packages scope to get a package's versions.
```

Because of that token scope, this diagnosis could not list the last five GHCR
package versions directly. The workflow log still proves that CI successfully
pushed the `latest` and `sha-8f0cd37...` tags for the chatbot boot-fix commit.

### Helm / ArgoCD

Helm chart values:

```yaml
# deploy/charts/ssuai-backend/values.yaml
image:
  repository: ghcr.io/ghdtjdwn/ssuai-backend
  tag: latest
  pullPolicy: IfNotPresent
```

Production override:

```yaml
# deploy/charts/ssuai-backend/values-prod.yaml
{}
```

ArgoCD Application:

```yaml
# deploy/argocd/application-ssuai-backend.yaml
spec:
  source:
    targetRevision: main
    path: deploy/charts/ssuai-backend
    helm:
      valueFiles:
        - values.yaml
        - values-prod.yaml
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

The Application watches `main` and has automated sync, prune, and self-heal.
However, if `values.yaml` remains at `image.tag: latest`, a new image push does
not necessarily create a Git diff or a Deployment pod-template diff. With
`imagePullPolicy: IfNotPresent`, an existing node also has no reason to pull a
moved `latest` tag unless the pod is recreated and the image is absent or the
pull policy forces a pull.

### Image updater

Image Updater is intended to close this exact gap.

Configured annotations in `deploy/argocd/application-ssuai-backend.yaml`:

```yaml
argocd-image-updater.argoproj.io/image-list: "backend=ghcr.io/ghdtjdwn/ssuai-backend"
argocd-image-updater.argoproj.io/backend.update-strategy: "newest-build"
argocd-image-updater.argoproj.io/backend.allow-tags: "regexp:^sha-[0-9a-f]{40}$"
argocd-image-updater.argoproj.io/backend.helm.image-name: "image.repository"
argocd-image-updater.argoproj.io/backend.helm.image-tag: "image.tag"
argocd-image-updater.argoproj.io/write-back-method: "git:secret:argocd/argocd-image-updater-git-creds"
argocd-image-updater.argoproj.io/git-branch: "main"
argocd-image-updater.argoproj.io/write-back-target: "helmvalues:values.yaml"
```

Local repository evidence:

- `deploy/argocd/image-updater/values.yaml` configures git author
  `argocd-image-updater`.
- `git log --all --author='argocd-image-updater' -20`: no commits found.
- `git log --all --grep='chore(image)' -20`: no commits found.
- `deploy/charts/ssuai-backend/values.yaml` still says `image.tag: latest`.

The repo has the desired Image Updater manifests, but there is no evidence that
Image Updater has written back a concrete `sha-...` tag to Git.

## Root Cause Hypothesis

Most likely: **(D) Image Updater is configured in Git but is not operating in
the live cluster, or cannot write back to Git.**

Supporting evidence:

- CI did build and push a chatbot-capable backend image for commit `8f0cd37`.
- ArgoCD is configured to watch `main`, but the Helm value committed to `main`
  still uses `image.tag: latest`.
- The Application annotations expect Image Updater to write a concrete
  `sha-<full>` tag into `values.yaml`.
- No `argocd-image-updater` or `chore(image)` commits exist in repository
  history.

Secondary contributing factor: **(C) the chart is still effectively pinned to
a floating `latest` tag from Git's point of view.** Even when CI moves
`latest` in GHCR, ArgoCD may see no manifest change, and Kubernetes with
`IfNotPresent` may not pull the moved tag.

This combination explains a stale live backend despite successful image
builds.

## Proposed Fix

Small Git-only fix that can be done by Codex/Claude in a PR:

- After the latest `main` image job completes, change
  `deploy/charts/ssuai-backend/values.yaml` from `image.tag: latest` to the
  matching immutable tag, for example:
  `sha-6b974a7719aaf5f65d1ab025d1ea768cd0a8b58c`.
- If the latest image job is not complete yet, use the proven chatbot-capable
  image tag from the successful run:
  `sha-8f0cd37b87aefc2f9b23bccb6e5a453e03d40376`.
- Keep Image Updater annotations, then verify that future Image Updater
  write-back commits replace the manual bump flow.

- In ArgoCD UI or CLI, confirm `ssuai-backend` synced after the tag bump.
- Check `argocd-image-updater` pod logs for registry auth or git credential
  errors.
- Confirm the secret named
  `argocd-image-updater-git-creds` exists in namespace `argocd` and has a
  token with `contents:write` on `ghdtjdwn/ssuAI`.
- If the pod is still stale after Git sync, manually restart the backend
  Deployment once or trigger an ArgoCD hard refresh.

## Out of Scope

- VM SSH, `kubectl`, and ArgoCD UI inspection were not available in this
  session.
- GHCR package version listing was blocked by missing `read:packages` scope.
- No Helm value was changed in this diagnosis step.

## Resolution (2026-05-14)

Resolved via commit `5be57d9` (`deploy(backend): enable LLM chat and fix image
pull policy`), which took a simpler workaround instead of the proposed
sha-pinning fix above:

- `deploy/charts/ssuai-backend/values.yaml`:
  `image.pullPolicy: IfNotPresent` → `Always`, so each ArgoCD sync pulls the
  latest GHCR image rather than reusing the cached `latest` tag on the node.
- Same file: `env.connectorChat: mock` → `llm` to actually wire the LLM + MCP
  chat flow in prod.

This unblocks the live rollout of the chatbot-capable backend without changing
`image.tag`, so Image Updater annotations stay in place for the future.

Still outstanding (intentionally deferred):

- ArgoCD Image Updater is still not writing back concrete `sha-...` tags to
  Git. The current `pullPolicy: Always` workaround means we re-pull `latest`
  on every sync, which is good enough for now but loses the
  "exact image per Git revision" guarantee Image Updater would give us.
- Revisit when (a) Image Updater becomes blocking, (b) we want immutable
  rollbacks, or (c) `pullPolicy: Always` causes registry rate-limit or
  cold-start issues. At that point, restore the original proposed fix and
  debug the `argocd-image-updater` pod / `argocd-image-updater-git-creds`
  secret per the steps above.
