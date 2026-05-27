package com.ssuai.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTests {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void usesIncomingTraceIdAndClearsMdcAfterRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/meals/today");
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingFilterChain filterChain = new CapturingFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(filterChain.traceIdDuringRequest()).isEqualTo("trace-123");
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo("trace-123");
        assertThat(MDC.get(TraceIdFilter.TRACE_ID_KEY)).isNull();
    }

    @Test
    void createsTraceIdWhenHeaderIsMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/meals/today");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingFilterChain filterChain = new CapturingFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(filterChain.traceIdDuringRequest()).isNotBlank();
        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .isEqualTo(filterChain.traceIdDuringRequest());
    }

    @Test
    void replacesOversizedIncomingTraceId() throws ServletException, IOException {
        String oversizedTraceId = "x".repeat(129);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/meals/today");
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, oversizedTraceId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingFilterChain filterChain = new CapturingFilterChain();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER))
                .isNotBlank()
                .isNotEqualTo(oversizedTraceId);
        assertThat(filterChain.traceIdDuringRequest())
                .isEqualTo(response.getHeader(TraceIdFilter.TRACE_ID_HEADER));
    }

    private static class CapturingFilterChain extends MockFilterChain {

        private String traceIdDuringRequest;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                throws IOException, ServletException {
            traceIdDuringRequest = MDC.get(TraceIdFilter.TRACE_ID_KEY);
            super.doFilter(request, response);
        }

        String traceIdDuringRequest() {
            return traceIdDuringRequest;
        }
    }
}
