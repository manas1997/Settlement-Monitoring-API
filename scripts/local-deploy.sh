#!/bin/bash
#
# local-deploy.sh — Deploy to local minikube/kind cluster (no registry)
#
# Usage:
#   ./scripts/local-deploy.sh minikube    # builds locally and injects into minikube
#   ./scripts/local-deploy.sh kind        # builds and loads into kind
#

CLUSTER_TYPE="${1:-minikube}"
OVERLAY="${2:-dev}"
NS="settlement-$OVERLAY"

IMAGE="settlement-monitoring-api:local"

echo "🚀 Local deployment to $CLUSTER_TYPE"
echo "   Cluster type: $CLUSTER_TYPE"
echo "   Overlay: $OVERLAY"
echo "   Namespace: $NS"
echo "   Image: $IMAGE (local, no registry)"
echo ""

# Step 1: Build image
echo "📦 Building Docker image: $IMAGE"
docker build -t "$IMAGE" .

# Step 2: Load into cluster
if [ "$CLUSTER_TYPE" = "minikube" ]; then
    echo "📥 Loading image into minikube..."
    minikube image load "$IMAGE"
elif [ "$CLUSTER_TYPE" = "kind" ]; then
    echo "📥 Loading image into kind..."
    kind load docker-image "$IMAGE" --name settlement
else
    echo "❌ Unknown cluster type: $CLUSTER_TYPE"
    exit 1
fi

# Step 3: Deploy
echo "🔧 Applying Kustomize manifests..."
cd deploy/k8s/overlays/$OVERLAY
kustomize edit set image settlement-monitoring-api="$IMAGE"
kubectl apply -k .

# Step 4: Wait for rollout
echo "⏳ Waiting for deployment to be ready..."
kubectl -n "$NS" rollout status deployment/settlement-monitoring-api --timeout=300s

# Step 5: Show how to access
echo ""
echo "✅ Deployment complete!"
echo ""

if [ "$CLUSTER_TYPE" = "minikube" ]; then
    EXT=$(kubectl -n "$NS" get svc settlement-monitoring-api -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null)
    if [ -n "$EXT" ]; then
        echo "🌐 API accessible at: http://$EXT"
        echo "   (requires 'minikube tunnel' running in another terminal)"
    else
        echo "⚠️  LoadBalancer IP not assigned yet (minikube tunnel must be running)"
    fi
elif [ "$CLUSTER_TYPE" = "kind" ]; then
    echo "🌐 API accessible at: http://localhost:8080"
    echo "   (kind port-mapping: 8080 → ingress HTTP)"
fi

echo ""
echo "📋 Service info:"
kubectl -n "$NS" get svc settlement-monitoring-api -o wide

echo ""
echo "💡 Quick checks:"
echo "   Health:   curl http://localhost:8080/api/v1/settlement/health  (or $EXT)"
echo "   Analytics: curl http://localhost:8080/api/v1/analytics/settlement"
echo "   Swagger:  http://localhost:8080/swagger-ui.html"
echo ""
echo "   Monitor: ./scripts/health-check.sh $OVERLAY"
echo "   Logs:    kubectl -n $NS logs -f deployment/settlement-monitoring-api"

