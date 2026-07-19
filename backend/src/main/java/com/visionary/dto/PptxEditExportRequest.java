package com.visionary.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PptxEditExportRequest(
        @NotBlank @Size(max = 255) String deckTitle,
        @Size(max = 500) String subtitle,
        @NotEmpty @Size(max = 40) List<@Valid SlideEdit> slides
) {
    public record SlideEdit(
            @NotBlank @Size(max = 255) String title,
            @NotBlank @Size(max = 3_500) String body
    ) {
    }
}
