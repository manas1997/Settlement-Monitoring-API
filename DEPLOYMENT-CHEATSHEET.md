# 🚀 Settlement Monitoring API — Deployment Quick Reference

## Cloud Deployment (EKS / GKE / AKS)

### 1. Build & Push Image
```bash
OWNER=your-github-username
IMAGE=ghcr.io/$OWNER/settlement-monitoring-api:v1.0.0
docker build -t "$IMAGE" .
docker push "$IMAGE"
```

### 2. Deploy (dev / review / prod)
```bash
# Deploy to dev:
cd deploy/k8s/overlays/dev
kustomize edit set image settlement-monitoring-api="$IMAGE"
kubectl apply -k .
kubectl -n settlement-dev rollout status deployment/settlement-monitoring-api

# Or use the helper script:
OVERLAY=dev IMAGE=$IMAGE ./scripts/deploy.sh
```

### 3. Get Public URL
```bash
kubectl -n settlement-dev get svc settlement-monitoring-api -w
# Wait for EXTERNAL-IP to appear
# Then: http://<EXTERNAL-IP>/api/v1/analytics/settlement
```

---

## Local Development (Minikube / Kind)

### Minikube (macOS)
```bash
# Terminal 1: Setup + deploy
brew install minikube hyperkit
./scripts/local-cluster-setup.sh minikube
./scripts/local-deploy.sh minikube dev

# Terminal 2: Keep tunnel running (required for LoadBalancer)
minikube tunnel

# Terminal 3: Monitor health
./scripts/health-check.sh dev
```

### Kind (Docker-based)
```bash
# Setup + deploy (single command, no separate tunnel)
./scripts/local-cluster-setup.sh kind
./scripts/local-deploy.sh kind dev

# Access at: http://localhost:8080
./scripts/health-check.sh dev
```

---

## Testing the API (No Auth Required)

```bash
# Health check
curl -s http://$EXT/api/v1/settlement/health

# Settlement analytics
curl -s http://$EXT/api/v1/analytics/settlement | jq

# Swagger interactive docs
curl http://$EXT/swagger-ui.html

# Prometheus metrics
curl -s http://$EXT/actuator/prometheus | head -20
```

---

## Debugging & Monitoring

### Check Pod Status
```bash
kubectl -n settlement-dev get pods
kubectl -n settlement-dev describe pod <pod-name>
```

### View Logs
```bash
kubectl -n settlement-dev logs deployment/settlement-monitoring-api -f --all-containers=true
```

### Port-Forward (if no external IP)
```bash
kubectl -n settlement-dev port-forward svc/settlement-monitoring-api 8080:80 &
curl -s localhost:8080/api/v1/analytics/settlement | jq
kill %1  # stop port-forward
```

### Monitor from Inside Cluster
```bash
# Long-running health monitor
kubectl -n settlement-dev run monitor --image=curlimages/curl:8.10.1 -it --rm --restart=Never -- \
  sh -c 'while true; do date; curl -s http://settlement-monitoring-api/actuator/health; echo; sleep 5; done'

# One-shot analytics check
kubectl -n settlement-dev run curl --image=curlimages/curl -it --rm --restart=Never -- \
  curl -s http://settlement-monitoring-api/api/v1/analytics/settlement | jq
```

---

## Common Operations

### Rollback a Deployment
```bash
kubectl -n settlement-dev rollout undo deployment/settlement-monitoring-api
kubectl -n settlement-dev rollout status deployment/settlement-monitoring-api
```

### Restart Pods (force new deployment)
```bash
kubectl -n settlement-dev rollout restart deployment/settlement-monitoring-api
```

### Delete Everything & Redeploy
```bash
kubectl -n settlement-dev delete all -l app.kubernetes.io/name=settlement-monitoring-api
cd deploy/k8s/overlays/dev && kubectl apply -k .
```

### Scale Replicas
```bash
kubectl -n settlement-dev scale deployment settlement-monitoring-api --replicas=3
```

---

## CI/CD Pipeline

### Trigger on GitHub
- **PR** → deploys to `settlement-review` (auto-cleanup after PR closes)
- **Push to main** → deploys to `settlement-dev`
- **Tag v\*** → deploys to `settlement-prod` (gated approval)

### Manual Deploy via CD
```bash
# Just create a PR or merge to main; GitHub Actions handles the rest
git checkout -b my-feature
# ... make changes ...
git push origin my-feature
# → Creates PR → auto-deploys to settlement-review
```

### Production Release
```bash
git tag v1.0.0
git push origin v1.0.0
# → Triggers CD pipeline → asks for approval → deploys to settlement-prod
```

---

## Environment Details

| Env | Namespace | Replicas | Access | Use Case |
|---|---|---|---|---|
| **review** | settlement-review | 1 | Public LoadBalancer | Per-PR preview |
| **dev** | settlement-dev | 2 | Public LoadBalancer | Integration testing |
| **prod** | settlement-prod | 3 | Public LoadBalancer | Users (no auth) |

All have PostgreSQL, no authentication (challenge requirement), seeded demo data.

---

## Troubleshooting Checklist

- [ ] Cluster active? → `kubectl get nodes`
- [ ] Pods running? → `kubectl -n settlement-dev get pods`
- [ ] Service ready? → `kubectl -n settlement-dev get svc settlement-monitoring-api`
- [ ] Pod logs? → `kubectl -n settlement-dev logs <pod> -c app`
- [ ] Port-forward working? → `kubectl -n settlement-dev port-forward svc/settlement-monitoring-api 8080:80`
- [ ] External IP stuck? → minikube tunnel / kind uses localhost:8080

---

## Key Files

- **Manifests**: `deploy/k8s/base/` (environment-agnostic)
- **Overlays**: `deploy/k8s/overlays/dev|review|prod/` (environment-specific)
- **CI/CD**: `.github/workflows/cd.yml`, `.github/actions/kustomize-deploy/`
- **Scripts**: `scripts/deploy.sh`, `scripts/health-check.sh`, `scripts/local-*`
- **Docker**: `Dockerfile`, `docker-compose.yml` (for local dev without K8s)

