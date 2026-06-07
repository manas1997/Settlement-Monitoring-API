# 📋 Settlement Monitoring API — Step-by-Step Deployment Guide

This guide walks you through the complete deployment pipeline with all four steps.

---

## Prerequisites

Before you start, ensure you have:

```bash
# Check kubectl is installed
kubectl version --client

# Check docker is installed
docker --version

# Check Kustomize is available (either system-wide or via kubectl)
kustomize version || kubectl version

# Have a Kubernetes cluster running and active
kubectl get nodes
# Should show: cluster nodes in Ready state
```

---

## 🔄 Four-Step Deployment Pipeline

### Step 1️⃣: Install kubectl + kustomize

**What this does:**
- Downloads latest kubectl for your OS
- Downloads and installs kustomize
- Makes both tools available in your PATH

**For cloud CI/CD** (GitHub Actions, GitLab CI, etc.):
```bash
# Linux (CI runners use this)
curl -sLo kubectl "https://dl.k8s.io/release/$(curl -sL https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
chmod +x kubectl && sudo mv kubectl /usr/local/bin/

curl -sL "https://raw.githubusercontent.com/kubernetes-sigs/kustomize/master/hack/install_kustomize.sh" | bash
sudo mv kustomize /usr/local/bin/

# Verify
kubectl version --client
kustomize version
```

**For macOS (local development):**
```bash
# Install via Homebrew (easier)
brew install kubectl kustomize

# Verify
kubectl version --client
kustomize version
```

**Result:**
```
Client Version: v1.30.0
Kustomization version: v5.8.1
```

---

### Step 2️⃣: Configure kubeconfig (decode base64)

**What this does:**
- Sets up authentication to your Kubernetes cluster
- Decodes a base64-encoded kubeconfig file
- Places it in `~/.kube/config` with proper permissions

**Why base64?**
- Safe to store in GitHub Secrets or CI environment variables
- Can't accidentally expose credentials in logs or configs

#### Local Development (Cloud Cluster)

If you have a cloud cluster (EKS/GKE/AKS) and a local kubeconfig:

```bash
# Your kubeconfig is already at ~/.kube/config (set up by cloud CLI tools)
# Verify it's working:
kubectl get nodes
kubectl get namespaces

# If not, copy your cloud kubeconfig:
# AWS EKS:
aws eks update-kubeconfig --region us-east-1 --name my-cluster

# GKE:
gcloud container clusters get-credentials my-cluster --zone us-east-1a

# AKS:
az aks get-credentials --resource-group my-rg --name my-cluster
```

#### CI/CD Setup (GitHub Actions / GitLab CI)

In your CI environment (GitHub Actions example):

```bash
# Step 1: On your machine with cluster access, encode your kubeconfig
base64 -w0 ~/.kube/config > kubeconfig.b64

# Step 2: Copy the contents and add to GitHub Secrets
# Settings → Secrets and variables → Actions → New repository secret
# Name: KUBE_CONFIG_B64
# Value: <paste contents of kubeconfig.b64>

# Step 3: In CI, decode and use it
echo "${{ secrets.KUBE_CONFIG_B64 }}" | base64 -d > "$HOME/.kube/config"
chmod 600 "$HOME/.kube/config"

# Verify
kubectl get namespaces
```

**Result:**
```
NAME              STATUS   AGE
settlement-dev    Active   2d
settlement-prod   Active   5d
default           Active   10d
```

---

### Step 3️⃣: Set image and apply with client-side validation

**What this does:**
- Points the Kustomize overlay at your Docker image
- Builds the Kubernetes manifests from the overlay
- Applies them to the cluster with client-side validation only (no need for running API server)

#### Step 3a: Set the image

```bash
# Navigate to the overlay directory
cd deploy/k8s/overlays/dev

# Set your image (replace with your actual image)
IMAGE=ghcr.io/your-username/settlement-monitoring-api:v1.0.0
kustomize edit set image settlement-monitoring-api="$IMAGE"

# Verify the image was set
grep "newName:" kustomization.yaml
# Should show: newName: ghcr.io/your-username/settlement-monitoring-api
```

#### Step 3b: Build and apply manifests

```bash
# Build to see what will be deployed
kustomize build . | head -50
# Shows: Namespace, ServiceAccount, ConfigMaps, Deployment, Service, HPA, Ingress, etc.

# Apply to cluster with client-side validation only
# (--validate=client skips connection to API server for OpenAPI schema)
kustomize build . | kubectl apply -f - --validate=client

# Result:
# namespace/settlement-dev created
# configmap/settlement-config created
# secret/settlement-db-secrets created
# deployment.apps/settlement-monitoring-api created
# service/settlement-monitoring-api created
# horizontalpodautoscaler.autoscaling/settlement-monitoring-api created
# ingress.networking.k8s.io/settlement-monitoring-api created
# service/settlement-postgres created
```

