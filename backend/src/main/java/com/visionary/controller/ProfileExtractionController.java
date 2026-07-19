package com.visionary.controller;

import com.visionary.dto.ProfileExtractionRequest;
import com.visionary.dto.ProfileExtractionResponse;
import com.visionary.dto.OnboardingAnswerValidationRequest;
import com.visionary.dto.OnboardingAnswerValidationResponse;
import com.visionary.security.AuthContext;
import com.visionary.service.LearnerProfileExtractionService;
import com.visionary.service.OnboardingAnswerValidationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileExtractionController {

    private final LearnerProfileExtractionService extractionService;
    private final OnboardingAnswerValidationService onboardingAnswerValidationService;

    @PostMapping("/validate-onboarding-answer")
    public OnboardingAnswerValidationResponse validateOnboardingAnswer(
            @Valid @RequestBody OnboardingAnswerValidationRequest request) {
        return onboardingAnswerValidationService.validate(request);
    }

    @PostMapping("/extract")
    public ProfileExtractionResponse extract(@RequestBody ProfileExtractionRequest request) {
        Long authenticatedUserId = AuthContext.currentRegisteredUserId().orElse(null);
        ProfileExtractionRequest scopedRequest = new ProfileExtractionRequest(
                authenticatedUserId,
                request.conversationText(),
                request.assessmentSummary(),
                request.previousProfileSnapshot(),
                request.emotionSnapshot(),
                request.extractPhase()
        );
        return extractionService.extract(scopedRequest);
    }
}
