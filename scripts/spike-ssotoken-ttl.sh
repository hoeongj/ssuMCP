#!/usr/bin/env bash
# spike-ssotoken-ttl.sh
#
# POSIX/bash flavor of spike-ssotoken-ttl.ps1. Measures how long an
# oasis.ssu.ac.kr Pyxis-Auth-Token stays valid by polling /pyxis-api/1/seat-rooms
# until it returns the `needLogin` auth error.
#
# Pyxis API auth is via the `Pyxis-Auth-Token` request header, NOT a
# cookie. Capture flow: log into oasis, devtools -> Network -> any
# /pyxis-api/* XHR -> Request Headers -> copy Pyxis-Auth-Token value.
#
# Usage:
#   export OASIS_PYXIS_TOKEN="<paste captured Pyxis-Auth-Token value>"
#   ./scripts/spike-ssotoken-ttl.sh
#
# Legacy OASIS_SSOTOKEN env name also accepted for back-compat.
#
# Optional:
#   export OASIS_TTL_INTERVAL_SEC=300
#   export OASIS_TTL_LOG=scripts/ssotoken-ttl.log
#   export OASIS_TTL_URL="<override probe URL>"
#
# Output matches *.log so it is gitignored.

set -u

token="${OASIS_PYXIS_TOKEN:-${OASIS_SSOTOKEN:-}}"
if [ -z "$token" ]; then
    echo "OASIS_PYXIS_TOKEN env var is required. Capture from devtools: any /pyxis-api/* request -> Request Headers -> Pyxis-Auth-Token." >&2
    exit 1
fi

interval="${OASIS_TTL_INTERVAL_SEC:-300}"
log="${OASIS_TTL_LOG:-scripts/ssotoken-ttl.log}"
url="${OASIS_TTL_URL:-https://oasis.ssu.ac.kr/pyxis-api/1/seat-rooms?smufMethodCode=PC&branchGroupId=1}"
referer="https://oasis.ssu.ac.kr/library-services/smuf/reading-rooms"
user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"

fingerprint=$(printf '%s' "$token" | sha256sum | cut -c1-8)
started=$(date -Iseconds)
started_epoch=$(date +%s)

echo "started=$started fingerprint=$fingerprint interval=${interval}s url=$url" | tee -a "$log"

poll=0
while :; do
    poll=$((poll + 1))
    now=$(date -Iseconds)
    body=$(curl -fsS \
        -H "Pyxis-Auth-Token: $token" \
        -H "Accept: application/json, text/plain, */*" \
        -H "Accept-Language: ko" \
        -H "Referer: $referer" \
        -H "User-Agent: $user_agent" \
        "$url" 2>/dev/null || echo "__curl_error__")

    if [ "$body" = "__curl_error__" ]; then
        echo "$now poll=$poll status=error" | tee -a "$log"
    elif printf '%s' "$body" | grep -q 'needLogin'; then
        now_epoch=$(date +%s)
        elapsed=$((now_epoch - started_epoch))
        hours=$(awk "BEGIN { printf \"%.2f\", $elapsed/3600 }")
        echo "$now poll=$poll status=expired elapsed=${elapsed}s" | tee -a "$log"
        echo ""
        echo "===== TTL measurement complete ====="
        echo "Token died after $elapsed seconds (${hours} hours)."
        echo "Log: $log"
        break
    else
        bodylen=${#body}
        echo "$now poll=$poll status=ok bodylen=$bodylen" | tee -a "$log"
    fi

    sleep "$interval"
done
