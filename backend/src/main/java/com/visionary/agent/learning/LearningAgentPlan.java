package com.visionary.agent.learning;

import com.visionary.rag.KnowledgeLayer;

import java.util.List;

/**
 * Auditable plan for one learner-facing tutoring turn.
 * Internal model reasoning is never exposed; only the bounded action summary is sent to the UI.
 */
public record LearningAgentPlan(
        Intent intent,
        String learnerGoal,
        List<KnowledgeLayer> ragLayers,
        List<String> tools,
        List<VisibleStep> visibleSteps,
        boolean requiresGrounding,
        String responseStrategy
) {
    public enum Intent {
        TUTORING,
        CODE_TUTORING,
        ASSESSMENT_SUPPORT,
        READING_GUIDANCE,
        LEARNING_PLANNING,
        PLATFORM_GUIDANCE
    }

    public record VisibleStep(String id, String label, String status) {
    }
}
