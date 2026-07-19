package com.visionary.agent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.AgentRunStep;
import com.visionary.entity.GeneratedArtifact;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Scores only persisted, inspectable evidence. The audit page must not award a
 * high score merely because free-form summaries contain favorable keywords.
 */
@Service
@RequiredArgsConstructor
public class AgentQualityEvaluationService {

    private static final Set<String> EXPECTED_SPECIALISTS = Set.of(
            "DocAgent",
            "QuizAgent",
            "MindMapAgent",
            "PathAgent",
            "CodingAgent",
            "ReadingAgent",
            "VisualizationAgent"
    );
    private static final Set<String> GROUNDED_VALIDATION_STATUSES = Set.of("GROUNDED", "VERIFIED");
    private static final Set<String> REJECTED_VALIDATION_STATUSES = Set.of(
            "INVALID_CITATION",
            "NO_CITATION_USED",
            "NEEDS_HUMAN_REVIEW"
    );

    private final ObjectMapper objectMapper;

    public QualityScore score(List<AgentRunStep> steps, List<GeneratedArtifact> artifacts) {
        List<AgentRunStep> safeSteps = safeSteps(steps);
        List<GeneratedArtifact> safeArtifacts = safeArtifacts(artifacts);
        double coverage = coverageScore(safeSteps, safeArtifacts);
        double citationRate = citationRate(safeArtifacts);
        double personalization = personalizationMatchScore(safeSteps, safeArtifacts);
        double executability = executabilityScore(safeSteps, safeArtifacts);
        double overall = round((coverage * 0.25D)
                + (citationRate * 0.30D)
                + (personalization * 0.20D)
                + (executability * 0.25D));
        return new QualityScore(
                round(coverage),
                round(citationRate),
                round(personalization),
                round(executability),
                overall
        );
    }

    private double coverageScore(List<AgentRunStep> steps, List<GeneratedArtifact> artifacts) {
        Set<String> covered = new HashSet<>();
        for (AgentRunStep step : steps) {
            addSpecialistSignal(covered, step.getAgentName());
            JsonNode audit = readJson(step.getAuditTraceJson());
            addSpecialistSignal(covered, audit.path("action").asText(""));
            for (JsonNode call : audit.path("toolCalls")) {
                addSpecialistSignal(covered, call.path("name").asText(""));
            }
        }
        for (GeneratedArtifact artifact : artifacts) {
            if (artifact.getArtifactType() != null) {
                covered.add(specialistForType(artifact.getArtifactType()));
            }
        }
        return Math.min(1D, covered.size() / (double) EXPECTED_SPECIALISTS.size());
    }

    private void addSpecialistSignal(Set<String> covered, String signal) {
        if (signal == null || signal.isBlank()) {
            return;
        }
        EXPECTED_SPECIALISTS.stream()
                .filter(signal::contains)
                .forEach(covered::add);

        String normalized = signal.toLowerCase(Locale.ROOT);
        if (normalized.contains("lecture_handout") || normalized.contains("handout")) covered.add("DocAgent");
        if (normalized.contains("quiz")) covered.add("QuizAgent");
        if (normalized.contains("mind_map") || normalized.contains("mindmap")) covered.add("MindMapAgent");
        if (normalized.contains("learning_path")) covered.add("PathAgent");
        if (normalized.contains("coding") || normalized.contains("code_practice")) covered.add("CodingAgent");
        if (normalized.contains("reading")) covered.add("ReadingAgent");
        if (normalized.contains("video_script")) covered.add("VisualizationAgent");
        if (normalized.contains("visualization")) covered.add("VisualizationAgent");
    }

    /**
     * A citation counts as grounded only when it is parseable and the publish
     * pipeline persisted a positive validation status.
     */
    private double citationRate(List<GeneratedArtifact> artifacts) {
        if (artifacts.isEmpty()) {
            return 0D;
        }
        double grounded = artifacts.stream().mapToDouble(this::artifactGroundingScore).sum();
        return grounded / artifacts.size();
    }

    private double artifactGroundingScore(GeneratedArtifact artifact) {
        if (!hasCitations(artifact)) {
            return 0D;
        }
        String status = normalized(artifact.getValidationStatus());
        if (GROUNDED_VALIDATION_STATUSES.contains(status)) {
            return 1D;
        }
        if (REJECTED_VALIDATION_STATUSES.contains(status)) {
            return 0D;
        }
        return 0.5D;
    }

