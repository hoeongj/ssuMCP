package com.ssuai.global.admin;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.ssuai.domain.chat.service.LlmProviderChain;
import com.ssuai.global.exception.ForbiddenException;
import com.ssuai.global.resilience.PyxisResilience;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminResilienceControllerTests {

    private static final String ADMIN = "20240001";

    private static AdminAccessProperties allowlist(String... ids) {
        AdminAccessProperties props = new AdminAccessProperties();
        props.setStudentIds(List.of(ids));
        return props;
    }

    @Test
    void getResilience_returnsCircuitBreakerList_withoutLlmChain() {
        PyxisResilience pyxis = PyxisResilience.forTesting(new SimpleMeterRegistry());
        AdminResilienceController controller =
                new AdminResilienceController(Optional.empty(), pyxis, allowlist(ADMIN));

        AdminResilienceResponse response = controller.getResilience(ADMIN);

        assertThat(response.circuitBreakers()).hasSize(2);
        assertThat(response.circuitBreakers().stream().map(AdminResilienceResponse.CircuitBreakerInfo::name).toList())
                .containsExactly("pyxis-read", "pyxis-write");
        assertThat(response.circuitBreakers().getFirst().state()).isEqualTo("CLOSED");
    }

    @Test
    void getResilience_includesLlmBreakersWhenPresent() {
        PyxisResilience pyxis = PyxisResilience.forTesting(new SimpleMeterRegistry());
        LlmProviderChain chain = mock(LlmProviderChain.class);
        when(chain.circuitBreakerStates()).thenReturn(List.of(
                new AdminResilienceResponse.CircuitBreakerInfo("llm-groq", "CLOSED", -1.0f, -1.0f)
        ));
        AdminResilienceController controller =
                new AdminResilienceController(Optional.of(chain), pyxis, allowlist(ADMIN));

        AdminResilienceResponse response = controller.getResilience(ADMIN);

        assertThat(response.circuitBreakers()).hasSize(3);
        assertThat(response.circuitBreakers().stream().map(AdminResilienceResponse.CircuitBreakerInfo::name).toList())
                .containsExactly("llm-groq", "pyxis-read", "pyxis-write");
    }

    @Test
    void getResilience_sortedByName() {
        PyxisResilience pyxis = PyxisResilience.forTesting(new SimpleMeterRegistry());
        LlmProviderChain chain = mock(LlmProviderChain.class);
        when(chain.circuitBreakerStates()).thenReturn(List.of(
                new AdminResilienceResponse.CircuitBreakerInfo("llm-groq", "CLOSED", -1.0f, -1.0f),
                new AdminResilienceResponse.CircuitBreakerInfo("llm-gemini", "CLOSED", -1.0f, -1.0f)
        ));
        AdminResilienceController controller =
                new AdminResilienceController(Optional.of(chain), pyxis, allowlist(ADMIN));

        AdminResilienceResponse response = controller.getResilience(ADMIN);

        List<String> names = response.circuitBreakers().stream()
                .map(AdminResilienceResponse.CircuitBreakerInfo::name).toList();
        assertThat(names).containsExactly("llm-gemini", "llm-groq", "pyxis-read", "pyxis-write");
    }

    @Test
    void getResilience_forbidsStudentNotInAllowlist() {
        PyxisResilience pyxis = PyxisResilience.forTesting(new SimpleMeterRegistry());
        AdminResilienceController controller =
                new AdminResilienceController(Optional.empty(), pyxis, allowlist(ADMIN));

        assertThatThrownBy(() -> controller.getResilience("19999999"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getResilience_forbidsEveryoneWhenAllowlistEmpty() {
        PyxisResilience pyxis = PyxisResilience.forTesting(new SimpleMeterRegistry());
        AdminResilienceController controller =
                new AdminResilienceController(Optional.empty(), pyxis, allowlist());

        assertThatThrownBy(() -> controller.getResilience(ADMIN))
                .isInstanceOf(ForbiddenException.class);
    }
}
