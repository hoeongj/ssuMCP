#!/usr/bin/env bash
# Circuit-breaker-under-load experiment: drive authenticated seat reads, inject a
# WireMock 500 fault mid-load, and watch the `pyxis` Resilience4j circuit trip OPEN
# (short-circuiting the upstream) then auto-recover to CLOSED. See
# docs/performance/library-agent-load-test.md §4-1 ②.
#
# Prereq: docker compose up -d postgres wiremock (+ a redis on :6379), then start
# the backend with SSUAI_LIBRARY_SEAT_CACHE_TTL=0s so every read hits the connector
# (otherwise the 30s seat cache absorbs most reads and the breaker trips slowly).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PROM=${PROM:-http://localhost:8080/actuator/prometheus}
WM=${WM:-http://localhost:8089/__admin}
cbstate(){ curl -s --max-time 4 "$PROM" | grep 'circuitbreaker_state{name="pyxis"' | grep ' 1.0' | grep -oE 'state="[a-z_]+"' | cut -d'"' -f2; }
wmcnt(){ curl -s --max-time 4 -X POST "$WM/requests/count" -d '{"method":"GET","urlPathPattern":"/pyxis-api/1/(seat-rooms|api/rooms/[0-9]+/seats)"}' | python3 -c "import sys,json;print(json.load(sys.stdin).get('count'))"; }
poll(){ echo "CB=$(cbstate) wiremock_seat_calls=$(wmcnt)"; }

docker compose run --rm -e READ_RPS=25 k6 run /scripts/library-seat-read-baseline.js >/tmp/cb-k6.log 2>&1 &
K6=$!; echo "k6 driving load; warming 25s..."; sleep 25
curl -s -X POST "$WM/requests/reset" >/dev/null
echo "== normal =="; for _ in 1 2 3; do poll; sleep 3; done
echo "== inject 500 =="
curl -s -X POST "$WM/mappings" -d @"$HERE/wiremock/fault/seat-rooms-500.json" >/dev/null
curl -s -X POST "$WM/mappings" -d @"$HERE/wiremock/fault/room-seats-500.json" >/dev/null
echo "== observe OPEN (45s) =="; for _ in $(seq 1 15); do poll; sleep 3; done
echo "== restore (reset dynamic mappings) =="
curl -s -X POST "$WM/mappings/reset" >/dev/null; curl -s -X POST "$WM/requests/reset" >/dev/null
echo "== observe recovery -> CLOSED (50s) =="; for _ in $(seq 1 17); do poll; sleep 3; done
kill "$K6" 2>/dev/null || true; docker compose rm -sf k6 >/dev/null 2>&1 || true
