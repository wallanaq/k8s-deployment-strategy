# ------------------------------------------------------------------------------
# Istio
# ------------------------------------------------------------------------------
ISTIO_NAMESPACE       ?= istio-system
GATEWAY_API_CRDS_URL  ?= https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.3.0/standard-install.yaml

# ------------------------------------------------------------------------------
# Harbor
# ------------------------------------------------------------------------------
HARBOR_NAMESPACE        ?= harbor
HARBOR_HOST             ?= harbor.k8s.orb.local
HARBOR_CHARTS_PROJECT   ?= charts
# No default on purpose -- a real value here would be a plaintext credential
# committed to git. Pass it on the command line or export it in your shell,
# e.g. `HARBOR_ADMIN_PASSWORD=... make harbor`.
HARBOR_ADMIN_PASSWORD   ?=

CHART_DIR      ?= charts/base-webapp
CHART_DIST_DIR ?= dist

# ------------------------------------------------------------------------------
# Postgres (standalone infra capability, currently qrcode-api's only consumer)
# ------------------------------------------------------------------------------
POSTGRES_NAMESPACE   ?= postgres
POSTGRES_DIR         ?= infra/kubernetes/postgres
POSTGRES_SECRET_NAME ?= qrcode-api-postgres-credentials
POSTGRES_USER        ?= qrcode_api
# No default on purpose -- a real value here would be a plaintext credential
# committed to git. Pass it on the command line or export it in your shell,
# e.g. `POSTGRES_PASSWORD=... make postgres`. Must match whatever the live
# Postgres role's password actually is (see "ALTER ROLE" if rotating it).
POSTGRES_PASSWORD    ?=
POSTGRES_DB          ?= pix_qrcode

# ------------------------------------------------------------------------------
# Forgejo
# ------------------------------------------------------------------------------
FORGEJO_NAMESPACE ?= forgejo
FORGEJO_HOST      ?= forgejo.k8s.orb.local

# ------------------------------------------------------------------------------
# Argo CD
# ------------------------------------------------------------------------------
ARGOCD_NAMESPACE ?= argocd
ARGOCD_HOST      ?= argocd.k8s.orb.local

# ------------------------------------------------------------------------------
# Argo Rollouts
# ------------------------------------------------------------------------------
ARGO_ROLLOUTS_NAMESPACE ?= argo-rollouts
# Pinned rather than "latest" -- same reasoning as GATEWAY_API_CRDS_URL above,
# so re-running this target later doesn't silently pick up a newer, untested
# controller version.
ARGO_ROLLOUTS_VERSION   ?= v1.10.0
ARGO_ROLLOUTS_INSTALL_URL ?= https://github.com/argoproj/argo-rollouts/releases/download/$(ARGO_ROLLOUTS_VERSION)/install.yaml

# ------------------------------------------------------------------------------
# qrcode-api test-deploy (local smoke-test, not part of GitHub Actions)
# ------------------------------------------------------------------------------
QRCODE_API_NAMESPACE      ?= qrcode-api
QRCODE_API_RELEASE        ?= qrcode-api
QRCODE_API_VALUES         ?= qrcode-api/infra/helm/values.yaml
QRCODE_API_DB_SECRET_NAME ?= qrcode-api-db-credentials

.PHONY: help infra istio harbor postgres forgejo argocd argo-rollouts

help: ## Show this help message
	@echo "Available targets:"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# ------------------------------------------------------------------------------
# Install
# ------------------------------------------------------------------------------

infra: istio harbor postgres forgejo argocd argo-rollouts ## Install all local infra (Istio, Harbor, Postgres, Forgejo, ArgoCD, Argo Rollouts) in one go (idempotent)

istio: ## Install Istio and the shared *.k8s.orb.local Gateway (idempotent)
	@echo "==> Installing Gateway API CRDs..."
	@kubectl get crd gateways.gateway.networking.k8s.io >/dev/null 2>&1 || \
		kubectl apply -f $(GATEWAY_API_CRDS_URL)
	@echo "==> Creating $(ISTIO_NAMESPACE) namespace..."
	@kubectl create namespace $(ISTIO_NAMESPACE) --dry-run=client -o yaml | kubectl apply -f -
	@echo "==> Installing Istio via IstioOperator (ambient profile)..."
	@istioctl install -f infra/kubernetes/istio/operator.yaml -y
	@echo "==> Applying the *.k8s.orb.local Gateway (Gateway API, auto-provisions the ingress deployment)..."
	@kubectl apply -k infra/kubernetes/istio
	@echo "==> Waiting for the istio-gateway-istio deployment to become available..."
	@kubectl rollout status deployment/istio-gateway-istio \
		--namespace $(ISTIO_NAMESPACE) --timeout=300s
	@echo "==> Istio ingress gateway is up. Service:"
	@kubectl get svc istio-gateway-istio --namespace $(ISTIO_NAMESPACE)

