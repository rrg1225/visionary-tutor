package com.visionary.agent.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.agent.audit.AgentTraceDto.AgentStepDto;
import com.visionary.agent.audit.AgentTraceDto.FallbackEventDto;
import com.visionary.agent.eval.AgentQualityEvaluationService;
import com.visionary.entity.AgentRunStep;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.repository.AgentRunStepRepository;
import com.visionary.repository.GeneratedArtifactRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AgentAuditService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> TOOL_CALL_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final AgentRunStepRepository stepRepository;
    private final GeneratedArtifactRepository artifactRepository;
    private final AgentQualityEvaluationService qualityEvaluationService;
    private final ObjectMapper objectMapper;

    public AgentTraceDto traceByRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return emptyTrace(null, null, "runId is required");
        }
        List<AgentRunStep> steps = stepRepository.findByRunIdOrderByStepOrderAsc(runId.trim());
        if (steps.isEmpty()) {
            return emptyTrace(runId.trim(), null, "No agent steps found for runId=" + runId);
        }
        Long sessionId = steps.get(0).getLearningSessionId();
        List<GeneratedArtifact> artifacts = artifactRepository.findByRunIdOrderByIdAsc(runId.trim());
        return buildTrace(runId.trim(), sessionId, steps, artifacts);
    }

    public AgentTraceDto latestTraceBySessionId(Long learningSessionId) {
        if (learningSessionId == null) {
            return emptyTrace(null, null, "learningSessionId is required");
        }
        List<AgentRunStep> recent = stepRepository.findByLearningSessionIdOrderByGmtCreatedDesc(learningSessionId);
        Optional<String> runId = recent.stream()
                .map(AgentRunStep::getRunId)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
        return runId.map(this::traceByRunId)
                .orElseGet(() -> emptyTrace(null, learningSessionId, "No agent trace found for session"));
    }

    private AgentTraceDto buildTrace(
            String runId,
            Long learningSessionId,
            List<AgentRunStep> steps,
            List<GeneratedArtifact> artifacts
    ) {
        List<AgentStepDto> stepDtos = steps.stream().map(this::toStepDto).toList();
        List<FallbackEventDto> fallbackEvents = stepDtos.stream()
                .filter(step -> step.fallbackReason() != null && !step.fallbackReason().isBlank())
                .map(step -> new FallbackEventDto(step.agentName(), step.stepOrder(), step.fallbackReason()))
                .toList();
        String summary = "Agent collaboration trace contains "
                + stepDtos.size()
                + " steps, "
                + artifacts.size()
                + " artifacts, "
                + fallbackEvents.size()
                + " fallback events.";
        LocalDateTime generatedAt = steps.stream()
                .map(AgentRunStep::getGmtCreated)
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
        return new AgentTraceDto(
                runId,
                learningSessionId,
                generatedAt,
                qualityEvaluationService.score(steps, artifacts),
                stepDtos,
                fallbackEvents,
                summary
        );
    }

    private AgentStepDto toStepDto(AgentRunStep step) {
        JsonNode audit = readAudit(step.getAuditTraceJson());
        return new AgentStepDto(
                step.getId(),
                step.getAgentName(),
                step.getStepOrder(),
                step.getStatus(),
                step.getInputSummary(),
                step.getOutputSummary(),
                step.getCritique(),
                step.getAuditTraceJson(),
                mapNode(audit.path("inputSchema")),
                mapNode(audit.path("outputSchema")),
                toolCalls(audit.path("toolCalls")),
                stringList(audit.path("ragEvidence")),
                audit.path("revisionDiff").asText(""),
                resolveFallbackReason(step, audit),
                step.getGmtCreated()
        );
    }

    private JsonNode readAudit(String auditJson) {
        if (auditJson == null || auditJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(auditJson);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private Map<String, Object> mapNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, MAP_TYPE);
    }

    private List<Map<String, Object>> toolCalls(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(node, TOOL_CALL_TYPE);
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(node, STRING_LIST_TYPE);
    }

    private String resolveFallbackReason(AgentRunStep step, JsonNode audit) {
        String explicit = audit.path("fallbackReason").asText("");
        if (!explicit.isBlank()) {
            return explicit;
        }
        JsonNode fallbackSignal = audit.path("qualitySignals").path("fallback");
        String combined = String.join(" ",
                blank(step.getInputSummary()),
                blank(step.getOutputSummary()),
                blank(step.getCritique()));
        String lower = combined.toLowerCase(Locale.ROOT);
        if (fallbackSignal.asBoolean(false) || lower.contains("fallback") || lower.contains("degraded")) {
            return combined.length() > 320 ? combined.substring(0, 320) + "..." : combined;
        }
        return "";
    }

    private AgentTraceDto emptyTrace(String runId, Long learningSessionId, String summary) {
        return new AgentTraceDto(
                runId,
                learningSessionId,
                null,
                new AgentQualityEvaluationService.QualityScore(0D, 0D, 0D, 0D, 0D),
                List.of(),
                List.of(),
                summary
        );
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
