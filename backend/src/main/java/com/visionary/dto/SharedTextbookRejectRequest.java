package com.visionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SharedTextbookRejectRequest(
        @NotBlank @Size(max = 2000) String reason
) {
}
