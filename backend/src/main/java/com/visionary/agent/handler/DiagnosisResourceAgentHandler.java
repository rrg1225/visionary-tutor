package com.visionary.agent.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.visionary.agent.AgentJsonParser;
import com.visionary.agent.AgentResponse;
import com.visionary.agent.AgentService;
import com.visionary.agent.AgentTaskType;
import com.visionary.agent.AgentType;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.dto.AgentInvokeRequest;
import com.visionary.agent.RouterGateway;
import com.visionary.rag.GroundingAuditService;
import com.visionary.rag.RagRetrievalResult;
import com.visionary.rag.RagRetrievalService;
import com.visionary.dto.ResourceGenerationRequest;
import com.visionary.dto.ResourceGenerationResponse;
import com.visionary.resourcegeneration.application.ResourceGenerationUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.UUID;

/**
 * 知识诊断与资源生成 Agent Handler。
 * 处理 KNOWLEDGE_DIAGNOSIS 和 RESOURCE_GENERATION 任务。
 * <p>集成向量数据库检索（RAG），实现应用层、算法层、数学层的分层知识增强。</p>
 */
@Slf4j
@AgentType({AgentTaskType.KNOWLEDGE_DIAGNOSIS, AgentTaskType.RESOURCE_GENERATION})
@RequiredArgsConstructor
public class DiagnosisResourceAgentHandler implements AgentService {

    private final DeepSeekApiClient deepSeekApiClient;
    private final RagRetrievalService ragRetrievalService;
    private final AgentJsonParser jsonParser;
    private final ObjectProvider<ResourceGenerationUseCase> resourceGenerationUseCaseProvider;
    private final GroundingAuditService groundingAuditService;

    @Override
    public AgentResponse<?> process(AgentInvokeRequest request) {
        long start = System.currentTimeMillis();

        AgentTaskType taskType = request.taskType();
        if (taskType == null) {
            taskType = AgentTaskType.KNOWLEDGE_DIAGNOSIS;
        }

        return switch (taskType) {
            case KNOWLEDGE_DIAGNOSIS -> executeDiagnosis(request, start);
            case RESOURCE_GENERATION -> executeMaterialGeneration(request, start);
            default -> AgentResponse.error(
                    taskType,
                    taskType,
                    System.currentTimeMillis() - start,
                    "DiagnosisResourceAgentHandler does not support task type: " + taskType
            );
        };
    }

    private AgentResponse<DiagnosisResult> executeDiagnosis(AgentInvokeRequest request, long start) {
        String question = normalizeQuestion(request.learnerQuestion() != null ? request.learnerQuestion() : request.payloadText());
        RagRetrievalResult rag = resolveRag(request, AgentTaskType.KNOWLEDGE_DIAGNOSIS, question);
        String ragTrace = rag.groundedContextBlock();

        if (deepSeekApiClient.isConfigured()) {
            try {
                String raw = deepSeekApiClient.chat(
                        diagnosisSystemPrompt(),
                        diagnosisUserPrompt(request, question, request.studentProfileSnapshot(), rag),
                        true
                );
                DiagnosisResult result = parseDiagnosis(raw, ragTrace);
                return AgentResponse.success(
                        AgentTaskType.KNOWLEDGE_DIAGNOSIS,
                        AgentTaskType.KNOWLEDGE_DIAGNOSIS,
                        deepSeekApiClient.providerName(),
                        System.currentTimeMillis() - start,
                        result
                );
            } catch (Exception ex) {
                log.warn("DeepSeek diagnosis failed, using fallback: {}", ex.getMessage());
                DiagnosisResult fallbackResult = buildFallbackDiagnosis(question, ragTrace);
                return AgentResponse.fallback(
                        AgentTaskType.KNOWLEDGE_DIAGNOSIS,
                        AgentTaskType.KNOWLEDGE_DIAGNOSIS,
                        deepSeekApiClient.providerName(),
                        System.currentTimeMillis() - start,
                        "DeepSeek diagnosis unavailable: " + ex.getMessage(),
                        fallbackResult
                );
            }
        }

        DiagnosisResult fallbackResult = buildFallbackDiagnosis(question, ragTrace);
        return AgentResponse.fallback(
                AgentTaskType.KNOWLEDGE_DIAGNOSIS,
                AgentTaskType.KNOWLEDGE_DIAGNOSIS,
                "local-heuristic",
                System.currentTimeMillis() - start,
                "DeepSeek API key not configured",
                fallbackResult
        );
    }

