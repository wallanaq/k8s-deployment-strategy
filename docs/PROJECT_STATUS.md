# Project status

**As of:** 2026-08-24
**Scope:** covers the "Helm chart onward" phase of development, i.e. everything after
`b02afba feat: add github workflows (qrcode-api)`. Earlier work (the qrcode-api Spring Boot
app itself, and the original Tekton/Forgejo/Harbor CI setup that was later decommissioned)
is treated as settled background and not re-verified in depth here.

This document was produced by directly inspecting the repository (`git log`, `git status`,
file tree) and the live OrbStack cluster (`kubectl`, `helm`, Harbor's API) — not by summarizing
what prior prompts asked for. Every claim below reflects what was actually found. Sections 5
and 6 exist specifically to call out places where intention and reality diverge.

---

## 1. Goal

The actual objective of this project is to learn Kubernetes deployment strategies —
specifically canary and blue-green rollouts — using Argo CD and Istio. Nothing else in this
document is the point: the CI pipeline, the container registry, and the Helm chart are all
*supporting infrastructure* built to get one real application (`qrcode-api`) into a state
where it can be deployed repeatably, so that Argo CD and progressive-delivery mechanics have
something real to operate on. As of this writing, that supporting infrastructure is mostly in
place but the actual learning objective (Argo CD, canary/blue-green) has not been started.

---

## 2. Current state (verified)

### Source & CI

- The application (`qrcode-api`, a Spring Boot 4.1 / Java 25 service) lives in `qrcode-api/`
  as a Maven module alongside a root parent `pom.xml`, i.e. this is a monorepo, not a
  standalone repo per app.
- `.github/workflows/build-qrcode-api.yml` runs on push to paths under `qrcode-api/**`:
  a `test` job (`mvn -B test`), then a `build-and-push` job (`needs: test`) that builds the
  Dockerfile in `qrcode-api/` and pushes to GHCR using `docker/metadata-action` (tags:
  short-SHA and `latest` on the default branch) with GHA build caching.
- Images land at `ghcr.io/wallanaq/k8s-deployment-strategy/qrcode-api`. **Verified via an
  anonymous GHCR token pull — the package is genuinely public**, matching the "package
  visibility changed to public" decision; no pull secret is required or wired in.
  Currently published tags: `latest`, `sha-b02afba`.
- **Verified: this `latest` tag is amd64-only.** The workflow's `docker/build-push-action`
  step never set `platforms:`, so the GitHub-hosted (amd64) runner only built for its own
  architecture. This was only discovered by actually trying to run the image on this
  (arm64) OrbStack node — see §5/§6.
- No trace of Tekton, Forgejo, or Harbor-as-a-CI-system remains anywhere in the repo
  (`grep -rniE "tekton|forgejo"` returns nothing). The decommissioning was real and complete.
  Harbor itself still exists, but repurposed — see the Helm chart section below.

### Helm chart

- Real location: **`charts/base-webapp/`** (not `charts/qrcode-api/` — it was renamed and
  genericized). Structure is a standard `helm create` scaffold: `Chart.yaml`, `values.yaml`,
  `templates/{_helpers.tpl,deployment.yaml,service.yaml,serviceaccount.yaml,httproute.yaml,
  hpa.yaml,NOTES.txt,tests/}`. Current `Chart.yaml`: `name: base-webapp`, `version: 0.2.0`,
  `appVersion: "0.0.1"`.
- **Confirmed: the chart carries zero references to `qrcode-api`.** All helper templates are
  `base-webapp.*`, and `charts/base-webapp/values.yaml` is fully generic (empty image
  repository, empty env, empty hostnames).
- **Confirmed: the generic-chart / app-specific-values split described in prior work actually
  exists as described.** App-specific configuration lives separately at
  `qrcode-api/infra/helm/values.yaml`, which sets `nameOverride: qrcode-api`, the real image
  repository/tag, Postgres-backed env vars, and enables the HTTPRoute.
- There is **no Kubernetes `Ingress` template in this chart** — it was deliberately removed.
  The chart exposes services exclusively via Gateway API `HTTPRoute`, pointed by default at
  a Gateway named `istio-gateway` in namespace `istio-system` (matching
  `infra/kubernetes/istio/gateway.yaml`). When `httpRoute.hostnames` is left empty, the
  template auto-derives `<release-name>.<httpRoute.domain>` (default domain
  `k8s.orb.local`) — e.g. `qrcode-api` → `qrcode-api.k8s.orb.local`.

### Database

- **Confirmed: Postgres runs as plain manifests, not Bitnami's chart and not an operator.**
  `qrcode-api/infra/postgres/{pvc.yaml,deployment.yaml,service.yaml}`: a single-replica
  `postgres:16` Deployment, a 1Gi `ReadWriteOnce` PVC (default `local-path` StorageClass,
  currently `Bound`), and a `ClusterIP` Service on port 5432. Credentials are **not**
  committed as YAML — they're created imperatively into a `qrcode-api-postgres-credentials`
  Secret via a Makefile target (`make create-postgres-secret`), the same pattern used
  elsewhere in this repo for credentials.
- **Live-verified: this Postgres instance is genuinely running.** `qrcode-api-postgres`
  pod is `1/1 Running`, its PVC is `Bound`, and it's passing a `pg_isready` readiness probe.
  This is real, not just written-and-hoped-for.

### Cluster runtime (OrbStack, live right now)

| Namespace | What's there | Status |
|---|---|---|
| `qrcode-api` | `qrcode-api` Deployment (app) | **`0/1`, both pods `ImagePullBackOff`** — see §6 |
| `qrcode-api` | `qrcode-api-postgres` Deployment | `1/1 Running`, healthy |
| `qrcode-api` | PVC `qrcode-api-postgres-data` | `Bound`, 1Gi |
| `qrcode-api` | Secret `qrcode-api-postgres-credentials` | present |
| `harbor` | Full Harbor install (core, registry, portal, database, redis, jobservice, trivy, nginx) | all `Running` |
| `istio-system` | `istiod`, `istio-cni-node`, `ztunnel`, `istio-gateway-istio` | all `Running` |

`helm list -A` confirms exactly two releases exist: `harbor` (namespace `harbor`,
`deployed`) and `qrcode-api` (namespace `qrcode-api`, chart `base-webapp-0.2.0`, **status
`failed`** — revision 2 timed out waiting on the unpullable app pod).

### Service mesh

- Istio is installed in **ambient mode** (`profile: ambient` in
  `infra/kubernetes/istio/operator.yaml`; confirmed live by the presence of a `ztunnel`
  DaemonSet pod alongside `istiod`, with no sidecars injected into app pods).
  The `qrcode-api` namespace **is** labeled `istio.io/dataplane-mode=ambient` (confirmed via
  `kubectl get ns qrcode-api -o jsonpath='{.metadata.labels}'`).
- Gateway: `istio-gateway` in `istio-system`, listener `http` on port 80, wildcard hostname
  `*.k8s.orb.local`. **Its `Programmed` condition is `False`** (`AddressNotAssigned` — the
  backing `istio-gateway-istio` LoadBalancer Service's external IP is stuck `<pending>`).
- HTTPRoute: `qrcode-api` HTTPRoute in namespace `qrcode-api`, hostname
  `qrcode-api.k8s.orb.local`, parentRef → `istio-gateway`/`istio-system`/`http`.
  **`kubectl get httproute` reports `Accepted`** at the Kubernetes API level (`Accepted: True`,
  `ResolvedRefs: True`) — but see §6 for why this status is misleading on its own here.

---

## 3. Key architecture decisions and why

| Decision | Rejected alternative | Why |
|---|---|---|
| GitHub Actions for CI | Tekton + Forgejo + Harbor-as-CI (previously fully built) | Refocus effort on the actual learning goal (Argo CD/Istio deployment strategies) rather than maintaining a bespoke CI stack; GitHub Actions + GHCR is zero-maintenance for a solo learning project |
| Gateway API `HTTPRoute` | Istio-native `VirtualService` | Portability — HTTPRoute isn't Istio-specific, so the base chart isn't locked to one mesh implementation |
| Plain Kubernetes manifests for Postgres | Bitnami Helm chart / a Postgres operator (e.g. CloudNativePG) | Bitnami deprecated its free-tier chart images in 2025; Postgres here is a dependency for testing the app chart, not a learning objective in its own right — avoid over-investing in a supporting system |
| Generic base chart (`charts/base-webapp`) + a separate per-app values file | A full Helm library-chart split | Deliberately deferred until there's a real second application to design the library boundary around — premature now with only one consumer |
| Public GHCR package | Private package + `imagePullSecret` | Removes a whole class of pull-secret plumbing for a project with no sensitive image content |
| Istio ambient mode | Sidecar injection | (Documented in `operator.yaml`; lower per-pod overhead, no sidecar injection needed for the ambient dataplane) |
| Chart auto-derives HTTPRoute hostname from release name + domain | Hardcoding each app's hostname in its values file | One less thing every future app's values file has to get right; matches the "app name + gateway host" convention directly |

---

## 4. Explicitly abandoned or reverted paths

Do not re-propose these — they were considered and rejected, not overlooked:

- **Forgejo + Tekton + Harbor as the CI system.** This was fully built at one point, then
  intentionally decommissioned in favor of GitHub Actions + GHCR. Confirmed no trace remains
  in the current repo.
- **CloudNativePG (or any Postgres operator)** for the database dependency. Considered and
  rejected as premature scope for what's meant to be a simple, disposable-ish test dependency.
- **A Kubernetes `Ingress` resource in the base Helm chart.** The chart originally scaffolded
  one (via `helm create`); it was removed in favor of exposing services exclusively through
  Gateway API `HTTPRoute`, once Istio's ambient mode + shared Gateway became the real ingress
  path.
- **`helm registry login` for pushing chart to Harbor.** Attempted, but macOS's keychain
  integration (`docker-credential-osxkeychain`) broke on stale/foreign keychain entries.
  Reverted to passing `--username`/`--password` directly to `helm push`/`helm pull`, which
  behaves identically without touching the shared credential store. (Documented inline in the
  Makefile.)

---

## 5. Open items

- **Argo CD is not installed.** No `argocd` namespace, no Argo CD resources anywhere in the
  cluster or repo.
- **Canary/blue-green deployment strategies — the actual point of this project — have not
  been started.** No Argo CD `Rollout`/`Application` manifests exist yet.
- **The chart published to Harbor is stale.** Harbor genuinely holds a real OCI artifact at
  `oci://harbor.k8s.orb.local/charts/base-webapp:0.1.1` — but the local chart is now at
  `0.2.0` (after the Ingress-removal/HTTPRoute rework) and has never been re-published.
  Two more artifacts (`charts/qrcode-api:0.1.0`, `:0.1.1`) remain published under the
  pre-rename chart name and are now orphaned.
- **A large amount of work exists only in the local working tree, uncommitted.** See §6 for
  the full list — this needs a commit (and a push) before it can be considered durable.
- **The qrcode-api app has never successfully run in this cluster.** Both pods are
  `ImagePullBackOff` because the published image lacks an arm64 manifest. A fix
  (`docker/setup-qemu-action` + `platforms: linux/amd64,linux/arm64`) has been written into
  the workflow file but is uncommitted and hasn't triggered a CI run yet.
- **The Gateway's LoadBalancer address is stuck `<pending>`.** Even once the image issue is
  fixed, this needs investigating before HTTPRoute traffic can be trusted end-to-end.
- **Minor cleanup:** `qrcode-api/infra/helm/values.yaml`'s `httpRoute:` block has a handful of
  orphaned commented-out example lines (leftover `filters`/`matches` YAML comments with no
  corresponding `rules:` key above them) — harmless (still valid YAML) but confusing to read.
- GitHub Actions run history wasn't directly inspectable in this pass (no `gh` CLI available
  in this environment) — conclusions about CI are based on the workflow file's current
  content and the live state of the GHCR registry, not on actual run logs.

---

## 6. Discrepancies found during verification

These are the places where the real repo/cluster state does **not** match what a reasonable
reading of the git history or prior planning would suggest — worth double-checking before
building anything further on top of them.

1. **`git log` shows only 4 commits, the newest being a small CI-deprecation fix — but almost
   none of the infrastructure described in this document is actually committed.** The root
   `Makefile`, the entire `charts/base-webapp/` Helm chart, `infra/kubernetes/istio/`, and
   `qrcode-api/infra/{helm,postgres}/` all exist **only as uncommitted working-tree files**
   (`git status` shows them as untracked). Someone reading only `git log` would have no idea
   any of this exists.
2. **Even the one real commit ahead of `origin/main` (`48db304`, the setup-java/checkout
   version bump) hasn't been pushed.** `git status` shows `main...origin/main [ahead 1]`.
   GitHub's copy of the workflow is one commit behind local, and doesn't include today's
   multi-arch build fix either (which isn't committed at all yet).
3. **Harbor's role had genuine back-and-forth (per project history) and needs a real answer:
   it is installed, and the chart genuinely was published as an OCI artifact — this was
   actually executed, not just discussed in a prompt that was never run.** However, what's
   published is out of date (chart version `0.1.1` in Harbor vs. `0.2.0` locally).
4. **`kubectl get httproute` reporting `Accepted` does not mean traffic actually flows.** The
   backing Gateway Service (`istio-gateway-istio`) has never received a LoadBalancer address
   (`<pending>` since creation). Querying `http://qrcode-api.k8s.orb.local/` right now returns
   an HTTP 200 — but verbose inspection shows that response comes from an unrelated
   OrbStack-internal nginx catch-all (same behavior reproduces for `harbor.k8s.orb.local`
   hitting a different, unrelated IP than Harbor's real LoadBalancer address), **not from the
   application**. Taking the HTTPRoute's `Accepted` status (or a 200 response from that
   hostname) at face value would be a mistake.
5. **The qrcode-api application pod has literally never run successfully in this cluster.**
   Every attempt so far has ended in `ImagePullBackOff` due to the amd64-only image.
6. **Postgres, in contrast, is the one piece that's fully real and working end-to-end** —
   running, healthy, PVC bound, passing readiness checks — despite being introduced in a
   prompt that described it as an "adjustment" to a "previous pass" that, per point 1 above,
   never actually existed before it was built in this same pass.
