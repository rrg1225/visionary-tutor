package com.visionary.controller;

import com.visionary.dto.SandboxExecuteRequest;
import com.visionary.service.CodeSandboxService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/sandbox")
@RequiredArgsConstructor
public class SandboxController {

    private final CodeSandboxService codeSandboxService;

    @GetMapping("/health")
    public CodeSandboxService.SandboxHealth health() {
        return codeSandboxService.health();
    }

    @PostMapping("/execute")
    public ResponseEntity<CodeSandboxService.SandboxReport> execute(@Valid @RequestBody SandboxExecuteRequest request) {
        try {
            CodeSandboxService.SandboxReport report = codeSandboxService.executePythonCode(request.getCode());
            if ("UNAVAILABLE".equalsIgnoreCase(report.getStatus())) {
                return ResponseEntity.status(503).body(report);
            }
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.warn("Sandbox execute endpoint degraded: {}", e.getMessage());
            CodeSandboxService.SandboxReport report = new CodeSandboxService.SandboxReport();
            report.setStatus("ERROR");
            report.setError("Sandbox service degraded: " + e.getMessage());
            return ResponseEntity.ok(report);
        }
    }

    @PostMapping("/validate")
    public ResponseEntity<CodeSandboxService.SandboxReport> validate(@Valid @RequestBody SandboxExecuteRequest request) {
        try {
            CodeSandboxService.SandboxReport report = codeSandboxService.validateMarkdown(request.getCode());
            if ("UNAVAILABLE".equalsIgnoreCase(report.getStatus())) {
                return ResponseEntity.status(503).body(report);
            }
            return ResponseEntity.ok(report);
        } catch (Exception e) {
            log.warn("Sandbox validate endpoint degraded: {}", e.getMessage());
            CodeSandboxService.SandboxReport report = new CodeSandboxService.SandboxReport();
            report.setStatus("UNAVAILABLE");
            report.setError("Sandbox validation degraded: " + e.getMessage());
            return ResponseEntity.ok(report);
        }
    }
}
