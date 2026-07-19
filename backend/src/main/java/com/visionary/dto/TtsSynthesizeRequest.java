package com.visionary.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TtsSynthesizeRequest(
        @NotBlank @Size(max = 4000) String text,
        String voice,
        Double speed,
        String format
) {
}
