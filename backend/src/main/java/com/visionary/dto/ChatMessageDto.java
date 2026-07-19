package com.visionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * OpenAI-compatible chat message for multi-turn streaming.
 */
public record ChatMessageDto(
        @NotBlank
        @Pattern(regexp = "system|user|assistant", message = "role must be system, user, or assistant")
        String role,
        @NotBlank String content
) {
}
