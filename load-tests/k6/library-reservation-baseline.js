// EPIC 1 write baseline — prepare_reserve_library_seat → confirm_action
// through the real MCP Streamable HTTP endpoint.
//
// Two modes (MODE env):
//   same-seat      (default) SESSIONS users all confirm seat 9999 at once.
//                  WireMock's contested-seat scenario lets the first reserve
//                  through and answers warning.seat.alreadyCharged afterwards,
//                  so exactly one confirm should end SUCCESS and the rest
//                  FAILURE_RACE.
//   distinct-seats every user confirms their own seat (20000+VU); all should
//                  succeed.
//
// setup() provisions SESSIONS MCP auth sessions without a browser: the
// loginUrl state from start_auth is POSTed straight to the library callback,
// and WireMock's Pyxis login stub accepts any credentials. This only works
// because the whole stack is synthetic — never run this against prod.
//
// The authoritative result lives in Postgres action_audit (see README for the
// verification SQL); the k6 counters classify the user-facing messages.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { mcpInitialize, mcpToolCall, toolJson } from './lib/mcp.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const WIREMOCK = __ENV.WIREMOCK_URL || 'http://localhost:8089';
const SESSIONS = Number(__ENV.SESSIONS || 100);
const MODE = __ENV.MODE || 'same-seat';
const CONTESTED_SEAT = '9999';

const reserveSuccess = new Counter('reserve_success');
const reserveRace = new Counter('reserve_race');
const reserveTimeout = new Counter('reserve_timeout');
const reserveOther = new Counter('reserve_other');

export const options = {
  scenarios: {
    confirm_burst: {
      executor: 'per-vu-iterations',
      vus: SESSIONS,
      iterations: 1,
      maxDuration: '5m',
    },
  },
  thresholds:
    MODE === 'distinct-seats'
      ? {
          checks: ['rate>0.99'],
          // Every distinct-seat confirm must succeed.
          reserve_success: [`count==${SESSIONS}`],
          'http_req_duration{endpoint:confirm}': ['p(95)<2000'],
        }
      : {
          checks: ['rate>0.99'],
          // Upstream allows exactly one charge; at least one must win and
          // race losers must be the overwhelming majority.
          reserve_success: ['count>=1'],
          reserve_race: [`count>=${SESSIONS - 5}`],
          'http_req_duration{endpoint:confirm}': ['p(95)<2000'],
        },
};

export function setup() {
  // Reset the contested-seat scenario so seat 9999 starts free.
  const reset = http.post(`${WIREMOCK}/__admin/scenarios/reset`);
  if (reset.status !== 200) {
    throw new Error(`WireMock scenario reset failed: ${reset.status}`);
  }

  const sessions = [];
  for (let i = 0; i < SESSIONS; i++) {
    const transport = mcpInitialize(BASE);
    const start = mcpToolCall(BASE, transport, 'start_auth', { provider: 'LIBRARY' });
    const startJson = toolJson(start);
    if (!startJson || !startJson.loginUrl || !startJson.mcpSessionId) {
      throw new Error(`start_auth #${i} unexpected response: ${start.text}`);
    }
    const match = /[?&]state=([^&]+)/.exec(startJson.loginUrl);
    if (!match) {
      throw new Error(`start_auth #${i}: no state in loginUrl ${startJson.loginUrl}`);
    }
    const cb = http.post(
      `${BASE}/api/mcp/auth/library/callback`,
      JSON.stringify({ state: match[1], loginId: `loadtest${i}`, password: 'loadtest' }),
      { headers: { 'Content-Type': 'application/json' } }
    );
    if (cb.status !== 200) {
      throw new Error(`library callback #${i} failed: ${cb.status} ${cb.body}`);
    }
    sessions.push({ transport: transport, auth: startJson.mcpSessionId });
  }
  return { sessions: sessions };
}

export default function (data) {
  const s = data.sessions[__VU - 1];
  const seat = MODE === 'distinct-seats' ? String(20000 + __VU) : CONTESTED_SEAT;

  const prep = mcpToolCall(
    BASE,
    s.transport,
    'prepare_reserve_library_seat',
    { mcp_session_id: s.auth, seat_id: seat },
    { endpoint: 'prepare' }
  );
  check(prep, {
    'prepare ok': (c) => !!c.text && c.text.indexOf('준비했습니다') >= 0,
  });

  const conf = mcpToolCall(
    BASE,
    s.transport,
    'confirm_action',
    { mcp_session_id: s.auth },
    { endpoint: 'confirm' }
  );
  const ok = check(conf, {
    'confirm answered': (c) => !!c.text,
  });
  if (!ok) {
    reserveOther.add(1);
    return;
  }
  if (conf.text.indexOf('예약 완료') >= 0) {
    reserveSuccess.add(1);
  } else if (conf.text.indexOf('이미 선점') >= 0) {
    reserveRace.add(1);
  } else if (conf.text.indexOf('응답이 없') >= 0) {
    reserveTimeout.add(1);
  } else {
    reserveOther.add(1);
  }
}
