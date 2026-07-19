package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.dto.GroundingMetrics;
import com.visionary.dto.ResourceCard;
import com.visionary.entity.GeneratedArtifact;
import com.visionary.rag.GroundingEvaluationEngine;
import com.visionary.rag.RagCitation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps persisted artifacts to {@link ResourceCard}, attaching side-path grounding metrics.
 */
@Component
@RequiredArgsConstructor
public class ResourceCardMapper {

    private final GroundingEvaluationEngine groundingEvaluationEngine;
    private final ObjectMapper objectMapper;

    public ResourceCard toCard(GeneratedArtifact artifact) {
        if (artifact == null) {
            return null;
        }
        List<String> chunks = extractChunks(artifact);
        GroundingMetrics metrics = resolveGroundingMetrics(artifact, chunks);

        return ResourceCard.builder()
                .id(artifact.getId())
                .learningSessionId(artifact.getLearningSessionId())
                .runId(artifact.getRunId())
                .artifactType(artifact.getArtifactType() != null ? artifact.getArtifactType().name() : null)
                .title(artifact.getTitle())
                .contentMarkdown(artifact.getContentMarkdown())
                .contentJson(artifact.getContentJson())
                .citationsJson(artifact.getCitationsJson())
                .validationStatus(artifact.getValidationStatus())
                .publishStatus(artifact.getPublishStatus())
                .verificationAuditJson(artifact.getVerificationAuditJson())
                .reviewNotes(artifact.getReviewNotes())
                .progress(artifact.getProgress())
                .mediaTaskId(artifact.getMediaTaskId())
                .mediaStatus(artifact.getMediaStatus())
                .mediaUrl(artifact.getMediaUrl())
                .coverImageUrl(artifact.getCoverImageUrl())
                .mediaError(artifact.getMediaError())
                .groundingMetrics(metrics)
                .build();
    }

    public List<ResourceCard> toCards(List<GeneratedArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return List.of();
        }
        return artifacts.stream().map(this::toCard).toList();
    }

    private GroundingMetrics resolveGroundingMetrics(GeneratedArtifact artifact, List<String> chunks) {
        GroundingMetrics persisted = readPersistedMetrics(artifact.getVerificationAuditJson());
        if (persisted != null) {
            return persisted;
        }
        return groundingEvaluationEngine.evaluate(artifact.getContentMarkdown(), chunks);
    }

    private GroundingMetrics readPersistedMetrics(String verificationAuditJson) {
        if (verificationAuditJson == null || verificationAuditJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(verificationAuditJson);
            JsonNode node = root.path("groundingMetrics");
            if (node.isMissingNode() || node.isNull()) {
                return null;
            }
            return objectMapper.treeToValue(node, GroundingMetrics.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> extractChunks(GeneratedArtifact artifact) {
        if (artifact.getCitationsJson() == null || artifact.getCitationsJson().isBlank()) {
            return List.of();
        }
        try {
            RagCitation[] citations = objectMapper.readValue(artifact.getCitationsJson(), RagCitation[].class);
            List<String> chunks = new ArrayList<>();
            for (RagCitation citation : citations) {
                if (citation.excerpt() != null && !citation.excerpt().isBlank()) {
                    chunks.add(citation.excerpt());
                }
            }
            return chunks;
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