#### What gets deployed:

| Resource | Purpose |
|---|---|
| **Namespace** | Isolated environment (settlement-dev / settlement-review / settlement-prod) |
| **ConfigMap** | App config (profiles, seed flag, Java settings) |
| **Secret** | Postgres & DB passwords (hardcoded in demo; use External Secrets for prod) |
| **Deployment** | API pod(s) with readiness/liveness probes, init container to wait for Postgres |
| **Service** | LoadBalancer type (exposes API publicly, no login) |
| **HPA** | Auto-scales replicas based on CPU/memory |
| **Ingress** | Optional; used if you have ingress-nginx controller |
| **StatefulSet (Postgres)** | In-cluster database with PVC for data |

---

### Step 4️⃣: Wait for rollout

**What this does:**
- Polls the deployment until all pods are ready
- Ensures the API is running and healthy
- Times out if it takes too long (default 180 seconds)

```bash
# Get the namespace (varies by overlay)
NS="settlement-dev"  # or settlement-review / settlement-prod

# Wait for rollout to complete
kubectl -n "$NS" rollout status deployment/settlement-monitoring-api --timeout=180s

# Output while waiting:
# Waiting for deployment "settlement-monitoring-api" to be rolled out...
# deployment "settlement-monitoring-api" successfully rolled out

# Additional status checks
kubectl -n "$NS" get pods -l app.kubernetes.io/name=settlement-monitoring-api
# Should show: 2 Running, 2 Ready pods

kubectl -n "$NS" get deployment settlement-monitoring-api
# Should show: READY 2/2, UP-TO-DATE 2/2, AVAILABLE 2/2
```

**Troubleshooting:**

If rollout hangs or fails:

```bash
# Check pod status
kubectl -n "$NS" get pods

# Describe a failing pod
kubectl -n "$NS" describe pod <pod-name>

# Check logs (API container)
kubectl -n "$NS" logs deployment/settlement-monitoring-api -c app

# Check database init logs
kubectl -n "$NS" logs deployment/settlement-monitoring-api -c wait-for-db

# View recent events
kubectl -n "$NS" get events --sort-by='.lastTimestamp'
```

---

## 🚀 Complete Flow (Copy-Paste Ready)

### For Cloud Deployment

```bash
#!/bin/bash
set -e

# Configuration
OWNER="your-github-username"
REPO="settlement-monitoring-api"
IMAGE="ghcr.io/$OWNER/$REPO:v1.0.0"
OVERLAY="dev"
NS="settlement-$OVERLAY"

echo "🚀 Starting 4-step deployment pipeline"
echo "   Image: $IMAGE"
echo "   Target: $NS"
echo ""

# STEP 1: Install tools (usually pre-installed in cloud CI)
echo "📦 Step 1: Verify kubectl + kustomize"
kubectl version --client | head -1
kustomize version | head -1

# STEP 2: Verify kubeconfig
echo ""
echo "🔑 Step 2: Verify kubeconfig (authentication)"
kubectl get namespaces | head -3

# STEP 3: Set image and apply
echo ""
echo "🔧 Step 3: Set image and apply manifests"
cd deploy/k8s/overlays/$OVERLAY
kustomize edit set image settlement-monitoring-api="$IMAGE"
echo "   Building and applying manifests..."
kustomize build . | kubectl apply -f - --validate=client

# STEP 4: Wait for rollout
echo ""
echo "⏳ Step 4: Waiting for deployment to be ready (timeout: 180s)"
kubectl -n "$NS" rollout status deployment/settlement-monitoring-api --timeout=180s

# Success!
echo ""
echo "✅ Deployment complete!"
echo ""
echo "📋 Deployment info:"
kubectl -n "$NS" get deployment settlement-monitoring-api
kubectl -n "$NS" get svc settlement-monitoring-api

# Get external IP/hostname
EXT=$(kubectl -n "$NS" get svc settlement-monitoring-api -o jsonpath='{.status.loadBalancer.ingress[0].ip}{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null)
if [ -n "$EXT" ]; then
    echo ""
    echo "🌐 Public URL: http://$EXT"
    echo "   Health:   http://$EXT/api/v1/settlement/health"
    echo "   Analytics: http://$EXT/api/v1/analytics/settlement"
    echo "   Swagger:  http://$EXT/swagger-ui.html"
else
    echo ""
    echo "⚠️  No external IP yet. Use port-forward:"
    echo "   kubectl -n $NS port-forward svc/settlement-monitoring-api 8080:80"
fi
```

