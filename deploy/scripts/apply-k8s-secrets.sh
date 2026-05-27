#!/usr/bin/env bash
# apply-k8s-secrets.sh - reads .env and applies a k8s Secret.
#
# Usage (run from repo root on a machine with kubectl pointing at the cluster):
#   bash deploy/scripts/apply-k8s-secrets.sh
#
# The script reads .env, extracts the keys listed in REQUIRED_KEYS
# and OPTIONAL_KEYS, and applies them as a k8s Secret in ssuai-prod.
# Nothing is committed to git.

set -euo pipefail

ENV_FILE="${ENV_FILE:-.env}"
NAMESPACE="ssuai-prod"
SECRET_NAME="ssuai-backend-secrets"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: $ENV_FILE not found. Run from repo root." >&2
  exit 1
fi

if ! command -v kubectl &>/dev/null; then
  echo "ERROR: kubectl not found in PATH." >&2
  exit 1
fi

# Load .env (skip comments and blank lines)
declare -A ENV_VALS
while IFS='=' read -r key value; do
  [[ "$key" =~ ^[[:space:]]*# ]] && continue
  [[ -z "$key" ]] && continue
  ENV_VALS["$key"]="$value"
done < <(grep -v '^[[:space:]]*#' "$ENV_FILE" | grep -v '^[[:space:]]*$')

# Keys that must be present
REQUIRED_KEYS=(
  SSUAI_JWT_SECRET
  SSUAI_CREDENTIAL_ENCRYPTION_KEY
  SSUAI_GEMINI_API_KEY
)

# Keys that are optional (at least one LLM key is required for chat to work)
OPTIONAL_KEYS=(
  SSUAI_API_BASE_URL
  SSUAI_GROQ_API_KEY
  SSUAI_CEREBRAS_API_KEY
  SSUAI_DEEPINFRA_API_KEY
  SSUAI_SAMBANOVA_API_KEY
  SSUAI_NSCALE_API_KEY
  SSUAI_FIREWORKS_API_KEY
  SSUAI_HUGGINGFACE_API_KEY
  SSUAI_MISTRAL_API_KEY
  SSUAI_MISTRAL_TRAINING_OPT_OUT_CONFIRMED
  SSUAI_OPENROUTER_API_KEY
  SSUAI_OPENROUTER_HTTP_REFERER
  SSUAI_OPENROUTER_APP_TITLE
)

# Validate required keys are present and non-empty
for key in "${REQUIRED_KEYS[@]}"; do
  val="${ENV_VALS[$key]:-}"
  if [[ -z "$val" ]]; then
    echo "ERROR: $key is missing or empty in $ENV_FILE" >&2
    exit 1
  fi
done

# Build --from-literal args
LITERAL_ARGS=()
for key in "${REQUIRED_KEYS[@]}" "${OPTIONAL_KEYS[@]}"; do
  val="${ENV_VALS[$key]:-}"
  if [[ -n "$val" ]]; then
    LITERAL_ARGS+=("--from-literal=${key}=${val}")
  fi
done

echo "Applying Secret '$SECRET_NAME' in namespace '$NAMESPACE'..."
echo "(${#LITERAL_ARGS[@]} keys)"
echo ""

kubectl create secret generic "$SECRET_NAME" \
  --namespace "$NAMESPACE" \
  "${LITERAL_ARGS[@]}" \
  --save-config \
  --dry-run=client -o yaml | kubectl apply -f -

echo ""
echo "Done. Restart pod to pick up new secret values:"
echo "  kubectl rollout restart deployment/ssuai-backend -n $NAMESPACE"
