# ADR 0008 - GitOps rollout with ArgoCD + Helm chart

- **Status**: Accepted (Task 07 merged; ArgoCD + Image Updater running against the live cluster, auto-deploy on every ghcr.io push)
- **Date**: 2026-05-08
- **Scope**: `deploy/charts/`, `deploy/argocd/`, removal of
  `deploy/k8s/` raw manifests, `deploy/README.md` upgrade workflow.

## Context

Task 06 (ADR 0007) put a working K8s cluster behind a public URL with
manual `kubectl apply -f deploy/k8s/`. That is acceptable for the very
first deploy, but every subsequent change — bumping the image after CI,
flipping a CORS origin, tweaking JVM opts — would require an SSH +
kubectl ritual nobody wants to run twice. The whole point of putting
Spring Boot on K8s instead of a PaaS was to demonstrate operating
cloud-native infrastructure; running it like a PaaS deployment defeats
that.

The state-of-practice answer in 2026 is **GitOps**: the cluster
continuously reconciles itself to a Git repository, and the developer's
only deploy action is `git push`. Two requirements shape the choice:

1. **One developer, one cluster, public repo.** Whatever tooling we
   pick has to be reasonable to operate alone. Multi-cluster, SSO, and
   HA controllers are pure cost at this size.
2. **Portfolio narrative.** ssuAI's pitch is "I can build and operate
   cloud-native backend infrastructure". The tooling chosen here ends
   up on a CV; the choice has to land where Korean cloud-native job
   postings actually look.
3. **Backend-only workload (today).** The frontend lives on Vercel, so
   GitOps is exclusively for the cluster's K8s resources. Multi-tenant
   patterns can wait.

## Decision

Production rollout uses:

- **ArgoCD**, installed via the upstream Helm chart into the `argocd`
  namespace, exposed at `argo-ssuai.duckdns.org` behind Traefik +
  cert-manager (the same TLS stack as the backend).
- **Helm chart** at `deploy/charts/ssuai-backend/` for the backend
  workload. Each plain manifest from Task 06 becomes a chart template;
  values.yaml carries everything an environment might want to override.
- A **single ArgoCD `Application`** named `ssuai-backend`, source =
  this repo's `deploy/charts/ssuai-backend/` path on `main`. Sync policy
  = `automated: { prune: true, selfHeal: true }`.
- **ArgoCD Image Updater** watches `ghcr.io/ghdtjdwn/ssuai-backend` for
  new `sha-<full>` tags pushed by CI. On a new tag, Image Updater writes
  the tag back to `deploy/charts/ssuai-backend/values.yaml` via a git
  commit authored by `argocd-image-updater[bot]`, using a fine-grained
  PAT scoped to `contents:write` on this repo only.
- **No SSO** for ArgoCD (admin password only). **No App-of-apps**
  (single Application is enough). **No Sealed Secrets** yet (no real
  secrets to manage).

Manual `kubectl apply` survives only as a break-glass for one specific
case: bootstrap. The cluster's one-time bootstrap (`cert-manager`,
`ClusterIssuer`, ArgoCD itself) is applied imperatively from a laptop.
Once ArgoCD is up, ArgoCD owns everything that lives in
`deploy/charts/ssuai-backend/`.

## Consequences

**Good**

- The deploy path is fully declarative and visible in git history. A
  reviewer can trace any production state change to a commit, and a
  `git revert` is a deploy revert without any cluster access.
- Drift is caught automatically. A hand-edit in the cluster (`kubectl
  edit deploy …` for a debug-then-forget) reverts within ~30s thanks
  to self-heal.
- The Image Updater closes the "CI built an image, now what?" gap that
  Task 06 left wide open. After merge, "push to main" is literally the
  whole deploy procedure.
- The ArgoCD UI is a strong portfolio asset for the demo URL — a
  reviewer can click around and see the sync state of the live system.
- Helm chart + values.yaml gives a clean place to add the next
  environment (a stage cluster) without re-templating anything.

**Cost**

