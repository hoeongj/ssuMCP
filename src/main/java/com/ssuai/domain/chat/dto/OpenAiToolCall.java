package com.ssuai.domain.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiToolCall(
        String id,
        String type,
        FunctionCall function
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionCall(
            String name,
            String arguments
    ) {
    }
}
