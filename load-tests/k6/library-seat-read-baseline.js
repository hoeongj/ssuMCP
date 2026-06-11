// EPIC 1 read baseline — get_library_seat_status via the MCP endpoint.
//
// The REST mirror (/api/library/seats) sits behind JWT auth, and the real
// client path (Claude Desktop, ssuAgent) speaks MCP anyway, so the baseline
// drives tools/call directly. One LIBRARY auth session (created browser-free
// in setup, see library-reservation-baseline.js) is shared by every VU; each
// VU keeps its own MCP transport session.
//
// Profile per MASTERPLAN EPIC 1: 1m ramp-up → 3m steady → 1m ramp-down at a
// fixed arrival rate (READ_RPS, default 50) so throughput holds even when
// latency degrades.
//
// Interpretation notes (see docs/performance/library-agent-load-test.md):
// - LibrarySeatService caches each floor for 30s → most calls are cache hits;
//   roughly one call per floor per 30s pays the full upstream cost.
// - RealLibrarySeatConnector adds a deliberate 300–1200ms random delay before
//   each upstream call (good-citizen jitter), which dominates tail latency on
//   cache misses.
import http from 'k6/http';
import { check } from 'k6';
import { mcpInitialize, mcpToolCall, toolJson } from './lib/mcp.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RPS = Number(__ENV.READ_RPS || 50);
const FLOORS = [2, 5, 6];

// Per-VU MCP transport session, created lazily on the VU's first iteration.
let transport = null;

export const options = {
  scenarios: {
    seat_read: {
      executor: 'ramping-arrival-rate',
      startRate: 0,
      timeUnit: '1s',
      preAllocatedVUs: 60,
      maxVUs: 300,
      stages: [
        { target: RPS, duration: '1m' },
        { target: RPS, duration: '3m' },
        { target: 0, duration: '1m' },
      ],
    },
  },
  thresholds: {
    // SLO: 95% of seat reads under 500ms, error rate under 1%.
    http_req_failed: ['rate<0.01'],
    'http_req_duration{mcp:get_library_seat_status}': ['p(95)<500'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  const transportSession = mcpInitialize(BASE);
  const start = mcpToolCall(BASE, transportSession, 'start_auth', { provider: 'LIBRARY' });
  const startJson = toolJson(start);
  if (!startJson || !startJson.loginUrl || !startJson.mcpSessionId) {
    throw new Error(`start_auth unexpected response: ${start.text}`);
  }
  const match = /[?&]state=([^&]+)/.exec(startJson.loginUrl);
  const cb = http.post(
    `${BASE}/api/mcp/auth/library/callback`,
    JSON.stringify({ state: match[1], loginId: 'loadtest-read', password: 'loadtest' }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (cb.status !== 200) {
    throw new Error(`library callback failed: ${cb.status} ${cb.body}`);
  }
  return { auth: startJson.mcpSessionId };
}

export default function (data) {
  if (!transport) {
    transport = mcpInitialize(BASE);
  }
  const floor = FLOORS[Math.floor(Math.random() * FLOORS.length)];
  const call = mcpToolCall(BASE, transport, 'get_library_seat_status', {
    floor: floor,
    mcp_session_id: data.auth,
  });
  check(call, {
    'tool answered': (c) => !!c.text,
    'has seat data': (c) => !!c.text && c.text.indexOf('availableSeats') >= 0,
  });
}
