package com.visionary.dto;

import java.util.List;

public record KnowledgeTracingRadarDto(
        List<ConceptScore> concepts,
        int meaningfulCount,
        boolean insufficientData
) {
    public record ConceptScore(String concept, double score) {}
}
