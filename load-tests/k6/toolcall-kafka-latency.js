// Compare MCP request latency with the tool-call event pipeline disabled,
// connected to a healthy broker, and connected to an unavailable broker.
// The surrounding runner controls the backend/broker state; this script keeps
// the request shape and load constant across all three measurements.
import { check } from 'k6';
import { mcpInitialize, mcpToolCall } from './lib/mcp.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const RPS = Number(__ENV.TOOLCALL_RPS || 10);
const DURATION = __ENV.TOOLCALL_DURATION || '30s';

let transport = null;

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    toolcall: {
      executor: 'constant-arrival-rate',
      rate: RPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(10, RPS * 2),
      maxVUs: Math.max(30, RPS * 5),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    // Declaring the tagged submetric keeps handshakes out of the comparison
    // and makes it available in both the console and summary-export JSON.
    'http_req_duration{mcp:get_today_meal}': ['p(95)<1000'],
    checks: ['rate>0.99'],
  },
};

export default function () {
  if (!transport) {
    transport = mcpInitialize(BASE);
  }
  const call = mcpToolCall(BASE, transport, 'get_today_meal', {});
  check(call, {
    'tool call returned JSON-RPC result': (c) => !!c.rpc && c.rpc.result !== undefined,
    'tool call returned content': (c) => !!c.text,
  });
}