- ArgoCD adds ~7 pods to the cluster. On a 4-OCPU / 24 GB free-tier
  VM that is well within budget, but it is real load that did not
  exist before.
- Image Updater's git write-back requires a real PAT in the cluster.
  The blast radius of that token is `contents:write` on the repo;
  rotation is now a recurring chore (documented in
  `deploy/argocd/README.md`).
- The Helm templating layer is one more thing to debug when a deploy
  goes wrong. `helm template` + `argocd app diff` are the new "what
  did I just change" commands; the project's first 1–2 deploys after
  this task land will discover the foot-guns.
- ArgoCD UI is publicly exposed to keep the portfolio narrative
  visible. Mitigation is HTTPS + a strong admin password; long-term
  fix is SSO (Dex + GitHub OAuth), deferred.
- Bootstrapping ArgoCD itself with `kubectl apply` (instead of
  ArgoCD-manages-ArgoCD via app-of-apps) means a fresh cluster needs
  a documented bootstrap order. The runbook in `deploy/argocd/README.md`
  carries this cost.

## Alternatives considered

- **Flux (FluxCD v2)** — the other dominant GitOps controller.
  Strictly viable, and the operational footprint is slightly smaller
  than ArgoCD. Rejected because (a) Korean cloud-native JD postings
  call out ArgoCD by name far more often than Flux — the portfolio
  signal is real, (b) ArgoCD's UI is a better demo asset for a
  portfolio URL than Flux's CLI-first posture, and (c) ArgoCD's Helm
  chart support is more polished. Re-evaluate if the project ever
  needs Flux's `ImageUpdateAutomation` natively (currently we delegate
  to ArgoCD Image Updater, which lives outside core ArgoCD anyway).
- **Kustomize instead of Helm** — Kustomize's overlay model is a clean
  fit for "one base, per-environment patches". Rejected because the
  project has one environment today; the overlay machinery would be
  pure cost. Helm + values.yaml gives the same parameterization with
  one less tool to learn, and Helm is more common as a baseline K8s
  skill. (ArgoCD speaks both natively, so this can be revisited
  cheaply if Kustomize ever fits better.)
- **Raw manifests + GitHub Actions writes back the new image tag** —
  no in-cluster controller. Rejected because (a) it pulls deploy
  responsibility into the CI runner instead of the cluster, (b) it
  needs the same PAT problem solved (or a deploy key) without ArgoCD's
  UI to inspect the result, and (c) the portfolio loses the "I run an
  ArgoCD" line item. The complexity is roughly comparable; ArgoCD pays
  back its complexity in observability and drift detection.
- **Spinnaker** — was the canonical CD platform pre-GitOps and is
  still operated at large companies. Rejected — operational footprint
  (multiple JVMs, a dedicated Postgres) is absurd for one workload.
- **Jenkins X** — bundles GitOps with opinionated CI. Rejected for the
  same reason: heavy footprint, narrow community in 2026, and CI is
  already on GitHub Actions.
- **App-of-apps from day one** — ArgoCD pattern where one root
  Application reconciles N child Applications. Rejected because there
  is exactly one workload (`ssuai-backend`); the app-of-apps machinery
  is solving a problem the project does not yet have. Promote when
  observability + a database land alongside the backend.
- **ArgoCD ApplicationSet for multi-environment promotion** — same
  reasoning. The project has one cluster; promotion targets that do
  not exist do not need a controller.
- **Sealed Secrets / SOPS / external-secrets-operator** — would let
  secrets live in git encrypted. Rejected because the MVP has no
  secrets to encrypt yet; this is a deferred decision, picked when
  the first real secret (DB password, JWT key, etc.) appears. ADR is
  open about which one will win — likely external-secrets-operator
  if the secret backend is something the cluster can authenticate to,
  Sealed Secrets if not.
- **Hand-rolled bash scripts that `ssh && kubectl set image`** —
  rejected on principle. The whole task is the move from "I deployed
  it once" to "I run it"; a script is just the manual workflow with
  fewer keystrokes.
