# ADR 0090 — Tool-call event pipeline

| 항목 | 내용 |
|---|---|
| 날짜 | 2026-07-10 |
| 상태 | Accepted |
| 범위 | MCP tool-call 이벤트 발행, Kafka consumer, Loki structured audit log |
| 연관 문서 | ADR 0089(Kafka broker), ADR 0078(capacity/budget), ADR 0071(Kafka graduation), ADR 0022(event payload privacy discipline) |

---

## Context

ssuMCP is moving from a single-service campus demo toward a large-scale campus MCP surface. Exam-period peaks can turn ordinary tool calls into a high-volume operational signal: which tools are hot, whether tools return MCP-level errors, and how long tool handlers spend on the servlet request thread.

The tool path is synchronous. `SyncToolSpecification.callHandler()` runs on the Tomcat request thread, so event capture must never wait for broker metadata, network I/O, topic creation, or consumer availability. The pipeline must fail open: tool calls are the product path, tool-call events are observability/audit signals.

## Decision

Publish one whitelisted event per MCP tool call to keyed Kafka topic `mcp.toolcall.events.v1`, then consume it in the backend and write structured logs to `mcp.toolcall.audit`. Promtail sends the JSON-profile stdout stream to Loki, where Grafana can query the structured fields.

Event payload v1 contains only:

- `toolName`
- `requestId`
- `durationMs`
- `outcome` (`ok`, `tool_error`, `exception`)
- `timestampEpochMs`
- `schemaVersion`

No token, session id, principal, raw tool args, credentials, user text, or PII is allowed in the event.

## Why Kafka, Not Redis

Redis pub/sub is still the right fit for short-lived fan-out. This pipeline needs persistence and offset-based replay: a consumer can be stopped, restarted, or replaced without losing the event log contract. That is the graduation trigger ADR 0071 left open and ADR 0089 now provides.

## Partition Key

The producer keys records by `toolName`. This gives per-tool ordering and makes partition behavior easy to explain operationally. The trade-off is hot-tool skew: a very popular tool can concentrate traffic on one partition. At current campus scale this is accepted; if skew becomes visible, the future key is a composite such as `toolName + requestIdHashBucket`.

## Reliability

Producer settings use `acks=all` and idempotence with short timeouts (`delivery.timeout.ms=5000`, `request.timeout.ms=4000`, `max.block.ms=500`). These settings improve broker-side delivery when Kafka is healthy, but they do not move broker waiting into the MCP request path.

Non-blocking design:

- The call handler only builds a tiny event and submits to a bounded executor.
- Kafka serialization and `KafkaTemplate.send()` run on `toolcall-kafka-*` threads.
- The executor is bounded (`queueCapacity=1000`, core/max `1/2`), so overload sheds events instead of growing memory or blocking Tomcat.
- Queue-full, send, serialization, and broker failures are counted with `mcp.toolcall.event{result=...}`.

Fail-open behavior:

- Kafka disabled: the seam passes `original.callHandler()` unchanged.
- Kafka enabled but broker down: the app starts because `KafkaAdmin` is non-fatal, tool calls still return exactly as before, and events are dropped/counted.
- Consumer poison message: parse warning, no raw payload log, continue.

## Loki Sink

OpenSearch is not introduced for this unit. ADR 0078 treats OpenSearch as a budget-heavy future component and already prefers existing Loki when possible. The consumer writes Logstash structured arguments (`toolName`, `requestId`, `durationMs`, `outcome`, `timestampEpochMs`) so the existing `json-logs` profile makes those fields queryable in Loki.

## SLO And Metrics

Consumer lag is exposed by Spring Kafka Micrometer consumer listener into the existing Prometheus scrape path. Producer outcomes are counted:

- `sent`
- `dropped_queue_full`
- `dropped_error`

The operational SLO is not "never drop audit events"; it is "never delay or fail tool calls because of the audit event pipeline." Lag and drop counters tell operators when observability is degraded.

## Trade-offs

Kafka adds broker and topic operations, but ADR 0089 already pays that cost for Phase 2. A bounded async producer can drop events under pressure; that is intentional because a durable audit signal must not become a campus MCP outage source. Keying only by tool name preserves per-tool order but can skew partitions; future composite keys can spread hot tools if metrics show a need.
