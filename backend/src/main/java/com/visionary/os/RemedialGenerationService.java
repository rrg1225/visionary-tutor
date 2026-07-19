package com.visionary.os;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.agent.CodingAgent;
import com.visionary.agent.DocAgent;
import com.visionary.agent.QuizAgent;
import com.visionary.agent.core.AgentContext;
import com.visionary.agent.core.AgentResult;
import com.visionary.agent.core.AgentTask;
import com.visionary.agent.core.MessageBus;
import com.visionary.agent.core.SharedBlackboard;
import com.visionary.agent.core.Tool;
import com.visionary.agent.impl.InMemoryMessageBus;
import com.visionary.agent.tool.ArtifactPersistTool;
import com.visionary.agent.tool.ProfileMergeTool;
import com.visionary.agent.tool.RagRetrieveTool;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.rag.CitationValidator;
import com.visionary.rag.RagRetrievalResult;
import com.visionary.rag.RagRetrievalService;
import com.visionary.repository.GeneratedArtifactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Targeted multi-agent generation for quiz-triggered remediation.
 * Replaces static AUTO_PUSH templates with DocAgent / QuizAgent / CodingAgent collaboration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemedialGenerationService {

    private final DocAgent docAgent;
    private final QuizAgent quizAgent;
    private final CodingAgent codingAgent;
    private final RagRetrievalService ragRetrievalService;
    private final CitationValidator citationValidator;
    private final PublishGate publishGate;
    private final GeneratedArtifactRepository artifactRepository;
    private final RagRetrieveTool ragRetrieveTool;
    private final ArtifactPersistTool artifactPersistTool;
    private final ProfileMergeTool profileMergeTool;
    private final ObjectMapper objectMapper;
    private final RemediationProgressStore progressStore;

    @Transactional
    public int generateRemedialPack(
            String runId,
            Long learningSessionId,
            Long userId,
            String profileSnapshot,
            double accuracy,
            List<String> weakPoints,
            List<String> errorPatterns,
            String quizFeedback
    ) {
        String topic = weakPoints == null || weakPoints.isEmpty()
                ? "薄弱点专项补救"
                : String.join("、", weakPoints.stream().limit(3).toList());

        SharedBlackboard blackboard = new SharedBlackboard();
        blackboard.setRunId(runId);
        blackboard.setCurrentTopic(topic);
        blackboard.updateProfileSnapshot(profileSnapshot != null ? profileSnapshot : "{}");
        blackboard.put("weakPointsSnapshot", topic);
        blackboard.put("remediationContext", buildRemediationContext(accuracy, weakPoints, errorPatterns, quizFeedback));

        Map<String, Tool> tools = Map.of(
                "RAGRetrieveTool", ragRetrieveTool,
                "ArtifactPersistTool", artifactPersistTool,
                "ProfileMergeTool", profileMergeTool
        );
        MessageBus bus = new InMemoryMessageBus();
        AgentContext context = new AgentContext(blackboard, tools, bus, runId, Map.of());

        Map<String, Object> taskInput = new HashMap<>();
        taskInput.put("learningSessionId", learningSessionId);
        taskInput.put("topic", topic);
        taskInput.put("learnerProfileSnapshot", profileSnapshot);
        taskInput.put("weakPointsSnapshot", topic);
        taskInput.put("remediationMode", true);
        taskInput.put("quizAccuracy", accuracy);

        AgentTask task = new AgentTask(runId, "REMEDIATION", taskInput, List.of("DocAgent", "QuizAgent", "CodingAgent"));

        progressStore.running(runId, "LearningOS", "启动 DocAgent / QuizAgent / CodingAgent 协同", 15);

        int generated = 0;
        generated += runAgent(docAgent, "DocAgent", task, context, topic, GeneratedArtifact.ArtifactType.HANDOUT, 35) ? 1 : 0;
        generated += runAgent(quizAgent, "QuizAgent", task, context, topic, GeneratedArtifact.ArtifactType.QUIZ, 60) ? 1 : 0;
        generated += runAgent(codingAgent, "CodingAgent", task, context, topic, GeneratedArtifact.ArtifactType.CODE_PRACTICE, 80) ? 1 : 0;

        progressStore.running(runId, "PublishGate", "正在验证资源忠实度并发布", 90);
        applyPublishGate(runId, topic);
        progressStore.complete(runId, generated);
        log.info("[RemedialGeneration] runId={} generated={} via multi-agent pipeline", runId, generated);
        return generated;
    }

    private boolean runAgent(
            com.visionary.agent.core.Agent agent,
            String agentLabel,
            AgentTask task,
            AgentContext context,
            String topic,
            GeneratedArtifact.ArtifactType expectedType,
            int progressPercent
    ) {
        progressStore.running(context.runId(), agentLabel, "正在生成 " + expectedType.name(), progressPercent);
        try {
            AgentResult result = agent.execute(task, context);
            if (!result.success()) {
                log.warn("[RemedialGeneration] {} failed: {}", agent.getRole(), result.output());
                return false;
            }
            return artifactRepository.findByRunIdOrderByIdAsc(context.runId()).stream()
                    .anyMatch(a -> a.getArtifactType() == expectedType);
        } catch (Exception e) {
            log.warn("[RemedialGeneration] {} error: {}", agent.getRole(), e.getMessage());
            return false;
        }
    }

    private void applyPublishGate(String runId, String topic) {
        List<GeneratedArtifact> batch = artifactRepository.findByRunIdOrderByIdAsc(runId);
        for (GeneratedArtifact artifact : batch) {
            String content = artifact.getContentMarkdown() != null ? artifact.getContentMarkdown() : "";
            RagRetrievalResult rag = ragRetrievalService.retrieveForTask(
                    com.visionary.agent.AgentTaskType.RESOURCE_GENERATION,
                    topic + " " + artifact.getArtifactType().name()
            );
            CitationValidator.ValidationResult validation = citationValidator.validate(content, rag);
            PublishGate.PublishDecision decision = publishGate.evaluate(content, rag, validation);

            artifact.setValidationStatus(validation.status());
            artifact.setPublishStatus(decision.publishStatus().name());
            artifact.setVerificationAuditJson(decision.auditJson());
            artifact.setCitationsJson(writeJson(rag.citations()));
            artifact.setReviewNotes("RemedialGeneration | " + decision.validationMessage());
            try {
                artifact.setContentJson(objectMapper.writeValueAsString(Map.of(
                        "generation_mode", "AGENT_REMEDIATION",
                        "source", "quiz_submission",
                        "publishStatus", decision.publishStatus().name()
                )));
            } catch (Exception ignored) {
                // optional metadata
            }
            artifactRepository.save(artifact);
        }
    }

    private String buildRemediationContext(
            double accuracy,
            List<String> weakPoints,
            List<String> errorPatterns,
            String quizFeedback
    ) {
        return """
                测验准确率: %.0f%%
                薄弱点: %s
                易错模式: %s
                学生反馈: %s
                """.formatted(
                accuracy * 100,
                weakPoints == null || weakPoints.isEmpty() ? "待确认" : String.join("、", weakPoints),
                errorPatterns == null || errorPatterns.isEmpty() ? "待确认" : String.join("、", errorPatterns),
                quizFeedback == null || quizFeedback.isBlank() ? "无" : quizFeedback
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
