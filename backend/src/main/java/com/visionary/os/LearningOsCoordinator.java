package com.visionary.os;

import com.visionary.dto.ProfileExtractionRequest;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.service.LearnerProfileExtractionService;
import com.visionary.service.LearningPathGraphService;
import com.visionary.service.LearningPathRePlanService;
import com.visionary.service.RecommendationPushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LearningOsCoordinator {

    private final PolicyEngine policyEngine;
    private final LearnerStateStore learnerStateStore;
    private final RemediationDispatcher remediationDispatcher;
    private final LearningPathRePlanService learningPathRePlanService;
    private final GeneratedArtifactRepository artifactRepository;
    private final LearnerProfileExtractionService profileExtractionService;
    private final LearningPathGraphService learningPathGraphService;
    private final RecommendationPushService recommendationPushService;

    @EventListener
    @Transactional
    public void onLearningEvent(LearningEvent event) {
        PolicyEngine.PolicyDecision decision = policyEngine.decide(event);
        learnerStateStore.recordPolicyReason(event.userId(), decision.reason());

        if (!decision.executeActions()) {
            return;
        }

        switch (event.type()) {
            case QUIZ_SUBMITTED -> handleQuizSubmitted(event, decision);
            case ASSESSMENT_COMPLETED -> handleAssessmentCompleted(event, decision);
            default -> log.debug("[LearningOS] no handler for {}", event.type());
        }
    }

    private void handleQuizSubmitted(LearningEvent event, PolicyEngine.PolicyDecision decision) {
        Map<String, Object> payload = event.payload();
        double accuracy = toDouble(payload.get("accuracy"));
        List<String> weakPoints = stringList(payload.get("newWeakPoints"));
        List<String> errorPatterns = stringList(payload.get("errorPatterns"));
        String quizFeedback = String.valueOf(payload.getOrDefault("quizFeedback", ""));
        String profileSnapshot = String.valueOf(payload.getOrDefault("profileSnapshot", "{}"));

        if (decision.actions().contains(PolicyEngine.PolicyAction.EXTRACT_PROFILE_FROM_QUIZ)) {
            try {
                var extract = profileExtractionService.extract(new ProfileExtractionRequest(
                        event.userId(),
                        quizFeedback,
                        "测验准确率 %.0f%%，薄弱点：%s，易错：%s".formatted(
                                accuracy * 100,
                                String.join("、", weakPoints),
                                String.join("、", errorPatterns)
                        ),
                        profileSnapshot,
                        "",
                        "QUIZ_FEEDBACK"
                ));
                profileSnapshot = extract.profileSnapshot();
                learnerStateStore.bumpProfileVersion(event.userId(), profileSnapshot, decision.reason());
            } catch (Exception e) {
                log.warn("[LearningOS] quiz profile extract failed: {}", e.getMessage());
            }
        }

        String runId = String.valueOf(payload.getOrDefault("runId", "REPLAN-QUIZ-" + System.currentTimeMillis()));

        if (decision.actions().contains(PolicyEngine.PolicyAction.REPLAN_PATH)) {
            persistPathArtifact(event, runId, profileSnapshot, decision.reason(), "练习结果后自动重规划");
        }

        if (decision.actions().contains(PolicyEngine.PolicyAction.GENERATE_REMEDIAL_AGENTS)) {
            boolean asyncQueued = remediationDispatcher.dispatch(new RemediationJob(
                    runId,
                    event.learningSessionId(),
                    event.userId(),
                    profileSnapshot,
                    accuracy,
                    weakPoints,
                    errorPatterns,
                    quizFeedback,
                    "quiz_submission"
            ));
            log.info("[LearningOS] remediation dispatched runId={} async={}", runId, asyncQueued);
        }

        maybePushRecommendations(event, decision, profileSnapshot, weakPoints, accuracy < 0.6);
    }

    private void handleAssessmentCompleted(LearningEvent event, PolicyEngine.PolicyDecision decision) {
        Map<String, Object> payload = event.payload();
        String profileSnapshot = String.valueOf(payload.getOrDefault("profileSnapshot", "{}"));
        List<String> weakPoints = stringList(payload.get("weakPoints"));
        String runId = String.valueOf(payload.getOrDefault("runId", "REPLAN-ASSESS-" + System.currentTimeMillis()));

        learnerStateStore.bumpPathVersion(event.userId(), decision.reason());

        if (decision.actions().contains(PolicyEngine.PolicyAction.GENERATE_REMEDIAL_AGENTS) && !weakPoints.isEmpty()) {
            remediationDispatcher.dispatch(new RemediationJob(
                    runId,
                    event.learningSessionId(),
                    event.userId(),
                    profileSnapshot,
                    0.5D,
                    weakPoints,
                    List.of(),
                    String.valueOf(payload.getOrDefault("assessmentSummary", "")),
                    "visual_assessment"
            ));
        }

        maybePushRecommendations(event, decision, profileSnapshot, weakPoints, false);
    }

    private void maybePushRecommendations(
            LearningEvent event,
            PolicyEngine.PolicyDecision decision,
            String profileSnapshot,
            List<String> weakPoints,
            boolean recentQuizLow
    ) {
        if (!decision.actions().contains(PolicyEngine.PolicyAction.PUSH_RECOMMENDATIONS)) {
            return;
        }
        String weakSnapshot = weakPoints.isEmpty() ? "" : String.join("、", weakPoints);
        recommendationPushService.pushForSession(
                event.userId(),
                event.learningSessionId(),
                profileSnapshot,
                weakSnapshot,
                null,
                recentQuizLow,
                "event",
                decision.reason()
        ).ifPresent(push -> log.info("[LearningOS] recommendation push logged id={} sessionId={}",
                push.pushId(), event.learningSessionId()));
    }

    private void persistPathArtifact(
            LearningEvent event,
            String runId,
            String profileSnapshot,
            String reason,
            String titleSuffix
    ) {
        var plan = learningPathRePlanService.rePlan(event.learningSessionId(), profileSnapshot, null);
        String graphJson = learningPathGraphService.graphJsonFromPlan(plan);
        GeneratedArtifact pathArtifact = new GeneratedArtifact();
        pathArtifact.setLearningSessionId(event.learningSessionId());
        pathArtifact.setRunId(runId);
        pathArtifact.setArtifactType(GeneratedArtifact.ArtifactType.LEARNING_PATH);
        pathArtifact.setTitle("动态学习路径（" + titleSuffix + "）");
        pathArtifact.setContentMarkdown(formatPathPlan(plan));
        pathArtifact.setContentJson(learningPathGraphService.mergeGraphIntoContentJson(null, graphJson));
        pathArtifact.setValidationStatus("PROFILE_DERIVED");
        pathArtifact.setPublishStatus(PublishStatus.DEGRADED.name());
        pathArtifact.setVerificationAuditJson("{\"grounding\":\"profile_and_assessment_signals\",\"ragGrounded\":false}");
        pathArtifact.setReviewNotes(reason);
        pathArtifact.setProgress(100);
        GeneratedArtifact saved = artifactRepository.save(pathArtifact);
        learningPathGraphService.persistGraph(saved, graphJson);
        learnerStateStore.bumpPathVersion(event.userId(), reason);
    }

    private String formatPathPlan(LearningPathRePlanService.LearningPathPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("**动态学习路径（Learning OS 策略触发）**\n\n");
        sb.append(plan.overallRationale()).append("\n\n");
        for (var step : plan.steps()) {
            sb.append(step.order()).append(". ").append(step.concept())
                    .append("（").append(step.recommendedResourceType()).append("，预计 ")
                    .append(step.estimatedMinutes()).append(" 分钟）\n");
        }
        return sb.toString();
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
        }
        return List.of();
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0D : Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return 0D;
        }
    }
}
