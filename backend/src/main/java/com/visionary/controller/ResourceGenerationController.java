package com.visionary.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.dto.KnowledgeTracingRadarDto;
import com.visionary.dto.PptxExportResult;
import com.visionary.dto.PptxEditExportRequest;
import com.visionary.dto.ResourceCard;
import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.dto.ResourceRecommendationResponse;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.security.AuthContext;
import com.visionary.service.KnowledgeTracingService;
import com.visionary.service.LearningMasteryPipelineService;
import com.visionary.service.LearningEffectAssessmentService;
import com.visionary.service.LearningEffectExperimentService;
import com.visionary.service.PptxExportService;
import com.visionary.service.QuizResultListener;
import com.visionary.service.ReplanTriggerService;
import com.visionary.service.ResourceGenerationFacade;
import com.visionary.service.ResourceGenerationJobService;
import com.visionary.service.ResourceRecommendationService;
import com.visionary.service.ResourceUsageService;
import com.visionary.service.ShowcaseResourceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;

@Slf4j
@RestController
@RequestMapping("/api/resources")
public class ResourceGenerationController {

    private static final long SSE_TIMEOUT_MS = 600_000L;

    private final ResourceGenerationFacade resourceService;
    private final PptxExportService pptxExportService;
    private final QuizResultListener quizResultListener;
    private final LearningEffectAssessmentService learningEffectAssessmentService;
    private final LearningEffectExperimentService learningEffectExperimentService;
    private final LearningMasteryPipelineService learningMasteryPipelineService;
    private final ResourceGenerationJobService jobService;
    private final ResourceRecommendationService recommendationService;
    private final ResourceUsageService resourceUsageService;
    private final KnowledgeTracingService knowledgeTracingService;
    private final ShowcaseResourceService showcaseResourceService;
    private final LearningSessionRepository learningSessionRepository;
    private final GeneratedArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;
    private final Executor sseStreamExecutor;

    public ResourceGenerationController(
            ResourceGenerationFacade resourceService,
            PptxExportService pptxExportService,
            QuizResultListener quizResultListener,
            LearningEffectAssessmentService learningEffectAssessmentService,
            LearningEffectExperimentService learningEffectExperimentService,
            LearningMasteryPipelineService learningMasteryPipelineService,
            ResourceGenerationJobService jobService,
            ResourceRecommendationService recommendationService,
            ResourceUsageService resourceUsageService,
            KnowledgeTracingService knowledgeTracingService,
            ShowcaseResourceService showcaseResourceService,
            LearningSessionRepository learningSessionRepository,
            GeneratedArtifactRepository artifactRepository,
            ObjectMapper objectMapper,
            @Qualifier("sseStreamExecutor") Executor sseStreamExecutor
    ) {
        this.resourceService = resourceService;
        this.pptxExportService = pptxExportService;
        this.quizResultListener = quizResultListener;
        this.learningEffectAssessmentService = learningEffectAssessmentService;
        this.learningEffectExperimentService = learningEffectExperimentService;
        this.learningMasteryPipelineService = learningMasteryPipelineService;
        this.jobService = jobService;
        this.recommendationService = recommendationService;
        this.resourceUsageService = resourceUsageService;
        this.knowledgeTracingService = knowledgeTracingService;
        this.showcaseResourceService = showcaseResourceService;
        this.learningSessionRepository = learningSessionRepository;
        this.artifactRepository = artifactRepository;
        this.objectMapper = objectMapper;
        this.sseStreamExecutor = sseStreamExecutor;
    }

    @GetMapping
    public List<ResourceCard> list(@RequestParam(required = false) Long learningSessionId) {
        if (learningSessionId == null) {
            return List.of();
        }
        requireOwnedSession(learningSessionId);
        return resourceService.listResourceCards(learningSessionId);
    }

    /** 内置示例资源（CNN 专题），个人库内容较少时用于填充展示。 */
    @GetMapping("/showcase")
    public List<ResourceCard> listShowcase() {
        return showcaseResourceService.listShowcaseCards();
    }

