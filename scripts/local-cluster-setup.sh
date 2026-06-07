#!/bin/bash
#
# local-cluster-setup.sh — Quick start for local Kubernetes testing
#
# Supports: minikube (with tunnel), kind
#
# Usage:
#   ./scripts/local-cluster-setup.sh minikube      # or 'kind'
#

CLUSTER_TYPE="${1:-minikube}"

echo "🚀 Setting up local $CLUSTER_TYPE cluster for Settlement Monitoring API"
echo ""

if [ "$CLUSTER_TYPE" = "minikube" ]; then
    echo "📦 Minikube setup (macOS with Hyperkit)..."

    if ! command -v minikube &> /dev/null; then
        echo "Installing minikube..."
        brew install minikube hyperkit
    fi

    echo "Starting minikube..."
    minikube start --driver=hyperkit --cpus=4 --memory=4096

    echo ""
    echo "✅ Minikube cluster running!"
    echo "⚠️  IMPORTANT: Open another terminal and run:"
    echo "   minikube tunnel"
    echo "   (Keep it running; it bridges LoadBalancer to localhost)"
    echo ""
    echo "Then in THIS terminal, deploy:"
    echo "   ./scripts/local-deploy.sh"

elif [ "$CLUSTER_TYPE" = "kind" ]; then
    echo "📦 Kind (Kubernetes IN Docker) setup..."

    if ! command -v kind &> /dev/null; then
        echo "Installing kind..."
        brew install kind
    fi

    KIND_CONFIG=$(mktemp)
    cat > "$KIND_CONFIG" << 'EOF'
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraPortMappings:
      - containerPort: 80
        hostPort: 8080
        protocol: TCP
      - containerPort: 443
        hostPort: 8443
        protocol: TCP
EOF

    echo "Creating kind cluster..."
    kind create cluster --config="$KIND_CONFIG" --name settlement
    rm "$KIND_CONFIG"

    echo ""
    echo "✅ Kind cluster running!"
    echo "ℹ️  Port mapping: localhost:8080 → ingress HTTP / localhost:8443 → ingress HTTPS"
    echo ""
    echo "Deploy with:"
    echo "   ./scripts/local-deploy.sh kind"

else
    echo "❌ Unknown cluster type: $CLUSTER_TYPE"
    echo "Usage: $0 [minikube|kind]"
    exit 1
fi

echo ""
echo "💡 Check cluster status:"
echo "   kubectl get nodes"
echo "   kubectl get pods --all-namespaces"

