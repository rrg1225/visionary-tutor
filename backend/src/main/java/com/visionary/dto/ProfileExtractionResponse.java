package com.visionary.dto;

public record ProfileExtractionResponse(
        String profileSnapshot,
        boolean llmUsed,
        String status,
        String message
) {
}