    private AgentResponse<MaterialResult> executeMaterialGeneration(AgentInvokeRequest request, long start) {
        Long learningSessionId = request.learningSessionId();
        if (learningSessionId != null) {
            return executeSupervisedResourceGeneration(request, learningSessionId, start);
        }

        String diagnosisId = request.diagnosisId();
        String query = firstNonBlank(request.ragQuery(), request.payloadText(), request.learnerQuestion());
        RagRetrievalResult rag = resolveRag(request, AgentTaskType.RESOURCE_GENERATION, query);
        String context = rag.groundedContextBlock();

        if (deepSeekApiClient.isConfigured()) {
            try {
                String material = deepSeekApiClient.chat(
                        materialSystemPrompt(),
                        materialUserPrompt(request, diagnosisId, rag),
                        false
                );
                groundingAuditService.audit(material, rag).ifPresent(audit -> {
                    if (audit.needsReview()) {
                        log.warn("[InvokeGrounding] needsReview status={} faithfulness={}",
                                audit.status(), audit.faithfulnessScore());
                    }
                });
                MaterialResult result = new MaterialResult(
                        diagnosisId,
                        material,
                        context,
                        ragRetrievalService.isAvailable(),
                        null,
                        1,
                        "single_shot_llm"
                );
                return AgentResponse.success(
                        AgentTaskType.RESOURCE_GENERATION,
                        AgentTaskType.RESOURCE_GENERATION,
                        deepSeekApiClient.providerName(),
                        System.currentTimeMillis() - start,
                        result
                );
            } catch (Exception ex) {
                log.warn("DeepSeek material generation failed, using fallback: {}", ex.getMessage());
                MaterialResult fallbackResult = buildFallbackMaterial(diagnosisId, context);
                return AgentResponse.fallback(
                        AgentTaskType.RESOURCE_GENERATION,
                        AgentTaskType.RESOURCE_GENERATION,
                        deepSeekApiClient.providerName(),
                        System.currentTimeMillis() - start,
                        "DeepSeek generation unavailable: " + ex.getMessage(),
                        fallbackResult
                );
            }
        }

        MaterialResult fallbackResult = buildFallbackMaterial(diagnosisId, context);
        return AgentResponse.fallback(
                AgentTaskType.RESOURCE_GENERATION,
                AgentTaskType.RESOURCE_GENERATION,
                "local-heuristic",
                System.currentTimeMillis() - start,
                "DeepSeek API key not configured",
                fallbackResult
        );
    }

    private AgentResponse<MaterialResult> executeSupervisedResourceGeneration(
            AgentInvokeRequest request,
            Long learningSessionId,
            long start
    ) {
        String topic = firstNonBlank(request.ragQuery(), request.payloadText(), request.learnerQuestion(), "课程个性化学习");
        ResourceGenerationRequest generationRequest = new ResourceGenerationRequest(
                learningSessionId,
                topic,
                request.studentProfileSnapshot(),
                request.weakPointsSnapshot(),
                request.emotionSnapshot(),
                null
        );
        ResourceGenerationResponse response = resourceGenerationUseCaseProvider.getObject().generate(generationRequest);
        int artifactCount = response.artifacts() != null ? response.artifacts().size() : 0;
        String markdown = response.reviewSummary() != null && !response.reviewSummary().isBlank()
                ? response.reviewSummary()
                : "Supervisor 多 Agent 协同已生成 %d 类资源（runId=%s）".formatted(artifactCount, response.runId());
        MaterialResult result = new MaterialResult(
                request.diagnosisId(),
                markdown,
                "",
                true,
                response.runId(),
                artifactCount,
                "supervisor_pipeline"
        );
        return AgentResponse.success(
                AgentTaskType.RESOURCE_GENERATION,
                AgentTaskType.RESOURCE_GENERATION,
                "SupervisorAgent",
                System.currentTimeMillis() - start,
                result
        );
    }

    public List<String> streamGeneratedMaterial(String prompt) {
        RagRetrievalResult rag = ragRetrievalService.retrieveForTask(AgentTaskType.RESOURCE_GENERATION, prompt);
        return streamGeneratedMaterial(prompt, rag);
    }

    /**
     * 带 RAG 上下文的流式材料生成。
     */
    public List<String> streamGeneratedMaterial(String prompt, RagRetrievalResult rag) {
        String enhancedPrompt = buildRagEnhancedPrompt(normalizeQuestion(prompt), rag);
        // Note: streaming now callback-based in DeepSeekApiClient; returning empty list as placeholder for batch use
        return List.of();
    }

    // ==================== Diagnosis Logic ====================

    private DiagnosisResult parseDiagnosis(String raw, String ragContext) {
        JsonNode node = jsonParser.parseLenient(raw);
        return new DiagnosisResult(
                jsonParser.text(node, "diagnosisId", UUID.randomUUID().toString()),
                jsonParser.text(node, "weakKnowledgeNode", "Unknown concept"),
                jsonParser.text(node, "reasoningTrace", raw),
                ragContext
        );
    }

