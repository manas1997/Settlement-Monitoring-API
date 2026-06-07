#!/bin/bash
#
# deploy.sh — Quick helper to build, push, and deploy Settlement Monitoring API
#
# Usage:
#   OVERLAY=dev IMAGE=ghcr.io/your-user/settlement-monitoring-api:v1.0.0 ./scripts/deploy.sh
#   OVERLAY=review ./scripts/deploy.sh  # uses git short SHA as tag
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

OWNER="${OWNER:-unknown-user}"
REPO="${REPO:-settlement-monitoring-api}"
OVERLAY="${OVERLAY:-dev}"
NS="settlement-$OVERLAY"

# If IMAGE not set, build from git SHA
if [ -z "$IMAGE" ]; then
    SHA=$(cd "$REPO_ROOT" && git rev-parse --short HEAD)
    IMAGE="ghcr.io/$OWNER/$REPO:$SHA"
fi

echo "================================================================"
echo "🚀 Settlement Monitoring API Deployment"
echo "================================================================"
echo "Overlay:  $OVERLAY"
echo "Namespace: $NS"
echo "Image:    $IMAGE"
echo ""

# Step 1: Build
echo "📦 Building Docker image..."
docker build -t "$IMAGE" "$REPO_ROOT"

# Step 2: Push
echo "📤 Pushing image to registry..."
docker push "$IMAGE"

# Step 3: Deploy
echo "🔧 Applying Kustomize manifests..."
cd "$REPO_ROOT/deploy/k8s/overlays/$OVERLAY"
echo "   Setting image in kustomization.yaml..."
kustomize edit set image settlement-monitoring-api="$IMAGE"
echo "   Applying manifests..."
kubectl apply -k .

# Step 4: Wait for rollout
echo "⏳ Waiting for deployment to be ready (300s timeout)..."
kubectl -n "$NS" rollout status deployment/settlement-monitoring-api --timeout=300s

# Step 5: Show service info
echo ""
echo "✅ Deployment complete!"
echo ""
echo "📋 Service info:"
kubectl -n "$NS" get svc settlement-monitoring-api -o wide

# Try to get external IP
EXT_IP=$(kubectl -n "$NS" get svc settlement-monitoring-api -o jsonpath='{.status.loadBalancer.ingress[0].ip}{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "")
if [ -n "$EXT_IP" ]; then
    echo ""
    echo "🌐 API accessible at: http://$EXT_IP"
    echo "   Health:   http://$EXT_IP/api/v1/settlement/health"
    echo "   Analytics: http://$EXT_IP/api/v1/analytics/settlement"
    echo "   Swagger:  http://$EXT_IP/swagger-ui.html"
else
    echo ""
    echo "⚠️  External IP not assigned yet (might take a moment, or use port-forward)"
    echo "   Try: kubectl -n $NS get svc settlement-monitoring-api -w"
    echo "   Or:  kubectl -n $NS port-forward svc/settlement-monitoring-api 8080:80"
fi

echo ""
echo "📊 Pod status:"
kubectl -n "$NS" get pods -l app.kubernetes.io/name=settlement-monitoring-api

echo ""
echo "💡 Next steps:"
echo "   • View logs: kubectl -n $NS logs -f deployment/settlement-monitoring-api"
echo "   • Monitor health: ./scripts/health-check.sh $OVERLAY"
echo "   • Rollback: kubectl -n $NS rollout undo deployment/settlement-monitoring-api"

