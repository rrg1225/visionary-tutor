package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.config.LearningOsProperties;
import com.visionary.os.LearnerStateStore;
import com.visionary.os.LearningEvent;
import com.visionary.os.LearningEventBus;
import com.visionary.os.LearningEventType;
import com.visionary.os.PolicyEngine;
import com.visionary.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified replan trigger — publishes Learning OS events instead of writing template artifacts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReplanTriggerService {

    private final LearningPathRePlanService learningPathRePlanService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final LearningEventBus learningEventBus;
    private final PolicyEngine policyEngine;
    private final LearnerStateStore learnerStateStore;
    private final LearningOsProperties learningOsProperties;

    @Transactional
    public ReplanResult triggerAfterQuiz(Long userId, Long learningSessionId, double accuracy,
                                         List<String> newWeakPoints, List<String> errorPatterns,
                                         String quizFeedback) {
        String updatedProfile = mergeQuizFeedbackIntoProfile(userId, accuracy, newWeakPoints, errorPatterns);
        var state = learnerStateStore.getState(userId);
        String runId = "REPLAN-QUIZ-" + System.currentTimeMillis();

        Map<String, Object> payload = new HashMap<>();
        payload.put("accuracy", accuracy);
        payload.put("newWeakPoints", newWeakPoints != null ? newWeakPoints : List.of());
        payload.put("errorPatterns", errorPatterns != null ? errorPatterns : List.of());
        payload.put("quizFeedback", quizFeedback);
        payload.put("profileSnapshot", updatedProfile);
        payload.put("runId", runId);

        LearningEvent event = LearningEvent.of(
                LearningEventType.QUIZ_SUBMITTED,
                userId,
                learningSessionId,
                state.profileVersion(),
                payload
        );

        PolicyEngine.PolicyDecision decision = policyEngine.decide(event);
        learningEventBus.publish(event);

        if (!decision.executeActions()) {
            return new ReplanResult(false, decision.reason());
        }

        var plan = learningPathRePlanService.rePlan(learningSessionId, updatedProfile, null);
        boolean asyncQueued = decision.actions().contains(PolicyEngine.PolicyAction.GENERATE_REMEDIAL_AGENTS)
                && learningOsProperties.isAsyncRemediation();
        return new ReplanResult(true, decision.reason(), plan, runId, asyncQueued);
    }

    private String mergeQuizFeedbackIntoProfile(Long userId, double accuracy, List<String> newWeakPoints, List<String> errorPatterns) {
        return userRepository.findById(userId).map(user -> {
            String snapshot = user.getLearnerProfileSnapshot();
            if (snapshot == null || snapshot.isBlank()) snapshot = "{}";

            try {
                JsonNode root = objectMapper.readTree(snapshot);
                ObjectNode mutable = root.deepCopy();

                if (errorPatterns != null && !errorPatterns.isEmpty()) {
                    ObjectNode ep = mutable.has("errorPatterns") && mutable.get("errorPatterns").isObject()
                            ? (ObjectNode) mutable.get("errorPatterns").deepCopy()
                            : objectMapper.createObjectNode();
                    ArrayNode valueArr = objectMapper.createArrayNode();
                    errorPatterns.forEach(valueArr::add);
                    ep.set("value", valueArr);
                    mutable.set("errorPatterns", ep);
                }

                if (newWeakPoints != null && !newWeakPoints.isEmpty()) {
                    ObjectNode wp = mutable.has("weakPoints") && mutable.get("weakPoints").isObject()
                            ? (ObjectNode) mutable.get("weakPoints").deepCopy()
                            : objectMapper.createObjectNode();
                    ArrayNode valueArr = objectMapper.createArrayNode();
                    newWeakPoints.forEach(valueArr::add);
                    wp.set("value", valueArr);
                    mutable.set("weakPoints", wp);
                }

                ObjectNode quizEvidence = objectMapper.createObjectNode();
                quizEvidence.put("accuracy", accuracy);
                quizEvidence.put("timestamp", java.time.Instant.now().toString());
                mutable.set("lastQuizAccuracy", quizEvidence);

                String merged = objectMapper.writeValueAsString(mutable);
                user.setLearnerProfileSnapshot(merged);
                userRepository.save(user);
                return merged;
            } catch (Exception e) {
                log.warn("Failed to merge quiz feedback into profile: {}", e.getMessage());
                return snapshot;
            }
        }).orElse("{}");
    }

    public record ReplanResult(
            boolean triggered,
            String message,
            LearningPathRePlanService.LearningPathPlan plan,
            String runId,
            boolean asyncQueued
    ) {
        public ReplanResult(boolean triggered, String message) {
            this(triggered, message, null, null, false);
        }
    }
}