harbor: ## Install Harbor (OCI registry, plain HTTP) at http://harbor.k8s.orb.local via the shared Gateway (idempotent)
	@if [ -z "$(HARBOR_ADMIN_PASSWORD)" ]; then \
		echo "HARBOR_ADMIN_PASSWORD must be set (no default -- see the comment by its declaration)."; \
		exit 1; \
	fi
	@echo "==> Adding/updating the Harbor Helm repo..."
	@helm repo add harbor https://helm.goharbor.io >/dev/null 2>&1 || true
	@helm repo update harbor >/dev/null
	@echo "==> Creating $(HARBOR_NAMESPACE) namespace..."
	@kubectl create namespace $(HARBOR_NAMESPACE) --dry-run=client -o yaml | kubectl apply -f -
	@echo "==> Installing/upgrading Harbor via Helm..."
	@echo "    Exposed as a ClusterIP Service named 'harbor', reached via an HTTPRoute on the"
	@echo "    shared istio-gateway at http://$(HARBOR_HOST) -- not its own LoadBalancer. A"
	@echo "    single-node cluster only has one host port 80 to hand out via k3s's ServiceLB,"
	@echo "    and qrcode-api's Gateway needs it too; only one LoadBalancer:80 can ever bind."
	@helm upgrade --install harbor harbor/harbor \
		--namespace $(HARBOR_NAMESPACE) \
		--set expose.type=clusterIP \
		--set expose.clusterIP.name=harbor \
		--set expose.tls.enabled=false \
		--set externalURL=http://$(HARBOR_HOST) \
		--set harborAdminPassword=$(HARBOR_ADMIN_PASSWORD) \
		--wait --timeout 10m
	@echo "==> Applying Harbor's HTTPRoute on the shared Gateway..."
	@kubectl apply -k infra/kubernetes/harbor
	@echo "==> Harbor is up at http://$(HARBOR_HOST) (user: admin)"

postgres: ## Install Postgres (plain manifests, PVC-backed) for qrcode-api in its own namespace (idempotent)
	@if [ -z "$(POSTGRES_PASSWORD)" ]; then \
		echo "POSTGRES_PASSWORD must be set (no default -- see the comment by its declaration)."; \
		exit 1; \
	fi
	@echo "==> Creating $(POSTGRES_NAMESPACE) namespace..."
	@kubectl create namespace $(POSTGRES_NAMESPACE) --dry-run=client -o yaml | kubectl apply -f -
	@echo "==> Creating/updating the Postgres credentials Secret..."
	@kubectl create secret generic $(POSTGRES_SECRET_NAME) \
		--namespace $(POSTGRES_NAMESPACE) \
		--from-literal=POSTGRES_USER=$(POSTGRES_USER) \
		--from-literal=POSTGRES_PASSWORD=$(POSTGRES_PASSWORD) \
		--from-literal=POSTGRES_DB=$(POSTGRES_DB) \
		--dry-run=client -o yaml | kubectl apply -f -
	@echo "==> Applying Postgres manifests from $(POSTGRES_DIR)..."
	@kubectl apply -f $(POSTGRES_DIR)
	@echo "==> Waiting for qrcode-api-postgres to become available..."
	@kubectl rollout status deployment/qrcode-api-postgres \
		--namespace $(POSTGRES_NAMESPACE) --timeout=180s
	@echo "==> Postgres is up in namespace $(POSTGRES_NAMESPACE)."

