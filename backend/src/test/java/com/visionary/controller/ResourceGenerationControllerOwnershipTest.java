package com.visionary.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.security.CustomUserDetails;
import com.visionary.service.KnowledgeTracingService;
import com.visionary.service.LearningEffectAssessmentService;
import com.visionary.service.LearningEffectExperimentService;
import com.visionary.service.LearningMasteryPipelineService;
import com.visionary.service.PptxExportService;
import com.visionary.service.QuizResultListener;
import com.visionary.service.ResourceGenerationFacade;
import com.visionary.service.ResourceGenerationJobService;
import com.visionary.service.ResourceRecommendationService;
import com.visionary.service.ResourceUsageService;
import com.visionary.service.ShowcaseResourceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourceGenerationControllerOwnershipTest {

    @Mock ResourceGenerationFacade resourceService;
    @Mock PptxExportService pptxExportService;
    @Mock QuizResultListener quizResultListener;
    @Mock LearningEffectAssessmentService learningEffectAssessmentService;
    @Mock LearningEffectExperimentService learningEffectExperimentService;
    @Mock LearningMasteryPipelineService learningMasteryPipelineService;
    @Mock ResourceGenerationJobService jobService;
    @Mock ResourceRecommendationService recommendationService;
    @Mock ResourceUsageService resourceUsageService;
    @Mock KnowledgeTracingService knowledgeTracingService;
    @Mock ShowcaseResourceService showcaseResourceService;
    @Mock LearningSessionRepository learningSessionRepository;
    @Mock GeneratedArtifactRepository artifactRepository;
    @Mock ObjectMapper objectMapper;
    @Mock Executor executor;

    @InjectMocks ResourceGenerationController controller;

    @BeforeEach
    void authenticateUser() {
        CustomUserDetails details = new CustomUserDetails(
                7L,
                "learner",
                "unused",
                "learner@example.test",
                "Learner",
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                true,
                true,
                true,
                true
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void refusesToStartGenerationForAnotherUsersSession() {
        ResourceGenerationRequest request = new ResourceGenerationRequest(
                55L, "CNN", "{}", "[]", "{}", List.of()
        );
        when(learningSessionRepository.existsByIdAndUserId(55L, 7L)).thenReturn(false);

        assertThatThrownBy(() -> controller.startGenerateJob(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        verifyNoInteractions(jobService);
    }

    @Test
    void refusesToReadAnotherUsersBackgroundJob() {
        ResourceGenerationJobService.JobSnapshot snapshot = new ResourceGenerationJobService.JobSnapshot(
                "res-other", 55L, "RUNNING", 40, "running", null, List.of(), null, null,
                null, null, null, 30, false
        );
        when(jobService.get("res-other")).thenReturn(snapshot);
        when(learningSessionRepository.existsByIdAndUserId(55L, 7L)).thenReturn(false);

        assertThatThrownBy(() -> controller.getGenerateJob("res-other"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    void refusesToSubmitQuizForAnotherUsersSession() {
        ResourceGenerationController.QuizResultRequest request =
                new ResourceGenerationController.QuizResultRequest(7L, 55L, 0.8, List.of(), List.of(), null);
        when(learningSessionRepository.existsByIdAndUserId(55L, 7L)).thenReturn(false);

        assertThatThrownBy(() -> controller.submitQuizResult(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        verifyNoInteractions(quizResultListener);
    }

    @Test
    void refusesToWriteUsageForAnotherUsersArtifact() {
        com.visionary.entity.GeneratedArtifact artifact = new com.visionary.entity.GeneratedArtifact();
        artifact.setLearningSessionId(55L);
        when(artifactRepository.findById(9L)).thenReturn(java.util.Optional.of(artifact));
        when(learningSessionRepository.existsByIdAndUserId(55L, 7L)).thenReturn(false);
        ResourceGenerationController.ResourceUsageRequest request =
                new ResourceGenerationController.ResourceUsageRequest(7L, null, 9L, "view", 30, null);

        assertThatThrownBy(() -> controller.recordUsage(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND");
        verifyNoInteractions(resourceUsageService);
    }
}
