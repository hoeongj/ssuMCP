package com.ssuai.domain.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @Size(max = 64)
        @Pattern(regexp = "^$|^[a-zA-Z0-9-]+$", message = "must contain only letters, numbers, and hyphens")
        String conversationId,

        @NotBlank
        @Size(max = 1000)
        String message
) {
}
