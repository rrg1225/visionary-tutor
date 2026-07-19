package com.visionary.service;

import com.visionary.entity.GeneratedArtifact;
import com.visionary.rag.VectorDbService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceVectorIndexService {

    private static final Set<String> RESOURCE_LAYER = Set.of("resource");
    private static final int MAX_INDEX_TEXT_LENGTH = 6_000;

    private final VectorDbService vectorDbService;

    public void indexArtifacts(List<GeneratedArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return;
        }
        for (GeneratedArtifact artifact : artifacts) {
            indexArtifact(artifact);
        }
    }

    public void indexArtifact(GeneratedArtifact artifact) {
        if (!isAvailable() || artifact == null || artifact.getId() == null) {
            return;
        }
        String text = indexText(artifact);
        if (text.isBlank()) {
            return;
        }
        try {
            Metadata metadata = new Metadata()
                    .put("source", "artifact:" + artifact.getId())
                    .put("category", "generated_artifact/" + artifactType(artifact))
                    .put("chunk_type", "resource_artifact")
                    .put("layer", "resource")
                    .put("chroma_layer", "resource")
                    .put("artifact_id", artifact.getId().toString())
                    .put("artifact_type", artifactType(artifact))
                    .put("learning_session_id", String.valueOf(artifact.getLearningSessionId()))
                    .put("run_id", blankToDash(artifact.getRunId()))
                    .put("vector_id", vectorId(artifact));
            vectorDbService.upsert(Document.from(text, metadata));
        } catch (Exception e) {
            log.warn("Resource vector indexing skipped for artifact id={}: {}", artifact.getId(), e.getMessage());
        }
    }

    public Map<Long, Double> scoreSimilarArtifacts(
            String query,
            List<GeneratedArtifact> candidates,
            int topK
    ) {
        Map<Long, Double> scores = new HashMap<>();
        if (!isAvailable() || query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return scores;
        }

        indexArtifacts(candidates);

        Set<Long> candidateIds = new HashSet<>();
        for (GeneratedArtifact candidate : candidates) {
            if (candidate.getId() != null) {
                candidateIds.add(candidate.getId());
            }
        }

        try {
            List<VectorDbService.KnowledgeFragment> hits = vectorDbService.search(
                    query,
                    Math.max(Math.max(1, topK), candidates.size() * 2),
                    RESOURCE_LAYER
            );
            for (VectorDbService.KnowledgeFragment hit : hits) {
                Long artifactId = parseArtifactId(hit.source());
                if (artifactId == null || !candidateIds.contains(artifactId)) {
                    continue;
                }
                scores.merge(artifactId, hit.score(), Math::max);
            }
        } catch (Exception e) {
            log.warn("Resource vector search failed, falling back to rule scoring: {}", e.getMessage());
        }
        return normalize(scores);
    }

    public boolean isAvailable() {
        return vectorDbService != null && vectorDbService.isAvailable();
    }

    private Map<Long, Double> normalize(Map<Long, Double> raw) {
        if (raw.isEmpty()) {
            return raw;
        }
        double max = raw.values().stream().mapToDouble(Double::doubleValue).max().orElse(1D);
        if (max <= 0D) {
            return raw;
        }
        Map<Long, Double> normalized = new LinkedHashMap<>();
        raw.forEach((id, score) -> normalized.put(id, Math.max(0D, Math.min(1D, score / max))));
        return normalized;
    }

    private Long parseArtifactId(String source) {
        if (source == null || !source.startsWith("artifact:")) {
            return null;
        }
        try {
            return Long.parseLong(source.substring("artifact:".length()).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String indexText(GeneratedArtifact artifact) {
        String text = """
                Title: %s
                Type: %s
                Validation: %s
                Content:
                %s
                """.formatted(
                blankToDash(artifact.getTitle()),
                artifactType(artifact),
                blankToDash(artifact.getValidationStatus()),
                blankToDash(artifact.getContentMarkdown())
        ).trim();
        return text.length() <= MAX_INDEX_TEXT_LENGTH ? text : text.substring(0, MAX_INDEX_TEXT_LENGTH);
    }

    private String vectorId(GeneratedArtifact artifact) {
        return "resource-artifact-" + artifact.getId();
    }

    private String artifactType(GeneratedArtifact artifact) {
        return artifact.getArtifactType() == null ? "UNKNOWN" : artifact.getArtifactType().name();
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
