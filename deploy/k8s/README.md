# Kubernetes Deployment — Settlement Monitoring API

Deploys the API + an in-cluster PostgreSQL to a cloud cluster (EKS / GKE / AKS) using
**Kustomize overlays** for three environments and a **GitHub Actions CD pipeline**.

```
deploy/k8s
├── base/                 # environment-agnostic manifests
│   ├── postgres.yaml     # Secret + PVC + Deployment + Service
│   ├── app-config.yaml   # ConfigMap + Secret (DB creds)
│   ├── app.yaml          # API Deployment (init-wait, probes, hardened) + Service
│   ├── hpa.yaml          # HorizontalPodAutoscaler
│   ├── ingress.yaml      # Ingress (nginx by default)
│   └── kustomization.yaml
├── components/
│   └── loadbalancer/     # exposes the API Service as a public LoadBalancer
└── overlays/
    ├── review/           # per-PR ephemeral env  (ns: settlement-review)
    ├── dev/              # integration env        (ns: settlement-dev)
    └── prod/             # production             (ns: settlement-prod)
```

> **Public access (challenge requirement).** All three overlays run the **`prod` profile
> only — the `secure`/JWT profile is intentionally OFF** so the deployed app is reachable
> **without login**, and they seed the demo dataset so endpoints return data. Also make the
> GitHub repo **public** and the **GHCR image package public** (Packages → settlement-monitoring-api
> → Package settings → Change visibility → Public). Production hardening is noted at the bottom.

---

## 1. Prerequisites

- A running cluster (EKS / GKE / AKS) and `kubectl` pointed at it.
- `kustomize` (or `kubectl` ≥ 1.27, which has `-k` built in).
- An ingress controller (these manifests assume **ingress-nginx**) **or** switch the API
  Service to `type: LoadBalancer` for the simplest public URL (see §4).
- A container registry you can push to (GHCR / ECR / GCR / ACR).

---

## 2. Build & push the image

**GHCR (recommended — easy to make public):**

```bash
OWNER=<your-github-username-lowercase>
IMAGE=ghcr.io/$OWNER/settlement-monitoring-api:1.0.0
echo "$CR_PAT" | docker login ghcr.io -u "$OWNER" --password-stdin
docker build -t "$IMAGE" .
docker push "$IMAGE"
# Then: GitHub → Packages → make this package Public
```

<details><summary>ECR / GCR / ACR equivalents</summary>

```bash
# AWS ECR
aws ecr create-repository --repository-name settlement-monitoring-api || true
aws ecr get-login-password | docker login --username AWS --password-stdin <acct>.dkr.ecr.<region>.amazonaws.com
IMAGE=<acct>.dkr.ecr.<region>.amazonaws.com/settlement-monitoring-api:1.0.0

# GCP Artifact Registry
gcloud auth configure-docker <region>-docker.pkg.dev
IMAGE=<region>-docker.pkg.dev/<project>/<repo>/settlement-monitoring-api:1.0.0

# Azure ACR
az acr login --name <registry>
IMAGE=<registry>.azurecr.io/settlement-monitoring-api:1.0.0

docker build -t "$IMAGE" . && docker push "$IMAGE"
```
</details>

---

## 3. Deploy manually (any environment)

Point the overlay at your image, then apply:

```bash
cd deploy/k8s/overlays/dev          # or review / prod
kustomize edit set image settlement-monitoring-api="$IMAGE"
kubectl apply -k .                  # creates the namespace + Postgres + API

# watch it come up
kubectl -n settlement-dev rollout status deployment/settlement-monitoring-api
kubectl -n settlement-dev get pods,svc,ingress
```

The API waits for Postgres via an init container, so a cold cluster won't crash-loop.

> Change the demo passwords before any real use:
> edit `POSTGRES_PASSWORD` in `base/postgres.yaml` and `DB_PASSWORD` in `base/app-config.yaml`
> (or, better, patch them per-overlay from a secrets manager — see §6).

---

