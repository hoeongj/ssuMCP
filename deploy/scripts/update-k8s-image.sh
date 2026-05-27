#!/usr/bin/env bash
# update-k8s-image.sh - updates the k8s deployment to the latest CI-built image.
#
# Usage (run from repo root on a machine with kubectl pointing at the cluster):
#   bash deploy/scripts/update-k8s-image.sh
#
# Resolves the latest commit SHA from git and constructs the GHCR image tag
# that GitHub Actions CI built for it, then runs kubectl set image + rollout.

set -euo pipefail

NAMESPACE="ssuai-prod"
DEPLOYMENT="ssuai-backend"
CONTAINER="backend"
IMAGE_REPO="ghcr.io/hoeongj/ssumcp"

if ! command -v kubectl &>/dev/null; then
  echo "ERROR: kubectl not found in PATH." >&2
  exit 1
fi

# Allow overriding the SHA (e.g. UPDATE_SHA=abc123 bash update-k8s-image.sh)
SHA="${UPDATE_SHA:-$(git rev-parse HEAD)}"
IMAGE="${IMAGE_REPO}:sha-${SHA}"

echo "Updating $DEPLOYMENT/$CONTAINER to:"
echo "  $IMAGE"
echo ""

kubectl set image "deployment/$DEPLOYMENT" \
  "${CONTAINER}=${IMAGE}" \
  -n "$NAMESPACE"

echo "Waiting for rollout (up to 6 min; ARM64 image pulls are slow)..."
kubectl rollout status "deployment/$DEPLOYMENT" \
  -n "$NAMESPACE" \
  --timeout=360s

echo ""
echo "Active image:"
kubectl get pod -n "$NAMESPACE" \
  -o jsonpath='{.items[0].spec.containers[0].image}'
echo ""
echo "Done."
