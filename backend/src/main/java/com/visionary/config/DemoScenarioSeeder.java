package com.visionary.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.entity.LearningSession;
import com.visionary.entity.SessionChatMessage;
import com.visionary.entity.User;
import com.visionary.repository.AgentRunStepRepository;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.repository.LearningEventMetricRepository;
import com.visionary.repository.LearningMetricsRepository;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.repository.ResourceUsageRecordRepository;
import com.visionary.repository.SessionChatMessageRepository;
import com.visionary.repository.UserRepository;
import com.visionary.service.KnowledgeTracingService;
import com.visionary.service.LearningEffectAssessmentService;
import com.visionary.service.LearningEffectExperimentService;
import com.visionary.service.PersistenceManager;
import com.visionary.service.ResourceUsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DemoScenarioSeeder {

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final UserRepository userRepository;
    private final LearningSessionRepository sessionRepository;
    private final GeneratedArtifactRepository artifactRepository;
    private final AgentRunStepRepository stepRepository;
    private final SessionChatMessageRepository chatMessageRepository;
    private final LearningEventMetricRepository metricRepository;
    private final ResourceUsageRecordRepository usageRepository;
    private final LearningMetricsRepository learningMetricsRepository;
    private final PasswordEncoder passwordEncoder;
    private final PersistenceManager persistenceManager;
    private final LearningEffectAssessmentService effectAssessmentService;
    private final LearningEffectExperimentService effectExperimentService;
    private final KnowledgeTracingService knowledgeTracingService;
    private final ResourceUsageService resourceUsageService;

    @Value("${visionary.demo-scenario.data-resource:classpath:demo-data/demo-scenario-cnn.json}")
    private String dataResource;

    @Transactional
    public DemoScenarioResult seed() {
        JsonNode root = loadScenario();
        User student = upsertUser(root.path("student"), "demo_student");
        User admin = upsertUser(root.path("admin"), "demo_admin");
        LearningSession session = resolveSession(student, root);
        resetScenarioRows(student.getId(), session.getId());

        session.setTopic(root.path("topic").asText("CNN Padding/Stride defense demo"));
        session.setStatus(LearningSession.SessionStatus.ACTIVE);
        session.setCurrentPhase(LearningSession.LearningPhase.RESOURCE_GENERATION);
        session.setConversationSummary(root.path("conversationSummary").asText("Seeded CNN defense scenario"));
        session.setLastEmotionSnapshot(root.path("emotionSnapshot").asText("focused"));
        session = sessionRepository.save(session);

        String runId = root.path("runId").asText("demo-cnn-run");
        seedConversation(session, root.path("conversation"));
        Map<String, Long> artifactIds = seedArtifacts(session, runId, root.path("artifacts"));
        seedLearningEffects(student.getId(), session.getId(), root.path("concepts"));
        seedUsage(student.getId(), session.getId(), artifactIds, root.path("usage"));
        seedAgentTrace(runId, session.getId(), root.path("agentTrace"));

        return new DemoScenarioResult(
                student.getUsername(),
                root.path("student").path("password").asText("Demo@2026"),
                admin.getUsername(),
                root.path("admin").path("password").asText("Admin@2026"),
                session.getId(),
                runId,
                artifactIds.size(),
                Instant.now().toString()
        );
    }

    private User upsertUser(JsonNode node, String fallbackUsername) {
        String username = node.path("username").asText(fallbackUsername);
        User user = userRepository.findByUsername(username).orElseGet(User::new);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(node.path("password").asText("Demo@2026")));
        user.setEmail(node.path("email").asText(username + "@demo.local"));
        user.setDisplayName(node.path("displayName").asText(username));
        user.setLearningGoal(node.path("learningGoal").asText("CNN Padding/Stride mastery"));
        user.setLearnerProfileSnapshot(node.path("learnerProfileSnapshot").toString());
        user.setEmotionProfileSnapshot(node.path("emotionProfileSnapshot").toString());
        user.setStatus(User.UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    private LearningSession resolveSession(User student, JsonNode root) {
        String topic = root.path("topic").asText("CNN Padding/Stride defense demo");
        return sessionRepository.findByUserIdOrderByGmtCreatedDesc(student.getId()).stream()
                .filter(session -> topic.equals(session.getTopic()))
                .findFirst()
                .orElseGet(() -> {
                    LearningSession session = new LearningSession();
                    session.setUserId(student.getId());
                    session.setTopic(topic);
                    return sessionRepository.save(session);
                });
    }

    private void resetScenarioRows(Long userId, Long sessionId) {
        artifactRepository.deleteByLearningSessionId(sessionId);
        stepRepository.deleteByLearningSessionId(sessionId);
        chatMessageRepository.deleteByLearningSessionId(sessionId);
        metricRepository.deleteByLearningSessionId(sessionId);
        usageRepository.deleteByLearningSessionId(sessionId);
        learningMetricsRepository.deleteByUserId(userId);
    }

    private void seedConversation(LearningSession session, JsonNode conversation) {
        int seq = 1;
        for (JsonNode item : iterable(conversation)) {
            SessionChatMessage message = new SessionChatMessage();
            message.setLearningSessionId(session.getId());
            message.setUserId(session.getUserId());
            message.setRole(item.path("role").asText("assistant"));
            message.setContent(item.path("content").asText(""));
            message.setSeq(seq++);
            chatMessageRepository.save(message);
        }
    }

    private Map<String, Long> seedArtifacts(LearningSession session, String runId, JsonNode artifacts) {
        Map<String, Long> artifactIds = new HashMap<>();
        for (JsonNode item : iterable(artifacts)) {
            GeneratedArtifact.ArtifactType type = GeneratedArtifact.ArtifactType.valueOf(item.path("artifactType").asText());
            GeneratedArtifact artifact = new GeneratedArtifact();
            artifact.setLearningSessionId(session.getId());
            artifact.setRunId(runId);
            artifact.setArtifactType(type);
            artifact.setTitle(item.path("title").asText(type.name()));
            artifact.setContentMarkdown(item.path("contentMarkdown").asText(""));
            artifact.setContentJson(jsonOrDefault(item.path("contentJson"), Map.of(
                    "generation_mode", "DEMO_SCENARIO",
                    "agent", item.path("agent").asText("DemoScenarioSeeder")
            )));
            artifact.setCitationsJson(jsonOrDefault(item.path("citations"), List.of()));
            artifact.setValidationStatus(item.path("validationStatus").asText("GROUNDED"));
            artifact.setPublishStatus(item.path("publishStatus").asText("PUBLISHED"));
            artifact.setVerificationAuditJson(jsonOrDefault(item.path("verificationAudit"), Map.of(
                    "groundingMetrics", Map.of("faithfulness", 0.92D, "citationCoverage", 0.88D)
            )));
            artifact.setReviewNotes(item.path("reviewNotes").asText("Seeded for defense demo"));
            artifact.setProgress(100);
            GeneratedArtifact saved = persistenceManager.saveAndIndexArtifact(artifact);
            artifactIds.put(type.name(), saved.getId());
        }
        persistenceManager.markResourceGenerationPhase(session);
        return artifactIds;
    }

    private void seedLearningEffects(Long userId, Long sessionId, JsonNode concepts) {
        for (JsonNode item : iterable(concepts)) {
            String concept = item.path("concept").asText("CNN concept");
            double pre = item.path("preTest").asDouble(0.42D);
            double post = item.path("postTest").asDouble(0.78D);
            effectAssessmentService.recordMetric(
                    userId, sessionId, "PRE_TEST", concept, pre, "demo_pre_test", "demo_scenario"
            );
            effectExperimentService.recordPostTest(userId, sessionId, concept, post);
            effectAssessmentService.recordMetric(
                    userId, sessionId, "QUIZ_ACCURACY", concept, post, "demo_quiz", "demo_scenario"
            );
            knowledgeTracingService.recordPracticeAccuracy(userId, concept, post);
        }
    }

    private void seedUsage(Long userId, Long sessionId, Map<String, Long> artifactIds, JsonNode usage) {
        for (JsonNode item : iterable(usage)) {
            String type = item.path("artifactType").asText("");
            Long artifactId = artifactIds.get(type);
            resourceUsageService.recordUsage(
                    userId,
                    sessionId,
                    artifactId,
                    item.path("actionType").asText("view"),
                    item.path("durationSeconds").asInt(60),
                    item.path("feedback").asText("")
            );
        }
    }

    private void seedAgentTrace(String runId, Long sessionId, JsonNode steps) {
        int order = 1;
        for (JsonNode item : iterable(steps)) {
            persistenceManager.saveStep(
                    runId,
                    sessionId,
                    item.path("agentName").asText("DemoAgent"),
                    item.path("stepOrder").asInt(order++),
                    item.path("input").asText(""),
                    item.path("output").asText(""),
                    item.path("critique").asText("")
            );
        }
    }

    private JsonNode loadScenario() {
        try {
            org.springframework.core.io.Resource resource = resourceLoader.getResource(dataResource);
            try (InputStream input = resource.getInputStream()) {
                return objectMapper.readTree(input);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to load demo scenario: " + dataResource, ex);
        }
    }

    private String jsonOrDefault(JsonNode node, Object fallback) {
        try {
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                return objectMapper.writeValueAsString(node);
            }
            return objectMapper.writeValueAsString(fallback);
        } catch (Exception ex) {
            log.warn("Demo scenario JSON serialization failed: {}", ex.getMessage());
            return "{}";
        }
    }

    private Iterable<JsonNode> iterable(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        return () -> new Iterator<>() {
            private final Iterator<JsonNode> delegate = node.elements();

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public JsonNode next() {
                return delegate.next();
            }
        };
    }

    public record DemoScenarioResult(
            String studentUsername,
            String studentPassword,
            String adminUsername,
            String adminPassword,
            Long learningSessionId,
            String runId,
            int artifactCount,
            String seededAt
    ) {
    }
}