## 4. Get a public URL (no login)

**Option A — LoadBalancer (default, simplest):** every overlay already includes the
`components/loadbalancer` component, so the API Service is created as `type: LoadBalancer`.
Just read the external address:

```bash
kubectl -n settlement-dev get svc settlement-monitoring-api -w   # wait for EXTERNAL-IP
```

(If you'd rather use an Ingress only, drop the `components:` entry from the overlay.)

Then verify (no auth needed):

```bash
EXT=<external-ip-or-hostname>
curl -s "http://$EXT/api/v1/settlement/health"
curl -s "http://$EXT/api/v1/analytics/settlement" | jq
# Swagger UI: http://$EXT/swagger-ui.html
```

**Option B — Ingress:** set a real host in the overlay's ingress patch (e.g.
`dev.settlement.yourdomain.com`), point DNS at the ingress controller's external address,
and hit `https://that-host/...`.

---

## 4.1 Quick-start: Deploy and get public URL (cloud cluster)

**Prerequisites:** kubectl pointed at a live cloud cluster (EKS/GKE/AKS), an image repo (GHCR/ECR/GCR/ACR).

**Step 1: Build & push your image**

```bash
OWNER=your-github-username-lowercase  # or ECR account, GCP project, etc.
IMAGE=ghcr.io/$OWNER/settlement-monitoring-api:1.0.0

# For GHCR:
echo "$GITHUB_TOKEN" | docker login ghcr.io -u "$OWNER" --password-stdin
docker build -t "$IMAGE" .
docker push "$IMAGE"
# Make image public: GitHub → Packages → settlement-monitoring-api → Settings → Change visibility
```

**Step 2: Deploy to dev (or review/prod)**

```bash
cd deploy/k8s/overlays/dev
kustomize edit set image settlement-monitoring-api="$IMAGE"
kubectl apply -k .
kubectl -n settlement-dev rollout status deployment/settlement-monitoring-api --timeout=300s
```

**Step 3: Get the public URL**

```bash
kubectl -n settlement-dev get svc settlement-monitoring-api -w   # Ctrl+C when EXTERNAL-IP appears
```

Watch until `EXTERNAL-IP` shows an IP (EKS/GKE/AKS) or CNAME (EKS NLB). Copy it.

**Step 4: Hit the API (no login required)**

```bash
EXT=<EXTERNAL-IP-or-CNAME>
curl -s "http://$EXT/api/v1/settlement/health"        # → "Settlement Monitoring API is running"
curl -s "http://$EXT/api/v1/analytics/settlement" | jq # → JSON with settlement stats
curl "http://$EXT/swagger-ui.html"                     # → open in browser for interactive docs
curl "http://$EXT/actuator/prometheus"                 # → Prometheus metrics for monitoring
```

---

## 4.2 Debugging: port-forward if no external IP yet

If the LoadBalancer is stuck in `<pending>` or you want to test without exposing publicly:

```bash
kubectl -n settlement-dev port-forward svc/settlement-monitoring-api 8080:80 &
# [1] <PID>

# In another terminal:
curl -s localhost:8080/api/v1/analytics/settlement | jq

# Logs while testing:
kubectl -n settlement-dev logs deployment/settlement-monitoring-api -f

# Stop port-forward:
kill %1
```

Useful diagnostics:

```bash
kubectl -n settlement-dev get pods                           # Are they Running/Ready?
kubectl -n settlement-dev get svc settlement-monitoring-api  # Service details
kubectl -n settlement-dev describe pod <pod-name>           # Why is it stuck?
kubectl -n settlement-dev logs <pod-name> --previous         # Crash logs
```

---

## 4.3 Monitor from inside the cluster

Spin up a debug pod inside the cluster that pings the API every 5 seconds (no login):

```bash
# Long-running health monitor (Ctrl+C to stop):
kubectl -n settlement-dev run monitor --image=curlimages/curl:8.10.1 -it --rm --restart=Never -- \
  sh -c 'while true; do date; curl -s http://settlement-monitoring-api/actuator/health; echo; sleep 5; done'
```

