package com.visionary.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.visionary.sandbox.DockerSandboxExecutor;
import com.visionary.sandbox.SandboxExecutionResult;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Docker-isolated code sandbox service.
 * Executes Python snippets inside a hardened container (no network, capped CPU/RAM, hard timeout).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeSandboxService {

    private static final Pattern PYTHON_BLOCK = Pattern.compile(
            "```(?:python|py)\\s+([\\s\\S]*?)```",
            Pattern.CASE_INSENSITIVE
    );

    private final DockerSandboxExecutor dockerSandboxExecutor;

    public SandboxHealth health() {
        return new SandboxHealth(dockerSandboxExecutor.isReady(), dockerSandboxExecutor.readinessReason());
    }

    public record SandboxHealth(boolean available, String message) {}

    @Data
    public static class SandboxReport {
        private String status;
        private String output;
        private String error;
        private int totalSnippets;
        private int passedSnippets;
        private List<String> issues = new ArrayList<>();

        @JsonProperty("execution_time_ms")
        private long executionTimeMs;

        public SandboxReport() {
        }

        public SandboxReport(String status, int totalSnippets, int passedSnippets, String error, List<String> issues) {
            this.status = status;
            this.totalSnippets = totalSnippets;
            this.passedSnippets = passedSnippets;
            this.error = error;
            this.issues = issues == null ? new ArrayList<>() : new ArrayList<>(issues);
        }

        public String status() {
            return status;
        }

        public int totalSnippets() {
            return totalSnippets;
        }

        public int passedSnippets() {
            return passedSnippets;
        }

        public String toMarkdown() {
            return "\n\n---\n\n**CodeSandbox**: " + status
                    + " (" + passedSnippets + "/" + totalSnippets + " snippets passed)"
                    + (error == null || error.isBlank() ? "" : "\n\n> " + error);
        }
    }

    public SandboxReport validateMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return new SandboxReport("SKIPPED", 0, 0, "No markdown content to validate.", List.of());
        }

        Matcher matcher = PYTHON_BLOCK.matcher(markdown);
        int total = 0;
        int passed = 0;
        List<String> issues = new ArrayList<>();
        long totalElapsed = 0L;

        while (matcher.find()) {
            total++;
            String snippet = matcher.group(1) == null ? "" : matcher.group(1).trim();
            SandboxReport snippetReport = executePythonCode(snippet);
            totalElapsed += snippetReport.getExecutionTimeMs();
            if ("SUCCESS".equalsIgnoreCase(snippetReport.getStatus())) {
                passed++;
            } else {
                String issue = snippetReport.getError() == null || snippetReport.getError().isBlank()
                        ? snippetReport.getStatus()
                        : snippetReport.getError();
                issues.add(issue);
            }
        }

        if (total == 0) {
            SandboxReport direct = executePythonCode(markdown.trim());
            totalElapsed = direct.getExecutionTimeMs();
            if ("SUCCESS".equalsIgnoreCase(direct.getStatus())) {
                SandboxReport report = new SandboxReport("PASSED", 1, 1, "", List.of());
                report.setOutput(direct.getOutput());
                report.setExecutionTimeMs(totalElapsed);
                return report;
            }
            SandboxReport report = new SandboxReport(
                    "UNAVAILABLE".equalsIgnoreCase(direct.getStatus()) ? "UNAVAILABLE" : "ERROR",
                    1,
                    0,
                    direct.getError(),
                    List.of(direct.getError())
            );
            report.setOutput(direct.getOutput());
            report.setExecutionTimeMs(totalElapsed);
            return report;
        }

        String status;
        if (passed == total) {
            status = "PASSED";
        } else if (issues.stream().anyMatch(this::isUnavailableError)) {
            status = "UNAVAILABLE";
        } else {
            status = "ERROR";
        }

        SandboxReport report = new SandboxReport(
                status,
                total,
                passed,
                issues.isEmpty() ? "" : String.join("; ", issues),
                issues
        );
        report.setExecutionTimeMs(totalElapsed);
        return report;
    }

    public SandboxReport executePythonCode(String code) {
        try {
            SandboxExecutionResult result = dockerSandboxExecutor.executePython(code);
            SandboxReport report = new SandboxReport();
            report.setStatus(result.status());
            report.setOutput(result.output());
            report.setError(result.error());
            report.setExecutionTimeMs(result.executionTimeMs());
            report.setTotalSnippets(1);
            report.setPassedSnippets("SUCCESS".equalsIgnoreCase(result.status()) ? 1 : 0);
            if (!"SUCCESS".equalsIgnoreCase(result.status()) && result.error() != null && !result.error().isBlank()) {
                report.getIssues().add(result.error());
            }
            return report;
        } catch (Exception e) {
            log.warn("[Sandbox] Unexpected execution failure: {}", e.getMessage());
            SandboxReport report = new SandboxReport();
            report.setStatus("ERROR");
            report.setError("Sandbox execution failed: " + e.getMessage());
            report.setTotalSnippets(1);
            report.setPassedSnippets(0);
            report.getIssues().add(report.getError());
            return report;
        }
    }

    private boolean isUnavailableError(String issue) {
        String normalized = issue == null ? "" : issue.toLowerCase();
        return normalized.contains("docker engine is unavailable")
                || normalized.contains("sandbox execution skipped")
                || normalized.contains("unavailable");
    }
}
