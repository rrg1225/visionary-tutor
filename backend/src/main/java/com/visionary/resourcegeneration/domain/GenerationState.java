package com.visionary.resourcegeneration.domain;

public enum GenerationState {
    CREATED,
    PLANNING,
    RETRIEVING,
    GENERATING,
    CRITIQUING,
    REVISING,
    PERSISTING,
    SUCCEEDED,
    DEGRADED,
    MANUAL_REVIEW,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == MANUAL_REVIEW || this == FAILED;
    }
}