forgejo: ## Install Forgejo (Git hosting) at http://$(FORGEJO_HOST) via the shared Gateway (idempotent)
	@echo "==> Creating $(FORGEJO_NAMESPACE) namespace..."
	@kubectl create namespace $(FORGEJO_NAMESPACE) --dry-run=client -o yaml | kubectl apply -f -
	@echo "==> Installing/upgrading Forgejo via Helm..."
	@echo "    Exposed as a ClusterIP Service ('forgejo-http'), reached via an HTTPRoute on the"
	@echo "    shared istio-gateway at http://$(FORGEJO_HOST) -- not its own LoadBalancer, for the"
	@echo "    same port-80 scheduling conflict reason noted by the harbor target above."
	@helm upgrade --install forgejo oci://code.forgejo.org/forgejo-helm/forgejo \
		--namespace $(FORGEJO_NAMESPACE) \
		--set service.http.type=ClusterIP \
		--wait --timeout 10m
	@echo "==> Applying Forgejo's HTTPRoute on the shared Gateway..."
	@kubectl apply -k infra/kubernetes/forgejo
	@echo "==> Waiting for the forgejo deployment to become available..."
	@kubectl rollout status deployment/forgejo \
		--namespace $(FORGEJO_NAMESPACE) --timeout=300s
	@echo "==> Forgejo is up at http://$(FORGEJO_HOST)"

argocd: ## Install Argo CD (GitOps controller) at http://$(ARGOCD_HOST) via the shared Gateway (idempotent)
	@echo "==> Creating $(ARGOCD_NAMESPACE) namespace..."
	@kubectl create namespace $(ARGOCD_NAMESPACE) --dry-run=client -o yaml | kubectl apply -f -
	@echo "==> Installing/upgrading Argo CD via Helm..."
	@echo "    Server runs in insecure/plain-HTTP mode (configs.params.\"server.insecure\") since it"
	@echo "    sits behind the same TLS-less Gateway as everything else in this cluster. Exposed as"
	@echo "    a ClusterIP Service ('argocd-server'), reached via an HTTPRoute -- not its own"
	@echo "    LoadBalancer, for the same port-80 scheduling conflict reason noted by harbor above."
	@helm upgrade --install argocd oci://ghcr.io/argoproj/argo-helm/argo-cd \
		--namespace $(ARGOCD_NAMESPACE) \
		--set 'configs.params.server\.insecure=true' \
		--wait --timeout 10m
	@echo "==> Applying Argo CD's HTTPRoute on the shared Gateway..."
	@kubectl apply -k infra/kubernetes/argocd
	@echo "==> Waiting for the argocd-server deployment to become available..."
	@kubectl rollout status deployment/argocd-server \
		--namespace $(ARGOCD_NAMESPACE) --timeout=300s
	@echo "==> Argo CD is up at http://$(ARGOCD_HOST) (user: admin)"
	@echo "    Initial password: kubectl -n $(ARGOCD_NAMESPACE) get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d"

argo-rollouts: ## Install Argo Rollouts (controller + CRDs) with the Gateway API traffic-routing plugin (idempotent)
	@echo "==> Creating $(ARGO_ROLLOUTS_NAMESPACE) namespace..."
	@kubectl create namespace $(ARGO_ROLLOUTS_NAMESPACE) --dry-run=client -o yaml | kubectl apply -f -
	@echo "==> Installing the Argo Rollouts controller + CRDs ($(ARGO_ROLLOUTS_VERSION))..."
	@echo "    Raw manifest, not the argo-helm chart -- the project's own docs only document"
	@echo "    'kubectl apply -f install.yaml' as the install path; Helm isn't mentioned as an"
	@echo "    alternative there, unlike Argo CD's docs which do point at argo-helm."
	@echo "    --server-side because the Rollout/AnalysisRun CRDs' schemas exceed the 256KiB"
	@echo "    last-applied-configuration annotation limit that plain client-side apply hits."
	@kubectl apply --server-side -n $(ARGO_ROLLOUTS_NAMESPACE) -f $(ARGO_ROLLOUTS_INSTALL_URL)
	@echo "==> Registering the Gateway API traffic-routing plugin (argoproj-labs/gatewayAPI)"
	@echo "    and its supplemental RBAC (HTTPRoutes aren't covered by the core install)..."
	@echo "    --server-side, and under a distinct --field-manager: argo-rollouts-config is"
	@echo "    co-owned with the install.yaml above. Client-side apply's diff-and-strip logic"
	@echo "    drops fields (e.g. labels) it doesn't know the other manifest declared; a shared"
	@echo "    server-side field-manager name has the same problem (whichever applies last wins"
	@echo "    and prunes the other's fields), so this uses its own field-manager identity."
	@kubectl apply --server-side --field-manager=argo-rollouts-gatewayapi-plugin \
		-k infra/kubernetes/argo-rollouts
	@echo "==> Restarting the controller so it picks up the plugin config..."
	@kubectl rollout restart deployment/argo-rollouts --namespace $(ARGO_ROLLOUTS_NAMESPACE)
	@echo "==> Waiting for the argo-rollouts deployment to become available..."
	@kubectl rollout status deployment/argo-rollouts \
		--namespace $(ARGO_ROLLOUTS_NAMESPACE) --timeout=300s
	@echo "==> Argo Rollouts is up in namespace $(ARGO_ROLLOUTS_NAMESPACE)."
	@echo "    Not yet Argo CD-managed -- installed imperatively via this target, same as"
	@echo "    every other tool in infra/kubernetes/ before its own GitOps migration."

