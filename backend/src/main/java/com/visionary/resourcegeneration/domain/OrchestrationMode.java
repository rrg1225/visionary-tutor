package com.visionary.resourcegeneration.domain;

public enum OrchestrationMode {
    REACT,
    WORKFLOW,
    DEMO;

    public static OrchestrationMode fromConfiguration(String configuredMode) {
        if (configuredMode == null || configuredMode.isBlank()) {
            return REACT;
        }
        return switch (configuredMode.trim().toLowerCase()) {
            case "react" -> REACT;
            case "workflow", "legacy" -> WORKFLOW;
            case "demo" -> DEMO;
            default -> throw new IllegalArgumentException("Unsupported orchestration mode: " + configuredMode);
        };
    }
}
