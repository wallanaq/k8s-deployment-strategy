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
HARBOR_ADMIN_PASSWORD   ?= Harbor12345

CHART_DIR      ?= charts/base-webapp
CHART_DIST_DIR ?= dist

# ------------------------------------------------------------------------------
# qrcode-api test-deploy (local smoke-test, not part of GitHub Actions)
# ------------------------------------------------------------------------------
QRCODE_API_NAMESPACE ?= qrcode-api
QRCODE_API_RELEASE   ?= qrcode-api
QRCODE_API_VALUES    ?= qrcode-api/infra/helm/values.yaml
POSTGRES_DIR          ?= qrcode-api/infra/postgres

# GHCR image-pull Secret. Not needed today -- the qrcode-api package is public --
# kept for the day a package on this registry goes private again.
GHCR_REGISTRY         ?= ghcr.io
GHCR_PULL_SECRET_NAME ?= ghcr-pull-secret
GHCR_USERNAME         ?=
GHCR_TOKEN            ?=

# Postgres credentials (qrcode-api's dependency, not the Harbor admin account).
POSTGRES_SECRET_NAME ?= qrcode-api-postgres-credentials
POSTGRES_USER        ?= qrcode_api
POSTGRES_PASSWORD    ?= qrcode_api
POSTGRES_DB          ?= pix_qrcode

.PHONY: help istio harbor harbor-chart-project package-chart publish-chart \
	cluster create-ghcr-pull-secret create-postgres-secret install-postgres \
	test-deploy-qrcode-api

help: ## Show this help message
	@echo "Available targets:"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'

# ------------------------------------------------------------------------------
# Install
# ------------------------------------------------------------------------------

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

harbor: ## Install Harbor (OCI registry, plain HTTP) at http://harbor.k8s.orb.local (idempotent)
	@echo "==> Adding/updating the Harbor Helm repo..."
	@helm repo add harbor https://helm.goharbor.io >/dev/null 2>&1 || true
	@helm repo update harbor >/dev/null
	@echo "==> Creating $(HARBOR_NAMESPACE) namespace..."
	@kubectl create namespace $(HARBOR_NAMESPACE) --dry-run=client -o yaml | kubectl apply -f -
	@echo "==> Installing/upgrading Harbor via Helm..."
	@echo "    Exposed as a LoadBalancer Service named 'harbor', which OrbStack resolves at"
	@echo "    http://$(HARBOR_HOST) automatically -- no Ingress/Gateway needed."
	@helm upgrade --install harbor harbor/harbor \
		--namespace $(HARBOR_NAMESPACE) \
		--set expose.type=loadBalancer \
		--set expose.loadBalancer.name=harbor \
		--set expose.tls.enabled=false \
		--set externalURL=http://$(HARBOR_HOST) \
		--set harborAdminPassword=$(HARBOR_ADMIN_PASSWORD) \
		--wait --timeout 10m
	@echo "==> Harbor is up at http://$(HARBOR_HOST) (user: admin)"

# ------------------------------------------------------------------------------
# Helm chart publishing (base-webapp -> Harbor OCI, local/manual step)
# ------------------------------------------------------------------------------
# GitHub Actions' hosted runners can't reach $(HARBOR_HOST) (it's only routable
# inside the local OrbStack cluster), so this is run by hand from a machine that
# can. Argo CD will later pull the published chart straight from Harbor
# in-cluster; that wiring isn't built yet.

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

# ------------------------------------------------------------------------------
# qrcode-api test-deploy (local smoke-test, not part of GitHub Actions)
# ------------------------------------------------------------------------------

cluster: ## Ensure the qrcode-api namespace exists and is onboarded to the Istio ambient dataplane (idempotent)
	@kubectl create namespace $(QRCODE_API_NAMESPACE) --dry-run=client -o yaml | kubectl apply -f -
	@kubectl label namespace $(QRCODE_API_NAMESPACE) istio.io/dataplane-mode=ambient --overwrite

create-ghcr-pull-secret: cluster ## Create/update a GHCR image-pull Secret (only needed if a GHCR package goes private)
	@if [ -z "$(GHCR_USERNAME)" ] || [ -z "$(GHCR_TOKEN)" ]; then \
		echo "GHCR_USERNAME and GHCR_TOKEN (a PAT with read:packages) must be set to create this secret."; \
		exit 1; \
	fi
	@kubectl create secret docker-registry $(GHCR_PULL_SECRET_NAME) \
		--namespace $(QRCODE_API_NAMESPACE) \
		--docker-server=$(GHCR_REGISTRY) \
		--docker-username=$(GHCR_USERNAME) \
		--docker-password=$(GHCR_TOKEN) \
		--dry-run=client -o yaml | kubectl apply -f -

create-postgres-secret: cluster ## Create/update the qrcode-api Postgres credentials Secret (idempotent)
	@kubectl create secret generic $(POSTGRES_SECRET_NAME) \
		--namespace $(QRCODE_API_NAMESPACE) \
		--from-literal=POSTGRES_USER=$(POSTGRES_USER) \
		--from-literal=POSTGRES_PASSWORD=$(POSTGRES_PASSWORD) \
		--from-literal=POSTGRES_DB=$(POSTGRES_DB) \
		--dry-run=client -o yaml | kubectl apply -f -

install-postgres: cluster create-postgres-secret ## Deploy plain-manifest, PVC-backed Postgres for qrcode-api (idempotent)
	@echo "==> Applying Postgres manifests from $(POSTGRES_DIR)..."
	@kubectl apply -f $(POSTGRES_DIR)
	@echo "==> Waiting for qrcode-api-postgres to become available..."
	@kubectl rollout status deployment/qrcode-api-postgres \
		--namespace $(QRCODE_API_NAMESPACE) --timeout=180s

test-deploy-qrcode-api: cluster install-postgres ## Deploy qrcode-api (base-webapp chart) to the local cluster for smoke-testing
	@echo "==> Installing/upgrading the qrcode-api release..."
	@helm upgrade --install $(QRCODE_API_RELEASE) $(CHART_DIR) \
		--namespace $(QRCODE_API_NAMESPACE) \
		-f $(QRCODE_API_VALUES) \
		--wait --timeout 5m
	@echo "==> Deployed. Try:"
	@echo "    kubectl get pods -n $(QRCODE_API_NAMESPACE)"
	@echo "    kubectl get httproute -n $(QRCODE_API_NAMESPACE)"
	@echo "    curl http://qrcode-api.k8s.orb.local/actuator/health"
