package com.visionary.client;

public record MediaTaskStatus(
        String taskId,
        String status,
        int progress,
        String mediaUrl,
        String coverImageUrl,
        String errorMessage
) {
    public boolean succeeded() {
        return "SUCCEEDED".equalsIgnoreCase(status)
                || "SUCCESS".equalsIgnoreCase(status)
                || "succeeded".equalsIgnoreCase(status)
                || "completed".equalsIgnoreCase(status);
    }

    public boolean failed() {
        return "FAILED".equalsIgnoreCase(status)
                || "FAILURE".equalsIgnoreCase(status)
                || "failed".equalsIgnoreCase(status);
    }
}