Or a one-shot curl against the analytics endpoint:

```bash
kubectl -n settlement-dev run curl --image=curlimages/curl -it --rm --restart=Never -- \
  curl -s http://settlement-monitoring-api/api/v1/analytics/settlement | jq
```

---

## 4.4 Local cluster (minikube / kind)

No cloud account? Use a local cluster for testing:

**Setup once:**

```bash
# macOS: install minikube + hyperkit
brew install minikube hyperkit

# Create a local cluster
minikube start --driver=hyperkit

# In another terminal, enable LoadBalancer support (tunneling):
minikube tunnel
# Keep this running; it bridges minikube's LoadBalancer to localhost
```

**Deploy (same as cloud):**

```bash
cd deploy/k8s/overlays/dev
IMAGE=settlement-api:test      # use local image name
docker build -t "$IMAGE" .
minikube image load "$IMAGE"   # inject into minikube

kustomize edit set image settlement-monitoring-api="$IMAGE"
kubectl apply -k .
kubectl -n settlement-dev rollout status deployment/settlement-monitoring-api
```

**Get local URL:**

```bash
kubectl -n settlement-dev get svc settlement-monitoring-api
# EXTERNAL-IP should show 127.0.0.1 (thanks to `minikube tunnel`)

curl -s "http://127.0.0.1/api/v1/analytics/settlement" | jq
```

(On **macOS with kind** instead: skip `minikube tunnel`; use `kubectl port-forward` above.)

---

## 4.5 Practical CI scenarios

**Scenario: PR triggers deploy-review**
- GitHub Actions builds & pushes image to GHCR
- `deploy-review` job runs `kustomize edit set image` + `kubectl apply`
- You get a link to `http://<LoadBalancer-IP>/swagger-ui.html` to review the PR

**Scenario: Merge to main triggers deploy-dev**
- Image pushed, deployment rolls out to `settlement-dev` namespace
- `kubectl -n settlement-dev get svc` gives you the dev URL for integration testing

**Scenario: Tag v1.0.0 triggers deploy-prod**
- Same flow, but gated (requires manual approval in GitHub)
- Deploys to `settlement-prod` namespace

**Rollback:**
```bash
kubectl -n settlement-dev rollout undo deployment/settlement-monitoring-api
kubectl -n settlement-dev rollout status deployment/settlement-monitoring-api
```

No-cluster smoke test (port-forward):

```bash
kubectl -n settlement-dev port-forward svc/settlement-monitoring-api 8080:80
curl -s localhost:8080/api/v1/analytics/settlement | jq
```

---

## 5. CD pipeline (review / dev / prod)

`.github/workflows/cd.yml` + the composite action `.github/actions/kustomize-deploy`:

| Trigger | Job | Target |
|---|---|---|
| Pull request | `deploy-review` | namespace `settlement-review` |
| Push to `main`/`master` | `deploy-dev` | namespace `settlement-dev` |
| Tag `v*` (e.g. `v1.0.0`) | `deploy-prod` | namespace `settlement-prod` (gated) |

Every run builds & pushes the image to GHCR, then the matching deploy job runs
`kustomize edit set image` + `kubectl apply` against that environment's cluster and waits
for the rollout.

**Setup once in GitHub:**

1. Create three **Environments** (Settings → Environments): `review`, `dev`, `prod`.
2. On each, add secret **`KUBE_CONFIG_B64`** = `base64 -w0 ~/.kube/config` for that cluster.
3. On **`prod`**, add **Required reviewers** so production needs manual approval.
4. Make the **GHCR package public** (so image pulls need no auth) and the **repo public**.

Cut a production release with:

```bash
git tag v1.0.0 && git push origin v1.0.0   # triggers gated prod deploy
```

---

## 6. Handy deployment scripts

Save these as `scripts/deploy.sh` for quick deploys:

