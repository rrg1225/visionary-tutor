package com.visionary.rag;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 教学知识分层（与 Python document_processor TEACHING_LAYERS 对齐）。
 */
public enum KnowledgeLayer {

    COURSE("course_layer", "application_layer"),
    CONCEPT("concept_layer", "application_layer"),
    EXERCISE("exercise_layer", "application_layer"),
    ASSESSMENT("assessment_layer", "application_layer"),
    ALGORITHM("algorithm_layer", "algorithm_layer"),
    CODE("code_layer", "algorithm_layer"),
    MATH("math_layer", "math_layer"),
    UGC("ugc_layer", "application_layer");

    private final String metadataValue;
    private final String chromaBucket;

    KnowledgeLayer(String metadataValue, String chromaBucket) {
        this.metadataValue = metadataValue;
        this.chromaBucket = chromaBucket;
    }

    public String metadataValue() {
        return metadataValue;
    }

    public String chromaBucket() {
        return chromaBucket;
    }

    public static KnowledgeLayer fromMetadata(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase();
        for (KnowledgeLayer layer : values()) {
            if (layer.metadataValue.equals(normalized) || layer.chromaBucket.equals(normalized)) {
                return layer;
            }
        }
        return null;
    }

    public static Set<String> metadataValuesForLayers(List<KnowledgeLayer> layers) {
        return layers.stream()
                .map(KnowledgeLayer::metadataValue)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static List<KnowledgeLayer> applicationLayers() {
        return List.of(COURSE, CONCEPT, EXERCISE, ASSESSMENT);
    }

    public static List<KnowledgeLayer> algorithmLayers() {
        return List.of(ALGORITHM, CODE);
    }

    public static List<KnowledgeLayer> mathLayers() {
        return List.of(MATH);
    }

    public static List<KnowledgeLayer> allLayers() {
        return Arrays.asList(values());
    }

    public static List<KnowledgeLayer> ugcLayers() {
        return List.of(UGC);
    }
}
