// P1-10 (ADR 0088) — 1-vs-2(-vs-3) replica comparative throughput test.
//
// Bursts get_library_seat_catalog: a public MCP tool ("인증 불필요" in its own
// description — no LIBRARY login, no mcp_session_id). McpOAuthSecurityConfig
// permitAll()s /mcp/** and this tool never calls out past the in-memory
// LibrarySeatRoomCatalogService (static screenshot-built catalog, no upstream
// Pyxis call, no rate-limit/circuit-breaker path). That isolates the
// measurement to one question: "how many backend pods can answer this tool
// call," which is exactly what P1-10 (HA replicas + HPA) needs evidence for.
// It is safe to run repeatedly against prod for that reason.
//
// Contrast with library-seat-read-baseline.js / library-reservation-baseline.js:
// those drive the real Pyxis connector + LIBRARY auth and require the WireMock
// login bypass — the README for those scripts explicitly forbids prod. This
// script has no such restriction; it is meant to run against prod.
//
// Usage:
//   BASE_URL=https://ssumcp.duckdns.org BURST_RPS=20 \
//     k6 run load-tests/k6/replica-scale-comparison.js
//
// See load-tests/README.md §7 for the full before/after recording procedure
// (scale to 1, run, scale to 2, run, diff the two end-of-test summaries).
// This script only produces one run's numbers — it does not scale the
// deployment or diff runs itself.
import { check } from 'k6';
import { mcpInitialize, mcpToolCall } from './lib/mcp.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RPS = Number(__ENV.BURST_RPS || 20);
const RAMP = __ENV.RAMP_DURATION || '20s';
const STEADY = __ENV.STEADY_DURATION || '90s';

// Per-VU MCP transport session, created lazily on the VU's first iteration
// (same lazy-init pattern as library-seat-read-baseline.js).
let transport = null;

export const options = {
  scenarios: {
    catalog_burst: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: Math.max(20, RPS * 2),
      maxVUs: Math.max(100, RPS * 6),
      stages: [
        { target: RPS, duration: RAMP },
        { target: RPS, duration: STEADY },
        { target: 0, duration: RAMP },
      ],
    },
  },
  thresholds: {
    // Documentation targets, not hard gates: a 1-replica run under a burst
    // sized for 2-3 replicas is *expected* to run hotter than these — that
    // gap is the evidence this script exists to capture. abortOnFail stays
    // false so a failing threshold never cuts a run short before ramp-down
    // completes (an incomplete run makes the two sides of the comparison
    // uneven).
    http_req_failed: [{ threshold: 'rate<0.05', abortOnFail: false }],
    'http_req_duration{mcp:get_library_seat_catalog}': [
      { threshold: 'p(95)<800', abortOnFail: false },
    ],
  },
};

export default function () {
  if (!transport) {
    transport = mcpInitialize(BASE);
  }
  const call = mcpToolCall(BASE, transport, 'get_library_seat_catalog', {});
  check(call, {
    'tool answered': (c) => !!c.text,
    'has catalog data': (c) => !!c.text && c.text.indexOf('rooms') >= 0,
  });
}
