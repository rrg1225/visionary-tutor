package com.visionary.sandbox;

public record SandboxExecutionResult(
        String status,
        String output,
        String error,
        long executionTimeMs
) {
    public static SandboxExecutionResult success(String stdout, String stderr, long elapsedMs) {
        return new SandboxExecutionResult("SUCCESS", stdout, stderr, elapsedMs);
    }

    public static SandboxExecutionResult timeout(String stdout, String stderr, long elapsedMs) {
        String message = stderr == null || stderr.isBlank()
                ? "Execution exceeded hard timeout."
                : stderr;
        return new SandboxExecutionResult("TIMEOUT", stdout, message, elapsedMs);
    }

    public static SandboxExecutionResult error(String stdout, String error, long elapsedMs) {
        return new SandboxExecutionResult("ERROR", stdout, error, elapsedMs);
    }

    public static SandboxExecutionResult error(String error, long elapsedMs) {
        return error("", error, elapsedMs);
    }

    public static SandboxExecutionResult unavailable(String message, long elapsedMs) {
        return new SandboxExecutionResult("UNAVAILABLE", "", message, elapsedMs);
    }
}
