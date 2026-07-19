package com.visionary.service;

import com.visionary.sandbox.DockerSandboxExecutor;
import com.visionary.sandbox.SandboxExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeSandboxServiceTest {

    @Mock
    private DockerSandboxExecutor dockerSandboxExecutor;

    private CodeSandboxService codeSandboxService;

    @BeforeEach
    void setUp() {
        codeSandboxService = new CodeSandboxService(dockerSandboxExecutor);
    }

    @Test
    void executePythonCodeMapsSuccess() {
        when(dockerSandboxExecutor.executePython("print('ok')"))
                .thenReturn(SandboxExecutionResult.success("ok\n", "", 120L));

        CodeSandboxService.SandboxReport report = codeSandboxService.executePythonCode("print('ok')");

        assertEquals("SUCCESS", report.getStatus());
        assertEquals("ok", report.getOutput().trim());
        assertEquals(1, report.getPassedSnippets());
    }

    @Test
    void executePythonCodeMapsTimeoutWithoutThrowing() {
        when(dockerSandboxExecutor.executePython(anyString()))
                .thenReturn(SandboxExecutionResult.timeout("", "Execution exceeded hard timeout.", 5000L));

        CodeSandboxService.SandboxReport report = codeSandboxService.executePythonCode("while True: pass");

        assertEquals("TIMEOUT", report.getStatus());
        assertTrue(report.getError().contains("timeout"));
        assertEquals(0, report.getPassedSnippets());
    }

    @Test
    void validateMarkdownRunsAllPythonBlocks() {
        when(dockerSandboxExecutor.executePython("print(1)"))
                .thenReturn(SandboxExecutionResult.success("1\n", "", 80L));
        when(dockerSandboxExecutor.executePython("print(2)"))
                .thenReturn(SandboxExecutionResult.error("", "boom", 90L));

        String markdown = """
                ```python
                print(1)
                ```
                ```py
                print(2)
                ```
                """;

        CodeSandboxService.SandboxReport report = codeSandboxService.validateMarkdown(markdown);

        assertEquals("ERROR", report.getStatus());
        assertEquals(2, report.getTotalSnippets());
        assertEquals(1, report.getPassedSnippets());
        assertEquals(1, report.getIssues().size());
    }

    @Test
    void validateMarkdownDegradesWhenDockerUnavailable() {
        when(dockerSandboxExecutor.executePython(anyString()))
                .thenReturn(SandboxExecutionResult.unavailable("Docker engine is unavailable.", 0L));

        CodeSandboxService.SandboxReport report = codeSandboxService.validateMarkdown("print('x')");

        assertEquals("UNAVAILABLE", report.getStatus());
    }
}
