package com.visionary.os;

public enum PublishStatus {
    PUBLISHED,
    DEGRADED,
    BLOCKED;

    public static PublishStatus fromValidation(String validationStatus) {
        if (validationStatus == null || validationStatus.isBlank()) {
            return DEGRADED;
        }
        return switch (validationStatus) {
            case "GROUNDED", "NO_EVIDENCE", "RAG_UNUSED" -> PUBLISHED;
            case "INVALID_CITATION" -> BLOCKED;
            default -> DEGRADED;
        };
    }
}
