package com.visionary.agent.audit;

import com.visionary.agent.eval.AgentQualityEvaluationService.QualityScore;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record AgentTraceDto(
        String runId,
        Long learningSessionId,
        LocalDateTime generatedAt,
        QualityScore qualityScore,
        List<AgentStepDto> steps,
        List<FallbackEventDto> fallbackEvents,
        String summary
) {

    public record AgentStepDto(
            Long id,
            String agentName,
            Integer stepOrder,
            String status,
            String inputSummary,
            String outputSummary,
            String critique,
            String auditTraceJson,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            List<Map<String, Object>> toolCalls,
            List<String> ragEvidence,
            String revisionDiff,
            String fallbackReason,
            LocalDateTime createdAt
    ) {
    }

    public record FallbackEventDto(
            String agentName,
            Integer stepOrder,
            String reason
    ) {
    }
}