    @GetMapping("/recommendations")
    public ResourceRecommendationResponse recommend(
            @RequestParam Long learningSessionId,
            @RequestParam(required = false) String learnerProfileSnapshot,
            @RequestParam(required = false) String weakPointsSnapshot,
            @RequestParam(required = false) String cognitiveStyle,
            @RequestParam(required = false, defaultValue = "false") boolean recentQuizLow
    ) {
        requireOwnedSession(learningSessionId);
        List<GeneratedArtifact> artifacts = resourceService.listArtifacts(learningSessionId);
        return recommendationService.recommend(
                artifacts,
                learnerProfileSnapshot,
                weakPointsSnapshot,
                cognitiveStyle,
                recentQuizLow,
                null,
                learningSessionId,
                "manual",
                null
        );
    }

    @PostMapping("/generate")
    public ResourceGenerationResponse generate(@RequestBody ResourceGenerationRequest request) {
        requireOwnedSession(request.learningSessionId());
        return resourceService.generate(request);
    }

    @PostMapping("/generate/jobs")
    public ResourceGenerationJobService.JobSnapshot startGenerateJob(@RequestBody ResourceGenerationRequest request) {
        requireOwnedSession(request.learningSessionId());
        return jobService.start(request);
    }

    @GetMapping("/generate/jobs/{taskId}")
    public ResourceGenerationJobService.JobSnapshot getGenerateJob(@PathVariable String taskId) {
        ResourceGenerationJobService.JobSnapshot snapshot = jobService.get(taskId);
        requireOwnedJob(snapshot);
        return snapshot;
    }

    @DeleteMapping("/generate/jobs/{taskId}")
    public ResourceGenerationJobService.JobSnapshot cancelGenerateJob(@PathVariable String taskId) {
        requireOwnedJob(jobService.get(taskId));
        return jobService.cancel(taskId);
    }

    @PostMapping("/generate/jobs/{taskId}/retry")
    public ResourceGenerationJobService.JobSnapshot retryGenerateJob(@PathVariable String taskId) {
        requireOwnedJob(jobService.get(taskId));
        return jobService.retry(taskId);
    }

    /**
     * SSE stream for resource generation progress ({@code agent_step} / {@code workflow} / {@code complete}).
     */
    @PostMapping(value = "/generate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generateStream(@RequestBody ResourceGenerationRequest request) {
        requireOwnedSession(request.learningSessionId());
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sseStreamExecutor.execute(() -> runGenerationStream(emitter, request));
        return emitter;
    }

