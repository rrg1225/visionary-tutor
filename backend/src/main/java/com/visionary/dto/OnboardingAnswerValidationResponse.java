package com.visionary.dto;

public record OnboardingAnswerValidationResponse(
        boolean valid,
        String reason,
        boolean aiUsed
) {
}
