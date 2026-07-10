# Tool-call event pipeline measurement plan

> Date: 2026-07-10. Scope: Phase 2 Unit 1 Kafka tool-call event pipeline.

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

No k6 latency run was taken in this unit. The follow-up load run should reuse the existing MCP k6 harness and compare p95/p99 with Kafka disabled, healthy, and down.
