package com.ssuai.domain.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.function.BiFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.ssuai.global.kafka.ToolCallEventProducer;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

class McpToolCallSeamTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void wrapperReturnsSameOkResultAndEmitsOkOutcome() {
        MDC.put("requestId", "req-ok");
        McpSchema.CallToolResult result = result(false);
        ToolCallEventProducer producer = mock(ToolCallEventProducer.class);

        var wrapped = McpServerConfig.wrapCallHandler(spec((exchange, request) -> result), producer);

        assertThat(wrapped.apply(null, null)).isSameAs(result);
        verify(producer).tryEmit(eq("test_tool"), eq("req-ok"), anyLong(), eq("ok"));
    }

    @Test
    void wrapperReturnsSameToolErrorResultAndEmitsToolErrorOutcome() {
        MDC.put("requestId", "req-error");
        McpSchema.CallToolResult result = result(true);
        ToolCallEventProducer producer = mock(ToolCallEventProducer.class);

        var wrapped = McpServerConfig.wrapCallHandler(spec((exchange, request) -> result), producer);

        assertThat(wrapped.apply(null, null)).isSameAs(result);
        verify(producer).tryEmit(eq("test_tool"), eq("req-error"), anyLong(), eq("tool_error"));
    }

    @Test
    void wrapperPropagatesSameRuntimeExceptionAndEmitsExceptionOutcome() {
        MDC.put("requestId", "req-ex");
        RuntimeException failure = new IllegalStateException("tool failed");
        ToolCallEventProducer producer = mock(ToolCallEventProducer.class);

        var wrapped = McpServerConfig.wrapCallHandler(spec((exchange, request) -> {
            throw failure;
        }), producer);

        assertThatThrownBy(() -> wrapped.apply(null, null)).isSameAs(failure);
        verify(producer).tryEmit(eq("test_tool"), eq("req-ex"), anyLong(), eq("exception"));
    }

    @Test
    void wrapperSwallowsProducerFailureAndStillReturnsToolResult() {
        McpSchema.CallToolResult result = result(false);
        ToolCallEventProducer producer = mock(ToolCallEventProducer.class);
        doThrow(new IllegalStateException("emit failed"))
                .when(producer).tryEmit(eq("test_tool"), eq(null), anyLong(), eq("ok"));

        var wrapped = McpServerConfig.wrapCallHandler(spec((exchange, request) -> result), producer);

        assertThat(wrapped.apply(null, null)).isSameAs(result);
    }

    private static McpServerFeatures.SyncToolSpecification spec(
            BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("test_tool")
                .description("test")
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(handler)
                .build();
    }

    private static McpSchema.CallToolResult result(boolean isError) {
        return McpSchema.CallToolResult.builder()
                .content(List.<McpSchema.Content>of(new McpSchema.TextContent("ok")))
                .isError(isError)
                .build();
    }
}