    private DiagnosisResult buildFallbackDiagnosis(String question, String ragContext) {
        String weakNode = "Matrix Chain Rule";
        if (question.contains("convolution") || question.contains("kernel")) {
            weakNode = "Convolution Kernels";
        } else if (question.contains("backprop") || question.contains("gradient")) {
            weakNode = "Backpropagation Graph";
        }

        return new DiagnosisResult(
                UUID.randomUUID().toString(),
                weakNode,
                "Heuristic diagnosis based on keyword routing and RAG hints.",
                ragContext
        );
    }

    private String diagnosisSystemPrompt() {
        return """
                You are a knowledge diagnosis tutor for computer vision.
                Follow Plan-and-Solve: output <thought> first, then JSON.
                Return strict JSON with keys: diagnosisId, weakKnowledgeNode, reasoningTrace, citations.
                citations must only reference grounded retrieval [cite: 74]; use [] if no evidence.
                """;
    }

    private String diagnosisUserPrompt(
            AgentInvokeRequest request,
            String question,
            String profileSnapshot,
            RagRetrievalResult rag
    ) {
        if (isGatewayEnriched(request)) {
            return """
                    Learner question: %s
                    Student profile snapshot: %s

                    %s
                    """.formatted(question, blankToDefault(profileSnapshot, "N/A"), request.contextPrompt());
        }
        return """
                Learner question: %s
                Student profile snapshot: %s
                RAG application context: %s
                RAG algorithm context: %s
                RAG math context: %s

                %s
                """.formatted(
                question,
                blankToDefault(profileSnapshot, "N/A"),
                rag.applicationContext(),
                rag.algorithmContext(),
                rag.mathContext(),
                rag.toCitationInstructionBlock()
        );
    }

    // ==================== Material Generation Logic ====================

    private MaterialResult buildFallbackMaterial(String diagnosisId, String context) {
        String id = diagnosisId == null || diagnosisId.isBlank() ? UUID.randomUUID().toString() : diagnosisId;
        String material = """
                # Personalized Handout

                ## Retrieved Knowledge Context
                %s

                ## Next Step
                Upload your draft solution for visual assessment feedback.
                """.formatted(context);
        return new MaterialResult(id, material, context, ragRetrievalService.isAvailable(), null, 1, "fallback");
    }

    private String materialSystemPrompt() {
        return """
                You are an adaptive tutoring content generator.
                Follow Plan-and-Solve: write <thought> planning which citations to use, then Markdown handout.
                End with JSON containing citations array — only grounded sources [cite: 74].
                """;
    }

    private String materialUserPrompt(AgentInvokeRequest request, String diagnosisId, RagRetrievalResult rag) {
        if (isGatewayEnriched(request)) {
            return """
                    Diagnosis ID: %s

                    %s
                    """.formatted(blankToDefault(diagnosisId, "N/A"), request.contextPrompt());
        }
        return """
                Diagnosis ID: %s
                Application context: %s
                Algorithm context: %s
                Math context: %s

                %s
                """.formatted(
                blankToDefault(diagnosisId, "N/A"),
                rag.applicationContext(),
                rag.algorithmContext(),
                rag.mathContext(),
                rag.toCitationInstructionBlock()
        );
    }

    private String buildRagEnhancedPrompt(String userPrompt, RagRetrievalResult rag) {
        return """
                User request: %s

                %s

                %s

                --- Instructions ---
                1. Output <thought> with citation plan
                2. Output Markdown handout grounded in context only
                3. Output JSON with citations field [cite: 74]
                """.formatted(userPrompt, rag.toCitationInstructionBlock(), rag.groundedContextBlock());
    }

    // ==================== Helper Methods ====================

    private RagRetrievalResult resolveRag(AgentInvokeRequest request, AgentTaskType taskType, String query) {
        if (isGatewayEnriched(request)) {
            return ragRetrievalService.retrieveForTask(taskType, query);
        }
        if (query == null || query.isBlank() || !ragRetrievalService.isAvailable()) {
            return RagRetrievalResult.empty();
        }
        return ragRetrievalService.retrieveForTask(taskType, query);
    }

    private static boolean isGatewayEnriched(AgentInvokeRequest request) {
        return request.contextPrompt() != null
                && request.contextPrompt().contains(RouterGateway.RAG_ENRICHED_MARKER);
    }

    private String normalizeQuestion(String learnerQuestion) {
        if (learnerQuestion == null || learnerQuestion.isBlank()) {
            return "Explain convolution and backpropagation gaps in my current study path.";
        }
        return learnerQuestion.trim();
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    // ==================== Records ====================

    public record DiagnosisResult(
            String diagnosisId,
            String weakKnowledgeNode,
            String reasoningTrace,
            String ragContext
    ) {
    }

    public record MaterialResult(
            String diagnosisId,
            String materialMarkdown,
            String ragContext,
            boolean ragVectorSearchUsed,
            String runId,
            int artifactCount,
            String orchestrationMode
    ) {
    }
}
