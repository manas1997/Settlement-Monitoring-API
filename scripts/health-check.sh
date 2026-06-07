#!/bin/bash
#
# health-check.sh — Monitor API health in a loop (useful for CI/debugging)
#
# Usage:
#   ./scripts/health-check.sh dev              # polls the dev deployment
#   ./scripts/health-check.sh review           # polls the review deployment
#

OVERLAY="${1:-dev}"
NS="settlement-$OVERLAY"

echo "🔍 Monitoring health for: $NS"
echo "Press Ctrl+C to stop"
echo ""

# Try to get external IP first
EXT=$(kubectl -n "$NS" get svc settlement-monitoring-api -o jsonpath='{.status.loadBalancer.ingress[0].ip}{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null)

if [ -z "$EXT" ]; then
    echo "⚠️  No external LoadBalancer IP. Using port-forward..."
    echo "Starting port-forward in background..."
    kubectl -n "$NS" port-forward svc/settlement-monitoring-api 8080:80 >/dev/null 2>&1 &
    PF_PID=$!
    EXT="localhost:8080"
    sleep 2  # wait for port-forward to start
    trap "kill $PF_PID 2>/dev/null" EXIT
else
    echo "✅ Using external IP: $EXT"
fi

echo ""

COUNT=0
HEALTHY=0
FAILED=0

while true; do
    COUNT=$((COUNT + 1))

    # Try to fetch health status with 5s timeout
    RESPONSE=$(curl -s --max-time 5 -o /dev/null -w "%{http_code}" "http://$EXT/api/v1/settlement/health" 2>/dev/null || echo "000")

    TIMESTAMP=$(date '+%Y-%m-%d %H:%M:%S')

    if [ "$RESPONSE" = "200" ]; then
        echo "[$TIMESTAMP] ✅ HTTP $RESPONSE (healthy) [$HEALTHY/$COUNT]"
        HEALTHY=$((HEALTHY + 1))
    else
        echo "[$TIMESTAMP] ❌ HTTP $RESPONSE (failed) [$FAILED/$COUNT]"
        FAILED=$((FAILED + 1))
    fi

    # Optional: fetch and display analytics once healthy
    if [ "$HEALTHY" -ge 2 ]; then
        ANALYTICS=$(curl -s --max-time 5 "http://$EXT/api/v1/analytics/settlement" 2>/dev/null | jq -r '.settlementStats | "\(.settledCount) settled, \(.delayedCount) delayed, \(.atRiskCount) at-risk"' 2>/dev/null || echo "(error fetching analytics)")
        echo "         📊 Settlement stats: $ANALYTICS"
    fi

    sleep 5
done

