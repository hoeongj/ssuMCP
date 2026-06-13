package com.ssuai.global.admin;

import java.util.List;

public record AdminResilienceResponse(List<CircuitBreakerInfo> circuitBreakers) {

    public record CircuitBreakerInfo(
            String name,
            String state,
            float failureRate,
            float slowCallRate
    ) {}
}
