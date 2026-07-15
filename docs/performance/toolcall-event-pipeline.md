# Tool-call event pipeline measurement plan

> Date: 2026-07-10. Measurement completed: 2026-07-15.
> Scope: Phase 2 Unit 1 Kafka tool-call event pipeline.

## Goal

Validate that request-path latency is unaffected when Kafka is slow or down, because the servlet thread only submits to a bounded executor. Broker work, metadata waits, serialization, and `KafkaTemplate.send()` happen off the MCP request thread.

## Measurements

1. **Kafka disabled baseline**
   - Run normal MCP tool-call load with `ssuai.kafka.enabled=false`.
   - Confirm the seam is a no-op: original handler result/exception identity is preserved.

2. **Kafka enabled, broker healthy**
   - Run representative read-only tools at expected peak RPS.
   - Track request p50/p95/p99, `mcp.toolcall.event{result="sent"}`, and Kafka consumer lag.
   - Expected: request latency close to disabled baseline; lag near zero under steady state.

3. **Kafka enabled, broker slow/down**
   - Point `SPRING_KAFKA_BOOTSTRAP_SERVERS` to a blackholed address or stop the broker.
   - Run the same tool-call load.
   - Expected: tool calls succeed; request latency does not include `max.block.ms`; `dropped_error` and possibly `dropped_queue_full` rise.

4. **Producer overload**
   - Lower `ssuai.kafka.queue-capacity` in a staging profile and block producer sends.
   - Expected: bounded queue rejects excess events, increments `dropped_queue_full`, and does not block request threads.

## Local validation notes

Focused local run:

```text
./gradlew test --tests 'com.ssuai.global.kafka.ToolCallEventProducerTest' \
  --tests 'com.ssuai.domain.mcp.config.McpToolCallSeamTest' \
  --tests 'com.ssuai.global.kafka.ToolCallEventPipelineIT'

Result: BUILD SUCCESSFUL, 7 tests.
```

Full local run:

```text
./gradlew test
Result: BUILD SUCCESSFUL, tests=1145 failures=0 errors=0 skipped=15.
```

The unit tests exercise the failure-mode mechanics directly:

- KafkaTemplate send exception: `tryEmit(...)` returns without throwing and increments `dropped_error`.
- Bounded executor saturation: with capacity `1` and two blocked producer workers, the next event is rejected and increments `dropped_queue_full`.
- Embedded Kafka round-trip: the real producer publishes a whitelisted JSON event and the consumer logs the structured fields to `mcp.toolcall.audit`.

## k6 three-condition result

The follow-up measurement used the reproducible stack and script in
[`load-tests/README.md`](../../load-tests/README.md). The host was an Apple Silicon
development machine running Colima. Each condition used the public
`get_today_meal` tool at 10 iterations/s for 20 seconds (201 completed iterations,
100% checks passed). The table reports only the tagged tool request; MCP initialize
and initialized requests are excluded.

| Condition | avg | median | p95 | p99 | max |
| --- | ---: | ---: | ---: | ---: | ---: |
| Pipeline disabled | 6.24 ms | 5.06 ms | 9.22 ms | 23.82 ms | 84.08 ms |
| Broker healthy | 6.84 ms | 5.67 ms | 10.84 ms | 35.66 ms | 72.12 ms |
| Broker stopped | 5.85 ms | 5.14 ms | 9.90 ms | 15.77 ms | 27.33 ms |

With the healthy broker, the topic had six in-sync partitions and
`mcp_toolcall_event_total{result="sent"}` reached 201. After the broker was stopped
and a six-second detection window elapsed, all 201 tool calls still succeeded and
`dropped_error` began increasing (40 observed when the run ended). The request p95
remained below both the healthy-broker result and the 1,000 ms safety threshold,
which supports the non-blocking request-path claim.

This is a short local regression measurement, not a production capacity result.
Absolute latency and the p99 ordering should not be generalized; repeat the same
script for longer at the expected traffic profile when capacity evidence is needed.
