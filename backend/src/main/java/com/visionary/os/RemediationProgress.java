package com.visionary.os;

public record RemediationProgress(
        String runId,
        String status,
        String phase,
        String agentName,
        String message,
        int percent,
        long updatedAtEpochMs
) {
    public static RemediationProgress queued(String runId) {
        return new RemediationProgress(runId, "QUEUED", "queue", "LearningOS", "任务已加入补救资源队列", 5,
                System.currentTimeMillis());
    }

    public static RemediationProgress running(String runId, String agentName, String message, int percent) {
        return new RemediationProgress(runId, "RUNNING", "agent", agentName, message, percent,
                System.currentTimeMillis());
    }

    public static RemediationProgress complete(String runId, int generated) {
        return new RemediationProgress(runId, "COMPLETE", "done", "LearningOS",
                "已生成 " + generated + " 个专项资源", 100, System.currentTimeMillis());
    }

    public static RemediationProgress failed(String runId, String message) {
        return new RemediationProgress(runId, "FAILED", "error", "LearningOS", message, 0,
                System.currentTimeMillis());
    }

    public boolean terminal() {
        return "COMPLETE".equals(status) || "FAILED".equals(status);
    }
}