    /**
     * Structured evidence is worth the full score. A provenance flag on an
     * artifact is useful corroboration, but by itself is capped at 25%.
     */
    private double personalizationMatchScore(
            List<AgentRunStep> steps,
            List<GeneratedArtifact> artifacts
    ) {
        boolean topic = false;
        boolean profile = false;
        boolean weakPoints = false;
        boolean constrainedSelection = false;
        boolean hasStructuredEvidence = false;

        for (AgentRunStep step : steps) {
            JsonNode evidence = readJson(step.getAuditTraceJson()).path("personalizationEvidence");
            if (!evidence.isObject()) {
                continue;
            }
            hasStructuredEvidence = true;
            topic |= evidence.path("topicPresent").asBoolean(false);
            profile |= evidence.path("profilePresent").asBoolean(false);
            weakPoints |= evidence.path("weakPointsPresent").asBoolean(false);
            constrainedSelection |= evidence.path("toolSelectionConstrained").asBoolean(false);
        }

        if (hasStructuredEvidence) {
            int hits = (topic ? 1 : 0)
                    + (profile ? 1 : 0)
                    + (weakPoints ? 1 : 0)
                    + (constrainedSelection ? 1 : 0);
            return hits / 4D;
        }

        boolean artifactClaimsPersonalization = artifacts.stream().anyMatch(artifact -> {
            JsonNode metadata = readJson(artifact.getContentJson());
            return metadata.path("personalized").asBoolean(false)
                    && "LIVE".equalsIgnoreCase(metadata.path("origin").asText(""))
                    && !metadata.path("degraded").asBoolean(false);
        });
        return artifactClaimsPersonalization ? 0.25D : 0D;
    }

    private double executabilityScore(List<AgentRunStep> steps, List<GeneratedArtifact> artifacts) {
        double deliverableRatio = ratio(artifacts.stream().filter(this::isExecutableArtifact).count(), artifacts.size());
        double provenanceRatio = artifacts.isEmpty()
                ? 0D
                : artifacts.stream().mapToDouble(this::provenanceScore).average().orElse(0D);
        double stepRatio = stepSuccessRatio(steps);
        return (deliverableRatio * 0.55D) + (provenanceRatio * 0.25D) + (stepRatio * 0.20D);
    }

    private boolean isExecutableArtifact(GeneratedArtifact artifact) {
        String publishStatus = normalized(artifact.getPublishStatus());
        String validationStatus = normalized(artifact.getValidationStatus());
        return artifact.getContentMarkdown() != null
                && !artifact.getContentMarkdown().isBlank()
                && !"BLOCKED".equals(publishStatus)
                && !REJECTED_VALIDATION_STATUSES.contains(validationStatus);
    }

    private double provenanceScore(GeneratedArtifact artifact) {
        JsonNode metadata = readJson(artifact.getContentJson());
        String origin = metadata.path("origin").asText("").toUpperCase(Locale.ROOT);
        boolean degraded = metadata.path("degraded").asBoolean(false);
        if ("DEMO".equals(origin)) {
            return 0D;
        }
        if ("DEGRADED".equals(origin) || degraded) {
            return 0.4D;
        }
        if ("LIVE".equals(origin)) {
            return 1D;
        }
        return 0.5D;
    }

    private double stepSuccessRatio(List<AgentRunStep> steps) {
        if (steps.isEmpty()) {
            return 0D;
        }
        int observed = 0;
        int succeeded = 0;
        for (AgentRunStep step : steps) {
            JsonNode signals = readJson(step.getAuditTraceJson()).path("qualitySignals");
            if (signals.has("toolSucceeded")) {
                observed++;
                if (signals.path("toolSucceeded").asBoolean(false)) {
                    succeeded++;
                }
                continue;
            }
            String status = normalized(step.getStatus());
            if (status.contains("FAIL") || status.contains("ERROR")) {
                observed++;
            } else if (!"THOUGHT_ACTION".equals(status)) {
                observed++;
                succeeded++;
            }
        }
        return observed == 0 ? 0D : succeeded / (double) observed;
    }

    private boolean hasCitations(GeneratedArtifact artifact) {
        JsonNode node = readJson(artifact.getCitationsJson());
        return node.isArray() && node.size() > 0;
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private static List<AgentRunStep> safeSteps(List<AgentRunStep> steps) {
        return steps == null ? List.of() : steps;
    }

    private static List<GeneratedArtifact> safeArtifacts(List<GeneratedArtifact> artifacts) {
        return artifacts == null ? List.of() : artifacts;
    }

    private static String specialistForType(GeneratedArtifact.ArtifactType type) {
        return switch (type) {
            case HANDOUT -> "DocAgent";
            case QUIZ -> "QuizAgent";
            case MINDMAP -> "MindMapAgent";
            case LEARNING_PATH -> "PathAgent";
            case CODE_PRACTICE -> "CodingAgent";
            case EXTENDED_READING -> "ReadingAgent";
            case VIDEO_SCRIPT -> "VisualizationAgent";
            case VISUALIZATION -> "VisualizationAgent";
        };
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static double ratio(long numerator, int denominator) {
        return denominator == 0 ? 0D : numerator / (double) denominator;
    }

    private static double round(double value) {
        return Math.round(value * 1000D) / 1000D;
    }

    public record QualityScore(
            double coverage,
            double citationRate,
            double personalizationMatch,
            double executability,
            double overall
    ) {
    }
}
