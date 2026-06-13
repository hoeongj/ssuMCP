package com.ssuai.global.admin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.chat.service.LlmProviderChain;
import com.ssuai.global.resilience.PyxisResilience;

@RestController
@RequestMapping("/api/admin")
public class AdminResilienceController {

    private final Optional<LlmProviderChain> llmProviderChain;
    private final PyxisResilience pyxisResilience;

    public AdminResilienceController(
            Optional<LlmProviderChain> llmProviderChain,
            PyxisResilience pyxisResilience) {
        this.llmProviderChain = llmProviderChain;
        this.pyxisResilience = pyxisResilience;
    }

    @GetMapping("/resilience")
    public AdminResilienceResponse getResilience() {
        List<AdminResilienceResponse.CircuitBreakerInfo> cbs = new ArrayList<>();

        cbs.add(new AdminResilienceResponse.CircuitBreakerInfo(
                "pyxis",
                pyxisResilience.circuitBreakerState().name(),
                pyxisResilience.circuitBreakerFailureRate(),
                -1.0f));

        llmProviderChain.ifPresent(chain -> cbs.addAll(chain.circuitBreakerStates()));

        cbs.sort(Comparator.comparing(AdminResilienceResponse.CircuitBreakerInfo::name));

        return new AdminResilienceResponse(cbs);
    }
}
