package com.visionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AppendChatMessageRequest(
        @NotBlank
        @Pattern(regexp = "user|assistant", message = "role must be user or assistant")
        String role,
        @NotBlank String content,
        String contextType,
        String contextKey,
        String contextTitle
) {
}