    private void runGenerationStream(SseEmitter emitter, ResourceGenerationRequest request) {
        try {
            ResourceGenerationResponse response = resourceService.generate(request, event -> {
                try {
                    emitter.send(SseEmitter.event()
                            .name(event.phase().equals("agent_step") ? "agent_step" : event.phase())
                            .data(objectMapper.writeValueAsString(event)));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            emitter.send(SseEmitter.event()
                    .name("complete")
                    .data(objectMapper.writeValueAsString(response)));
            emitter.complete();
        } catch (Exception e) {
            log.warn("Resource generation stream failed: {}", e.getMessage(), e);
            try {
                emitter.send(SseEmitter.event().name("error").data(
                        e.getMessage() != null ? e.getMessage() : "资源生成失败"));
                emitter.complete();
            } catch (IOException ignored) {
                emitter.completeWithError(e);
            }
        }
    }

    /**
     * Export a GeneratedArtifact as downloadable PPTX.
     * Uses ai_engine/pptx_generator.py (python-pptx) to produce professional slides:
     * Cover + Knowledge + Practice + Citations.
     */
    @GetMapping("/{id}/export/pptx")
    public ResponseEntity<byte[]> exportPptx(@PathVariable Long id) {
        requireOwnedArtifact(id);
        byte[] pptxBytes = pptxExportService.exportPptx(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation"));
        headers.setContentDispositionFormData("attachment", "visionary-resource-" + id + ".pptx");
        headers.setContentLength(pptxBytes.length);
        return ResponseEntity.ok().headers(headers).body(pptxBytes);
    }

    /**
     * Export full learning session as one PPTX (cover + profile + TOC + handout + mindmap + quiz + video + citations).
     * Uses enhanced pptx_generator.py --json mode for professional multi-resource deck.
     */
    @GetMapping("/session/{sessionId}/export/pptx")
    public ResponseEntity<byte[]> exportSessionPptx(@PathVariable Long sessionId,
                                                    @RequestParam(defaultValue = "standard") String quality) {
        requireOwnedSession(sessionId);
        PptxExportResult result = pptxExportService.exportSessionPptx(sessionId, quality);
        byte[] pptxBytes = result.bytes();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.presentationml.presentation"));
        headers.setContentDispositionFormData("attachment", "visionary-session-" + sessionId + ".pptx");
        headers.setContentLength(pptxBytes.length);
        headers.set("X-Pptx-Export-Mode", result.exportMode());
        return ResponseEntity.ok().headers(headers).body(pptxBytes);
    }

    @PostMapping("/session/{sessionId}/export/pptx/edited")
    public ResponseEntity<byte[]> exportEditedSessionPptx(
            @PathVariable Long sessionId,
            @jakarta.validation.Valid @RequestBody PptxEditExportRequest request
    ) {
        requireOwnedSession(sessionId);
        byte[] pptxBytes = pptxExportService.exportEditedPptx(request);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"));
        headers.setContentDispositionFormData("attachment", "visionary-edited-session-" + sessionId + ".pptx");
        headers.setContentLength(pptxBytes.length);
        headers.set("X-Pptx-Export-Mode", "edited-java");
        return ResponseEntity.ok().headers(headers).body(pptxBytes);
    }

    /**
     * Quiz submission endpoint - triggers unified replanning when accuracy is low or new weak points appear.
     * This closes the "practice results → dynamic path & resource adjustment" loop.
     */
    @PostMapping("/quiz/submit")
    public ReplanTriggerService.ReplanResult submitQuizResult(@RequestBody QuizResultRequest request) {
        Long userId = requireMatchingUser(request.userId());
        if (request.learningSessionId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "learningSessionId is required");
        }
        requireOwnedSession(request.learningSessionId());
        return quizResultListener.onQuizSubmitted(
                userId,
                request.learningSessionId(),
                Math.max(0D, Math.min(1D, request.accuracy() == null ? 0D : request.accuracy())),
                request.newWeakPoints() == null ? List.of() : request.newWeakPoints(),
                request.errorPatterns() == null ? List.of() : request.errorPatterns(),
                request.quizFeedback()
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QuizResultRequest(
            @JsonAlias({"user_id"}) Long userId,
            @JsonAlias({"sessionId", "learning_session_id"}) Long learningSessionId,
            Double accuracy,
            @JsonAlias({"weakPoints", "new_weak_points"}) List<String> newWeakPoints,
            @JsonAlias({"errors", "error_patterns"}) List<String> errorPatterns,
            @JsonAlias({"feedback", "quiz_feedback"}) String quizFeedback
    ) {}

    @PostMapping("/learning/pre-test")
    public String recordPreTest(@RequestBody PreTestRequest request) {
        Long userId = requireMatchingUser(request.userId());
        requireOwnedSession(request.learningSessionId());
        learningMasteryPipelineService.recordExplicitPreTest(
                userId,
                request.learningSessionId(),
                request.concept(),
                request.scorePercent()
        );
        return "recorded";
    }

    public record PreTestRequest(
            Long userId,
            Long learningSessionId,
            String concept,
            double scorePercent
    ) {}

    @PostMapping("/learning/post-test")
    public String recordPostTest(@RequestBody PostTestRequest request) {
        Long userId = requireMatchingUser(request.userId());
        requireOwnedSession(request.learningSessionId());
        learningEffectExperimentService.recordPostTest(
                userId,
                request.learningSessionId(),
                request.concept(),
                request.scorePercent()
        );
        return "recorded";
    }

    public record PostTestRequest(
            Long userId,
            Long learningSessionId,
            String concept,
            double scorePercent
    ) {}

    @GetMapping("/learning/effect-experiment")
    public LearningEffectExperimentService.LearningEffectExperimentReport learningEffectExperiment(
            @RequestParam Long userId,
            @RequestParam(required = false) Long learningSessionId
    ) {
        Long resolvedUserId = requireMatchingUser(userId);
        if (learningSessionId != null) {
            requireOwnedSession(learningSessionId);
        }
        return learningEffectExperimentService.buildReport(resolvedUserId, learningSessionId);
    }

    @GetMapping(value = "/learning/effect-experiment/export", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> exportLearningEffectExperiment(
            @RequestParam Long userId,
            @RequestParam(required = false) Long learningSessionId
    ) {
        Long resolvedUserId = requireMatchingUser(userId);
        if (learningSessionId != null) {
            requireOwnedSession(learningSessionId);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(learningEffectExperimentService.exportMarkdown(resolvedUserId, learningSessionId));
    }

    /**
     * Record a learning metric (quiz accuracy, resource feedback, etc.)
     */
    @PostMapping("/metrics/record")
    public String recordMetric(@RequestBody MetricRecordRequest req) {
        if (req == null || req.type() == null || req.type().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metric type is required");
        }
        Long userId = requireMatchingUser(req.userId());
        if (req.sessionId() != null) {
            requireOwnedSession(req.sessionId());
        }
        learningEffectAssessmentService.recordMetric(
                userId, req.sessionId(), req.type().trim().toUpperCase(), req.concept(),
                req.numericValue(), req.textValue(), req.source()
        );
        return "recorded";
    }

    @PostMapping("/usage/record")
    public String recordUsage(@RequestBody ResourceUsageRequest req) {
        Long userId = requireMatchingUser(req.userId());
        if (req.learningSessionId() != null) {
            requireOwnedSession(req.learningSessionId());
        }
        if (req.resourceId() != null) {
            requireOwnedArtifact(req.resourceId());
        }
        resourceUsageService.recordUsage(
                userId,
                req.learningSessionId(),
                req.resourceId(),
                req.actionType(),
                req.durationSeconds(),
                req.feedback()
        );
        return "recorded";
    }

    /**
     * Trigger full learning effect assessment (radar + suggestions + optional replan)
     */
    @PostMapping("/learning/assess")
    public LearningEffectAssessmentService.AssessmentResult assess(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long learningSessionId,
            @RequestBody(required = false) AssessmentRequest body
    ) {
        Long requestedUserId = userId != null ? userId : body != null ? body.userId() : null;
        Long resolvedUserId = requireMatchingUser(requestedUserId);
        Long resolvedSessionId = learningSessionId != null
                ? learningSessionId : body != null ? body.learningSessionId() : null;
        if (resolvedSessionId != null) {
            requireOwnedSession(resolvedSessionId);
        }
        return learningEffectAssessmentService.assessAndRecommend(resolvedUserId, resolvedSessionId);
    }

    @GetMapping("/knowledge-tracing/radar")
    public KnowledgeTracingRadarDto knowledgeTracingRadar(@RequestParam(required = false) Long userId) {
        return knowledgeTracingService.getRadarSnapshot(requireMatchingUser(userId));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AssessmentRequest(
            @JsonAlias({"user_id"}) Long userId,
            @JsonAlias({"learning_session_id", "sessionId"}) Long learningSessionId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MetricRecordRequest(
            @JsonAlias({"user_id"}) Long userId,
            @JsonAlias({"learningSessionId", "learning_session_id"}) Long sessionId,
            @JsonAlias({"metricType", "metric_type"}) String type,
            String concept,
            @JsonAlias({"value", "valueNumeric", "numeric_value"}) Double numericValue,
            @JsonAlias({"valueText", "text_value"}) String textValue,
            String source
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResourceUsageRequest(
            @JsonAlias({"user_id"}) Long userId,
            @JsonAlias({"learning_session_id", "sessionId"}) Long learningSessionId,
            @JsonAlias({"resource_id", "artifactId"}) Long resourceId,
            @JsonAlias({"action_type", "action"}) String actionType,
            @JsonAlias({"duration_seconds", "duration"}) Integer durationSeconds,
            String feedback
    ) {}

    private void requireOwnedJob(ResourceGenerationJobService.JobSnapshot snapshot) {
        if (snapshot != null && snapshot.learningSessionId() != null) {
            requireOwnedSession(snapshot.learningSessionId());
        }
    }

    private void requireOwnedArtifact(Long artifactId) {
        if (artifactId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "resource id is required");
        }
        GeneratedArtifact artifact = artifactRepository.findById(artifactId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "resource not found"));
        requireOwnedSession(artifact.getLearningSessionId());
    }

    private void requireOwnedSession(Long learningSessionId) {
        if (learningSessionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "learningSessionId is required");
        }
        Long currentUserId = requireMatchingUser(null);
        if (!learningSessionRepository.existsByIdAndUserId(learningSessionId, currentUserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "learning session not found");
        }
    }

    private static Long requireMatchingUser(Long requestedUserId) {
        Long currentUserId = AuthContext.currentRegisteredUserId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录后访问学习数据"));
        if (requestedUserId != null && !currentUserId.equals(requestedUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问其他用户的学习数据");
        }
        return currentUserId;
    }
}
