package com.ssuai.global.admin;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.chat.service.LlmProviderChain;
import com.ssuai.global.resilience.PyxisResilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminResilienceControllerTests {

    @Test
    void getResilience_returnsCircuitBreakerList_withoutLlmChain() {
        PyxisResilience pyxis = PyxisResilience.forTesting(new SimpleMeterRegistry());
        AdminResilienceController controller = new AdminResilienceController(Optional.empty(), pyxis);

        AdminResilienceResponse response = controller.getResilience();

        assertThat(response.circuitBreakers()).hasSize(1);
        assertThat(response.circuitBreakers().getFirst().name()).isEqualTo("pyxis");
        assertThat(response.circuitBreakers().getFirst().state()).isEqualTo("CLOSED");
    }

    @Test
    void getResilience_includesLlmBreakersWhenPresent() {
        PyxisResilience pyxis = PyxisResilience.forTesting(new SimpleMeterRegistry());
        LlmProviderChain chain = mock(LlmProviderChain.class);
        when(chain.circuitBreakerStates()).thenReturn(List.of(
                new AdminResilienceResponse.CircuitBreakerInfo("llm-groq", "CLOSED", -1.0f, -1.0f)
        ));
        AdminResilienceController controller = new AdminResilienceController(Optional.of(chain), pyxis);

        AdminResilienceResponse response = controller.getResilience();

        assertThat(response.circuitBreakers()).hasSize(2);
        assertThat(response.circuitBreakers().stream().map(AdminResilienceResponse.CircuitBreakerInfo::name).toList())
                .containsExactly("llm-groq", "pyxis");
    }

    @Test
    void getResilience_sortedByName() {
        PyxisResilience pyxis = PyxisResilience.forTesting(new SimpleMeterRegistry());
        LlmProviderChain chain = mock(LlmProviderChain.class);
        when(chain.circuitBreakerStates()).thenReturn(List.of(
                new AdminResilienceResponse.CircuitBreakerInfo("llm-groq", "CLOSED", -1.0f, -1.0f),
                new AdminResilienceResponse.CircuitBreakerInfo("llm-gemini", "CLOSED", -1.0f, -1.0f)
        ));
        AdminResilienceController controller = new AdminResilienceController(Optional.of(chain), pyxis);

        AdminResilienceResponse response = controller.getResilience();

        List<String> names = response.circuitBreakers().stream()
                .map(AdminResilienceResponse.CircuitBreakerInfo::name).toList();
        assertThat(names).containsExactly("llm-gemini", "llm-groq", "pyxis");
    }
}
