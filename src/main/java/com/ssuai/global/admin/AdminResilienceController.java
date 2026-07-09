package com.ssuai.global.admin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ssuai.domain.chat.service.LlmProviderChain;
import com.ssuai.global.auth.AuthUser;
import com.ssuai.global.exception.ForbiddenException;
import com.ssuai.global.resilience.PyxisResilience;

/**
 * Operational resilience dashboard data (circuit-breaker states).
 *
 * <h2>Owner-only authorization (ADR 0063)</h2>
 * <p>Spring Security leaves {@code /api/admin/**} as {@code permitAll} — like
 * every private REST endpoint here, the gate is per-controller, not in the
 * security filter chain. This endpoint requires a valid ssuAI session
 * ({@code @AuthUser} → 401 if absent) AND that the caller's student ID is in the
 * {@code ssuai.admin.student-ids} allowlist (→ 403 otherwise). An empty allowlist
 * denies everyone. This closes the prior gap where the endpoint was
 * world-readable — the structural risk of allow-by-default authorization where a
 * single forgotten {@code @AuthUser} silently exposes an endpoint.</p>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminResilienceController {

    private final Optional<LlmProviderChain> llmProviderChain;
    private final PyxisResilience pyxisResilience;
    private final AdminAccessProperties adminAccess;

    public AdminResilienceController(
            Optional<LlmProviderChain> llmProviderChain,
            PyxisResilience pyxisResilience,
            AdminAccessProperties adminAccess) {
        this.llmProviderChain = llmProviderChain;
        this.pyxisResilience = pyxisResilience;
        this.adminAccess = adminAccess;
    }

    @GetMapping("/resilience")
    public AdminResilienceResponse getResilience(@AuthUser String studentId) {
        if (!adminAccess.isAllowed(studentId)) {
            throw new ForbiddenException();
        }

        List<AdminResilienceResponse.CircuitBreakerInfo> cbs = new ArrayList<>();

        cbs.add(new AdminResilienceResponse.CircuitBreakerInfo(
                "pyxis-read",
                pyxisResilience.readCircuitBreakerState().name(),
                pyxisResilience.readCircuitBreakerFailureRate(),
                pyxisResilience.readCircuitBreakerSlowCallRate()));
        cbs.add(new AdminResilienceResponse.CircuitBreakerInfo(
                "pyxis-write",
                pyxisResilience.writeCircuitBreakerState().name(),
                pyxisResilience.writeCircuitBreakerFailureRate(),
                pyxisResilience.writeCircuitBreakerSlowCallRate()));

        llmProviderChain.ifPresent(chain -> cbs.addAll(chain.circuitBreakerStates()));

        cbs.sort(Comparator.comparing(AdminResilienceResponse.CircuitBreakerInfo::name));

        return new AdminResilienceResponse(cbs);
    }
}
