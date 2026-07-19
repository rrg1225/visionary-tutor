package com.visionary.service;

import com.visionary.client.DeepSeekApiClient;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.rag.RagRetrievalResult;
import com.visionary.rag.RagRetrievalService;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.repository.LearningSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Real TutoringAgent: instant multimodal Q&A using RAG + profile + strict evidence.
 * Returns text answer + references to existing diagrams and local animated explainers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TutoringService {

    private final RagRetrievalService ragRetrievalService;
    private final DeepSeekApiClient deepSeekApiClient;
    private final GeneratedArtifactRepository artifactRepository;
    private final LearningSessionRepository learningSessionRepository;

    public TutoringResponse answerQuestion(Long learningSessionId, String question, String learnerProfileSnapshot) {
        if (question == null || question.isBlank()) {
            return new TutoringResponse("请提供具体问题。", List.of(), false);
        }

        RagRetrievalResult rag = ragRetrievalService.retrieveForTask(
                com.visionary.agent.AgentTaskType.KNOWLEDGE_DIAGNOSIS,
                question
        );

        if (!deepSeekApiClient.isConfigured() || !rag.hasGroundedEvidence()) {
            return new TutoringResponse(
                    "知识库证据不足，本次回答为通用学习建议：" + question,
                    fetchRecentArtifacts(learningSessionId, 3),
                    false
            );
        }

        String prompt = buildStrictEvidencePrompt(question, learnerProfileSnapshot, rag);
        try {
            String answer = deepSeekApiClient.chat(
                    "你是智眸学伴 TutoringAgent。只用提供的证据回答，每句话必须有 citationId 支撑，否则标注“证据不足”。",
                    prompt,
                    false
            );
            List<GeneratedArtifact> visuals = fetchRecentArtifacts(learningSessionId, 4);
            return new TutoringResponse(answer, visuals, true);
        } catch (Exception e) {
            log.warn("Tutoring LLM failed: {}", e.getMessage());
            return new TutoringResponse("回答生成失败，请稍后重试。", fetchRecentArtifacts(learningSessionId, 2), false);
        }
    }

    private String buildStrictEvidencePrompt(String question, String profile, RagRetrievalResult rag) {
        return """
                学生问题：%s
                学生画像摘要：%s

                严格证据（只可使用以下 citationId）：
                %s

                输出要求：
                1. 每句话必须引用证据，否则省略或写“知识库证据不足，本段为通用建议”。
                2. 回答后列出用到的 citationId。
                3. 如果有导图或动画讲解资源，建议学生查看对应卡片。
                """.formatted(question, profile == null ? "暂无" : profile, rag.groundedContextBlock());
    }

    private List<GeneratedArtifact> fetchRecentArtifacts(Long sessionId, int limit) {
        if (sessionId == null) return List.of();
        return artifactRepository.findByLearningSessionIdOrderByGmtCreatedDesc(sessionId).stream()
                .filter(a -> a.getArtifactType() == GeneratedArtifact.ArtifactType.MINDMAP
                        || a.getArtifactType() == GeneratedArtifact.ArtifactType.VISUALIZATION
                        || a.getCoverImageUrl() != null)
                .limit(limit)
                .collect(Collectors.toList());
    }

    public record TutoringResponse(String answer, List<GeneratedArtifact> suggestedVisuals, boolean usedRag) {}
}