# ------------------------------------------------------------------------------
# Helm chart publishing (base-webapp -> Harbor OCI, local/manual step)
# ------------------------------------------------------------------------------
# GitHub Actions' hosted runners can't reach $(HARBOR_HOST) (it's only routable
# inside the local OrbStack cluster), so this is run by hand from a machine that
# can. Argo CD will later pull the published chart straight from Harbor
# in-cluster; that wiring isn't built yet.

.PHONY: harbor-chart-project package-chart publish-chart verify-chart-published

harbor-chart-project: ## Create the Harbor "charts" project for chart artifacts (idempotent)
	@echo "==> Checking for Harbor project '$(HARBOR_CHARTS_PROJECT)'..."
	@count=$$(curl -sf -u admin:$(HARBOR_ADMIN_PASSWORD) \
		"http://$(HARBOR_HOST)/api/v2.0/projects?name=$(HARBOR_CHARTS_PROJECT)" | jq 'length'); \
	if [ "$$count" -gt 0 ]; then \
		echo "==> Project '$(HARBOR_CHARTS_PROJECT)' already exists, skipping."; \
	else \
		echo "==> Creating project '$(HARBOR_CHARTS_PROJECT)'..."; \
		curl -sf -u admin:$(HARBOR_ADMIN_PASSWORD) \
			-X POST "http://$(HARBOR_HOST)/api/v2.0/projects" \
			-H "Content-Type: application/json" \
			-d '{"project_name": "$(HARBOR_CHARTS_PROJECT)", "public": false}'; \
		echo "==> Project '$(HARBOR_CHARTS_PROJECT)' created."; \
	fi

package-chart: ## Package charts/base-webapp into dist/base-webapp-<version>.tgz
	@mkdir -p $(CHART_DIST_DIR)
	@helm package $(CHART_DIR) -d $(CHART_DIST_DIR)

# Credentials go straight to "helm push"/"helm pull" via --username/--password
# rather than a separate "helm registry login" step: on macOS, login persists
# the credential through docker-credential-osxkeychain, which conflicts with
# stale/foreign keychain entries left behind by earlier Harbor installs and
# fails outright ("item already exists in the keychain"). Inline flags avoid
# that shared, stateful credential store entirely and behave identically.
publish-chart: package-chart harbor-chart-project ## Push the packaged base-webapp chart to Harbor as an OCI artifact
	@chart_name=$$(basename $(CHART_DIR)); \
	chart_tgz=$$(ls -t $(CHART_DIST_DIR)/$$chart_name-*.tgz | head -n1); \
	chart_version=$$(basename $$chart_tgz .tgz | sed "s/^$$chart_name-//"); \
	echo "==> Publishing $$chart_tgz to oci://$(HARBOR_HOST)/$(HARBOR_CHARTS_PROJECT)..."; \
	helm push $$chart_tgz "oci://$(HARBOR_HOST)/$(HARBOR_CHARTS_PROJECT)" \
		--plain-http --username admin --password $(HARBOR_ADMIN_PASSWORD); \
	echo "==> Published: oci://$(HARBOR_HOST)/$(HARBOR_CHARTS_PROJECT)/$$chart_name:$$chart_version"
	@$(MAKE) verify-chart-published

