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

## 6. Production hardening (beyond the public demo)

For a real production rollout (not the open review deployment), change the `prod` overlay to:

- `SPRING_PROFILES_ACTIVE: "prod,secure"` and set `OAUTH_ISSUER_URI` → enables JWT auth.
- `SETTLEMENT_SEED_ON_STARTUP: "false"` → no synthetic data.
- `SPRING_JPA_HIBERNATE_DDL_AUTO: "validate"` → schema owned by Flyway/Liquibase migrations.
- Replace the in-cluster Postgres with a managed DB (RDS / Cloud SQL / Azure DB) and source
  `DB_USER` / `DB_PASSWORD` from an external secrets manager (External Secrets Operator →
  AWS Secrets Manager / GCP Secret Manager / Azure Key Vault) instead of the committed stubs.
- Put TLS on the ingress (cert-manager) and restrict ingress to known clients if needed.
```
