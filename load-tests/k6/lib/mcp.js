// Minimal MCP Streamable HTTP client for k6.
//
// Talks JSON-RPC 2.0 to the Spring AI MCP server at {base}/mcp. The server
// may answer either plain JSON or an SSE-framed body (text/event-stream), so
// parseRpc handles both. No external deps — k6 scripts can't use npm modules.
import http from 'k6/http';

const ACCEPT_BOTH = 'application/json, text/event-stream';

function headers(sessionId) {
  const h = { 'Content-Type': 'application/json', Accept: ACCEPT_BOTH };
  if (sessionId) {
    h['mcp-session-id'] = sessionId;
  }
  return h;
}

// Extracts the JSON-RPC response object from a raw body that is either plain
// JSON or SSE frames ("event: message\ndata: {...}").
export function parseRpc(body) {
  if (!body) {
    return null;
  }
  const text = body.trim();
  if (text.indexOf('data:') >= 0) {
    const lines = text.split('\n');
    for (let i = lines.length - 1; i >= 0; i--) {
      const line = lines[i].trim();
      if (line.indexOf('data:') !== 0) {
        continue;
      }
      try {
        const obj = JSON.parse(line.slice(5).trim());
        if (obj && (obj.result !== undefined || obj.error !== undefined)) {
          return obj;
        }
      } catch (e) {
        // partial frame — keep scanning earlier lines
      }
    }
    return null;
  }
  try {
    return JSON.parse(text);
  } catch (e) {
    return null;
  }
}

// initialize + notifications/initialized handshake. Returns the transport
// session id (mcp-session-id header) used by all subsequent calls.
export function mcpInitialize(base) {
  const res = http.post(
    `${base}/mcp`,
    JSON.stringify({
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: {
        protocolVersion: '2025-03-26',
        capabilities: {},
        clientInfo: { name: 'k6-loadtest', version: '1.0.0' },
      },
    }),
    { headers: headers(null), tags: { mcp: 'initialize' } }
  );
  const sid = res.headers['Mcp-Session-Id'] || res.headers['mcp-session-id'];
  if (!sid) {
    throw new Error(`MCP initialize: no session id (status=${res.status} body=${res.body})`);
  }
  http.post(
    `${base}/mcp`,
    JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
    { headers: headers(sid), tags: { mcp: 'initialized' } }
  );
  return sid;
}

// tools/call. Returns {res, rpc, text} where text is the first content item
// (ssuMCP tools serialize their payload as a JSON string there).
export function mcpToolCall(base, sessionId, name, args, tags) {
  const res = http.post(
    `${base}/mcp`,
    JSON.stringify({
      jsonrpc: '2.0',
      id: 2,
      method: 'tools/call',
      params: { name: name, arguments: args || {} },
    }),
    { headers: headers(sessionId), tags: Object.assign({ mcp: name }, tags || {}) }
  );
  const rpc = parseRpc(res.body);
  let text = null;
  if (
    rpc &&
    rpc.result &&
    rpc.result.content &&
    rpc.result.content.length > 0 &&
    rpc.result.content[0].text !== undefined
  ) {
    text = rpc.result.content[0].text;
  }
  return { res: res, rpc: rpc, text: text };
}

// Parses the tool text payload as JSON; returns null when it isn't JSON.
export function toolJson(call) {
  if (!call || !call.text) {
    return null;
  }
  try {
    return JSON.parse(call.text);
  } catch (e) {
    return null;
  }
}