# Second time a template change has shipped without a matching Chart.yaml
# version bump (first was the HTTPRoute/Ingress rework, 0.1.1 vs 0.2.0; this
# one was the secretName/envFrom feature landing under the still-published
# 0.4.0). Catches drift by re-rendering whatever Harbor has at the local
# Chart.yaml version and diffing it against the local working tree -- if
# someone edited templates without bumping the version, this fails loudly
# instead of silently shipping stale behavior under an old tag.
verify-chart-published: ## Diff local chart templates against the matching version already published in Harbor, if any (fails loudly on mismatch)
	@if [ -z "$(HARBOR_ADMIN_PASSWORD)" ]; then \
		echo "HARBOR_ADMIN_PASSWORD must be set (no default -- see the comment by its declaration)."; \
		exit 1; \
	fi
	@chart_name=$$(basename $(CHART_DIR)); \
	chart_version=$$(grep '^version:' $(CHART_DIR)/Chart.yaml | awk '{print $$2}'); \
	tmp_dir=$$(mktemp -d); \
	echo "==> Checking whether $$chart_name:$$chart_version is already published in Harbor..."; \
	if helm pull "oci://$(HARBOR_HOST)/$(HARBOR_CHARTS_PROJECT)/$$chart_name" \
		--version "$$chart_version" --plain-http \
		--username admin --password $(HARBOR_ADMIN_PASSWORD) \
		-d "$$tmp_dir" >/dev/null 2>&1; then \
		echo "==> Found published $$chart_name:$$chart_version -- diffing rendered templates against the local working tree..."; \
		mkdir -p "$$tmp_dir/extracted"; \
		tar xzf "$$tmp_dir/$$chart_name-$$chart_version.tgz" -C "$$tmp_dir/extracted"; \
		helm template "$$tmp_dir/extracted/$$chart_name" > "$$tmp_dir/published.yaml" 2>&1; \
		helm template $(CHART_DIR) > "$$tmp_dir/local.yaml" 2>&1; \
		if diff -u "$$tmp_dir/published.yaml" "$$tmp_dir/local.yaml" > "$$tmp_dir/diff.txt"; then \
			echo "==> OK: published $$chart_name:$$chart_version renders identically to the local working tree."; \
			rm -rf "$$tmp_dir"; \
		else \
			echo ""; \
			echo "!! MISMATCH: Harbor's $$chart_name:$$chart_version does not render identically to the"; \
			echo "!! local working tree at the same version. Templates were edited without bumping"; \
			echo "!! Chart.yaml's version -- bump the version and republish."; \
			echo ""; \
			cat "$$tmp_dir/diff.txt"; \
			rm -rf "$$tmp_dir"; \
			exit 1; \
		fi; \
	else \
		echo "==> $$chart_name:$$chart_version is not published in Harbor yet (expected for a pending new version) -- nothing to verify."; \
		rm -rf "$$tmp_dir"; \
	fi

# ------------------------------------------------------------------------------
# qrcode-api test-deploy (local smoke-test, not part of GitHub Actions)
# ------------------------------------------------------------------------------

# Kubernetes Secrets are namespace-scoped: qrcode-api's pod (namespace
# qrcode-api) can't reference the Postgres Secret living in namespace
# postgres, so it needs its own copy of the same credential here.
.PHONY: qrcode-api

qrcode-api: postgres ## Deploy qrcode-api (base-webapp chart) to the local cluster for smoke-testing
	@if [ -z "$(POSTGRES_PASSWORD)" ]; then \
		echo "POSTGRES_PASSWORD must be set (same value used by 'make postgres')."; \
		exit 1; \
	fi
	@echo "==> Creating $(QRCODE_API_NAMESPACE) namespace..."
	@kubectl create namespace $(QRCODE_API_NAMESPACE) --dry-run=client -o yaml | kubectl apply -f -
	@kubectl label namespace $(QRCODE_API_NAMESPACE) istio.io/dataplane-mode=ambient --overwrite
	@echo "==> Creating/updating qrcode-api's DB credentials Secret..."
	@kubectl create secret generic $(QRCODE_API_DB_SECRET_NAME) \
		--namespace $(QRCODE_API_NAMESPACE) \
		--from-literal=DB_USERNAME=$(POSTGRES_USER) \
		--from-literal=DB_PASSWORD=$(POSTGRES_PASSWORD) \
		--dry-run=client -o yaml | kubectl apply -f -
	@echo "==> Installing/upgrading the qrcode-api release..."
	@helm upgrade --install $(QRCODE_API_RELEASE) $(CHART_DIR) \
		--namespace $(QRCODE_API_NAMESPACE) \
		-f $(QRCODE_API_VALUES) \
		--wait --timeout 5m
	@echo "==> Deployed. Try:"
	@echo "    kubectl get pods -n $(QRCODE_API_NAMESPACE)"
	@echo "    kubectl get httproute -n $(QRCODE_API_NAMESPACE)"
	@echo "    curl http://qrcode-api.k8s.orb.local/actuator/health"