```bash
#!/bin/bash
set -e

OWNER="${OWNER:-your-github-username}"
REPO="settlement-monitoring-api"
IMAGE="${IMAGE:-ghcr.io/$OWNER/$REPO:$(git rev-parse --short HEAD)}"
OVERLAY="${OVERLAY:-dev}"
NS="settlement-$OVERLAY"

echo "=== Building & pushing image: $IMAGE ==="
docker build -t "$IMAGE" .
docker push "$IMAGE"

echo "=== Deploying to $NS ==="
cd deploy/k8s/overlays/$OVERLAY
kustomize edit set image settlement-monitoring-api="$IMAGE"
kubectl apply -k .

echo "=== Waiting for rollout ==="
kubectl -n "$NS" rollout status deployment/settlement-monitoring-api --timeout=300s

echo "=== Getting external IP ==="
kubectl -n "$NS" get svc settlement-monitoring-api

echo "=== Done! Check: kubectl -n $NS get pods ==="
```

Run it:
```bash
OVERLAY=dev IMAGE=ghcr.io/your-user/settlement-monitoring-api:v1.0.0 ./scripts/deploy.sh
```

Or a simple health-check loop:

```bash
#!/bin/bash
OVERLAY="${1:-dev}"
NS="settlement-$OVERLAY"
EXT=$(kubectl -n "$NS" get svc settlement-monitoring-api -o jsonpath='{.status.loadBalancer.ingress[0].ip}{.status.loadBalancer.ingress[0].hostname}')

if [ -z "$EXT" ]; then
  echo "No external IP yet. Use port-forward:"
  kubectl -n "$NS" port-forward svc/settlement-monitoring-api 8080:80
  EXT="localhost:8080"
else
  echo "API reachable at: $EXT"
fi

while true; do
  echo "$(date): $(curl -s --max-time 2 http://$EXT/actuator/health -I | head -1)"
  sleep 5
done
```

Run it:
```bash
./scripts/health-check.sh dev
```

---

## 7. Troubleshooting

| Symptom | Diagnosis | Fix |
|---|---|---|
| Pod stuck in `Pending` | Resource quota exceeded | `kubectl describe pod <name>` → check node capacity |
| Pod crashes (CrashLoopBackOff) | App can't reach Postgres | Check init container logs: `kubectl logs <name> -c wait-for-db` |
| EXTERNAL-IP shows `<pending>` | LoadBalancer not assigned | Might take min, or cloud quota hit. Use `port-forward` to test. |
| `curl` times out | Pod not ready / wrong port | Check: `kubectl -n settlement-dev get pods`, test via `port-forward` |
| `connection refused` on `localhost:8080` | Port-forward not started or died | Restart: `kubectl -n settlement-dev port-forward svc/settlement-monitoring-api 8080:80` |

**Always check logs:**
```bash
kubectl -n settlement-dev logs deployment/settlement-monitoring-api -f --all-containers
```

**Restart everything:**
```bash
kubectl -n settlement-dev delete all -l app.kubernetes.io/name=settlement-monitoring-api
kubectl -n settlement-dev apply -k deploy/k8s/overlays/dev
```

---

## 8. Production hardening (beyond the public demo)

For a real production rollout (not the open review deployment), change the `prod` overlay to:

- `SPRING_PROFILES_ACTIVE: "prod,secure"` and set `OAUTH_ISSUER_URI` → enables JWT auth.
- `SETTLEMENT_SEED_ON_STARTUP: "false"` → no synthetic data.
- `SPRING_JPA_HIBERNATE_DDL_AUTO: "validate"` → schema owned by Flyway/Liquibase migrations.
- Replace the in-cluster Postgres with a managed DB (RDS / Cloud SQL / Azure DB) and source
  `DB_USER` / `DB_PASSWORD` from an external secrets manager (External Secrets Operator →
  AWS Secrets Manager / GCP Secret Manager / Azure Key Vault) instead of the committed stubs.
- Put TLS on the ingress (cert-manager) and restrict ingress to known clients if needed.
```