Save as `deploy-4-steps.sh`, make executable, and run:
```bash
chmod +x deploy-4-steps.sh
OWNER=your-user OVERLAY=dev ./deploy-4-steps.sh
```

### For Local Minikube

```bash
#!/bin/bash
set -e

# Setup cluster (do this once)
minikube start --driver=hyperkit --cpus=4 --memory=4096

# In another terminal: minikube tunnel

# Deployment
IMAGE="settlement-api:local"
OVERLAY="dev"
NS="settlement-$OVERLAY"

docker build -t "$IMAGE" .
minikube image load "$IMAGE"

# Steps 1-4
kubectl context use-context minikube
cd deploy/k8s/overlays/$OVERLAY
kustomize edit set image settlement-monitoring-api="$IMAGE"
kustomize build . | kubectl apply -f - --validate=client
kubectl -n "$NS" rollout status deployment/settlement-monitoring-api --timeout=180s

# Access
echo "API at: http://127.0.0.1/api/v1/analytics/settlement"
```

---

## ✅ Verification Checklist

After all 4 steps complete:

- [ ] **kubectl installed?** → `kubectl version --client`
- [ ] **kustomize installed?** → `kustomize version`
- [ ] **kubeconfig active?** → `kubectl get nodes` (shows nodes)
- [ ] **Image set in kustomization?** → `grep newName deploy/k8s/overlays/dev/kustomization.yaml`
- [ ] **Manifests applied?** → `kubectl -n settlement-dev get all`
- [ ] **Pods running?** → `kubectl -n settlement-dev get pods` (shows Running)
- [ ] **Service ready?** → `kubectl -n settlement-dev get svc settlement-monitoring-api` (shows IP/hostname)
- [ ] **Rollout success?** → No errors from Step 4
- [ ] **API responding?** → `curl http://<IP>/api/v1/settlement/health`

---

## 📊 Understanding the Deployment

### Why 4 Steps?

1. **Tools** — Dependencies for the pipeline
2. **Auth** — Access to cluster
3. **Apply** — Deploy manifests with the right image
4. **Verify** — Ensure everything started correctly

### Client-Side Validation (`--validate=client`)

By default, `kubectl apply` tries to validate against a running Kubernetes API server's OpenAPI schema. In our case:
- **Local dev:** The API server is the cluster (works fine)
- **CI/CD:** No running cluster in the runner, so we skip server-side validation
- **Solution:** Use `--validate=client` to validate locally only

This is why we added `--validate=client` to the kustomize-deploy action.

### Timeouts & Health Checks

The deployment waits 180 seconds for rollout to complete because:
- Pulling Docker image from registry (~10-30s)
- Postgres init container starts database (~5-10s)
- API container starts Spring Boot (~30-60s)
- Readiness probe succeeds (healthy=true)

If it takes longer, check:
- Image pull errors: `kubectl logs <pod> -c app`
- Database init: `kubectl logs <pod> -c wait-for-db`
- Resources: `kubectl describe node` (any pressure?)

---

## 🔗 Next Steps

After successful deployment:

1. **Test the API**
   ```bash
   curl http://$EXT/api/v1/analytics/settlement | jq
   ```

2. **View logs**
   ```bash
   kubectl -n settlement-dev logs -f deployment/settlement-monitoring-api
   ```

3. **Scale up**
   ```bash
   kubectl -n settlement-dev scale deployment settlement-monitoring-api --replicas=5
   ```

4. **Rollback** (if needed)
   ```bash
   kubectl -n settlement-dev rollout undo deployment/settlement-monitoring-api
   ```

5. **See deployment history**
   ```bash
   kubectl -n settlement-dev rollout history deployment/settlement-monitoring-api
   ```

---

## 🚨 Common Issues

| Issue | Cause | Fix |
|---|---|---|
| "kubectl: command not found" | kubectl not installed | Homebrew: `brew install kubectl` |
| "Cannot connect to cluster" | kubeconfig not set | `aws eks update-kubeconfig ...` or copy ~/.kube/config |
| "ImagePullBackOff" | Docker image doesn't exist or private | Push image, make repo public |
| "CrashLoopBackOff" on app | App can't reach Postgres | Check init container: `kubectl logs <pod> -c wait-for-db` |
| "CrashLoopBackOff" on Postgres | PVC can't bind | Check node capacity: `kubectl describe node` |
| Rollout timeout | Pods stuck in Pending | Check resources, node status: `kubectl top nodes` |

---

## 📚 Reference

- **Kubectl docs:** https://kubernetes.io/docs/reference/kubectl/
- **Kustomize guide:** https://kustomize.io/
- **GitHub Actions with Kubernetes:** https://docs.github.com/en/actions/deployment/targeting-different-environments/deployment-status-checks

